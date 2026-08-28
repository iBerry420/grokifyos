<?php

declare(strict_types=1);

/**
 * System chat helpers for GrokifyOS (sessions, audit, bridge, Grok Build usage).
 */

require_once __DIR__ . '/usage-tracker.php';

function gos_system_chat_tables_ready(): bool
{
    static $ready = null;
    if ($ready !== null) {
        return $ready;
    }
    $ready = gos_table_exists('system_chat_sessions')
        && gos_table_exists('system_chat_messages')
        && gos_table_exists('system_chat_events');

    return $ready;
}

/** @return array{user: array, device: ?array, auth: string} */
function gos_require_system_chat(): array
{
    $access = gos_require_access();
    if (!gos_system_chat_tables_ready()) {
        gos_api_json(['ok' => false, 'error' => 'system_chat_not_migrated'], 503);
    }

    return $access;
}

function gos_system_chat_session_id(): string
{
    return bin2hex(random_bytes(16));
}

function gos_system_chat_valid_session_id(string $id): bool
{
    return (bool) preg_match('/^[a-f0-9]{32}$/', $id);
}

function gos_system_chat_auto_title(string $content, int $maxLen = 48): string
{
    $text = trim(preg_replace('/\s+/u', ' ', str_replace(["\r\n", "\r"], "\n", $content)) ?? '');
    if ($text === '') {
        return '';
    }
    if (preg_match('/^(.{1,' . max(12, $maxLen) . '}?)(?:[.!?](?:\s|$)|$)/u', $text, $m)) {
        $candidate = trim($m[1]);
        if ($candidate !== '') {
            $text = $candidate;
        }
    }
    if (mb_strlen($text) > $maxLen) {
        $text = rtrim(mb_substr($text, 0, $maxLen - 1)) . '…';
    }

    return $text;
}

function gos_system_chat_ws_secret(): string
{
    $fromEnv = gos_env('GROKIFY_WS_AUTH_SECRET', '') ?? '';
    if ($fromEnv !== '') {
        return $fromEnv;
    }
    $pepper = gos_env('GROKIFY_SECRETS_PEPPER', '') ?? '';
    if ($pepper !== '') {
        return hash('sha256', 'grokifyos_system_chat_ws:' . $pepper);
    }

    return hash('sha256', 'grokifyos_system_chat_ws_fallback');
}

function gos_system_chat_ws_token(array $user, int $ttlSeconds = 3600): string
{
    $payload = [
        'uid' => (int) ($user['id'] ?? 0),
        'role' => (string) ($user['role'] ?? ''),
        'exp' => time() + $ttlSeconds,
        'nonce' => bin2hex(random_bytes(8)),
    ];
    $json = json_encode($payload, JSON_UNESCAPED_SLASHES);
    $sig = hash_hmac('sha256', (string) $json, gos_system_chat_ws_secret());

    return base64_encode((string) $json) . '.' . $sig;
}

/** @return array{uid: int, role: string}|null */
function gos_system_chat_verify_ws_token(string $token): ?array
{
    $parts = explode('.', $token, 2);
    if (count($parts) !== 2) {
        return null;
    }
    $json = base64_decode($parts[0], true);
    if ($json === false) {
        return null;
    }
    $expected = hash_hmac('sha256', $json, gos_system_chat_ws_secret());
    if (!hash_equals($expected, $parts[1])) {
        return null;
    }
    $data = json_decode($json, true);
    if (!is_array($data) || empty($data['uid']) || empty($data['exp'])) {
        return null;
    }
    if ((int) $data['exp'] < time()) {
        return null;
    }

    return ['uid' => (int) $data['uid'], 'role' => (string) ($data['role'] ?? '')];
}

function gos_setting_get(string $key, string $default = ''): string
{
    if (!gos_table_exists('app_settings')) {
        return $default;
    }
    $st = gos_pdo()->prepare('SELECT setting_value FROM app_settings WHERE setting_key = ? LIMIT 1');
    $st->execute([$key]);
    $v = $st->fetchColumn();

    return is_string($v) ? $v : $default;
}

function gos_setting_set(string $key, string $value): void
{
    if (!gos_table_exists('app_settings')) {
        return;
    }
    $st = gos_pdo()->prepare(
        'INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?)
         ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)'
    );
    $st->execute([$key, $value]);
}

function gos_system_chat_selected_model(): string
{
    $m = gos_setting_get('system_chat_selected_model', '');
    if ($m === '' || $m === 'auto') {
        return '';
    }
    if (!str_starts_with($m, 'gb:') && !str_starts_with($m, 'grok:')) {
        return '';
    }
    if (str_starts_with($m, 'grok:') && !str_starts_with($m, 'gb:')) {
        return 'gb:' . substr($m, 5);
    }

    return $m;
}

function gos_system_chat_set_selected_model(string $model): void
{
    $model = trim($model);
    if ($model === '' || $model === 'auto') {
        return;
    }
    if (!str_starts_with($model, 'gb:') && !str_starts_with($model, 'grok:')) {
        return;
    }
    if (str_starts_with($model, 'grok:') && !str_starts_with($model, 'gb:')) {
        $model = 'gb:' . substr($model, 5);
    }
    gos_setting_set('system_chat_selected_model', $model);
}

/** @return list<string> */
function gos_reasoning_efforts_for_model(string $model): array
{
    $real = (string) preg_replace('/^(gb:|grok:)/', '', trim($model));
    if (preg_match('/^grok-(\d+)(?:\.(\d+))?/', strtolower($real), $m) === 1) {
        $major = (int) $m[1];
        $minor = isset($m[2]) ? (int) $m[2] : 0;
        if ($major > 4 || ($major === 4 && $minor >= 6)) {
            return ['low', 'medium', 'high', 'xhigh'];
        }
    }

    return ['low', 'medium', 'high'];
}

function gos_default_reasoning_effort_for_model(string $model): string
{
    $allowed = gos_reasoning_efforts_for_model($model);

    return in_array('xhigh', $allowed, true) ? 'xhigh' : 'high';
}

function gos_clamp_reasoning_effort(string $model, string $effort): string
{
    $allowed = gos_reasoning_efforts_for_model($model);
    $req = strtolower(trim($effort));
    if (in_array($req, $allowed, true)) {
        return $req;
    }

    return gos_default_reasoning_effort_for_model($model);
}

function gos_system_chat_selected_reasoning_effort(): string
{
    return strtolower(trim((string) gos_setting_get('system_chat_selected_reasoning_effort', '')));
}

function gos_system_chat_set_selected_reasoning_effort(string $effort, string $model = ''): void
{
    $clamped = gos_clamp_reasoning_effort($model !== '' ? $model : gos_system_chat_selected_model(), $effort);
    gos_setting_set('system_chat_selected_reasoning_effort', $clamped);
}

function gos_system_chat_bridge_url(): string
{
    $url = gos_env('GROKIFY_BRIDGE_URL', '') ?? '';
    if ($url !== '') {
        return rtrim($url, '/');
    }

    return 'http://127.0.0.1:8876';
}

/**
 * Low-level HTTP call to the agent bridge.
 *
 * @return array{ok: bool, error?: string, http_code?: int, message?: string}|array<string, mixed>
 */
function gos_bridge_http(string $path, string $method = 'GET', ?array $body = null, int $timeout = 8): array
{
    $base = rtrim(gos_system_chat_bridge_url(), '/');
    $url = $base . (str_starts_with($path, '/') ? $path : '/' . $path);
    $ch = curl_init($url);
    if ($ch === false) {
        return ['ok' => false, 'error' => 'curl_init_failed'];
    }
    $headers = ['Accept: application/json'];
    $opts = [
        CURLOPT_CUSTOMREQUEST => strtoupper($method),
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => $timeout,
        CURLOPT_CONNECTTIMEOUT => 2,
    ];
    if ($body !== null) {
        $payload = json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        $headers[] = 'Content-Type: application/json';
        $opts[CURLOPT_POSTFIELDS] = $payload !== false ? $payload : '{}';
    }
    $opts[CURLOPT_HTTPHEADER] = $headers;
    curl_setopt_array($ch, $opts);
    $resp = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);
    if (!is_string($resp) || $resp === '') {
        return [
            'ok' => false,
            'error' => $err !== '' ? $err : 'empty_response',
            'http_code' => $code,
        ];
    }
    $json = json_decode($resp, true);
    if (!is_array($json)) {
        return ['ok' => false, 'error' => 'parse_failed', 'http_code' => $code];
    }
    $json['http_code'] = $code;
    if (!array_key_exists('ok', $json)) {
        $json['ok'] = $code >= 200 && $code < 300;
    }

    return $json;
}

/** @return array<string, mixed> */
function gos_bridge_work_dir_get(): array
{
    return gos_bridge_http('/work-dir', 'GET');
}

/** @return array<string, mixed> */
function gos_bridge_work_dir_set(string $path, bool $reset = false): array
{
    if ($reset || $path === '') {
        return gos_bridge_http('/work-dir', 'POST', ['reset' => true]);
    }

    return gos_bridge_http('/work-dir', 'POST', ['path' => $path]);
}

/** @return array<string, mixed> */
function gos_bridge_work_dir_list(string $path = ''): array
{
    $q = $path !== '' ? ('?path=' . rawurlencode($path)) : '';

    return gos_bridge_http('/work-dir/list' . $q, 'GET');
}

function gos_system_chat_ws_path(): string
{
    $path = gos_env('GROKIFY_WS_PATH', '/grokify-ws/') ?? '/grokify-ws/';
    if ($path === '') {
        $path = '/grokify-ws/';
    }
    if ($path[0] !== '/') {
        $path = '/' . $path;
    }
    if (!str_ends_with($path, '/')) {
        $path .= '/';
    }

    return gos_web_base() . $path;
}

/**
 * @param array<string, mixed> $context
 */
function gos_system_chat_audit(
    string $level,
    string $category,
    string $message,
    array $context = [],
    ?int $userId = null,
    ?string $sessionId = null
): ?int {
    if (!gos_table_exists('system_chat_events')) {
        return null;
    }

    $level = match (strtolower($level)) {
        'debug', 'info', 'notice', 'warning', 'error' => strtolower($level),
        default => 'info',
    };
    $category = substr(preg_replace('/[^a-z0-9_]/', '', strtolower($category)) ?: 'general', 0, 32);
    $message = mb_substr(trim($message), 0, 512);
    if ($message === '') {
        $message = '(empty)';
    }

    $context = gos_system_chat_redact_context($context);
    if ($sessionId !== null && !gos_system_chat_valid_session_id($sessionId)) {
        $sessionId = null;
    }

    try {
        $st = gos_pdo()->prepare(
            'INSERT INTO system_chat_events (level, category, message, context, user_id, session_id)
             VALUES (?, ?, ?, ?, ?, ?)'
        );
        $st->execute([
            $level,
            $category,
            $message,
            $context === [] ? null : json_encode($context, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
            $userId,
            $sessionId,
        ]);

        return (int) gos_pdo()->lastInsertId();
    } catch (Throwable $e) {
        error_log('[gos_system_chat_audit] ' . $e->getMessage());

        return null;
    }
}

/**
 * @param array<string, mixed> $context
 * @return array<string, mixed>
 */
function gos_system_chat_redact_context(array $context): array
{
    $out = [];
    foreach ($context as $k => $v) {
        if (is_string($v)) {
            $v = preg_replace('/0x[0-9a-fA-F]{64}\b/', '[REDACTED_KEY]', $v) ?? $v;
            $v = preg_replace('/\b[0-9a-fA-F]{64}\b/', '[REDACTED_KEY]', $v) ?? $v;
            if (strlen($v) > 4000) {
                $v = substr($v, 0, 4000) . '…';
            }
        } elseif (is_array($v)) {
            $v = gos_system_chat_redact_context($v);
        }
        $out[$k] = $v;
    }

    return $out;
}

/** @return list<string> */
function gos_system_chat_active_notes(): array
{
    if (!gos_table_exists('system_chat_notes')) {
        return [];
    }
    $st = gos_pdo()->query('SELECT note_text FROM system_chat_notes WHERE enabled = 1 ORDER BY id ASC');
    $rows = $st ? $st->fetchAll(PDO::FETCH_COLUMN) : [];

    return array_values(array_filter(array_map('strval', $rows ?: [])));
}

function gos_system_chat_session_owned(string $sessionId, int $userId): bool
{
    if (!gos_system_chat_valid_session_id($sessionId)) {
        return false;
    }
    $st = gos_pdo()->prepare('SELECT user_id FROM system_chat_sessions WHERE id = ? LIMIT 1');
    $st->execute([$sessionId]);
    $row = $st->fetch(PDO::FETCH_ASSOC);

    return $row && (int) $row['user_id'] === $userId;
}

/**
 * @return array{events: list<array>, total: int}
 */
function gos_system_chat_audit_list(array $opts): array
{
    $limit = min(500, max(1, (int) ($opts['limit'] ?? 100)));
    $offset = max(0, (int) ($opts['offset'] ?? 0));
    $sinceId = (int) ($opts['since_id'] ?? 0);

    $where = ['1=1'];
    $params = [];

    if (!empty($opts['level'])) {
        $where[] = 'level = ?';
        $params[] = (string) $opts['level'];
    }
    if (!empty($opts['category'])) {
        $where[] = 'category = ?';
        $params[] = (string) $opts['category'];
    }
    if (!empty($opts['session_id']) && gos_system_chat_valid_session_id((string) $opts['session_id'])) {
        $where[] = 'session_id = ?';
        $params[] = (string) $opts['session_id'];
    }
    if ($sinceId > 0) {
        $where[] = 'id > ?';
        $params[] = $sinceId;
    }

    $sqlWhere = implode(' AND ', $where);
    $countSt = gos_pdo()->prepare("SELECT COUNT(*) FROM system_chat_events WHERE {$sqlWhere}");
    $countSt->execute($params);
    $total = (int) $countSt->fetchColumn();

    $order = $sinceId > 0 ? 'ORDER BY id ASC' : 'ORDER BY id DESC';
    $listSt = gos_pdo()->prepare(
        "SELECT id, level, category, message, context, user_id, session_id, created_at
         FROM system_chat_events WHERE {$sqlWhere}
         {$order} LIMIT " . (int) $limit . ' OFFSET ' . (int) $offset
    );
    $listSt->execute($params);
    $events = $listSt->fetchAll(PDO::FETCH_ASSOC) ?: [];
    foreach ($events as &$ev) {
        if (!empty($ev['context']) && is_string($ev['context'])) {
            $ev['context'] = json_decode($ev['context'], true);
        }
    }
    unset($ev);

    return ['events' => $events, 'total' => $total];
}

/** @return list<string> */
function gos_grok_auth_json_candidates(): array
{
    $out = [];
    $env = gos_env('GROKIFY_GROK_AUTH_JSON', '') ?? '';
    if ($env !== '') {
        $out[] = $env;
    }
    $out[] = gos_root() . '/storage/grok-auth.json';
    $out[] = '/etc/grokifyos/grok-auth.json';
    $out[] = '/root/.grok/auth.json';
    $home = getenv('HOME');
    if (is_string($home) && $home !== '' && $home !== '/root') {
        $out[] = rtrim($home, '/') . '/.grok/auth.json';
    }
    $seen = [];
    $uniq = [];
    foreach ($out as $p) {
        if ($p === '' || isset($seen[$p])) {
            continue;
        }
        $seen[$p] = true;
        $uniq[] = $p;
    }

    return $uniq;
}

function gos_grok_auth_json_path(): string
{
    foreach (gos_grok_auth_json_candidates() as $path) {
        if (is_readable($path)) {
            return $path;
        }
    }

    return gos_root() . '/storage/grok-auth.json';
}

/** @return array{entry_key: string, entry: array<string, mixed>, path: string}|null */
function gos_grok_auth_load(): ?array
{
    foreach (gos_grok_auth_json_candidates() as $path) {
        if (!is_readable($path)) {
            continue;
        }
        $raw = @file_get_contents($path);
        if ($raw === false || $raw === '') {
            continue;
        }
        $data = json_decode($raw, true);
        if (!is_array($data) || $data === []) {
            continue;
        }
        foreach ($data as $key => $entry) {
            if (!is_array($entry)) {
                continue;
            }
            $token = (string) ($entry['key'] ?? $entry['access_token'] ?? '');
            if ($token !== '') {
                return ['entry_key' => (string) $key, 'entry' => $entry, 'path' => $path];
            }
        }
    }

    return null;
}

/** @param array<string, mixed> $entry */
function gos_grok_auth_save_entry(string $entryKey, array $entry, ?string $path = null): bool
{
    $path = $path ?: gos_grok_auth_json_path();
    if (!is_writable($path) && !is_writable(dirname($path))) {
        return false;
    }
    $fp = @fopen($path, 'c+');
    if ($fp === false) {
        return false;
    }
    try {
        if (!flock($fp, LOCK_EX)) {
            return false;
        }
        rewind($fp);
        $raw = stream_get_contents($fp);
        $data = is_string($raw) && $raw !== '' ? json_decode($raw, true) : [];
        if (!is_array($data)) {
            $data = [];
        }
        $data[$entryKey] = $entry;
        $json = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
        if ($json === false) {
            return false;
        }
        ftruncate($fp, 0);
        rewind($fp);
        fwrite($fp, $json . "\n");
        fflush($fp);

        return true;
    } finally {
        flock($fp, LOCK_UN);
        fclose($fp);
    }
}

function gos_grok_auth_token_expired(array $entry, int $skewSeconds = 120): bool
{
    $expiresAt = (string) ($entry['expires_at'] ?? '');
    if ($expiresAt === '') {
        return false;
    }
    $ts = strtotime($expiresAt);
    if ($ts === false) {
        return false;
    }

    return $ts <= (time() + $skewSeconds);
}

/**
 * Ask the root-owned bridge to copy CLI ~/.grok/auth.json → storage/grok-auth.json.
 * PHP-FPM (www-data) cannot read the CLI auth file itself after `grok login`.
 *
 * @return array{ok: bool, synced?: bool, reason?: string, error?: string}
 */
function gos_grok_auth_request_bridge_sync(bool $force = true): array
{
    static $lastAttemptAt = 0;
    static $lastResult = null;
    // Avoid hammering the bridge when many usage polls race.
    if (!$force && is_array($lastResult) && (time() - $lastAttemptAt) < 15) {
        return $lastResult;
    }
    if ($force && (time() - $lastAttemptAt) < 5 && is_array($lastResult) && !empty($lastResult['ok'])) {
        return $lastResult;
    }

    $base = rtrim((string) (gos_env('GROKIFY_BRIDGE_URL', 'http://127.0.0.1:8876') ?? 'http://127.0.0.1:8876'), '/');
    $url = $base . '/sync-grok-auth' . ($force ? '?force=1' : '');
    $ch = curl_init($url);
    if ($ch === false) {
        return ['ok' => false, 'error' => 'curl_init_failed'];
    }
    curl_setopt_array($ch, [
        CURLOPT_CUSTOMREQUEST => $force ? 'POST' : 'GET',
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 8,
        CURLOPT_CONNECTTIMEOUT => 2,
        CURLOPT_HTTPHEADER => ['Accept: application/json'],
    ]);
    $resp = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);
    $lastAttemptAt = time();
    if (!is_string($resp) || $resp === '') {
        $lastResult = ['ok' => false, 'error' => $err !== '' ? $err : 'empty_response', 'http_code' => $code];

        return $lastResult;
    }
    $json = json_decode($resp, true);
    if (!is_array($json)) {
        $lastResult = ['ok' => false, 'error' => 'parse_failed', 'http_code' => $code];

        return $lastResult;
    }
    $json['http_code'] = $code;
    $lastResult = $json;

    return $lastResult;
}

/**
 * Call the bridge device-login endpoints (OIDC device code → clickable xAI link).
 *
 * @param 'start'|'status'|'logout' $action
 * @return array<string, mixed>
 */
function gos_grok_auth_bridge_login(string $action = 'start', bool $force = false): array
{
    if ($action !== 'status' && $action !== 'logout') {
        $action = 'start';
    }
    $base = rtrim((string) (gos_env('GROKIFY_BRIDGE_URL', 'http://127.0.0.1:8876') ?? 'http://127.0.0.1:8876'), '/');
    $qs = [];
    if ($force && $action !== 'logout') {
        $qs[] = 'force=1';
    }
    if ($action === 'status' && $force) {
        // status?force=1 alone does not start; start=1 does.
        $qs[] = 'start=1';
    }
    $url = $base . '/grok-login/' . $action . ($qs !== [] ? ('?' . implode('&', $qs)) : '');
    $ch = curl_init($url);
    if ($ch === false) {
        return ['ok' => false, 'error' => 'curl_init_failed', 'needed' => true];
    }
    // logout + start need POST; status is GET.
    $method = ($action === 'status') ? 'GET' : 'POST';
    curl_setopt_array($ch, [
        CURLOPT_CUSTOMREQUEST => $method,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => $action === 'logout' ? 20 : 15,
        CURLOPT_CONNECTTIMEOUT => 2,
        CURLOPT_HTTPHEADER => ['Accept: application/json'],
    ]);
    $resp = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);
    if (!is_string($resp) || $resp === '') {
        return [
            'ok' => false,
            'needed' => true,
            'error' => $err !== '' ? $err : 'empty_response',
            'http_code' => $code,
            'message' => $action === 'logout'
                ? 'Could not reach bridge to log out of Grok'
                : 'Could not reach bridge to start Grok login',
        ];
    }
    $json = json_decode($resp, true);
    if (!is_array($json)) {
        return [
            'ok' => false,
            'needed' => true,
            'error' => 'parse_failed',
            'http_code' => $code,
            'message' => 'Invalid bridge login response',
        ];
    }
    $json['http_code'] = $code;
    if (!isset($json['needed'])) {
        $json['needed'] = true;
    }

    return $json;
}

/**
 * Attach a device-login payload so clients can open verification_uri_complete.
 *
 * @param array<string, mixed> $payload
 * @return array<string, mixed>
 */
function gos_grok_auth_with_login_link(array $payload, bool $forceNew = false): array
{
    $login = gos_grok_auth_bridge_login('start', $forceNew);
    $payload['login'] = $login;
    if (!empty($login['verification_uri_complete'])) {
        $payload['message'] = ($payload['message'] ?? 'Grok re-login needed')
            . ' — open the login link on your phone, approve, then refresh usage.';
        $payload['login_url'] = (string) $login['verification_uri_complete'];
        if (!empty($login['user_code'])) {
            $payload['login_user_code'] = (string) $login['user_code'];
        }
    }

    return $payload;
}

/** @return array{ok: bool, token?: string, entry?: array<string, mixed>, error?: string, message?: string, http_code?: int, detail?: mixed} */
function gos_grok_auth_ensure_token(bool $allowBridgeResync = true): array
{
    $loaded = gos_grok_auth_load();
    if ($loaded === null) {
        if ($allowBridgeResync) {
            $sync = gos_grok_auth_request_bridge_sync(true);
            if (!empty($sync['ok'])) {
                return gos_grok_auth_ensure_token(false);
            }
        }
        $candidates = gos_grok_auth_json_candidates();
        $detail = [];
        foreach ($candidates as $p) {
            $detail[] = $p . (is_readable($p) ? ' (readable)' : ' (unreadable)');
        }

        return [
            'ok' => false,
            'error' => 'auth_missing',
            'message' => 'Grok Build auth unavailable — run `scripts/sync-grok-auth.sh` after `grok login` so PHP (www-data) can read auth.json.',
            'candidates' => $detail,
        ];
    }
    $entryKey = $loaded['entry_key'];
    $entry = $loaded['entry'];
    $authPath = isset($loaded['path']) ? (string) $loaded['path'] : null;
    $token = (string) ($entry['key'] ?? $entry['access_token'] ?? '');
    if ($token !== '' && !gos_grok_auth_token_expired($entry)) {
        return ['ok' => true, 'token' => $token, 'entry' => $entry];
    }

    $refresh = (string) ($entry['refresh_token'] ?? '');
    $clientId = (string) ($entry['oidc_client_id'] ?? '');
    if ($refresh === '' || $clientId === '') {
        // Never hand an already-expired token to callers — it only produces misleading 401s.
        if ($token !== '' && !gos_grok_auth_token_expired($entry)) {
            return ['ok' => true, 'token' => $token, 'entry' => $entry];
        }
        if ($allowBridgeResync) {
            $sync = gos_grok_auth_request_bridge_sync(true);
            if (!empty($sync['ok']) && !empty($sync['synced'])) {
                return gos_grok_auth_ensure_token(false);
            }
        }

        return [
            'ok' => false,
            'error' => 'auth_refresh_unavailable',
            'message' => 'Grok access token expired and no refresh_token is available. Run `grok login` then `./scripts/sync-grok-auth.sh`.',
        ];
    }

    $body = http_build_query([
        'grant_type' => 'refresh_token',
        'refresh_token' => $refresh,
        'client_id' => $clientId,
    ]);
    $ch = curl_init('https://auth.x.ai/oauth2/token');
    if ($ch === false) {
        return ['ok' => false, 'error' => 'curl_init_failed'];
    }
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => $body,
        CURLOPT_HTTPHEADER => ['Content-Type: application/x-www-form-urlencoded', 'Accept: application/json'],
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 20,
    ]);
    $resp = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);
    if (!is_string($resp) || $resp === '' || $code < 200 || $code >= 300) {
        $detail = is_string($resp) && $resp !== '' ? $resp : $err;
        $revoked = is_string($detail) && (
            str_contains($detail, 'invalid_grant')
            || str_contains($detail, 'revoked')
            || str_contains($detail, 'expired')
        );
        // Only reuse the existing access token if it is still unexpired.
        if ($token !== '' && !gos_grok_auth_token_expired($entry)) {
            return ['ok' => true, 'token' => $token, 'entry' => $entry];
        }
        // CLI re-login rotates refresh tokens; bridge (root) can pull the new auth.json.
        if ($allowBridgeResync) {
            $sync = gos_grok_auth_request_bridge_sync(true);
            if (!empty($sync['ok'])) {
                $retry = gos_grok_auth_ensure_token(false);
                if (!empty($retry['ok'])) {
                    $retry['recovered_via'] = 'bridge_auth_sync';

                    return $retry;
                }
            }
        }

        return [
            'ok' => false,
            'error' => $revoked ? 'auth_refresh_revoked' : 'auth_refresh_failed',
            'http_code' => $code,
            'message' => $revoked
                ? 'Grok refresh token revoked/expired — run `grok login` then `./scripts/sync-grok-auth.sh` so PHP can read the new auth.json.'
                : 'Grok auth refresh failed (HTTP ' . $code . ').',
            'detail' => $detail,
        ];
    }
    $json = json_decode($resp, true);
    if (!is_array($json) || empty($json['access_token'])) {
        return ['ok' => false, 'error' => 'auth_refresh_parse'];
    }
    $entry['key'] = (string) $json['access_token'];
    if (!empty($json['refresh_token'])) {
        $entry['refresh_token'] = (string) $json['refresh_token'];
    }
    $expiresIn = (int) ($json['expires_in'] ?? 21600);
    $entry['expires_at'] = gmdate('Y-m-d\TH:i:s.u\Z', time() + max(60, $expiresIn));
    gos_grok_auth_save_entry($entryKey, $entry, $authPath);

    return ['ok' => true, 'token' => (string) $entry['key'], 'entry' => $entry];
}

/** @return array<string, mixed> */
function gos_grok_build_fetch_usage(bool $forceRefresh = false): array
{
    static $cache = null;
    static $cacheAt = 0;
    $ttl = 60;
    if (!$forceRefresh && is_array($cache) && (time() - $cacheAt) < $ttl) {
        return $cache;
    }

    $auth = gos_grok_auth_ensure_token();
    if (empty($auth['ok']) || empty($auth['token'])) {
        $err = (string) ($auth['error'] ?? 'auth_failed');
        $needsLogin = in_array($err, [
            'auth_missing',
            'auth_refresh_unavailable',
            'auth_refresh_revoked',
            'auth_refresh_failed',
            'auth_failed',
        ], true);
        $payload = [
            'ok' => false,
            'error' => $err,
            'message' => $auth['message'] ?? 'Grok Build auth unavailable — re-login required.',
            'http_code' => $auth['http_code'] ?? null,
        ];
        if ($needsLogin) {
            // Auto-start device OAuth so the app can show a one-tap xAI login link.
            return gos_grok_auth_with_login_link($payload, false);
        }

        return $payload;
    }

    $url = gos_env('GROKIFY_GROK_BILLING_URL', 'https://cli-chat-proxy.grok.com/v1/billing?format=credits')
        ?? 'https://cli-chat-proxy.grok.com/v1/billing?format=credits';
    $ch = curl_init($url);
    if ($ch === false) {
        return ['ok' => false, 'error' => 'curl_init_failed'];
    }
    curl_setopt_array($ch, [
        CURLOPT_HTTPGET => true,
        CURLOPT_HTTPHEADER => [
            'Authorization: Bearer ' . $auth['token'],
            'Accept: application/json',
            'User-Agent: grokifyos-bridge/usage',
            'x-grok-client-version: 0.2.99',
            'x-grok-client-mode: cli',
        ],
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 20,
    ]);
    $resp = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);

    if (!is_string($resp) || $resp === '' || $code < 200 || $code >= 300) {
        if ($code === 401 && !$forceRefresh) {
            $loaded = gos_grok_auth_load();
            if ($loaded !== null) {
                $entry = $loaded['entry'];
                // Force ensure_token to refresh on the next attempt.
                $entry['expires_at'] = gmdate('Y-m-d\TH:i:s\Z', time() - 10);
                gos_grok_auth_save_entry($loaded['entry_key'], $entry, $loaded['path'] ?? null);
            }

            return gos_grok_build_fetch_usage(true);
        }

        $upstreamBody = is_string($resp) ? trim($resp) : '';
        $authish = $code === 401 || $code === 403
            || str_contains($upstreamBody, 'expired credentials')
            || str_contains($upstreamBody, 'Invalid or expired')
            || str_contains($upstreamBody, 'PermissionDenied');

        $payload = [
            'ok' => false,
            'error' => $authish ? 'billing_auth_failed' : 'billing_fetch_failed',
            'http_code' => $code,
            'message' => $authish
                ? 'Grok billing rejected credentials (HTTP ' . $code . ') — re-login required.'
                : ($err ?: ('Billing upstream error (HTTP ' . $code . ')')),
            'detail' => $upstreamBody !== '' ? mb_substr($upstreamBody, 0, 400) : null,
        ];
        if ($authish) {
            return gos_grok_auth_with_login_link($payload, false);
        }

        return $payload;
    }

    $json = json_decode($resp, true);
    if (!is_array($json)) {
        return ['ok' => false, 'error' => 'billing_parse_failed'];
    }

    $config = is_array($json['config'] ?? null) ? $json['config'] : $json;
    $period = is_array($config['currentPeriod'] ?? null) ? $config['currentPeriod'] : [];
    $products = [];
    if (is_array($config['productUsage'] ?? null)) {
        foreach ($config['productUsage'] as $p) {
            if (!is_array($p)) {
                continue;
            }
            $products[] = [
                'product' => (string) ($p['product'] ?? ''),
                'usage_percent' => isset($p['usagePercent']) ? (float) $p['usagePercent'] : null,
            ];
        }
    }

    $percent = isset($config['creditUsagePercent']) ? (float) $config['creditUsagePercent'] : 0.0;
    $resetAt = (string) ($period['end'] ?? $config['billingPeriodEnd'] ?? '');
    $periodStart = (string) ($period['start'] ?? $config['billingPeriodStart'] ?? '');
    $tier = (string) ($json['subscriptionTier'] ?? $config['subscriptionTier'] ?? '');

    $prepaid = 0.0;
    if (is_array($config['prepaidBalance'] ?? null) && isset($config['prepaidBalance']['val'])) {
        $prepaid = (float) $config['prepaidBalance']['val'];
    }
    $onDemandUsed = 0.0;
    if (is_array($config['onDemandUsed'] ?? null) && isset($config['onDemandUsed']['val'])) {
        $onDemandUsed = (float) $config['onDemandUsed']['val'];
    }
    $onDemandCap = 0.0;
    if (is_array($config['onDemandCap'] ?? null) && isset($config['onDemandCap']['val'])) {
        $onDemandCap = (float) $config['onDemandCap']['val'];
    }

    $out = [
        'ok' => true,
        'usage_percent' => $percent,
        'remaining_percent' => max(0.0, 100.0 - $percent),
        'period_type' => (string) ($period['type'] ?? 'USAGE_PERIOD_TYPE_WEEKLY'),
        'period_start' => $periodStart,
        'period_end' => $resetAt,
        'reset_at' => $resetAt,
        'subscription_tier' => $tier,
        'products' => $products,
        'prepaid_balance' => $prepaid,
        'on_demand_used' => $onDemandUsed,
        'on_demand_cap' => $onDemandCap,
        'is_unified_billing' => !empty($config['isUnifiedBillingUser']),
        'fetched_at' => gmdate('c'),
        'source' => 'cli-chat-proxy',
    ];
    $cache = $out;
    $cacheAt = time();

    return $out;
}

/** @return array{devices: list<array>, active: list<array>} */
function gos_devices_for_user(int $userId): array
{
    if (!gos_table_exists('grokify_devices')) {
        return ['devices' => [], 'active' => []];
    }
    $st = gos_pdo()->prepare(
        'SELECT id, device_name, token_prefix, app_version_name, app_version_code,
                last_seen_at, last_ip, created_at, revoked_at
         FROM grokify_devices WHERE user_id = ? ORDER BY created_at DESC'
    );
    $st->execute([$userId]);
    $devices = $st->fetchAll(PDO::FETCH_ASSOC) ?: [];
    $active = array_values(array_filter($devices, static fn ($d) => empty($d['revoked_at'])));

    return ['devices' => $devices, 'active' => $active];
}

function gos_apk_storage_dir(): string
{
    $override = gos_env('GROKIFY_APK_DIR');
    $dir = (is_string($override) && $override !== '')
        ? rtrim($override, '/')
        : (gos_root() . '/storage/apk');
    if (!is_dir($dir)) {
        @mkdir($dir, 0750, true);
    }

    return $dir;
}

function gos_public_origin(): string
{
    $url = gos_site_url();
    if (is_string($url) && $url !== '') {
        return rtrim($url, '/');
    }

    return 'https://grokifyos.grokpot.io';
}

/**
 * Normalize APK channel. Valid: phone | wear. Unknown → phone.
 */
function gos_apk_channel(?string $channel): string
{
    $c = strtolower(trim((string) $channel));
    return in_array($c, ['phone', 'wear', 'wear-face'], true) ? $c : 'phone';
}

/**
 * Whether grokify_apk_releases has a channel column (migration 003).
 */
function gos_apk_has_channel_column(): bool
{
    static $has = null;
    if ($has !== null) {
        return $has;
    }
    if (!gos_table_exists('grokify_apk_releases')) {
        $has = false;
        return false;
    }
    try {
        $st = gos_pdo()->query("SHOW COLUMNS FROM grokify_apk_releases LIKE 'channel'");
        $has = $st && $st->fetch(PDO::FETCH_ASSOC) !== false;
    } catch (Throwable $e) {
        $has = false;
    }

    return $has;
}

/**
 * Latest active APK for a channel (default phone — backward compatible).
 *
 * @return array<string, mixed>|null
 */
function gos_latest_apk(?string $channel = 'phone'): ?array
{
    if (!gos_table_exists('grokify_apk_releases')) {
        return null;
    }
    $channel = gos_apk_channel($channel);
    $hasChannel = gos_apk_has_channel_column();

    if ($hasChannel) {
        $st = gos_pdo()->prepare(
            'SELECT id, version_code, version_name, channel, file_name, file_path, file_size, sha256, changelog,
                    min_sdk, is_active, created_by, created_at
             FROM grokify_apk_releases
             WHERE is_active = 1 AND channel = ?
             ORDER BY version_code DESC
             LIMIT 1'
        );
        $st->execute([$channel]);
        $row = $st->fetch(PDO::FETCH_ASSOC);
    } else {
        // Pre-migration: single stream (phone only)
        if ($channel !== 'phone') {
            return null;
        }
        $st = gos_pdo()->query(
            'SELECT id, version_code, version_name, file_name, file_path, file_size, sha256, changelog,
                    min_sdk, is_active, created_by, created_at
             FROM grokify_apk_releases
             WHERE is_active = 1
             ORDER BY version_code DESC
             LIMIT 1'
        );
        $row = $st ? $st->fetch(PDO::FETCH_ASSOC) : false;
        if (is_array($row)) {
            $row['channel'] = 'phone';
        }
    }

    return is_array($row) ? $row : null;
}

/**
 * Public summary of a release for API responses.
 *
 * @param array<string, mixed> $release
 * @return array<string, mixed>
 */
function gos_apk_public_summary(array $release, ?string $site = null): array
{
    $site = $site ?? rtrim(gos_site_url(), '/');
    $channel = gos_apk_channel(isset($release['channel']) ? (string) $release['channel'] : 'phone');

    return [
        'channel' => $channel,
        'version_code' => (int) $release['version_code'],
        'version_name' => $release['version_name'],
        'file_size' => (int) ($release['file_size'] ?? 0),
        'sha256' => $release['sha256'] ?? null,
        'changelog' => $release['changelog'] ?? null,
        'min_sdk' => isset($release['min_sdk']) && $release['min_sdk'] !== null ? (int) $release['min_sdk'] : null,
        'download_url' => $site . '/api/apk-download.php?channel=' . rawurlencode($channel),
        'created_at' => $release['created_at'] ?? null,
    ];
}

/**
 * Register a built or uploaded APK as the active release for a channel.
 *
 * @return array{ok: bool, release?: array<string, mixed>, error?: string}
 */
function gos_register_apk_upload(
    string $tmpPath,
    string $originalName,
    int $versionCode,
    string $versionName,
    ?string $changelog,
    int $createdBy,
    ?int $minSdk = null,
    string $channel = 'phone'
): array {
    $channel = gos_apk_channel($channel);
    if ($versionCode < 1 || $versionName === '') {
        return ['ok' => false, 'error' => 'invalid_version'];
    }
    if (!is_uploaded_file($tmpPath) && !is_readable($tmpPath)) {
        return ['ok' => false, 'error' => 'invalid_file'];
    }

    $sha = hash_file('sha256', $tmpPath);
    if ($sha === false) {
        return ['ok' => false, 'error' => 'hash_failed'];
    }
    $size = filesize($tmpPath);
    if ($size === false || $size < 1) {
        return ['ok' => false, 'error' => 'empty_file'];
    }

    $safeName = 'grokifyos-' . $channel . '-v' . $versionCode . '-'
        . preg_replace('/[^a-zA-Z0-9._-]+/', '_', $versionName) . '.apk';
    $destDir = gos_apk_storage_dir();
    $dest = $destDir . '/' . $safeName;

    $stored = false;
    if (is_uploaded_file($tmpPath)) {
        $stored = @move_uploaded_file($tmpPath, $dest) || @copy($tmpPath, $dest);
    } else {
        $stored = @copy($tmpPath, $dest);
    }
    if (!$stored || !is_readable($dest)) {
        return ['ok' => false, 'error' => 'store_failed'];
    }
    @chmod($dest, 0640);
    @chown($dest, 'www-data');
    @chgrp($dest, 'www-data');

    $relPath = 'storage/apk/' . $safeName;
    $pdo = gos_pdo();
    $hasChannel = gos_apk_has_channel_column();
    $pdo->beginTransaction();
    try {
        if ($hasChannel) {
            $pdo->prepare('UPDATE grokify_apk_releases SET is_active = 0 WHERE is_active = 1 AND channel = ?')
                ->execute([$channel]);
            $st = $pdo->prepare(
                'INSERT INTO grokify_apk_releases
                 (version_code, version_name, channel, file_name, file_path, file_size, sha256, changelog, min_sdk, is_active, created_by)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                 ON DUPLICATE KEY UPDATE
                   version_name = VALUES(version_name),
                   file_name = VALUES(file_name),
                   file_path = VALUES(file_path),
                   file_size = VALUES(file_size),
                   sha256 = VALUES(sha256),
                   changelog = VALUES(changelog),
                   min_sdk = VALUES(min_sdk),
                   is_active = 1,
                   created_by = VALUES(created_by),
                   created_at = CURRENT_TIMESTAMP'
            );
            $st->execute([
                $versionCode,
                mb_substr($versionName, 0, 32),
                $channel,
                $safeName,
                $relPath,
                $size,
                $sha,
                $changelog,
                $minSdk,
                $createdBy > 0 ? $createdBy : null,
            ]);
        } else {
            // Pre-migration fallback (phone only)
            if ($channel !== 'phone') {
                $pdo->rollBack();
                @unlink($dest);

                return ['ok' => false, 'error' => 'channel_not_supported'];
            }
            $pdo->prepare('UPDATE grokify_apk_releases SET is_active = 0 WHERE is_active = 1')->execute();
            $st = $pdo->prepare(
                'INSERT INTO grokify_apk_releases
                 (version_code, version_name, file_name, file_path, file_size, sha256, changelog, min_sdk, is_active, created_by)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                 ON DUPLICATE KEY UPDATE
                   version_name = VALUES(version_name),
                   file_name = VALUES(file_name),
                   file_path = VALUES(file_path),
                   file_size = VALUES(file_size),
                   sha256 = VALUES(sha256),
                   changelog = VALUES(changelog),
                   min_sdk = VALUES(min_sdk),
                   is_active = 1,
                   created_by = VALUES(created_by),
                   created_at = CURRENT_TIMESTAMP'
            );
            $st->execute([
                $versionCode,
                mb_substr($versionName, 0, 32),
                $safeName,
                $relPath,
                $size,
                $sha,
                $changelog,
                $minSdk,
                $createdBy > 0 ? $createdBy : null,
            ]);
        }
        $pdo->commit();
    } catch (Throwable $e) {
        $pdo->rollBack();
        @unlink($dest);

        return ['ok' => false, 'error' => 'db_failed'];
    }

    $release = gos_latest_apk($channel);

    return $release ? ['ok' => true, 'release' => $release] : ['ok' => false, 'error' => 'not_found'];
}

function gos_apk_absolute_path(array $release): string
{
    $path = (string) ($release['file_path'] ?? '');
    $name = (string) ($release['file_name'] ?? '');
    if ($name === '' && $path !== '') {
        $name = basename($path);
    }
    if ($name !== '') {
        $stored = gos_apk_storage_dir() . '/' . $name;
        if (is_readable($stored) || $path === '' || !str_starts_with($path, '/')) {
            return $stored;
        }
    }
    if ($path === '') {
        return '';
    }
    if ($path[0] === '/') {
        return $path;
    }

    return gos_root() . '/' . ltrim($path, '/');
}
