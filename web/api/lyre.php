<?php

declare(strict_types=1);

/**
 * Device-auth LYRE proxy: MySQL lyre_projects, Postgres BoardData, grokme storage GET.
 */

require_once __DIR__ . '/_common.php';

function gos_lyre_pdo(): PDO
{
    static $pdo = null;
    if (gos_pdo_alive($pdo)) {
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
        if ($bytes <= 0) {
            gos_lyre_json_error('not_found', 404);
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

$httpMethod = strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'));
$qsAction = strtolower(trim((string) ($_GET['action'] ?? '')));

if ($httpMethod === 'GET' && $qsAction === 'storage_get') {
    gos_lyre_auth();
    gos_lyre_storage_get((string) ($_GET['key'] ?? ''));
}
if ($httpMethod === 'POST' && $qsAction === 'storage_put') {
    gos_lyre_auth();
    gos_api_json(['ok' => false, 'error' => 'not_implemented'], 501);
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
gos_api_json(['ok' => false, 'error' => 'unknown_action'], 400);
