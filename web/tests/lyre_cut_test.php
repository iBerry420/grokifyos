<?php

declare(strict_types=1);

/**
 * LYRE ffmpeg stitch/trim/pop helpers (disk-only; skip encode without sample mp4).
 * Run: php web/tests/lyre_cut_test.php
 */

$tmpRoot = sys_get_temp_dir() . '/lyre-cut-' . bin2hex(random_bytes(4));
@mkdir($tmpRoot . '/locks', 0777, true);
@mkdir($tmpRoot . '/activity', 0777, true);
@mkdir($tmpRoot . '/jobs', 0777, true);
@mkdir($tmpRoot . '/files', 0777, true);
putenv('GOS_LYRE_LOCKS_DIR=' . $tmpRoot . '/locks');
$_ENV['GOS_LYRE_LOCKS_DIR'] = $tmpRoot . '/locks';
putenv('GOS_LYRE_ACTIVITY_DIR=' . $tmpRoot . '/activity');
$_ENV['GOS_LYRE_ACTIVITY_DIR'] = $tmpRoot . '/activity';
putenv('GOS_LYRE_JOBS_DIR=' . $tmpRoot . '/jobs');
$_ENV['GOS_LYRE_JOBS_DIR'] = $tmpRoot . '/jobs';
putenv('GROKIFY_LYRE_FILES_DIR=' . $tmpRoot . '/files');
$_ENV['GROKIFY_LYRE_FILES_DIR'] = $tmpRoot . '/files';
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

function lyre_cut_rmdir(string $dir): void
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

$unstitched = load_fixture('unstitched.json');
$stitched = load_fixture('stitched.json');

$probeJson = [
    'format' => ['duration' => '2.0'],
    'streams' => [
        [
            'codec_type' => 'video',
            'avg_frame_rate' => '24/1',
            'r_frame_rate' => '24/1',
            'nb_read_frames' => '48',
            'nb_frames' => '50',
            'width' => 1920,
            'height' => 1080,
            'duration' => '2.0',
        ],
        ['codec_type' => 'audio'],
    ],
];
$probe = gos_lyre_cut_read_probe($probeJson);
expect_eq($probe['frames'], 48, 'probe prefers nb_read_frames');
expect_eq($probe['fps'], 24.0, 'probe fps from avg_frame_rate');
expect_eq($probe['hasAudio'], true, 'probe hasAudio');
expect_eq($probe['width'], 1920, 'probe width');
expect_eq($probe['height'], 1080, 'probe height');

$probeNb = gos_lyre_cut_read_probe([
    'format' => ['duration' => '2'],
    'streams' => [[
        'codec_type' => 'video',
        'avg_frame_rate' => '0/0',
        'r_frame_rate' => '24/1',
        'nb_frames' => '47',
        'width' => 10,
        'height' => 10,
    ]],
]);
expect_eq($probeNb['frames'], 47, 'probe falls back to nb_frames');
expect_eq($probeNb['fps'], 24.0, 'probe fps from r_frame_rate when avg is 0/0');
expect_eq($probeNb['hasAudio'], false, 'probe no audio');

$probeRound = gos_lyre_cut_read_probe([
    'format' => ['duration' => '2'],
    'streams' => [[
        'codec_type' => 'video',
        'avg_frame_rate' => '24/1',
        'width' => 2,
        'height' => 2,
    ]],
]);
expect_eq($probeRound['frames'], 48, 'probe rounds duration*fps when no frame counts');

$probeLow = gos_lyre_cut_read_probe([
    'format' => ['duration' => '1'],
    'streams' => [[
        'codec_type' => 'video',
        'avg_frame_rate' => '1/1',
        'nb_read_frames' => '10',
    ]],
]);
expect_eq($probeLow['fps'], 24.0, 'probe fps<=1 assumes 24');

expect_eq(gos_lyre_cut_even(1921), 1920, 'even 1921');
expect_eq(gos_lyre_cut_even(1), 2, 'even 1');
expect_eq(gos_lyre_cut_even(0), 2, 'even 0');
expect_eq(gos_lyre_cut_even(1280), 1280, 'even 1280');

expect_true(gos_lyre_cut_drop_last_too_short(['frames' => 2], true, null), 'too_short frames<3');
expect_true(!gos_lyre_cut_drop_last_too_short(['frames' => 3], true, null), '3 frames not too_short');
expect_true(!gos_lyre_cut_drop_last_too_short(['frames' => 2], true, 0.5), 'keep_sec skips too_short');
expect_true(!gos_lyre_cut_drop_last_too_short(['frames' => 2], false, null), 'no drop_last not too_short');

$tsPath = '/tmp/lyre-inspect/cut-video.server.ts';
$ts = is_file($tsPath) ? (string) file_get_contents($tsPath) : '';
if ($ts !== '') {
    expect_true(str_contains($ts, 'trim=end_frame=${Math.max(1, frames - 1)}'), 'TS videoPrep drop-last template');
    expect_true(str_contains($ts, 'anullsrc=r=48000:cl=stereo,atrim=0:'), 'TS silence template');
    expect_true(str_contains($ts, '[v0][a0][v1][a1]concat=n=2:v=1:a=1[v][a]'), 'TS audio concat');
    expect_true(str_contains($ts, '[v0][v1]concat=n=2:v=1:a=0[v]'), 'TS silent concat');
}

$v0 = gos_lyre_cut_video_prep('0', 'v0', 1280, 720, 24.0, true, 48, null);
expect_eq(
    $v0,
    '[0:v]trim=end_frame=47,setpts=PTS-STARTPTS,fps=24,scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1[v0]',
    'videoPrep drop last encoded frame'
);
$v1 = gos_lyre_cut_video_prep('1', 'v1', 1280, 720, 24.0, false, 24, null);
expect_eq(
    $v1,
    '[1:v]setpts=PTS-STARTPTS,fps=24,scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1[v1]',
    'videoPrep leftover no trim'
);
$a0 = gos_lyre_cut_audio_prep('0', 'a0', 47 / 24);
expect_eq($a0, '[0:a]atrim=end=' . gos_lyre_cut_fmt6(47 / 24) . ',asetpts=PTS-STARTPTS,aresample=48000,aformat=channel_layouts=stereo[a0]', 'audioPrep movie trim');
$sil = gos_lyre_cut_silence('a1', 1.0);
expect_eq($sil, 'anullsrc=r=48000:cl=stereo,atrim=0:' . gos_lyre_cut_fmt6(1.0) . ',asetpts=PTS-STARTPTS[a1]', 'silence graph');

$movieInfo = ['duration' => 2.0, 'fps' => 24.0, 'frames' => 48, 'hasAudio' => true, 'width' => 1280, 'height' => 720];
$clipInfo = ['duration' => 1.0, 'fps' => 24.0, 'frames' => 24, 'hasAudio' => true, 'width' => 1280, 'height' => 720];
$graph = gos_lyre_cut_stitch_graph($movieInfo, $clipInfo, true, null);
expect_true(str_contains($graph['graph'], $v0), 'stitch graph contains movie drop-last');
expect_true(str_contains($graph['graph'], $v1), 'stitch graph contains leftover prep');
expect_true(str_contains($graph['graph'], '[v0][a0][v1][a1]concat=n=2:v=1:a=1[v][a]'), 'stitch graph audio concat');
expect_true(in_array('[v]', $graph['maps'], true), 'stitch maps video');
$silentMovie = $movieInfo;
$silentMovie['hasAudio'] = false;
$silentClip = $clipInfo;
$silentClip['hasAudio'] = false;
$g2 = gos_lyre_cut_stitch_graph($silentMovie, $silentClip, true, null);
expect_true(str_contains($g2['graph'], '[v0][v1]concat=n=2:v=1:a=0[v]'), 'both silent concat a=0');
expect_true(in_array('-an', $g2['maps'], true), 'both silent -an');

$tw = gos_lyre_cut_trim_window(0.5, 1.5, ['duration' => 2.0, 'fps' => 24.0, 'frames' => 48]);
expect_eq($tw['ok'] ?? null, true, 'trim window ok');
expect_eq($tw['vf'] ?? null, 'trim=start=0.500000:end=1.500000,setpts=PTS-STARTPTS', 'trim vf');
expect_eq($tw['af'] ?? null, 'atrim=start=0.500000:end=1.500000,asetpts=PTS-STARTPTS', 'trim af');
$twShort = gos_lyre_cut_trim_window(0.0, 0.001, ['duration' => 0.01, 'fps' => 24.0, 'frames' => 1]);
expect_eq($twShort['ok'] ?? null, false, 'trim window too_short');

expect_eq(gos_lyre_next_stitch_target($unstitched)['id'] ?? null, 'lc_b', 'next stitch leftover lc_b');
expect_true(gos_lyre_can_stitch_clip($unstitched, 'lc_b'), 'can stitch lc_b');
expect_true(!gos_lyre_can_stitch_clip($unstitched, 'lc_a'), 'cannot stitch first clip');
expect_eq(gos_lyre_next_stitch_target($stitched), null, 'stitched has no next');

$firstIn = gos_lyre_cut_movie_input_key($unstitched, 'lyre');
expect_eq($firstIn, 'boards/lyre/clips/lc_a.mp4', 'first stitch movie input is clip A (resolvedMovie)');
expect_true($firstIn !== 'boards/lyre/clips/lc_b.mp4', 'first stitch is not leftover-only');
expect_true(!gos_lyre_cut_is_compiled_key((string) $firstIn, 'lyre'), 'unstitched src is not compiled movie.mp4');
$stitchedIn = gos_lyre_cut_movie_input_key($stitched, 'lyre');
expect_eq($stitchedIn, 'boards/lyre/movie.mp4', 'later stitch uses compiled movie');

expect_eq(gos_lyre_php_cli_bin(), '/usr/bin/php', 'php cli is /usr/bin/php');
expect_true(gos_lyre_php_cli_bin() !== PHP_BINARY || PHP_BINARY === '/usr/bin/php', 'php cli never invents PHP_BINARY');

$workerSrc = (string) file_get_contents(dirname(__DIR__, 2) . '/scripts/lyre-cut-worker.php');
expect_true(!str_contains($workerSrc, 'PHP_BINARY'), 'worker does not use PHP_BINARY');
expect_true(!str_contains($workerSrc, 'nohup'), 'worker does not nohup');
expect_true(str_contains($workerSrc, "define('GOS_SKIP_SESSION', true);"), 'worker defines GOS_SKIP_SESSION');
expect_true(str_contains($workerSrc, "define('GOS_LYRE_NO_ROUTE', true);"), 'worker defines GOS_LYRE_NO_ROUTE');

$front = (string) file_get_contents(dirname(__DIR__) . '/api/lyre-mcp.php');
expect_true(str_contains($front, "define('GOS_SKIP_SESSION', true);"), 'front controller GOS_SKIP_SESSION');
expect_true(str_contains($front, "define('GOS_LYRE_NO_ROUTE', true);"), 'front controller GOS_LYRE_NO_ROUTE');

$apiSrc = (string) file_get_contents(dirname(__DIR__) . '/api/lyre.php');
expect_true(!str_contains($apiSrc, 'nohup'), 'Apache lyre.php does not nohup');
expect_true(!preg_match('/\\bPHP_BINARY\\b/', $apiSrc), 'Apache lyre.php does not use PHP_BINARY');

$cutJob = [
    'request_id' => 'cut_ab12cd34ef56aa99',
    'kind' => 'stitch',
    'status' => 'done',
    'movie_key' => 'boards/lyre_phone_x/movie.mp4',
    'key' => 'videos/should-not-leak.mp4',
    'duration' => 7.0,
    'clip_id' => 'lc_b',
];
$pub = gos_lyre_cut_status_payload($cutJob);
expect_eq($pub['kind'] ?? null, 'stitch', 'cut status kind is stitch not video');
expect_true(!isset($pub['key']), 'cut status does not report job[key] as Imagine key');
expect_eq($pub['movie_key'] ?? null, 'boards/lyre_phone_x/movie.mp4', 'cut status movie_key');
expect_eq($pub['src'] ?? null, 'me:boards/lyre_phone_x/movie.mp4', 'cut done src from movie_key');

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
$pid = str_repeat('e', 32);
$bid = 'lyre_phone_cut-1';
gos_lyre_test_store_reset();
gos_lyre_test_put_project([
    'id' => $pid,
    'user_id' => 7,
    'name' => 'Cut',
    'visibility' => 'private',
    'board_id' => $bid,
    'is_odysseus' => 0,
    'updated_at' => 'old',
]);
gos_lyre_test_put_board($bid, $unstitched, '2026-08-28 18:00:00.000000+00');

$err = catch_error(static fn () => gos_lyre_director_stitch($access, [
    'board_id' => $bid,
    'clip_id' => 'lc_a',
]));
expect_eq($err?->error, 'not_stitch_target', 'stitch wrong clip');

$err = catch_error(static fn () => gos_lyre_director_pop($access, ['board_id' => $bid]));
expect_eq($err?->error, 'nothing_to_pop', 'pop unstitched nothing_to_pop');

$st = gos_lyre_director_stitch($access, ['board_id' => $bid, 'clip_id' => 'lc_b']);
expect_eq($st['ok'] ?? null, true, 'stitch enqueue ok');
expect_eq($st['status'] ?? null, 'pending', 'stitch returns pending');
expect_true(str_starts_with((string) ($st['request_id'] ?? ''), 'cut_'), 'cut_ request id');
$job = gos_lyre_job_read((string) $st['request_id']);
expect_eq($job['kind'] ?? null, 'stitch', 'job kind stitch');
expect_eq($job['status'] ?? null, 'pending', 'job pending');
expect_eq($job['clip_key'] ?? null, 'boards/lyre/clips/lc_b.mp4', 'job leftover key');
expect_eq($job['movie_in_key'] ?? null, 'boards/lyre/clips/lc_a.mp4', 'job movie input is clip A');
expect_eq($job['drop_last'] ?? null, true, 'drop_last true');
expect_eq($job['key'] ?? null, null, 'cut job key is null');
$afterEnq = gos_lyre_test_store()['boards'][$bid]['payload'] ?? [];
expect_eq(count($afterEnq['movie']['parts'] ?? []), 1, 'enqueue does not append parts yet');

$err = catch_error(static fn () => gos_lyre_director_stitch($mcpAccess, ['board_id' => 'lyre']));
expect_eq($err?->error, 'odysseus_protected', 'MCP stitch Odysseus');

$bidSt = 'lyre_phone_cut-st';
$pidSt = str_repeat('f', 32);
gos_lyre_test_put_project([
    'id' => $pidSt,
    'user_id' => 7,
    'name' => 'Stitched',
    'visibility' => 'private',
    'board_id' => $bidSt,
    'is_odysseus' => 0,
    'updated_at' => 'old',
]);
gos_lyre_test_put_board($bidSt, $stitched, '2026-08-28 18:10:00.000000+00');
$pop = gos_lyre_director_pop($access, ['board_id' => $bidSt]);
expect_eq($pop['status'] ?? null, 'pending', 'pop enqueue pending');
$popJob = gos_lyre_job_read((string) $pop['request_id']);
expect_eq($popJob['kind'] ?? null, 'pop', 'pop job kind');
expect_eq($popJob['clip_id'] ?? null, 'lc_b', 'pop last part lc_b');
expect_eq(count($popJob['remaining_parts'] ?? []), 1, 'pop remaining one part');
$stillStitched = gos_lyre_test_store()['boards'][$bidSt]['payload'] ?? [];
expect_eq(count($stillStitched['movie']['parts'] ?? []), 2, 'pop enqueue does not drop parts yet');

$stamp0 = '2026-08-28 18:00:00.000000+00';
$bidCas = 'lyre_phone_cut-cas';
$pidCas = str_repeat('a', 32);
gos_lyre_test_put_project([
    'id' => $pidCas,
    'user_id' => 7,
    'name' => 'CAS',
    'visibility' => 'private',
    'board_id' => $bidCas,
    'is_odysseus' => 0,
    'updated_at' => 'old',
]);
gos_lyre_test_put_board($bidCas, $unstitched, $stamp0);
$ridCas = 'cut_casretry0000001';
$movieKey = gos_lyre_cut_compiled_movie_key($bidCas);
$tmpPath = gos_lyre_cut_tmp_path($bidCas, $ridCas);
expect_true(is_string($tmpPath) && $tmpPath !== '', 'tmp path');
@mkdir(dirname((string) $tmpPath), 0777, true);
file_put_contents((string) $tmpPath, str_repeat('m', 128));
$casJob = [
    'request_id' => $ridCas,
    'kind' => 'stitch',
    'status' => 'running',
    'board_id' => $bidCas,
    'project_id' => $pidCas,
    'user_id' => 7,
    'clip_id' => 'lc_b',
    'movie_key' => $movieKey,
    'clip_key' => 'boards/lyre/clips/lc_b.mp4',
    'drop_last' => true,
    'expected_updated_at' => 'stale-stamp-from-enqueue',
    'ffmpeg_code' => 0,
    'actor' => 'bot',
    'created_at' => time(),
];
gos_lyre_job_write($ridCas, $casJob);
$bumpsBefore = gos_lyre_test_mysql_bumps();
$probeOut = ['duration' => 6.9, 'fps' => 24.0, 'frames' => 166, 'hasAudio' => false, 'width' => 1280, 'height' => 720];
$result = gos_lyre_cut_cas_apply($casJob, (string) $tmpPath, $probeOut);
expect_eq($result, 'done', 'CAS retry applies when stitch target still valid');
$payload = gos_lyre_test_store()['boards'][$bidCas]['payload'] ?? [];
$partIds = array_map(static fn ($p) => $p['clipId'] ?? '', $payload['movie']['parts'] ?? []);
expect_eq($partIds, ['lc_a', 'lc_b'], 'CAS apply appends leftover as part B');
expect_eq($payload['movie']['src'] ?? null, $movieKey, 'movie.src is compiled key');
expect_true(isset($payload['videoLayers'][0]['clips'][1]['id']) && $payload['videoLayers'][0]['clips'][1]['id'] === 'lc_b', 'layer clip B kept');
$doneJob = gos_lyre_job_read($ridCas);
expect_eq($doneJob['status'] ?? null, 'done', 'job done after CAS');
expect_eq($doneJob['ffmpeg_code'] ?? null, 0, 'ffmpeg_code recorded');
expect_eq($doneJob['uploaded'] ?? null, false, 'PUT after unlock; test store leaves uploaded false');
expect_true(in_array($pidCas, gos_lyre_test_mysql_bumps(), true), 'CAS success bumps mysql');
$dest = gos_lyre_cut_dest_path($movieKey);
expect_true(is_string($dest) && is_file($dest), 'tmp renamed onto movie.mp4');

$casSrc = (string) file_get_contents(dirname(__DIR__) . '/includes/lyre_cut.php');
$casFrom = strpos($casSrc, 'function gos_lyre_cut_cas_apply');
$casTo = strpos($casSrc, 'function gos_lyre_cut_list_claimable');
expect_true($casFrom !== false && $casTo !== false && $casTo > $casFrom, 'cas_apply bounds');
$casBody = $casFrom !== false && $casTo !== false ? substr($casSrc, $casFrom, $casTo - $casFrom) : '';
$unlockPos = strrpos($casBody, 'gos_lyre_board_unlock');
$putPos = strpos($casBody, 'gos_lyre_grokme_put_file');
expect_true(is_int($unlockPos) && is_int($putPos) && $putPos > $unlockPos, 'grokme PUT is after board unlock');

expect_eq(gos_lyre_cut_job_keep_sec(['keep_sec' => null]), null, 'keep_sec null');
expect_eq(gos_lyre_cut_job_keep_sec([]), null, 'keep_sec omitted');
expect_eq(gos_lyre_cut_job_keep_sec(['keep_sec' => 1.25]), 1.25, 'keep_sec float');
expect_true(str_contains($casSrc, 'gos_lyre_cut_job_keep_sec($job)'), 'stitch encode passes job keep_sec');

$ridRo = 'cut_casrenamefail0004';
$bidRo = 'lyre_phone_cut-ro';
$pidRo = str_repeat('b', 32);
gos_lyre_test_put_project([
    'id' => $pidRo,
    'user_id' => 7,
    'name' => 'RO',
    'visibility' => 'private',
    'board_id' => $bidRo,
    'is_odysseus' => 0,
    'updated_at' => 'old',
]);
gos_lyre_test_put_board($bidRo, $unstitched, '2026-08-28 18:20:00.000000+00');
$movieKeyRo = gos_lyre_cut_compiled_movie_key($bidRo);
$tmpRo = gos_lyre_cut_tmp_path($bidRo, $ridRo);
@mkdir(dirname((string) $tmpRo), 0777, true);
file_put_contents((string) $tmpRo, str_repeat('r', 128));
$destRo = gos_lyre_cut_dest_path($movieKeyRo);
expect_true(is_string($destRo), 'ro dest path');
@mkdir(dirname((string) $destRo), 0777, true);
@mkdir((string) $destRo, 0777, true);
$roJob = [
    'request_id' => $ridRo,
    'kind' => 'stitch',
    'status' => 'running',
    'board_id' => $bidRo,
    'project_id' => $pidRo,
    'user_id' => 7,
    'clip_id' => 'lc_b',
    'movie_key' => $movieKeyRo,
    'clip_key' => 'boards/lyre/clips/lc_b.mp4',
    'drop_last' => true,
    'expected_updated_at' => '2026-08-28 18:20:00.000000+00',
    'ffmpeg_code' => 0,
    'actor' => 'bot',
    'created_at' => time(),
];
gos_lyre_job_write($ridRo, $roJob);
$roRes = gos_lyre_cut_cas_apply($roJob, (string) $tmpRo, $probeOut);
expect_eq($roRes, 'done', 'CAS success stays done when dest cannot be published');
$roSaved = gos_lyre_job_read($ridRo);
expect_eq($roSaved['status'] ?? null, 'done', 'do not fail_job after CAS commit');
expect_eq($roSaved['uploaded'] ?? null, false, 'unpublished dest is uploaded false');
expect_eq($roSaved['error'] ?? null, 'storage_error', 'unpublished dest flagged storage_error');
$roParts = array_map(
    static fn ($p) => $p['clipId'] ?? '',
    gos_lyre_test_store()['boards'][$bidRo]['payload']['movie']['parts'] ?? []
);
expect_eq($roParts, ['lc_a', 'lc_b'], 'board JSON still applied when dest unpublished');
if (is_dir((string) $destRo)) {
    @rmdir((string) $destRo);
}

$ridOw = 'cut_casoverwrite0005';
$bidOw = 'lyre_phone_cut-ow';
$pidOw = str_repeat('9', 32);
gos_lyre_test_put_project([
    'id' => $pidOw,
    'user_id' => 7,
    'name' => 'OW',
    'visibility' => 'private',
    'board_id' => $bidOw,
    'is_odysseus' => 0,
    'updated_at' => 'old',
]);
gos_lyre_test_put_board($bidOw, $unstitched, '2026-08-28 18:30:00.000000+00');
$movieKeyOw = gos_lyre_cut_compiled_movie_key($bidOw);
$tmpOw = gos_lyre_cut_tmp_path($bidOw, $ridOw);
$destOw = gos_lyre_cut_dest_path($movieKeyOw);
expect_true(is_string($tmpOw) && is_string($destOw), 'overwrite dest/tmp paths');
@mkdir(dirname((string) $tmpOw), 0777, true);
@mkdir(dirname((string) $destOw), 0777, true);
$oldBytes = str_repeat('O', 200);
$newBytes = str_repeat('N', 180);
file_put_contents((string) $destOw, $oldBytes);
file_put_contents((string) $tmpOw, $newBytes);
$owJob = [
    'request_id' => $ridOw,
    'kind' => 'stitch',
    'status' => 'running',
    'board_id' => $bidOw,
    'project_id' => $pidOw,
    'user_id' => 7,
    'clip_id' => 'lc_b',
    'movie_key' => $movieKeyOw,
    'clip_key' => 'boards/lyre/clips/lc_b.mp4',
    'drop_last' => true,
    'expected_updated_at' => '2026-08-28 18:30:00.000000+00',
    'ffmpeg_code' => 0,
    'actor' => 'bot',
    'created_at' => time(),
];
gos_lyre_job_write($ridOw, $owJob);
$owRes = gos_lyre_cut_cas_apply($owJob, (string) $tmpOw, $probeOut);
expect_eq($owRes, 'done', 'overwrite stitch apply done');
expect_true(is_file((string) $destOw), 'overwrite dest exists');
expect_eq(filesize((string) $destOw), strlen($newBytes), 'dest size matches this-attempt tmp');
expect_eq((string) file_get_contents((string) $destOw), $newBytes, 'dest bytes replaced with tmp not old movie');
expect_true((string) file_get_contents((string) $destOw) !== $oldBytes, 'old compiled movie is gone');

$cutPollId = 'cut_statuspoll00001';
gos_lyre_job_write($cutPollId, [
    'request_id' => $cutPollId,
    'kind' => 'stitch',
    'status' => 'pending',
    'movie_key' => 'boards/lyre_phone_x/movie.mp4',
    'key' => 'videos/should-not-leak.mp4',
    'clip_id' => 'lc_b',
]);
$cutPoll = gos_lyre_imagine_status_result($cutPollId);
expect_eq($cutPoll['http'] ?? null, 200, 'cut poll http 200');
expect_eq($cutPoll['body']['kind'] ?? null, 'stitch', 'cut poll kind stitch');
expect_true(!isset($cutPoll['body']['key']), 'cut poll omits Imagine key');
$vidDoneId = 'videodone00000001';
gos_lyre_job_write($vidDoneId, [
    'request_id' => $vidDoneId,
    'kind' => 'video',
    'status' => 'done',
    'key' => 'videos/x.mp4',
    'duration' => 6,
]);
$vidPoll = gos_lyre_imagine_status_result($vidDoneId);
expect_eq($vidPoll['body']['kind'] ?? null, 'video', 'video done poll kind video');
expect_eq($vidPoll['body']['key'] ?? null, 'videos/x.mp4', 'video done poll returns key without xAI');
$mcpPoll = gos_lyre_mcp_run_tool('lyre_imagine_status', [
    'request_id' => $cutPollId,
    'attach' => true,
], $mcpAccess);
$mcpText = (string) ($mcpPoll['content'][0]['text'] ?? '');
expect_true(str_contains($mcpText, '"kind": "stitch"') || str_contains($mcpText, '"kind":"stitch"'), 'MCP imagine_status cut kind');
expect_true(!str_contains($mcpText, '"kind": "video"') && !str_contains($mcpText, '"kind":"video"'), 'MCP cut poll is not kind=video');
expect_eq($mcpPoll['isError'] ?? null, false, 'MCP attach ignored on cut poll');

$ridFail = 'cut_casconflict00002';
gos_lyre_test_put_board($bidCas, $unstitched, '2026-08-28 19:00:00.000000+00');
$tmp2 = gos_lyre_cut_tmp_path($bidCas, $ridFail);
file_put_contents((string) $tmp2, str_repeat('x', 128));
$liveBefore = gos_lyre_cut_dest_path($movieKey);
$liveSize = is_string($liveBefore) && is_file($liveBefore) ? filesize($liveBefore) : 0;
$failJob = [
    'request_id' => $ridFail,
    'kind' => 'stitch',
    'status' => 'running',
    'board_id' => $bidCas,
    'project_id' => $pidCas,
    'user_id' => 7,
    'clip_id' => 'lc_a',
    'movie_key' => $movieKey,
    'clip_key' => 'boards/lyre/clips/lc_a.mp4',
    'expected_updated_at' => 'nope',
    'ffmpeg_code' => 0,
    'actor' => 'bot',
    'created_at' => time(),
];
gos_lyre_job_write($ridFail, $failJob);
$bumpsMid = gos_lyre_test_mysql_bumps();
$failed = gos_lyre_cut_cas_apply($failJob, (string) $tmp2, $probeOut);
expect_eq($failed, 'failed', 'invalid stitch target after mismatch fails');
$failedJob = gos_lyre_job_read($ridFail);
expect_eq($failedJob['status'] ?? null, 'failed', 'job failed');
expect_eq($failedJob['error'] ?? null, 'not_stitch_target', 'fail not_stitch_target');
expect_eq($failedJob['ffmpeg_code'] ?? null, 0, 'ffmpeg_code kept on CAS fail');
expect_true(is_file((string) $tmp2), 'tmp kept on fail');
expect_eq(gos_lyre_test_mysql_bumps(), $bumpsMid, 'failed CAS does not bump mysql');
if (is_string($liveBefore) && is_file($liveBefore)) {
    expect_eq(filesize($liveBefore), $liveSize, 'live movie.mp4 not replaced on fail');
}

$popBoard = $payload;
gos_lyre_test_put_board($bidCas, $popBoard, (string) (gos_lyre_test_store()['boards'][$bidCas]['updated_at'] ?? 'x'));
$ridPop = 'cut_poponepart000003';
$popApply = [
    'request_id' => $ridPop,
    'kind' => 'pop',
    'status' => 'running',
    'board_id' => $bidCas,
    'project_id' => $pidCas,
    'user_id' => 7,
    'clip_id' => 'lc_b',
    'movie_key' => $movieKey,
    'clip_key' => 'boards/lyre/clips/lc_b.mp4',
    'remaining_parts' => [[
        'clipId' => 'lc_a',
        'src' => 'boards/lyre/clips/lc_a.mp4',
        'durationSec' => 4.0,
    ]],
    'expected_updated_at' => (string) (gos_lyre_test_store()['boards'][$bidCas]['updated_at'] ?? ''),
    'ffmpeg_code' => 0,
    'actor' => 'bot',
    'created_at' => time(),
];
gos_lyre_job_write($ridPop, $popApply);
$popRes = gos_lyre_cut_cas_apply($popApply, '', ['duration' => 4.0, 'fps' => 24.0, 'frames' => 96, 'hasAudio' => false, 'width' => 0, 'height' => 0]);
expect_eq($popRes, 'done', 'pop to one part applies');
$popped = gos_lyre_test_store()['boards'][$bidCas]['payload'] ?? [];
expect_eq(count($popped['movie']['parts'] ?? []), 1, 'pop remaining parts size 1');
expect_eq($popped['movie']['src'] ?? null, 'boards/lyre/clips/lc_a.mp4', 'one part left is clip A (resolvedMovie)');
expect_eq(gos_lyre_next_stitch_target($popped)['id'] ?? null, 'lc_b', 'after pop next stitch is leftover again');
$hasB = false;
foreach ($popped['videoLayers'][0]['clips'] ?? [] as $c) {
    if (($c['id'] ?? '') === 'lc_b') {
        $hasB = true;
    }
}
expect_true($hasB, 'pop does not remove leftover clip');

$samples = glob(__DIR__ . '/fixtures/lyre/*.mp4') ?: [];
if ($samples !== [] && gos_lyre_ffmpeg_bin() !== '') {
    $a = $samples[0];
    $b = $samples[1] ?? $samples[0];
    $out = $tmpRoot . '/files/encode-out.mp4';
    $enc = gos_lyre_cut_ffmpeg_stitch($a, $b, true, null, $out);
    expect_eq($enc['ok'] ?? null, true, 'optional encode stitch');
    expect_true(is_file($out) && filesize($out) >= 80, 'optional encode wrote mp4');
}

lyre_cut_rmdir($tmpRoot);

if ($fails > 0) {
    fwrite(STDERR, "{$fails} failed\n");
    exit(1);
}
fwrite(STDOUT, "ok\n");
