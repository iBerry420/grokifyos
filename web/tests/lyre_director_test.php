<?php

declare(strict_types=1);

/**
 * Director decision table, CAS, snapshot compactness, safeBoardId.
 * Run: php web/tests/lyre_director_test.php
 */

$tmpRoot = sys_get_temp_dir() . '/lyre-director-' . bin2hex(random_bytes(4));
@mkdir($tmpRoot . '/locks', 0777, true);
@mkdir($tmpRoot . '/activity', 0777, true);
putenv('GOS_LYRE_LOCKS_DIR=' . $tmpRoot . '/locks');
$_ENV['GOS_LYRE_LOCKS_DIR'] = $tmpRoot . '/locks';
putenv('GOS_LYRE_ACTIVITY_DIR=' . $tmpRoot . '/activity');
$_ENV['GOS_LYRE_ACTIVITY_DIR'] = $tmpRoot . '/activity';
putenv('GOS_LYRE_TEST_STORE=1');
$_ENV['GOS_LYRE_TEST_STORE'] = '1';

define('GOS_SKIP_SESSION', true);
define('GOS_LYRE_NO_ROUTE', true);

require_once dirname(__DIR__) . '/api/lyre.php';

$fails = 0;

function expect_true(bool $cond, string $msg): void
{
    global $fails;
    if (!$cond) {
        $fails++;
        fwrite(STDERR, "FAIL: {$msg}\n");
    }
}

function expect_eq(mixed $got, mixed $want, string $msg): void
{
    expect_true($got === $want, $msg . ' got=' . var_export($got, true) . ' want=' . var_export($want, true));
}

function load_fixture(string $name): array
{
    $path = __DIR__ . '/fixtures/lyre/' . $name;
    $raw = file_get_contents($path);
    $data = json_decode((string) $raw, true);
    if (!is_array($data)) {
        throw new RuntimeException('fixture ' . $name);
    }

    return $data;
}

function catch_error(callable $fn): ?GosLyreException
{
    try {
        $fn();

        return null;
    } catch (GosLyreException $e) {
        return $e;
    }
}

function unlink_clip(array $board, string $clipId): array
{
    $layers = [];
    foreach ($board['videoLayers'] as $layer) {
        $clips = [];
        foreach ($layer['clips'] as $clip) {
            if (($clip['id'] ?? '') === $clipId) {
                unset($clip['linkedFrameId']);
            }
            $clips[] = $clip;
        }
        $layer['clips'] = $clips;
        $layers[] = $layer;
    }
    $board['videoLayers'] = $layers;

    return $board;
}

function add_leftover(array $board, string $id, float $start, float $dur): array
{
    $board['videoLayers'][0]['clips'][] = [
        'id' => $id,
        'src' => 'boards/lyre/clips/' . $id . '.mp4',
        'name' => $id,
        'startSec' => $start,
        'durationSec' => $dur,
        'sourceDurationSec' => $dur,
    ];

    return $board;
}

$unstitched = load_fixture('unstitched.json');
$stitched = load_fixture('stitched.json');

expect_eq(gos_lyre_safe_board_id('lyre_phone_abc-def'), 'lyre_phone_abc-def', 'safe uuid hyphens');
expect_eq(gos_lyre_safe_board_id('lyre'), 'lyre', 'safe odysseus');
expect_eq(gos_lyre_safe_board_id('../etc/passwd'), null, 'safe rejects ../');
expect_eq(gos_lyre_safe_board_id(''), null, 'safe empty');
expect_eq(gos_lyre_safe_board_id('.'), null, 'safe dot');
expect_eq(gos_lyre_safe_board_id('..'), null, 'safe dotdot');
expect_eq(gos_lyre_safe_board_id('foo/../bar'), null, 'safe rejects slash traversal');

$ordered = gos_lyre_ordered_video_clips($unstitched);
expect_eq(array_map(static fn ($c) => $c['id'], $ordered), ['lc_a', 'lc_b'], 'ordered clips skip hold still');
expect_true(gos_lyre_clip_in_movie($unstitched, 'lc_a'), 'unstitched first clipInMovie');
expect_true(!gos_lyre_clip_in_movie($unstitched, 'lc_b'), 'unstitched leftover not clipInMovie');
expect_true(!gos_lyre_is_stitched_member($unstitched, 'lc_a'), 'unstitched not stitched member');
expect_true(gos_lyre_is_stitched_member($stitched, 'lc_a'), 'stitched member a');
expect_true(gos_lyre_is_stitched_member($stitched, 'lc_b'), 'stitched member b');
expect_true(!gos_lyre_is_picture_locked($unstitched, 'fr_a'), 'unstitched picture unlocked');
expect_true(gos_lyre_is_picture_locked($stitched, 'fr_a'), 'stitched picture locked');
expect_eq(gos_lyre_next_stitch_target($unstitched)['id'] ?? null, 'lc_b', 'next stitch lc_b');
expect_eq(gos_lyre_next_stitch_target($stitched), null, 'stitched has no next');
expect_eq(gos_lyre_leftover_start($unstitched, 0.0), 4.0, 'leftover starts after unstitched movie');

$err = catch_error(static fn () => gos_lyre_apply_trim($unstitched, 'lc_movie', 0.0, 1.0));
expect_eq($err?->error, 'movie_locked', 'trim lc_movie locked');
expect_eq($err?->http, 409, 'trim lc_movie 409');
$err = catch_error(static fn () => gos_lyre_apply_move($unstitched, 'lc_movie', 1.0));
expect_eq($err?->error, 'movie_locked', 'move lc_movie locked');
$err = catch_error(static fn () => gos_lyre_apply_trim($stitched, 'lc_a', 0.0, 2.0));
expect_eq($err?->error, 'movie_locked', 'trim stitched member locked');
$err = catch_error(static fn () => gos_lyre_apply_move($stitched, 'lc_b', 0.0));
expect_eq($err?->error, 'movie_locked', 'move stitched member locked');

$trimmed = gos_lyre_apply_trim($unstitched, 'lc_a', 0.0, 2.0);
expect_eq($trimmed['scenes'][0]['frames'][0]['durationSec'], 2.0, 'trim linked leftover dual-write duration');
$moved = gos_lyre_apply_move($unstitched, 'lc_a', 8.0);
$movedIds = array_map(static fn ($f) => $f['id'] ?? '', $moved['scenes'][0]['frames']);
expect_true($movedIds !== ['fr_a', 'fr_hold', 'fr_b'], 'move linked leftover reorders stills');
expect_true(in_array('fr_a', $movedIds, true), 'moved still remains');

$err = catch_error(static fn () => gos_lyre_apply_trim($stitched, 'lc_a', 0.0, 2.0));
expect_eq($err?->error, 'movie_locked', 'trim linked leftover stitched locked');

$unlinkedFirst = unlink_clip($unstitched, 'lc_a');
$err = catch_error(static fn () => gos_lyre_apply_trim($unlinkedFirst, 'lc_a', 0.0, 2.0));
expect_eq($err?->error, 'movie_locked', 'trim leftover-only clipInMovie locked');

$loose = add_leftover($unstitched, 'lc_extra', 20.0, 2.0);
$win = gos_lyre_apply_trim($loose, 'lc_extra', 20.5, 21.5);
$extra = null;
foreach ($win['videoLayers'][0]['clips'] as $c) {
    if (($c['id'] ?? '') === 'lc_extra') {
        $extra = $c;
    }
}
expect_true(is_array($extra), 'leftover-only trim found clip');
expect_true(abs((float) ($extra['durationSec'] ?? 0) - 1.0) < 0.05, 'leftover-only trim JSON window');

$movedLoose = gos_lyre_apply_move($loose, 'lc_extra', 18.0);
$extraM = null;
foreach ($movedLoose['videoLayers'][0]['clips'] as $c) {
    if (($c['id'] ?? '') === 'lc_extra') {
        $extraM = $c;
    }
}
expect_eq($extraM['startSec'] ?? null, 18.0, 'move leftover-only no movie lock');

$picStitched = gos_lyre_picture_video_clips($stitched);
expect_eq(array_map(static fn ($c) => $c['id'] ?? '', $picStitched), ['lc_movie'], 'stitched picture lane emits lc_movie');
expect_eq($picStitched[0]['startSec'] ?? null, 0.0, 'lc_movie starts at 0');
expect_true(gos_lyre_num($picStitched[0]['durationSec'] ?? 0) >= 6.9, 'lc_movie spans play duration');
$underMovie = add_leftover($stitched, 'lc_extra', 20.0, 2.0);
$blocked = gos_lyre_apply_move($underMovie, 'lc_extra', 1.0);
$extraStart = null;
foreach ($blocked['videoLayers'][0]['clips'] as $c) {
    if (($c['id'] ?? '') === 'lc_extra') {
        $extraStart = $c['startSec'] ?? null;
    }
}
expect_eq($extraStart, 20.0, 'leftover-only move under stitched movie is no-op');

$err = catch_error(static fn () => gos_lyre_apply_delete($unstitched, 'lc_movie'));
expect_eq($err?->error, 'movie_locked', 'delete lc_movie locked');
$err = catch_error(static fn () => gos_lyre_apply_delete($stitched, 'lc_a'));
expect_eq($err?->error, 'movie_locked', 'delete stitched member locked (pop only)');

$delFirst = gos_lyre_apply_delete($unstitched, 'lc_a');
expect_true(array_key_exists('movie', $delFirst) && $delFirst['movie'] === null, 'delete unstitched first clip nulls movie');
$hasA = false;
foreach ($delFirst['videoLayers'][0]['clips'] as $c) {
    if (($c['id'] ?? '') === 'lc_a') {
        $hasA = true;
    }
}
expect_true(!$hasA, 'deleted clip gone');

$delLoose = gos_lyre_apply_delete($loose, 'lc_extra');
$hasExtra = false;
foreach ($delLoose['videoLayers'][0]['clips'] as $c) {
    if (($c['id'] ?? '') === 'lc_extra') {
        $hasExtra = true;
    }
}
expect_true(!$hasExtra, 'delete leftover unlinked');

$delB = gos_lyre_apply_delete($unstitched, 'lc_b');
$frB = null;
foreach ($delB['scenes'][0]['frames'] as $fr) {
    if (($fr['id'] ?? '') === 'fr_b') {
        $frB = $fr;
    }
}
expect_true($frB !== null && empty($frB['videoSrc']), 'delete linked leftover clears frame.videoSrc');

$err = catch_error(static fn () => gos_lyre_attach_generated_video($stitched, 'fr_a', 'boards/lyre/clips/new.mp4', 4.0));
expect_eq($err?->error, 'movie_locked', 'attach stitched picture locked');
$attached = gos_lyre_attach_generated_video($unstitched, 'fr_hold', 'boards/lyre/clips/new.mp4', 2.0);
$hold = null;
foreach ($attached['scenes'][0]['frames'] as $fr) {
    if (($fr['id'] ?? '') === 'fr_hold') {
        $hold = $fr;
    }
}
expect_eq($hold['videoSrc'] ?? null, 'boards/lyre/clips/new.mp4', 'attach unlocked frame');

$proj = [
    'id' => str_repeat('1', 32),
    'name' => 'The Return',
    'board_id' => 'lyre_phone_demo',
    'is_odysseus' => 0,
];
$long = $unstitched;
$long['brainstorm'] = str_repeat('x', 5000);
$long['title'] = 'The Return';
$snap = gos_lyre_compact_snapshot($proj, $long, 'stamp-1', []);
expect_eq($snap['ok'] ?? null, true, 'snapshot ok');
expect_true(!isset($snap['payload']), 'snapshot has no payload key');
expect_true(!isset($snap['videoLayers']), 'snapshot is compact (no videoLayers dump)');
expect_true(!isset($snap['data']), 'snapshot has no data dump');
expect_true(strlen((string) $snap['brainstorm']) <= 4096, 'brainstorm truncated to 4k');
expect_eq($snap['movie']['next_stitch_clip_id'] ?? null, 'lc_b', 'snapshot next stitch');
expect_eq($snap['movie']['locked'] ?? null, false, 'unstitched movie not locked');
$snapSt = gos_lyre_compact_snapshot($proj, $stitched, 'stamp-2', []);
expect_eq($snapSt['movie']['locked'] ?? null, true, 'stitched movie locked');
$frLocked = false;
foreach ($snapSt['scenes'][0]['frames'] as $fr) {
    if (($fr['id'] ?? '') === 'fr_a') {
        $frLocked = (bool) ($fr['locked'] ?? false);
    }
}
expect_true($frLocked, 'snapshot frame locked = isStitchedFrame');
$head = gos_lyre_head_payload($stitched, 'lyre_phone_demo', 'stamp-2', 12);
expect_eq($head['movie_locked'] ?? null, true, 'head movie_locked when parts>1');
expect_true(!isset($head['payload']) && !isset($head['data']), 'head does not serialize payload');
expect_eq($head['activity_bytes'] ?? null, 12, 'head activity_bytes');
$headU = gos_lyre_head_payload($unstitched, 'lyre_phone_demo', 'stamp-1', 0);
expect_eq($headU['movie_locked'] ?? null, false, 'unstitched head movie_locked false');
expect_eq($headU['next_stitch_clip_id'] ?? null, 'lc_b', 'head next stitch');

$access = [
    'user' => ['id' => 7, 'status' => 'active'],
    'device' => ['id' => 1],
    'auth' => 'device',
];
$mcpAccess = [
    'user' => ['id' => 7, 'status' => 'active'],
    'device' => null,
    'auth' => 'mcp',
];
$pid = str_repeat('c', 32);
$bid = 'lyre_phone_cas-1';
gos_lyre_test_store_reset();
gos_lyre_test_put_project([
    'id' => $pid,
    'user_id' => 7,
    'name' => 'CAS',
    'visibility' => 'private',
    'board_id' => $bid,
    'is_odysseus' => 0,
    'updated_at' => 'old',
]);
gos_lyre_test_put_board($bid, $unstitched, '2026-08-28 16:00:00.000000+00');

$err = catch_error(static fn () => gos_lyre_save_board($access, [
    'id' => $pid,
    'data' => $unstitched,
]));
expect_eq($err?->error, 'expected_updated_at_required', 'CAS missing stamp 400');
expect_eq($err?->http, 400, 'missing stamp http 400');
expect_eq(gos_lyre_test_mysql_bumps(), [], 'missing stamp no mysql bump');

$err = catch_error(static fn () => gos_lyre_save_board($access, [
    'id' => $pid,
    'data' => $unstitched,
    'expected_updated_at' => 'wrong-stamp',
]));
expect_eq($err?->error, 'conflict', 'CAS mismatch 409');
expect_eq($err?->http, 409, 'CAS mismatch http');
expect_eq($err?->extra['updated_at'] ?? null, '2026-08-28 16:00:00.000000+00', 'conflict echoes current stamp');
expect_eq(gos_lyre_test_mysql_bumps(), [], 'CAS fail does not bump mysql');

$ok = gos_lyre_save_board($access, [
    'id' => $pid,
    'data' => $unstitched,
    'expected_updated_at' => '2026-08-28 16:00:00.000000+00',
]);
expect_eq($ok['ok'] ?? null, true, 'CAS success');
expect_true(isset($ok['updated_at']) && $ok['updated_at'] !== '', 'CAS echoes new stamp');
expect_eq(gos_lyre_test_mysql_bumps(), [$pid], 'CAS success bumps mysql');

$err = catch_error(static fn () => gos_lyre_director_folder($mcpAccess, [
    'path' => 'Characters/Penelope',
]));
expect_eq($err?->error, 'project_required', 'MCP mutate missing board_id');
expect_eq($err?->http, 400, 'project_required 400');

$err = catch_error(static fn () => gos_lyre_director_folder($mcpAccess, [
    'board_id' => 'lyre',
    'path' => 'Library',
]));
expect_eq($err?->error, 'odysseus_protected', 'MCP mutate Odysseus 403');
expect_eq($err?->http, 403, 'odysseus http 403');

$folder = gos_lyre_director_folder($access, [
    'board_id' => $bid,
    'path' => 'Characters/Penelope/Attire/red',
]);
expect_eq($folder['ok'] ?? null, true, 'folder ensure');
expect_eq($folder['name'] ?? null, 'Characters/Penelope/Attire/red', 'folder name');
expect_eq($folder['created'] ?? null, true, 'folder created');
$again = gos_lyre_director_folder($access, [
    'board_id' => $bid,
    'path' => 'Characters/Penelope/Attire/red',
]);
expect_eq($again['created'] ?? null, false, 'folder idempotent');
expect_eq($again['folder_id'] ?? null, $folder['folder_id'] ?? 'x', 'same folder id');

$scene = gos_lyre_director_scene($access, [
    'board_id' => $bid,
    'title' => 'Hall',
    'logline' => 'hearth',
]);
expect_true(str_starts_with((string) ($scene['scene_id'] ?? ''), 'sc_'), 'scene id prefix');
expect_eq($scene['activity']['sceneId'] ?? null, $scene['scene_id'] ?? 'x', 'scene create activity has sceneId');

$spoof = gos_lyre_director_activity_append($access, [
    'board_id' => $bid,
    'text' => 'I am a bot',
    'actor' => 'bot',
]);
expect_eq($spoof['activity']['actor'] ?? null, 'phone', 'device append forces actor=phone');
$mcpLine = gos_lyre_normalize_activity_line(['text' => 'hi', 'actor' => 'phone'], 'bot');
expect_eq($mcpLine['actor'] ?? null, 'bot', 'normalize uses caller actor not payload');

$err = catch_error(static fn () => gos_lyre_pg_update(null, 'x', ['title' => 'n']));
expect_eq($err?->error, 'expected_updated_at_required', 'LWW pg_update requires stamp');

$place = gos_lyre_director_place($access, [
    'board_id' => $bid,
    'kind' => 'audio',
    'src' => 'boards/demo/audio/a.wav',
    'name' => 'Bed',
    'duration_sec' => 4,
]);
expect_eq($place['ok'] ?? null, true, 'place audio');

$err = catch_error(static fn () => gos_lyre_director_trim($access, [
    'board_id' => $bid,
    'clip_id' => 'lc_movie',
    'start_sec' => 0,
    'end_sec' => 1,
]));
expect_eq($err?->error, 'movie_locked', 'director trim lc_movie via mutate');

$pid2 = str_repeat('d', 32);
$bid2 = 'lyre_phone_move-1';
gos_lyre_test_put_project([
    'id' => $pid2,
    'user_id' => 7,
    'name' => 'Move',
    'visibility' => 'private',
    'board_id' => $bid2,
    'is_odysseus' => 0,
    'updated_at' => 'old',
]);
$stitchedLoose = add_leftover($stitched, 'lc_extra', 20.0, 2.0);
gos_lyre_test_put_board($bid2, $stitchedLoose, '2026-08-28 17:00:00.000000+00');
$bumpsBefore = gos_lyre_test_mysql_bumps();
$noopMove = gos_lyre_director_move($access, [
    'board_id' => $bid2,
    'clip_id' => 'lc_extra',
    'start_sec' => 1,
]);
expect_eq($noopMove['ok'] ?? null, true, 'overlap move ok');
expect_eq($noopMove['noop'] ?? null, true, 'overlap move is noop');
expect_eq($noopMove['updated_at'] ?? null, '2026-08-28 17:00:00.000000+00', 'noop keeps stamp');
expect_eq(gos_lyre_test_mysql_bumps(), $bumpsBefore, 'noop move does not bump mysql');
$stored = gos_lyre_test_store()['boards'][$bid2]['payload'] ?? [];
$storedStart = null;
foreach (($stored['videoLayers'][0]['clips'] ?? []) as $c) {
    if (($c['id'] ?? '') === 'lc_extra') {
        $storedStart = $c['startSec'] ?? null;
    }
}
expect_eq($storedStart, 20.0, 'director leftover move under movie did not persist');

function lyre_director_rmdir(string $dir): void
{
    if (!is_dir($dir)) {
        return;
    }
    $it = new RecursiveIteratorIterator(
        new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS),
        RecursiveIteratorIterator::CHILD_FIRST
    );
    foreach ($it as $f) {
        $p = $f->getPathname();
        if ($f->isDir()) {
            @rmdir($p);
        } else {
            @unlink($p);
        }
    }
    @rmdir($dir);
}

lyre_director_rmdir($tmpRoot);

if ($fails > 0) {
    fwrite(STDERR, "{$fails} failed\n");
    exit(1);
}
fwrite(STDOUT, "ok\n");
