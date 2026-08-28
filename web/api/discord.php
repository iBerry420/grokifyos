<?php

declare(strict_types=1);

/**
 * Device-authenticated proxy to the local Avalynn Discord backend (loopback :4201).
 *
 * Phone inner app talks here. avalynn.ai is unchanged — this only reads the
 * existing discord_backend_password from Avalynn settings and forwards an
 * allowlisted slice of /api/discord/* plus local captured emoji files.
 *
 * Do not log bot tokens, JWTs, or the backend password.
 */

require_once __DIR__ . '/_common.php';
require_once dirname(__DIR__) . '/includes/discord_local.php';
require_once dirname(__DIR__) . '/includes/discord_media.php';
require_once dirname(__DIR__) . '/includes/discord_ai.php';

function gos_discord_backend_url(): string
{
    return rtrim((string) (gos_env('GROKIFY_DISCORD_URL', 'http://127.0.0.1:4201') ?? 'http://127.0.0.1:4201'), '/');
}

function gos_discord_avalynn_user_id(): int
{
    $id = (int) (gos_env('GROKIFY_DISCORD_AVALYNN_USER_ID', '1') ?? '1');
    return $id > 0 ? $id : 1;
}

function gos_discord_avalynn_env_path(): string
{
    return (string) (gos_env('GROKIFY_AVALYNN_ENV', '/var/www/avalynn/.env') ?? '/var/www/avalynn/.env');
}

function gos_discord_emoji_dir(): string
{
    $override = trim((string) (gos_env('GROKIFY_DISCORD_EMOJI_DIR', '') ?? ''));
    if ($override !== '' && is_dir($override)) {
        return rtrim($override, '/');
    }
    return '/var/www/avalynn/uploads/emojis';
}

function gos_discord_jwt_path(): string
{
    return gos_root() . '/storage/discord-jwt.json';
}

/**
 * @return array<string, string>
 */
function gos_discord_parse_env_file(string $path): array
{
    $out = [];
    if ($path === '' || !is_readable($path)) {
        return $out;
    }
    $lines = file($path, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    if ($lines === false) {
        return $out;
    }
    foreach ($lines as $line) {
        $line = trim($line);
        if ($line === '' || str_starts_with($line, '#') || !str_contains($line, '=')) {
            continue;
        }
        [$key, $value] = explode('=', $line, 2);
        $key = trim($key);
        if ($key === '') {
            continue;
        }
        $value = trim($value);
        if (
            (str_starts_with($value, '"') && str_ends_with($value, '"'))
            || (str_starts_with($value, "'") && str_ends_with($value, "'"))
        ) {
            $value = substr($value, 1, -1);
        }
        $out[$key] = $value;
    }
    return $out;
}

function gos_discord_avalynn_pdo(): ?PDO
{
    static $pdo = null;
    if (gos_pdo_alive($pdo)) {
        return $pdo;
    }
    $pdo = null;

    $env = gos_discord_parse_env_file(gos_discord_avalynn_env_path());
    $host = (string) (gos_env('GROKIFY_DISCORD_DB_HOST', $env['DB_HOST'] ?? 'localhost') ?? 'localhost');
    $name = (string) (gos_env('GROKIFY_DISCORD_DB_NAME', $env['DB_NAME'] ?? 'avalynn_chat') ?? 'avalynn_chat');
    $user = (string) (gos_env('GROKIFY_DISCORD_DB_USER', $env['DB_USER'] ?? 'root') ?? 'root');
    $pass = (string) (gos_env('GROKIFY_DISCORD_DB_PASS', $env['DB_PASS'] ?? $env['DB_PASSWORD'] ?? '') ?? '');
    $port = (int) (gos_env('GROKIFY_DISCORD_DB_PORT', $env['DB_PORT'] ?? '3306') ?? '3306');

    $opts = [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ];
    try {
        if ($host === 'localhost' || $host === '127.0.0.1') {
            $sock = (string) (gos_env('GROKIFY_DISCORD_DB_SOCKET', '/var/run/mysqld/mysqld.sock') ?? '/var/run/mysqld/mysqld.sock');
            $dsn = sprintf('mysql:unix_socket=%s;dbname=%s;charset=utf8mb4', $sock, $name);
            $pdo = new PDO($dsn, $user, $pass, $opts);
        } else {
            $dsn = sprintf('mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4', $host, $port, $name);
            $pdo = new PDO($dsn, $user, $pass, $opts);
        }
        return $pdo;
    } catch (Throwable) {
        $pdo = null;
        return null;
    }
}

function gos_discord_backend_password(): string
{
    $direct = trim((string) (gos_env('GROKIFY_DISCORD_PASSWORD', '') ?? ''));
    if ($direct !== '') {
        return $direct;
    }
    $pdo = gos_discord_avalynn_pdo();
    if ($pdo === null) {
        return '';
    }
    try {
        $stmt = $pdo->prepare('SELECT setting_value FROM settings WHERE setting_key = ? LIMIT 1');
        $stmt->execute(['discord_backend_password']);
        $row = $stmt->fetch();
        return is_array($row) ? trim((string) ($row['setting_value'] ?? '')) : '';
    } catch (Throwable) {
        return '';
    }
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_curl(
    string $httpMethod,
    string $url,
    ?string $jsonBody,
    int $timeoutSec,
    array $headers,
): array {
    if (!function_exists('curl_init')) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'curl_missing'];
    }
    $ch = curl_init($url);
    if ($ch === false) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'curl_init'];
    }
    $opts = [
        CURLOPT_CUSTOMREQUEST => $httpMethod,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => $headers,
        CURLOPT_TIMEOUT => max(3, $timeoutSec),
        CURLOPT_CONNECTTIMEOUT => 3,
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_PROTOCOLS => CURLPROTO_HTTP | CURLPROTO_HTTPS,
    ];
    if ($jsonBody !== null) {
        $opts[CURLOPT_POSTFIELDS] = $jsonBody;
    }
    curl_setopt_array($ch, $opts);
    $raw = curl_exec($ch);
    $errno = curl_errno($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($raw === false || $errno !== 0) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'discord_unreachable'];
    }
    $decoded = null;
    if (is_string($raw) && $raw !== '') {
        $decoded = json_decode($raw, true);
        if ($decoded === null && json_last_error() !== JSON_ERROR_NONE) {
            $decoded = $raw;
        }
    }
    if ($status >= 400 || $status === 0) {
        $msg = null;
        if (is_array($decoded) && isset($decoded['error'])) {
            $msg = is_string($decoded['error']) ? $decoded['error'] : json_encode($decoded['error']);
        }
        return [
            'ok' => false,
            'status' => $status > 0 ? $status : 502,
            'data' => gos_discord_redact($decoded),
            'error' => $msg ?: ('discord_http_' . $status),
        ];
    }
    return [
        'ok' => true,
        'status' => $status > 0 ? $status : 200,
        'data' => gos_discord_redact($decoded),
        'error' => null,
    ];
}

function gos_discord_redact(mixed $data): mixed
{
    if (is_array($data)) {
        $out = [];
        foreach ($data as $k => $v) {
            if (is_string($k) && in_array(strtolower($k), ['token', 'password', 'jwt', 'secret', 'authorization'], true)) {
                $out[$k] = is_string($v) && $v !== '' ? '***hidden***' : $v;
            } else {
                $out[$k] = gos_discord_redact($v);
            }
        }
        return $out;
    }
    return $data;
}

function gos_discord_login_raw(string $password): string
{
    $base = gos_discord_backend_url();
    if (!function_exists('curl_init') || $password === '') {
        return '';
    }
    $ch = curl_init($base . '/api/auth/login');
    if ($ch === false) {
        return '';
    }
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => ['Content-Type: application/json', 'Accept: application/json'],
        CURLOPT_POSTFIELDS => json_encode(['password' => $password], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        CURLOPT_TIMEOUT => 10,
        CURLOPT_CONNECTTIMEOUT => 3,
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_PROTOCOLS => CURLPROTO_HTTP | CURLPROTO_HTTPS,
    ]);
    $raw = curl_exec($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if (!is_string($raw) || $status !== 200) {
        return '';
    }
    $data = json_decode($raw, true);
    $token = is_array($data) ? trim((string) ($data['token'] ?? '')) : '';
    return $token !== '***hidden***' ? $token : '';
}

function gos_discord_jwt(): string
{
    $path = gos_discord_jwt_path();
    if (is_readable($path)) {
        $raw = file_get_contents($path);
        $cached = is_string($raw) ? json_decode($raw, true) : null;
        if (is_array($cached)) {
            $token = (string) ($cached['token'] ?? '');
            $exp = (int) ($cached['exp'] ?? 0);
            if ($token !== '' && $token !== '***hidden***' && strlen($token) > 20 && $exp > (time() + 30)) {
                return $token;
            }
        }
    }

    $password = gos_discord_backend_password();
    if ($password === '') {
        return '';
    }
    $token = gos_discord_login_raw($password);
    if ($token === '' || strlen($token) < 20) {
        return '';
    }
    $payload = json_encode([
        'token' => $token,
        'exp' => time() + 6 * 3600,
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if (is_string($payload)) {
        $dir = dirname($path);
        if (!is_dir($dir)) {
            @mkdir($dir, 0770, true);
        }
        @file_put_contents($path, $payload, LOCK_EX);
        @chmod($path, 0660);
    }
    return $token;
}

function gos_discord_assert_loopback(string $url): bool
{
    $parsed = parse_url($url);
    if (!is_array($parsed) || empty($parsed['host'])) {
        return false;
    }
    $host = strtolower((string) $parsed['host']);
    return in_array($host, ['127.0.0.1', 'localhost', '::1'], true);
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_backend(string $httpMethod, string $path, ?string $jsonBody, int $timeoutSec = 45): array
{
    $path = '/' . ltrim($path, '/');
    if (!preg_match('#^/api/(discord|auth)(/[A-Za-z0-9._~:/?&=%,-]*)?$#', $path)) {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'bad_path'];
    }
    $base = gos_discord_backend_url();
    if (!gos_discord_assert_loopback($base)) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'discord_url_not_loopback'];
    }
    $jwt = gos_discord_jwt();
    if ($jwt === '') {
        return ['ok' => false, 'status' => 502, 'data' => null, 'error' => 'discord_auth_failed'];
    }
    $headers = [
        'Accept: application/json',
        'Authorization: Bearer ' . $jwt,
        'X-Avalynn-User-Id: ' . (string) gos_discord_avalynn_user_id(),
    ];
    if ($jsonBody !== null) {
        $headers[] = 'Content-Type: application/json';
    }
    return gos_discord_curl($httpMethod, $base . $path, $jsonBody, $timeoutSec, $headers);
}

function gos_discord_send(array $result): never
{
    $code = (int) ($result['status'] ?? 200);
    if ($code < 100 || $code > 599) {
        $code = !empty($result['ok']) ? 200 : 502;
    }
    $payload = [
        'ok' => (bool) $result['ok'],
        'data' => $result['data'],
    ];
    if (!empty($result['error'])) {
        $payload['error'] = $result['error'];
    }
    gos_api_json($payload, $code);
}

function gos_discord_id(mixed $raw): string
{
    $s = trim((string) $raw);
    if ($s === '' || !preg_match('/^[0-9]{1,24}$/', $s)) {
        return '';
    }
    return $s;
}

function gos_discord_snowflake(mixed $raw): string
{
    $s = trim((string) $raw);
    if ($s === '' || !preg_match('/^[0-9]{5,32}$/', $s)) {
        return '';
    }
    return $s;
}

function gos_discord_filename(mixed $raw): string
{
    $s = trim((string) $raw);
    if ($s === '' || str_contains($s, '/') || str_contains($s, '\\') || str_contains($s, '..')) {
        return '';
    }
    if (!preg_match('/^[A-Za-z0-9._-]{1,180}$/', $s)) {
        return '';
    }
    return $s;
}

/**
 * @param array<string, mixed> $src
 * @param list<string> $keys
 * @return array<string, string>
 */
function gos_discord_pick_query(array $src, array $keys): array
{
    $out = [];
    foreach ($keys as $key) {
        if (!array_key_exists($key, $src)) {
            continue;
        }
        $val = $src[$key];
        if (is_array($val)) {
            $parts = [];
            foreach ($val as $item) {
                $s = trim((string) $item);
                if ($s !== '' && strlen($s) <= 80) {
                    $parts[] = $s;
                }
            }
            if ($parts !== []) {
                $out[$key] = $parts;
            }
            continue;
        }
        $s = trim((string) $val);
        if ($s === '' || strlen($s) > 240) {
            continue;
        }
        $out[$key] = $s;
    }
    return $out;
}

/**
 * @param array<string, mixed> $picked
 */
function gos_discord_query_string(array $picked): string
{
    if ($picked === []) {
        return '';
    }
    $pairs = [];
    foreach ($picked as $k => $v) {
        if (is_array($v)) {
            foreach ($v as $item) {
                $pairs[] = rawurlencode((string) $k) . '=' . rawurlencode((string) $item);
            }
        } else {
            $pairs[] = rawurlencode((string) $k) . '=' . rawurlencode((string) $v);
        }
    }
    return $pairs === [] ? '' : ('?' . implode('&', $pairs));
}

function gos_discord_bool(mixed $v): ?bool
{
    if (is_bool($v)) {
        return $v;
    }
    if ($v === 1 || $v === '1' || $v === 'true' || $v === 'on') {
        return true;
    }
    if ($v === 0 || $v === '0' || $v === 'false' || $v === 'off') {
        return false;
    }
    return null;
}

/**
 * @param array<string, mixed> $body
 * @param list<string> $keys
 * @return array<string, mixed>
 */
function gos_discord_pick_body(array $body, array $keys): array
{
    $out = [];
    foreach ($keys as $key) {
        if (!array_key_exists($key, $body)) {
            continue;
        }
        $out[$key] = $body[$key];
    }
    return $out;
}

/**
 * @param array<string, mixed> $q
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_list_emojis(array $q): array
{
    $search = strtolower(trim((string) ($q['search'] ?? '')));
    if (strlen($search) > 80) {
        $search = substr($search, 0, 80);
    }
    $limit = max(1, min(100, (int) ($q['limit'] ?? 48)));
    $offset = max(0, (int) ($q['offset'] ?? 0));
    $dir = gos_discord_emoji_dir();
    $files = [];
    if (is_dir($dir)) {
        $dh = opendir($dir);
        if ($dh !== false) {
            while (($f = readdir($dh)) !== false) {
                if ($f === '.' || $f === '..') {
                    continue;
                }
                $ext = strtolower(pathinfo($f, PATHINFO_EXTENSION));
                if (!in_array($ext, ['png', 'gif', 'jpg', 'jpeg', 'webp'], true)) {
                    continue;
                }
                if ($search !== '' && !str_contains(strtolower($f), $search)) {
                    continue;
                }
                $files[] = $f;
            }
            closedir($dh);
        }
    }
    sort($files, SORT_NATURAL | SORT_FLAG_CASE);
    $total = count($files);
    $slice = array_slice($files, $offset, $limit);
    return [
        'ok' => true,
        'status' => 200,
        'data' => [
            'files' => $slice,
            'total' => $total,
            'limit' => $limit,
            'offset' => $offset,
        ],
        'error' => null,
    ];
}

function gos_discord_serve_emoji(string $name): never
{
    $file = gos_discord_filename($name);
    if ($file === '') {
        gos_api_json(['ok' => false, 'error' => 'invalid_filename'], 400);
    }
    $ext = strtolower(pathinfo($file, PATHINFO_EXTENSION));
    $mimes = [
        'png' => 'image/png',
        'gif' => 'image/gif',
        'jpg' => 'image/jpeg',
        'jpeg' => 'image/jpeg',
        'webp' => 'image/webp',
    ];
    if (!isset($mimes[$ext])) {
        gos_api_json(['ok' => false, 'error' => 'unsupported_type'], 400);
    }
    $path = gos_discord_emoji_dir() . '/' . $file;
    $realDir = realpath(gos_discord_emoji_dir());
    $realFile = realpath($path);
    if ($realDir === false || $realFile === false || !str_starts_with($realFile, $realDir . DIRECTORY_SEPARATOR) || !is_file($realFile)) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    header('Content-Type: ' . $mimes[$ext]);
    header('Cache-Control: private, max-age=86400');
    header('X-Content-Type-Options: nosniff');
    readfile($realFile);
    exit;
}

/**
 * @param array<string, mixed> $q
 * @return array{method:string,path:string,timeout:int}|null
 */
function gos_discord_resolve_get(string $action, array $q): ?array
{
    $common = ['botId', 'guildId', 'channelId', 'userId', 'search', 'tags', 'timeframe', 'dateFrom', 'dateTo', 'fromDate', 'toDate', 'aroundMessageId', 'aroundAt', 'beforeAt', 'limit', 'offset', 'page', 'watched', 'sort', 'order', 'contentType', 'includeTotal'];
    $qs = static function (array $keys) use ($q): string {
        return gos_discord_query_string(gos_discord_pick_query($q, $keys));
    };

    return match ($action) {
        'health', 'bots' => ['method' => 'GET', 'path' => '/api/discord/bots', 'timeout' => 20],
        'bot' => (static function () use ($q) {
            $id = gos_discord_id($q['id'] ?? '');
            return $id === '' ? null : ['method' => 'GET', 'path' => '/api/discord/bots/' . $id, 'timeout' => 15];
        })(),
        'bot_guilds' => (static function () use ($q) {
            $id = gos_discord_id($q['id'] ?? $q['botId'] ?? '');
            return $id === '' ? null : ['method' => 'GET', 'path' => '/api/discord/bots/' . $id . '/guilds', 'timeout' => 15];
        })(),
        'guilds' => ['method' => 'GET', 'path' => '/api/discord/guilds' . $qs(['watched', 'botId', 'search', 'sort']), 'timeout' => 15],
        'guild_settings' => (static function () use ($q, $qs) {
            $gid = gos_discord_snowflake($q['guildId'] ?? '');
            return $gid === '' ? null : ['method' => 'GET', 'path' => '/api/discord/guilds/' . $gid . '/settings' . $qs(['botId']), 'timeout' => 15];
        })(),
        'channels' => (static function () use ($q, $qs) {
            $gid = gos_discord_snowflake($q['guildId'] ?? '');
            return $gid === '' ? null : ['method' => 'GET', 'path' => '/api/discord/guilds/' . $gid . '/channels' . $qs(['botId']), 'timeout' => 12];
        })(),
        'live_channels' => (static function () use ($q, $qs) {
            $gid = gos_discord_snowflake($q['guildId'] ?? '');
            return $gid === '' ? null : ['method' => 'GET', 'path' => '/api/discord/guilds/' . $gid . '/live-channels' . $qs(['botId']), 'timeout' => 25];
        })(),
        'roles' => (static function () use ($q, $qs) {
            $gid = gos_discord_snowflake($q['guildId'] ?? '');
            return $gid === '' ? null : ['method' => 'GET', 'path' => '/api/discord/guilds/' . $gid . '/roles' . $qs(['botId']), 'timeout' => 20];
        })(),
        'users' => ['method' => 'GET', 'path' => '/api/discord/users' . $qs(['search', 'limit', 'offset', 'sort', 'order', 'guildId']), 'timeout' => 12],
        'user' => (static function () use ($q, $qs) {
            $id = gos_discord_id($q['id'] ?? '');
            return $id === '' ? null : ['method' => 'GET', 'path' => '/api/discord/users/' . $id . $qs(['byDiscordId']), 'timeout' => 20];
        })(),
        'messages' => ['method' => 'GET', 'path' => '/api/discord/messages' . $qs($common), 'timeout' => 40],
        'attachments' => ['method' => 'GET', 'path' => '/api/discord/attachments' . $qs($common), 'timeout' => 30],
        'role_pickers' => ['method' => 'GET', 'path' => '/api/discord/tools/role-pickers' . $qs(['botId']), 'timeout' => 20],
        'role_picker' => (static function () use ($q) {
            $id = gos_discord_id($q['id'] ?? '');
            return $id === '' ? null : ['method' => 'GET', 'path' => '/api/discord/tools/role-pickers/' . $id, 'timeout' => 15];
        })(),
        'captchas' => ['method' => 'GET', 'path' => '/api/discord/tools/captchas' . $qs(['botId']), 'timeout' => 20],
        'captcha' => (static function () use ($q) {
            $id = gos_discord_id($q['id'] ?? '');
            return $id === '' ? null : ['method' => 'GET', 'path' => '/api/discord/tools/captchas/' . $id, 'timeout' => 15];
        })(),
        'captcha_attempts' => (static function () use ($q, $qs) {
            $id = gos_discord_id($q['id'] ?? '');
            return $id === '' ? null : ['method' => 'GET', 'path' => '/api/discord/tools/captchas/' . $id . '/attempts' . $qs(['limit', 'offset']), 'timeout' => 20];
        })(),
        'emojis_local' => ['method' => 'GET', 'path' => '/api/discord/tools/emojis/local', 'timeout' => 20],
        'emojis_guild' => ['method' => 'GET', 'path' => '/api/discord/tools/emojis/guild' . $qs(['botId', 'guildId']), 'timeout' => 25],
        'emojis_bot_guilds' => ['method' => 'GET', 'path' => '/api/discord/tools/emojis/bot-guilds' . $qs(['botId']), 'timeout' => 25],
        'audits' => (static function () use ($q): array {
            $picked = gos_discord_pick_query($q, ['botId', 'guildId', 'fromDate', 'toDate', 'limit', 'offset', 'sort', 'includeTotal', 'timeframe']);
            $eventAction = gos_discord_audit_event_action($q);
            if ($eventAction !== '') {
                $picked['action'] = $eventAction;
            }
            return ['method' => 'GET', 'path' => '/api/discord/tools/audits' . gos_discord_query_string($picked), 'timeout' => 15];
        })(),
        default => null,
    };
}

/**
 * @param array<string, mixed> $body
 * @return array{method:string,path:string,timeout:int,json:?array}|null
 */
function gos_discord_resolve_write(string $action, array $body): ?array
{
    $id = gos_discord_id($body['id'] ?? '');
    $guildId = gos_discord_snowflake($body['guildId'] ?? '');
    $channelId = gos_discord_snowflake($body['channelId'] ?? '');
    $botId = gos_discord_id($body['botId'] ?? $body['id'] ?? '');

    return match ($action) {
        'create_bot' => (static function () use ($body) {
            $name = trim((string) ($body['name'] ?? ''));
            $token = trim((string) ($body['token'] ?? ''));
            if ($name === '' || $token === '') {
                return null;
            }
            $client = (string) ($body['clientType'] ?? 'discord.js');
            if (!in_array($client, ['discord.js', 'selfbot'], true)) {
                $client = 'discord.js';
            }
            $json = [
                'name' => substr($name, 0, 80),
                'token' => $token,
                'clientType' => $client,
            ];
            $mentions = gos_discord_bool($body['respondMentions'] ?? null);
            $replies = gos_discord_bool($body['respondReplies'] ?? null);
            if ($mentions !== null) {
                $json['respondMentions'] = $mentions;
            }
            if ($replies !== null) {
                $json['respondReplies'] = $replies;
            }
            return ['method' => 'POST', 'path' => '/api/discord/bots', 'timeout' => 30, 'json' => $json];
        })(),
        'update_bot' => $id === '' ? null : [
            'method' => 'PUT',
            'path' => '/api/discord/bots/' . $id,
            'timeout' => 25,
            'json' => (static function () use ($body) {
                $json = [];
                if (isset($body['name'])) {
                    $json['name'] = substr(trim((string) $body['name']), 0, 80);
                }
                if (isset($body['token']) && trim((string) $body['token']) !== '' && trim((string) $body['token']) !== '***hidden***') {
                    $json['token'] = trim((string) $body['token']);
                }
                if (isset($body['clientType']) && in_array((string) $body['clientType'], ['discord.js', 'selfbot'], true)) {
                    $json['clientType'] = (string) $body['clientType'];
                }
                foreach (['respondMentions', 'respondReplies', 'isActive'] as $b) {
                    $v = gos_discord_bool($body[$b] ?? null);
                    if ($v !== null) {
                        $json[$b] = $v;
                    }
                }
                foreach (['activityType', 'activityText'] as $s) {
                    if (isset($body[$s]) && is_string($body[$s])) {
                        $json[$s] = substr($body[$s], 0, 120);
                    }
                }
                return $json;
            })(),
        ],
        'delete_bot' => $id === '' ? null : ['method' => 'DELETE', 'path' => '/api/discord/bots/' . $id, 'timeout' => 20, 'json' => null],
        'start_bot' => $id === '' ? null : ['method' => 'POST', 'path' => '/api/discord/bots/' . $id . '/start', 'timeout' => 45, 'json' => new stdClass()],
        'stop_bot' => $id === '' ? null : ['method' => 'POST', 'path' => '/api/discord/bots/' . $id . '/stop', 'timeout' => 30, 'json' => new stdClass()],
        'update_guild_settings' => $guildId === '' ? null : [
            'method' => 'PUT',
            'path' => '/api/discord/guilds/' . $guildId . '/settings' . gos_discord_query_string(gos_discord_pick_query($body, ['botId'])),
            'timeout' => 20,
            'json' => (static function () use ($body) {
                $json = [];
                foreach (['isWatched', 'respondToMentions', 'respondToReplies', 'respondInConversation', 'semanticTagging', 'analyzeFiles'] as $b) {
                    $v = gos_discord_bool($body[$b] ?? null);
                    if ($v !== null) {
                        $json[$b] = $v;
                    }
                }
                return $json;
            })(),
        ],
        'update_channel_settings' => ($channelId === '' || $guildId === '') ? null : [
            'method' => 'PUT',
            'path' => '/api/discord/channels/' . $channelId . '/settings' . gos_discord_query_string(gos_discord_pick_query($body, ['botId'])),
            'timeout' => 20,
            'json' => (static function () use ($body, $guildId) {
                $json = ['guildId' => $guildId];
                if (isset($body['channelName'])) {
                    $json['channelName'] = substr((string) $body['channelName'], 0, 120);
                }
                if (isset($body['channelType'])) {
                    $json['channelType'] = (int) $body['channelType'];
                }
                foreach (['isEnabled', 'respondToAll', 'isMuted'] as $b) {
                    $v = gos_discord_bool($body[$b] ?? null);
                    if ($v !== null) {
                        $json[$b] = $v;
                    }
                }
                return $json;
            })(),
        ],
        'create_role_picker' => ($botId === '' || $guildId === '' || $channelId === '') ? null : [
            'method' => 'POST',
            'path' => '/api/discord/tools/role-pickers',
            'timeout' => 30,
            'json' => gos_discord_role_picker_body($body, $botId, $guildId, $channelId),
        ],
        'delete_role_picker' => $id === '' ? null : ['method' => 'DELETE', 'path' => '/api/discord/tools/role-pickers/' . $id, 'timeout' => 20, 'json' => null],
        'deploy_role_picker' => $id === '' ? null : ['method' => 'POST', 'path' => '/api/discord/tools/role-pickers/' . $id . '/deploy', 'timeout' => 40, 'json' => new stdClass()],
        'create_captcha' => ($botId === '' || $guildId === '' || $channelId === '') ? null : [
            'method' => 'POST',
            'path' => '/api/discord/tools/captchas',
            'timeout' => 30,
            'json' => gos_discord_captcha_body($body, $botId, $guildId, $channelId),
        ],
        'delete_captcha' => $id === '' ? null : ['method' => 'DELETE', 'path' => '/api/discord/tools/captchas/' . $id, 'timeout' => 20, 'json' => null],
        'deploy_captcha' => $id === '' ? null : ['method' => 'POST', 'path' => '/api/discord/tools/captchas/' . $id . '/deploy', 'timeout' => 40, 'json' => new stdClass()],
        'add_emoji' => [
            'method' => 'POST',
            'path' => '/api/discord/tools/emojis/guild',
            'timeout' => 40,
            'json' => [
                'botId' => (int) $botId,
                'guildId' => $guildId,
                'filename' => gos_discord_filename($body['filename'] ?? ''),
            ],
        ],
        'rename_emoji' => [
            'method' => 'PATCH',
            'path' => '/api/discord/tools/emojis/guild',
            'timeout' => 25,
            'json' => [
                'botId' => (int) $botId,
                'guildId' => $guildId,
                'emojiId' => gos_discord_snowflake($body['emojiId'] ?? ''),
                'name' => substr(trim((string) ($body['name'] ?? '')), 0, 32),
            ],
        ],
        'delete_emoji' => [
            'method' => 'DELETE',
            'path' => '/api/discord/tools/emojis/guild',
            'timeout' => 25,
            'json' => [
                'botId' => (int) $botId,
                'guildId' => $guildId,
                'emojiId' => gos_discord_snowflake($body['emojiId'] ?? ''),
            ],
        ],
        default => null,
    };
}

/**
 * @param array<string, mixed> $body
 * @return array<string, mixed>
 */
function gos_discord_role_picker_body(array $body, string $botId, string $guildId, string $channelId): array
{
    $rolesIn = $body['roles'] ?? [];
    $roles = [];
    if (is_array($rolesIn)) {
        foreach ($rolesIn as $row) {
            if (!is_array($row)) {
                continue;
            }
            $emoji = trim((string) ($row['emoji'] ?? ''));
            $roleId = gos_discord_snowflake($row['roleId'] ?? '');
            if ($emoji === '' || $roleId === '') {
                continue;
            }
            $item = ['emoji' => substr($emoji, 0, 80), 'roleId' => $roleId];
            if (isset($row['roleName'])) {
                $item['roleName'] = substr((string) $row['roleName'], 0, 80);
            }
            $roles[] = $item;
            if (count($roles) >= 25) {
                break;
            }
        }
    }
    $json = [
        'botId' => (int) $botId,
        'guildId' => $guildId,
        'channelId' => $channelId,
        'roles' => $roles,
        'deploy' => gos_discord_bool($body['deploy'] ?? false) ?? false,
    ];
    if (isset($body['embedTitle'])) {
        $json['embedTitle'] = substr((string) $body['embedTitle'], 0, 256);
    }
    if (isset($body['embedDescription'])) {
        $json['embedDescription'] = substr((string) $body['embedDescription'], 0, 2000);
    }
    return $json;
}

/**
 * @param array<string, mixed> $body
 * @return array<string, mixed>
 */
function gos_discord_captcha_body(array $body, string $botId, string $guildId, string $channelId): array
{
    $json = [
        'botId' => (int) $botId,
        'guildId' => $guildId,
        'channelId' => $channelId,
        'postRoleId' => gos_discord_snowflake($body['postRoleId'] ?? ''),
        'deploy' => gos_discord_bool($body['deploy'] ?? false) ?? false,
    ];
    foreach (['preRoleId', 'logChannelId'] as $sf) {
        $v = gos_discord_snowflake($body[$sf] ?? '');
        if ($v !== '') {
            $json[$sf] = $v;
        }
    }
    foreach (['preRoleName', 'postRoleName', 'embedTitle'] as $s) {
        if (isset($body[$s])) {
            $json[$s] = substr((string) $body[$s], 0, 256);
        }
    }
    if (isset($body['embedDescription'])) {
        $json['embedDescription'] = substr((string) $body['embedDescription'], 0, 2000);
    }
    return $json;
}

if (PHP_SAPI === 'cli') {
    if (($argv[1] ?? '') === '--probe') {
        $r = gos_discord_backend('GET', '/api/discord/bots', null, 20);
        $n = (is_array($r['data']) && array_is_list($r['data'])) ? count($r['data']) : 0;
        fwrite(STDERR, ($r['ok'] ? 'ok' : 'fail') . ' status=' . (int) $r['status'] . ' bots=' . $n . ' err=' . (string) ($r['error'] ?? '') . "\n");
        exit($r['ok'] ? 0 : 1);
    }
    if (($argv[1] ?? '') === '--probe-local') {
        $u = gos_discord_local_users(['limit' => 3, 'sort' => 'lastActive']);
        $a = gos_discord_local_audits(['limit' => 3, 'timeframe' => '1d']);
        $g = gos_discord_local_guilds([]);
        $m = gos_discord_local_messages(['limit' => 5, 'timeframe' => '1d']);
        $nU = is_array($u['data']['users'] ?? null) ? count($u['data']['users']) : 0;
        $nA = is_array($a['data']['events'] ?? null) ? count($a['data']['events']) : 0;
        $nG = is_array($g['data']) ? count($g['data']) : 0;
        $nM = is_array($m['data']['messages'] ?? null) ? count($m['data']['messages']) : 0;
        fwrite(STDERR, 'users=' . $nU . ' audits=' . $nA . ' guilds=' . $nG . ' messages=' . $nM . "\n");
        exit(!empty($u['ok']) && !empty($a['ok']) && !empty($g['ok']) && !empty($m['ok']) ? 0 : 1);
    }
    return;
}

$httpMethod = strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'));
$action = strtolower(trim((string) ($_GET['action'] ?? '')));

if ($httpMethod === 'GET' && $action === 'avatar') {
    gos_discord_serve_avatar($_GET);
}
if ($httpMethod === 'GET' && $action === 'file') {
    gos_discord_serve_file($_GET);
}

gos_require_access();

if ($httpMethod === 'GET') {
    $action = strtolower(trim((string) ($_GET['action'] ?? 'health')));
    if ($action === 'emoji') {
        gos_discord_serve_emoji((string) ($_GET['name'] ?? ''));
    }
    if ($action === 'emojis_local') {
        gos_discord_send(gos_discord_list_emojis($_GET));
    }
    $local = gos_discord_try_local_get($action, $_GET);
    if ($local === null) {
        $local = gos_discord_try_ai_get($action, $_GET);
    }
    if ($local !== null) {
        gos_discord_send($local);
    }
    $resolved = gos_discord_resolve_get($action, $_GET);
    if ($resolved === null) {
        gos_api_json(['ok' => false, 'error' => 'unknown_action'], 404);
    }
    $result = gos_discord_backend($resolved['method'], $resolved['path'], null, $resolved['timeout']);
    if (!empty($result['ok'])) {
        $result['data'] = gos_discord_rewrite_tree($result['data']);
    }
    gos_discord_send($result);
}

if ($httpMethod !== 'POST') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

$body = gos_json_body();
$action = strtolower(trim((string) ($body['action'] ?? '')));
$aiWrite = gos_discord_try_ai_write($action, $body);
if ($aiWrite !== null) {
    gos_discord_send($aiWrite);
}
$localWrite = gos_discord_try_local_write($action, $body);
if ($localWrite !== null) {
    if (
        !empty($localWrite['ok'])
        && $action === 'update_guild_settings'
        && array_key_exists('semanticTagging', $body)
        && function_exists('gos_discord_ai_spawn_worker')
    ) {
        gos_discord_ai_spawn_worker();
    }
    gos_discord_send($localWrite);
}
$resolved = gos_discord_resolve_write($action, $body);
if ($resolved === null) {
    gos_api_json(['ok' => false, 'error' => 'unknown_action'], 400);
}
$json = $resolved['json'];
$payload = null;
if ($json !== null) {
    $encoded = json_encode($json, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if (!is_string($encoded)) {
        gos_api_json(['ok' => false, 'error' => 'invalid_args'], 400);
    }
    $payload = $encoded;
}
$result = gos_discord_backend($resolved['method'], $resolved['path'], $payload, $resolved['timeout']);
if (
    !empty($result['ok'])
    && $action === 'update_guild_settings'
    && array_key_exists('semanticTagging', $body)
    && function_exists('gos_discord_ai_spawn_worker')
) {
    gos_discord_ai_spawn_worker();
}
gos_discord_send($result);
