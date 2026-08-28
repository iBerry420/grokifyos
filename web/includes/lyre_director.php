<?php

declare(strict_types=1);

/**
 * LYRE director: CAS, flock, compact snapshot, activity JSONL, JSON-only timeline ops.
 */

const GOS_LYRE_MIN_DUR = 0.1;
const GOS_LYRE_ACTIVITY_CAP = 2097152;
const GOS_LYRE_ACTIVITY_KEEP = 1500;
const GOS_LYRE_BRAINSTORM_MAX = 4096;

/** @var array<string, mixed> */
function &gos_lyre_test_store(): array
{
    static $store = [
        'projects' => [],
        'boards' => [],
        'mysql_bumps' => [],
        'cas_seq' => 0,
    ];

    return $store;
}

function gos_lyre_test_store_enabled(): bool
{
    return getenv('GOS_LYRE_TEST_STORE') === '1';
}

function gos_lyre_test_store_reset(): void
{
    $store = &gos_lyre_test_store();
    $store['projects'] = [];
    $store['boards'] = [];
    $store['mysql_bumps'] = [];
    $store['cas_seq'] = 0;
}

/** @param array<string, mixed> $row */
function gos_lyre_test_put_project(array $row): void
{
    $store = &gos_lyre_test_store();
    $id = (string) ($row['id'] ?? '');
    $store['projects'][$id] = $row;
}

/**
 * @param array<string, mixed> $payload
 */
function gos_lyre_test_put_board(string $id, array $payload, string $updatedAt): void
{
    $store = &gos_lyre_test_store();
    $store['boards'][$id] = [
        'id' => $id,
        'title' => (string) ($payload['title'] ?? 'Untitled'),
        'brainstorm' => (string) ($payload['brainstorm'] ?? ''),
        'payload' => $payload,
        'updated_at' => $updatedAt,
    ];
}

/** @return list<string> */
function gos_lyre_test_mysql_bumps(): array
{
    return gos_lyre_test_store()['mysql_bumps'];
}

function gos_lyre_is_mcp(array $access): bool
{
    return (string) ($access['auth'] ?? '') === 'mcp';
}

function gos_lyre_safe_board_id(string $boardId): ?string
{
    $boardId = trim($boardId);
    if ($boardId === '' || $boardId === '.' || $boardId === '..') {
        return null;
    }
    if (str_contains($boardId, '..') || str_contains($boardId, '/') || str_contains($boardId, '\\')) {
        return null;
    }
    $safe = preg_replace('/[^A-Za-z0-9._-]+/', '_', $boardId) ?? '';
    $safe = trim($safe);
    if ($safe === '' || $safe === '.' || $safe === '..') {
        return null;
    }

    return $safe;
}

function gos_lyre_locks_dir(): string
{
    $override = getenv('GOS_LYRE_LOCKS_DIR');
    if (is_string($override) && $override !== '') {
        return rtrim($override, '/');
    }

    return gos_root() . '/storage/lyre-locks';
}

function gos_lyre_activity_dir(): string
{
    $override = getenv('GOS_LYRE_ACTIVITY_DIR');
    if (is_string($override) && $override !== '') {
        return rtrim($override, '/');
    }

    return gos_root() . '/storage/lyre-activity';
}

function gos_lyre_ensure_storage_dir(string $dir): void
{
    if (!is_dir($dir) && !mkdir($dir, 0770, true) && !is_dir($dir)) {
        gos_lyre_fail('storage_error', 500);
    }
}

/**
 * @return resource
 */
function gos_lyre_board_lock(string $boardId, int $timeoutSec = 5)
{
    $safe = gos_lyre_safe_board_id($boardId);
    if ($safe === null) {
        gos_lyre_fail('invalid_board_id', 400);
    }
    $dir = gos_lyre_locks_dir();
    gos_lyre_ensure_storage_dir($dir);
    $path = $dir . '/' . $safe . '.lock';
    $fh = fopen($path, 'c+');
    if ($fh === false) {
        gos_lyre_fail('lock_timeout', 409);
    }
    $deadline = microtime(true) + $timeoutSec;
    while (!flock($fh, LOCK_EX | LOCK_NB)) {
        if (microtime(true) >= $deadline) {
            fclose($fh);
            gos_lyre_fail('lock_timeout', 409);
        }
        usleep(100000);
    }

    return $fh;
}

/** @param resource|null $fh */
function gos_lyre_board_unlock($fh): void
{
    if (!is_resource($fh)) {
        return;
    }
    flock($fh, LOCK_UN);
    fclose($fh);
}

/**
 * @template T
 * @param callable(): T $fn
 * @return T
 */
function gos_lyre_with_board_lock(string $boardId, callable $fn)
{
    $fh = gos_lyre_board_lock($boardId);
    try {
        return $fn();
    } finally {
        gos_lyre_board_unlock($fh);
    }
}

function gos_lyre_find_project_by_id(int $userId, string $id): ?array
{
    if (gos_lyre_test_store_enabled()) {
        $row = gos_lyre_test_store()['projects'][$id] ?? null;
        if (!is_array($row)) {
            return null;
        }
        if ((int) ($row['user_id'] ?? 0) !== $userId) {
            return null;
        }

        return $row;
    }

    return gos_lyre_project_by_id(gos_lyre_mysql(), $userId, $id);
}

function gos_lyre_find_project_by_board(int $userId, string $boardId): ?array
{
    if (gos_lyre_test_store_enabled()) {
        foreach (gos_lyre_test_store()['projects'] as $row) {
            if (!is_array($row)) {
                continue;
            }
            if ((int) ($row['user_id'] ?? 0) === $userId && (string) ($row['board_id'] ?? '') === $boardId) {
                return $row;
            }
        }

        return null;
    }

    return gos_lyre_project_by_board(gos_lyre_mysql(), $userId, $boardId);
}

function gos_lyre_touch_project(int $userId, string $projectId): void
{
    if (gos_lyre_test_store_enabled()) {
        $store = &gos_lyre_test_store();
        $store['mysql_bumps'][] = $projectId;
        if (isset($store['projects'][$projectId])) {
            $store['projects'][$projectId]['updated_at'] = gmdate('Y-m-d H:i:s') . '+00';
        }

        return;
    }
    $st = gos_lyre_mysql()->prepare(
        'UPDATE lyre_projects SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?'
    );
    $st->execute([$projectId, $userId]);
}

function gos_lyre_pg_maybe(): ?PDO
{
    if (gos_lyre_test_store_enabled()) {
        return null;
    }

    return gos_lyre_pg();
}

/** @return array<string, mixed>|null */
function gos_lyre_pg_select_id(string $id): ?array
{
    if (gos_lyre_test_store_enabled()) {
        $row = gos_lyre_test_store()['boards'][$id] ?? null;

        return is_array($row) ? $row : null;
    }

    return gos_lyre_pg_select(gos_lyre_pg(), $id);
}

/**
 * @param array<string, mixed> $data
 * @return array{updated_at: string}
 */
function gos_lyre_pg_update_cas(?PDO $pg, string $id, array $data, string $expectedUpdatedAt): array
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
    if (gos_lyre_test_store_enabled()) {
        $store = &gos_lyre_test_store();
        $cur = $store['boards'][$id] ?? null;
        if (!is_array($cur)) {
            gos_lyre_fail('not_found', 404);
        }
        if ((string) ($cur['updated_at'] ?? '') !== $expectedUpdatedAt) {
            gos_lyre_fail('conflict', 409, ['updated_at' => (string) ($cur['updated_at'] ?? '')]);
        }
        $store['cas_seq']++;
        $stamp = '2026-08-28 16:01:02.' . sprintf('%06d', $store['cas_seq']) . '+00';
        $store['boards'][$id] = [
            'id' => $id,
            'title' => $title,
            'brainstorm' => $brainstorm,
            'payload' => $data,
            'updated_at' => $stamp,
        ];

        return ['updated_at' => $stamp];
    }
    if (!$pg instanceof PDO) {
        gos_lyre_fail('lyre_pg_unavailable', 503);
    }
    try {
        $st = $pg->prepare(
            'UPDATE boards
             SET title = ?, brainstorm = ?, payload = CAST(? AS jsonb), updated_at = NOW()
             WHERE id = ? AND updated_at = CAST(? AS timestamptz)
             RETURNING updated_at'
        );
        $st->execute([$title, $brainstorm, $json, $id, $expectedUpdatedAt]);
        $row = $st->fetch();
        if (!is_array($row) || !array_key_exists('updated_at', $row)) {
            $cur = gos_lyre_pg_select($pg, $id);
            gos_lyre_fail('conflict', 409, [
                'updated_at' => is_array($cur) ? (string) ($cur['updated_at'] ?? '') : '',
            ]);
        }

        return ['updated_at' => (string) $row['updated_at']];
    } catch (GosLyreException $e) {
        throw $e;
    } catch (Throwable) {
        gos_lyre_fail('lyre_pg_unavailable', 503);
    }
}

function gos_lyre_activity_path(string $boardId): string
{
    $safe = gos_lyre_safe_board_id($boardId);
    if ($safe === null) {
        gos_lyre_fail('invalid_board_id', 400);
    }

    return gos_lyre_activity_dir() . '/' . $safe . '.jsonl';
}

function gos_lyre_activity_bytes(string $boardId): int
{
    $path = gos_lyre_activity_path($boardId);
    if (!is_file($path)) {
        return 0;
    }
    $n = filesize($path);

    return $n === false ? 0 : (int) $n;
}

/** @param array<string, mixed> $line */
function gos_lyre_activity_append_line(string $boardId, array $line): void
{
    $path = gos_lyre_activity_path($boardId);
    gos_lyre_ensure_storage_dir(dirname($path));
    $json = json_encode($line, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    if (!is_string($json)) {
        return;
    }
    $fh = fopen($path, 'c+');
    if ($fh === false) {
        gos_lyre_fail('storage_error', 500);
    }
    try {
        flock($fh, LOCK_EX);
        fseek($fh, 0, SEEK_END);
        fwrite($fh, $json . "\n");
        $size = ftell($fh);
        if (is_int($size) && $size > GOS_LYRE_ACTIVITY_CAP) {
            rewind($fh);
            $raw = stream_get_contents($fh);
            $parts = is_string($raw) ? preg_split("/\r\n|\n|\r/", trim($raw)) : [];
            $parts = is_array($parts) ? $parts : [];
            $keep = array_slice($parts, -GOS_LYRE_ACTIVITY_KEEP);
            ftruncate($fh, 0);
            rewind($fh);
            fwrite($fh, implode("\n", $keep) . "\n");
        }
    } finally {
        flock($fh, LOCK_UN);
        fclose($fh);
    }
}

/**
 * @return list<array<string, mixed>>
 */
function gos_lyre_activity_read(string $boardId, int $limit = 50, ?int $beforeTs = null): array
{
    $path = gos_lyre_activity_path($boardId);
    if (!is_file($path)) {
        return [];
    }
    $raw = file_get_contents($path);
    if (!is_string($raw) || $raw === '') {
        return [];
    }
    $out = [];
    foreach (preg_split("/\r\n|\n|\r/", $raw) ?: [] as $row) {
        $row = trim($row);
        if ($row === '') {
            continue;
        }
        $decoded = json_decode($row, true);
        if (!is_array($decoded)) {
            continue;
        }
        $ts = (int) ($decoded['ts'] ?? 0);
        if ($beforeTs !== null && $ts >= $beforeTs) {
            continue;
        }
        $out[] = $decoded;
    }
    usort($out, static fn ($a, $b) => ((int) ($b['ts'] ?? 0)) <=> ((int) ($a['ts'] ?? 0)));
    if ($limit < 1) {
        $limit = 50;
    }

    return array_slice($out, 0, $limit);
}

function gos_lyre_num(mixed $v, float $fallback = 0.0): float
{
    if (is_int($v) || is_float($v)) {
        return (float) $v;
    }
    if (is_string($v) && is_numeric($v)) {
        return (float) $v;
    }

    return $fallback;
}

function gos_lyre_js_or(mixed $value, float $fallback): float
{
    if ($value === null || $value === '' || $value === false) {
        return $fallback;
    }
    $n = gos_lyre_num($value, 0.0);
    if ($n == 0.0 || is_nan($n)) {
        return $fallback;
    }

    return $n;
}

function gos_lyre_str(mixed $v): string
{
    return is_string($v) ? $v : (is_scalar($v) ? (string) $v : '');
}

/** @return list<array<string, mixed>> */
function gos_lyre_arr(mixed $v): array
{
    return is_array($v) ? array_values($v) : [];
}

/** @return list<array<string, mixed>> */
function gos_lyre_layers(array $board, string $field): array
{
    $layers = $board[$field] ?? [];
    if (!is_array($layers)) {
        return [];
    }
    $out = [];
    foreach ($layers as $layer) {
        if (is_array($layer)) {
            $out[] = $layer;
        }
    }

    return $out;
}

function gos_lyre_movie(array $board): ?array
{
    $movie = $board['movie'] ?? null;

    return is_array($movie) ? $movie : null;
}

function gos_lyre_parts(array $board): array
{
    $movie = gos_lyre_movie($board);
    $parts = is_array($movie) ? ($movie['parts'] ?? []) : [];

    return is_array($parts) ? $parts : [];
}

/** @return list<array<string, mixed>> */
function gos_lyre_ordered_video_clips(array $layersOrBoard): array
{
    $layers = isset($layersOrBoard['videoLayers']) ? gos_lyre_layers($layersOrBoard, 'videoLayers') : gos_lyre_arr($layersOrBoard);
    $clips = [];
    foreach ($layers as $layer) {
        if (!is_array($layer) || (string) ($layer['kind'] ?? 'video') !== 'video') {
            continue;
        }
        foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
            if (!is_array($clip)) {
                continue;
            }
            if (gos_lyre_str($clip['src'] ?? '') === '') {
                continue;
            }
            $clips[] = $clip;
        }
    }
    usort($clips, static function ($a, $b) {
        $sa = gos_lyre_num($a['startSec'] ?? 0);
        $sb = gos_lyre_num($b['startSec'] ?? 0);
        if ($sa === $sb) {
            return gos_lyre_str($a['id'] ?? '') <=> gos_lyre_str($b['id'] ?? '');
        }

        return $sa <=> $sb;
    });

    return $clips;
}

function gos_lyre_movie_play_duration(array $movie): float
{
    $full = max(0.1, gos_lyre_num($movie['durationSec'] ?? 0));
    $play = $movie['playDurationSec'] ?? null;
    if ($play === null || gos_lyre_num($play) >= $full - 1.0 / 48.0) {
        return $full;
    }

    return max(0.1, gos_lyre_num($play));
}

function gos_lyre_resolved_movie(array $board): ?array
{
    $movie = gos_lyre_movie($board);
    if ($movie !== null && gos_lyre_str($movie['src'] ?? '') !== '') {
        return $movie;
    }
    $first = gos_lyre_ordered_video_clips($board)[0] ?? null;
    if (!is_array($first)) {
        return null;
    }
    $src = gos_lyre_str($first['src'] ?? '');
    if ($src === '') {
        return null;
    }
    $duration = gos_lyre_js_or($first['sourceDurationSec'] ?? null, gos_lyre_num($first['durationSec'] ?? 0));

    return [
        'src' => $src,
        'durationSec' => $duration,
        'fps' => null,
        'parts' => [[
            'clipId' => gos_lyre_str($first['id'] ?? ''),
            'src' => $src,
            'durationSec' => gos_lyre_num($first['durationSec'] ?? 0),
        ]],
    ];
}

function gos_lyre_clip_in_movie(array $board, string $clipId): bool
{
    if ($clipId === 'lc_movie') {
        return true;
    }
    $movie = gos_lyre_movie($board);
    if (is_array($movie)) {
        foreach (gos_lyre_arr($movie['parts'] ?? []) as $part) {
            if (is_array($part) && gos_lyre_str($part['clipId'] ?? '') === $clipId) {
                return true;
            }
        }
        if (gos_lyre_str($movie['src'] ?? '') !== '') {
            return false;
        }
    }
    $first = gos_lyre_ordered_video_clips($board)[0] ?? null;

    return is_array($first) && gos_lyre_str($first['id'] ?? '') === $clipId;
}

function gos_lyre_is_stitched_member(array $board, string $clipId): bool
{
    $parts = gos_lyre_parts($board);
    $n = count($parts);
    if ($clipId === 'lc_movie') {
        return $n > 1;
    }
    if ($n <= 1) {
        return false;
    }
    foreach ($parts as $part) {
        if (is_array($part) && gos_lyre_str($part['clipId'] ?? '') === $clipId) {
            return true;
        }
    }

    return false;
}

function gos_lyre_is_stitched_frame(array $board, string $frameId): bool
{
    $movie = gos_lyre_movie($board);
    if ($movie === null || count(gos_lyre_parts($board)) <= 1) {
        return false;
    }
    foreach (gos_lyre_ordered_video_clips($board) as $clip) {
        if (gos_lyre_str($clip['linkedFrameId'] ?? '') === $frameId && gos_lyre_is_stitched_member($board, gos_lyre_str($clip['id'] ?? ''))) {
            return true;
        }
    }

    return false;
}

function gos_lyre_is_picture_locked(array $board, string $frameId): bool
{
    return gos_lyre_is_stitched_frame($board, $frameId);
}

function gos_lyre_is_movie_locked(array $board, string $clipId): bool
{
    if ($clipId === 'lc_movie') {
        return true;
    }
    if (gos_lyre_is_stitched_member($board, $clipId)) {
        return true;
    }

    return gos_lyre_clip_in_movie($board, $clipId);
}

function gos_lyre_next_stitch_target(array $board): ?array
{
    $ordered = gos_lyre_ordered_video_clips($board);
    if (count($ordered) < 2) {
        return null;
    }
    $movie = gos_lyre_movie($board);
    $parts = is_array($movie) ? gos_lyre_arr($movie['parts'] ?? []) : [];
    if ($movie === null || $parts === []) {
        return $ordered[1];
    }
    $lastIdx = -1;
    foreach ($parts as $part) {
        if (!is_array($part)) {
            continue;
        }
        $pid = gos_lyre_str($part['clipId'] ?? '');
        foreach ($ordered as $i => $clip) {
            if (gos_lyre_str($clip['id'] ?? '') === $pid && $i > $lastIdx) {
                $lastIdx = $i;
            }
        }
    }
    if ($lastIdx < 0) {
        return $ordered[1];
    }

    return $ordered[$lastIdx + 1] ?? null;
}

function gos_lyre_can_stitch_clip(array $board, string $clipId, ?string $src = null): bool
{
    if ($src === null) {
        foreach (gos_lyre_ordered_video_clips($board) as $clip) {
            if (gos_lyre_str($clip['id'] ?? '') === $clipId) {
                $src = gos_lyre_str($clip['src'] ?? '');
                break;
            }
        }
    }
    if ($src === null || $src === '') {
        return false;
    }
    $next = gos_lyre_next_stitch_target($board);

    return is_array($next) && gos_lyre_str($next['id'] ?? '') === $clipId;
}

function gos_lyre_leftover_start(array $board, float $playhead): float
{
    $live = gos_lyre_resolved_movie($board);
    $movieEnd = 0.0;
    if (is_array($live) && gos_lyre_str($live['src'] ?? '') !== '') {
        $movieEnd = gos_lyre_movie_play_duration($live);
    }

    return max(max(0.0, $playhead), $movieEnd);
}

/** @return list<array{frame: array, sceneId: string, sceneTitle: string, start: float, length: float}> */
function gos_lyre_storyboard(array $board): array
{
    $out = [];
    $t = 0.0;
    foreach (gos_lyre_arr($board['scenes'] ?? []) as $scene) {
        if (!is_array($scene)) {
            continue;
        }
        $sceneId = gos_lyre_str($scene['id'] ?? '');
        $sceneTitle = gos_lyre_str($scene['title'] ?? '');
        foreach (gos_lyre_arr($scene['frames'] ?? []) as $frame) {
            if (!is_array($frame)) {
                continue;
            }
            $length = max(GOS_LYRE_MIN_DUR, gos_lyre_num($frame['durationSec'] ?? 0));
            $out[] = [
                'frame' => $frame,
                'sceneId' => $sceneId,
                'sceneTitle' => $sceneTitle,
                'start' => $t,
                'length' => $length,
            ];
            $t += $length;
        }
    }

    return $out;
}

function gos_lyre_clip_of(array $board, string $frameId): ?array
{
    foreach (gos_lyre_storyboard($board) as $sc) {
        if (gos_lyre_str($sc['frame']['id'] ?? '') === $frameId) {
            return $sc;
        }
    }

    return null;
}

function gos_lyre_clip_at_time(array $clips, float $t): ?array
{
    if ($clips === []) {
        return null;
    }
    $last = $clips[array_key_last($clips)];
    if ($t >= gos_lyre_num($last['start'] ?? 0) + gos_lyre_num($last['length'] ?? 0)) {
        return $last;
    }
    foreach ($clips as $clip) {
        $start = gos_lyre_num($clip['start'] ?? 0);
        $len = gos_lyre_num($clip['length'] ?? 0);
        if ($t >= $start && $t < $start + $len) {
            return $clip;
        }
    }

    return $clips[0];
}

function gos_lyre_scene_id_at(array $board, float $t): string
{
    $clip = gos_lyre_clip_at_time(gos_lyre_storyboard($board), $t);
    if (is_array($clip)) {
        return gos_lyre_str($clip['sceneId'] ?? '');
    }
    $active = gos_lyre_str($board['activeSceneId'] ?? '');
    if ($active !== '') {
        return $active;
    }
    $scenes = gos_lyre_arr($board['scenes'] ?? []);

    return $scenes !== [] ? gos_lyre_str($scenes[0]['id'] ?? '') : '';
}

function gos_lyre_find_video_clip(array $board, string $clipId): ?array
{
    foreach (gos_lyre_layers($board, 'videoLayers') as $layer) {
        foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
            if (is_array($clip) && gos_lyre_str($clip['id'] ?? '') === $clipId) {
                return $clip;
            }
        }
    }

    return null;
}

function gos_lyre_find_audio_clip(array $board, string $clipId): ?array
{
    foreach (gos_lyre_layers($board, 'audioLayers') as $layer) {
        foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
            if (is_array($clip) && gos_lyre_str($clip['id'] ?? '') === $clipId) {
                return $clip;
            }
        }
    }

    return null;
}

function gos_lyre_overlaps(array $a, array $b, float $epsilon = 1e-4): bool
{
    if (gos_lyre_str($a['id'] ?? '') === gos_lyre_str($b['id'] ?? '')) {
        return false;
    }
    $as = gos_lyre_num($a['startSec'] ?? 0);
    $ad = gos_lyre_num($a['durationSec'] ?? 0);
    $bs = gos_lyre_num($b['startSec'] ?? 0);
    $bd = gos_lyre_num($b['durationSec'] ?? 0);

    return $as < $bs + $bd - $epsilon && $bs < $as + $ad - $epsilon;
}

/** @return list<array<string, mixed>> */
function gos_lyre_picture_video_clips(array $board): array
{
    $stills = gos_lyre_storyboard($board);
    $byFrame = [];
    foreach (gos_lyre_layers($board, 'videoLayers') as $layer) {
        foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
            if (!is_array($clip)) {
                continue;
            }
            $fid = gos_lyre_str($clip['linkedFrameId'] ?? '');
            if ($fid !== '' && !isset($byFrame[$fid])) {
                $byFrame[$fid] = $clip;
            }
        }
    }
    $memberIds = [];
    foreach (gos_lyre_parts($board) as $part) {
        if (is_array($part)) {
            $memberIds[gos_lyre_str($part['clipId'] ?? '')] = true;
        }
    }
    $stitched = count($memberIds) > 1;
    $live = $stitched ? gos_lyre_resolved_movie($board) : null;
    $out = [];
    $movieEmitted = false;
    foreach ($stills as $sc) {
        $frame = $sc['frame'];
        $fid = gos_lyre_str($frame['id'] ?? '');
        $existing = $byFrame[$fid] ?? null;
        $src = '';
        if (is_array($existing)) {
            $src = gos_lyre_str($existing['src'] ?? '');
        }
        if ($src === '') {
            $src = gos_lyre_str($frame['videoSrc'] ?? '');
        }
        if ($src === '') {
            continue;
        }
        if ($stitched && is_array($existing) && isset($memberIds[gos_lyre_str($existing['id'] ?? '')])) {
            if (!$movieEmitted) {
                $members = [];
                foreach ($stills as $m) {
                    $mfid = gos_lyre_str($m['frame']['id'] ?? '');
                    $ex = $byFrame[$mfid] ?? null;
                    if (is_array($ex) && isset($memberIds[gos_lyre_str($ex['id'] ?? '')])) {
                        $members[] = $m;
                    }
                }
                $start = $members !== [] ? gos_lyre_num($members[0]['start'] ?? 0) : gos_lyre_num($sc['start'] ?? 0);
                if (is_array($live)) {
                    $play = gos_lyre_movie_play_duration($live);
                    $native = gos_lyre_num($live['durationSec'] ?? $play);
                    $movieSrc = gos_lyre_str($live['src'] ?? '') !== '' ? gos_lyre_str($live['src'] ?? '') : $src;
                } else {
                    $sum = 0.0;
                    foreach ($members as $m) {
                        $sum += gos_lyre_num($m['length'] ?? 0);
                    }
                    $play = max(GOS_LYRE_MIN_DUR, $sum);
                    $native = $play;
                    $movieSrc = $src;
                }
                $out[] = [
                    'id' => 'lc_movie',
                    'src' => $movieSrc,
                    'name' => 'Movie · ' . (string) count($memberIds),
                    'startSec' => $start,
                    'durationSec' => $play,
                    'trimInSec' => 0.0,
                    'sourceDurationSec' => $native,
                    'linkedFrameId' => $members !== []
                        ? gos_lyre_str($members[0]['frame']['id'] ?? $fid)
                        : $fid,
                ];
                $movieEmitted = true;
            }
            continue;
        }
        $clip = is_array($existing) ? $existing : [
            'id' => 'pv_' . $fid,
            'src' => $src,
            'name' => gos_lyre_str($frame['caption'] ?? '') !== '' ? gos_lyre_str($frame['caption'] ?? '') : $fid,
            'startSec' => $sc['start'],
            'durationSec' => $sc['length'],
            'linkedFrameId' => $fid,
        ];
        $clip['startSec'] = $sc['start'];
        $clip['durationSec'] = $sc['length'];
        $clip['src'] = $src;
        $clip['linkedFrameId'] = $fid;
        $out[] = $clip;
    }

    return $out;
}

function gos_lyre_sync_linked_video(array $board): array
{
    $stills = gos_lyre_storyboard($board);
    $byFrame = [];
    $unlinked = [];
    foreach (gos_lyre_layers($board, 'videoLayers') as $layer) {
        foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
            if (!is_array($clip)) {
                continue;
            }
            $fid = gos_lyre_str($clip['linkedFrameId'] ?? '');
            if ($fid === '') {
                $unlinked[] = $clip;
            } elseif (!isset($byFrame[$fid])) {
                $byFrame[$fid] = $clip;
            }
        }
    }
    $packed = [];
    foreach ($stills as $sc) {
        $frame = $sc['frame'];
        $fid = gos_lyre_str($frame['id'] ?? '');
        $prev = $byFrame[$fid] ?? null;
        unset($byFrame[$fid]);
        $src = is_array($prev) ? gos_lyre_str($prev['src'] ?? '') : '';
        if ($src === '') {
            $src = gos_lyre_str($frame['videoSrc'] ?? '');
        }
        if ($src === '') {
            continue;
        }
        $base = is_array($prev) ? $prev : [
            'id' => gos_lyre_media_id('lc'),
            'src' => $src,
            'name' => gos_lyre_str($frame['caption'] ?? '') !== '' ? gos_lyre_str($frame['caption'] ?? '') : $fid,
            'startSec' => $sc['start'],
            'durationSec' => $sc['length'],
            'sourceDurationSec' => gos_lyre_js_or($frame['videoDurationSec'] ?? null, $sc['length']),
            'trimInSec' => gos_lyre_num($frame['videoInSec'] ?? 0),
            'linkedFrameId' => $fid,
        ];
        $base['startSec'] = $sc['start'];
        $base['durationSec'] = $sc['length'];
        $base['src'] = $src;
        $base['linkedFrameId'] = $fid;
        $packed[] = $base;
    }
    foreach ($byFrame as $clip) {
        $unlinked[] = $clip;
    }
    $t = 0.0;
    foreach ($stills as $sc) {
        $t += gos_lyre_num($sc['length'] ?? 0);
    }
    $rest = [];
    foreach ($unlinked as $clip) {
        $clip['startSec'] = $t;
        $rest[] = $clip;
        $t += max(GOS_LYRE_MIN_DUR, gos_lyre_num($clip['durationSec'] ?? 0));
    }
    $all = array_merge($packed, $rest);
    $layers = gos_lyre_layers($board, 'videoLayers');
    if ($all === [] && $layers === []) {
        return $board;
    }
    if ($layers === []) {
        $board['videoLayers'] = [[
            'id' => gos_lyre_media_id('ly'),
            'kind' => 'video',
            'name' => 'V1',
            'clips' => $all,
        ]];
    } else {
        $out = [];
        foreach ($layers as $i => $layer) {
            $layer['clips'] = $i === 0 ? $all : [];
            $out[] = $layer;
        }
        $board['videoLayers'] = $out;
    }

    return $board;
}

function gos_lyre_member_frame_ids(array $board): array
{
    $ids = [];
    $layers = gos_lyre_layers($board, 'videoLayers');
    foreach ($layers as $layer) {
        foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
            if (!is_array($clip)) {
                continue;
            }
            $cid = gos_lyre_str($clip['id'] ?? '');
            if (!gos_lyre_clip_in_movie($board, $cid)) {
                continue;
            }
            $fid = gos_lyre_str($clip['linkedFrameId'] ?? '');
            if ($fid !== '') {
                $ids[$fid] = true;
            }
        }
    }

    return $ids;
}

function gos_lyre_picture_sig(array $board, array $memberFrames): array
{
    $sig = [];
    foreach (gos_lyre_storyboard($board) as $sc) {
        $fid = gos_lyre_str($sc['frame']['id'] ?? '');
        if (!isset($memberFrames[$fid])) {
            continue;
        }
        $sig[] = [$fid, $sc['start'], $sc['length']];
    }

    return $sig;
}

function gos_lyre_clear_movie_if_picture_changed(array $before, array $after): array
{
    if (!is_array($after['movie'] ?? null)) {
        return $after;
    }
    $memberFrames = gos_lyre_member_frame_ids($before);
    if ($memberFrames === []) {
        return $after;
    }
    if (gos_lyre_picture_sig($before, $memberFrames) !== gos_lyre_picture_sig($after, $memberFrames)) {
        $after['movie'] = null;
    }

    return $after;
}

function gos_lyre_finish_picture_edit(array $before, array $after): array
{
    return gos_lyre_clear_movie_if_picture_changed($before, gos_lyre_sync_linked_video($after));
}

function gos_lyre_set_frame_duration_raw(array $board, string $frameId, float $durationSec): array
{
    $dur = max(GOS_LYRE_MIN_DUR, $durationSec);
    $scenes = [];
    foreach (gos_lyre_arr($board['scenes'] ?? []) as $scene) {
        if (!is_array($scene)) {
            continue;
        }
        $frames = [];
        foreach (gos_lyre_arr($scene['frames'] ?? []) as $frame) {
            if (!is_array($frame)) {
                $frames[] = $frame;
                continue;
            }
            if (gos_lyre_str($frame['id'] ?? '') !== $frameId) {
                $frames[] = $frame;
                continue;
            }
            $inn = gos_lyre_num($frame['videoInSec'] ?? 0);
            $frame['durationSec'] = $dur;
            if (gos_lyre_str($frame['videoSrc'] ?? '') !== '') {
                $frame['videoOutSec'] = $inn + $dur;
            }
            $frames[] = $frame;
        }
        $scene['frames'] = $frames;
        $scenes[] = $scene;
    }
    $board['scenes'] = $scenes;

    return $board;
}

function gos_lyre_bump_video_in(array $board, string $frameId, float $delta): array
{
    if (abs($delta) < 1e-6) {
        return $board;
    }
    $scenes = [];
    foreach (gos_lyre_arr($board['scenes'] ?? []) as $scene) {
        if (!is_array($scene)) {
            continue;
        }
        $frames = [];
        foreach (gos_lyre_arr($scene['frames'] ?? []) as $frame) {
            if (!is_array($frame)) {
                $frames[] = $frame;
                continue;
            }
            if (gos_lyre_str($frame['id'] ?? '') !== $frameId) {
                $frames[] = $frame;
                continue;
            }
            $inn = max(0.0, gos_lyre_num($frame['videoInSec'] ?? 0) + $delta);
            $frame['videoInSec'] = $inn;
            $frame['videoOutSec'] = $inn + gos_lyre_num($frame['durationSec'] ?? 0);
            $frames[] = $frame;
        }
        $scene['frames'] = $frames;
        $scenes[] = $scene;
    }
    $board['scenes'] = $scenes;
    $layers = [];
    foreach (gos_lyre_layers($board, 'videoLayers') as $layer) {
        $clips = [];
        foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
            if (!is_array($clip) || gos_lyre_str($clip['linkedFrameId'] ?? '') !== $frameId) {
                $clips[] = $clip;
                continue;
            }
            $clip['trimInSec'] = max(0.0, gos_lyre_num($clip['trimInSec'] ?? 0) + $delta);
            $clips[] = $clip;
        }
        $layer['clips'] = $clips;
        $layers[] = $layer;
    }
    $board['videoLayers'] = $layers;

    return $board;
}

function gos_lyre_set_frame_duration(array $board, string $frameId, float $durationSec): array
{
    return gos_lyre_finish_picture_edit($board, gos_lyre_set_frame_duration_raw($board, $frameId, $durationSec));
}

function gos_lyre_trim_still_right(array $board, string $frameId, float $newEndSec): array
{
    if (gos_lyre_is_picture_locked($board, $frameId)) {
        gos_lyre_fail('movie_locked', 409);
    }
    $clip = gos_lyre_clip_of($board, $frameId);
    if ($clip === null) {
        return $board;
    }
    $dur = max(GOS_LYRE_MIN_DUR, $newEndSec - gos_lyre_num($clip['start'] ?? 0));

    return gos_lyre_set_frame_duration($board, $frameId, $dur);
}

function gos_lyre_trim_still_left(array $board, string $frameId, float $newStartSec): array
{
    if (gos_lyre_is_picture_locked($board, $frameId)) {
        gos_lyre_fail('movie_locked', 409);
    }
    $clips = gos_lyre_storyboard($board);
    $i = -1;
    foreach ($clips as $idx => $sc) {
        if (gos_lyre_str($sc['frame']['id'] ?? '') === $frameId) {
            $i = $idx;
            break;
        }
    }
    if ($i < 0) {
        return $board;
    }
    $cur = $clips[$i];
    $curEnd = gos_lyre_num($cur['start'] ?? 0) + gos_lyre_num($cur['length'] ?? 0);
    if ($i === 0) {
        $start = max(0.0, $newStartSec);
        $dur = max(GOS_LYRE_MIN_DUR, $curEnd - $start);
        $cut = gos_lyre_num($cur['length'] ?? 0) - $dur;
        $next = gos_lyre_set_frame_duration_raw($board, $frameId, $dur);
        $next = gos_lyre_bump_video_in($next, $frameId, $cut);

        return gos_lyre_finish_picture_edit($board, $next);
    }
    $prev = $clips[$i - 1];
    $boundary = min(max($newStartSec, gos_lyre_num($prev['start'] ?? 0) + GOS_LYRE_MIN_DUR), $curEnd - GOS_LYRE_MIN_DUR);
    $cut = $boundary - gos_lyre_num($cur['start'] ?? 0);
    $next = gos_lyre_set_frame_duration_raw($board, gos_lyre_str($prev['frame']['id'] ?? ''), $boundary - gos_lyre_num($prev['start'] ?? 0));
    $next = gos_lyre_set_frame_duration_raw($next, $frameId, $curEnd - $boundary);
    $next = gos_lyre_bump_video_in($next, $frameId, $cut);

    return gos_lyre_finish_picture_edit($board, $next);
}

function gos_lyre_move_still_to(array $board, string $frameId, float $atSec): array
{
    if (gos_lyre_is_picture_locked($board, $frameId)) {
        gos_lyre_fail('movie_locked', 409);
    }
    $clips = gos_lyre_storyboard($board);
    $moving = null;
    foreach ($clips as $sc) {
        if (gos_lyre_str($sc['frame']['id'] ?? '') === $frameId) {
            $moving = $sc;
            break;
        }
    }
    if ($moving === null) {
        return $board;
    }
    $target = gos_lyre_clip_at_time($clips, $atSec) ?? $moving;
    $sceneId = gos_lyre_str($target['sceneId'] ?? '');
    $strippedScenes = [];
    foreach (gos_lyre_arr($board['scenes'] ?? []) as $scene) {
        if (!is_array($scene)) {
            continue;
        }
        $frames = [];
        foreach (gos_lyre_arr($scene['frames'] ?? []) as $frame) {
            if (is_array($frame) && gos_lyre_str($frame['id'] ?? '') === $frameId) {
                continue;
            }
            $frames[] = $frame;
        }
        $scene['frames'] = $frames;
        $strippedScenes[] = $scene;
    }
    $stripped = $board;
    $stripped['scenes'] = $strippedScenes;
    $dest = null;
    foreach ($strippedScenes as $scene) {
        if (gos_lyre_str($scene['id'] ?? '') === $sceneId) {
            $dest = $scene;
            break;
        }
    }
    if (!is_array($dest)) {
        return $board;
    }
    $destStart = 0.0;
    foreach (gos_lyre_storyboard($stripped) as $sc) {
        if (gos_lyre_str($sc['sceneId'] ?? '') === $sceneId) {
            $destStart = gos_lyre_num($sc['start'] ?? 0);
            break;
        }
    }
    $insert = count(gos_lyre_arr($dest['frames'] ?? []));
    $t = $destStart;
    foreach (gos_lyre_arr($dest['frames'] ?? []) as $i => $frame) {
        $len = max(GOS_LYRE_MIN_DUR, gos_lyre_num(is_array($frame) ? ($frame['durationSec'] ?? 0) : 0));
        if ($atSec < $t + $len / 2.0) {
            $insert = $i;
            break;
        }
        $t += $len;
    }
    $frames = gos_lyre_arr($dest['frames'] ?? []);
    array_splice($frames, max(0, min($insert, count($frames))), 0, [$moving['frame']]);
    $dest['frames'] = array_values($frames);
    $scenes = [];
    foreach ($strippedScenes as $scene) {
        $scenes[] = gos_lyre_str($scene['id'] ?? '') === gos_lyre_str($dest['id'] ?? '') ? $dest : $scene;
    }
    $after = $board;
    $after['scenes'] = $scenes;
    $after['activeSceneId'] = gos_lyre_str($dest['id'] ?? $board['activeSceneId'] ?? '');

    return gos_lyre_finish_picture_edit($board, $after);
}

function gos_lyre_source_duration(array $clip): float
{
    $inn = gos_lyre_num($clip['trimInSec'] ?? 0);
    $dur = gos_lyre_num($clip['durationSec'] ?? 0);

    return max($inn + $dur, gos_lyre_js_or($clip['sourceDurationSec'] ?? null, $inn + $dur));
}

function gos_lyre_trimmed_window(array $clip, float $newStartSec, float $newEndSec): array
{
    $inn0 = gos_lyre_num($clip['trimInSec'] ?? 0);
    $native = gos_lyre_source_duration($clip);
    $oldStart = gos_lyre_num($clip['startSec'] ?? 0);
    $start = $newStartSec;
    $end = $newEndSec;
    if ($end < $start + GOS_LYRE_MIN_DUR) {
        $end = $start + GOS_LYRE_MIN_DUR;
    }
    $inn = $inn0 + ($start - $oldStart);
    $dur = $end - $start;
    if ($inn < 0.0) {
        $start -= $inn;
        $dur += $inn;
        $inn = 0.0;
    }
    if ($inn + $dur > $native) {
        $dur = max(GOS_LYRE_MIN_DUR, $native - $inn);
    }
    if ($start < 0.0) {
        $inn += -$start;
        $dur -= -$start;
        $start = 0.0;
    }
    $dur = max(GOS_LYRE_MIN_DUR, $dur);
    $inn = min(max(0.0, $inn), max(0.0, $native - GOS_LYRE_MIN_DUR));
    $clip['startSec'] = max(0.0, $start);
    $clip['durationSec'] = $dur;
    $clip['trimInSec'] = $inn;
    if (!isset($clip['sourceDurationSec'])) {
        $clip['sourceDurationSec'] = $native;
    }

    return $clip;
}

function gos_lyre_map_video_clip(array $board, string $clipId, callable $fn): array
{
    $layers = [];
    foreach (gos_lyre_layers($board, 'videoLayers') as $layer) {
        $clips = [];
        foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
            if (is_array($clip) && gos_lyre_str($clip['id'] ?? '') === $clipId) {
                $clips[] = $fn($clip);
            } else {
                $clips[] = $clip;
            }
        }
        $layer['clips'] = $clips;
        $layers[] = $layer;
    }
    $board['videoLayers'] = $layers;

    return $board;
}

function gos_lyre_map_audio_clip(array $board, string $clipId, callable $fn): array
{
    $layers = [];
    foreach (gos_lyre_layers($board, 'audioLayers') as $layer) {
        $clips = [];
        foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
            if (is_array($clip) && gos_lyre_str($clip['id'] ?? '') === $clipId) {
                $clips[] = $fn($clip);
            } else {
                $clips[] = $clip;
            }
        }
        $layer['clips'] = $clips;
        $layers[] = $layer;
    }
    $board['audioLayers'] = $layers;

    return $board;
}

function gos_lyre_apply_trim(array $board, string $clipId, float $newStartSec, float $newEndSec): array
{
    $audio = gos_lyre_find_audio_clip($board, $clipId);
    if (is_array($audio)) {
        $trimmed = gos_lyre_trimmed_window($audio, $newStartSec, $newEndSec);

        return gos_lyre_map_audio_clip($board, $clipId, static fn ($c) => $trimmed);
    }
    if ($clipId === 'lc_movie' || gos_lyre_is_stitched_member($board, $clipId)) {
        gos_lyre_fail('movie_locked', 409);
    }
    $video = gos_lyre_find_video_clip($board, $clipId);
    if (!is_array($video)) {
        gos_lyre_fail('not_found', 404);
    }
    $fid = gos_lyre_str($video['linkedFrameId'] ?? '');
    if ($fid !== '') {
        if (gos_lyre_is_picture_locked($board, $fid)) {
            gos_lyre_fail('movie_locked', 409);
        }
        $sc = gos_lyre_clip_of($board, $fid);
        if ($sc === null) {
            gos_lyre_fail('not_found', 404);
        }
        $curEnd = gos_lyre_num($sc['start'] ?? 0) + gos_lyre_num($sc['length'] ?? 0);
        $next = $board;
        if (abs($newStartSec - gos_lyre_num($sc['start'] ?? 0)) > 1e-4) {
            $next = gos_lyre_trim_still_left($next, $fid, $newStartSec);
        }
        if (abs($newEndSec - $curEnd) > 1e-4) {
            $next = gos_lyre_trim_still_right($next, $fid, $newEndSec);
        }

        return $next;
    }
    if (gos_lyre_is_movie_locked($board, $clipId)) {
        gos_lyre_fail('movie_locked', 409);
    }
    $trimmed = gos_lyre_trimmed_window($video, $newStartSec, $newEndSec);

    return gos_lyre_map_video_clip($board, $clipId, static fn ($c) => $trimmed);
}

function gos_lyre_apply_move(array $board, string $clipId, float $startSec): array
{
    if ($clipId === 'lc_movie' || gos_lyre_is_stitched_member($board, $clipId)) {
        gos_lyre_fail('movie_locked', 409);
    }
    $moving = gos_lyre_find_video_clip($board, $clipId);
    if (!is_array($moving)) {
        gos_lyre_fail('not_found', 404);
    }
    $fid = gos_lyre_str($moving['linkedFrameId'] ?? '');
    if ($fid !== '') {
        if (gos_lyre_is_picture_locked($board, $fid)) {
            gos_lyre_fail('movie_locked', 409);
        }

        return gos_lyre_move_still_to($board, $fid, $startSec);
    }
    $next = $moving;
    $next['startSec'] = max(0.0, $startSec);
    foreach (gos_lyre_picture_video_clips($board) as $pic) {
        if (gos_lyre_overlaps($pic, $next)) {
            return $board;
        }
    }

    return gos_lyre_map_video_clip($board, $clipId, static fn ($c) => $next);
}

function gos_lyre_apply_delete(array $board, string $clipId): array
{
    if ($clipId === 'lc_movie') {
        gos_lyre_fail('movie_locked', 409);
    }
    if (gos_lyre_is_stitched_member($board, $clipId)) {
        gos_lyre_fail('movie_locked', 409);
    }
    $audio = gos_lyre_find_audio_clip($board, $clipId);
    if (is_array($audio)) {
        $layers = [];
        foreach (gos_lyre_layers($board, 'audioLayers') as $layer) {
            $clips = [];
            foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
                if (is_array($clip) && gos_lyre_str($clip['id'] ?? '') !== $clipId) {
                    $clips[] = $clip;
                }
            }
            $layer['clips'] = $clips;
            $layers[] = $layer;
        }
        $board['audioLayers'] = $layers;

        return $board;
    }
    $videoClip = gos_lyre_find_video_clip($board, $clipId);
    if (!is_array($videoClip)) {
        gos_lyre_fail('not_found', 404);
    }
    $wasMember = gos_lyre_clip_in_movie($board, $clipId);
    $layers = [];
    foreach (gos_lyre_layers($board, 'videoLayers') as $layer) {
        $clips = [];
        foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
            if (is_array($clip) && gos_lyre_str($clip['id'] ?? '') !== $clipId) {
                $clips[] = $clip;
            }
        }
        $layer['clips'] = $clips;
        $layers[] = $layer;
    }
    $board['videoLayers'] = $layers;
    $fid = gos_lyre_str($videoClip['linkedFrameId'] ?? '');
    if ($fid !== '') {
        $scenes = [];
        foreach (gos_lyre_arr($board['scenes'] ?? []) as $scene) {
            if (!is_array($scene)) {
                continue;
            }
            $frames = [];
            foreach (gos_lyre_arr($scene['frames'] ?? []) as $frame) {
                if (!is_array($frame)) {
                    continue;
                }
                if (gos_lyre_str($frame['id'] ?? '') === $fid) {
                    $frame['videoSrc'] = null;
                    $frame['videoDurationSec'] = null;
                    $frame['videoMuted'] = null;
                }
                $frames[] = $frame;
            }
            $scene['frames'] = $frames;
            $scenes[] = $scene;
        }
        $board['scenes'] = $scenes;
    }
    if ($wasMember) {
        $board['movie'] = null;
    }

    return gos_lyre_sync_linked_video($board);
}

function gos_lyre_add_still_to_scene(
    array $board,
    string $sceneId,
    string $src,
    string $caption,
    float $durationSec = 6.0,
    ?string $videoSrc = null,
    ?float $videoDurationSec = null,
): array {
    $frame = [
        'id' => gos_lyre_media_id('fr'),
        'src' => $src,
        'caption' => $caption,
        'durationSec' => max(GOS_LYRE_MIN_DUR, $durationSec),
    ];
    if ($videoSrc !== null && $videoSrc !== '') {
        $frame['videoSrc'] = $videoSrc;
        $frame['videoDurationSec'] = $videoDurationSec ?? $durationSec;
    }
    $found = false;
    $scenes = [];
    foreach (gos_lyre_arr($board['scenes'] ?? []) as $scene) {
        if (!is_array($scene)) {
            continue;
        }
        if (gos_lyre_str($scene['id'] ?? '') === $sceneId) {
            $found = true;
            $frames = gos_lyre_arr($scene['frames'] ?? []);
            $frames[] = $frame;
            $scene['frames'] = $frames;
        }
        $scenes[] = $scene;
    }
    if (!$found) {
        return $board;
    }
    $after = $board;
    $after['scenes'] = $scenes;
    $after['activeSceneId'] = $sceneId;

    return gos_lyre_finish_picture_edit($board, $after);
}

function gos_lyre_insert_picture_after(
    array $board,
    ?string $afterFrameId,
    string $src,
    string $caption,
    float $durationSec = 6.0,
    ?string $videoSrc = null,
    ?float $videoDurationSec = null,
): array {
    $id = gos_lyre_media_id('fr');
    $dur = max(GOS_LYRE_MIN_DUR, $durationSec);
    $frame = [
        'id' => $id,
        'src' => $src,
        'caption' => $caption,
        'durationSec' => $dur,
    ];
    if ($videoSrc !== null && $videoSrc !== '') {
        $frame['videoSrc'] = $videoSrc;
        $frame['videoDurationSec'] = $videoDurationSec ?? $dur;
    }
    $after = $afterFrameId !== null && $afterFrameId !== '' ? $afterFrameId : null;
    $destId = null;
    $inserted = $after === null;
    $scenes = gos_lyre_arr($board['scenes'] ?? []);
    if ($after === null) {
        $sceneId = gos_lyre_str($board['activeSceneId'] ?? '');
        if ($sceneId === '' && $scenes !== []) {
            $sceneId = gos_lyre_str($scenes[0]['id'] ?? '');
        }
        if ($sceneId === '') {
            return $board;
        }
        $destId = $sceneId;
        $out = [];
        foreach ($scenes as $scene) {
            if (!is_array($scene)) {
                continue;
            }
            if (gos_lyre_str($scene['id'] ?? '') === $sceneId) {
                $frames = gos_lyre_arr($scene['frames'] ?? []);
                $frames[] = $frame;
                $scene['frames'] = $frames;
            }
            $out[] = $scene;
        }
        $scenes = $out;
    } else {
        $out = [];
        foreach ($scenes as $scene) {
            if (!is_array($scene)) {
                continue;
            }
            $frames = gos_lyre_arr($scene['frames'] ?? []);
            $idx = -1;
            foreach ($frames as $i => $fr) {
                if (is_array($fr) && gos_lyre_str($fr['id'] ?? '') === $after) {
                    $idx = $i;
                    break;
                }
            }
            if ($idx >= 0) {
                $inserted = true;
                $destId = gos_lyre_str($scene['id'] ?? '');
                array_splice($frames, $idx + 1, 0, [$frame]);
                $scene['frames'] = array_values($frames);
            }
            $out[] = $scene;
        }
        $scenes = $out;
    }
    if (!$inserted) {
        return gos_lyre_add_still_to_scene($board, gos_lyre_str($board['activeSceneId'] ?? ''), $src, $caption, $durationSec, $videoSrc, $videoDurationSec);
    }
    $next = $board;
    $next['scenes'] = $scenes;
    $next['activeSceneId'] = $destId ?? gos_lyre_str($board['activeSceneId'] ?? '');

    return gos_lyre_finish_picture_edit($board, $next);
}

function gos_lyre_place_video_at(
    array $board,
    string $src,
    string $name,
    float $durationSec,
    float $playhead,
    ?string $posterSrc = null,
): array {
    $dur = max(GOS_LYRE_MIN_DUR, $durationSec);
    $poster = $posterSrc !== null && $posterSrc !== '' ? $posterSrc : null;
    if ($poster !== null) {
        $start = gos_lyre_leftover_start($board, $playhead);

        return gos_lyre_add_still_to_scene(
            $board,
            gos_lyre_scene_id_at($board, $start),
            $poster,
            $name,
            $dur,
            $src,
            $dur
        );
    }
    $clip = [
        'id' => gos_lyre_media_id('lc'),
        'src' => $src,
        'name' => $name,
        'startSec' => gos_lyre_leftover_start($board, $playhead),
        'durationSec' => $dur,
        'sourceDurationSec' => $dur,
    ];
    $layers = gos_lyre_layers($board, 'videoLayers');
    if ($layers === []) {
        $board['videoLayers'] = [[
            'id' => gos_lyre_media_id('ly'),
            'kind' => 'video',
            'name' => 'V1',
            'clips' => [$clip],
        ]];
    } else {
        $out = [];
        foreach ($layers as $i => $layer) {
            if ($i === 0) {
                $clips = gos_lyre_arr($layer['clips'] ?? []);
                $clips[] = $clip;
                $layer['clips'] = $clips;
            }
            $out[] = $layer;
        }
        $board['videoLayers'] = $out;
    }

    return gos_lyre_finish_picture_edit($board, $board);
}

function gos_lyre_insert_audio_clip(array $board, array $clip, ?int $preferLane): array
{
    $layers = gos_lyre_layers($board, 'audioLayers');
    if ($layers === []) {
        $layers[] = [
            'id' => gos_lyre_media_id('ly'),
            'kind' => 'audio',
            'name' => 'A1',
            'clips' => [],
        ];
    }
    $idx = max(0, $preferLane ?? 0);
    while (count($layers) <= $idx) {
        $n = count($layers) + 1;
        $layers[] = [
            'id' => gos_lyre_media_id('ly'),
            'kind' => 'audio',
            'name' => 'A' . $n,
            'clips' => [],
        ];
    }
    $dest = $layers[$idx];
    $clips = [];
    foreach (gos_lyre_arr($dest['clips'] ?? []) as $c) {
        if (is_array($c) && gos_lyre_str($c['id'] ?? '') !== gos_lyre_str($clip['id'] ?? '')) {
            $clips[] = $c;
        }
    }
    $clips[] = $clip;
    usort($clips, static function ($a, $b) {
        $sa = gos_lyre_num($a['startSec'] ?? 0);
        $sb = gos_lyre_num($b['startSec'] ?? 0);
        if ($sa === $sb) {
            return gos_lyre_str($a['id'] ?? '') <=> gos_lyre_str($b['id'] ?? '');
        }

        return $sa <=> $sb;
    });
    $dest['clips'] = $clips;
    $layers[$idx] = $dest;
    $board['audioLayers'] = $layers;

    return $board;
}

function gos_lyre_place_audio_at(
    array $board,
    string $src,
    string $name,
    float $durationSec,
    float $playhead,
    ?string $layerId = null,
): array {
    $start = max(0.0, $playhead);
    $dur = max(GOS_LYRE_MIN_DUR, $durationSec);
    $clip = [
        'id' => gos_lyre_media_id('lc'),
        'src' => $src,
        'name' => $name,
        'startSec' => $start,
        'durationSec' => $dur,
        'sourceDurationSec' => $dur,
        'volume' => 1.0,
    ];
    $prefer = 0;
    if ($layerId !== null && $layerId !== '') {
        foreach (gos_lyre_layers($board, 'audioLayers') as $i => $layer) {
            if (gos_lyre_str($layer['id'] ?? '') === $layerId) {
                $prefer = $i;
                break;
            }
        }
    }

    return gos_lyre_insert_audio_clip($board, $clip, $prefer);
}

function gos_lyre_attach_generated_video(
    array $board,
    string $frameId,
    string $videoSrc,
    float $durationSec,
    string $name = '',
): array {
    if (gos_lyre_is_picture_locked($board, $frameId)) {
        gos_lyre_fail('movie_locked', 409);
    }
    $dur = max(GOS_LYRE_MIN_DUR, $durationSec);
    $label = $name;
    $already = false;
    $scenes = [];
    foreach (gos_lyre_arr($board['scenes'] ?? []) as $scene) {
        if (!is_array($scene)) {
            continue;
        }
        $frames = [];
        foreach (gos_lyre_arr($scene['frames'] ?? []) as $frame) {
            if (!is_array($frame)) {
                continue;
            }
            if (gos_lyre_str($frame['id'] ?? '') === $frameId) {
                if (gos_lyre_str($frame['videoSrc'] ?? '') === $videoSrc) {
                    $already = true;
                }
                if ($label === '') {
                    $cap = gos_lyre_str($frame['caption'] ?? '');
                    $label = $cap !== '' ? $cap : $frameId;
                }
                if (!isset($frame['origVideoSrc']) || gos_lyre_str($frame['origVideoSrc']) === '') {
                    $frame['origVideoSrc'] = $frame['videoSrc'] ?? null;
                }
                if (!isset($frame['origVideoDurationSec'])) {
                    $frame['origVideoDurationSec'] = $frame['videoDurationSec'] ?? null;
                }
                $frame['videoSrc'] = $videoSrc;
                $frame['videoDurationSec'] = $dur;
                $frame['videoGenerating'] = false;
                $frame['videoGeneratingError'] = null;
            }
            $frames[] = $frame;
        }
        $scene['frames'] = $frames;
        $scenes[] = $scene;
    }
    $withScenes = $board;
    $withScenes['scenes'] = $scenes;
    $existing = null;
    foreach (gos_lyre_layers($withScenes, 'videoLayers') as $layer) {
        foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
            if (is_array($clip) && gos_lyre_str($clip['linkedFrameId'] ?? '') === $frameId) {
                $existing = $clip;
                break 2;
            }
        }
    }
    if ($already && is_array($existing) && gos_lyre_str($existing['src'] ?? '') === $videoSrc) {
        return $withScenes;
    }
    if (is_array($existing)) {
        $replaced = gos_lyre_map_video_clip($withScenes, gos_lyre_str($existing['id'] ?? ''), static function ($clip) use ($videoSrc, $dur, $frameId) {
            if (gos_lyre_str($clip['linkedFrameId'] ?? '') !== $frameId) {
                return $clip;
            }
            if (!isset($clip['origSrc']) || gos_lyre_str($clip['origSrc']) === '') {
                $clip['origSrc'] = $clip['src'] ?? null;
            }
            if (!isset($clip['origDurationSec'])) {
                $clip['origDurationSec'] = $clip['sourceDurationSec'] ?? $clip['durationSec'] ?? null;
            }
            $clip['src'] = $videoSrc;
            $clip['durationSec'] = $dur;
            $clip['sourceDurationSec'] = $dur;
            $clip['trimInSec'] = 0.0;

            return $clip;
        });

        return gos_lyre_finish_picture_edit($board, $replaced);
    }
    $clip = [
        'id' => gos_lyre_media_id('lc'),
        'src' => $videoSrc,
        'name' => $label !== '' ? $label : $frameId,
        'startSec' => 0.0,
        'durationSec' => $dur,
        'sourceDurationSec' => $dur,
        'linkedFrameId' => $frameId,
    ];
    $layers = gos_lyre_layers($withScenes, 'videoLayers');
    if ($layers === []) {
        $withScenes['videoLayers'] = [[
            'id' => gos_lyre_media_id('ly'),
            'kind' => 'video',
            'name' => 'V1',
            'clips' => [$clip],
        ]];
    } else {
        $out = [];
        foreach ($layers as $i => $layer) {
            if ($i === 0) {
                $clips = gos_lyre_arr($layer['clips'] ?? []);
                $clips[] = $clip;
                $layer['clips'] = $clips;
            }
            $out[] = $layer;
        }
        $withScenes['videoLayers'] = $out;
    }

    return gos_lyre_finish_picture_edit($board, $withScenes);
}

/**
 * @param array<string, mixed> $project
 * @param array<string, mixed> $board
 * @param list<array<string, mixed>> $activity
 * @return array<string, mixed>
 */
function gos_lyre_compact_snapshot(array $project, array $board, string $updatedAt, array $activity = []): array
{
    $brainstorm = gos_lyre_str($board['brainstorm'] ?? '');
    if (function_exists('mb_substr')) {
        $brainstorm = mb_substr($brainstorm, 0, GOS_LYRE_BRAINSTORM_MAX);
    } elseif (strlen($brainstorm) > GOS_LYRE_BRAINSTORM_MAX) {
        $brainstorm = substr($brainstorm, 0, GOS_LYRE_BRAINSTORM_MAX);
    }
    $resolved = gos_lyre_resolved_movie($board);
    $next = gos_lyre_next_stitch_target($board);
    $movieOut = null;
    if (is_array($resolved)) {
        $partsOut = [];
        foreach (gos_lyre_arr($resolved['parts'] ?? []) as $part) {
            if (!is_array($part)) {
                continue;
            }
            $partsOut[] = [
                'clip_id' => gos_lyre_str($part['clipId'] ?? ''),
                'src' => gos_lyre_str($part['src'] ?? ''),
                'duration_sec' => gos_lyre_num($part['durationSec'] ?? 0),
            ];
        }
        $movieOut = [
            'src' => gos_lyre_str($resolved['src'] ?? ''),
            'duration_sec' => gos_lyre_num($resolved['durationSec'] ?? 0),
            'play_duration_sec' => gos_lyre_movie_play_duration($resolved),
            'fps' => $resolved['fps'] ?? null,
            'parts' => $partsOut,
            'locked' => count(gos_lyre_parts($board)) > 1,
            'next_stitch_clip_id' => is_array($next) ? gos_lyre_str($next['id'] ?? '') : null,
        ];
    }
    $scenes = [];
    foreach (gos_lyre_arr($board['scenes'] ?? []) as $scene) {
        if (!is_array($scene)) {
            continue;
        }
        $frames = [];
        foreach (gos_lyre_arr($scene['frames'] ?? []) as $frame) {
            if (!is_array($frame)) {
                continue;
            }
            $fid = gos_lyre_str($frame['id'] ?? '');
            $clipId = null;
            foreach (gos_lyre_ordered_video_clips($board) as $clip) {
                if (gos_lyre_str($clip['linkedFrameId'] ?? '') === $fid) {
                    $clipId = gos_lyre_str($clip['id'] ?? '');
                    break;
                }
            }
            $frames[] = [
                'id' => $fid,
                'caption' => gos_lyre_str($frame['caption'] ?? ''),
                'src' => gos_lyre_str($frame['src'] ?? ''),
                'video_src' => gos_lyre_str($frame['videoSrc'] ?? '') !== '' ? gos_lyre_str($frame['videoSrc'] ?? '') : null,
                'duration_sec' => gos_lyre_num($frame['durationSec'] ?? 0),
                'locked' => gos_lyre_is_stitched_frame($board, $fid),
                'clip_id' => $clipId,
            ];
        }
        $scenes[] = [
            'id' => gos_lyre_str($scene['id'] ?? ''),
            'title' => gos_lyre_str($scene['title'] ?? ''),
            'logline' => gos_lyre_str($scene['logline'] ?? ''),
            'notes' => gos_lyre_str($scene['notes'] ?? ''),
            'dialogue' => gos_lyre_str($scene['dialogue'] ?? ''),
            'frames' => $frames,
        ];
    }
    $folders = [];
    foreach (gos_lyre_arr($board['refFolders'] ?? []) as $folder) {
        if (!is_array($folder)) {
            continue;
        }
        $images = [];
        foreach (gos_lyre_arr($folder['images'] ?? []) as $image) {
            if (!is_array($image)) {
                continue;
            }
            $images[] = [
                'id' => gos_lyre_str($image['id'] ?? ''),
                'caption' => gos_lyre_str($image['caption'] ?? ''),
                'src' => gos_lyre_str($image['src'] ?? ''),
            ];
        }
        $folders[] = [
            'id' => gos_lyre_str($folder['id'] ?? ''),
            'name' => gos_lyre_str($folder['name'] ?? ''),
            'images' => $images,
        ];
    }
    $videoClips = [];
    foreach (gos_lyre_ordered_video_clips($board) as $clip) {
        $cid = gos_lyre_str($clip['id'] ?? '');
        $videoClips[] = [
            'id' => $cid,
            'src' => gos_lyre_str($clip['src'] ?? ''),
            'start_sec' => gos_lyre_num($clip['startSec'] ?? 0),
            'duration_sec' => gos_lyre_num($clip['durationSec'] ?? 0),
            'linked_frame_id' => gos_lyre_str($clip['linkedFrameId'] ?? '') !== '' ? gos_lyre_str($clip['linkedFrameId'] ?? '') : null,
            'in_movie' => gos_lyre_clip_in_movie($board, $cid),
        ];
    }
    $audioClips = [];
    foreach (gos_lyre_layers($board, 'audioLayers') as $layer) {
        $lid = gos_lyre_str($layer['id'] ?? '');
        foreach (gos_lyre_arr($layer['clips'] ?? []) as $clip) {
            if (!is_array($clip)) {
                continue;
            }
            $audioClips[] = [
                'id' => gos_lyre_str($clip['id'] ?? ''),
                'layer_id' => $lid,
                'src' => gos_lyre_str($clip['src'] ?? ''),
                'start_sec' => gos_lyre_num($clip['startSec'] ?? 0),
                'duration_sec' => gos_lyre_num($clip['durationSec'] ?? 0),
            ];
        }
    }

    return [
        'ok' => true,
        'project' => [
            'id' => gos_lyre_str($project['id'] ?? ''),
            'name' => gos_lyre_str($project['name'] ?? ''),
            'board_id' => gos_lyre_str($project['board_id'] ?? ''),
            'is_odysseus' => gos_lyre_is_odysseus_project($project),
        ],
        'updated_at' => $updatedAt,
        'title' => gos_lyre_str($board['title'] ?? ''),
        'brainstorm' => $brainstorm,
        'active_scene_id' => gos_lyre_str($board['activeSceneId'] ?? ''),
        'movie' => $movieOut,
        'scenes' => $scenes,
        'folders' => $folders,
        'video_clips' => $videoClips,
        'audio_clips' => $audioClips,
        'activity' => $activity,
    ];
}

/**
 * @return array<string, mixed>
 */
function gos_lyre_head_payload(array $board, string $boardId, string $updatedAt, int $activityBytes): array
{
    $next = gos_lyre_next_stitch_target($board);

    return [
        'ok' => true,
        'board_id' => $boardId,
        'updated_at' => $updatedAt,
        'activity_bytes' => $activityBytes,
        'movie_locked' => count(gos_lyre_parts($board)) > 1,
        'next_stitch_clip_id' => is_array($next) ? gos_lyre_str($next['id'] ?? '') : null,
    ];
}

/**
 * @param array<string, mixed> $access
 * @param array<string, mixed> $body
 * @return array<string, mixed>
 */
function gos_lyre_director_resolve(array $access, array $body, bool $mutating): array
{
    $userId = gos_lyre_user_id($access);
    $isMcp = gos_lyre_is_mcp($access);
    $projectId = trim(gos_lyre_str($body['project_id'] ?? ''));
    $boardId = trim(gos_lyre_str($body['board_id'] ?? ''));
    if ($boardId === '') {
        $boardId = trim(gos_lyre_str($body['id'] ?? ''));
    }
    if ($isMcp && $mutating) {
        if ($projectId === '' && $boardId === '') {
            gos_lyre_fail('project_required', 400);
        }
        if ($boardId !== '' && $boardId === gos_lyre_odysseus_board_id()) {
            gos_lyre_fail('odysseus_protected', 403);
        }
    }
    $row = null;
    if ($projectId !== '') {
        $row = gos_lyre_find_project_by_id($userId, $projectId);
    }
    if ($row === null && $boardId !== '') {
        $row = gos_lyre_find_project_by_board($userId, $boardId);
    }
    if ($row === null && !$mutating && $isMcp) {
        $state = function_exists('gos_lyre_mcp_user_state') ? gos_lyre_mcp_user_state($userId) : [];
        $openProj = trim(gos_lyre_str($state['mcp_open_project_id'] ?? ''));
        $openBoard = trim(gos_lyre_str($state['mcp_open_board_id'] ?? ''));
        if ($openProj !== '') {
            $row = gos_lyre_find_project_by_id($userId, $openProj);
        }
        if ($row === null && $openBoard !== '') {
            $row = gos_lyre_find_project_by_board($userId, $openBoard);
        }
    }
    if ($row === null) {
        if ($projectId === '' && $boardId === '') {
            gos_lyre_fail('project_required', 400);
        }
        gos_lyre_fail('not_found', 404);
    }
    if ($isMcp && $mutating && gos_lyre_is_odysseus_project($row)) {
        gos_lyre_fail('odysseus_protected', 403);
    }
    $bid = gos_lyre_str($row['board_id'] ?? '');
    if (gos_lyre_safe_board_id($bid) === null) {
        gos_lyre_fail('invalid_board_id', 400);
    }

    return $row;
}

/**
 * @param array<string, mixed> $access
 * @param array<string, mixed> $body
 * @param array<string, mixed>|callable $activity
 * @return array<string, mixed>
 */
function gos_lyre_director_mutate(array $access, array $body, callable $mutator, array|callable $activity): array
{
    $row = gos_lyre_director_resolve($access, $body, true);
    $boardId = gos_lyre_str($row['board_id'] ?? '');

    return gos_lyre_with_board_lock($boardId, function () use ($access, $body, $row, $boardId, $mutator, $activity) {
        $pgRow = gos_lyre_pg_select_id($boardId);
        if (!is_array($pgRow)) {
            gos_lyre_fail('not_found', 404);
        }
        $board = gos_lyre_payload_array($pgRow['payload'] ?? null);
        $expected = trim(gos_lyre_str($body['expected_updated_at'] ?? ''));
        if ($expected === '') {
            $expected = (string) ($pgRow['updated_at'] ?? '');
        }
        $next = $mutator($board, $row, $body);
        if (!is_array($next)) {
            gos_lyre_fail('invalid_board', 400);
        }
        $stamp = (string) ($pgRow['updated_at'] ?? '');
        if ($next === $board) {
            return [
                'ok' => true,
                'board_id' => $boardId,
                'updated_at' => $stamp,
                'noop' => true,
            ];
        }
        $cas = gos_lyre_pg_update_cas(gos_lyre_pg_maybe(), $boardId, $next, $expected);
        gos_lyre_touch_project(gos_lyre_user_id($access), gos_lyre_str($row['id'] ?? ''));
        $act = is_callable($activity) ? $activity($next, $row) : $activity;
        if (!is_array($act)) {
            $act = [];
        }
        $act['ts'] = (int) round(microtime(true) * 1000);
        $act['projectId'] = gos_lyre_str($row['id'] ?? '');
        $act['actor'] = gos_lyre_is_mcp($access) ? 'bot' : 'phone';
        if (!isset($act['summary'])) {
            $act['summary'] = gos_lyre_str($act['type'] ?? 'edit');
        }
        gos_lyre_activity_append_line($boardId, $act);

        return [
            'ok' => true,
            'board_id' => $boardId,
            'updated_at' => (string) $cas['updated_at'],
            'activity' => $act,
        ];
    });
}

function gos_lyre_load_owned_board(array $access, array $body, bool $mutating): array
{
    $row = gos_lyre_director_resolve($access, $body, $mutating);
    $boardId = gos_lyre_str($row['board_id'] ?? '');
    $pgRow = gos_lyre_pg_select_id($boardId);
    if (!is_array($pgRow)) {
        gos_lyre_fail('not_found', 404);
    }

    return [
        'row' => $row,
        'pg' => $pgRow,
        'board' => gos_lyre_payload_array($pgRow['payload'] ?? null),
        'board_id' => $boardId,
        'updated_at' => (string) ($pgRow['updated_at'] ?? ''),
    ];
}

/** @return array<string, mixed> */
function gos_lyre_director_head(array $access, array $body): array
{
    $loaded = gos_lyre_load_owned_board($access, $body, false);

    return gos_lyre_head_payload(
        $loaded['board'],
        $loaded['board_id'],
        $loaded['updated_at'],
        gos_lyre_activity_bytes($loaded['board_id'])
    );
}

/** @return array<string, mixed> */
function gos_lyre_director_snapshot(array $access, array $body): array
{
    $loaded = gos_lyre_load_owned_board($access, $body, false);
    $limit = (int) ($body['activity_limit'] ?? 20);
    if ($limit < 1) {
        $limit = 20;
    }
    $activity = gos_lyre_activity_read($loaded['board_id'], $limit, null);

    return gos_lyre_compact_snapshot($loaded['row'], $loaded['board'], $loaded['updated_at'], $activity);
}

/** @return array<string, mixed> */
function gos_lyre_director_activity(array $access, array $body): array
{
    $loaded = gos_lyre_load_owned_board($access, $body, false);
    $limit = (int) ($body['limit'] ?? 50);
    $before = $body['before_ts'] ?? null;
    $beforeTs = is_numeric($before) ? (int) $before : null;

    return [
        'ok' => true,
        'lines' => gos_lyre_activity_read($loaded['board_id'], $limit, $beforeTs),
    ];
}

/** @return array<string, mixed> */
function gos_lyre_normalize_activity_line(array $raw, string $actorDefault): array
{
    $summary = trim(gos_lyre_str($raw['summary'] ?? $raw['text'] ?? ''));
    if ($summary === '') {
        gos_lyre_fail('text_required', 400);
    }
    $ts = (int) ($raw['ts'] ?? 0);
    if ($ts <= 0) {
        $ts = (int) round(microtime(true) * 1000);
    }
    $line = [
        'ts' => $ts,
        'type' => gos_lyre_str($raw['type'] ?? 'edit') !== '' ? gos_lyre_str($raw['type'] ?? 'edit') : 'edit',
        'projectId' => gos_lyre_str($raw['projectId'] ?? ''),
        'summary' => $summary,
        'actor' => $actorDefault,
    ];
    foreach (['sceneId', 'frameId', 'clipId', 'op'] as $k) {
        $v = gos_lyre_str($raw[$k] ?? '');
        if ($v !== '') {
            $line[$k] = $v;
        }
    }

    return $line;
}

/** @return array<string, mixed> */
function gos_lyre_director_activity_append(array $access, array $body): array
{
    $row = gos_lyre_director_resolve($access, $body, true);
    $boardId = gos_lyre_str($row['board_id'] ?? '');
    $actor = gos_lyre_is_mcp($access) ? 'bot' : 'phone';
    $rawLines = [];
    if (isset($body['lines']) && is_array($body['lines'])) {
        $rawLines = $body['lines'];
    } else {
        $rawLines = [$body];
    }
    $written = [];
    foreach ($rawLines as $raw) {
        if (!is_array($raw)) {
            continue;
        }
        $line = gos_lyre_normalize_activity_line($raw, $actor);
        $line['actor'] = $actor;
        if ($line['projectId'] === '') {
            $line['projectId'] = gos_lyre_str($row['id'] ?? '');
        }
        gos_lyre_activity_append_line($boardId, $line);
        $written[] = $line;
    }
    $pg = gos_lyre_pg_select_id($boardId);

    return [
        'ok' => true,
        'board_id' => $boardId,
        'updated_at' => is_array($pg) ? (string) ($pg['updated_at'] ?? '') : '',
        'activity' => $written[array_key_last($written)] ?? null,
        'appended' => count($written),
    ];
}

/** @return array<string, mixed> */
function gos_lyre_director_folder(array $access, array $body): array
{
    $path = trim(str_replace('\\', '/', gos_lyre_str($body['path'] ?? $body['name'] ?? '')));
    $path = trim($path, '/');
    if ($path === '' || str_contains($path, '..')) {
        gos_lyre_fail('invalid_path', 400);
    }
    $folderIdIn = trim(gos_lyre_str($body['folder_id'] ?? ''));
    $created = false;
    $folderId = '';
    $out = gos_lyre_director_mutate($access, $body, function (array $board) use ($path, $folderIdIn, &$created, &$folderId) {
        $folders = gos_lyre_arr($board['refFolders'] ?? []);
        $hit = null;
        foreach ($folders as $i => $folder) {
            if (!is_array($folder)) {
                continue;
            }
            if ($folderIdIn !== '' && gos_lyre_str($folder['id'] ?? '') === $folderIdIn) {
                $hit = $i;
                break;
            }
            if (gos_lyre_str($folder['name'] ?? '') === $path) {
                $hit = $i;
                break;
            }
        }
        if ($hit === null) {
            $folderId = $folderIdIn !== '' ? $folderIdIn : gos_lyre_media_id('rf');
            $folders[] = [
                'id' => $folderId,
                'name' => $path,
                'images' => [],
            ];
            $created = true;
        } else {
            $folderId = gos_lyre_str($folders[$hit]['id'] ?? '');
            $folders[$hit]['name'] = $path;
        }
        $board['refFolders'] = $folders;
        $board['activeFolderId'] = $folderId;

        return $board;
    }, [
        'type' => 'folder',
        'summary' => 'Folder · ' . $path,
        'op' => 'folder',
    ]);
    $out['folder_id'] = $folderId;
    $out['name'] = $path;
    $out['created'] = $created;

    return $out;
}

/** @return array<string, mixed> */
function gos_lyre_director_scene(array $access, array $body): array
{
    $sceneIdIn = trim(gos_lyre_str($body['scene_id'] ?? ''));
    $sceneId = $sceneIdIn;
    $out = gos_lyre_director_mutate($access, $body, function (array $board) use ($body, $sceneIdIn, &$sceneId) {
        $scenes = gos_lyre_arr($board['scenes'] ?? []);
        if ($sceneIdIn === '') {
            $sceneId = 'sc_' . bin2hex(random_bytes(6));
            $scene = [
                'id' => $sceneId,
                'title' => gos_lyre_str($body['title'] ?? 'Scene'),
                'book' => gos_lyre_str($body['book'] ?? ''),
                'durationTargetSec' => 0,
                'logline' => gos_lyre_str($body['logline'] ?? ''),
                'dialogue' => gos_lyre_str($body['dialogue'] ?? ''),
                'notes' => gos_lyre_str($body['notes'] ?? ''),
                'frames' => [],
            ];
            $scenes[] = $scene;
            $board['scenes'] = $scenes;
            $board['activeSceneId'] = $sceneId;

            return $board;
        }
        $found = false;
        $outScenes = [];
        foreach ($scenes as $scene) {
            if (!is_array($scene)) {
                continue;
            }
            if (gos_lyre_str($scene['id'] ?? '') === $sceneIdIn) {
                $found = true;
                foreach (['title', 'logline', 'notes', 'dialogue', 'book'] as $field) {
                    if (array_key_exists($field, $body)) {
                        $scene[$field] = gos_lyre_str($body[$field]);
                    }
                }
            }
            $outScenes[] = $scene;
        }
        if (!$found) {
            gos_lyre_fail('not_found', 404);
        }
        $board['scenes'] = $outScenes;

        return $board;
    }, function () use (&$sceneId, $sceneIdIn) {
        return [
            'type' => 'scene',
            'sceneId' => $sceneId,
            'summary' => $sceneIdIn === '' ? 'Scene created' : 'Scene updated',
            'op' => 'scene',
        ];
    });
    $out['scene_id'] = $sceneId;

    return $out;
}

/** @return array<string, mixed> */
function gos_lyre_director_place(array $access, array $body): array
{
    $kind = strtolower(trim(gos_lyre_str($body['kind'] ?? '')));
    $src = trim(gos_lyre_str($body['src'] ?? ''));
    if ($src === '') {
        gos_lyre_fail('src_required', 400);
    }
    if (!in_array($kind, ['still', 'video', 'audio'], true)) {
        gos_lyre_fail('invalid_kind', 400);
    }
    $name = gos_lyre_str($body['name'] ?? $kind);
    $duration = gos_lyre_num($body['duration_sec'] ?? 6.0, 6.0);

    return gos_lyre_director_mutate($access, $body, function (array $board) use ($body, $kind, $src, $name, $duration) {
        if ($kind === 'still') {
            $sceneId = trim(gos_lyre_str($body['scene_id'] ?? ''));
            $frameId = trim(gos_lyre_str($body['frame_id'] ?? ''));
            if ($frameId !== '') {
                return gos_lyre_insert_picture_after($board, $frameId, $src, $name, $duration);
            }
            if ($sceneId === '') {
                $sceneId = gos_lyre_str($board['activeSceneId'] ?? '');
            }

            return gos_lyre_add_still_to_scene($board, $sceneId, $src, $name, $duration);
        }
        if ($kind === 'video') {
            $poster = trim(gos_lyre_str($body['poster_src'] ?? ''));
            $at = array_key_exists('at_sec', $body) ? gos_lyre_num($body['at_sec']) : gos_lyre_leftover_start($board, 0.0);

            return gos_lyre_place_video_at($board, $src, $name, $duration, $at, $poster !== '' ? $poster : null);
        }
        $at = array_key_exists('at_sec', $body) ? gos_lyre_num($body['at_sec']) : 0.0;
        $layerId = trim(gos_lyre_str($body['layer_id'] ?? ''));

        return gos_lyre_place_audio_at($board, $src, $name, $duration, $at, $layerId !== '' ? $layerId : null);
    }, [
        'type' => 'place',
        'summary' => 'Placed ' . $kind . ' · ' . $name,
        'op' => 'place',
        'frameId' => gos_lyre_str($body['frame_id'] ?? ''),
        'clipId' => gos_lyre_str($body['clip_id'] ?? ''),
    ]);
}

/** @return array<string, mixed> */
function gos_lyre_director_trim(array $access, array $body): array
{
    $clipId = trim(gos_lyre_str($body['clip_id'] ?? ''));
    if ($clipId === '') {
        gos_lyre_fail('clip_id_required', 400);
    }
    $start = gos_lyre_num($body['start_sec'] ?? $body['new_start_sec'] ?? 0);
    $end = gos_lyre_num($body['end_sec'] ?? $body['new_end_sec'] ?? ($start + GOS_LYRE_MIN_DUR));

    return gos_lyre_director_mutate($access, $body, function (array $board) use ($clipId, $start, $end) {
        return gos_lyre_apply_trim($board, $clipId, $start, $end);
    }, [
        'type' => 'trim',
        'clipId' => $clipId,
        'frameId' => gos_lyre_str($body['frame_id'] ?? ''),
        'summary' => 'Trim · ' . $clipId,
        'op' => 'trim',
    ]);
}

/** @return array<string, mixed> */
function gos_lyre_director_move(array $access, array $body): array
{
    $clipId = trim(gos_lyre_str($body['clip_id'] ?? ''));
    if ($clipId === '') {
        gos_lyre_fail('clip_id_required', 400);
    }
    $start = gos_lyre_num($body['start_sec'] ?? $body['at_sec'] ?? 0);

    return gos_lyre_director_mutate($access, $body, function (array $board) use ($clipId, $start) {
        return gos_lyre_apply_move($board, $clipId, $start);
    }, [
        'type' => 'move',
        'clipId' => $clipId,
        'summary' => 'Move · ' . $clipId,
        'op' => 'move',
    ]);
}

/** @return array<string, mixed> */
function gos_lyre_director_delete(array $access, array $body): array
{
    $clipId = trim(gos_lyre_str($body['clip_id'] ?? ''));
    if ($clipId === '') {
        gos_lyre_fail('clip_id_required', 400);
    }

    return gos_lyre_director_mutate($access, $body, function (array $board) use ($clipId) {
        return gos_lyre_apply_delete($board, $clipId);
    }, [
        'type' => 'delete',
        'clipId' => $clipId,
        'summary' => 'Delete · ' . $clipId,
        'op' => 'delete',
    ]);
}

/** @return array<string, mixed> */
function gos_lyre_save_board(array $access, array $body): array
{
    $userId = gos_lyre_user_id($access);
    $id = trim(gos_lyre_str($body['id'] ?? ''));
    $boardId = trim(gos_lyre_str($body['board_id'] ?? ''));
    $row = null;
    if ($id !== '') {
        $row = gos_lyre_find_project_by_id($userId, $id);
        if ($row === null) {
            $row = gos_lyre_find_project_by_board($userId, $id);
        }
    }
    if ($row === null && $boardId !== '') {
        $row = gos_lyre_find_project_by_board($userId, $boardId);
    }
    if ($row === null) {
        gos_lyre_fail('not_found', 404);
    }
    $expected = trim(gos_lyre_str($body['expected_updated_at'] ?? ''));
    if ($expected === '') {
        gos_lyre_fail('expected_updated_at_required', 400);
    }
    $data = $body['data'] ?? null;
    if (is_string($data) && $data !== '') {
        $data = json_decode($data, true);
    }
    if (!is_array($data)) {
        gos_lyre_fail('data_required', 400);
    }
    $target = gos_lyre_str($row['board_id'] ?? '');
    if (gos_lyre_safe_board_id($target) === null) {
        gos_lyre_fail('invalid_board_id', 400);
    }

    return gos_lyre_with_board_lock($target, function () use ($userId, $row, $target, $data, $expected) {
        $cas = gos_lyre_pg_update_cas(gos_lyre_pg_maybe(), $target, $data, $expected);
        gos_lyre_touch_project($userId, gos_lyre_str($row['id'] ?? ''));

        return [
            'ok' => true,
            'board_id' => $target,
            'updated_at' => (string) $cas['updated_at'],
        ];
    });
}
