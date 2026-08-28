'use strict';

/**
 * Harvest Grok CLI session usage from ~/.grok/sessions.
 *
 * signals.json → last-context snapshot, tools, wall time, assistant loops
 * updates.jsonl turn_completed.usage → billed input/output (when the turn finished)
 */

const fs = require('fs');
const path = require('path');

const CONTEXT_WINDOW_CAP = 600000;
const CACHE_VERSION = 1;

function encodeCwd(cwd) {
    return String(cwd || '').replace(/\//g, '%2F');
}

function grokSessionsRoot(home) {
    return path.join(home || process.env.HOME || '/root', '.grok', 'sessions');
}

function num(v) {
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
}

function parseIsoMs(iso) {
    if (!iso) return 0;
    const t = Date.parse(iso);
    return Number.isFinite(t) ? t : 0;
}

function dayKey(iso, timeZone) {
    const ms = parseIsoMs(iso);
    if (!ms) return String(iso || '').slice(0, 10);
    try {
        return new Intl.DateTimeFormat('en-CA', {
            timeZone: timeZone || 'UTC',
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
        }).format(new Date(ms));
    } catch {
        return new Date(ms).toISOString().slice(0, 10);
    }
}

function readJson(file) {
    try {
        return JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
        return null;
    }
}

function tailFile(file, maxBytes) {
    try {
        const st = fs.statSync(file);
        const size = st.size;
        const fd = fs.openSync(file, 'r');
        const len = Math.min(maxBytes || 131072, size);
        const buf = Buffer.alloc(len);
        fs.readSync(fd, buf, 0, len, Math.max(0, size - len));
        fs.closeSync(fd);
        return buf.toString('utf8');
    } catch {
        return '';
    }
}

function srcMtime(dir) {
    let m = 0;
    for (const name of ['summary.json', 'signals.json', 'updates.jsonl', 'events.jsonl']) {
        try {
            const st = fs.statSync(path.join(dir, name));
            if (st.mtimeMs > m) m = st.mtimeMs;
        } catch {
            /* missing */
        }
    }
    return Math.round(m);
}

function findUsage(obj, depth) {
    if (!obj || typeof obj !== 'object' || (depth || 0) > 8) return null;
    if (obj.inputTokens != null || obj.input_tokens != null) return obj;
    if (obj.usage && typeof obj.usage === 'object') {
        const nested = findUsage(obj.usage, (depth || 0) + 1);
        if (nested) return nested;
    }
    if (Array.isArray(obj)) {
        for (const v of obj) {
            const nested = findUsage(v, (depth || 0) + 1);
            if (nested) return nested;
        }
        return null;
    }
    for (const v of Object.values(obj)) {
        if (v && typeof v === 'object') {
            const nested = findUsage(v, (depth || 0) + 1);
            if (nested) return nested;
        }
    }
    return null;
}

function normalizeUsage(u) {
    if (!u || typeof u !== 'object') return null;
    const input = Math.round(num(u.inputTokens || u.input_tokens));
    const output = Math.round(num(u.outputTokens || u.output_tokens));
    if (!input && !output) return null;
    return {
        input_tokens: input,
        output_tokens: output,
        total_tokens: Math.round(num(u.totalTokens || u.total_tokens)),
        cached_read_tokens: Math.round(num(u.cachedReadTokens || u.cached_read_tokens)),
        reasoning_tokens: Math.round(num(u.reasoningTokens || u.reasoning_tokens)),
        model_calls: Math.round(num(u.modelCalls || u.model_calls || u.numTurns || u.num_turns)),
        api_duration_ms: Math.round(num(u.apiDurationMs || u.api_duration_ms)),
    };
}

function parseTurnUsage(updatesPath) {
    const text = tailFile(updatesPath, 196608);
    if (!text) return null;
    const lines = text.split('\n');
    for (let i = lines.length - 1; i >= 0; i--) {
        const line = lines[i].trim();
        if (!line || line[0] !== '{') continue;
        if (!line.includes('inputTokens') && !line.includes('turn_completed')) continue;
        try {
            const usage = normalizeUsage(findUsage(JSON.parse(line)));
            if (usage) return usage;
        } catch {
            /* truncated / partial line */
        }
    }
    return null;
}

function lastContextFromUpdates(updatesPath) {
    const text = tailFile(updatesPath, 65536);
    if (!text) return 0;
    const re = /"totalTokens"\s*:\s*(\d+)/g;
    let match;
    let lastSnap = 0;
    let lastAny = 0;
    while ((match = re.exec(text))) {
        const v = parseInt(match[1], 10);
        lastAny = v;
        if (v > 0 && v < CONTEXT_WINDOW_CAP) lastSnap = v;
    }
    if (lastSnap) return lastSnap;
    return lastAny < CONTEXT_WINDOW_CAP ? lastAny : 0;
}

function parseSignals(file) {
    const s = readJson(file);
    if (!s || typeof s !== 'object') return null;
    const tools = Array.isArray(s.toolsUsed) ? s.toolsUsed.map(String) : [];
    return {
        last_context_tokens: Math.round(num(s.contextTokensUsed)),
        context_pct: Math.round(num(s.contextWindowUsage)),
        context_window_tokens: Math.round(num(s.contextWindowTokens)) || 500000,
        tool_calls: Math.round(num(s.toolCallCount)),
        tools,
        wall_time_s: Math.round(num(s.sessionDurationSeconds)),
        model_loops: Math.round(num(s.assistantMessageCount)),
        user_messages: Math.round(num(s.userMessageCount)),
        message_count: Math.round(num(s.userMessageCount) + num(s.assistantMessageCount)),
    };
}

function countEvents(eventsPath) {
    const out = { model_loops: 0, tool_calls: 0, tools: [] };
    try {
        const st = fs.statSync(eventsPath);
        if (st.size > 4_000_000) return out;
        const text = fs.readFileSync(eventsPath, 'utf8');
        out.model_loops = (text.match(/"type":"loop_started"/g) || []).length;
        out.tool_calls = (text.match(/"type":"tool_started"/g) || []).length;
        const names = new Set();
        const nameRe = /"tool_name":"([^"]+)"/g;
        let m;
        while ((m = nameRe.exec(text))) names.add(m[1]);
        out.tools = [...names];
    } catch {
        /* missing */
    }
    return out;
}

function parseSessionDir(dir) {
    const summary = readJson(path.join(dir, 'summary.json'));
    if (!summary) return null;
    const info = summary.info && typeof summary.info === 'object' ? summary.info : {};
    const id = info.id || path.basename(dir);
    const created = summary.created_at || '';
    const updated = summary.updated_at || summary.last_active_at || created;
    const signals = parseSignals(path.join(dir, 'signals.json'));
    const usage = parseTurnUsage(path.join(dir, 'updates.jsonl'));

    let lastContext = signals ? signals.last_context_tokens : 0;
    if (!lastContext) lastContext = lastContextFromUpdates(path.join(dir, 'updates.jsonl'));

    let modelLoops = (usage && usage.model_calls) || (signals && signals.model_loops) || 0;
    let toolCalls = (signals && signals.tool_calls) || 0;
    let tools = (signals && signals.tools) || [];
    let wall = (signals && signals.wall_time_s) || 0;
    if (!wall && created && updated) {
        const a = parseIsoMs(created);
        const b = parseIsoMs(updated);
        if (a && b) wall = Math.max(0, Math.round((b - a) / 1000));
    }
    if (!modelLoops || !toolCalls) {
        const ev = countEvents(path.join(dir, 'events.jsonl'));
        if (!modelLoops) modelLoops = ev.model_loops;
        if (!toolCalls) toolCalls = ev.tool_calls;
        if (!tools.length) tools = ev.tools;
    }

    const billed = !!(usage && usage.input_tokens);
    const estIn = billed
        ? usage.input_tokens
        : lastContext * Math.max(modelLoops, 1);
    const estOut = usage ? usage.output_tokens : 0;

    return {
        id,
        cwd: info.cwd || '',
        title: summary.generated_title || summary.session_summary || '',
        created,
        updated,
        model: summary.current_model_id || '',
        reasoning: summary.reasoning_effort || '',
        model_loops: modelLoops,
        tool_calls: toolCalls,
        wall_time_s: wall,
        model_time_s: usage ? Math.round((usage.api_duration_ms || 0) / 1000) : 0,
        last_context_tokens: lastContext,
        context_pct: signals ? signals.context_pct : 0,
        estimated_input_tokens: estIn,
        estimated_output_tokens: estOut,
        reasoning_tokens: usage ? usage.reasoning_tokens : 0,
        cached_read_tokens: usage ? usage.cached_read_tokens : 0,
        message_count: Math.round(
            num(summary.num_chat_messages) || (signals && signals.message_count) || 0
        ),
        tokens_estimated: !billed,
        tools,
    };
}

function listSessionDirs(sessionsRoot, cwdAllow) {
    const out = [];
    if (!fs.existsSync(sessionsRoot)) return out;
    let parents;
    try {
        parents = fs.readdirSync(sessionsRoot, { withFileTypes: true });
    } catch {
        return out;
    }
    const allow = Array.isArray(cwdAllow) && cwdAllow.length
        ? new Set(cwdAllow.map((c) => path.resolve(c)))
        : null;
    for (const ent of parents) {
        if (!ent.isDirectory()) continue;
        const parent = path.join(sessionsRoot, ent.name);
        const cwd = ent.name.replace(/%2F/g, '/');
        if (allow && !allow.has(path.resolve(cwd))) continue;
        let kids;
        try {
            kids = fs.readdirSync(parent, { withFileTypes: true });
        } catch {
            continue;
        }
        for (const k of kids) {
            if (!k.isDirectory()) continue;
            if (!/^[0-9a-f-]{20,}$/i.test(k.name)) continue;
            out.push(path.join(parent, k.name));
        }
    }
    return out;
}

function collect(opts) {
    const options = opts || {};
    const sessionsRoot = options.sessionsRoot || grokSessionsRoot(options.home);
    const cache = options.cache && typeof options.cache === 'object'
        ? options.cache
        : { sessions: {} };
    const dirs = listSessionDirs(sessionsRoot, options.cwdAllow);
    const sessions = {};
    let parsed = 0;
    let reused = 0;
    for (const dir of dirs) {
        const id = path.basename(dir);
        const mt = srcMtime(dir);
        const prev = cache.sessions && cache.sessions[id];
        if (prev && prev._mtime === mt) {
            sessions[id] = prev;
            reused++;
            continue;
        }
        const row = parseSessionDir(dir);
        if (!row) continue;
        row._mtime = mt;
        sessions[id] = row;
        parsed++;
    }
    return { sessions, parsed, reused, scanned: dirs.length };
}

function emptyTotals() {
    return {
        agent_sessions: 0,
        model_loops: 0,
        tool_calls: 0,
        wall_time_s: 0,
        model_time_s: 0,
        last_context_tokens: 0,
        estimated_input_tokens: 0,
        estimated_output_tokens: 0,
        reasoning_tokens: 0,
        cached_read_tokens: 0,
        message_count: 0,
    };
}

function addTo(t, row) {
    t.agent_sessions += 1;
    t.model_loops += row.model_loops || 0;
    t.tool_calls += row.tool_calls || 0;
    t.wall_time_s += row.wall_time_s || 0;
    t.model_time_s += row.model_time_s || 0;
    t.last_context_tokens += row.last_context_tokens || 0;
    t.estimated_input_tokens += row.estimated_input_tokens || 0;
    t.estimated_output_tokens += row.estimated_output_tokens || 0;
    t.reasoning_tokens += row.reasoning_tokens || 0;
    t.cached_read_tokens += row.cached_read_tokens || 0;
    t.message_count += row.message_count || 0;
}

function sessionOverlaps(row, fromMs, toMs) {
    const createdMs = parseIsoMs(row.created);
    const updatedMs = parseIsoMs(row.updated) || createdMs;
    if (fromMs && updatedMs && updatedMs < fromMs) return false;
    if (toMs && createdMs && createdMs > toMs) return false;
    return true;
}

function compactRun(row) {
    return {
        id: row.id,
        created: row.created,
        updated: row.updated,
        title: row.title || '',
        last_context_tokens: row.last_context_tokens || 0,
        estimated_input_tokens: row.estimated_input_tokens || 0,
        estimated_output_tokens: row.estimated_output_tokens || 0,
        model_loops: row.model_loops || 0,
        tool_calls: row.tool_calls || 0,
        wall_time_s: row.wall_time_s || 0,
        model_time_s: row.model_time_s || 0,
        message_count: row.message_count || 0,
        tokens_estimated: !!row.tokens_estimated,
    };
}

function aggregate(sessions, opts) {
    const options = opts || {};
    const fromMs = options.from ? parseIsoMs(options.from) : 0;
    const toMs = options.to ? parseIsoMs(options.to) : 0;
    const tz = options.timeZone || process.env.TZ || 'UTC';
    const totals = emptyTotals();
    const dailyMap = new Map();
    const tools = new Set();
    const runs = [];
    const list = Array.isArray(sessions) ? sessions : Object.values(sessions || {});
    for (const row of list) {
        if (!row) continue;
        if (!sessionOverlaps(row, fromMs, toMs)) continue;
        addTo(totals, row);
        (row.tools || []).forEach((t) => tools.add(String(t)));
        const day = dayKey(row.created || row.updated, tz);
        if (day) {
            if (!dailyMap.has(day)) dailyMap.set(day, emptyTotals());
            addTo(dailyMap.get(day), row);
        }
        if (options.includeRuns) runs.push(compactRun(row));
    }
    totals.tools = [...tools].sort();
    const daily = [...dailyMap.entries()]
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map(([day, t]) => ({ day, ...t }));
    return { totals, daily, runs };
}

function loadCache(file) {
    const j = readJson(file);
    if (!j || j.version !== CACHE_VERSION || !j.sessions || typeof j.sessions !== 'object') {
        return { version: CACHE_VERSION, sessions: {} };
    }
    return j;
}

function saveCache(file, sessions) {
    if (!file) return;
    const dir = path.dirname(file);
    if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true, mode: 0o775 });
    }
    const tmp = file + '.tmp';
    fs.writeFileSync(
        tmp,
        JSON.stringify({
            version: CACHE_VERSION,
            saved_at: new Date().toISOString(),
            sessions,
        })
    );
    fs.renameSync(tmp, file);
    try { fs.chmodSync(file, 0o664); } catch { /* ignore */ }
}

function resolveSessionDir(workspace, grokCliSessionId, startMs, home) {
    const root = path.join(grokSessionsRoot(home), encodeCwd(workspace || ''));
    if (grokCliSessionId) {
        const p = path.join(root, grokCliSessionId);
        if (fs.existsSync(p)) return p;
    }
    if (!fs.existsSync(root)) return null;
    let entries;
    try {
        entries = fs.readdirSync(root, { withFileTypes: true });
    } catch {
        return null;
    }
    const after = (startMs || 0) - 5000;
    let best = null;
    let bestMtime = 0;
    for (const ent of entries) {
        if (!ent.isDirectory()) continue;
        if (!/^[0-9a-f-]{20,}$/i.test(ent.name)) continue;
        const full = path.join(root, ent.name);
        let st;
        try { st = fs.statSync(full); } catch { continue; }
        if (after && st.mtimeMs < after) continue;
        if (st.mtimeMs >= bestMtime) {
            bestMtime = st.mtimeMs;
            best = full;
        }
    }
    return best;
}

function applyHarvestToAgent(agent, row) {
    if (!agent || !row) return agent;
    agent.estimatedInputTokens = row.estimated_input_tokens || 0;
    agent.estimatedOutputTokens = row.estimated_output_tokens || 0;
    agent.contextTokens = row.last_context_tokens || 0;
    agent.modelLoops = row.model_loops || 0;
    agent._usageFromTurn = !row.tokens_estimated;
    if (row.id) agent._grokCliSessionId = row.id;
    return agent;
}

function harvestAgent(agent, workspace, home) {
    if (!agent) return null;
    const dir = agent._grokSessionDir
        || resolveSessionDir(
            workspace,
            agent._grokCliSessionId,
            agent.startTime,
            home
        );
    if (!dir) return null;
    agent._grokSessionDir = dir;
    const row = parseSessionDir(dir);
    if (!row) return null;
    applyHarvestToAgent(agent, row);
    return row;
}

function buildTracker(opts) {
    const options = opts || {};
    const cacheFile = options.cacheFile;
    const cache = cacheFile ? loadCache(cacheFile) : { sessions: {} };
    const collected = collect({ ...options, cache });
    if (cacheFile && collected.parsed > 0) {
        saveCache(cacheFile, collected.sessions);
    }
    const agg = aggregate(collected.sessions, options);
    const out = {
        ok: true,
        source: 'grok-cli-sessions',
        scanned: collected.scanned,
        parsed: collected.parsed,
        reused: collected.reused,
        period_start: options.from || '',
        period_end: options.to || '',
        timezone: options.timeZone || process.env.TZ || 'UTC',
        totals: agg.totals,
        daily: agg.daily,
        fetched_at: new Date().toISOString(),
    };
    if (options.includeRuns) out.runs = agg.runs;
    return out;
}

module.exports = {
    CONTEXT_WINDOW_CAP,
    encodeCwd,
    grokSessionsRoot,
    parseIsoMs,
    dayKey,
    findUsage,
    normalizeUsage,
    parseTurnUsage,
    parseSessionDir,
    collect,
    aggregate,
    emptyTotals,
    loadCache,
    saveCache,
    resolveSessionDir,
    applyHarvestToAgent,
    harvestAgent,
    buildTracker,
};
