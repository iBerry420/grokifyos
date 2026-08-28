<?php

declare(strict_types=1);

/**
 * Device-auth LYRE proxy: MySQL lyre_projects, Postgres BoardData, grokme storage GET.
 */

require_once __DIR__ . '/_common.php';

final class GosLyreException extends RuntimeException
{
    public function __construct(
        public string $error,
        public int $http = 400,
        public array $extra = [],
        string $message = '',
    ) {
        parent::__construct($message !== '' ? $message : $error);
    }

    public function toHttpBody(): array
    {
        return ['ok' => false, 'error' => $this->error] + $this->extra;
    }
}

function gos_lyre_fail(string $error, int $http = 400, array $extra = []): never
{
    throw new GosLyreException($error, $http, $extra);
}

function gos_lyre_http_status(string $error): int
{
    return match ($error) {
        'auth_required', 'invalid_token' => 401,
        'not_found' => 404,
        'not_deletable', 'odysseus_protected' => 403,
        'conflict', 'movie_locked', 'lock_timeout', 'not_stitch_target', 'nothing_to_pop' => 409,
        'db_error' => 500,
        'lyre_pg_unavailable', 'lyre_unconfigured' => 503,
        default => 400,
    };
}

function gos_lyre_http_send(callable $fn): never
{
    try {
        $out = $fn();
        $ok = is_array($out) && (($out['ok'] ?? true) !== false);
        gos_api_json($out, $ok ? 200 : gos_lyre_http_status((string) ($out['error'] ?? 'error')));
    } catch (GosLyreException $e) {
        gos_api_json($e->toHttpBody(), $e->http);
    }
}

function gos_lyre_pdo_alive(?PDO $pdo): bool
{
    if (function_exists('gos_pdo_alive')) {
        return gos_pdo_alive($pdo);
    }
    if (!($pdo instanceof PDO)) {
        return false;
    }
    try {
        return $pdo->query('SELECT 1') !== false;
    } catch (Throwable) {
        return false;
    }
}

function gos_lyre_pdo(): PDO
{
    static $pdo = null;
    if (gos_lyre_pdo_alive($pdo)) {
        return $pdo;
    }
    $url = (string) (gos_env('GROKIFY_LYRE_DATABASE_URL', '') ?? '');
    $p = parse_url($url);
    if ($p === false || empty($p['host']) || empty($p['path'])) {
        throw new RuntimeException('lyre_pg_unconfigured');
    }
    $db = ltrim((string) $p['path'], '/');
    $dsn = sprintf(
        'pgsql:host=%s;port=%d;dbname=%s;sslmode=require',
        $p['host'],
        (int) ($p['port'] ?? 5432),
        $db
    );
    $user = isset($p['user']) ? urldecode($p['user']) : '';
    $pass = isset($p['pass']) ? urldecode($p['pass']) : '';
    $pdo = new PDO($dsn, $user, $pass, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
    return $pdo;
}

function gos_lyre_odysseus_board_id(): string
{
    $id = trim((string) (gos_env('GROKIFY_LYRE_ODYSSEUS_BOARD_ID', 'lyre') ?? 'lyre'));
    return $id !== '' ? $id : 'lyre';
}

function gos_lyre_auth(): array
{
    $access = gos_require_access();
    if (!empty($access['device'])) {
        $vName = isset($_GET['version_name']) ? (string) $_GET['version_name'] : null;
        $vCode = (int) ($_GET['version_code'] ?? $_GET['versionCode'] ?? 0);
        gos_touch_device((int) $access['device']['id'], $vName, $vCode > 0 ? $vCode : null);
    }
    return $access;
}

function gos_lyre_user_id(array $access): int
{
    $id = (int) ($access['user']['id'] ?? 0);
    if ($id <= 0) {
        gos_lyre_fail('auth_required', 401);
    }
    return $id;
}

function gos_lyre_mysql(): PDO
{
    if (!gos_table_exists('lyre_projects')) {
        gos_lyre_fail('lyre_unconfigured', 503);
    }
    return gos_pdo();
}

function gos_lyre_pg(): PDO
{
    if (getenv('GOS_LYRE_TEST_PG_FAIL') === '1') {
        gos_lyre_fail('lyre_pg_unavailable', 503);
    }
    try {
        return gos_lyre_pdo();
    } catch (GosLyreException $e) {
        throw $e;
    } catch (Throwable) {
        gos_lyre_fail('lyre_pg_unavailable', 503);
    }
}

function gos_lyre_is_odysseus_project(array $row): bool
{
    return ((int) ($row['is_odysseus'] ?? 0)) === 1
        || (string) ($row['board_id'] ?? '') === gos_lyre_odysseus_board_id();
}

/** @return array<string, mixed> */
function gos_lyre_empty_board(): array
{
    return [
        'title' => 'Untitled',
        'brainstorm' => '',
        'scenes' => [[
            'id' => 'sc_1',
            'title' => 'Scene 1',
            'book' => '',
            'durationTargetSec' => 0,
            'logline' => '',
            'dialogue' => '',
            'notes' => '',
            'frames' => [],
        ]],
        'activeSceneId' => 'sc_1',
        'refFolders' => [[
            'id' => 'lib',
            'name' => 'Library',
            'images' => [],
        ]],
        'activeFolderId' => 'lib',
        'videoLayers' => [],
        'audioLayers' => [],
        'libraryAudio' => [],
        'libraryVideo' => [],
    ];
}

function gos_lyre_new_hex_id(): string
{
    return bin2hex(random_bytes(16));
}

function gos_lyre_uuid(): string
{
    $b = random_bytes(16);
    $b[6] = chr((ord($b[6]) & 0x0f) | 0x40);
    $b[8] = chr((ord($b[8]) & 0x3f) | 0x80);
    $h = bin2hex($b);
    return sprintf(
        '%s-%s-%s-%s-%s',
        substr($h, 0, 8),
        substr($h, 8, 4),
        substr($h, 12, 4),
        substr($h, 16, 4),
        substr($h, 20, 12)
    );
}

function gos_lyre_project_name(string $name): string
{
    $name = trim($name);
    if ($name === '') {
        $name = 'Untitled';
    }
    if (function_exists('mb_substr')) {
        $name = mb_substr($name, 0, 128);
    } elseif (strlen($name) > 128) {
        $name = substr($name, 0, 128);
    }
    return $name;
}

/** @return array<string, mixed> */
function gos_lyre_project_public(array $row): array
{
    return [
        'id' => (string) $row['id'],
        'name' => (string) $row['name'],
        'visibility' => (string) $row['visibility'],
        'board_id' => (string) $row['board_id'],
        'watch_token' => isset($row['watch_token']) && $row['watch_token'] !== null && $row['watch_token'] !== ''
            ? (string) $row['watch_token']
            : null,
        'is_odysseus' => ((int) ($row['is_odysseus'] ?? 0)) === 1,
        'compiled_key' => isset($row['compiled_key']) && $row['compiled_key'] !== null && $row['compiled_key'] !== ''
            ? (string) $row['compiled_key']
            : null,
        'created_at' => (string) ($row['created_at'] ?? ''),
        'updated_at' => (string) ($row['updated_at'] ?? ''),
    ];
}

/** @return array<string, mixed>|null */
function gos_lyre_project_by_id(PDO $mysql, int $userId, string $id): ?array
{
    $st = $mysql->prepare('SELECT * FROM lyre_projects WHERE id = ? AND user_id = ? LIMIT 1');
    $st->execute([$id, $userId]);
    $row = $st->fetch();
    return is_array($row) ? $row : null;
}

/** @return array<string, mixed>|null */
function gos_lyre_project_by_board(PDO $mysql, int $userId, string $boardId): ?array
{
    $st = $mysql->prepare('SELECT * FROM lyre_projects WHERE user_id = ? AND board_id = ? LIMIT 1');
    $st->execute([$userId, $boardId]);
    $row = $st->fetch();
    return is_array($row) ? $row : null;
}

/** @param mixed $payload @return array<string, mixed> */
function gos_lyre_payload_array($payload): array
{
    if (is_array($payload)) {
        return $payload;
    }
    if (is_string($payload) && $payload !== '') {
        $decoded = json_decode($payload, true);
        if (is_array($decoded)) {
            return $decoded;
        }
    }
    return [];
}

/**
 * Live grokme boards columns: id, title, brainstorm, payload jsonb, updated_at.
 *
 * @return array<string, mixed>|null
 */
function gos_lyre_pg_select(PDO $pg, string $id): ?array
{
    try {
        $st = $pg->prepare(
            'SELECT id, title, brainstorm, payload, updated_at FROM boards WHERE id = ? LIMIT 1'
        );
        $st->execute([$id]);
        $row = $st->fetch();
        return is_array($row) ? $row : null;
    } catch (GosLyreException $e) {
        throw $e;
    } catch (Throwable) {
        gos_lyre_fail('lyre_pg_unavailable', 503);
    }
}

function gos_lyre_pg_insert(PDO $pg, string $id, array $data): void
{
    $title = trim((string) ($data['title'] ?? 'Untitled'));
    if ($title === '') {
        $title = 'Untitled';
    }
    $brainstorm = (string) ($data['brainstorm'] ?? '');
    $json = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if (!is_string($json) || $json === '') {
        gos_lyre_fail('invalid_board', 400);
    }
    try {
        $st = $pg->prepare(
            'INSERT INTO boards (id, title, brainstorm, payload, updated_at)
             VALUES (?, ?, ?, CAST(? AS jsonb), NOW())'
        );
        $st->execute([$id, $title, $brainstorm, $json]);
    } catch (GosLyreException $e) {
        throw $e;
    } catch (Throwable) {
        gos_lyre_fail('lyre_pg_unavailable', 503);
    }
}

/**
 * Stamp-required wrapper so later PRs cannot LWW-clobber via this name.
 *
 * @param array<string, mixed> $data
 * @return array{updated_at: string}
 */
function gos_lyre_pg_update(?PDO $pg, string $id, array $data, string $expectedUpdatedAt = ''): array
{
    if ($expectedUpdatedAt === '') {
        gos_lyre_fail('expected_updated_at_required', 400);
    }

    return gos_lyre_pg_update_cas($pg, $id, $data, $expectedUpdatedAt);
}

function gos_lyre_pg_delete(PDO $pg, string $id): void
{
    try {
        $st = $pg->prepare('DELETE FROM boards WHERE id = ?');
        $st->execute([$id]);
    } catch (GosLyreException $e) {
        throw $e;
    } catch (Throwable) {
        gos_lyre_fail('lyre_pg_unavailable', 503);
    }
}

function gos_lyre_ensure_odysseus(int $userId): void
{
    $mysql = gos_lyre_mysql();
    $boardId = gos_lyre_odysseus_board_id();
    $existing = gos_lyre_project_by_board($mysql, $userId, $boardId);
    if ($existing === null) {
        try {
            $ins = $mysql->prepare(
                'INSERT INTO lyre_projects (id, user_id, name, visibility, board_id, watch_token, is_odysseus)
                 VALUES (?, ?, ?, ?, ?, NULL, 1)'
            );
            $ins->execute([gos_lyre_new_hex_id(), $userId, 'Odysseus', 'private', $boardId]);
        } catch (PDOException) {
            if (gos_lyre_project_by_board($mysql, $userId, $boardId) === null) {
                gos_lyre_fail('db_error', 500);
            }
        }
    }
    $pg = gos_lyre_pg();
    $row = gos_lyre_pg_select($pg, $boardId);
    if ($row === null) {
        gos_lyre_pg_insert($pg, $boardId, gos_lyre_empty_board());
    }
}

function gos_lyre_storage_key(string $raw): ?string
{
    $key = trim($raw);
    if ($key === '') {
        return null;
    }
    if (preg_match('#^https?://[^/]+/v1/storage/(.+)$#i', $key, $m) === 1) {
        $key = rawurldecode((string) $m[1]);
    } elseif (preg_match('#/api/storage/(.+)$#i', $key, $m) === 1) {
        $key = rawurldecode((string) $m[1]);
    } elseif (str_contains($key, '/api/media')) {
        $query = parse_url($key, PHP_URL_QUERY);
        if (!is_string($query) || $query === '') {
            return null;
        }
        $params = [];
        parse_str($query, $params);
        $key = (string) ($params['p'] ?? '');
    }
    if (str_starts_with($key, 'me:')) {
        $key = substr($key, 3);
    }
    $key = ltrim(str_replace('\\', '/', $key), '/');
    if ($key === '' || str_contains($key, '..') || str_contains($key, "\0")) {
        return null;
    }
    if (preg_match('#^boards/[A-Za-z0-9_./-]+$#', $key) === 1) {
        return $key;
    }
    if (preg_match('#^public/watch/[a-f0-9]{32}\.mp4$#', $key) === 1) {
        return $key;
    }
    if (preg_match('#^(stills|videos|audio|seed/stills|public/stills)/[A-Za-z0-9_./-]+$#', $key) === 1) {
        return $key;
    }
    return null;
}

/**
 * Candidate paths under the grokme files dir for a normalized storage key.
 *
 * @return list<string>
 */
function gos_lyre_storage_relatives(string $key): array
{
    $relatives = [$key];
    if (preg_match('#^stills/[A-Za-z0-9_.-]+$#', $key) === 1) {
        $base = basename($key);
        $relatives[] = 'seed/stills/' . $base;
        $relatives[] = 'public/stills/' . $base;
    }
    return $relatives;
}

/** Absolute path under the grokme files dir, or null. */
function gos_lyre_local_object_path(string $key): ?string
{
    $root = trim((string) (gos_env('GROKIFY_LYRE_FILES_DIR', '') ?? ''));
    if ($root === '') {
        $guess = '/root/grokme/storage/projects/lyre_grok_me/files';
        $root = is_dir($guess) ? $guess : '';
    }
    if ($root === '' || !is_dir($root)) {
        return null;
    }
    $rootReal = realpath($root);
    if (!is_string($rootReal) || $rootReal === '') {
        return null;
    }
    $relatives = gos_lyre_storage_relatives($key);
    foreach ($relatives as $rel) {
        if ($rel === '' || str_contains($rel, '..') || str_contains($rel, "\0")) {
            continue;
        }
        $candidate = $rootReal . '/' . $rel;
        if (!is_file($candidate)) {
            continue;
        }
        $real = realpath($candidate);
        if (!is_string($real) || $real === '') {
            continue;
        }
        $prefix = $rootReal . DIRECTORY_SEPARATOR;
        if (!str_starts_with($real, $prefix) && $real !== $rootReal) {
            continue;
        }
        if (filesize($real) > 0) {
            return $real;
        }
    }
    return null;
}

function gos_lyre_stream_local_file(string $path): never
{
    $size = filesize($path);
    if ($size === false || $size <= 0) {
        gos_lyre_json_error('not_found', 404);
    }
    $ext = strtolower(pathinfo($path, PATHINFO_EXTENSION));
    $mime = match ($ext) {
        'jpg', 'jpeg' => 'image/jpeg',
        'png' => 'image/png',
        'webp' => 'image/webp',
        'gif' => 'image/gif',
        'mp4' => 'video/mp4',
        'webm' => 'video/webm',
        'wav' => 'audio/wav',
        'mp3' => 'audio/mpeg',
        'm4a' => 'audio/mp4',
        default => (function_exists('mime_content_type') ? (mime_content_type($path) ?: 'application/octet-stream') : 'application/octet-stream'),
    };
    while (ob_get_level() > 0) {
        ob_end_clean();
    }
    header_remove('Content-Type');
    header('Content-Type: ' . $mime);
    header('Content-Length: ' . (string) $size);
    header('Cache-Control: private, max-age=60');
    header('X-Content-Type-Options: nosniff');
    header('X-Accel-Buffering: no');
    http_response_code(200);
    $ok = readfile($path);
    if ($ok === false) {
        exit;
    }
    exit;
}

function gos_lyre_json_error(string $error, int $code): never
{
    header('Content-Type: application/json; charset=utf-8');
    gos_api_json(['ok' => false, 'error' => $error], $code);
}

function gos_lyre_storage_get(string $rawKey): never
{
    $key = gos_lyre_storage_key($rawKey);
    if ($key === null) {
        gos_lyre_json_error('invalid_key', 400);
    }
    $local = gos_lyre_local_object_path($key);
    if ($local !== null) {
        gos_lyre_stream_local_file($local);
    }
    $base = rtrim((string) (gos_env('GROKIFY_LYRE_ME_STORAGE_BASE', 'https://me.grokpot.io/v1/storage') ?? ''), '/');
    $apiKey = (string) (gos_env('GROKIFY_LYRE_ME_API_KEY', '') ?? '');
    if ($base === '' || $apiKey === '') {
        gos_lyre_json_error('lyre_storage_unconfigured', 503);
    }
    if (!function_exists('curl_init')) {
        gos_lyre_json_error('curl_missing', 500);
    }
    $url = $base . '/' . implode('/', array_map('rawurlencode', explode('/', $key)));
    $status = 0;
    $contentType = 'application/octet-stream';
    $contentLength = null;
    $streamed = false;
    $bytes = 0;
    $ch = curl_init($url);
    if ($ch === false) {
        gos_lyre_json_error('storage_get_failed', 502);
    }
    curl_setopt_array($ch, [
        CURLOPT_HTTPGET => true,
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_CONNECTTIMEOUT => 30,
        CURLOPT_TIMEOUT => 300,
        CURLOPT_HTTPHEADER => [
            'Authorization: Bearer ' . $apiKey,
            'Accept: */*',
        ],
        CURLOPT_HEADERFUNCTION => static function ($ch, string $header) use (&$status, &$contentType, &$contentLength): int {
            if (preg_match('#^HTTP/\S+\s+(\d+)#', $header, $m) === 1) {
                $status = (int) $m[1];
            } elseif (stripos($header, 'Content-Type:') === 0) {
                $contentType = trim(substr($header, strlen('Content-Type:')));
            } elseif (stripos($header, 'Content-Length:') === 0) {
                $contentLength = trim(substr($header, strlen('Content-Length:')));
            }
            return strlen($header);
        },
        CURLOPT_WRITEFUNCTION => static function ($ch, string $data) use (
            &$status,
            &$contentType,
            &$contentLength,
            &$streamed,
            &$bytes
        ): int {
            if ($status !== 200) {
                return strlen($data);
            }
            if ($contentLength === '0') {
                return strlen($data);
            }
            if (!$streamed) {
                $streamed = true;
                while (ob_get_level() > 0) {
                    ob_end_clean();
                }
                header_remove('Content-Type');
                header('Content-Type: ' . ($contentType !== '' ? $contentType : 'application/octet-stream'));
                if (is_string($contentLength) && $contentLength !== '' && ctype_digit($contentLength)) {
                    header('Content-Length: ' . $contentLength);
                }
                header('Cache-Control: private, max-age=60');
                header('X-Content-Type-Options: nosniff');
                header('X-Accel-Buffering: no');
                http_response_code(200);
                if (function_exists('flush')) {
                    flush();
                }
            }
            $bytes += strlen($data);
            echo $data;
            return strlen($data);
        },
    ]);
    $ok = curl_exec($ch);
    $errno = curl_errno($ch);
    curl_close($ch);
    if ($streamed) {
        // Headers already flushed; a JSON error would be appended to the body.
        $short = is_string($contentLength) && $contentLength !== '' && ctype_digit($contentLength)
            && $bytes !== (int) $contentLength;
        if ($ok === false || $errno !== 0 || $bytes <= 0 || $short) {
            exit;
        }
        exit;
    }
    if ($ok === false || $errno !== 0) {
        gos_lyre_json_error('storage_get_failed', 502);
    }
    if ($status === 404 || $status === 200 || $status === 0) {
        $local = gos_lyre_local_object_path($key);
        if ($local !== null) {
            gos_lyre_stream_local_file($local);
        }
        gos_lyre_json_error('not_found', 404);
    }
    gos_lyre_json_error('storage_get_failed', $status >= 400 ? $status : 502);
}

function gos_lyre_list_projects(array $access): array
{
    $userId = gos_lyre_user_id($access);
    gos_lyre_ensure_odysseus($userId);
    $mysql = gos_lyre_mysql();
    $st = $mysql->prepare(
        'SELECT * FROM lyre_projects WHERE user_id = ? ORDER BY updated_at DESC, id DESC'
    );
    $st->execute([$userId]);
    $projects = [];
    while ($row = $st->fetch()) {
        if (is_array($row)) {
            $projects[] = gos_lyre_project_public($row);
        }
    }
    $state = function_exists('gos_lyre_mcp_user_state') ? gos_lyre_mcp_user_state($userId) : [];
    return [
        'ok' => true,
        'projects' => $projects,
        'phone_last_project_id' => $state['phone_last_project_id'] ?? null,
        'mcp_open_project_id' => $state['mcp_open_project_id'] ?? null,
    ];
}

function gos_lyre_get_projects(array $access): never
{
    gos_lyre_http_send(fn () => gos_lyre_list_projects($access));
}

function gos_lyre_load_project(array $access, string $id): array
{
    $id = trim($id);
    if ($id === '') {
        gos_lyre_fail('id_required', 400);
    }
    $userId = gos_lyre_user_id($access);
    $row = gos_lyre_project_by_id(gos_lyre_mysql(), $userId, $id);
    if ($row === null) {
        gos_lyre_fail('not_found', 404);
    }
    return ['ok' => true, 'project' => gos_lyre_project_public($row)];
}

function gos_lyre_get_project(array $access, string $id): never
{
    gos_lyre_http_send(fn () => gos_lyre_load_project($access, $id));
}

function gos_lyre_load_board(array $access, string $boardId): array
{
    $boardId = trim($boardId);
    if ($boardId === '') {
        gos_lyre_fail('board_id_required', 400);
    }
    $userId = gos_lyre_user_id($access);
    $proj = gos_lyre_project_by_board(gos_lyre_mysql(), $userId, $boardId);
    if ($proj === null) {
        gos_lyre_fail('not_found', 404);
    }
    $row = gos_lyre_pg_select(gos_lyre_pg(), $boardId);
    if ($row === null) {
        gos_lyre_fail('not_found', 404);
    }
    $data = gos_lyre_payload_array($row['payload'] ?? null);
    return [
        'ok' => true,
        'board_id' => (string) $row['id'],
        'data' => $data === [] ? new stdClass() : $data,
        'updated_at' => (string) ($row['updated_at'] ?? ''),
    ];
}

function gos_lyre_get_board(array $access, string $boardId): never
{
    gos_lyre_http_send(fn () => gos_lyre_load_board($access, $boardId));
}

function gos_lyre_create_project(array $access, array $body): array
{
    $userId = gos_lyre_user_id($access);
    $name = gos_lyre_project_name((string) ($body['name'] ?? ''));
    $brainstorm = $body['brainstorm'] ?? '';
    if (!is_string($brainstorm)) {
        $brainstorm = '';
    }
    $boardId = 'lyre_phone_' . gos_lyre_uuid();
    $projectId = gos_lyre_new_hex_id();
    $empty = gos_lyre_empty_board();
    $empty['title'] = $name;
    $empty['brainstorm'] = $brainstorm;
    $pg = gos_lyre_pg();
    gos_lyre_pg_insert($pg, $boardId, $empty);
    try {
        $ins = gos_lyre_mysql()->prepare(
            'INSERT INTO lyre_projects (id, user_id, name, visibility, board_id, watch_token, is_odysseus)
             VALUES (?, ?, ?, ?, ?, NULL, 0)'
        );
        $ins->execute([$projectId, $userId, $name, 'private', $boardId]);
    } catch (GosLyreException $e) {
        gos_lyre_pg_delete($pg, $boardId);
        throw $e;
    } catch (Throwable) {
        gos_lyre_pg_delete($pg, $boardId);
        gos_lyre_fail('db_error', 500);
    }
    $row = gos_lyre_project_by_id(gos_lyre_mysql(), $userId, $projectId);
    if ($row === null) {
        gos_lyre_fail('db_error', 500);
    }
    return ['ok' => true, 'project' => gos_lyre_project_public($row)];
}

function gos_lyre_post_create(array $access, array $body): never
{
    gos_lyre_http_send(fn () => gos_lyre_create_project($access, $body));
}

function gos_lyre_resolve_project_row(array $access, array $body): array
{
    $userId = gos_lyre_user_id($access);
    $projectId = trim((string) ($body['project_id'] ?? $body['id'] ?? ''));
    $boardId = trim((string) ($body['board_id'] ?? ''));
    $mysql = gos_lyre_mysql();
    $row = null;
    if ($projectId !== '') {
        $row = gos_lyre_project_by_id($mysql, $userId, $projectId);
    }
    if ($row === null && $boardId !== '') {
        $row = gos_lyre_project_by_board($mysql, $userId, $boardId);
    }
    if ($row === null) {
        gos_lyre_fail($projectId === '' && $boardId === '' ? 'project_required' : 'not_found', $projectId === '' && $boardId === '' ? 400 : 404);
    }
    return $row;
}

function gos_lyre_open_resolved(array $access, array $row, string $slot): array
{
    $userId = gos_lyre_user_id($access);
    if ($slot === 'mcp' && gos_lyre_is_odysseus_project($row)) {
        gos_lyre_fail('odysseus_protected', 403);
    }
    if (function_exists('gos_lyre_mcp_persist_open')) {
        gos_lyre_mcp_persist_open($userId, $row, $slot);
    }
    return ['ok' => true, 'project' => gos_lyre_project_public($row)];
}

function gos_lyre_open_project(array $access, array $body, string $slot): array
{
    $row = gos_lyre_resolve_project_row($access, $body);
    return gos_lyre_open_resolved($access, $row, $slot);
}

function gos_lyre_http_open(array $access, array $body): never
{
    gos_lyre_http_send(fn () => gos_lyre_open_project($access, $body, 'phone'));
}

function gos_lyre_http_mcp_status(array $access): never
{
    gos_lyre_http_send(function () use ($access) {
        return gos_lyre_mcp_status_payload(gos_lyre_user_id($access), null);
    });
}

function gos_lyre_http_mcp_ensure(array $access): never
{
    gos_lyre_http_send(function () use ($access) {
        $userId = gos_lyre_user_id($access);
        $ens = gos_lyre_mcp_ensure_for_user($userId, false);
        $out = gos_lyre_mcp_status_payload($userId, $ens['plain_token']);
        if (is_string($ens['plain_token']) && $ens['plain_token'] !== '') {
            $out['plain_token'] = $ens['plain_token'];
        }
        return $out;
    });
}

function gos_lyre_http_mcp_rotate(array $access, array $body): never
{
    gos_lyre_http_send(function () use ($access, $body) {
        if (!filter_var($body['confirm'] ?? false, FILTER_VALIDATE_BOOLEAN)) {
            gos_lyre_fail('confirm_required', 400);
        }
        $userId = gos_lyre_user_id($access);
        $ens = gos_lyre_mcp_ensure_for_user($userId, true);
        $out = gos_lyre_mcp_status_payload($userId, $ens['plain_token']);
        $out['plain_token'] = $ens['plain_token'];
        return $out;
    });
}

function gos_lyre_http_mcp_disable(array $access): never
{
    gos_lyre_http_send(function () use ($access) {
        $userId = gos_lyre_user_id($access);
        gos_lyre_mcp_disable_for_user($userId);
        return gos_lyre_mcp_status_payload($userId, null);
    });
}

function gos_lyre_http_mcp_enable(array $access): never
{
    gos_lyre_http_send(function () use ($access) {
        $userId = gos_lyre_user_id($access);
        $ens = gos_lyre_mcp_enable_for_user($userId);
        $out = gos_lyre_mcp_status_payload($userId, $ens['plain_token']);
        if (is_string($ens['plain_token']) && $ens['plain_token'] !== '') {
            $out['plain_token'] = $ens['plain_token'];
        }
        return $out;
    });
}

function gos_lyre_post_rename(array $access, array $body): never
{
    $id = trim((string) ($body['id'] ?? ''));
    if ($id === '') {
        gos_lyre_fail('id_required', 400);
    }
    $name = gos_lyre_project_name((string) ($body['name'] ?? ''));
    $userId = gos_lyre_user_id($access);
    $mysql = gos_lyre_mysql();
    $row = gos_lyre_project_by_id($mysql, $userId, $id);
    if ($row === null) {
        gos_lyre_fail('not_found', 404);
    }
    $st = $mysql->prepare('UPDATE lyre_projects SET name = ? WHERE id = ? AND user_id = ?');
    $st->execute([$name, $id, $userId]);
    $row = gos_lyre_project_by_id($mysql, $userId, $id);
    gos_api_json(['ok' => true, 'project' => gos_lyre_project_public($row ?? [])]);
}

function gos_lyre_post_delete(array $access, array $body): never
{
    $id = trim((string) ($body['id'] ?? ''));
    if ($id === '') {
        gos_lyre_fail('id_required', 400);
    }
    $userId = gos_lyre_user_id($access);
    $mysql = gos_lyre_mysql();
    $row = gos_lyre_project_by_id($mysql, $userId, $id);
    if ($row === null) {
        gos_lyre_fail('not_found', 404);
    }
    $boardId = (string) $row['board_id'];
    $odysseus = gos_lyre_odysseus_board_id();
    if (((int) ($row['is_odysseus'] ?? 0)) === 1 || $boardId === $odysseus || !str_starts_with($boardId, 'lyre_phone_')) {
        gos_lyre_fail('not_deletable', 403);
    }
    gos_lyre_pg_delete(gos_lyre_pg(), $boardId);
    $del = $mysql->prepare('DELETE FROM lyre_projects WHERE id = ? AND user_id = ?');
    $del->execute([$id, $userId]);
    gos_api_json(['ok' => true]);
}

function gos_lyre_post_save_board(array $access, array $body): never
{
    gos_lyre_http_send(fn () => gos_lyre_save_board($access, $body));
}

/** @return list<string> */
function gos_lyre_known_voices(): array
{
    return ['eve', 'ara', 'leo', 'rex', 'sal', 'carina', 'helix', 'orion', 'luna', 'iris', 'sirius', 'atlas'];
}

function gos_lyre_media_id(string $prefix): string
{
    $alpha = 'abcdefghijklmnopqrstuvwxyz0123456789';
    $tail = '';
    for ($i = 0; $i < 12; $i++) {
        $tail .= $alpha[random_int(0, strlen($alpha) - 1)];
    }
    return $prefix . '_' . $tail;
}

function gos_lyre_xai_key(): string
{
    foreach (['GROKIFY_LYRE_XAI_API_KEY', 'GROKIFY_XAI_API_KEY', 'XAI_API_KEY'] as $k) {
        $v = trim((string) (gos_env($k, '') ?? ''));
        if ($v !== '') {
            return $v;
        }
    }
    if (function_exists('gos_setting_get')) {
        foreach (['discord_ai_spacexai_key', 'llm_xai_key'] as $k) {
            $v = trim((string) gos_setting_get($k, ''));
            if ($v !== '') {
                return $v;
            }
        }
    }
    return '';
}

function gos_lyre_jobs_dir(): string
{
    $dir = gos_root() . '/storage/lyre-jobs';
    if (!is_dir($dir)) {
        @mkdir($dir, 0775, true);
    }
    return $dir;
}

function gos_lyre_job_id_ok(string $id): bool
{
    return preg_match('/^[A-Za-z0-9._-]{8,128}$/', $id) === 1;
}

/** @param array<string, mixed> $job */
function gos_lyre_job_write(string $id, array $job): void
{
    if (!gos_lyre_job_id_ok($id)) {
        return;
    }
    $path = gos_lyre_jobs_dir() . '/' . $id . '.json';
    file_put_contents($path, json_encode($job, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE));
}

/** @return array<string, mixed>|null */
function gos_lyre_job_read(string $id): ?array
{
    if (!gos_lyre_job_id_ok($id)) {
        return null;
    }
    $path = gos_lyre_jobs_dir() . '/' . $id . '.json';
    if (!is_file($path)) {
        return null;
    }
    $raw = file_get_contents($path);
    $data = is_string($raw) ? json_decode($raw, true) : null;
    return is_array($data) ? $data : null;
}

function gos_lyre_files_root(): ?string
{
    $root = trim((string) (gos_env('GROKIFY_LYRE_FILES_DIR', '') ?? ''));
    if ($root === '') {
        $guess = '/root/grokme/storage/projects/lyre_grok_me/files';
        $root = is_dir($guess) ? $guess : '';
    }
    if ($root === '' || !is_dir($root)) {
        return null;
    }
    $real = realpath($root);
    return is_string($real) && $real !== '' ? $real : null;
}

function gos_lyre_mime_for_key(string $key): string
{
    $ext = strtolower(pathinfo($key, PATHINFO_EXTENSION));
    return match ($ext) {
        'jpg', 'jpeg' => 'image/jpeg',
        'png' => 'image/png',
        'webp' => 'image/webp',
        'gif' => 'image/gif',
        'mp4' => 'video/mp4',
        'webm' => 'video/webm',
        'mov' => 'video/quicktime',
        'wav' => 'audio/wav',
        'mp3' => 'audio/mpeg',
        'm4a' => 'audio/mp4',
        'aac' => 'audio/aac',
        'ogg' => 'audio/ogg',
        default => 'application/octet-stream',
    };
}

function gos_lyre_storage_write_key(string $key, string $bytes): ?string
{
    $root = gos_lyre_files_root();
    if ($root === null || $bytes === '') {
        return null;
    }
    $dest = $root . '/' . $key;
    $dir = dirname($dest);
    if (!is_dir($dir) && !@mkdir($dir, 0775, true) && !is_dir($dir)) {
        return null;
    }
    if (@file_put_contents($dest, $bytes) === false) {
        return null;
    }
    $real = realpath($dest);
    $prefix = $root . DIRECTORY_SEPARATOR;
    if (!is_string($real) || (!str_starts_with($real, $prefix) && $real !== $root)) {
        @unlink($dest);
        return null;
    }
    return $real;
}

function gos_lyre_grokme_put(string $key, string $bytes, string $mime): bool
{
    $base = rtrim((string) (gos_env('GROKIFY_LYRE_ME_STORAGE_BASE', 'https://me.grokpot.io/v1/storage') ?? ''), '/');
    $apiKey = (string) (gos_env('GROKIFY_LYRE_ME_API_KEY', '') ?? '');
    if ($base === '' || $apiKey === '' || $bytes === '' || !function_exists('curl_init')) {
        return false;
    }
    $url = $base . '/' . implode('/', array_map('rawurlencode', explode('/', $key)));
    $ch = curl_init($url);
    if ($ch === false) {
        return false;
    }
    curl_setopt_array($ch, [
        CURLOPT_CUSTOMREQUEST => 'PUT',
        CURLOPT_POSTFIELDS => $bytes,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 30,
        CURLOPT_TIMEOUT => 180,
        CURLOPT_HTTPHEADER => [
            'Authorization: Bearer ' . $apiKey,
            'Content-Type: ' . $mime,
            'Content-Length: ' . (string) strlen($bytes),
        ],
    ]);
    curl_exec($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    return $status >= 200 && $status < 300;
}

/**
 * @param list<mixed> $raw
 * @return list<string>
 */
function gos_lyre_voice_ids(array $raw): array
{
    $known = gos_lyre_known_voices();
    $out = [];
    foreach ($raw as $v) {
        $id = strtolower(trim((string) $v));
        if ($id === '' || !in_array($id, $known, true) || in_array($id, $out, true)) {
            continue;
        }
        $out[] = $id;
        if (count($out) >= 3) {
            break;
        }
    }
    return $out;
}

function gos_lyre_tag_prompt(string $prompt, int $imageCount, int $voiceCount): string
{
    $text = trim($prompt);
    if ($imageCount > 0 && !str_contains($text, '<IMAGE_')) {
        $tags = [];
        for ($i = 0; $i < $imageCount; $i++) {
            $tags[] = '<IMAGE_' . $i . '>';
        }
        $join = count($tags) === 1 ? $tags[0] : (implode(', ', array_slice($tags, 0, -1)) . ' and ' . $tags[count($tags) - 1]);
        $text = trim($text . ' Use ' . $join . '.');
    }
    if ($voiceCount > 0 && !str_contains($text, '<AUDIO_')) {
        $tags = [];
        for ($i = 0; $i < $voiceCount; $i++) {
            $tags[] = '<AUDIO_' . $i . '>';
        }
        $join = count($tags) === 1 ? $tags[0] : (implode(', ', array_slice($tags, 0, -1)) . ' and ' . $tags[count($tags) - 1]);
        $text = trim($text . ' Speak with the voice from ' . $join . '.');
    }
    return $text !== '' ? $text : 'Continue this cinematic shot.';
}

/**
 * @param list<mixed> $images
 * @return list<string> data URIs
 */
function gos_lyre_collect_image_uris(array $images, int $max = 4): array
{
    $out = [];
    foreach ($images as $img) {
        if (count($out) >= $max) {
            break;
        }
        if (is_string($img) && str_starts_with($img, 'data:')) {
            $out[] = $img;
            continue;
        }
        if (!is_array($img)) {
            continue;
        }
        $data = (string) ($img['data'] ?? $img['base64'] ?? '');
        if ($data !== '') {
            $mime = strtolower(trim((string) ($img['mimeType'] ?? $img['mime'] ?? 'image/jpeg')));
            if (!str_starts_with($mime, 'image/')) {
                $mime = 'image/jpeg';
            }
            $out[] = 'data:' . $mime . ';base64,' . preg_replace('/\s+/', '', $data);
            continue;
        }
        $url = (string) ($img['url'] ?? '');
        if (str_starts_with($url, 'data:')) {
            $out[] = $url;
            continue;
        }
        $key = gos_lyre_storage_key((string) ($img['key'] ?? $img['src'] ?? $url));
        if ($key === null) {
            continue;
        }
        $path = gos_lyre_local_object_path($key);
        if ($path === null) {
            continue;
        }
        $bytes = @file_get_contents($path);
        if (!is_string($bytes) || $bytes === '') {
            continue;
        }
        $out[] = 'data:' . gos_lyre_mime_for_key($key) . ';base64,' . base64_encode($bytes);
    }
    return $out;
}

function gos_lyre_collect_video_uri(array $body): ?string
{
    $video = $body['video'] ?? null;
    if (is_array($video)) {
        $data = (string) ($video['data'] ?? $video['base64'] ?? '');
        if ($data !== '') {
            $mime = strtolower(trim((string) ($video['mimeType'] ?? $video['mime'] ?? 'video/mp4')));
            if (!str_starts_with($mime, 'video/')) {
                $mime = 'video/mp4';
            }
            return 'data:' . $mime . ';base64,' . preg_replace('/\s+/', '', $data);
        }
        $url = (string) ($video['url'] ?? '');
        if (str_starts_with($url, 'data:')) {
            return $url;
        }
        $key = gos_lyre_storage_key((string) ($video['key'] ?? $video['src'] ?? $url));
        if ($key !== null) {
            $path = gos_lyre_local_object_path($key);
            if ($path !== null && filesize($path) !== false && filesize($path) > 0 && filesize($path) <= 40 * 1024 * 1024) {
                $bytes = @file_get_contents($path);
                if (is_string($bytes) && $bytes !== '') {
                    return 'data:' . gos_lyre_mime_for_key($key) . ';base64,' . base64_encode($bytes);
                }
            }
        }
    }
    $key = gos_lyre_storage_key((string) ($body['video_key'] ?? $body['videoKey'] ?? ''));
    if ($key === null) {
        return null;
    }
    $path = gos_lyre_local_object_path($key);
    if ($path === null || filesize($path) === false || filesize($path) <= 0 || filesize($path) > 40 * 1024 * 1024) {
        return null;
    }
    $bytes = @file_get_contents($path);
    if (!is_string($bytes) || $bytes === '') {
        return null;
    }
    return 'data:' . gos_lyre_mime_for_key($key) . ';base64,' . base64_encode($bytes);
}

/**
 * @param list<string> $uris
 * @return array<string, mixed>
 */
function gos_lyre_image_edit_payload(string $prompt, array $uris, string $aspect = ''): array
{
    $model = 'grok-imagine-image-2.0';
    $body = [
        'model' => $model,
        'prompt' => $prompt,
        'n' => 1,
    ];
    if ($aspect !== '') {
        $body['aspect_ratio'] = $aspect;
    }
    $slice = array_slice($uris, 0, 3);
    if (count($slice) === 1) {
        $body['image'] = ['url' => $slice[0], 'type' => 'image_url'];
    } elseif (count($slice) > 1) {
        $images = [];
        foreach ($slice as $uri) {
            $images[] = ['url' => $uri, 'type' => 'image_url'];
        }
        $body['images'] = $images;
    }
    return $body;
}

/**
 * @param list<string> $imageUris
 * @param list<string> $voices
 * @return array<string, mixed>
 */
function gos_lyre_video_payload(
    string $prompt,
    array $imageUris,
    array $voices,
    int $duration,
    string $aspect,
    string $resolution,
    ?string $videoUri,
    string $mode,
): array {
    $body = [
        'model' => 'grok-imagine-video-1.5',
        'prompt' => $prompt,
    ];
    if ($mode === 'edit' && $videoUri !== null) {
        $body['video'] = ['url' => $videoUri];
        return $body;
    }
    $body['duration'] = $duration;
    if ($aspect !== '') {
        $body['aspect_ratio'] = $aspect;
    }
    if ($resolution !== '') {
        $body['resolution'] = $resolution;
    }
    $useRefs = $voices !== [] || count($imageUris) > 1;
    if ($useRefs) {
        $refs = [];
        foreach (array_slice($imageUris, 0, 7) as $uri) {
            $refs[] = ['url' => $uri];
        }
        if ($refs !== []) {
            $body['reference_images'] = $refs;
        }
        if ($voices !== []) {
            $aud = [];
            foreach ($voices as $id) {
                $aud[] = ['voice_id' => $id];
            }
            $body['reference_audios'] = $aud;
        }
        if (($body['resolution'] ?? '') === '1080p') {
            $body['resolution'] = '720p';
        }
    } elseif ($imageUris !== []) {
        $body['image'] = ['url' => $imageUris[0]];
    }
    return $body;
}

/**
 * @return array{ok:bool,status:int,data:?array,raw:string,error:?string}
 */
function gos_lyre_xai_json(string $method, string $url, ?array $body, int $timeout = 120): array
{
    $key = gos_lyre_xai_key();
    if ($key === '') {
        return ['ok' => false, 'status' => 503, 'data' => null, 'raw' => '', 'error' => 'spacexai_key_missing'];
    }
    if (!function_exists('curl_init')) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'raw' => '', 'error' => 'curl_missing'];
    }
    $ch = curl_init($url);
    if ($ch === false) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'raw' => '', 'error' => 'curl_init'];
    }
    $headers = [
        'Authorization: Bearer ' . $key,
        'Accept: application/json',
    ];
    $opts = [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 20,
        CURLOPT_TIMEOUT => $timeout,
        CURLOPT_FOLLOWLOCATION => false,
    ];
    $verb = strtoupper($method);
    if ($verb === 'POST') {
        $json = json_encode($body ?? new stdClass(), JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        $headers[] = 'Content-Type: application/json';
        $opts[CURLOPT_POST] = true;
        $opts[CURLOPT_POSTFIELDS] = is_string($json) ? $json : '{}';
    } elseif ($verb !== 'GET') {
        $opts[CURLOPT_CUSTOMREQUEST] = $verb;
    }
    $opts[CURLOPT_HTTPHEADER] = $headers;
    curl_setopt_array($ch, $opts);
    $raw = curl_exec($ch);
    $errno = curl_errno($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($raw === false || $errno !== 0) {
        return ['ok' => false, 'status' => $status, 'data' => null, 'raw' => '', 'error' => 'unreachable'];
    }
    $decoded = json_decode((string) $raw, true);
    $err = null;
    if ($status >= 400) {
        $err = 'http_' . $status;
        if (is_array($decoded)) {
            $msg = $decoded['error']['message'] ?? $decoded['error'] ?? $decoded['message'] ?? null;
            if (is_string($msg) && $msg !== '') {
                $err = $msg;
            }
        }
    }
    return [
        'ok' => $status >= 200 && $status < 300,
        'status' => $status,
        'data' => is_array($decoded) ? $decoded : null,
        'raw' => (string) $raw,
        'error' => $err,
    ];
}

function gos_lyre_http_bytes(string $url, int $timeout = 120): ?string
{
    if (!function_exists('curl_init') || $url === '') {
        return null;
    }
    $ch = curl_init($url);
    if ($ch === false) {
        return null;
    }
    curl_setopt_array($ch, [
        CURLOPT_HTTPGET => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_CONNECTTIMEOUT => 20,
        CURLOPT_TIMEOUT => $timeout,
        CURLOPT_MAXFILESIZE => 80 * 1024 * 1024,
    ]);
    $raw = curl_exec($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($status >= 200 && $status < 300 && is_string($raw) && $raw !== '') {
        return $raw;
    }
    return null;
}

function gos_lyre_commit_bytes(string $key, string $bytes): bool
{
    $wrote = gos_lyre_storage_write_key($key, $bytes);
    if ($wrote === null) {
        return false;
    }
    gos_lyre_grokme_put($key, $bytes, gos_lyre_mime_for_key($key));
    return true;
}

function gos_lyre_image_result_bytes(array $data): ?string
{
    $url = '';
    $b64 = '';
    if (isset($data['data']) && is_array($data['data']) && isset($data['data'][0]) && is_array($data['data'][0])) {
        $url = (string) ($data['data'][0]['url'] ?? '');
        $b64 = (string) ($data['data'][0]['b64_json'] ?? $data['data'][0]['b64'] ?? '');
    }
    if ($url === '') {
        $url = (string) ($data['url'] ?? '');
    }
    if ($b64 !== '') {
        $decoded = base64_decode($b64, true);
        return is_string($decoded) && $decoded !== '' ? $decoded : null;
    }
    if (str_starts_with($url, 'data:')) {
        $comma = strpos($url, ',');
        if ($comma === false) {
            return null;
        }
        $decoded = base64_decode(substr($url, $comma + 1), true);
        return is_string($decoded) && $decoded !== '' ? $decoded : null;
    }
    if ($url !== '') {
        return gos_lyre_http_bytes($url);
    }
    return null;
}

function gos_lyre_storage_put_bytes(string $keyRaw, string $bytes): never
{
    $key = gos_lyre_storage_key($keyRaw);
    if ($key === null) {
        gos_api_json(['ok' => false, 'error' => 'invalid_key'], 400);
    }
    if ($bytes === '') {
        gos_api_json(['ok' => false, 'error' => 'empty_body'], 400);
    }
    if (strlen($bytes) > 80 * 1024 * 1024) {
        gos_api_json(['ok' => false, 'error' => 'too_large'], 413);
    }
    if (!gos_lyre_commit_bytes($key, $bytes)) {
        gos_api_json(['ok' => false, 'error' => 'write_failed'], 500);
    }
    gos_api_json(['ok' => true, 'key' => $key, 'size' => strlen($bytes)]);
}

function gos_lyre_storage_put_json(array $body): never
{
    $data = (string) ($body['data'] ?? $body['bytes'] ?? '');
    $bytes = '';
    if ($data !== '') {
        $decoded = base64_decode($data, true);
        if (!is_string($decoded) || $decoded === '') {
            gos_api_json(['ok' => false, 'error' => 'invalid_data'], 400);
        }
        $bytes = $decoded;
    }
    gos_lyre_storage_put_bytes((string) ($body['key'] ?? ''), $bytes);
}

function gos_lyre_storage_put_request(): never
{
    $ct = strtolower((string) ($_SERVER['CONTENT_TYPE'] ?? $_SERVER['HTTP_CONTENT_TYPE'] ?? ''));
    $raw = file_get_contents('php://input') ?: '';
    $keyRaw = (string) ($_GET['key'] ?? '');
    $bytes = $raw;
    if (str_contains($ct, 'json')) {
        $body = json_decode($raw, true);
        if (!is_array($body)) {
            gos_api_json(['ok' => false, 'error' => 'invalid_json'], 400);
        }
        gos_lyre_storage_put_json($body);
    }
    gos_lyre_storage_put_bytes($keyRaw, $bytes);
}

function gos_lyre_post_imagine_still(array $body): never
{
    @set_time_limit(180);
    $prompt = trim((string) ($body['prompt'] ?? ''));
    $aspect = trim((string) ($body['aspect_ratio'] ?? $body['aspect'] ?? '16:9'));
    $images = $body['images'] ?? [];
    if (!is_array($images)) {
        $images = [];
    }
    $uris = gos_lyre_collect_image_uris($images, 4);
    if ($uris === [] && $prompt === '') {
        gos_api_json(['ok' => false, 'error' => 'prompt_required'], 400);
    }
    $tagged = gos_lyre_tag_prompt($prompt !== '' ? $prompt : 'Generate the next cinematic still in this sequence.', count($uris), 0);
    $payload = gos_lyre_image_edit_payload($tagged, $uris, $aspect);
    $url = $uris === []
        ? 'https://api.x.ai/v1/images/generations'
        : 'https://api.x.ai/v1/images/edits';
    if ($uris === []) {
        unset($payload['image'], $payload['images']);
        $payload['prompt'] = $tagged;
        $payload['model'] = 'grok-imagine-image-2.0';
        if ($aspect !== '') {
            $payload['aspect_ratio'] = $aspect;
        }
    }
    $res = gos_lyre_xai_json('POST', $url, $payload, 120);
    if (!$res['ok'] && ($res['status'] === 404 || $res['status'] === 400) && $uris !== []) {
        $single = $payload;
        unset($single['images']);
        if (isset($uris[0])) {
            $single['image'] = ['url' => $uris[0], 'type' => 'image_url'];
        }
        $res = gos_lyre_xai_json('POST', $url, $single, 120);
    }
    if (!$res['ok'] && ($res['status'] === 404 || $res['status'] === 400)) {
        $gen = [
            'model' => 'grok-imagine-image-2.0',
            'prompt' => $tagged,
            'n' => 1,
        ];
        if ($aspect !== '') {
            $gen['aspect_ratio'] = $aspect;
        }
        $res = gos_lyre_xai_json('POST', 'https://api.x.ai/v1/images/generations', $gen, 120);
    }
    if (!$res['ok']) {
        $code = $res['status'] === 503 ? 503 : 502;
        gos_api_json(['ok' => false, 'error' => $res['error'] ?? 'imagine_failed'], $code);
    }
    $bytes = is_array($res['data']) ? gos_lyre_image_result_bytes($res['data']) : null;
    if ($bytes === null) {
        gos_api_json(['ok' => false, 'error' => 'empty_image'], 502);
    }
    $key = 'stills/' . gos_lyre_media_id('st') . '.jpg';
    if (!gos_lyre_commit_bytes($key, $bytes)) {
        gos_api_json(['ok' => false, 'error' => 'write_failed'], 500);
    }
    gos_api_json([
        'ok' => true,
        'status' => 'done',
        'key' => $key,
        'src' => 'me:' . $key,
        'kind' => 'still',
    ]);
}

function gos_lyre_post_imagine_video(array $body): never
{
    $prompt = trim((string) ($body['prompt'] ?? ''));
    $mode = strtolower(trim((string) ($body['mode'] ?? 'generate')));
    $duration = (int) ($body['duration'] ?? 6);
    if ($duration < 1) {
        $duration = 6;
    }
    if ($duration > 15) {
        $duration = 15;
    }
    $aspect = trim((string) ($body['aspect_ratio'] ?? $body['aspect'] ?? '16:9'));
    $resolution = trim((string) ($body['resolution'] ?? '720p'));
    $images = $body['images'] ?? [];
    if (!is_array($images)) {
        $images = [];
    }
    if (isset($body['image']) && is_array($body['image'])) {
        array_unshift($images, $body['image']);
    }
    $uris = gos_lyre_collect_image_uris($images, 4);
    $voices = $body['voice_ids'] ?? $body['voices'] ?? [];
    if (!is_array($voices)) {
        $voices = [];
    }
    $voiceIds = gos_lyre_voice_ids($voices);
    $videoUri = $mode === 'edit' ? gos_lyre_collect_video_uri($body) : null;
    if ($mode === 'edit' && $videoUri === null && $uris === []) {
        gos_api_json(['ok' => false, 'error' => 'video_required'], 400);
    }
    $tagged = gos_lyre_tag_prompt(
        $prompt !== '' ? $prompt : ($mode === 'edit' ? 'Edit this clip as directed.' : 'Animate this still with a slow cinematic camera move.'),
        count($uris),
        count($voiceIds),
    );
    $payload = gos_lyre_video_payload($tagged, $uris, $voiceIds, $duration, $aspect, $resolution, $videoUri, $mode);
    $res = gos_lyre_xai_json('POST', 'https://api.x.ai/v1/videos/generations', $payload, 60);
    if (!$res['ok'] && $mode === 'edit' && $uris !== []) {
        $fallback = gos_lyre_video_payload($tagged, $uris, $voiceIds, $duration, $aspect, $resolution, null, 'generate');
        $res = gos_lyre_xai_json('POST', 'https://api.x.ai/v1/videos/generations', $fallback, 60);
    }
    if (!$res['ok']) {
        $code = $res['status'] === 503 ? 503 : 502;
        gos_api_json(['ok' => false, 'error' => $res['error'] ?? 'imagine_failed'], $code);
    }
    $data = is_array($res['data']) ? $res['data'] : [];
    $rid = (string) ($data['request_id'] ?? $data['id'] ?? '');
    if ($rid === '' || !gos_lyre_job_id_ok($rid)) {
        gos_api_json(['ok' => false, 'error' => 'no_request_id'], 502);
    }
    gos_lyre_job_write($rid, [
        'request_id' => $rid,
        'kind' => 'video',
        'status' => 'pending',
        'duration' => $duration,
        'mode' => $mode,
        'created_at' => time(),
    ]);
    gos_api_json(['ok' => true, 'status' => 'pending', 'request_id' => $rid, 'kind' => 'video']);
}

function gos_lyre_get_imagine_status(string $requestId): never
{
    if (!gos_lyre_job_id_ok($requestId)) {
        gos_api_json(['ok' => false, 'error' => 'request_id_required'], 400);
    }
    $job = gos_lyre_job_read($requestId);
    if ($job !== null && ($job['status'] ?? '') === 'done' && !empty($job['key'])) {
        gos_api_json([
            'ok' => true,
            'status' => 'done',
            'request_id' => $requestId,
            'key' => $job['key'],
            'src' => 'me:' . $job['key'],
            'duration' => $job['duration'] ?? null,
            'kind' => 'video',
        ]);
    }
    $res = gos_lyre_xai_json('GET', 'https://api.x.ai/v1/videos/' . rawurlencode($requestId), null, 30);
    if (!$res['ok']) {
        $code = $res['status'] === 503 ? 503 : (($res['status'] === 404) ? 404 : 502);
        gos_api_json(['ok' => false, 'error' => $res['error'] ?? 'status_failed', 'status' => 'failed'], $code);
    }
    $data = is_array($res['data']) ? $res['data'] : [];
    $st = strtolower((string) ($data['status'] ?? 'pending'));
    if ($st === 'done' || $st === 'completed' || $st === 'succeeded') {
        $url = (string) ($data['video']['url'] ?? $data['url'] ?? '');
        $bytes = $url !== '' ? gos_lyre_http_bytes($url, 180) : null;
        if ($bytes === null) {
            gos_api_json(['ok' => false, 'error' => 'empty_video', 'status' => 'failed'], 502);
        }
        $key = 'videos/' . gos_lyre_media_id('vid') . '.mp4';
        if (!gos_lyre_commit_bytes($key, $bytes)) {
            gos_api_json(['ok' => false, 'error' => 'write_failed', 'status' => 'failed'], 500);
        }
        $duration = $data['video']['duration'] ?? $data['duration'] ?? ($job['duration'] ?? 6);
        $next = [
            'request_id' => $requestId,
            'kind' => 'video',
            'status' => 'done',
            'key' => $key,
            'duration' => $duration,
            'created_at' => $job['created_at'] ?? time(),
        ];
        gos_lyre_job_write($requestId, $next);
        gos_api_json([
            'ok' => true,
            'status' => 'done',
            'request_id' => $requestId,
            'key' => $key,
            'src' => 'me:' . $key,
            'duration' => $duration,
            'kind' => 'video',
        ]);
    }
    if ($st === 'failed' || $st === 'expired' || $st === 'error') {
        gos_api_json(['ok' => false, 'status' => $st, 'error' => (string) ($data['error'] ?? $st)], 502);
    }
    gos_api_json(['ok' => true, 'status' => 'pending', 'request_id' => $requestId, 'kind' => 'video']);
}

require_once dirname(__DIR__) . '/includes/lyre_director.php';
require_once dirname(__DIR__) . '/includes/lyre_mcp.php';

if (defined('GOS_LYRE_NO_ROUTE') && GOS_LYRE_NO_ROUTE) {
    return;
}

try {
    $httpMethod = strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'));
    $qsAction = strtolower(trim((string) ($_GET['action'] ?? '')));

    if ($httpMethod === 'GET' && $qsAction === 'storage_get') {
        gos_lyre_auth();
        gos_lyre_storage_get((string) ($_GET['key'] ?? ''));
    }
    if ($httpMethod === 'POST' && $qsAction === 'storage_put') {
        gos_lyre_auth();
        gos_lyre_storage_put_request();
    }
    if ($httpMethod === 'GET') {
        $access = gos_lyre_auth();
        $action = $qsAction !== '' ? $qsAction : 'projects';
        if ($action === 'projects') {
            gos_lyre_get_projects($access);
        }
        if ($action === 'project') {
            gos_lyre_get_project($access, (string) ($_GET['id'] ?? ''));
        }
        if ($action === 'board') {
            gos_lyre_get_board($access, (string) ($_GET['board_id'] ?? $_GET['id'] ?? ''));
        }
        if ($action === 'head') {
            gos_lyre_http_send(fn () => gos_lyre_director_head($access, [
                'board_id' => (string) ($_GET['board_id'] ?? $_GET['id'] ?? ''),
                'project_id' => (string) ($_GET['project_id'] ?? ''),
            ]));
        }
        if ($action === 'snapshot') {
            gos_lyre_http_send(fn () => gos_lyre_director_snapshot($access, [
                'board_id' => (string) ($_GET['board_id'] ?? $_GET['id'] ?? ''),
                'project_id' => (string) ($_GET['project_id'] ?? ''),
                'activity_limit' => (int) ($_GET['activity_limit'] ?? 20),
            ]));
        }
        if ($action === 'activity') {
            gos_lyre_http_send(fn () => gos_lyre_director_activity($access, [
                'board_id' => (string) ($_GET['board_id'] ?? $_GET['id'] ?? ''),
                'project_id' => (string) ($_GET['project_id'] ?? ''),
                'limit' => (int) ($_GET['limit'] ?? 50),
                'before_ts' => $_GET['before_ts'] ?? null,
            ]));
        }
        if ($action === 'open') {
            gos_lyre_http_open($access, [
                'id' => (string) ($_GET['id'] ?? $_GET['project_id'] ?? ''),
                'project_id' => (string) ($_GET['project_id'] ?? $_GET['id'] ?? ''),
                'board_id' => (string) ($_GET['board_id'] ?? ''),
            ]);
        }
        if ($action === 'mcp_status') {
            gos_lyre_http_mcp_status($access);
        }
        if ($action === 'imagine_status') {
            gos_lyre_get_imagine_status((string) ($_GET['request_id'] ?? $_GET['id'] ?? ''));
        }
        gos_api_json(['ok' => false, 'error' => 'unknown_action'], 404);
    }
    if ($httpMethod !== 'POST') {
        gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
    }

    $access = gos_lyre_auth();
    $body = gos_json_body();
    $action = strtolower(trim((string) ($body['action'] ?? '')));
    if ($action === 'create') {
        gos_lyre_post_create($access, $body);
    }
    if ($action === 'rename') {
        gos_lyre_post_rename($access, $body);
    }
    if ($action === 'delete') {
        gos_lyre_post_delete($access, $body);
    }
    if ($action === 'save_board') {
        gos_lyre_post_save_board($access, $body);
    }
    if ($action === 'snapshot') {
        gos_lyre_http_send(fn () => gos_lyre_director_snapshot($access, $body));
    }
    if ($action === 'folder') {
        gos_lyre_http_send(fn () => gos_lyre_director_folder($access, $body));
    }
    if ($action === 'scene') {
        gos_lyre_http_send(fn () => gos_lyre_director_scene($access, $body));
    }
    if ($action === 'place') {
        gos_lyre_http_send(fn () => gos_lyre_director_place($access, $body));
    }
    if ($action === 'trim') {
        gos_lyre_http_send(fn () => gos_lyre_director_trim($access, $body));
    }
    if ($action === 'move') {
        gos_lyre_http_send(fn () => gos_lyre_director_move($access, $body));
    }
    if ($action === 'delete_clip') {
        gos_lyre_http_send(fn () => gos_lyre_director_delete($access, $body));
    }
    if ($action === 'activity_append') {
        gos_lyre_http_send(fn () => gos_lyre_director_activity_append($access, $body));
    }
    if ($action === 'open') {
        gos_lyre_http_open($access, $body);
    }
    if ($action === 'mcp_status') {
        gos_lyre_http_mcp_status($access);
    }
    if ($action === 'mcp_ensure') {
        gos_lyre_http_mcp_ensure($access);
    }
    if ($action === 'mcp_rotate') {
        gos_lyre_http_mcp_rotate($access, $body);
    }
    if ($action === 'mcp_disable') {
        gos_lyre_http_mcp_disable($access);
    }
    if ($action === 'mcp_enable') {
        gos_lyre_http_mcp_enable($access);
    }
    if ($action === 'storage_put') {
        gos_lyre_storage_put_json($body);
    }
    if ($action === 'imagine_still') {
        gos_lyre_post_imagine_still($body);
    }
    if ($action === 'imagine_video') {
        gos_lyre_post_imagine_video($body);
    }
    gos_api_json(['ok' => false, 'error' => 'unknown_action'], 400);
} catch (GosLyreException $e) {
    gos_api_json($e->toHttpBody(), $e->http);
}
