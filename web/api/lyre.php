<?php

declare(strict_types=1);

/**
 * Device-auth LYRE proxy: MySQL lyre_projects, Postgres BoardData, grokme storage GET.
 */

require_once __DIR__ . '/_common.php';

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
        gos_api_json(['ok' => false, 'error' => 'auth_required'], 401);
    }
    return $id;
}

function gos_lyre_mysql(): PDO
{
    if (!gos_table_exists('lyre_projects')) {
        gos_api_json(['ok' => false, 'error' => 'lyre_unconfigured'], 503);
    }
    return gos_pdo();
}

function gos_lyre_pg(): PDO
{
    try {
        return gos_lyre_pdo();
    } catch (Throwable) {
        gos_api_json(['ok' => false, 'error' => 'lyre_pg_unavailable'], 503);
    }
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
    } catch (Throwable) {
        gos_api_json(['ok' => false, 'error' => 'lyre_pg_unavailable'], 503);
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
        gos_api_json(['ok' => false, 'error' => 'invalid_board'], 400);
    }
    try {
        $st = $pg->prepare(
            'INSERT INTO boards (id, title, brainstorm, payload, updated_at)
             VALUES (?, ?, ?, CAST(? AS jsonb), NOW())'
        );
        $st->execute([$id, $title, $brainstorm, $json]);
    } catch (Throwable) {
        gos_api_json(['ok' => false, 'error' => 'lyre_pg_unavailable'], 503);
    }
}

function gos_lyre_pg_update(PDO $pg, string $id, array $data): void
{
    if (gos_lyre_pg_select($pg, $id) === null) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    $title = trim((string) ($data['title'] ?? 'Untitled'));
    if ($title === '') {
        $title = 'Untitled';
    }
    $brainstorm = (string) ($data['brainstorm'] ?? '');
    $json = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if (!is_string($json) || $json === '') {
        gos_api_json(['ok' => false, 'error' => 'invalid_board'], 400);
    }
    try {
        $st = $pg->prepare(
            'UPDATE boards
             SET title = ?, brainstorm = ?, payload = CAST(? AS jsonb), updated_at = NOW()
             WHERE id = ?'
        );
        $st->execute([$title, $brainstorm, $json, $id]);
    } catch (Throwable) {
        gos_api_json(['ok' => false, 'error' => 'lyre_pg_unavailable'], 503);
    }
}

function gos_lyre_pg_delete(PDO $pg, string $id): void
{
    try {
        $st = $pg->prepare('DELETE FROM boards WHERE id = ?');
        $st->execute([$id]);
    } catch (Throwable) {
        gos_api_json(['ok' => false, 'error' => 'lyre_pg_unavailable'], 503);
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
                gos_api_json(['ok' => false, 'error' => 'db_error'], 500);
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
    $key = ltrim(str_replace('\\', '/', $raw), '/');
    if ($key === '' || str_contains($key, '..')) {
        return null;
    }
    if (preg_match('#^boards/[A-Za-z0-9_./-]+$#', $key) === 1) {
        return $key;
    }
    if (preg_match('#^public/watch/[a-f0-9]{32}\.mp4$#', $key) === 1) {
        return $key;
    }
    return null;
}

function gos_lyre_json_error(string $error, int $code): never
{
    header('Content-Type: application/json; charset=utf-8');
    gos_api_json(['ok' => false, 'error' => $error], $code);
}

function gos_lyre_storage_put(string $rawKey): never
{
    $key = gos_lyre_storage_key($rawKey);
    if ($key === null) {
        gos_lyre_json_error('invalid_key', 400);
    }
    $base = rtrim((string) (gos_env('GROKIFY_LYRE_ME_STORAGE_BASE', 'https://me.grokpot.io/v1/storage') ?? ''), '/');
    $apiKey = (string) (gos_env('GROKIFY_LYRE_ME_API_KEY', '') ?? '');
    if ($base === '' || $apiKey === '') {
        gos_lyre_json_error('lyre_storage_unconfigured', 503);
    }
    if (!function_exists('curl_init')) {
        gos_lyre_json_error('curl_missing', 500);
    }
    $tmp = tmpfile();
    $in = fopen('php://input', 'rb');
    if ($tmp === false || $in === false) {
        gos_lyre_json_error('storage_put_spool', 500);
    }
    stream_copy_to_stream($in, $tmp);
    fclose($in);
    rewind($tmp);
    $stat = fstat($tmp);
    $size = (int) ($stat['size'] ?? 0);
    if ($size <= 0) {
        fclose($tmp);
        gos_lyre_json_error('empty_body', 400);
    }
    $url = $base . '/' . implode('/', array_map('rawurlencode', explode('/', $key)));
    $ch = curl_init($url);
    if ($ch === false) {
        fclose($tmp);
        gos_lyre_json_error('storage_put_failed', 502);
    }
    $ctype = (string) ($_SERVER['CONTENT_TYPE'] ?? 'application/octet-stream');
    if ($ctype === '') {
        $ctype = 'application/octet-stream';
    }
    curl_setopt_array($ch, [
        CURLOPT_CUSTOMREQUEST => 'POST',
        CURLOPT_UPLOAD => true,
        CURLOPT_INFILE => $tmp,
        CURLOPT_INFILESIZE => $size,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_CONNECTTIMEOUT => 30,
        CURLOPT_TIMEOUT => 300, // Apache Timeout 300 caps the hop
        CURLOPT_HTTPHEADER => [
            'Authorization: Bearer ' . $apiKey,
            'Content-Type: ' . $ctype,
            'Content-Length: ' . (string) $size,
        ],
    ]);
    $raw = curl_exec($ch);
    $errno = curl_errno($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    fclose($tmp);
    if ($errno === 28) {
        gos_lyre_json_error('storage_put_timeout', 504);
    }
    if ($raw === false || $errno !== 0) {
        gos_lyre_json_error('storage_put_failed', 502);
    }
    if ($status === 504) {
        gos_lyre_json_error('storage_put_timeout', 504);
    }
    if ($status < 200 || $status >= 300) {
        gos_lyre_json_error('storage_put_failed', $status >= 400 ? $status : 502);
    }
    $json = json_decode(is_string($raw) ? $raw : '', true);
    if (!is_array($json)) {
        $json = ['ok' => true, 'key' => $key, 'bytes' => $size];
    }
    $json['ok'] = true;
    gos_api_json($json);
}

function gos_lyre_storage_get(string $rawKey): never
{
    $key = gos_lyre_storage_key($rawKey);
    if ($key === null) {
        gos_lyre_json_error('invalid_key', 400);
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
        gos_lyre_json_error('not_found', 404);
    }
    gos_lyre_json_error('storage_get_failed', $status >= 400 ? $status : 502);
}

function gos_lyre_get_projects(array $access): never
{
    $userId = gos_lyre_user_id($access);
    gos_lyre_ensure_odysseus($userId);
    $mysql = gos_lyre_mysql();
    $st = $mysql->prepare(
        'SELECT * FROM lyre_projects WHERE user_id = ? ORDER BY is_odysseus DESC, updated_at DESC'
    );
    $st->execute([$userId]);
    $projects = [];
    while ($row = $st->fetch()) {
        if (is_array($row)) {
            $projects[] = gos_lyre_project_public($row);
        }
    }
    gos_api_json(['ok' => true, 'projects' => $projects]);
}

function gos_lyre_get_project(array $access, string $id): never
{
    $id = trim($id);
    if ($id === '') {
        gos_api_json(['ok' => false, 'error' => 'id_required'], 400);
    }
    $userId = gos_lyre_user_id($access);
    $row = gos_lyre_project_by_id(gos_lyre_mysql(), $userId, $id);
    if ($row === null) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    gos_api_json(['ok' => true, 'project' => gos_lyre_project_public($row)]);
}

function gos_lyre_get_board(array $access, string $boardId): never
{
    $boardId = trim($boardId);
    if ($boardId === '') {
        gos_api_json(['ok' => false, 'error' => 'board_id_required'], 400);
    }
    $userId = gos_lyre_user_id($access);
    $proj = gos_lyre_project_by_board(gos_lyre_mysql(), $userId, $boardId);
    if ($proj === null) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    $row = gos_lyre_pg_select(gos_lyre_pg(), $boardId);
    if ($row === null) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    $data = gos_lyre_payload_array($row['payload'] ?? null);
    gos_api_json([
        'ok' => true,
        'board_id' => (string) $row['id'],
        'data' => $data === [] ? new stdClass() : $data,
        'updated_at' => (string) ($row['updated_at'] ?? ''),
    ]);
}

function gos_lyre_post_create(array $access, array $body): never
{
    $userId = gos_lyre_user_id($access);
    $name = gos_lyre_project_name((string) ($body['name'] ?? ''));
    $boardId = 'lyre_phone_' . gos_lyre_uuid();
    $projectId = gos_lyre_new_hex_id();
    $empty = gos_lyre_empty_board();
    $empty['title'] = $name;
    $pg = gos_lyre_pg();
    gos_lyre_pg_insert($pg, $boardId, $empty);
    try {
        $ins = gos_lyre_mysql()->prepare(
            'INSERT INTO lyre_projects (id, user_id, name, visibility, board_id, watch_token, is_odysseus)
             VALUES (?, ?, ?, ?, ?, NULL, 0)'
        );
        $ins->execute([$projectId, $userId, $name, 'private', $boardId]);
    } catch (Throwable) {
        gos_lyre_pg_delete($pg, $boardId);
        gos_api_json(['ok' => false, 'error' => 'db_error'], 500);
    }
    $row = gos_lyre_project_by_id(gos_lyre_mysql(), $userId, $projectId);
    if ($row === null) {
        gos_api_json(['ok' => false, 'error' => 'db_error'], 500);
    }
    gos_api_json(['ok' => true, 'project' => gos_lyre_project_public($row)]);
}

function gos_lyre_post_rename(array $access, array $body): never
{
    $id = trim((string) ($body['id'] ?? ''));
    if ($id === '') {
        gos_api_json(['ok' => false, 'error' => 'id_required'], 400);
    }
    $name = gos_lyre_project_name((string) ($body['name'] ?? ''));
    $userId = gos_lyre_user_id($access);
    $mysql = gos_lyre_mysql();
    $row = gos_lyre_project_by_id($mysql, $userId, $id);
    if ($row === null) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
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
        gos_api_json(['ok' => false, 'error' => 'id_required'], 400);
    }
    $userId = gos_lyre_user_id($access);
    $mysql = gos_lyre_mysql();
    $row = gos_lyre_project_by_id($mysql, $userId, $id);
    if ($row === null) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    $boardId = (string) $row['board_id'];
    $odysseus = gos_lyre_odysseus_board_id();
    if (((int) ($row['is_odysseus'] ?? 0)) === 1 || $boardId === $odysseus || !str_starts_with($boardId, 'lyre_phone_')) {
        gos_api_json(['ok' => false, 'error' => 'not_deletable'], 403);
    }
    gos_lyre_pg_delete(gos_lyre_pg(), $boardId);
    $del = $mysql->prepare('DELETE FROM lyre_projects WHERE id = ? AND user_id = ?');
    $del->execute([$id, $userId]);
    gos_api_json(['ok' => true]);
}

function gos_lyre_post_save_board(array $access, array $body): never
{
    $userId = gos_lyre_user_id($access);
    $mysql = gos_lyre_mysql();
    $id = trim((string) ($body['id'] ?? ''));
    $boardId = trim((string) ($body['board_id'] ?? ''));
    $row = null;
    if ($id !== '') {
        $row = gos_lyre_project_by_id($mysql, $userId, $id);
        if ($row === null) {
            $row = gos_lyre_project_by_board($mysql, $userId, $id);
        }
    }
    if ($row === null && $boardId !== '') {
        $row = gos_lyre_project_by_board($mysql, $userId, $boardId);
    }
    if ($row === null) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    $data = $body['data'] ?? null;
    if (is_string($data) && $data !== '') {
        $data = json_decode($data, true);
    }
    if (!is_array($data)) {
        gos_api_json(['ok' => false, 'error' => 'data_required'], 400);
    }
    $target = (string) $row['board_id'];
    gos_lyre_pg_update(gos_lyre_pg(), $target, $data);
    $touch = $mysql->prepare(
        'UPDATE lyre_projects SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?'
    );
    $touch->execute([(string) $row['id'], $userId]);
    gos_api_json(['ok' => true, 'board_id' => $target]);
}

function gos_lyre_me_api_base(): string
{
    return rtrim((string) (gos_env('GROKIFY_LYRE_ME_API_BASE', 'https://me.grokpot.io/v1') ?? ''), '/');
}

function gos_lyre_me_storage_base(): string
{
    return rtrim((string) (gos_env('GROKIFY_LYRE_ME_STORAGE_BASE', 'https://me.grokpot.io/v1/storage') ?? ''), '/');
}

function gos_lyre_me_api_key(): string
{
    return (string) (gos_env('GROKIFY_LYRE_ME_API_KEY', '') ?? '');
}

function gos_lyre_me_object_url(string $key): string
{
    return gos_lyre_me_storage_base() . '/' . implode('/', array_map('rawurlencode', explode('/', $key)));
}

/** @return array{status:int, body:string, content_type:string} */
function gos_lyre_me_http(string $method, string $url, ?string $body, int $timeout, array $extraHeaders = []): array
{
    $apiKey = gos_lyre_me_api_key();
    if ($apiKey === '' || $url === '') {
        return ['status' => 0, 'body' => '', 'content_type' => ''];
    }
    if (!function_exists('curl_init')) {
        return ['status' => 0, 'body' => '', 'content_type' => ''];
    }
    $ch = curl_init($url);
    if ($ch === false) {
        return ['status' => 0, 'body' => '', 'content_type' => ''];
    }
    $headers = array_merge([
        'Authorization: Bearer ' . $apiKey,
        'Accept: application/json, text/event-stream, */*',
    ], $extraHeaders);
    $opts = [
        CURLOPT_CUSTOMREQUEST => $method,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_CONNECTTIMEOUT => 15,
        CURLOPT_TIMEOUT => $timeout,
        CURLOPT_HTTPHEADER => $headers,
    ];
    if ($body !== null) {
        $opts[CURLOPT_POSTFIELDS] = $body;
        $hasCt = false;
        foreach ($headers as $h) {
            if (stripos($h, 'Content-Type:') === 0) {
                $hasCt = true;
                break;
            }
        }
        if (!$hasCt) {
            $headers[] = 'Content-Type: application/json';
            $opts[CURLOPT_HTTPHEADER] = $headers;
        }
    }
    curl_setopt_array($ch, $opts);
    $raw = curl_exec($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $ctype = (string) curl_getinfo($ch, CURLINFO_CONTENT_TYPE);
    curl_close($ch);
    return [
        'status' => $status,
        'body' => is_string($raw) ? $raw : '',
        'content_type' => $ctype,
    ];
}

function gos_lyre_me_object_exists(string $key): bool
{
    $url = gos_lyre_me_object_url($key);
    $apiKey = gos_lyre_me_api_key();
    if ($apiKey === '' || $url === '' || !function_exists('curl_init')) {
        return false;
    }
    $ch = curl_init($url);
    if ($ch === false) {
        return false;
    }
    $status = 0;
    curl_setopt_array($ch, [
        CURLOPT_HTTPGET => true,
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_CONNECTTIMEOUT => 15,
        CURLOPT_TIMEOUT => 30,
        CURLOPT_HTTPHEADER => [
            'Authorization: Bearer ' . $apiKey,
            'Range: bytes=0-0',
        ],
        CURLOPT_HEADERFUNCTION => static function ($ch, string $header) use (&$status): int {
            if (preg_match('#^HTTP/\S+\s+(\d+)#', $header, $m) === 1) {
                $status = (int) $m[1];
            }
            return strlen($header);
        },
        CURLOPT_WRITEFUNCTION => static function ($ch, string $data): int {
            return 0;
        },
    ]);
    curl_exec($ch);
    curl_close($ch);
    return $status === 200 || $status === 206;
}

function gos_lyre_me_put_bytes(string $key, string $bytes, string $ctype): bool
{
    if ($bytes === '') {
        return false;
    }
    $res = gos_lyre_me_http('POST', gos_lyre_me_object_url($key), $bytes, 120, [
        'Content-Type: ' . $ctype,
        'Content-Length: ' . (string) strlen($bytes),
    ]);
    return $res['status'] >= 200 && $res['status'] < 300;
}

function gos_lyre_harvest_image_url(string $text): ?string
{
    if ($text === '') {
        return null;
    }
    if (preg_match('/!\[[^\]]*]\((https?:\/\/[^)\s]+)\)/i', $text, $m) === 1) {
        return $m[1];
    }
    if (preg_match('/https?:\/\/[^\s"\'<>]+?\.(?:png|jpe?g|webp|gif)(?:\?[^\s"\'<>]*)?/i', $text, $m) === 1) {
        return $m[0];
    }
    if (preg_match('#(?:/v1/storage/|boards/)[A-Za-z0-9_./%-]+\.(?:png|jpe?g|webp|gif)#i', $text, $m) === 1) {
        $path = $m[0];
        if (str_starts_with($path, 'http')) {
            return $path;
        }
        if (str_starts_with($path, '/v1/storage/')) {
            $site = (string) preg_replace('#/v1$#', '', gos_lyre_me_api_base());
            return $site . $path;
        }
        return gos_lyre_me_object_url(ltrim($path, '/'));
    }
    return null;
}

/** @param mixed $data */
function gos_lyre_harvest_from_json($data): ?string
{
    if (is_string($data)) {
        return gos_lyre_harvest_image_url($data);
    }
    if (!is_array($data)) {
        return null;
    }
    foreach (['url', 'path', 'content', 'image'] as $k) {
        if (!array_key_exists($k, $data)) {
            continue;
        }
        $v = $data[$k];
        if (is_string($v)) {
            $found = gos_lyre_harvest_image_url($v);
            if ($found !== null) {
                return $found;
            }
            if ($k === 'url' && preg_match('#^https?://#i', $v) === 1) {
                return $v;
            }
            if ($k === 'path' && $v !== '') {
                return str_starts_with($v, 'http') ? $v : gos_lyre_me_object_url(ltrim($v, '/'));
            }
        } elseif (is_array($v)) {
            $found = gos_lyre_harvest_from_json($v);
            if ($found !== null) {
                return $found;
            }
        }
    }
    if (isset($data['media']) && is_array($data['media'])) {
        $found = gos_lyre_harvest_from_json($data['media']);
        if ($found !== null) {
            return $found;
        }
    }
    return null;
}

function gos_lyre_harvest_sse(string $sse): ?string
{
    $found = null;
    $event = '';
    foreach (preg_split("/\r\n|\n|\r/", $sse) ?: [] as $line) {
        if (str_starts_with($line, 'event:')) {
            $event = trim(substr($line, 6));
            continue;
        }
        if (!str_starts_with($line, 'data:')) {
            continue;
        }
        $payload = trim(substr($line, 5));
        if ($payload === '' || $payload === '[DONE]') {
            continue;
        }
        $json = json_decode($payload, true);
        if (is_array($json)) {
            $hit = gos_lyre_harvest_from_json($json);
            if ($hit !== null) {
                $found = $hit;
            }
            if ($event === 'media' || ($json['type'] ?? '') === 'media') {
                $media = gos_lyre_harvest_from_json($json);
                if ($media !== null) {
                    $found = $media;
                }
            }
        } else {
            $hit = gos_lyre_harvest_image_url($payload);
            if ($hit !== null) {
                $found = $hit;
            }
        }
    }
    if ($found === null) {
        $found = gos_lyre_harvest_image_url($sse);
    }
    return $found;
}

function gos_lyre_download_url(string $url): ?string
{
    if ($url === '') {
        return null;
    }
    if (!function_exists('curl_init')) {
        return null;
    }
    $ch = curl_init($url);
    if ($ch === false) {
        return null;
    }
    $headers = ['Accept: */*'];
    if (str_contains($url, 'me.grokpot.io')) {
        $headers[] = 'Authorization: Bearer ' . gos_lyre_me_api_key();
    }
    curl_setopt_array($ch, [
        CURLOPT_HTTPGET => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS => 5,
        CURLOPT_CONNECTTIMEOUT => 15,
        CURLOPT_TIMEOUT => 60,
        CURLOPT_HTTPHEADER => $headers,
    ]);
    $raw = curl_exec($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($status !== 200 || !is_string($raw) || $raw === '') {
        return null;
    }
    return $raw;
}

function gos_lyre_inline_images(array $body): array
{
    $out = [];
    $raw = $body['images'] ?? [];
    if (!is_array($raw)) {
        return $out;
    }
    foreach ($raw as $img) {
        if (!is_array($img)) {
            continue;
        }
        $data = (string) ($img['data'] ?? $img['base64'] ?? '');
        $mime = (string) ($img['mimeType'] ?? $img['mime'] ?? 'image/jpeg');
        if (str_starts_with($data, 'data:')) {
            if (preg_match('#^data:([^;]+);base64,(.+)$#', $data, $m) === 1) {
                $mime = $m[1];
                $data = $m[2];
            }
        }
        if ($data === '') {
            continue;
        }
        if ($mime === '') {
            $mime = 'image/jpeg';
        }
        $out[] = ['data' => $data, 'mimeType' => $mime];
        if (count($out) >= 4) {
            break;
        }
    }
    return $out;
}

function gos_lyre_imagine_project(array $access, array $body): array
{
    $userId = gos_lyre_user_id($access);
    $mysql = gos_lyre_mysql();
    $id = trim((string) ($body['project_id'] ?? $body['id'] ?? ''));
    $boardId = trim((string) ($body['board_id'] ?? ''));
    $row = null;
    if ($id !== '') {
        $row = gos_lyre_project_by_id($mysql, $userId, $id);
        if ($row === null) {
            $row = gos_lyre_project_by_board($mysql, $userId, $id);
        }
    }
    if ($row === null && $boardId !== '') {
        $row = gos_lyre_project_by_board($mysql, $userId, $boardId);
    }
    if ($row === null) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    return $row;
}

function gos_lyre_imagine_unavailable(): never
{
    gos_api_json(['ok' => false, 'error' => 'grokme_unavailable'], 502);
}

function gos_lyre_imagine_still(array $access, array $body): never
{
    if (gos_lyre_me_api_key() === '' || gos_lyre_me_api_base() === '') {
        gos_lyre_imagine_unavailable();
    }
    $row = gos_lyre_imagine_project($access, $body);
    $boardId = (string) $row['board_id'];
    $frameId = trim((string) ($body['frame_id'] ?? ''));
    if (preg_match('/^[A-Za-z0-9_-]{1,64}$/', $frameId) !== 1) {
        gos_api_json(['ok' => false, 'error' => 'frame_id_required'], 400);
    }
    $prompt = trim((string) ($body['prompt'] ?? ''));
    $images = gos_lyre_inline_images($body);
    $message = "Call `image_gen` / `image_edit` once. Do not search the web.\n\n" . $prompt;
    if (function_exists('set_time_limit')) {
        set_time_limit(120);
    }

    $apiBase = gos_lyre_me_api_base();
    $imageJson = json_encode([
        'prompt' => $prompt,
        'images' => $images,
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    $best = gos_lyre_me_http('POST', $apiBase . '/imagine/image', is_string($imageJson) ? $imageJson : '{}', 20);
    $url = null;
    if ($best['status'] !== 404 && $best['status'] !== 0 && $best['status'] !== 405) {
        $decoded = json_decode($best['body'], true);
        if (is_array($decoded)) {
            $url = gos_lyre_harvest_from_json($decoded);
        }
        if ($url === null) {
            $url = gos_lyre_harvest_image_url($best['body']);
        }
    }
    if ($url === null) {
        $chatPayload = [
            'message' => $message,
            'images' => $images,
            'stream' => true,
        ];
        $chatJson = json_encode($chatPayload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        $chat = gos_lyre_me_http('POST', $apiBase . '/chat', is_string($chatJson) ? $chatJson : '{}', 120);
        if ($chat['status'] < 200 || $chat['status'] >= 300 || $chat['body'] === '') {
            gos_lyre_imagine_unavailable();
        }
        $url = gos_lyre_harvest_sse($chat['body']);
    }
    if ($url === null) {
        gos_lyre_imagine_unavailable();
    }
    $bytes = gos_lyre_download_url($url);
    if ($bytes === null || $bytes === '') {
        gos_lyre_imagine_unavailable();
    }
    $key = 'boards/' . $boardId . '/frames/' . $frameId . '.jpg';
    $allow = gos_lyre_storage_key($key);
    if ($allow === null) {
        gos_api_json(['ok' => false, 'error' => 'invalid_key'], 400);
    }
    if (!gos_lyre_me_put_bytes($allow, $bytes, 'image/jpeg')) {
        gos_lyre_imagine_unavailable();
    }
    gos_api_json([
        'ok' => true,
        'path' => $allow,
        'url' => gos_lyre_me_object_url($allow),
    ]);
}

function gos_lyre_imagine_video(array $access, array $body): never
{
    if (gos_lyre_me_api_key() === '' || gos_lyre_me_api_base() === '') {
        gos_lyre_imagine_unavailable();
    }
    gos_lyre_imagine_project($access, $body);
    $imageKey = gos_lyre_storage_key((string) ($body['image_key'] ?? ''));
    if ($imageKey === null) {
        gos_api_json(['ok' => false, 'error' => 'image_key_required'], 400);
    }
    if (!gos_lyre_me_object_exists($imageKey)) {
        gos_lyre_imagine_unavailable();
    }
    $refKeys = [];
    $rawRefs = $body['ref_keys'] ?? [];
    if (is_array($rawRefs)) {
        foreach ($rawRefs as $rk) {
            $key = gos_lyre_storage_key((string) $rk);
            if ($key === null) {
                gos_lyre_imagine_unavailable();
            }
            if (!gos_lyre_me_object_exists($key)) {
                gos_lyre_imagine_unavailable();
            }
            $refKeys[] = $key;
            if (count($refKeys) >= 3) {
                break;
            }
        }
    }
    $voices = [];
    $rawVoices = $body['voice_ids'] ?? [];
    if (is_array($rawVoices)) {
        foreach ($rawVoices as $v) {
            $id = strtolower(trim((string) $v));
            if ($id === '') {
                continue;
            }
            $voices[] = ['voice_id' => $id];
            if (count($voices) >= 3) {
                break;
            }
        }
    }
    $payload = [
        'prompt' => (string) ($body['prompt'] ?? ''),
        'duration' => (int) ($body['duration'] ?? 6),
        'aspect_ratio' => (string) ($body['aspect'] ?? $body['aspect_ratio'] ?? '16:9'),
        'resolution' => (string) ($body['resolution'] ?? '720p'),
        'image' => ['url' => gos_lyre_me_object_url($imageKey)],
    ];
    if ($refKeys !== []) {
        $payload['reference_images'] = array_map(
            static fn(string $k): array => ['url' => gos_lyre_me_object_url($k)],
            $refKeys
        );
    }
    if ($voices !== []) {
        $payload['reference_audios'] = $voices;
    }
    $json = json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    $res = gos_lyre_me_http(
        'POST',
        gos_lyre_me_api_base() . '/imagine/video',
        is_string($json) ? $json : '{}',
        30
    );
    if ($res['status'] === 404 || $res['status'] === 0 || $res['status'] === 405) {
        gos_lyre_imagine_unavailable();
    }
    $decoded = json_decode($res['body'], true);
    $requestId = '';
    if (is_array($decoded)) {
        $requestId = trim((string) ($decoded['request_id'] ?? $decoded['id'] ?? ''));
    }
    if ($res['status'] < 200 || $res['status'] >= 300 || $requestId === '') {
        gos_lyre_imagine_unavailable();
    }
    gos_api_json(['ok' => true, 'request_id' => $requestId]);
}

function gos_lyre_imagine_edit(array $access, array $body): never
{
    if (gos_lyre_me_api_key() === '' || gos_lyre_me_api_base() === '') {
        gos_lyre_imagine_unavailable();
    }
    gos_lyre_imagine_project($access, $body);
    $videoKey = gos_lyre_storage_key((string) ($body['video_key'] ?? ''));
    if ($videoKey === null) {
        gos_api_json(['ok' => false, 'error' => 'video_key_required'], 400);
    }
    if (!gos_lyre_me_object_exists($videoKey)) {
        gos_lyre_imagine_unavailable();
    }
    $imageKey = null;
    $rawImage = trim((string) ($body['image_key'] ?? ''));
    if ($rawImage !== '') {
        $imageKey = gos_lyre_storage_key($rawImage);
        if ($imageKey === null || !gos_lyre_me_object_exists($imageKey)) {
            gos_lyre_imagine_unavailable();
        }
    }
    $refKeys = [];
    $rawRefs = $body['ref_keys'] ?? [];
    if (is_array($rawRefs)) {
        foreach ($rawRefs as $rk) {
            $key = gos_lyre_storage_key((string) $rk);
            if ($key === null || !gos_lyre_me_object_exists($key)) {
                gos_lyre_imagine_unavailable();
            }
            $refKeys[] = $key;
            if (count($refKeys) >= 3) {
                break;
            }
        }
    }
    $voices = [];
    $rawVoices = $body['voice_ids'] ?? [];
    if (is_array($rawVoices)) {
        foreach ($rawVoices as $v) {
            $vid = strtolower(trim((string) $v));
            if ($vid === '') {
                continue;
            }
            $voices[] = ['voice_id' => $vid];
            if (count($voices) >= 3) {
                break;
            }
        }
    }
    $payload = [
        'prompt' => (string) ($body['prompt'] ?? ''),
        'duration' => (int) ($body['duration'] ?? 6),
        'aspect_ratio' => (string) ($body['aspect'] ?? $body['aspect_ratio'] ?? '16:9'),
        'resolution' => (string) ($body['resolution'] ?? '720p'),
        'video' => ['url' => gos_lyre_me_object_url($videoKey)],
    ];
    if ($imageKey !== null) {
        $payload['image'] = ['url' => gos_lyre_me_object_url($imageKey)];
    }
    if ($refKeys !== []) {
        $payload['reference_images'] = array_map(
            static fn(string $k): array => ['url' => gos_lyre_me_object_url($k)],
            $refKeys
        );
    }
    if ($voices !== []) {
        $payload['reference_audios'] = $voices;
    }
    $json = json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    $apiBase = gos_lyre_me_api_base();
    $res = gos_lyre_me_http('POST', $apiBase . '/imagine/edit', is_string($json) ? $json : '{}', 30);
    if ($res['status'] === 404 || $res['status'] === 405 || $res['status'] === 0) {
        $res = gos_lyre_me_http('POST', $apiBase . '/imagine/video/edit', is_string($json) ? $json : '{}', 30);
    }
    if ($res['status'] === 404 || $res['status'] === 0 || $res['status'] === 405) {
        gos_lyre_imagine_unavailable();
    }
    $decoded = json_decode($res['body'], true);
    $requestId = '';
    if (is_array($decoded)) {
        $requestId = trim((string) ($decoded['request_id'] ?? $decoded['id'] ?? ''));
    }
    if ($res['status'] < 200 || $res['status'] >= 300 || $requestId === '') {
        gos_lyre_imagine_unavailable();
    }
    gos_api_json(['ok' => true, 'request_id' => $requestId]);
}

function gos_lyre_imagine_status(array $access, string $requestId): never
{
    gos_lyre_user_id($access);
    $requestId = trim($requestId);
    if (preg_match('/^[A-Za-z0-9._:-]{8,128}$/', $requestId) !== 1) {
        gos_api_json(['ok' => false, 'error' => 'request_id_required'], 400);
    }
    if (gos_lyre_me_api_key() === '' || gos_lyre_me_api_base() === '') {
        gos_lyre_imagine_unavailable();
    }
    $res = gos_lyre_me_http('GET', gos_lyre_me_api_base() . '/imagine/video/' . rawurlencode($requestId), null, 30);
    if ($res['status'] === 404 || $res['status'] === 0) {
        gos_lyre_imagine_unavailable();
    }
    $decoded = json_decode($res['body'], true);
    if (!is_array($decoded)) {
        gos_lyre_imagine_unavailable();
    }
    $status = strtolower((string) ($decoded['status'] ?? 'pending'));
    $out = [
        'ok' => true,
        'status' => $status !== '' ? $status : 'pending',
        'request_id' => $requestId,
    ];
    if (isset($decoded['video']) && is_array($decoded['video'])) {
        $out['video'] = $decoded['video'];
    }
    if (isset($decoded['url'])) {
        $out['url'] = $decoded['url'];
    }
    if (isset($decoded['path'])) {
        $out['path'] = $decoded['path'];
    }
    gos_api_json($out);
}

$httpMethod = strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'));
$qsAction = strtolower(trim((string) ($_GET['action'] ?? '')));

if ($httpMethod === 'GET' && $qsAction === 'storage_get') {
    gos_lyre_auth();
    gos_lyre_storage_get((string) ($_GET['key'] ?? ''));
}
if ($httpMethod === 'POST' && $qsAction === 'storage_put') {
    gos_lyre_auth();
    gos_lyre_storage_put((string) ($_GET['key'] ?? ''));
}
if ($httpMethod === 'GET' && $qsAction === 'imagine_status') {
    $access = gos_lyre_auth();
    gos_lyre_imagine_status($access, (string) ($_GET['request_id'] ?? ''));
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
if ($action === 'imagine_still') {
    gos_lyre_imagine_still($access, $body);
}
if ($action === 'imagine_video') {
    gos_lyre_imagine_video($access, $body);
}
if ($action === 'imagine_edit') {
    gos_lyre_imagine_edit($access, $body);
}
gos_api_json(['ok' => false, 'error' => 'unknown_action'], 400);
