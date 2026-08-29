<?php

declare(strict_types=1);

/**
 * User-scoped LYRE MCP connector (Streamable HTTP).
 *
 * URL: {site}/mcp/lyre_mcp_<48 hex>
 * Tokens are hashed on disk. Never log REQUEST_URI or the plaintext token.
 */

const GOS_LYRE_MCP_PROTOCOL_VERSION = '2024-11-05';
const GOS_LYRE_MCP_TOKEN_RE = '/^lyre_mcp_[a-f0-9]{48}$/i';

/** @var list<string> */
const GOS_LYRE_MCP_TOOL_ALLOWLIST = [
    'lyre_instructions',
    'lyre_projects',
    'lyre_create',
    'lyre_open',
    'lyre_snapshot',
    'lyre_folder',
    'lyre_generate_still',
    'lyre_edit_still',
    'lyre_generate_video',
    'lyre_edit_video',
    'lyre_imagine_status',
    'lyre_scene',
    'lyre_place',
    'lyre_trim',
    'lyre_move',
    'lyre_delete',
    'lyre_activity',
    'lyre_stitch',
    'lyre_pop',
];

/** @var list<string> */
const GOS_LYRE_MCP_TOOL_DENYLIST = [
    'save_board',
    'storage_put',
];

function gos_lyre_mcp_root(): string
{
    $override = getenv('GOS_LYRE_MCP_DIR');
    if (is_string($override) && $override !== '') {
        return rtrim($override, '/');
    }

    return gos_root() . '/storage/lyre-mcp';
}

function gos_lyre_mcp_chgrp_www(string $path): void
{
    if (!function_exists('posix_getgrnam')) {
        return;
    }
    $gr = posix_getgrnam('www-data');
    if (is_array($gr) && isset($gr['gid'])) {
        @chgrp($path, (int) $gr['gid']);
    }
}

function gos_lyre_mcp_ensure_dir(string $dir): void
{
    if (!is_dir($dir) && !mkdir($dir, 0770, true) && !is_dir($dir)) {
        throw new RuntimeException('lyre_mcp_dir');
    }
    @chmod($dir, 0770);
    gos_lyre_mcp_chgrp_www($dir);
}

function gos_lyre_mcp_write_json(string $path, array $data): void
{
    $dir = dirname($path);
    gos_lyre_mcp_ensure_dir($dir);
    $json = json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    if ($json === false) {
        throw new RuntimeException('lyre_mcp_encode');
    }
    $tmp = $path . '.tmp';
    if (file_put_contents($tmp, $json, LOCK_EX) === false) {
        throw new RuntimeException('lyre_mcp_write');
    }
    @chmod($tmp, 0660);
    gos_lyre_mcp_chgrp_www($tmp);
    if (!rename($tmp, $path)) {
        @unlink($tmp);
        throw new RuntimeException('lyre_mcp_rename');
    }
    @chmod($path, 0660);
    gos_lyre_mcp_chgrp_www($path);
}

/** @return array<string, mixed> */
function gos_lyre_mcp_read_json(string $path): array
{
    if (!is_readable($path)) {
        return [];
    }
    $raw = file_get_contents($path);
    if (!is_string($raw) || $raw === '') {
        return [];
    }
    $data = json_decode($raw, true);

    return is_array($data) ? $data : [];
}

function gos_lyre_mcp_generate_plain_token(): string
{
    return 'lyre_mcp_' . bin2hex(random_bytes(24));
}

function gos_lyre_mcp_hash_token(string $plainToken): string
{
    return hash('sha256', strtolower($plainToken));
}

function gos_lyre_mcp_token_prefix(string $plain): string
{
    return substr($plain, 0, 16) . '…';
}

function gos_lyre_mcp_normalize_token(string $token): ?string
{
    $token = trim($token);
    if (!preg_match(GOS_LYRE_MCP_TOKEN_RE, $token)) {
        return null;
    }

    return strtolower($token);
}

function gos_lyre_mcp_authorization_header(): string
{
    $auth = (string) ($_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '');
    if ($auth === '' && function_exists('getallheaders')) {
        foreach (getallheaders() as $k => $v) {
            if (strtolower((string) $k) === 'authorization') {
                $auth = (string) $v;
                break;
            }
        }
    }

    return trim($auth);
}

function gos_lyre_mcp_bearer_raw(): ?string
{
    $auth = gos_lyre_mcp_authorization_header();
    if (preg_match('/^Bearer\s+(\S+)$/i', $auth, $m)) {
        return trim((string) $m[1]);
    }

    return null;
}

function gos_lyre_mcp_gos_bearer_presented(): bool
{
    $raw = gos_lyre_mcp_bearer_raw();
    if ($raw === null) {
        return false;
    }

    return str_starts_with(strtolower($raw), 'gos_');
}

function gos_lyre_mcp_extract_token_from_request(): ?string
{
    $fromQuery = (string) ($_GET['token'] ?? '');
    if ($fromQuery !== '') {
        $norm = gos_lyre_mcp_normalize_token($fromQuery);
        if ($norm !== null) {
            return $norm;
        }
    }

    $path = (string) (parse_url((string) ($_SERVER['REQUEST_URI'] ?? ''), PHP_URL_PATH) ?? '');
    if (preg_match('#/(?:mcp(?:\.php)?)/(lyre_mcp_[a-f0-9]{48})/?$#i', $path, $m)) {
        $norm = gos_lyre_mcp_normalize_token((string) $m[1]);
        if ($norm !== null) {
            return $norm;
        }
    }

    $info = (string) ($_SERVER['PATH_INFO'] ?? '');
    if ($info !== '' && preg_match('#(lyre_mcp_[a-f0-9]{48})#i', $info, $m)) {
        $norm = gos_lyre_mcp_normalize_token((string) $m[1]);
        if ($norm !== null) {
            return $norm;
        }
    }

    $bearer = gos_lyre_mcp_bearer_raw();
    if ($bearer !== null) {
        return gos_lyre_mcp_normalize_token($bearer);
    }

    return null;
}

function gos_lyre_mcp_users_dir(): string
{
    return gos_lyre_mcp_root() . '/users';
}

function gos_lyre_mcp_tokens_dir(): string
{
    return gos_lyre_mcp_root() . '/tokens';
}

function gos_lyre_mcp_user_path(int $userId): string
{
    return gos_lyre_mcp_users_dir() . '/' . $userId . '.json';
}

function gos_lyre_mcp_token_path(string $hash): string
{
    return gos_lyre_mcp_tokens_dir() . '/' . $hash . '.json';
}

/**
 * @template T
 * @param callable(): T $fn
 * @return T
 */
function gos_lyre_mcp_with_user_lock(int $userId, callable $fn): mixed
{
    gos_lyre_mcp_ensure_dir(gos_lyre_mcp_users_dir());
    $lockPath = gos_lyre_mcp_user_path($userId) . '.lock';
    $fh = fopen($lockPath, 'c+');
    if ($fh === false) {
        throw new RuntimeException('lyre_mcp_lock');
    }
    $ok = flock($fh, LOCK_EX);
    if (!$ok) {
        fclose($fh);
        throw new RuntimeException('lyre_mcp_lock');
    }
    try {
        return $fn();
    } finally {
        flock($fh, LOCK_UN);
        fclose($fh);
    }
}

/** @return array<string, mixed> */
function gos_lyre_mcp_empty_user_state(int $userId): array
{
    return [
        'user_id' => $userId,
        'token_hash' => null,
        'token_prefix' => null,
        'enabled' => false,
        'phone_last_project_id' => null,
        'phone_last_board_id' => null,
        'mcp_open_project_id' => null,
        'mcp_open_board_id' => null,
        'created_at' => null,
        'rotated_at' => null,
        'last_used_at' => null,
        'disabled_at' => null,
    ];
}

/** @return array<string, mixed> */
function gos_lyre_mcp_user_state(int $userId): array
{
    $data = gos_lyre_mcp_read_json(gos_lyre_mcp_user_path($userId));
    if ($data === []) {
        return gos_lyre_mcp_empty_user_state($userId);
    }

    return $data + gos_lyre_mcp_empty_user_state($userId);
}

/**
 * @param array<string, mixed> $row
 */
function gos_lyre_mcp_persist_open(int $userId, array $row, string $slot): void
{
    $projectId = (string) ($row['id'] ?? '');
    $boardId = (string) ($row['board_id'] ?? '');
    gos_lyre_mcp_with_user_lock($userId, function () use ($userId, $row, $slot, $projectId, $boardId): void {
        $state = gos_lyre_mcp_user_state($userId);
        $state['user_id'] = $userId;
        if ($slot === 'mcp') {
            if (gos_lyre_is_odysseus_project($row)) {
                return;
            }
            $state['mcp_open_project_id'] = $projectId !== '' ? $projectId : null;
            $state['mcp_open_board_id'] = $boardId !== '' ? $boardId : null;
        } else {
            $state['phone_last_project_id'] = $projectId !== '' ? $projectId : null;
            $state['phone_last_board_id'] = $boardId !== '' ? $boardId : null;
        }
        gos_lyre_mcp_write_json(gos_lyre_mcp_user_path($userId), $state);
    });
}

/** @return array{plain_token: ?string} */
function gos_lyre_mcp_ensure_for_user(int $userId, bool $rotate): array
{
    return gos_lyre_mcp_with_user_lock($userId, function () use ($userId, $rotate): array {
        $state = gos_lyre_mcp_user_state($userId);
        $has = is_string($state['token_hash'] ?? null) && $state['token_hash'] !== '';
        $plain = null;
        if (!$has || $rotate) {
            $plain = gos_lyre_mcp_generate_plain_token();
            $hash = gos_lyre_mcp_hash_token($plain);
            $now = date('c');
            $oldHash = is_string($state['token_hash'] ?? null) ? (string) $state['token_hash'] : '';
            if ($oldHash !== '' && $oldHash !== $hash) {
                $oldPath = gos_lyre_mcp_token_path($oldHash);
                if (is_file($oldPath)) {
                    @unlink($oldPath);
                }
            }
            $state['user_id'] = $userId;
            $state['token_hash'] = $hash;
            $state['token_prefix'] = gos_lyre_mcp_token_prefix($plain);
            $state['enabled'] = true;
            $state['disabled_at'] = null;
            $state['created_at'] = is_string($state['created_at'] ?? null) && $state['created_at'] !== ''
                ? $state['created_at']
                : $now;
            $state['rotated_at'] = ($rotate && $has) ? $now : ($state['rotated_at'] ?? null);
            gos_lyre_mcp_write_json(gos_lyre_mcp_user_path($userId), $state);
            gos_lyre_mcp_write_json(gos_lyre_mcp_token_path($hash), [
                'user_id' => $userId,
                'enabled' => true,
            ]);
        }

        return ['plain_token' => $plain];
    });
}

function gos_lyre_mcp_disable_for_user(int $userId): void
{
    gos_lyre_mcp_with_user_lock($userId, function () use ($userId): void {
        $state = gos_lyre_mcp_user_state($userId);
        if (!is_string($state['token_hash'] ?? null) || $state['token_hash'] === '') {
            return;
        }
        $state['enabled'] = false;
        $state['disabled_at'] = date('c');
        gos_lyre_mcp_write_json(gos_lyre_mcp_user_path($userId), $state);
        $tokPath = gos_lyre_mcp_token_path((string) $state['token_hash']);
        $tok = gos_lyre_mcp_read_json($tokPath);
        if ($tok !== []) {
            $tok['enabled'] = false;
            $tok['user_id'] = $userId;
            gos_lyre_mcp_write_json($tokPath, $tok);
        }
    });
}

/** @return array{plain_token: ?string} */
function gos_lyre_mcp_enable_for_user(int $userId): array
{
    gos_lyre_mcp_with_user_lock($userId, function () use ($userId): void {
        $state = gos_lyre_mcp_user_state($userId);
        $has = is_string($state['token_hash'] ?? null) && $state['token_hash'] !== '';
        if (!$has) {
            return;
        }
        $state['enabled'] = true;
        $state['disabled_at'] = null;
        gos_lyre_mcp_write_json(gos_lyre_mcp_user_path($userId), $state);
        $tokPath = gos_lyre_mcp_token_path((string) $state['token_hash']);
        gos_lyre_mcp_write_json($tokPath, [
            'user_id' => $userId,
            'enabled' => true,
        ]);
    });
    $state = gos_lyre_mcp_user_state($userId);
    if (!is_string($state['token_hash'] ?? null) || $state['token_hash'] === '') {
        return gos_lyre_mcp_ensure_for_user($userId, false);
    }

    return ['plain_token' => null];
}

function gos_lyre_mcp_touch_last_used(int $userId): void
{
    try {
        gos_lyre_mcp_with_user_lock($userId, function () use ($userId): void {
            $state = gos_lyre_mcp_user_state($userId);
            if ($state === [] || empty($state['token_hash'])) {
                return;
            }
            $state['last_used_at'] = date('c');
            gos_lyre_mcp_write_json(gos_lyre_mcp_user_path($userId), $state);
        });
    } catch (Throwable) {
        // never fail the RPC because of bookkeeping
    }
}

function gos_lyre_mcp_connector_url(?string $plainToken = null): string
{
    $origin = rtrim(gos_site_url(), '/');
    if ($plainToken === null || $plainToken === '') {
        return $origin . '/mcp';
    }

    return $origin . '/mcp/' . $plainToken;
}

/** @return array<string, mixed> */
function gos_lyre_mcp_status_payload(int $userId, ?string $plainJustIssued = null): array
{
    $state = gos_lyre_mcp_user_state($userId);
    $has = is_string($state['token_hash'] ?? null) && $state['token_hash'] !== '';
    $enabled = $has && !empty($state['enabled']);
    $link = null;
    if ($plainJustIssued !== null && $plainJustIssued !== '') {
        $link = gos_lyre_mcp_connector_url($plainJustIssued);
    }

    return [
        'ok' => true,
        'has_connector' => $has,
        'enabled' => $enabled,
        'connector_link' => $link,
        'connector_link_hint' => '/mcp/lyre_mcp_…',
        'token_prefix' => $state['token_prefix'] ?? null,
        'created_at' => $state['created_at'] ?? null,
        'rotated_at' => $state['rotated_at'] ?? null,
        'last_used_at' => $state['last_used_at'] ?? null,
        'phone_last_project_id' => $state['phone_last_project_id'] ?? null,
        'mcp_open_project_id' => $state['mcp_open_project_id'] ?? null,
        'mcp_open_board_id' => $state['mcp_open_board_id'] ?? null,
    ];
}

/** @return array<string, mixed>|null */
function gos_lyre_mcp_user_lookup(int $id): ?array
{
    $raw = getenv('GOS_LYRE_MCP_TEST_USER_JSON');
    if (is_string($raw) && $raw !== '') {
        $u = json_decode($raw, true);
        if (!is_array($u)) {
            return null;
        }
        if ((int) ($u['id'] ?? 0) !== $id) {
            return null;
        }

        return $u;
    }

    return gos_user_by_id($id);
}

function gos_lyre_mcp_instructions(): string
{
    return <<<'INST'
LYRE Director MCP — named operations on this user's boards. Never dump or PATCH board JSON. Never call save_board.

Pipeline:
1. lyre_create a new project; write a short bible into brainstorm + scenes (lyre_scene). Odysseus is listed; do not open it unless the operator names it — and even then MCP will refuse mutations.
2. Generate character sheets (angles, attire) into Characters/{name} and Characters/{name}/Attire/… (lyre_folder + lyre_generate_still). Use those stills as refs. Always pass board_id.
3. Generate environments into Environments/{place}.
4. Per scene: compose a still from character + environment refs, lyre_edit_still until it holds, then lyre_generate_video from that still. Poll lyre_imagine_status until src exists, then attach (POST / attach=true on the tool — GET status does not attach).
5. lyre_place clips on the leftover track (after the movie prefix). lyre_stitch in order (server drops the last encoded frame so the cut does not flash). lyre_pop is the only un-stitch.
6. Tell the operator to keep the phone on this project; the editor polls updated_at.

Hard rules:
- Never invent board JSON. Never ask for save_board.
- Always pass board_id (or project_id) on mutating tools. Concurrent bots cannot share a default.
- A video clip is a scene frame + leftover-track clip (linkedFrameId). Director ops dual-write. You cannot attach “only on the track”.
- Stitched members are locked. Do not trim/move/delete/edit them. lyre_pop only.
- Keep origs; stitch writes boards/{id}/movie.mp4 and snapshots movie.g{n}.mp4.
- Max 4 image refs, 3 voices (eve,ara,leo,rex,sal,carina,helix,orion,luna,iris,sirius,atlas).
- Prefer lyre_snapshot over guessing ids.
- After lyre_create, pass the returned board_id/project_id on every mutating call. Do not use Odysseus.
- lyre_open is optional convenience for reads (lyre_snapshot / lyre_activity / lyre_projects).
INST;
}

function gos_lyre_mcp_encode_payload(mixed $data): string
{
    if (is_string($data)) {
        $json = $data;
    } else {
        $json = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
        if (!is_string($json)) {
            return '(unencodable)';
        }
    }
    $max = 400000;
    if (strlen($json) > $max) {
        return substr($json, 0, $max) . "\n… truncated (" . strlen($json) . ' bytes)';
    }

    return $json;
}

/** @return array<string, mixed> */
function gos_lyre_mcp_text_result(string $text, bool $isError = false): array
{
    return [
        'content' => [
            ['type' => 'text', 'text' => $text],
        ],
        'isError' => $isError,
    ];
}

function gos_lyre_mcp_client_wants_sse(): bool
{
    $accept = (string) ($_SERVER['HTTP_ACCEPT'] ?? '');

    return stripos($accept, 'text/event-stream') !== false;
}

/** @param array<string, mixed> $params */
function gos_lyre_mcp_negotiate_protocol_version(array $params): string
{
    $requested = (string) ($params['protocolVersion'] ?? '');
    $supported = ['2024-11-05', '2025-03-26', '2025-06-18', '2025-11-25'];
    if (in_array($requested, $supported, true)) {
        return $requested;
    }

    return GOS_LYRE_MCP_PROTOCOL_VERSION;
}

/** @param array<string, mixed> $payload */
function gos_lyre_mcp_emit_jsonrpc(array $payload): never
{
    $json = json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if ($json === false) {
        $json = '{"jsonrpc":"2.0","error":{"code":-32603,"message":"Internal error"},"id":null}';
    }
    if (gos_lyre_mcp_client_wants_sse()) {
        header('Content-Type: text/event-stream; charset=utf-8');
        header('Cache-Control: no-cache, no-transform');
        header('X-Accel-Buffering: no');
        echo "event: message\n";
        echo 'data: ' . $json . "\n\n";
        exit;
    }
    header('Content-Type: application/json; charset=utf-8');
    echo $json;
    exit;
}

function gos_lyre_mcp_jsonrpc_success(mixed $id, mixed $result): never
{
    gos_lyre_mcp_emit_jsonrpc([
        'jsonrpc' => '2.0',
        'id' => $id,
        'result' => $result,
    ]);
}

function gos_lyre_mcp_jsonrpc_error(mixed $id, int $code, string $message): never
{
    if ($code === -32700) {
        http_response_code(400);
    }
    $payload = [
        'jsonrpc' => '2.0',
        'error' => [
            'code' => $code,
            'message' => $message,
        ],
    ];
    if ($id !== null) {
        $payload['id'] = $id;
    }
    gos_lyre_mcp_emit_jsonrpc($payload);
}

/** @return list<array<string, mixed>> */
function gos_lyre_mcp_tool_definitions(): array
{
    return [
        [
            'name' => 'lyre_instructions',
            'description' => 'Cookbook for the LYRE director pipeline. Call first. Odysseus is listed but must not be the bot default.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => new stdClass(),
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_projects',
            'description' => 'List this user\'s LYRE projects (updated_at DESC). Odysseus may appear. Read-only.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'include_odysseus' => [
                        'type' => 'boolean',
                        'description' => 'Include the shared Odysseus row. Default true.',
                    ],
                ],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_create',
            'description' => 'Create a new lyre_phone_* project with an empty board. Pass returned board_id on later mutating tools. Does not open the board.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'name' => [
                        'type' => 'string',
                        'description' => 'Project name (also the board title).',
                    ],
                    'brainstorm' => [
                        'type' => 'string',
                        'description' => 'Optional bible / notes stored on the board.',
                    ],
                ],
                'required' => ['name'],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_open',
            'description' => 'Set the MCP default board for reads only. Mutating tools still require board_id. Odysseus is refused and not persisted.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => [
                        'type' => 'string',
                        'description' => '32-hex lyre_projects.id',
                    ],
                    'board_id' => [
                        'type' => 'string',
                        'description' => 'grokme boards.id (lyre_phone_…)',
                    ],
                ],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_snapshot',
            'description' => 'Compact board snapshot (not raw payload). Pass board_id or project_id. Odysseus allowed when explicit.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                ],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_folder',
            'description' => 'Ensure a library folder path (flat name string). Requires board_id or project_id. Odysseus refused.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'path' => ['type' => 'string', 'description' => 'Folder name, e.g. Characters/Penelope/Attire/red'],
                    'folder_id' => ['type' => 'string'],
                ],
                'required' => ['path'],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_generate_still',
            'description' => 'Generate a still via Imagine into boards/{id}/stills (Odysseus refused). Optional folder_path + frame_id.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'prompt' => ['type' => 'string'],
                    'refs' => ['type' => 'array', 'items' => ['type' => 'string'], 'description' => 'Library image ids or storage keys (max 4)'],
                    'aspect_ratio' => ['type' => 'string'],
                    'folder_id' => ['type' => 'string'],
                    'folder_path' => ['type' => 'string'],
                    'frame_id' => ['type' => 'string'],
                    'caption' => ['type' => 'string'],
                ],
                'required' => ['prompt'],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_edit_still',
            'description' => 'Edit a still via Imagine. Requires frame_id or image_id. Stitched picture is movie_locked.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'prompt' => ['type' => 'string'],
                    'refs' => ['type' => 'array', 'items' => ['type' => 'string']],
                    'aspect_ratio' => ['type' => 'string'],
                    'folder_id' => ['type' => 'string'],
                    'folder_path' => ['type' => 'string'],
                    'frame_id' => ['type' => 'string'],
                    'image_id' => ['type' => 'string'],
                    'caption' => ['type' => 'string'],
                ],
                'required' => ['prompt'],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_generate_video',
            'description' => 'Start Imagine video. Writes boards/{id}/videos on done. GET status does not attach.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'prompt' => ['type' => 'string'],
                    'frame_id' => ['type' => 'string'],
                    'refs' => ['type' => 'array', 'items' => ['type' => 'string']],
                    'voices' => ['type' => 'array', 'items' => ['type' => 'string']],
                    'duration' => ['type' => 'number'],
                    'aspect_ratio' => ['type' => 'string'],
                    'resolution' => ['type' => 'string'],
                    'clip_id' => ['type' => 'string'],
                    'attach' => ['type' => 'boolean'],
                ],
                'required' => ['prompt'],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_edit_video',
            'description' => 'Edit an existing clip via Imagine (40MB data-URI cap). Requires clip_id or video_key.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'prompt' => ['type' => 'string'],
                    'frame_id' => ['type' => 'string'],
                    'clip_id' => ['type' => 'string'],
                    'video_key' => ['type' => 'string'],
                    'refs' => ['type' => 'array', 'items' => ['type' => 'string']],
                    'voices' => ['type' => 'array', 'items' => ['type' => 'string']],
                    'duration' => ['type' => 'number'],
                    'aspect_ratio' => ['type' => 'string'],
                    'resolution' => ['type' => 'string'],
                    'attach' => ['type' => 'boolean'],
                ],
                'required' => ['prompt'],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_imagine_status',
            'description' => 'Poll Imagine or cut job. attach=true requires board_id and CAS-attaches a done video. GET status never attaches.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'request_id' => ['type' => 'string'],
                    'attach' => ['type' => 'boolean'],
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'frame_id' => ['type' => 'string'],
                ],
                'required' => ['request_id'],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_scene',
            'description' => 'Create or update a scene (does not rewrite frames). Requires board_id or project_id.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'scene_id' => ['type' => 'string'],
                    'title' => ['type' => 'string'],
                    'logline' => ['type' => 'string'],
                    'notes' => ['type' => 'string'],
                    'dialogue' => ['type' => 'string'],
                    'book' => ['type' => 'string'],
                ],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_place',
            'description' => 'Place still, video, or audio. Video with poster dual-writes frame+clip. Requires board_id or project_id.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'kind' => ['type' => 'string', 'description' => 'still|video|audio'],
                    'src' => ['type' => 'string'],
                    'name' => ['type' => 'string'],
                    'at_sec' => ['type' => 'number'],
                    'scene_id' => ['type' => 'string'],
                    'frame_id' => ['type' => 'string'],
                    'poster_src' => ['type' => 'string'],
                    'duration_sec' => ['type' => 'number'],
                    'layer_id' => ['type' => 'string'],
                ],
                'required' => ['kind', 'src'],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_trim',
            'description' => 'JSON-only timeline trim. Stitched members and lc_movie are movie_locked. Requires board_id or project_id.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'clip_id' => ['type' => 'string'],
                    'frame_id' => ['type' => 'string'],
                    'start_sec' => ['type' => 'number'],
                    'end_sec' => ['type' => 'number'],
                    'commit_trim' => [
                        'type' => 'boolean',
                        'description' => 'Rewrite the clip file with ffmpeg (default true for bots). Keep origSrc.',
                    ],
                ],
                'required' => ['clip_id'],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_move',
            'description' => 'Move a leftover clip. Stitched members are movie_locked. Requires board_id or project_id.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'clip_id' => ['type' => 'string'],
                    'start_sec' => ['type' => 'number'],
                    'at_sec' => ['type' => 'number'],
                ],
                'required' => ['clip_id'],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_delete',
            'description' => 'Delete a leftover clip. Stitched members require pop. Requires board_id or project_id.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'clip_id' => ['type' => 'string'],
                    'frame_id' => ['type' => 'string'],
                ],
                'required' => ['clip_id'],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_activity',
            'description' => 'List activity newest first. If text is set, append a line (mutation; Odysseus refused).',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'limit' => ['type' => 'integer'],
                    'before_ts' => ['type' => 'integer'],
                    'text' => ['type' => 'string'],
                    'type' => ['type' => 'string'],
                ],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_stitch',
            'description' => 'Stitch the next leftover clip onto the movie (drop last encoded frame). Requires board_id or project_id. Returns pending request_id; poll lyre_imagine_status. Odysseus refused.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                    'clip_id' => [
                        'type' => 'string',
                        'description' => 'Must be nextStitchTarget; omit to use the next leftover.',
                    ],
                ],
                'additionalProperties' => false,
            ],
        ],
        [
            'name' => 'lyre_pop',
            'description' => 'Un-stitch the last movie part (restore movie.g{n} or rebuild). Clips stay on the leftover track. Requires board_id or project_id. Odysseus refused.',
            'inputSchema' => [
                'type' => 'object',
                'properties' => [
                    'project_id' => ['type' => 'string'],
                    'board_id' => ['type' => 'string'],
                ],
                'additionalProperties' => false,
            ],
        ],
    ];
}

/**
 * @param array<string, mixed> $args
 * @param array<string, mixed> $access
 * @return array<string, mixed>
 */
function gos_lyre_mcp_dispatch_tool(string $name, array $args, array $access): array
{
    unset($args['user_id'], $args['auth'], $args['device']);
    if (in_array($name, GOS_LYRE_MCP_TOOL_DENYLIST, true) || $name === 'save_board') {
        return gos_lyre_mcp_text_result(
            gos_lyre_mcp_encode_payload(['ok' => false, 'error' => 'unknown_tool']),
            true
        );
    }
    if (!in_array($name, GOS_LYRE_MCP_TOOL_ALLOWLIST, true)) {
        return gos_lyre_mcp_text_result(
            gos_lyre_mcp_encode_payload(['ok' => false, 'error' => 'unknown_tool']),
            true
        );
    }
    switch ($name) {
        case 'lyre_instructions':
            return gos_lyre_mcp_text_result(gos_lyre_mcp_instructions());
        case 'lyre_projects':
            $out = gos_lyre_list_projects($access);
            $include = array_key_exists('include_odysseus', $args)
                ? filter_var($args['include_odysseus'], FILTER_VALIDATE_BOOLEAN)
                : true;
            if (!$include && isset($out['projects']) && is_array($out['projects'])) {
                $out['projects'] = array_values(array_filter(
                    $out['projects'],
                    static fn ($p) => is_array($p) && empty($p['is_odysseus'])
                ));
            }

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_create':
            $out = gos_lyre_create_project($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_open':
            $out = gos_lyre_open_project($access, $args, 'mcp');

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_snapshot':
            $out = gos_lyre_director_snapshot($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_folder':
            $out = gos_lyre_director_folder($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_generate_still':
            $out = gos_lyre_director_generate_still($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_edit_still':
            $out = gos_lyre_director_edit_still($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_generate_video':
            $out = gos_lyre_director_generate_video($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_edit_video':
            $out = gos_lyre_director_edit_video($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_imagine_status':
            $rid = trim((string) ($args['request_id'] ?? ''));
            $job = gos_lyre_job_read($rid);
            $kind = is_array($job) ? gos_lyre_job_kind($job) : '';
            $attach = !empty($args['attach']) && !in_array($kind, ['stitch', 'trim', 'pop'], true);
            if ($attach) {
                $out = gos_lyre_director_imagine_status($access, $args);

                return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
            }
            $out = gos_lyre_imagine_status_result($rid);
            $body = $out['body'];
            $isError = empty($body['ok']);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($body), $isError);
        case 'lyre_scene':
            $out = gos_lyre_director_scene($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_place':
            $out = gos_lyre_director_place($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_trim':
            $out = gos_lyre_director_trim($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_move':
            $out = gos_lyre_director_move($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_delete':
            $out = gos_lyre_director_delete($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_activity':
            $text = trim((string) ($args['text'] ?? ''));
            if ($text !== '') {
                $args['summary'] = $text;
                $args['actor'] = 'bot';
                $out = gos_lyre_director_activity_append($access, $args);
            } else {
                $out = gos_lyre_director_activity($access, $args);
            }

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_stitch':
            $out = gos_lyre_director_stitch($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        case 'lyre_pop':
            $out = gos_lyre_director_pop($access, $args);

            return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($out));
        default:
            return gos_lyre_mcp_text_result(
                gos_lyre_mcp_encode_payload(['ok' => false, 'error' => 'unknown_tool']),
                true
            );
    }
}

/**
 * @param array<string, mixed> $args
 * @param array<string, mixed> $access
 * @return array<string, mixed>
 */
function gos_lyre_mcp_run_tool(string $name, array $args, array $access): array
{
    try {
        return gos_lyre_mcp_dispatch_tool($name, $args, $access);
    } catch (GosLyreException $e) {
        return gos_lyre_mcp_text_result(gos_lyre_mcp_encode_payload($e->toHttpBody()), true);
    } catch (Throwable $e) {
        $code = 'internal';
        $msg = $e->getMessage();
        if ($e instanceof PDOException || str_contains($msg, 'lyre_pg') || str_contains($msg, 'pgsql')) {
            $code = 'lyre_pg_unavailable';
        }

        return gos_lyre_mcp_text_result(
            gos_lyre_mcp_encode_payload(['ok' => false, 'error' => $code]),
            true
        );
    }
}

/**
 * @param array<string, mixed> $msg
 * @param array<string, mixed> $access
 * @return array<string, mixed>
 */
function gos_lyre_mcp_dispatch_message(array $msg, array $access): array
{
    $method = (string) ($msg['method'] ?? '');
    $id = $msg['id'] ?? null;
    $params = is_array($msg['params'] ?? null) ? $msg['params'] : [];

    if ($method === 'initialize') {
        return [
            'jsonrpc' => '2.0',
            'id' => $id,
            'result' => [
                'protocolVersion' => gos_lyre_mcp_negotiate_protocol_version($params),
                'capabilities' => [
                    'tools' => ['listChanged' => true],
                ],
                'serverInfo' => [
                    'name' => 'lyre',
                    'version' => '1.0.0',
                    'title' => 'LYRE Director',
                ],
                'instructions' => gos_lyre_mcp_instructions(),
            ],
        ];
    }
    if ($method === 'tools/list') {
        return [
            'jsonrpc' => '2.0',
            'id' => $id,
            'result' => [
                'tools' => gos_lyre_mcp_tool_definitions(),
            ],
        ];
    }
    if ($method === 'tools/call') {
        $toolName = (string) ($params['name'] ?? '');
        $toolArgs = is_array($params['arguments'] ?? null) ? $params['arguments'] : [];
        if ($toolName === '') {
            return [
                'jsonrpc' => '2.0',
                'id' => $id,
                'error' => ['code' => -32602, 'message' => 'Missing tool name'],
            ];
        }
        $result = gos_lyre_mcp_run_tool($toolName, $toolArgs, $access);

        return [
            'jsonrpc' => '2.0',
            'id' => $id,
            'result' => $result,
        ];
    }
    if ($method === 'ping') {
        return [
            'jsonrpc' => '2.0',
            'id' => $id,
            'result' => new stdClass(),
        ];
    }

    return [
        'jsonrpc' => '2.0',
        'id' => $id,
        'error' => ['code' => -32601, 'message' => 'Method not found: ' . $method],
    ];
}

/**
 * @return array<string, mixed>
 */
function gos_lyre_mcp_authenticate_plain(string $plain): array
{
    $hash = gos_lyre_mcp_hash_token($plain);
    $tok = gos_lyre_mcp_read_json(gos_lyre_mcp_token_path($hash));
    if ($tok === [] || !isset($tok['user_id'])) {
        error_log('lyre-mcp: unauthorized');
        gos_lyre_mcp_jsonrpc_error(null, -32001, 'Unauthorized — paste the LYRE connector URL from the LYRE project picker');
    }
    $userId = (int) $tok['user_id'];
    if (empty($tok['enabled'])) {
        gos_lyre_mcp_jsonrpc_error(null, -32003, 'Access revoked');
    }
    $user = gos_lyre_mcp_user_lookup($userId);
    if ($user === null || (string) ($user['status'] ?? '') !== 'active') {
        gos_lyre_mcp_jsonrpc_error(null, -32003, 'Access revoked');
    }
    $state = gos_lyre_mcp_user_state($userId);
    if (empty($state['enabled']) || (string) ($state['token_hash'] ?? '') !== $hash) {
        gos_lyre_mcp_jsonrpc_error(null, -32003, 'Access revoked');
    }

    return [
        'user' => $user,
        'device' => null,
        'auth' => 'mcp',
    ];
}

function gos_lyre_mcp_read_body(): string
{
    $override = getenv('GOS_LYRE_MCP_BODY_FILE');
    if (is_string($override) && $override !== '' && is_readable($override)) {
        $raw = file_get_contents($override);
        return is_string($raw) ? $raw : '';
    }
    $raw = file_get_contents('php://input');
    if (is_string($raw) && $raw !== '') {
        return $raw;
    }
    if (PHP_SAPI === 'cli' && defined('STDIN')) {
        $in = stream_get_contents(STDIN);
        return is_string($in) ? $in : '';
    }
    return '';
}

function gos_lyre_mcp_handle_request(): never
{
    header('Access-Control-Allow-Origin: *');
    header('Access-Control-Allow-Headers: Authorization, Content-Type, MCP-Protocol-Version, Mcp-Session-Id');
    header('Access-Control-Allow-Methods: GET, POST, OPTIONS');

    if (($_SERVER['REQUEST_METHOD'] ?? '') === 'OPTIONS') {
        http_response_code(204);
        exit;
    }

    $method = strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'));
    if ($method === 'GET' || $method === 'HEAD') {
        header('Content-Type: application/json; charset=utf-8');
        header('Allow: POST, OPTIONS');
        http_response_code(405);
        echo json_encode([
            'jsonrpc' => '2.0',
            'error' => [
                'code' => -32600,
                'message' => 'Use POST JSON-RPC (Streamable HTTP)',
            ],
            'id' => null,
        ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        exit;
    }

    if ($method !== 'POST') {
        http_response_code(405);
        exit;
    }

    if (gos_lyre_mcp_gos_bearer_presented() && gos_lyre_mcp_extract_token_from_request() === null) {
        error_log('lyre-mcp: unauthorized');
        gos_lyre_mcp_jsonrpc_error(null, -32001, 'Unauthorized — paste the LYRE connector URL from the LYRE project picker');
    }

    $plain = gos_lyre_mcp_extract_token_from_request();
    if ($plain === null) {
        error_log('lyre-mcp: unauthorized');
        gos_lyre_mcp_jsonrpc_error(null, -32001, 'Unauthorized — paste the LYRE connector URL from the LYRE project picker');
    }

    $access = gos_lyre_mcp_authenticate_plain($plain);
    $userId = (int) ($access['user']['id'] ?? 0);
    gos_lyre_mcp_touch_last_used($userId);

    $raw = gos_lyre_mcp_read_body();
    $msg = json_decode($raw, true);
    if (!is_array($msg) || ($msg['jsonrpc'] ?? '') !== '2.0') {
        gos_lyre_mcp_jsonrpc_error(null, -32700, 'Parse error');
    }

    $rpcMethod = (string) ($msg['method'] ?? '');
    if ($rpcMethod === 'notifications/initialized' || str_starts_with($rpcMethod, 'notifications/')) {
        http_response_code(202);
        exit;
    }

    $out = gos_lyre_mcp_dispatch_message($msg, $access);
    gos_lyre_mcp_emit_jsonrpc($out);
}
