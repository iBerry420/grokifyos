'use strict';

const crypto = require('crypto');
const { spawn, execSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const http = require('http');
const { WebSocketServer } = require('ws');
const mysql = require('mysql2/promise');

/** Prefer GROKIFY_* (GrokifyOS); fall back to GROKPOT_* for monorepo parity. */
function envFirst(...keys) {
    for (const k of keys) {
        const v = process.env[k];
        if (v !== undefined && v !== '') return v;
    }
    return undefined;
}

const WORKSPACE = envFirst('GROKIFY_WORKSPACE', 'GROKPOT_WORKSPACE') || '/root/grokifyos';
require('dotenv').config({ path: path.join(WORKSPACE, '.env') });

const PORT = parseInt(envFirst('GROKIFY_BRIDGE_PORT', 'GROKPOT_BRIDGE_PORT') || '8876', 10);
const INSTANCE_ID = envFirst('GROKIFY_BRIDGE_INSTANCE', 'GROKPOT_BRIDGE_INSTANCE') || 'a';
const GROK_BIN = envFirst('GROKIFY_GROK_BIN', 'GROKPOT_GROK_BIN') || '/root/.grok/bin/grok';
const DEFAULT_GROK_MODEL = envFirst('GROKIFY_GROK_DEFAULT_MODEL', 'GROKPOT_GROK_DEFAULT_MODEL') || 'grok-4.6';
/** Preferred headless CLI reasoning effort (clamped per model).
 * grok-4.6: low | medium | high | xhigh
 * grok-4.5: low | medium | high  (CLI rejects xhigh) */
const REASONING_EFFORT = envFirst('GROKIFY_REASONING_EFFORT', 'GROKPOT_REASONING_EFFORT') || 'xhigh';
const {
    defaultEffortForModel,
    resolveReasoningEffort,
    decorateModels,
} = require('./reasoning-effort');
const {
    MAX_PROMPT_BYTES,
    fitPromptContext,
    shouldUsePromptFile,
} = require('./prompt-context');
const LOG_FILE = path.join(WORKSPACE, 'storage', 'logs', 'bridge.log');
const AGENT_TIMEOUT_MS = 30 * 60 * 1000;
// When true (default), CLI agents are detached + file-tailed so they survive bridge restarts
const DETACH_AGENTS = (envFirst('GROKIFY_BRIDGE_DETACH', 'GROKPOT_BRIDGE_DETACH') || '1') !== '0';
function wsSecret() {
    const explicit = envFirst('GROKIFY_WS_AUTH_SECRET', 'GROKPOT_WS_AUTH_SECRET');
    if (explicit) return explicit;
    const pepper = envFirst('GROKIFY_SECRETS_PEPPER', 'GROKPOT_SECRETS_PEPPER') || '';
    if (pepper) return crypto.createHash('sha256').update('grokifyos_system_chat_ws:' + pepper).digest('hex');
    return crypto.createHash('sha256').update('grokifyos_system_chat_ws_fallback').digest('hex');
}
const WS_SECRET = wsSecret();

const DB_SOCKET = envFirst('GROKIFY_DB_SOCKET', 'GROKPOT_DB_SOCKET') || '/var/run/mysqld/mysqld.sock';

/** Match PHP PDO: localhost / 127.0.0.1 use the Unix socket, not TCP loopback. */
function buildDbConfig() {
    const base = {
        user: envFirst('GROKIFY_DB_USER', 'GROKPOT_DB_USER') || 'grokifyos',
        password: envFirst('GROKIFY_DB_PASS', 'GROKPOT_DB_PASS') || '',
        database: envFirst('GROKIFY_DB_NAME', 'GROKPOT_DB_NAME') || 'grokifyos',
    };
    const host = (envFirst('GROKIFY_DB_HOST', 'GROKPOT_DB_HOST', 'GROKPOT_DB_HOSTNAME') || '').trim();
    if (!host || host === 'localhost' || host === '127.0.0.1') {
        return { ...base, socketPath: DB_SOCKET };
    }
    return {
        ...base,
        host,
        port: parseInt(envFirst('GROKIFY_DB_PORT', 'GROKPOT_DB_PORT') || '3306', 10),
    };
}

const DB_CONFIG = buildDbConfig();

let ALLOWED_MODELS = new Set();
let GROK_MODELS_FULL = [];
let grokModelsLoadedAt = 0;

const agents = new Map();
/** In-memory CLI usage scan (invalidated after each agent harvest). */
let usageTrackerMem = { at: 0, sessions: null, scanned: 0 };

/** app_settings key: agent process cwd (empty/missing → WORKSPACE install path). */
const SETTING_AGENT_CWD = 'bridge_agent_cwd';
/** In-memory cache so multi-worker lag is short without a DB hit every spawn. */
let agentCwdCache = { path: null, loadedAt: 0 };
const AGENT_CWD_CACHE_MS = 10000;

const { createRuntime } = require('./agent-runtime');
const runtime = createRuntime({
    workspace: WORKSPACE,
    instanceId: INSTANCE_ID,
    log: (level, category, msg, ctx) => log(level, category, msg, ctx),
});

const { createMediaIngest } = require('./media-ingest');
const mediaIngest = createMediaIngest({
    workspace: WORKSPACE,
    log: (level, category, msg, ctx) => log(level, category, msg, ctx),
});
const usageHarvest = require('./usage-harvest');

function ensureLogDir() {
    const dir = path.dirname(LOG_FILE);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
}

function redactSecrets(text) {
    if (typeof text !== 'string') return text;
    text = text.replace(/0x[0-9a-fA-F]{64}\b/g, '[REDACTED_KEY]');
    text = text.replace(/\b[0-9a-fA-F]{64}\b/g, '[REDACTED_KEY]');
    return text;
}

function log(level, category, msg, ctx) {
    ensureLogDir();
    const line = JSON.stringify({
        ts: new Date().toISOString(),
        level,
        category,
        msg: redactSecrets(msg),
        ctx: ctx ? redactSecrets(JSON.stringify(ctx)) : undefined,
    });
    try { fs.appendFileSync(LOG_FILE, line + '\n'); } catch (_) {}
    auditDb(level, category, msg, ctx || {}, ctx?.user_id, ctx?.session_id).catch(() => {});
}

async function auditDb(level, category, message, context, userId, sessionId) {
    let conn;
    try {
        conn = await mysql.createConnection(DB_CONFIG);
        await conn.execute(
            `INSERT INTO system_chat_events (level, category, message, context, user_id, session_id)
             VALUES (?, ?, ?, ?, ?, ?)`,
            [
                level,
                category,
                String(message).substring(0, 512),
                Object.keys(context).length ? JSON.stringify(context) : null,
                userId || null,
                sessionId || null,
            ]
        );
    } catch (_) {
        /* table may not exist yet */
    } finally {
        if (conn) await conn.end();
    }
}

function verifyWsToken(token) {
    if (!token || typeof token !== 'string') return null;
    const parts = token.split('.');
    if (parts.length !== 2) return null;
    let json;
    try {
        json = Buffer.from(parts[0], 'base64').toString('utf8');
    } catch {
        return null;
    }
    const expected = crypto.createHmac('sha256', WS_SECRET).update(json).digest('hex');
    try {
        if (!crypto.timingSafeEqual(Buffer.from(expected, 'hex'), Buffer.from(parts[1], 'hex'))) return null;
    } catch {
        if (expected !== parts[1]) return null;
    }
    let data;
    try { data = JSON.parse(json); } catch { return null; }
    if (!data.uid || !data.exp || data.exp < Math.floor(Date.now() / 1000)) return null;
    return { uid: parseInt(data.uid, 10), role: data.role || '' };
}

function refreshGrokModels() {
    try {
        const raw = execSync(`"${GROK_BIN}" models 2>/dev/null`, {
            timeout: 20000,
            env: { ...process.env, HOME: process.env.HOME || '/root' },
        }).toString();
        const models = [];
        const nextAllowed = new Set();
        for (const line of raw.split('\n')) {
            // Only bullet lines under "Available models:" — not the "You are logged in..." banner
            const match = line.match(/^\s*[-*]\s+([a-z0-9][a-z0-9._-]+)(?:\s+\(default\))?\s*$/i);
            if (!match) continue;
            const id = match[1].trim();
            if (!id || !id.includes('-')) continue;
            models.push({ id, name: id });
            nextAllowed.add('gb:' + id);
        }
        if (models.length) {
            GROK_MODELS_FULL = decorateModels(models, REASONING_EFFORT);
            ALLOWED_MODELS = nextAllowed;
            grokModelsLoadedAt = Date.now();
            log('info', 'agent', `Loaded ${models.length} Grok Build models`, { sample: models.map((m) => m.id) });
        }
    } catch (err) {
        log('warning', 'error', `Grok model list failed: ${err.message}`, {});
    }
}

function refreshGrokModelsIfStale(maxAgeMs = 60000) {
    if (!GROK_MODELS_FULL.length || Date.now() - grokModelsLoadedAt > maxAgeMs) {
        refreshGrokModels();
    }
}

/** Accept gb:grok-4.6, grok:grok-4.6, or a raw future id from `grok models`. */
function resolveCompleteModel(model) {
    let real = grokRealModel(model);
    if (!real || real === 'undefined') real = DEFAULT_GROK_MODEL;
    if (GROK_MODELS_FULL.length && !GROK_MODELS_FULL.some((m) => m.id === real)) {
        if (!/^[a-z0-9][a-z0-9._-]{1,79}$/i.test(real)) {
            real = GROK_MODELS_FULL[0]?.id || DEFAULT_GROK_MODEL;
        }
    }
    return real;
}

function stripAnsi(s) {
    return String(s || '').replace(/\x1B\[[0-9;]*[A-Za-z]/g, '');
}

function extractCompleteText(raw, json) {
    const s = String(raw || '').trim();
    if (!s) return '';
    if (!json) return s;
    const start = s.indexOf('{');
    const end = s.lastIndexOf('}');
    const slice = start >= 0 && end > start ? s.slice(start, end + 1) : s;
    try {
        const parsed = JSON.parse(slice);
        if (parsed && typeof parsed === 'object') {
            if (typeof parsed.result === 'string' && parsed.result.trim()) return parsed.result.trim();
            if (typeof parsed.text === 'string' && parsed.text.trim()) return parsed.text.trim();
            if (Array.isArray(parsed.tags)) return JSON.stringify({ tags: parsed.tags });
            return slice;
        }
    } catch (_) {}
    return s;
}

function defaultAgentCwd() {
    return path.resolve(WORKSPACE);
}

/**
 * Normalize + validate an absolute directory path for agent cwd.
 * @returns {string} resolved real path
 * @throws {Error} with short code-ish message
 */
function validateAgentCwd(input) {
    if (input == null || typeof input !== 'string') {
        throw new Error('path_required');
    }
    let p = input.trim();
    if (!p) throw new Error('path_required');
    if (p.includes('\0')) throw new Error('invalid_path');
    if (!path.isAbsolute(p)) throw new Error('path_must_be_absolute');
    // Collapse . / .. without following symlinks first
    p = path.resolve(p);
    if (!fs.existsSync(p)) throw new Error('path_not_found');
    let real;
    try {
        real = fs.realpathSync(p);
    } catch {
        throw new Error('path_not_found');
    }
    let st;
    try {
        st = fs.statSync(real);
    } catch {
        throw new Error('path_not_found');
    }
    if (!st.isDirectory()) throw new Error('not_a_directory');
    return real;
}

async function loadAgentCwdFromDb() {
    let conn;
    try {
        conn = await mysql.createConnection(DB_CONFIG);
        const [rows] = await conn.execute(
            'SELECT setting_value FROM app_settings WHERE setting_key = ? LIMIT 1',
            [SETTING_AGENT_CWD]
        );
        if (rows && rows[0] && typeof rows[0].setting_value === 'string') {
            return rows[0].setting_value.trim();
        }
        return '';
    } catch {
        return '';
    } finally {
        if (conn) await conn.end();
    }
}

async function saveAgentCwdToDb(value) {
    let conn;
    try {
        conn = await mysql.createConnection(DB_CONFIG);
        if (value === '' || value == null) {
            await conn.execute('DELETE FROM app_settings WHERE setting_key = ?', [SETTING_AGENT_CWD]);
        } else {
            await conn.execute(
                `INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?)
                 ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = CURRENT_TIMESTAMP`,
                [SETTING_AGENT_CWD, value]
            );
        }
        return true;
    } catch (err) {
        log('warning', 'error', `save agent cwd failed: ${err.message}`, {});
        return false;
    } finally {
        if (conn) await conn.end();
    }
}

/**
 * Resolve effective agent working directory (default = bridge WORKSPACE).
 * Cached briefly so multi-worker set lag is short without DB on every spawn.
 */
async function getAgentCwd({ force = false } = {}) {
    const now = Date.now();
    if (!force && agentCwdCache.path && (now - agentCwdCache.loadedAt) < AGENT_CWD_CACHE_MS) {
        return agentCwdCache.path;
    }
    const fromDb = await loadAgentCwdFromDb();
    let resolved = defaultAgentCwd();
    if (fromDb) {
        try {
            resolved = validateAgentCwd(fromDb);
        } catch {
            // Stale/missing path → fall back to install workspace
            resolved = defaultAgentCwd();
        }
    }
    agentCwdCache = { path: resolved, loadedAt: now };
    return resolved;
}

/**
 * Set or reset agent cwd. Empty / reset → default workspace (clears setting).
 * @returns {{ ok: true, path: string, default_path: string, is_default: boolean } | { ok: false, error: string }}
 */
async function setAgentCwd(rawPath, { reset = false } = {}) {
    const def = defaultAgentCwd();
    if (reset || rawPath === '' || rawPath == null) {
        const saved = await saveAgentCwdToDb('');
        if (!saved) return { ok: false, error: 'db_write_failed' };
        agentCwdCache = { path: def, loadedAt: Date.now() };
        log('info', 'access', 'Agent working directory reset to default', { path: def });
        return { ok: true, path: def, default_path: def, is_default: true };
    }
    let validated;
    try {
        validated = validateAgentCwd(String(rawPath));
    } catch (err) {
        return { ok: false, error: err.message || 'invalid_path' };
    }
    // Store default as empty so install moves stay default
    const isDefault = path.resolve(validated) === path.resolve(def);
    const saved = await saveAgentCwdToDb(isDefault ? '' : validated);
    if (!saved) return { ok: false, error: 'db_write_failed' };
    agentCwdCache = { path: validated, loadedAt: Date.now() };
    log('info', 'access', 'Agent working directory updated', {
        path: validated,
        is_default: isDefault,
    });
    return {
        ok: true,
        path: validated,
        default_path: def,
        is_default: isDefault,
    };
}

/**
 * List immediate child directories for the picker UI.
 */
function listWorkDirEntries(dirPath) {
    let base;
    try {
        base = validateAgentCwd(dirPath || defaultAgentCwd());
    } catch (err) {
        return { ok: false, error: err.message || 'invalid_path' };
    }
    const parent = path.dirname(base);
    let names = [];
    try {
        names = fs.readdirSync(base, { withFileTypes: true });
    } catch (err) {
        return { ok: false, error: 'read_failed', message: err.message };
    }
    const entries = [];
    for (const ent of names) {
        if (!ent.isDirectory()) continue;
        // Skip common noisy / unusable dirs
        const name = ent.name;
        if (name === '.' || name === '..') continue;
        entries.push({
            name,
            path: path.join(base, name),
        });
    }
    entries.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
    return {
        ok: true,
        path: base,
        parent: parent !== base ? parent : null,
        default_path: defaultAgentCwd(),
        entries,
    };
}

function readRequestBody(req, limit = 65536) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        let size = 0;
        req.on('data', (c) => {
            size += c.length;
            if (size > limit) {
                reject(new Error('body_too_large'));
                req.destroy();
                return;
            }
            chunks.push(c);
        });
        req.on('end', () => {
            resolve(Buffer.concat(chunks).toString('utf8'));
        });
        req.on('error', reject);
    });
}

refreshGrokModels();
setInterval(refreshGrokModels, 6 * 60 * 60 * 1000);

/**
 * Keep storage/grok-auth.json (www-data readable) in sync with the live CLI
 * auth at ~/.grok/auth.json. PHP-FPM cannot read root's auth.json, so after
 * `grok login` the usage API shows "re-login needed" until this copies over.
 */
function grokAuthSrcPath() {
    return process.env.GROK_AUTH_SRC
        || path.join(process.env.HOME || '/root', '.grok', 'auth.json');
}

function grokAuthDestPath() {
    const destDefault = path.join(WORKSPACE, 'storage', 'grok-auth.json');
    let dest = envFirst('GROKIFY_GROK_AUTH_JSON', 'GROKPOT_GROK_AUTH_JSON') || destDefault;
    const src = grokAuthSrcPath();
    try {
        if (fs.realpathSync(src) === fs.realpathSync(dest)) dest = destDefault;
    } catch (_) {
        if (path.resolve(src) === path.resolve(dest)) dest = destDefault;
    }
    return dest;
}

function firstAuthEntryFromRaw(raw) {
    const data = JSON.parse(raw);
    if (!data || typeof data !== 'object') return null;
    for (const key of Object.keys(data)) {
        const entry = data[key];
        if (!entry || typeof entry !== 'object') continue;
        if (entry.key || entry.access_token) return { key, entry };
    }
    return null;
}

function authNeedsSync(srcPath, destPath) {
    if (!fs.existsSync(srcPath)) return { needed: false, reason: 'no_src' };
    if (!fs.existsSync(destPath)) return { needed: true, reason: 'no_dest' };
    try {
        const src = firstAuthEntryFromRaw(fs.readFileSync(srcPath, 'utf8'));
        const dest = firstAuthEntryFromRaw(fs.readFileSync(destPath, 'utf8'));
        if (!src) return { needed: false, reason: 'src_empty' };
        if (!dest) return { needed: true, reason: 'dest_empty' };
        const srcRefresh = String(src.entry.refresh_token || '');
        const destRefresh = String(dest.entry.refresh_token || '');
        if (srcRefresh && destRefresh && srcRefresh !== destRefresh) {
            return { needed: true, reason: 'refresh_token_mismatch' };
        }
        const srcToken = String(src.entry.key || src.entry.access_token || '');
        const destToken = String(dest.entry.key || dest.entry.access_token || '');
        if (srcToken && destToken && srcToken !== destToken) {
            const srcExp = Date.parse(src.entry.expires_at || '');
            const destExp = Date.parse(dest.entry.expires_at || '');
            if (!Number.isNaN(srcExp) && (Number.isNaN(destExp) || srcExp > destExp + 5000)) {
                return { needed: true, reason: 'src_fresher' };
            }
        }
        const destExp = Date.parse(dest.entry.expires_at || '');
        if (!Number.isNaN(destExp) && destExp <= Date.now() + 120000) {
            // Dest expired but same refresh token → PHP OIDC refresh can heal itself.
            if (srcRefresh && destRefresh && srcRefresh === destRefresh) {
                return { needed: false, reason: 'same_refresh_php_can_refresh' };
            }
            // Dest expired and no usable matching refresh → pull from CLI.
            return { needed: true, reason: 'dest_expired' };
        }
        return { needed: false, reason: 'in_sync' };
    } catch (err) {
        return { needed: true, reason: 'compare_error:' + (err.message || 'unknown') };
    }
}

function syncGrokAuthIfNeeded(force = false) {
    const src = grokAuthSrcPath();
    const dest = grokAuthDestPath();
    if (path.resolve(src) === path.resolve(dest)) {
        return { ok: false, error: 'same_path', src, dest };
    }
    if (!fs.existsSync(src)) {
        return { ok: false, error: 'src_missing', src, dest };
    }
    const check = force ? { needed: true, reason: 'forced' } : authNeedsSync(src, dest);
    if (!check.needed) {
        return { ok: true, synced: false, reason: check.reason, src, dest };
    }
    try {
        fs.mkdirSync(path.dirname(dest), { recursive: true });
        // Atomic-ish: write temp then rename so PHP never reads a partial file.
        const tmp = dest + '.tmp.' + process.pid;
        fs.copyFileSync(src, tmp);
        try {
            const uid = Number(execSync('id -u www-data', { encoding: 'utf8' }).trim());
            const gid = Number(execSync('id -g www-data', { encoding: 'utf8' }).trim());
            fs.chownSync(tmp, uid, gid);
        } catch (_) { /* non-Linux / no www-data — leave ownership as-is */ }
        try { fs.chmodSync(tmp, 0o640); } catch (_) {}
        fs.renameSync(tmp, dest);
        try {
            const uid = Number(execSync('id -u www-data', { encoding: 'utf8' }).trim());
            const gid = Number(execSync('id -g www-data', { encoding: 'utf8' }).trim());
            fs.chownSync(dest, uid, gid);
            fs.chmodSync(dest, 0o640);
        } catch (_) {}
        log('info', 'auth', 'Synced Grok CLI auth → PHP-readable path', {
            src,
            dest,
            reason: check.reason,
            instance: INSTANCE_ID,
        });
        return { ok: true, synced: true, reason: check.reason, src, dest };
    } catch (err) {
        log('warning', 'auth', `Grok auth sync failed: ${err.message}`, { src, dest });
        return { ok: false, error: err.message || 'sync_failed', src, dest };
    }
}

// Startup + periodic self-heal (CLI login rotates tokens without telling PHP).
syncGrokAuthIfNeeded(false);
setInterval(() => {
    try { syncGrokAuthIfNeeded(false); } catch (_) {}
}, 60 * 1000);

/**
 * Headless OIDC device-code login for Grok Build (same client as `grok login --device-code`).
 * When refresh tokens die, PHP/app can start this flow and show verification_uri_complete
 * so the user only taps the link, signs in, and approves — no SSH required.
 */
const GROK_OIDC_CLIENT_ID = process.env.GROKIFY_OIDC_CLIENT_ID
    || 'b1a00492-073a-47ea-816f-4c329264a828';
const GROK_OIDC_ISSUER = process.env.GROKIFY_OIDC_ISSUER || 'https://auth.x.ai';
const GROK_OIDC_DEVICE_URL = `${GROK_OIDC_ISSUER}/oauth2/device/code`;
const GROK_OIDC_TOKEN_URL = `${GROK_OIDC_ISSUER}/oauth2/token`;
const GROK_OIDC_USERINFO_URL = `${GROK_OIDC_ISSUER}/oauth2/userinfo`;
const GROK_OIDC_SCOPES = process.env.GROKIFY_OIDC_SCOPES
    || 'openid profile email offline_access grok-cli:access api:access conversations:read conversations:write';
const GROK_DEVICE_LOGIN_STATE_PATH = process.env.GROKIFY_DEVICE_LOGIN_STATE
    || path.join(process.env.HOME || '/root', '.grok', 'device-login.json');

let deviceLoginPollTimer = null;
let deviceLoginPollInFlight = false;

function publicDeviceLoginView(state) {
    if (!state || typeof state !== 'object') {
        return { ok: true, status: 'idle', needed: false };
    }
    const expiresAt = state.expires_at ? Date.parse(state.expires_at) : NaN;
    const expiresIn = Number.isNaN(expiresAt)
        ? null
        : Math.max(0, Math.floor((expiresAt - Date.now()) / 1000));
    return {
        ok: true,
        needed: state.status === 'pending' || state.status === 'error' || state.status === 'denied',
        status: state.status || 'idle',
        user_code: state.user_code || null,
        verification_uri: state.verification_uri || null,
        verification_uri_complete: state.verification_uri_complete || null,
        expires_in: expiresIn,
        expires_at: state.expires_at || null,
        interval: state.interval || 5,
        started_at: state.started_at || null,
        completed_at: state.completed_at || null,
        error: state.error || null,
        error_description: state.error_description || null,
        email: state.email || null,
        message: deviceLoginMessage(state),
    };
}

function deviceLoginMessage(state) {
    if (!state) return 'Grok Build login idle.';
    switch (state.status) {
        case 'pending':
            return 'Open the link, sign in with xAI/Grok, and approve this device.';
        case 'complete':
            return state.email
                ? `Signed in as ${state.email}. Usage should refresh automatically.`
                : 'Signed in. Usage should refresh automatically.';
        case 'denied':
            return 'Login was denied. Tap re-login to try again.';
        case 'expired':
            return 'Login link expired. Tap re-login for a fresh link.';
        case 'error':
            return state.error_description
                || state.error
                || 'Login failed. Tap re-login to try again.';
        default:
            return 'Grok Build login idle.';
    }
}

function readDeviceLoginState() {
    try {
        if (!fs.existsSync(GROK_DEVICE_LOGIN_STATE_PATH)) return null;
        const raw = fs.readFileSync(GROK_DEVICE_LOGIN_STATE_PATH, 'utf8');
        const data = JSON.parse(raw);
        return data && typeof data === 'object' ? data : null;
    } catch (_) {
        return null;
    }
}

function writeDeviceLoginState(state) {
    const dir = path.dirname(GROK_DEVICE_LOGIN_STATE_PATH);
    fs.mkdirSync(dir, { recursive: true });
    const tmp = GROK_DEVICE_LOGIN_STATE_PATH + '.tmp.' + process.pid;
    fs.writeFileSync(tmp, JSON.stringify(state, null, 2), { mode: 0o600 });
    try { fs.chmodSync(tmp, 0o600); } catch (_) {}
    fs.renameSync(tmp, GROK_DEVICE_LOGIN_STATE_PATH);
    try { fs.chmodSync(GROK_DEVICE_LOGIN_STATE_PATH, 0o600); } catch (_) {}
}

function clearDeviceLoginPollTimer() {
    if (deviceLoginPollTimer) {
        clearTimeout(deviceLoginPollTimer);
        deviceLoginPollTimer = null;
    }
}

function scheduleDeviceLoginPoll(delayMs) {
    clearDeviceLoginPollTimer();
    const ms = Math.max(2000, Number(delayMs) || 5000);
    deviceLoginPollTimer = setTimeout(() => {
        deviceLoginPollTimer = null;
        pollDeviceLoginOnce().catch((err) => {
            log('warning', 'auth', `Device login poll error: ${err.message || err}`);
        });
    }, ms);
}

function httpFormPost(url, fields, timeoutMs = 20000) {
    return new Promise((resolve, reject) => {
        const body = new URLSearchParams(fields).toString();
        const u = new URL(url);
        const lib = u.protocol === 'https:' ? require('https') : http;
        const req = lib.request({
            protocol: u.protocol,
            hostname: u.hostname,
            port: u.port || (u.protocol === 'https:' ? 443 : 80),
            path: u.pathname + u.search,
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                Accept: 'application/json',
                'Content-Length': Buffer.byteLength(body),
                'User-Agent': 'grokifyos-bridge/device-login',
            },
            timeout: timeoutMs,
        }, (res) => {
            let data = '';
            res.on('data', (c) => { data += c; });
            res.on('end', () => {
                let json = null;
                try { json = data ? JSON.parse(data) : null; } catch (_) {}
                resolve({ status: res.statusCode || 0, body: data, json });
            });
        });
        req.on('error', reject);
        req.on('timeout', () => {
            req.destroy();
            reject(new Error('timeout'));
        });
        req.write(body);
        req.end();
    });
}

function httpGetJson(url, headers = {}, timeoutMs = 15000) {
    return new Promise((resolve, reject) => {
        const u = new URL(url);
        const lib = u.protocol === 'https:' ? require('https') : http;
        const req = lib.request({
            protocol: u.protocol,
            hostname: u.hostname,
            port: u.port || (u.protocol === 'https:' ? 443 : 80),
            path: u.pathname + u.search,
            method: 'GET',
            headers: {
                Accept: 'application/json',
                'User-Agent': 'grokifyos-bridge/device-login',
                ...headers,
            },
            timeout: timeoutMs,
        }, (res) => {
            let data = '';
            res.on('data', (c) => { data += c; });
            res.on('end', () => {
                let json = null;
                try { json = data ? JSON.parse(data) : null; } catch (_) {}
                resolve({ status: res.statusCode || 0, body: data, json });
            });
        });
        req.on('error', reject);
        req.on('timeout', () => {
            req.destroy();
            reject(new Error('timeout'));
        });
        req.end();
    });
}

function decodeJwtPayload(token) {
    try {
        const parts = String(token || '').split('.');
        if (parts.length < 2) return null;
        let b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
        while (b64.length % 4) b64 += '=';
        return JSON.parse(Buffer.from(b64, 'base64').toString('utf8'));
    } catch (_) {
        return null;
    }
}

function isoNow() {
    return new Date().toISOString();
}

function expiresAtFromSeconds(expiresIn) {
    const sec = Math.max(60, Number(expiresIn) || 21600);
    return new Date(Date.now() + sec * 1000).toISOString();
}

async function persistDeviceLoginTokens(tokenJson) {
    const accessToken = String(tokenJson.access_token || '');
    const refreshToken = String(tokenJson.refresh_token || '');
    if (!accessToken) throw new Error('token_missing_access_token');

    let email = null;
    let firstName = null;
    let picture = null;
    try {
        const ui = await httpGetJson(GROK_OIDC_USERINFO_URL, {
            Authorization: `Bearer ${accessToken}`,
        });
        if (ui.json && typeof ui.json === 'object') {
            email = ui.json.email || null;
            firstName = ui.json.given_name || ui.json.name || null;
            picture = ui.json.picture || null;
        }
    } catch (err) {
        log('warning', 'auth', `userinfo failed after device login: ${err.message || err}`);
    }

    const claims = decodeJwtPayload(accessToken) || {};
    const sub = String(claims.sub || claims.principal_id || '');
    const teamId = claims.team_id ? String(claims.team_id) : null;
    const entryKey = `${GROK_OIDC_ISSUER}::${GROK_OIDC_CLIENT_ID}`;
    const entry = {
        key: accessToken,
        auth_mode: 'oidc',
        create_time: isoNow(),
        user_id: sub || null,
        email: email || null,
        first_name: firstName || null,
        profile_image_asset_id: picture || null,
        principal_type: claims.principal_type || 'User',
        principal_id: sub || null,
        team_id: teamId,
        coding_data_retention_opt_out: false,
        refresh_token: refreshToken || undefined,
        expires_at: expiresAtFromSeconds(tokenJson.expires_in),
        oidc_issuer: GROK_OIDC_ISSUER,
        oidc_client_id: GROK_OIDC_CLIENT_ID,
    };
    if (!entry.refresh_token) delete entry.refresh_token;

    const src = grokAuthSrcPath();
    fs.mkdirSync(path.dirname(src), { recursive: true });
    const tmp = src + '.tmp.' + process.pid;
    fs.writeFileSync(tmp, JSON.stringify({ [entryKey]: entry }, null, 2) + '\n', { mode: 0o600 });
    try { fs.chmodSync(tmp, 0o600); } catch (_) {}
    fs.renameSync(tmp, src);
    try { fs.chmodSync(src, 0o600); } catch (_) {}

    const sync = syncGrokAuthIfNeeded(true);
    log('info', 'auth', 'Device login completed — wrote auth.json + synced for PHP', {
        email,
        sync_ok: !!(sync && sync.ok),
        sync_reason: sync && sync.reason,
    });
    return { email, entryKey, sync };
}

async function startDeviceLogin(forceNew = false) {
    const existing = readDeviceLoginState();
    if (!forceNew && existing && existing.status === 'pending' && existing.device_code) {
        const exp = existing.expires_at ? Date.parse(existing.expires_at) : 0;
        if (exp > Date.now() + 30000) {
            scheduleDeviceLoginPoll((existing.interval || 5) * 1000);
            return publicDeviceLoginView(existing);
        }
    }

    const resp = await httpFormPost(GROK_OIDC_DEVICE_URL, {
        client_id: GROK_OIDC_CLIENT_ID,
        scope: GROK_OIDC_SCOPES,
    });
    if (!resp.json || !resp.json.device_code) {
        const err = (resp.json && (resp.json.error_description || resp.json.error))
            || `device_code_http_${resp.status}`;
        const state = {
            status: 'error',
            error: 'device_code_request_failed',
            error_description: String(err).slice(0, 400),
            started_at: isoNow(),
        };
        writeDeviceLoginState(state);
        return publicDeviceLoginView(state);
    }

    const interval = Math.max(3, Number(resp.json.interval) || 5);
    const expiresIn = Math.max(60, Number(resp.json.expires_in) || 1800);
    const state = {
        status: 'pending',
        device_code: String(resp.json.device_code),
        user_code: String(resp.json.user_code || ''),
        verification_uri: String(resp.json.verification_uri || 'https://accounts.x.ai/oauth2/device'),
        verification_uri_complete: String(
            resp.json.verification_uri_complete
            || `${resp.json.verification_uri || 'https://accounts.x.ai/oauth2/device'}?user_code=${resp.json.user_code || ''}`
        ),
        interval,
        expires_at: expiresAtFromSeconds(expiresIn),
        started_at: isoNow(),
        poll_owner: INSTANCE_ID,
    };
    writeDeviceLoginState(state);
    log('info', 'auth', 'Device login started', {
        user_code: state.user_code,
        expires_in: expiresIn,
        instance: INSTANCE_ID,
    });
    scheduleDeviceLoginPoll(interval * 1000);
    return publicDeviceLoginView(state);
}

async function pollDeviceLoginOnce() {
    if (deviceLoginPollInFlight) return publicDeviceLoginView(readDeviceLoginState());
    deviceLoginPollInFlight = true;
    try {
        const state = readDeviceLoginState();
        if (!state || state.status !== 'pending' || !state.device_code) {
            return publicDeviceLoginView(state);
        }
        const exp = state.expires_at ? Date.parse(state.expires_at) : 0;
        if (exp && exp <= Date.now()) {
            const expired = {
                ...state,
                status: 'expired',
                device_code: undefined,
                error: 'expired_token',
                error_description: 'Device login code expired',
            };
            delete expired.device_code;
            writeDeviceLoginState(expired);
            clearDeviceLoginPollTimer();
            return publicDeviceLoginView(expired);
        }

        const resp = await httpFormPost(GROK_OIDC_TOKEN_URL, {
            grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
            device_code: state.device_code,
            client_id: GROK_OIDC_CLIENT_ID,
        });
        const errCode = resp.json && resp.json.error ? String(resp.json.error) : '';
        if (errCode === 'authorization_pending') {
            scheduleDeviceLoginPoll((state.interval || 5) * 1000);
            return publicDeviceLoginView(state);
        }
        if (errCode === 'slow_down') {
            const next = (state.interval || 5) + 5;
            state.interval = next;
            writeDeviceLoginState(state);
            scheduleDeviceLoginPoll(next * 1000);
            return publicDeviceLoginView(state);
        }
        if (errCode === 'access_denied') {
            const denied = {
                status: 'denied',
                user_code: state.user_code,
                verification_uri: state.verification_uri,
                verification_uri_complete: state.verification_uri_complete,
                started_at: state.started_at,
                completed_at: isoNow(),
                error: 'access_denied',
                error_description: (resp.json && resp.json.error_description) || 'User denied login',
            };
            writeDeviceLoginState(denied);
            clearDeviceLoginPollTimer();
            return publicDeviceLoginView(denied);
        }
        if (errCode === 'expired_token') {
            const expired = {
                status: 'expired',
                user_code: state.user_code,
                started_at: state.started_at,
                completed_at: isoNow(),
                error: 'expired_token',
                error_description: (resp.json && resp.json.error_description) || 'Device code expired',
            };
            writeDeviceLoginState(expired);
            clearDeviceLoginPollTimer();
            return publicDeviceLoginView(expired);
        }
        if (!resp.json || !resp.json.access_token) {
            const failed = {
                status: 'error',
                user_code: state.user_code,
                started_at: state.started_at,
                completed_at: isoNow(),
                error: errCode || `token_http_${resp.status}`,
                error_description: (resp.json && resp.json.error_description)
                    || String(resp.body || '').slice(0, 300)
                    || 'Token exchange failed',
            };
            // Keep retrying transient errors while the code is still valid.
            if (resp.status >= 500 || resp.status === 0) {
                scheduleDeviceLoginPoll((state.interval || 5) * 1000);
                return publicDeviceLoginView(state);
            }
            writeDeviceLoginState(failed);
            clearDeviceLoginPollTimer();
            return publicDeviceLoginView(failed);
        }

        const saved = await persistDeviceLoginTokens(resp.json);
        const complete = {
            status: 'complete',
            user_code: state.user_code,
            started_at: state.started_at,
            completed_at: isoNow(),
            email: saved.email || null,
            message: saved.email ? `Signed in as ${saved.email}` : 'Signed in',
        };
        writeDeviceLoginState(complete);
        clearDeviceLoginPollTimer();
        return publicDeviceLoginView(complete);
    } finally {
        deviceLoginPollInFlight = false;
    }
}

async function getDeviceLoginStatus({ startIfNeeded = false, forceNew = false } = {}) {
    let state = readDeviceLoginState();
    if (startIfNeeded) {
        if (!state || state.status !== 'pending' || forceNew) {
            return startDeviceLogin(forceNew);
        }
        const exp = state.expires_at ? Date.parse(state.expires_at) : 0;
        if (!exp || exp <= Date.now() + 30000) {
            return startDeviceLogin(true);
        }
    }
    if (state && state.status === 'pending') {
        // Opportunistic poll on status reads so multi-worker HA keeps progress even if
        // the original poll timer lives on another process.
        try {
            return await pollDeviceLoginOnce();
        } catch (err) {
            scheduleDeviceLoginPoll((state.interval || 5) * 1000);
            return {
                ...publicDeviceLoginView(state),
                poll_error: err.message || String(err),
            };
        }
    }
    return publicDeviceLoginView(state);
}

/**
 * Wipe CLI + PHP-readable Grok OAuth so a fresh device-code login can switch accounts.
 * Does not revoke tokens at xAI — only clears local credentials.
 */
function clearGrokAuthFiles() {
    const src = grokAuthSrcPath();
    const dest = grokAuthDestPath();
    const cleared = [];
    const errors = [];

    function wipeAuthFile(p, { wwwData = false } = {}) {
        if (!p) return;
        try {
            fs.mkdirSync(path.dirname(p), { recursive: true });
            const tmp = p + '.tmp.' + process.pid;
            // Empty object = no usable entry (same shape CLI/PHP already handle).
            fs.writeFileSync(tmp, '{}\n', { mode: wwwData ? 0o640 : 0o600 });
            if (wwwData) {
                try {
                    const uid = Number(execSync('id -u www-data', { encoding: 'utf8' }).trim());
                    const gid = Number(execSync('id -g www-data', { encoding: 'utf8' }).trim());
                    fs.chownSync(tmp, uid, gid);
                } catch (_) { /* non-Linux / no www-data */ }
            }
            fs.renameSync(tmp, p);
            if (wwwData) {
                try {
                    const uid = Number(execSync('id -u www-data', { encoding: 'utf8' }).trim());
                    const gid = Number(execSync('id -g www-data', { encoding: 'utf8' }).trim());
                    fs.chownSync(p, uid, gid);
                    fs.chmodSync(p, 0o640);
                } catch (_) {}
            } else {
                try { fs.chmodSync(p, 0o600); } catch (_) {}
            }
            cleared.push(p);
        } catch (err) {
            errors.push({ path: p, error: err.message || String(err) });
        }
    }

    wipeAuthFile(src, { wwwData: false });
    if (path.resolve(src) !== path.resolve(dest)) {
        wipeAuthFile(dest, { wwwData: true });
    }

    clearDeviceLoginPollTimer();
    try {
        writeDeviceLoginState({
            status: 'idle',
            cleared_at: isoNow(),
            message: 'Signed out',
        });
    } catch (err) {
        errors.push({ path: GROK_DEVICE_LOGIN_STATE_PATH, error: err.message || String(err) });
    }

    return { cleared, errors };
}

/**
 * Log out current Grok/xAI account and immediately start a new device-code OAuth flow.
 * Returns verification_uri_complete so the app can open the re-login link.
 */
async function logoutAndStartDeviceLogin() {
    const previous = peekGrokAuthStatus();
    const wipe = clearGrokAuthFiles();
    log('info', 'auth', 'Grok auth cleared for account switch / re-login', {
        previous_email: previous && previous.email ? previous.email : null,
        cleared: wipe.cleared,
        errors: wipe.errors,
        instance: INSTANCE_ID,
    });
    const login = await startDeviceLogin(true);
    return {
        ...login,
        ok: true,
        logged_out: true,
        needed: true,
        previous_email: (previous && previous.email) || null,
        auth_cleared: wipe.cleared,
        auth_clear_errors: wipe.errors.length ? wipe.errors : undefined,
        message: login.status === 'pending'
            ? (previous && previous.email
                ? `Signed out ${previous.email}. Open the link to sign in with another account.`
                : 'Signed out. Open the link to sign in with Grok / xAI.')
            : (login.message || 'Signed out. Start login again if the link is missing.'),
    };
}

// Resume pending device login after bridge restart.
(() => {
    try {
        const st = readDeviceLoginState();
        if (st && st.status === 'pending' && st.device_code) {
            const exp = st.expires_at ? Date.parse(st.expires_at) : 0;
            if (exp > Date.now()) {
                scheduleDeviceLoginPoll(2000);
            }
        }
    } catch (_) {}
})();

function isGrokModel(model) {
    return !!(model && (model.startsWith('gb:') || model.startsWith('grok:')));
}

function grokRealModel(model) {
    if (!model || typeof model !== 'string') return DEFAULT_GROK_MODEL;
    if (model.startsWith('gb:')) return model.slice(3);
    if (model.startsWith('grok:')) return model.slice(5);
    return model;
}

/** Resolve client model id to a known Grok Build model (legacy cursor ids → default). */
function resolveGrokModel(model) {
    let real = isGrokModel(model) ? grokRealModel(model) : '';
    if (real && GROK_MODELS_FULL.length && !GROK_MODELS_FULL.some((m) => m.id === real)) {
        real = '';
    }
    if (!real) {
        real = GROK_MODELS_FULL[0]?.id || DEFAULT_GROK_MODEL;
    }
    return 'gb:' + real;
}

/**
 * Heal stream-join artifacts in text before save / history replay.
 * Mirrors assets/system-chat.js healMidwordSpaces (keep in sync).
 */
function healChatText(text) {
    if (!text) return text || '';
    let s = String(text);

    s = s.replace(/\bI\s+Ds\b/g, 'IDs');
    s = s.replace(/\bI\s+D\b/g, 'ID');
    s = s.replace(/\b([A-Z]{1,3})\s+([A-Z]{1,3})\b/g, (m, a, b) =>
        a.length + b.length <= 5 ? a + b : m
    );

    const KEEP = new Set(
        (
            'a an the and or but if in on at to of for from by as is it be we he she ' +
            'they you me my our your his her its are was were has had have will can ' +
            'may not no yes so up out off all any new old via per with this that than ' +
            'then when what who how why which into onto over under about after before ' +
            'between through during without within also just more most some such only ' +
            'other upon like back even well very much many own same too still need ' +
            'each few plus vs mode dark light full real next last first both once'
        ).split(/\s+/)
    );

    const SUFF =
        'izers?|izing|ized|ifies|ify|ifying|able|ible|ables|ibles|apsible|apsible|' +
        'ates|ating|ated|ation|ations|ments?|ness|less|ful|ings?|edly|tions?|sions?|' +
        'ests?|wards?|ures?|ences?|ances?|ents?|ants?|ous|ives?|icals?|ials?|ying|' +
        'ened|ships?|hoods?|isms?|ists?|izes?|ises?|ories?|aries?|uals?|iests?|iers?|' +
        'ies|ied|ily|iness|ably|ibly|atives?|ators?|ability|ibility|' +
        'oring|aring|ering|uring|oping|aping|uting|oting|isting|asting|esting|' +
        'igned|igning|ifying|ified|ifier|ifiers|ocket|ockets|erver|ervers|' +
        'ession|essions|essage|essages|istory|istories|ermission|ermissions|' +
        'ersion|ersions|ackage|ackages|evice|evices|otals|ounts?|okens?|pot|ify|kify';
    const suffRe = new RegExp('\\b([A-Za-z]{2,})\\s+(' + SUFF + ')\\b', 'gi');
    for (let n = 0; n < 8; n++) {
        const next = s.replace(suffRe, '$1$2');
        if (next === s) break;
        s = next;
    }

    for (let n = 0; n < 4; n++) {
        const next = s.replace(/\b([B-HJ-Z])\s+([a-z]{2,12})\b/g, (m, a, b) =>
            KEEP.has(b) ? m : a + b
        );
        if (next === s) break;
        s = next;
    }

    const CAMEL =
        'Http|Https|Url|Uri|Json|Xml|Html|Sql|Api|Uuid|Null|True|False|Socket|' +
        'Stream|Client|Server|Token|Header|Request|Response|Config|Object|Array|' +
        'String|Number|Boolean|Integer|Double|Float|Class|Method|Field|Error|' +
        'Exception|Status|Code|Type|Name|Value|Key|Path|File|Dir|Query|Param|' +
        'Params|Body|Auth|User|Session|Device|Bridge|Model|Prompt|Chunk|Delta';
    const camelRe = new RegExp('([a-z0-9])\\s+(' + CAMEL + ')\\b', 'g');
    for (let n = 0; n < 6; n++) {
        const next = s.replace(camelRe, '$1$2');
        if (next === s) break;
        s = next;
    }

    // Missing space after .!? before a new sentence ("sleep.Checking")
    s = s.replace(/(.?)([A-Za-z0-9)\]"'”’»])([.!?])([A-Z])/g, (all, pre, before, punct, after) => {
        const singleLetterAbbr =
            /[A-Z]/.test(before) && (pre === '' || /[^A-Za-z]/.test(pre));
        if (singleLetterAbbr) return all;
        return pre + before + punct + ' ' + after;
    });
    s = s.replace(/([a-z0-9)\]"'”’])([.!?])(\*{1,2})(?=[A-Za-z])/g, '$1$2 $3');
    s = s.replace(/(\*{1,2})([.!?])([A-Z])/g, '$1$2 $3');

    // Drop unpaired ** (keep inner text) — same idea as web normalizer
    let out = '';
    let i = 0;
    while (i < s.length) {
        if (s[i] === '*' && s[i + 1] === '*') {
            let j = i + 2;
            let found = -1;
            while (j < s.length - 1) {
                if (s[j] === '*' && s[j + 1] === '*') {
                    found = j;
                    break;
                }
                j++;
            }
            if (found > i + 2) {
                const inner = s.slice(i + 2, found).trim();
                if (inner) out += '**' + inner + '**';
                i = found + 2;
            } else if (found === i + 2) {
                i = found + 2;
            } else {
                i += 2;
            }
        } else {
            out += s[i];
            i++;
        }
    }
    return out;
}

/** Pretty-print JSON-looking tool payloads; leave plain text alone. */
function formatToolPayload(value, maxLen) {
    const limit = maxLen || 12000;
    if (value == null) return '';
    let text;
    if (typeof value === 'object') {
        try {
            text = JSON.stringify(value, null, 2);
        } catch (_) {
            text = String(value);
        }
    } else {
        text = String(value);
        const trimmed = text.trim();
        if (
            (trimmed.startsWith('{') && trimmed.endsWith('}')) ||
            (trimmed.startsWith('[') && trimmed.endsWith(']'))
        ) {
            try {
                text = JSON.stringify(JSON.parse(trimmed), null, 2);
            } catch (_) {
                /* keep raw */
            }
        }
    }
    if (text.length > limit) {
        return text.slice(0, limit) + '\n… [truncated, ' + text.length + ' chars]';
    }
    return text;
}

function sealOpenThinkingEvents(agent) {
    if (!agent || !agent.events || !agent.events.length) return;
    for (let i = agent.events.length - 1; i >= 0; i--) {
        const t = agent.events[i].type;
        if (t === 'thinking_done') return;
        if (t === 'thinking_delta') {
            const evt = { type: 'thinking_done' };
            agent.events.push(evt);
            sendToClient(agent, evt);
            return;
        }
        if (t === 'chunk' || t === 'tool_start' || t === 'tool_done' || t === 'media' || t === 'text_replace') {
            return;
        }
    }
}

function markToolEventDone(agent, toolName, success, info) {
    if (!agent.toolEventLog || !agent.toolEventLog.length) return;
    // Prefer last open tool with matching name (parallel tool batches)
    let idx = -1;
    for (let i = agent.toolEventLog.length - 1; i >= 0; i--) {
        const t = agent.toolEventLog[i];
        if (t.success != null) continue;
        if (toolName && t.tool === toolName) {
            idx = i;
            break;
        }
        if (idx < 0) idx = i;
    }
    if (idx < 0) return;
    agent.toolEventLog[idx].success = success;
    agent.toolEventLog[idx].info = info || '';
    if (toolName) agent.toolEventLog[idx].tool = toolName;
}

function healTimeline(timeline, { seal = false } = {}) {
    if (!Array.isArray(timeline)) return timeline;
    return timeline.map((seg) => {
        if (!seg || typeof seg !== 'object') return seg;
        const copy = { ...seg };
        if (typeof copy.content === 'string') copy.content = healChatText(copy.content);
        if (typeof copy.detail === 'string') copy.detail = healChatText(copy.detail);
        if (typeof copy.info === 'string') copy.info = healChatText(copy.info);
        // On finalize / history: never leave thinking open or tools spinning
        if (seal) {
            if (copy.type === 'thinking') copy.done = true;
            if (copy.type === 'tool' && (copy.success === null || copy.success === undefined)) {
                copy.success = true;
            }
        }
        return copy;
    });
}

function healHistoryForPrompt(history) {
    if (!Array.isArray(history)) return [];
    return history.map((msg) => {
        if (!msg || typeof msg !== 'object') return msg;
        return {
            role: msg.role,
            content: healChatText(msg.content || ''),
        };
    });
}

/** Max user-attached images per turn (Android chat media button). */
const MAX_USER_IMAGES = 4;
/** ~4.5MB base64 ≈ ~3.3MB binary — phone photos after client compress. */
const MAX_IMAGE_B64_CHARS = 6_000_000;

/**
 * Normalize client image attachments into ACP content blocks for --prompt-file.
 * Accepts [{ data|base64, mimeType|mime }] (raw base64 or data-URL).
 * @returns {{ type: 'image', data: string, mimeType: string }[]}
 */
function normalizeUserImages(raw) {
    if (!Array.isArray(raw) || raw.length === 0) return [];
    const out = [];
    for (const item of raw.slice(0, MAX_USER_IMAGES)) {
        if (!item || typeof item !== 'object') continue;
        let data = String(item.data || item.base64 || item.bytes || '');
        if (!data) continue;
        if (data.includes(',')) {
            // data:image/jpeg;base64,....
            const head = data.slice(0, data.indexOf(','));
            data = data.slice(data.indexOf(',') + 1);
            if (!item.mimeType && !item.mime && /data:([^;]+)/i.test(head)) {
                item.mimeType = head.match(/data:([^;]+)/i)[1];
            }
        }
        data = data.replace(/\s+/g, '');
        if (!data || data.length > MAX_IMAGE_B64_CHARS) continue;
        let mime = String(item.mimeType || item.mime || item.content_type || 'image/jpeg')
            .trim()
            .toLowerCase();
        if (!mime.startsWith('image/')) mime = 'image/jpeg';
        // Accept only common image mimes
        if (!/^image\/(jpeg|jpg|png|webp|gif|bmp)$/.test(mime)) mime = 'image/jpeg';
        if (mime === 'image/jpg') mime = 'image/jpeg';
        try {
            const buf = Buffer.from(data, 'base64');
            // Grok drops images under 512 total pixels; still allow small attaches
            // but reject tiny garbage / empty decode.
            if (!buf.length || buf.length < 64 || buf.length > 4_500_000) continue;
            out.push({ type: 'image', data, mimeType: mime });
        } catch (_) {
            /* skip bad base64 */
        }
    }
    return out;
}

/**
 * Write ACP content blocks (text + images) for grok --prompt-file.
 * Returns absolute path or null.
 */
function writePromptBlocksFile(sessionId, fullPromptText, images) {
    const dir = path.join(WORKSPACE, 'storage', 'bridge-runtime', sessionId);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    const filePath = path.join(dir, `prompt-${Date.now()}.json`);
    const blocks = [
        { type: 'text', text: String(fullPromptText || '') },
        ...images,
    ];
    fs.writeFileSync(filePath, JSON.stringify(blocks), { mode: 0o600 });
    return filePath;
}

/** Plain-text prompt file for `grok --prompt-file` (avoids MAX_ARG_STRLEN). */
function writePromptTextFile(sessionId, fullPromptText) {
    const dir = path.join(WORKSPACE, 'storage', 'bridge-runtime', sessionId);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    const filePath = path.join(dir, `prompt-${Date.now()}.txt`);
    fs.writeFileSync(filePath, String(fullPromptText || ''), { mode: 0o600 });
    return filePath;
}

function cleanupPromptFile(agent) {
    const p = agent && agent._promptFile;
    if (!p) return;
    agent._promptFile = null;
    try {
        if (fs.existsSync(p)) fs.unlinkSync(p);
    } catch (_) {}
}

function sendToClient(agent, obj) {
    if (agent?._suppressSend) return;
    try {
        if (agent.client && agent.client.readyState === 1) {
            agent.client.send(JSON.stringify(obj));
        }
    } catch (_) {}
}

function replayEvents(agent, ws) {
    for (const evt of agent.events) {
        if (ws.readyState === 1) ws.send(JSON.stringify(evt));
    }
}

async function sessionOwned(sessionId, userId) {
    let conn;
    try {
        conn = await mysql.createConnection(DB_CONFIG);
        const [rows] = await conn.execute(
            'SELECT user_id FROM system_chat_sessions WHERE id = ? LIMIT 1',
            [sessionId]
        );
        if (!rows.length) return false;
        return Number(rows[0].user_id) === Number(userId);
    } catch (err) {
        log('error', 'error', `Session lookup failed: ${err.message}`, { session_id: sessionId });
        return false;
    } finally {
        if (conn) await conn.end();
    }
}

function buildTimelineFromEvents(events) {
    const timeline = [];
    if (!events || !events.length) return timeline;

    function sealOpenThinking() {
        for (let i = timeline.length - 1; i >= 0; i--) {
            if (timeline[i].type === 'thinking') {
                timeline[i].done = true;
                return;
            }
            if (timeline[i].type === 'text' || timeline[i].type === 'tool' || timeline[i].type === 'media') {
                return;
            }
        }
    }

    function applyToolDone(evt) {
        const toolName = evt.tool || '';
        let fallback = -1;
        for (let i = timeline.length - 1; i >= 0; i--) {
            const seg = timeline[i];
            if (seg.type !== 'tool' || seg.success != null) continue;
            if (toolName && seg.tool === toolName) {
                seg.success = evt.success !== false;
                if (evt.info) seg.info = evt.info;
                if (evt.detail) seg.detail = evt.detail;
                return;
            }
            if (fallback < 0) fallback = i;
        }
        // Name mismatch (or missing name): close most recent open tool
        if (fallback >= 0) {
            timeline[fallback].success = evt.success !== false;
            if (evt.info) timeline[fallback].info = evt.info;
            if (evt.detail) timeline[fallback].detail = evt.detail;
            if (toolName) timeline[fallback].tool = toolName;
        }
    }

    for (const evt of events) {
        if (evt.type === 'thinking_delta' && evt.content) {
            const last = timeline[timeline.length - 1];
            if (last && last.type === 'thinking' && !last.done) {
                last.content += evt.content;
            } else {
                sealOpenThinking();
                timeline.push({ type: 'thinking', content: evt.content, done: false });
            }
            continue;
        }
        if (evt.type === 'thinking_done') {
            sealOpenThinking();
            continue;
        }
        if (evt.type === 'tool_start') {
            sealOpenThinking();
            timeline.push({
                type: 'tool',
                tool: evt.tool || 'tool',
                detail: evt.detail || '',
                success: null,
                info: '',
            });
            continue;
        }
        if (evt.type === 'tool_done') {
            applyToolDone(evt);
            continue;
        }
        if (evt.type === 'media' && evt.url) {
            sealOpenThinking();
            timeline.push({
                type: 'media',
                kind: evt.kind || 'image',
                url: evt.url,
                name: evt.name || '',
                tool: evt.tool || null,
            });
            continue;
        }
        if (evt.type === 'chunk' && evt.content) {
            sealOpenThinking();
            const last = timeline[timeline.length - 1];
            if (last && last.type === 'text') {
                last.content += evt.content;
            } else {
                timeline.push({ type: 'text', content: evt.content });
            }
            continue;
        }
        if (evt.type === 'text_replace' && evt.content != null) {
            sealOpenThinking();
            while (timeline.length && timeline[timeline.length - 1].type === 'text') {
                timeline.pop();
            }
            timeline.push({ type: 'text', content: String(evt.content) });
        }
    }
    return timeline;
}

function buildAgentMetadata(agent, finalize) {
    const metadata = {
        model: agent?.model || null,
        duration: agent ? Date.now() - agent.startTime : null,
        tool_count: agent?.toolCount || 0,
        tools: agent?.toolEventLog?.length ? agent.toolEventLog : null,
        thinking: agent?.thinkingSummary ? healChatText(agent.thinkingSummary) : null,
        input_tokens: agent?.estimatedInputTokens || 0,
        output_tokens: agent?.estimatedOutputTokens || 0,
        context_tokens: agent?.contextTokens || 0,
        model_loops: agent?.modelLoops || 0,
        grok_cli_session_id: agent?._grokCliSessionId || null,
        tokens_estimated: agent?._usageFromTurn ? false : true,
    };
    const media = mediaIngest.mediaForMetadata(agent);
    if (media && media.length) metadata.media = media;
    if (agent?.events?.length) {
        const tl = buildTimelineFromEvents(agent.events);
        metadata.timeline = healTimeline(tl, { seal: !!finalize });
    }
    // Finalize: seal any open tools in the legacy tools array too
    if (finalize && Array.isArray(metadata.tools)) {
        metadata.tools = metadata.tools.map((t) => {
            if (!t || typeof t !== 'object') return t;
            if (t.success === null || t.success === undefined) {
                return { ...t, success: true };
            }
            return t;
        });
    }
    if (!finalize) metadata.streaming = true;
    if (finalize && agent?._interrupted) metadata.interrupted = true;
    return metadata;
}

async function createPartialMessage(sessionId, agent) {
    if (!sessionId || agent._partialMsgId) return;
    let conn;
    try {
        conn = await mysql.createConnection(DB_CONFIG);
        const metadata = buildAgentMetadata(agent, false);
        const [result] = await conn.execute(
            'INSERT INTO system_chat_messages (session_id, role, content, metadata) VALUES (?, ?, ?, ?)',
            [sessionId, 'assistant', '', JSON.stringify(metadata)]
        );
        agent._partialMsgId = result.insertId;
        await conn.execute('UPDATE system_chat_sessions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?', [sessionId]);
    } catch (err) {
        log('warning', 'error', `Partial msg create failed: ${err.message}`, { session_id: sessionId?.substring(0, 8) });
    } finally {
        if (conn) await conn.end();
    }
}

async function updatePartialInDB(sessionId, agent, finalize) {
    if (!sessionId || !agent) return;
    // Only mutate fullOutput on finalize — mid-stream healing breaks fragment joins
    if (finalize && agent.fullOutput) {
        agent.fullOutput = healChatText(agent.fullOutput);
    }
    const content = agent.fullOutput || '';
    const hasThinking = !!(agent.thinkingSummary && String(agent.thinkingSummary).trim());
    const hasTools = !!(agent.toolCount || (agent.toolEventLog && agent.toolEventLog.length));
    const hasMedia = !!(agent.media && agent.media.length);
    const hasAnything = !!(content || hasThinking || hasTools || hasMedia);
    // Never leave empty stub rows (common on restart before first chunk)
    if (!hasAnything && !agent._partialMsgId) return;
    if (finalize && !hasAnything && agent._partialMsgId) {
        let conn;
        try {
            conn = await mysql.createConnection(DB_CONFIG);
            await conn.execute('DELETE FROM system_chat_messages WHERE id = ? AND role = ? AND content = ?', [
                agent._partialMsgId,
                'assistant',
                '',
            ]);
            agent._partialMsgId = null;
        } catch (err) {
            log('warning', 'error', `Empty partial delete failed: ${err.message}`, {
                session_id: sessionId?.substring(0, 8),
            });
        } finally {
            if (conn) await conn.end();
        }
        return;
    }
    if (!content && !agent._partialMsgId && !finalize) return;

    let conn;
    try {
        const metadata = buildAgentMetadata(agent, finalize);
        conn = await mysql.createConnection(DB_CONFIG);
        if (agent._partialMsgId) {
            await conn.execute(
                'UPDATE system_chat_messages SET content = ?, metadata = ?, input_tokens = ?, output_tokens = ? WHERE id = ?',
                [content, JSON.stringify(metadata), metadata.input_tokens, metadata.output_tokens, agent._partialMsgId]
            );
        } else if (content || finalize) {
            const [result] = await conn.execute(
                'INSERT INTO system_chat_messages (session_id, role, content, metadata, input_tokens, output_tokens) VALUES (?, ?, ?, ?, ?, ?)',
                [sessionId, 'assistant', content, JSON.stringify(metadata), metadata.input_tokens, metadata.output_tokens]
            );
            agent._partialMsgId = result.insertId;
        }
        await conn.execute('UPDATE system_chat_sessions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?', [sessionId]);
    } catch (err) {
        log('warning', 'error', `DB save failed: ${err.message}`, { session_id: sessionId?.substring(0, 8) });
    } finally {
        if (conn) await conn.end();
    }
}

function schedulePartialSave(agent) {
    if (!agent || agent.done) return;
    if (agent._partialSaveTimer) return;
    // Fast checkpoints so a bridge restart loses at most ~0.8s of text
    agent._partialSaveTimer = setTimeout(() => {
        agent._partialSaveTimer = null;
        if (!agent.done) updatePartialInDB(agent.sessionId, agent, false).catch(() => {});
    }, 800);
}

async function flushPartialSave(agent, finalize) {
    if (!agent) return;
    if (agent._partialSaveTimer) {
        clearTimeout(agent._partialSaveTimer);
        agent._partialSaveTimer = null;
    }
    await updatePartialInDB(agent.sessionId, agent, !!finalize);
}

async function saveResponseToDB(sessionId, content, agent) {
    if (!sessionId) return;
    if (content) agent.fullOutput = content;
    await updatePartialInDB(sessionId, agent, true);
}

function cleanupAgent(sessionId, { killProcess = true } = {}) {
    const agent = agents.get(sessionId);
    if (!agent) return;
    try {
        mediaIngest.stopWatcher(agent);
    } catch (_) {}
    if (!agent.done) {
        agent._interrupted = true;
        // Fire-and-forget best-effort save before kill (shutdown path awaits explicitly)
        try {
            mediaIngest.finalize(agent, sendToClient, mediaProcessHelpers(agent));
        } catch (_) {}
        flushPartialSave(agent, true).catch(() => {});
        try {
            sendToClient(agent, {
                type: 'interrupted',
                content: agent.fullOutput || '',
                message_id: agent._partialMsgId || null,
                reason: 'superseded',
            });
        } catch (_) {}
    }
    if (killProcess && !agent.done) {
        try {
            if (agent.process) agent.process.kill('SIGTERM');
            else if (agent.pid) process.kill(agent.pid, 'SIGTERM');
        } catch (_) {}
    }
    if (agent._stopTail) {
        try { agent._stopTail(); } catch (_) {}
    }
    if (agent.timeout) clearTimeout(agent.timeout);
    cleanupPromptFile(agent);
    if (killProcess) {
        try { runtime.releaseClaim(sessionId); } catch (_) {}
    }
    agents.delete(sessionId);
}

let shuttingDown = false;

async function gracefulShutdown(signal) {
    if (shuttingDown) return;
    shuttingDown = true;
    log('info', 'connection', `Bridge shutting down (${signal})`, {
        agents: agents.size,
        detach: DETACH_AGENTS,
        instance: INSTANCE_ID,
    });

    const list = [...agents.values()];

    // 1) Tell clients first (while sockets still open)
    try {
        wss.clients.forEach((ws) => {
            try {
                ws.send(JSON.stringify({
                    type: 'bridge_stopping',
                    reason: signal,
                    agents_survive: DETACH_AGENTS,
                    instance: INSTANCE_ID,
                }));
            } catch (_) {}
        });
    } catch (_) {}

    // With detached agents: do NOT mark interrupted / kill CLI — peer or restarted
    // instance will reattach and continue streaming after client reconnects.
    if (!DETACH_AGENTS) {
        for (const agent of list) {
            if (agent.done) continue;
            agent._interrupted = true;
            if (agent.timeout) clearTimeout(agent.timeout);
            try {
                sendToClient(agent, {
                    type: 'interrupted',
                    content: agent.fullOutput || '',
                    message_id: agent._partialMsgId || null,
                    reason: 'bridge_restart',
                    duration: Date.now() - agent.startTime,
                    tools: agent.toolCount,
                    model: agent.model,
                });
            } catch (_) {}
        }
    } else {
        for (const agent of list) {
            if (agent.done) continue;
            try {
                sendToClient(agent, {
                    type: 'status',
                    content: 'Bridge worker restarting — agent keeps running; reconnecting…',
                });
            } catch (_) {}
            // Persist meta so the next process can recover
            try { runtime.writeMeta(agent); } catch (_) {}
        }
    }

    // 2) Persist every in-flight reply (non-final when agents survive)
    await Promise.all(
        list.map((agent) =>
            flushPartialSave(agent, !DETACH_AGENTS || !!agent.done).catch((err) => {
                log('warning', 'error', `Shutdown flush failed: ${err.message}`, {
                    session_id: agent.sessionId?.substring(0, 8),
                });
            })
        )
    );

    // 3) Stop tails/timeouts but keep CLI processes alive when detached
    for (const agent of list) {
        if (agent.timeout) clearTimeout(agent.timeout);
        if (agent._stopTail) {
            try { agent._stopTail(); } catch (_) {}
        }
        if (!DETACH_AGENTS) {
            agent.done = true;
            try {
                if (agent.process) agent.process.kill('SIGTERM');
                else if (agent.pid) process.kill(agent.pid, 'SIGTERM');
            } catch (_) {}
            try { runtime.releaseClaim(agent.sessionId); } catch (_) {}
        }
        // Release claim so the recovering process can take ownership
        if (DETACH_AGENTS && !agent.done) {
            try { runtime.releaseClaim(agent.sessionId); } catch (_) {}
        }
    }

    try {
        wss.clients.forEach((ws) => {
            try {
                ws.close(1012, 'service restart');
            } catch (_) {}
        });
    } catch (_) {}

    try {
        httpServer.close();
    } catch (_) {}

    // Brief moment for sockets; agents (if detached) keep running outside this cgroup kill
    setTimeout(() => process.exit(0), DETACH_AGENTS ? 200 : 350);
}

/** True when CLI / stderr text indicates Grok Build auth is missing or expired. */
function isAuthFailureMessage(msg) {
    const s = String(msg || '').toLowerCase();
    return /not signed in|not authenticated|authentication required|auth(?:entication)? failed|please (?:log|sign) in|run:\s*grok login|unauthorized|invalid.?token|session expired|login required|xai_api_key|spacexai_api_key/.test(s);
}

function formatCliAuthError(raw) {
    const detail = String(raw || '').trim();
    const lines = [
        'Grok Build is not signed in on the server (no agent reply was produced).',
        '',
        'On the host, re-authenticate, then send your message again:',
        '  grok login --device-code',
        '',
        'Check status anytime:',
        '  php scripts/check-grok-auth.php',
    ];
    if (detail && !/^not signed in/i.test(detail)) {
        lines.push('', 'Detail: ' + detail.slice(0, 400));
    }
    return lines.join('\n');
}

function captureAgentCliError(agent, raw) {
    const msg = String(raw || '').trim();
    if (!msg) return;
    agent._cliError = msg;
    if (isAuthFailureMessage(msg)) {
        agent._cliErrorIsAuth = true;
    }
}

function emitAgentError(agent, content, code) {
    const evt = {
        type: 'error',
        content: String(content || 'Agent error'),
        code: code || 'agent_error',
    };
    agent.events.push(evt);
    sendToClient(agent, evt);
    return evt;
}

function ingestStreamUsage(agent, json) {
    const usage = usageHarvest.normalizeUsage(usageHarvest.findUsage(json));
    if (!usage) return;
    agent.estimatedInputTokens = usage.input_tokens;
    agent.estimatedOutputTokens = usage.output_tokens;
    agent.modelLoops = usage.model_calls || agent.modelLoops || 0;
    agent._usageFromTurn = true;
    if (usage.total_tokens && usage.total_tokens < usageHarvest.CONTEXT_WINDOW_CAP) {
        agent.contextTokens = usage.total_tokens;
    }
}

function harvestAgentUsage(agent) {
    try {
        const row = usageHarvest.harvestAgent(agent, WORKSPACE);
        if (row) {
            log('info', 'usage', 'Harvested CLI session usage', {
                session_id: agent.sessionId?.substring(0, 8),
                grok_cli: row.id,
                input: row.estimated_input_tokens,
                output: row.estimated_output_tokens,
                context: row.last_context_tokens,
                estimated: row.tokens_estimated,
            });
            usageTrackerMem.at = 0;
        }
    } catch (err) {
        log('warning', 'usage', `CLI usage harvest failed: ${err.message}`, {
            session_id: agent.sessionId?.substring(0, 8),
        });
    }
}

function processGrokEvent(agent, json) {
    const t = json.type;
    const data = json.data || json.content || '';
    ingestStreamUsage(agent, json);

    // Grok CLI auth / hard failures: {"type":"error","message":"Not signed in..."}
    if (t === 'error') {
        const msg = json.message || json.content || json.error || data || 'Agent error';
        captureAgentCliError(agent, msg);
        const content = agent._cliErrorIsAuth
            ? formatCliAuthError(msg)
            : String(msg);
        emitAgentError(agent, content, agent._cliErrorIsAuth ? 'auth_required' : 'agent_error');
        log('warning', 'error', `Grok CLI error: ${String(msg).slice(0, 200)}`, {
            session_id: agent.sessionId,
            user_id: agent.userId,
            auth: !!agent._cliErrorIsAuth,
        });
        return;
    }

    if (t === 'thought' && data) {
        agent.thinkingSummary += data;
        const evt = { type: 'thinking_delta', content: data };
        agent.events.push(evt);
        sendToClient(agent, evt);
        schedulePartialSave(agent);
        log('debug', 'process', 'grok thought', { len: String(data).length, session_id: agent.sessionId, user_id: agent.userId });
        return;
    }

    if (t === 'text' && data) {
        sealOpenThinkingEvents(agent);
        agent.fullOutput += data;
        const evt = { type: 'chunk', content: data };
        agent.events.push(evt);
        sendToClient(agent, evt);
        schedulePartialSave(agent);
        return;
    }

    if (t === 'tool_call' || t === 'tool_start') {
        sealOpenThinkingEvents(agent);
        agent.toolCount++;
        const tool = json.name || json.tool || 'tool';
        const detail = formatToolPayload(json.args != null ? json.args : json.input || json.parameters || {}, 8000);
        const evt = { type: 'tool_start', tool, detail, index: agent.toolCount };
        agent.toolEventLog.push({ tool, detail: evt.detail, success: null, info: '' });
        agent.events.push(evt);
        sendToClient(agent, evt);
        schedulePartialSave(agent);
        log('info', 'process', `Grok tool: ${tool}`, { session_id: agent.sessionId, user_id: agent.userId });
        return;
    }

    if (t === 'tool_result' || t === 'tool_done') {
        const toolName = json.name || json.tool || 'tool';
        const rawInfo = json.error
            ? json.error
            : (json.result != null ? json.result : (json.data != null ? json.data : json.content));
        const info = formatToolPayload(rawInfo, 16000);
        const ok = !json.error;
        markToolEventDone(agent, toolName, ok, info);
        const evt = { type: 'tool_done', tool: toolName, success: ok, info };
        agent.events.push(evt);
        sendToClient(agent, evt);
        // Harvest Imagine outputs from tool payload when streaming-json includes them
        try {
            const payload = json.result || json.data || json.content || json;
            mediaIngest.ingestSource(agent, payload?.path || '', { tool: toolName });
            if (typeof payload === 'string' || (payload && typeof payload === 'object')) {
                const paths = mediaIngest.extractPathsFromText(
                    typeof payload === 'string' ? payload : JSON.stringify(payload)
                );
                for (const p of paths) {
                    if (/^https?:\/\//i.test(p)) {
                        mediaIngest.ingestHttpUrl(agent, p, { tool: toolName }).then((media) => {
                            if (media && !media._emitted) {
                                media._emitted = true;
                                const mevt = {
                                    type: 'media',
                                    kind: media.kind || 'image',
                                    url: media.url,
                                    name: media.name || (media.kind === 'video' ? 'Video' : 'Image'),
                                };
                                if (toolName) mevt.tool = toolName;
                                agent.events.push(mevt);
                                sendToClient(agent, mevt);
                            }
                        });
                    } else {
                        const media = mediaIngest.ingestSource(agent, p, { tool: toolName });
                        if (media && !media._emitted) {
                            media._emitted = true;
                            const mevt = {
                                type: 'media',
                                kind: media.kind || 'image',
                                url: media.url,
                                name: media.name || (media.kind === 'video' ? 'Video' : 'Image'),
                            };
                            if (toolName) mevt.tool = toolName;
                            agent.events.push(mevt);
                            sendToClient(agent, mevt);
                        }
                    }
                }
            }
        } catch (_) {}
        schedulePartialSave(agent);
        return;
    }

    if (t === 'end') {
        if (json.sessionId) agent._grokCliSessionId = json.sessionId;
        // Final media harvest + rewrite happens in finalizeAgentClose so paths are durable
        // before the done event is sent (avoid double-done here).
    }
}

function mediaProcessHelpers(agent) {
    return {
        onToolStart(tool, detail) {
            sealOpenThinkingEvents(agent);
            const formatted = formatToolPayload(detail, 8000);
            // Deduplicate against streaming-json tool_start if both fire
            const already =
                agent.toolEventLog &&
                agent.toolEventLog.some(
                    (t) => t.tool === tool && t.success == null && (
                        t.detail === formatted ||
                        (!t.detail && !formatted) ||
                        (t.detail && formatted && t.detail.slice(0, 80) === formatted.slice(0, 80))
                    )
                );
            if (already) return;
            agent.toolCount = (agent.toolCount || 0) + 1;
            const evt = {
                type: 'tool_start',
                tool,
                detail: formatted,
                index: agent.toolCount,
            };
            if (!agent.toolEventLog) agent.toolEventLog = [];
            agent.toolEventLog.push({ tool, detail: evt.detail, success: null, info: '' });
            agent.events.push(evt);
            sendToClient(agent, evt);
            schedulePartialSave(agent);
        },
        onToolDone(tool, success, info) {
            const formatted = formatToolPayload(info, 16000);
            markToolEventDone(agent, tool || 'tool', success, formatted);
            const evt = {
                type: 'tool_done',
                tool: tool || 'tool',
                success,
                info: formatted,
            };
            agent.events.push(evt);
            sendToClient(agent, evt);
            schedulePartialSave(agent);
        },
    };
}

function attachAgentLineHandlers(agent) {
    const ANSI_RE = /\x1b\[\??[0-9;]*[a-zA-Z]|\r/g;
    return (trimmed) => {
        const clean = trimmed.replace(ANSI_RE, '');
        if (!clean) return;
        try {
            const json = JSON.parse(clean);
            processGrokEvent(agent, json);
        } catch {
            // Non-JSON stderr often carries "Not signed in..." when auth is dead.
            if (isAuthFailureMessage(clean) || /^error:/i.test(clean)) {
                captureAgentCliError(agent, clean.replace(/^error:\s*/i, ''));
            }
        }
    };
}

function finalizeAgentClose(agent, code) {
    if (agent.done) return;
    agent.done = true;
    if (agent.timeout) clearTimeout(agent.timeout);
    if (agent._stopTail) {
        try { agent._stopTail(); } catch (_) {}
    }
    // Prompt JSON (with base64 images) is only needed while the CLI is starting; drop ASAP.
    cleanupPromptFile(agent);

    // Harvest Imagine assets from Grok session dir (local paths / temp URLs → durable uploads)
    try {
        mediaIngest.finalize(agent, sendToClient, mediaProcessHelpers(agent));
    } catch (err) {
        log('warning', 'media', `Final harvest failed: ${err.message}`, {
            session_id: agent.sessionId?.substring(0, 8),
        });
    }
    harvestAgentUsage(agent);

    const alreadyErrored = agent.events.some((e) => e.type === 'error');
    const exitCode = code != null ? code : (agent._exitCode != null ? agent._exitCode : 0);
    const emptyFail =
        !agent.fullOutput &&
        !(agent.media && agent.media.length) &&
        (exitCode !== 0 || agent._cliError || alreadyErrored);

    if (emptyFail && !alreadyErrored) {
        // Silent empty exit was the auth-loss bug: surface a real chat error.
        const raw = agent._cliError || `Agent exited with code ${exitCode} and no reply.`;
        const content = agent._cliErrorIsAuth || isAuthFailureMessage(raw)
            ? formatCliAuthError(raw)
            : String(raw);
        const errCode = (agent._cliErrorIsAuth || isAuthFailureMessage(raw))
            ? 'auth_required'
            : 'agent_exit';
        emitAgentError(agent, content, errCode);
        log('warning', 'error', 'Agent finished with no output', {
            session_id: agent.sessionId,
            code: exitCode,
            auth: errCode === 'auth_required',
            user_id: agent.userId,
        });
    } else if (!agent.events.some((e) => e.type === 'done') && !alreadyErrored) {
        const media = mediaIngest.mediaForMetadata(agent);
        const evt = {
            type: 'done',
            content: agent.fullOutput,
            duration: Date.now() - agent.startTime,
            tools: agent.toolCount,
            model: agent.model,
            input_tokens: agent.estimatedInputTokens || 0,
            output_tokens: agent.estimatedOutputTokens || 0,
            context_tokens: agent.contextTokens || 0,
            tokens_estimated: agent._usageFromTurn ? false : true,
            media: media || undefined,
        };
        agent.events.push(evt);
        sendToClient(agent, evt);
    } else if (alreadyErrored && !agent.events.some((e) => e.type === 'done')) {
        // Client clears busy on error; still emit a terminal done so reconnects settle.
        const evt = {
            type: 'done',
            content: agent.fullOutput || '',
            duration: Date.now() - agent.startTime,
            tools: agent.toolCount,
            model: agent.model,
            error: true,
        };
        agent.events.push(evt);
        sendToClient(agent, evt);
    }
    log('info', 'agent_done', `${agent.provider || 'agent'} finished`, {
        session_id: agent.sessionId,
        code,
        output_len: agent.fullOutput.length,
        tools: agent.toolCount,
        media: agent.media?.length || 0,
        user_id: agent.userId,
        instance: INSTANCE_ID,
    });
    if (agent.fullOutput || (agent.media && agent.media.length)) {
        saveResponseToDB(agent.sessionId, agent.fullOutput, agent);
    } else {
        updatePartialInDB(agent.sessionId, agent, true).catch(() => {});
    }
    try { runtime.writeMeta(agent); } catch (_) {}
    try { runtime.releaseClaim(agent.sessionId); } catch (_) {}
    setTimeout(() => agents.delete(agent.sessionId), 60000);
}

function startAgentWatchers(agent) {
    const onLine = attachAgentLineHandlers(agent);
    if (DETACH_AGENTS) {
        runtime.startFileTail(agent, {
            onLine,
            onExit: (code) => finalizeAgentClose(agent, code),
        });
    } else if (agent.process) {
        let buffer = '';
        const ANSI_RE = /\x1b\[\??[0-9;]*[a-zA-Z]|\r/g;
        agent.process.stdout.on('data', (chunk) => {
            buffer += chunk.toString().replace(ANSI_RE, '');
            const lines = buffer.split('\n');
            buffer = lines.pop();
            for (const line of lines) {
                const trimmed = line.trim();
                if (trimmed) onLine(trimmed);
            }
        });
        agent.process.stderr.on('data', (chunk) => {
            const text = chunk.toString().trim();
            if (text) {
                log('warning', 'process', `stderr: ${text.substring(0, 300)}`, {
                    session_id: agent.sessionId,
                    user_id: agent.userId,
                });
                if (isAuthFailureMessage(text) || /^error:/i.test(text)) {
                    captureAgentCliError(agent, text.replace(/^error:\s*/i, ''));
                }
            }
        });
        agent.process.on('close', (code) => finalizeAgentClose(agent, code));
        agent.process.on('error', (err) => {
            agent.done = true;
            log('error', 'error', `Spawn error: ${err.message}`, {
                session_id: agent.sessionId,
                user_id: agent.userId,
            });
            emitAgentError(agent, err.message, 'spawn_error');
            updatePartialInDB(agent.sessionId, agent, true).catch(() => {});
        });
    }

    agent.timeout = setTimeout(() => {
        if (!agent.done) {
            log('warning', 'error', 'Agent timeout', {
                session_id: agent.sessionId,
                user_id: agent.userId,
            });
            try {
                if (agent.process) agent.process.kill('SIGTERM');
                else if (agent.pid) process.kill(agent.pid, 'SIGTERM');
            } catch (_) {}
        }
    }, AGENT_TIMEOUT_MS);
}

async function spawnGrokBuild(sessionId, prompt, model, client, history, notes, userId, images = [], reasoningEffort = '') {
    const existing = agents.get(sessionId);
    if (existing && !existing.done) cleanupAgent(sessionId, { killProcess: true });

    if (DETACH_AGENTS && !runtime.tryClaim(sessionId)) {
        try {
            client.send(JSON.stringify({ type: 'error', content: 'Session busy on another bridge worker' }));
        } catch (_) {}
        return;
    }

    const resolved = resolveGrokModel(model);
    const realModel = grokRealModel(resolved);
    const effort = resolveReasoningEffort(realModel, reasoningEffort, REASONING_EFFORT);
    const userImages = Array.isArray(images) ? images : [];
    const fitted = fitPromptContext(prompt, healHistoryForPrompt(history), notes, MAX_PROMPT_BYTES);
    let fullPrompt = fitted.text;
    if (userImages.length > 0) {
        fullPrompt +=
            `\n\n(The user attached ${userImages.length} image(s) inline for you to analyze visually.)`;
    }

    const agentCwd = await getAgentCwd();

    // Large text (and any images) go via --prompt-file so a single argv entry
    // cannot exceed Linux MAX_ARG_STRLEN (~128KiB) and crash the worker (E2BIG).
    let promptFile = null;
    const useFile = shouldUsePromptFile(fullPrompt, userImages.length);
    if (useFile) {
        try {
            promptFile = userImages.length > 0
                ? writePromptBlocksFile(sessionId, fullPrompt, userImages)
                : writePromptTextFile(sessionId, fullPrompt);
        } catch (err) {
            log('error', 'agent', `prompt-file write failed: ${err.message}`, {
                session_id: sessionId,
            });
            try {
                client.send(JSON.stringify({
                    type: 'error',
                    content: userImages.length > 0
                        ? 'Could not prepare image attachment for the agent'
                        : 'Could not prepare prompt file for the agent',
                }));
            } catch (_) {}
            if (DETACH_AGENTS) runtime.releaseClaim(sessionId);
            return;
        }
    }

    log('info', 'agent', 'Spawning Grok Build', {
        session_id: sessionId,
        model: realModel,
        reasoning_effort: effort,
        user_id: userId,
        prompt_bytes: Buffer.byteLength(fullPrompt),
        history_in: fitted.historyIn,
        history_kept: fitted.historyKept,
        prompt_file: !!promptFile,
        images: userImages.length,
        detach: DETACH_AGENTS,
        instance: INSTANCE_ID,
        cwd: agentCwd,
    });

    const args = [
        '--output-format', 'streaming-json',
        '--always-approve',
        '--reasoning-effort', effort,
        '-m', realModel,
    ];
    if (promptFile) {
        args.push('--prompt-file', promptFile);
    } else {
        args.push('-p', fullPrompt);
    }

    const env = { ...process.env, HOME: process.env.HOME || '/root' };
    let proc = null;
    let pid = null;
    let outPath = null;

    try {
        if (DETACH_AGENTS) {
            const spawned = runtime.spawnDetached(GROK_BIN, args, env, sessionId, {
                truncate: true,
                cwd: agentCwd,
            });
            proc = spawned.proc;
            pid = spawned.pid;
            outPath = spawned.outPath;
        } else {
            proc = spawn(GROK_BIN, args, {
                cwd: agentCwd,
                stdio: ['ignore', 'pipe', 'pipe'],
                env,
            });
            pid = proc.pid;
        }
    } catch (err) {
        log('error', 'error', `Spawn failed: ${err.message}`, {
            session_id: sessionId,
            user_id: userId,
            code: err.code || '',
            prompt_bytes: Buffer.byteLength(fullPrompt),
            prompt_file: !!promptFile,
        });
        if (promptFile) {
            try { fs.unlinkSync(promptFile); } catch (_) {}
        }
        if (DETACH_AGENTS) runtime.releaseClaim(sessionId);
        try {
            client.send(JSON.stringify({
                type: 'error',
                code: err.code === 'E2BIG' ? 'prompt_too_large' : 'spawn_error',
                content: err.code === 'E2BIG'
                    ? 'Prompt was too large to start the agent. Send again — older context will be trimmed.'
                    : `Could not start agent: ${err.message}`,
            }));
        } catch (_) {}
        return;
    }

    const agent = {
        process: proc,
        proc,
        pid,
        outPath,
        _fileOffset: 0,
        _promptFile: promptFile,
        sessionId,
        userId,
        events: [],
        fullOutput: '',
        toolCount: 0,
        toolEventLog: [],
        thinkingSummary: '',
        startTime: Date.now(),
        done: false,
        client,
        model: resolved,
        provider: 'grok-build',
    };
    agents.set(sessionId, agent);
    runtime.writeMeta(agent);
    createPartialMessage(sessionId, agent)
        .then(() => {
            runtime.writeMeta(agent);
            if (agent._partialMsgId) {
                sendToClient(agent, { type: 'partial_msg_id', message_id: agent._partialMsgId });
            }
        })
        .catch(() => {});

    const initEvt = { type: 'init', model: agent.model };
    agent.events.push(initEvt);
    sendToClient(agent, initEvt);
    startAgentWatchers(agent);
    // Poll Grok session dir for image_gen / video tools (not always in streaming-json)
    try {
        mediaIngest.startWatcher(agent, sendToClient, mediaProcessHelpers(agent));
    } catch (err) {
        log('warning', 'media', `Watcher start failed: ${err.message}`, {
            session_id: sessionId?.substring(0, 8),
        });
    }
}

/**
 * Reattach one detached CLI agent (if its process is still alive).
 * @returns {object|null} agent entry or null
 */
function recoverOneDetachedAgent(sessionId) {
    if (!DETACH_AGENTS || !sessionId || agents.has(sessionId)) {
        return agents.get(sessionId) || null;
    }
    const meta = runtime.readMeta(sessionId);
    if (!meta || meta.done) return null;
    if (!runtime.isPidAlive(meta.pid)) return null;
    if (!runtime.tryClaim(sessionId)) return null;

    // Only Grok Build agents are supported; skip any legacy Cursor runtimes.
    const provider = meta.provider || 'grok-build';
    if (provider !== 'grok-build') {
        log('info', 'agent', 'Skipping non-Grok detached agent', {
            session_id: sessionId,
            provider,
            instance: INSTANCE_ID,
        });
        return null;
    }

    const agent = {
        process: null,
        proc: null,
        pid: meta.pid,
        outPath: runtime.stdoutPath(sessionId),
        _fileOffset: 0,
        sessionId,
        userId: meta.userId || null,
        events: [],
        fullOutput: '',
        toolCount: 0,
        toolEventLog: [],
        thinkingSummary: '',
        currentThinking: '',
        startTime: meta.startTime || Date.now(),
        done: false,
        client: null,
        model: resolveGrokModel(meta.model),
        provider: 'grok-build',
        _partialMsgId: meta.partialMsgId || null,
        _suppressSend: true,
    };
    agents.set(sessionId, agent);

    // Rebuild state from stdout without notifying clients (they'll reconnect + replay)
    const onLine = attachAgentLineHandlers(agent);
    try {
        runtime.rebuildFromStdout(agent, onLine);
    } catch (err) {
        log('warning', 'error', `recover rebuild failed: ${err.message}`, {
            session_id: sessionId.substring(0, 8),
        });
    }
    agent._suppressSend = false;
    runtime.writeMeta(agent);
    startAgentWatchers(agent);
    try {
        mediaIngest.startWatcher(agent, sendToClient, mediaProcessHelpers(agent));
    } catch (_) {}
    log('info', 'agent', 'Recovered detached agent', {
        session_id: sessionId,
        pid: agent.pid,
        output_len: agent.fullOutput.length,
        events: agent.events.length,
        instance: INSTANCE_ID,
    });
    return agent;
}

/**
 * Reattach to detached CLI agents left running after a prior worker exit.
 */
function recoverDetachedAgents() {
    if (!DETACH_AGENTS) return;
    runtime.ensureRoot();
    runtime.cleanupStale();
    const sessions = runtime.listSessionDirs();
    let recovered = 0;
    for (const sessionId of sessions) {
        if (agents.has(sessionId)) continue;
        if (recoverOneDetachedAgent(sessionId)) recovered++;
    }
    if (recovered) {
        log('info', 'connection', `Recovered ${recovered} detached agent(s)`, { instance: INSTANCE_ID });
    }
}

/**
 * Lightweight auth.json peek for /health (no network refresh).
 * Full check: php scripts/check-grok-auth.php
 */
function peekGrokAuthStatus() {
    try {
        const candidates = [
            envFirst('GROKIFY_GROK_AUTH_JSON', 'GROKPOT_GROK_AUTH_JSON'),
            path.join(WORKSPACE, 'storage', 'grok-auth.json'),
            '/etc/grokifyos/grok-auth.json',
            path.join(process.env.HOME || '/root', '.grok', 'auth.json'),
            '/root/.grok/auth.json',
        ].filter(Boolean);
        for (const p of candidates) {
            if (!fs.existsSync(p)) continue;
            const raw = fs.readFileSync(p, 'utf8');
            const data = JSON.parse(raw);
            if (!data || typeof data !== 'object') continue;
            for (const key of Object.keys(data)) {
                const entry = data[key];
                if (!entry || typeof entry !== 'object') continue;
                const token = entry.key || entry.access_token || '';
                if (!token) continue;
                const expiresAt = entry.expires_at || null;
                let expired = false;
                if (expiresAt) {
                    const ts = Date.parse(expiresAt);
                    if (!Number.isNaN(ts)) expired = ts <= Date.now() + 120000;
                }
                return {
                    ok: !expired,
                    path: p,
                    email: entry.email || null,
                    expires_at: expiresAt,
                    expired,
                    has_refresh: !!(entry.refresh_token),
                };
            }
        }
        return { ok: false, error: 'auth_missing' };
    } catch (err) {
        return { ok: false, error: err.message || 'auth_peek_failed' };
    }
}

function runGrokComplete({ prompt, system, model, reasoningEffort, json, timeoutMs }) {
    return new Promise(async (resolve) => {
        const real = resolveCompleteModel(model);
        const effort = resolveReasoningEffort(real, reasoningEffort, REASONING_EFFORT);
        const sys = String(system || '').trim();
        const user = String(prompt || '');
        const fullPrompt = sys ? `${sys}\n\n${user}` : user;
        if (!fullPrompt.trim()) {
            resolve({ ok: false, error: 'empty_prompt' });
            return;
        }
        let promptFile = null;
        const args = [
            '--output-format', json ? 'json' : 'plain',
            '--always-approve',
            '--disable-web-search',
            '--no-subagents',
            '--no-plan',
            '--max-turns', '1',
            '--reasoning-effort', effort,
            '-m', real,
        ];
        if (json) {
            args.push('--json-schema', JSON.stringify({
                type: 'object',
                properties: { tags: { type: 'array', items: { type: 'string' } } },
                required: ['tags'],
            }));
        }
        try {
            if (shouldUsePromptFile(fullPrompt, 0)) {
                promptFile = writePromptTextFile('discord-ai', fullPrompt);
                args.push('--prompt-file', promptFile);
            } else {
                args.push('-p', fullPrompt);
            }
        } catch (err) {
            resolve({ ok: false, error: err.message || 'prompt_file_failed' });
            return;
        }
        let cwd = defaultAgentCwd();
        try { cwd = await getAgentCwd(); } catch (_) {}
        const env = { ...process.env, HOME: process.env.HOME || '/root' };
        let proc;
        try {
            proc = spawn(GROK_BIN, args, { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] });
        } catch (err) {
            if (promptFile) try { fs.unlinkSync(promptFile); } catch (_) {}
            resolve({ ok: false, error: err.message || 'spawn_failed' });
            return;
        }
        let out = '';
        let errOut = '';
        proc.stdout.on('data', (d) => {
            if (out.length < 800000) out += d.toString('utf8');
        });
        proc.stderr.on('data', (d) => {
            if (errOut.length < 20000) errOut += d.toString('utf8');
        });
        const timer = setTimeout(() => {
            try { proc.kill('SIGTERM'); } catch (_) {}
        }, Math.max(15000, timeoutMs || 150000));
        proc.on('close', (code) => {
            clearTimeout(timer);
            if (promptFile) try { fs.unlinkSync(promptFile); } catch (_) {}
            const text = extractCompleteText(stripAnsi(out), !!json);
            if (text) {
                resolve({ ok: true, text, model: real, reasoning_effort: effort });
                return;
            }
            const msg = stripAnsi(errOut).trim().slice(0, 180) || (`exit_${code}`);
            resolve({ ok: false, error: msg, model: real, reasoning_effort: effort });
        });
        proc.on('error', (e) => {
            clearTimeout(timer);
            if (promptFile) try { fs.unlinkSync(promptFile); } catch (_) {}
            resolve({ ok: false, error: e.message || 'spawn_failed' });
        });
    });
}

function hostTimeZone() {
    const envTz = process.env.TZ || process.env.GROKIFY_USAGE_TZ;
    if (envTz) return envTz;
    try {
        const tz = fs.readFileSync('/etc/timezone', 'utf8').trim();
        if (tz) return tz;
    } catch {
        /* ignore */
    }
    return 'UTC';
}

function usageTrackerCacheFile() {
    return path.join(WORKSPACE, 'storage', 'cache', 'usage-tracker.json');
}

function getUsageTracker(opts) {
    const options = opts || {};
    const ttlMs = 45000;
    const force = !!options.refresh;
    const cacheFile = usageTrackerCacheFile();
    const cwdAllow = [defaultAgentCwd()];
    const now = Date.now();
    let sessions = null;
    let scanned = usageTrackerMem.scanned || 0;
    let parsed = 0;
    let reused = 0;
    if (!force && usageTrackerMem.sessions && (now - usageTrackerMem.at) < ttlMs) {
        sessions = usageTrackerMem.sessions;
        reused = Object.keys(sessions).length;
    } else {
        const cache = usageHarvest.loadCache(cacheFile);
        const collected = usageHarvest.collect({
            cwdAllow,
            cache,
            home: process.env.HOME || '/root',
        });
        sessions = collected.sessions;
        scanned = collected.scanned;
        parsed = collected.parsed;
        reused = collected.reused;
        if (collected.parsed > 0 || !fs.existsSync(cacheFile)) {
            try { usageHarvest.saveCache(cacheFile, collected.sessions); } catch (_) {}
        }
        usageTrackerMem = { at: now, sessions, scanned };
    }
    const tracker = usageHarvest.aggregate(sessions, {
        from: options.from || '',
        to: options.to || '',
        timeZone: hostTimeZone(),
        includeRuns: !!options.includeRuns,
    });
    const out = {
        ok: true,
        source: 'grok-cli-sessions',
        scanned,
        parsed,
        reused,
        period_start: options.from || '',
        period_end: options.to || '',
        timezone: hostTimeZone(),
        totals: tracker.totals,
        daily: tracker.daily,
        fetched_at: new Date().toISOString(),
    };
    if (options.includeRuns) out.runs = tracker.runs;
    return out;
}

const httpServer = http.createServer((req, res) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    if (url.pathname === '/models' && req.method === 'GET') {
        refreshGrokModelsIfStale();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            grok_models: GROK_MODELS_FULL,
            allowed: [...ALLOWED_MODELS],
            default_model: resolveGrokModel(null),
            default_reasoning_effort: defaultEffortForModel(DEFAULT_GROK_MODEL, REASONING_EFFORT),
        }));
        return;
    }
    if (url.pathname === '/complete' && req.method === 'POST') {
        readRequestBody(req, 512 * 1024)
            .then(async (raw) => {
                let body = {};
                try {
                    body = raw ? JSON.parse(raw) : {};
                } catch {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ ok: false, error: 'invalid_json' }));
                    return;
                }
                const timeoutMs = Math.min(300000, Math.max(15000, parseInt(body.timeout_ms, 10) || 150000));
                const result = await runGrokComplete({
                    prompt: body.prompt || body.user || '',
                    system: body.system || '',
                    model: body.model || DEFAULT_GROK_MODEL,
                    reasoningEffort: body.reasoning_effort || body.reasoningEffort || '',
                    json: !!(body.json || body.response_format === 'json'),
                    timeoutMs,
                });
                res.writeHead(result.ok ? 200 : 502, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(result));
            })
            .catch((err) => {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ ok: false, error: err.message || 'complete_failed' }));
            });
        return;
    }
    // Agent working directory (global): get / set / list children for picker
    if (url.pathname === '/work-dir' && req.method === 'GET') {
        getAgentCwd({ force: true })
            .then((cwd) => {
                const def = defaultAgentCwd();
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({
                    ok: true,
                    path: cwd,
                    default_path: def,
                    is_default: path.resolve(cwd) === path.resolve(def),
                }));
            })
            .catch((err) => {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ ok: false, error: err.message || 'failed' }));
            });
        return;
    }
    if (url.pathname === '/work-dir' && req.method === 'POST') {
        readRequestBody(req)
            .then(async (raw) => {
                let body = {};
                try {
                    body = raw ? JSON.parse(raw) : {};
                } catch {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ ok: false, error: 'invalid_json' }));
                    return;
                }
                const reset = !!(body.reset || body.path === '' || body.path === null);
                const result = await setAgentCwd(reset ? '' : body.path, { reset });
                res.writeHead(result.ok ? 200 : 400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(result));
            })
            .catch((err) => {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ ok: false, error: err.message || 'bad_request' }));
            });
        return;
    }
    if (url.pathname === '/work-dir/list' && req.method === 'GET') {
        const listPath = url.searchParams.get('path') || '';
        const result = listWorkDirEntries(listPath || undefined);
        res.writeHead(result.ok ? 200 : 400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(result));
        return;
    }
    if (url.pathname === '/usage-tracker' && req.method === 'GET') {
        try {
            const result = getUsageTracker({
                from: url.searchParams.get('from') || '',
                to: url.searchParams.get('to') || '',
                refresh: url.searchParams.get('refresh') === '1',
                includeRuns: url.searchParams.get('include_runs') === '1',
            });
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify(result));
        } catch (err) {
            res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: false, error: err.message || 'tracker_failed' }));
        }
        return;
    }
    if (url.pathname === '/health' && req.method === 'GET') {
        // Opportunistic heal: keep usage API auth current whenever something pokes health.
        let authSync = null;
        try { authSync = syncGrokAuthIfNeeded(false); } catch (err) {
            authSync = { ok: false, error: err.message || 'sync_failed' };
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            status: 'healthy',
            port: PORT,
            agents: agents.size,
            instance: INSTANCE_ID,
            detach: DETACH_AGENTS,
            role: 'worker',
            grok_auth: peekGrokAuthStatus(),
            grok_auth_sync: authSync,
        }));
        return;
    }
    // Force-copy CLI auth → storage/grok-auth.json (used by PHP after refresh_token revoke).
    if (url.pathname === '/sync-grok-auth' && (req.method === 'POST' || req.method === 'GET')) {
        const force = url.searchParams.get('force') === '1' || req.method === 'POST';
        let result;
        try {
            result = syncGrokAuthIfNeeded(force);
        } catch (err) {
            result = { ok: false, error: err.message || 'sync_failed' };
        }
        res.writeHead(result && result.ok ? 200 : 500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(result));
        return;
    }
    // OIDC device-code login: start returns a clickable verification_uri_complete;
    // status polls until the user approves in the browser.
    // logout clears local auth.json then starts a fresh device-code flow (account switch).
    if (url.pathname === '/grok-login/logout' && (req.method === 'POST' || req.method === 'GET')) {
        logoutAndStartDeviceLogin()
            .then((result) => {
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(result));
            })
            .catch((err) => {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({
                    ok: false,
                    status: 'error',
                    needed: true,
                    logged_out: false,
                    error: err.message || 'logout_failed',
                    message: 'Failed to log out / start Grok device login',
                }));
            });
        return;
    }
    if (url.pathname === '/grok-login/start' && (req.method === 'POST' || req.method === 'GET')) {
        const forceNew = url.searchParams.get('force') === '1';
        startDeviceLogin(forceNew)
            .then((result) => {
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(result));
            })
            .catch((err) => {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({
                    ok: false,
                    status: 'error',
                    needed: true,
                    error: err.message || 'start_failed',
                    message: 'Failed to start Grok device login',
                }));
            });
        return;
    }
    if (url.pathname === '/grok-login/status' && req.method === 'GET') {
        const startIfNeeded = url.searchParams.get('start') === '1';
        const forceNew = url.searchParams.get('force') === '1';
        getDeviceLoginStatus({ startIfNeeded, forceNew })
            .then((result) => {
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(result));
            })
            .catch((err) => {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({
                    ok: false,
                    status: 'error',
                    needed: true,
                    error: err.message || 'status_failed',
                }));
            });
        return;
    }
    // Gateway uses this to sticky-route reconnects to the worker that owns the agent
    const agentMatch = url.pathname.match(/^\/agent\/([a-f0-9]{32})$/i);
    if (agentMatch && req.method === 'GET') {
        const sid = agentMatch[1].toLowerCase();
        const agent = agents.get(sid);
        const present = !!(agent && !agent.done);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            present,
            done: !!(agent && agent.done),
            session_id: sid,
            instance: INSTANCE_ID,
            pid: agent?.pid || null,
            model: agent?.model || null,
            output_len: agent?.fullOutput?.length || 0,
        }));
        return;
    }
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'not_found' }));
});

const wss = new WebSocketServer({ noServer: true });

httpServer.on('upgrade', (request, socket, head) => {
    wss.handleUpgrade(request, socket, head, (ws) => {
        wss.emit('connection', ws, request);
    });
});

wss.on('connection', (ws, req) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    const token = url.searchParams.get('token') || '';
    const auth = verifyWsToken(token);
    if (!auth) {
        ws.close(4001, 'unauthorized');
        log('warning', 'connection', 'WS auth failed', {});
        return;
    }
    ws.userId = auth.uid;
    ws.isAlive = true;
    log('info', 'connection', 'WS connected', { user_id: auth.uid });

    ws.on('pong', () => { ws.isAlive = true; });

    ws.on('message', async (raw) => {
        let data;
        try {
            data = JSON.parse(raw.toString());
        } catch {
            ws.send(JSON.stringify({ type: 'error', content: 'Invalid JSON' }));
            return;
        }

        if (data.type === 'reconnect') {
            const sid = data.session_id;
            // Prefer in-memory agent; if this worker just came up, reclaim detached CLI
            let agent = sid ? agents.get(sid) : null;
            if (sid && !agent) {
                try {
                    agent = recoverOneDetachedAgent(sid);
                } catch (err) {
                    log('warning', 'error', `reconnect reclaim failed: ${err.message}`, {
                        session_id: sid,
                        user_id: ws.userId,
                    });
                }
            }
            if (sid && agent && !agent.done) {
                agent.client = ws;
                // Tell client to clear bubble before replay so partial DOM is not wiped on dead agents
                try {
                    ws.send(JSON.stringify({
                        type: 'agent_resume',
                        session_id: sid,
                        message_id: agent._partialMsgId || null,
                        done: !!agent.done,
                        instance: INSTANCE_ID,
                    }));
                } catch (_) {}
                replayEvents(agent, ws);
                if (agent._partialMsgId) {
                    ws.send(JSON.stringify({ type: 'partial_msg_id', message_id: agent._partialMsgId }));
                }
                log('info', 'connection', 'WS reconnected to agent', {
                    session_id: sid,
                    user_id: ws.userId,
                    instance: INSTANCE_ID,
                });
            } else {
                ws.send(JSON.stringify({
                    type: 'no_agent',
                    session_id: sid || null,
                    instance: INSTANCE_ID,
                }));
            }
            return;
        }

        if (shuttingDown) {
            ws.send(JSON.stringify({ type: 'error', content: 'Bridge is restarting — retry in a moment' }));
            return;
        }

        const images = normalizeUserImages(data.images);
        let prompt = typeof data.prompt === 'string' ? data.prompt : '';
        // Image-only turns: default ask-to-analyze prompt
        if (!prompt.trim() && images.length > 0) {
            prompt = images.length === 1
                ? 'Please analyze the attached image and describe what you see.'
                : `Please analyze the ${images.length} attached images and describe what you see.`;
        }
        if (!prompt || typeof prompt !== 'string' || prompt.length > 50000) {
            ws.send(JSON.stringify({ type: 'error', content: 'Invalid prompt' }));
            return;
        }
        const { model, session_id, history, notes } = data;
        const requestedEffort = data.reasoning_effort || data.effort || data.reasoningEffort || '';
        const sessionId = session_id || '';
        if (!/^[a-f0-9]{32}$/.test(sessionId)) {
            ws.send(JSON.stringify({ type: 'error', content: 'Invalid session' }));
            return;
        }
        if (!(await sessionOwned(sessionId, ws.userId))) {
            ws.send(JSON.stringify({ type: 'error', content: 'Session not found' }));
            log('warning', 'error', 'Session ownership denied', { session_id: sessionId, user_id: ws.userId });
            return;
        }

        log('info', 'message', 'Prompt received', {
            session_id: sessionId,
            user_id: ws.userId,
            model: resolveGrokModel(model),
            reasoning_effort: resolveReasoningEffort(
                grokRealModel(resolveGrokModel(model)),
                requestedEffort,
                REASONING_EFFORT,
            ),
            prompt_len: prompt.length,
            images: images.length,
        });

        await spawnGrokBuild(sessionId, prompt, model, ws, history, notes, ws.userId, images, requestedEffort);
    });

    ws.on('close', () => {
        log('info', 'connection', 'WS disconnected', { user_id: ws.userId });
        for (const agent of agents.values()) {
            if (agent.client === ws) agent.client = null;
        }
    });
});

setInterval(() => {
    wss.clients.forEach((ws) => {
        if (ws.isAlive === false) return ws.terminate();
        ws.isAlive = false;
        try { ws.ping(); } catch (_) {}
    });
}, 25000);

httpServer.listen(PORT, () => {
    ensureLogDir();
    try {
        recoverDetachedAgents();
    } catch (err) {
        log('warning', 'error', `Agent recovery failed: ${err.message}`, { instance: INSTANCE_ID });
    }
    getAgentCwd({ force: true })
        .then((cwd) => {
            log('info', 'connection', `Bridge listening on ${PORT}`, {
                workspace: WORKSPACE,
                agent_cwd: cwd,
                instance: INSTANCE_ID,
                detach: DETACH_AGENTS,
            });
            console.log(
                `grokpot-bridge ${INSTANCE_ID} on :${PORT} (detach=${DETACH_AGENTS}, cwd=${cwd})`
            );
        })
        .catch(() => {
            log('info', 'connection', `Bridge listening on ${PORT}`, {
                workspace: WORKSPACE,
                instance: INSTANCE_ID,
                detach: DETACH_AGENTS,
            });
            console.log(`grokpot-bridge ${INSTANCE_ID} on :${PORT} (detach=${DETACH_AGENTS})`);
        });
});

// Flush in-flight assistant messages to MySQL before process exit (systemd restart, etc.)
process.on('SIGTERM', () => { gracefulShutdown('SIGTERM').catch(() => process.exit(1)); });
process.on('SIGINT', () => { gracefulShutdown('SIGINT').catch(() => process.exit(1)); });
process.on('SIGHUP', () => { gracefulShutdown('SIGHUP').catch(() => process.exit(1)); });

// E2BIG from `spawn(grok, ['-p', hugePrompt])` used to be uncaught and systemd
// force-restarted the worker mid-chat. Keep the process up; spawnGrokBuild
// already reports the error to the client when it catches it.
process.on('uncaughtException', (err) => {
    const code = err && err.code;
    log('error', 'error', `uncaughtException: ${err && err.message}`, {
        code: code || '',
        instance: INSTANCE_ID,
    });
    if (code === 'E2BIG') return;
    gracefulShutdown('uncaughtException').catch(() => process.exit(1));
});
process.on('unhandledRejection', (reason) => {
    const msg = reason && reason.message ? reason.message : String(reason);
    log('error', 'error', `unhandledRejection: ${msg}`, { instance: INSTANCE_ID });
});