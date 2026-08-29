<?php

declare(strict_types=1);

/**
 * LYRE disk-only ffmpeg stitch / trim / pop. Jobs in storage/lyre-jobs; systemd worker.
 */

const GOS_LYRE_CUT_RETRY_MAX = 5;

function gos_lyre_cut_new_id(): string
{
    return 'cut_' . bin2hex(random_bytes(8));
}

function gos_lyre_cut_fmt6(float $n): string
{
    return sprintf('%.6f', $n);
}

function gos_lyre_cut_fps_token(float $fps): string
{
    if (!is_finite($fps) || $fps <= 0) {
        return '24';
    }
    $r = round($fps);
    if (abs($fps - $r) < 1e-6) {
        return (string) (int) $r;
    }

    return rtrim(rtrim(sprintf('%.6f', $fps), '0'), '.');
}

function gos_lyre_cut_even(int $n): int
{
    return max(2, intdiv($n, 2) * 2);
}

function gos_lyre_cut_parse_frame_rate(?string $raw): float
{
    if ($raw === null || $raw === '' || $raw === '0/0' || $raw === 'N/A') {
        return 0.0;
    }
    if (str_contains($raw, '/')) {
        [$a, $b] = explode('/', $raw, 2);
        $na = (float) $a;
        $nb = (float) $b;
        if ($na > 0 && $nb > 0) {
            return $na / $nb;
        }
    }
    $n = (float) $raw;

    return is_finite($n) && $n > 0 ? $n : 0.0;
}

/**
 * @return array{duration: float, fps: float, frames: int, hasAudio: bool, width: int, height: int}
 */
function gos_lyre_cut_read_probe(mixed $json): array
{
    $rec = is_array($json) ? $json : [];
    $streams = isset($rec['streams']) && is_array($rec['streams']) ? $rec['streams'] : [];
    $format = isset($rec['format']) && is_array($rec['format']) ? $rec['format'] : [];
    $video = null;
    $hasAudio = false;
    foreach ($streams as $s) {
        if (!is_array($s)) {
            continue;
        }
        $type = (string) ($s['codec_type'] ?? '');
        if ($type === 'video' && $video === null) {
            $video = $s;
        } elseif ($type === 'audio') {
            $hasAudio = true;
        }
    }
    $duration = gos_lyre_num($format['duration'] ?? 0);
    if ($duration <= 0 && is_array($video)) {
        $duration = gos_lyre_num($video['duration'] ?? 0);
    }
    $fps = 0.0;
    if (is_array($video)) {
        $fps = gos_lyre_cut_parse_frame_rate(isset($video['avg_frame_rate']) ? (string) $video['avg_frame_rate'] : null);
        if ($fps <= 0) {
            $fps = gos_lyre_cut_parse_frame_rate(isset($video['r_frame_rate']) ? (string) $video['r_frame_rate'] : null);
        }
    }
    $read = is_array($video) ? (float) ($video['nb_read_frames'] ?? 0) : 0.0;
    $nb = is_array($video) ? (float) ($video['nb_frames'] ?? 0) : 0.0;
    if (is_finite($read) && $read > 0) {
        $frames = (int) $read;
    } elseif (is_finite($nb) && $nb > 0) {
        $frames = (int) $nb;
    } else {
        $frames = (int) max(1, (int) round($duration * ($fps > 0 ? $fps : 24)));
    }
    $width = is_array($video) ? (int) ($video['width'] ?? 0) : 0;
    $height = is_array($video) ? (int) ($video['height'] ?? 0) : 0;

    return [
        'duration' => $duration,
        'fps' => $fps > 1 ? $fps : 24.0,
        'frames' => $frames,
        'hasAudio' => $hasAudio,
        'width' => $width,
        'height' => $height,
    ];
}

function gos_lyre_cut_playable_duration(array $probe): float
{
    $fps = (float) ($probe['fps'] ?? 0);
    $frames = (int) ($probe['frames'] ?? 0);
    if ($frames > 0 && $fps > 1) {
        return $frames / $fps;
    }

    return (float) ($probe['duration'] ?? 0);
}

function gos_lyre_cut_drop_last_too_short(array $probe, bool $dropLast, ?float $keepSec): bool
{
    return $dropLast && $keepSec === null && (int) ($probe['frames'] ?? 0) < 3;
}

function gos_lyre_cut_video_prep(
    string $label,
    string $out,
    int $w,
    int $h,
    float $fps,
    bool $trimLast,
    int $frames,
    ?float $trimEndSec = null,
): string {
    $fpsTok = gos_lyre_cut_fps_token($fps);
    if ($trimEndSec !== null) {
        $trim = 'trim=end=' . gos_lyre_cut_fmt6(max(1 / max($fps, 0.001), $trimEndSec)) . ',';
    } elseif ($trimLast) {
        $trim = 'trim=end_frame=' . (string) max(1, $frames - 1) . ',';
    } else {
        $trim = '';
    }

    return "[{$label}:v]{$trim}setpts=PTS-STARTPTS,fps={$fpsTok},scale={$w}:{$h}:force_original_aspect_ratio=decrease,pad={$w}:{$h}:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1[{$out}]";
}

function gos_lyre_cut_audio_prep(string $label, string $out, ?float $trimEnd): string
{
    $trim = $trimEnd !== null ? 'atrim=end=' . gos_lyre_cut_fmt6($trimEnd) . ',' : '';

    return "[{$label}:a]{$trim}asetpts=PTS-STARTPTS,aresample=48000,aformat=channel_layouts=stereo[{$out}]";
}

function gos_lyre_cut_silence(string $out, float $duration): string
{
    return 'anullsrc=r=48000:cl=stereo,atrim=0:' . gos_lyre_cut_fmt6(max(0.04, $duration)) . ',asetpts=PTS-STARTPTS[' . $out . ']';
}

/**
 * @return array{graph: string, maps: list<string>, fps: float, w: int, h: int, movie_trim: ?float}
 */
function gos_lyre_cut_stitch_graph(array $movieInfo, array $clipInfo, bool $dropLast, ?float $keepSec = null): array
{
    $clipFps = (float) ($clipInfo['fps'] ?? 0);
    $movieFps = (float) ($movieInfo['fps'] ?? 0);
    $fps = ($movieFps > 1 ? $movieFps : $clipFps);
    if ($fps <= 1) {
        $fps = 24.0;
    }
    $w = gos_lyre_cut_even((int) ($movieInfo['width'] ?? 0) ?: (int) ($clipInfo['width'] ?? 0) ?: 1280);
    $h = gos_lyre_cut_even((int) ($movieInfo['height'] ?? 0) ?: (int) ($clipInfo['height'] ?? 0) ?: 720);
    $movieFrames = (int) ($movieInfo['frames'] ?? 0);
    $clipFrames = (int) ($clipInfo['frames'] ?? 0);
    $movieDuration = (float) ($movieInfo['duration'] ?? 0) ?: ($movieFrames > 0 ? $movieFrames / $fps : 0.0);
    $clipDur = (float) ($clipInfo['duration'] ?? 0) ?: ($clipFrames > 0 ? $clipFrames / $fps : 0.0);
    $movieTrim = null;
    if ($keepSec !== null) {
        $movieTrim = max(1 / $fps, min($movieDuration ?: $movieFrames / $fps, $keepSec));
    } elseif ($dropLast) {
        $movieTrim = ($movieFrames - 1) / $fps;
    }
    $movieDur = $movieTrim !== null ? $movieTrim : $movieDuration;
    $v0 = gos_lyre_cut_video_prep('0', 'v0', $w, $h, $fps, $dropLast && $keepSec === null, $movieFrames, $keepSec);
    $v1 = gos_lyre_cut_video_prep('1', 'v1', $w, $h, $fps, false, $clipFrames, null);
    $parts = [$v0, $v1];
    $maps = ['-map', '[v]'];
    $movieHas = !empty($movieInfo['hasAudio']);
    $clipHas = !empty($clipInfo['hasAudio']);
    if ($movieHas || $clipHas) {
        $parts[] = $movieHas ? gos_lyre_cut_audio_prep('0', 'a0', $movieTrim) : gos_lyre_cut_silence('a0', $movieDur);
        $parts[] = $clipHas ? gos_lyre_cut_audio_prep('1', 'a1', null) : gos_lyre_cut_silence('a1', $clipDur);
        $parts[] = '[v0][a0][v1][a1]concat=n=2:v=1:a=1[v][a]';
        $maps[] = '-map';
        $maps[] = '[a]';
        $maps[] = '-c:a';
        $maps[] = 'aac';
        $maps[] = '-b:a';
        $maps[] = '192k';
    } else {
        $parts[] = '[v0][v1]concat=n=2:v=1:a=0[v]';
        $maps[] = '-an';
    }

    return [
        'graph' => implode(';', $parts),
        'maps' => $maps,
        'fps' => $fps,
        'w' => $w,
        'h' => $h,
        'movie_trim' => $movieTrim,
    ];
}

/**
 * @return array{ok: bool, fps?: float, inn?: float, out?: float, vf?: string, af?: string, error?: string}
 */
function gos_lyre_cut_trim_window(float $startSec, float $endSec, array $probe): array
{
    $fps = ((float) ($probe['fps'] ?? 0)) > 1 ? (float) $probe['fps'] : 24.0;
    $native = (float) ($probe['duration'] ?? 0);
    if ($native <= 0 && (int) ($probe['frames'] ?? 0) > 0) {
        $native = (int) $probe['frames'] / $fps;
    }
    $step = 1 / $fps;
    $inn = max(0.0, $startSec);
    $out = min($native > 0 ? $native : $endSec, max($inn + $step, $endSec));
    if (!is_finite($out) || $out - $inn < $step * 0.5) {
        return ['ok' => false, 'error' => 'too_short'];
    }

    return [
        'ok' => true,
        'fps' => $fps,
        'inn' => $inn,
        'out' => $out,
        'vf' => 'trim=start=' . gos_lyre_cut_fmt6($inn) . ':end=' . gos_lyre_cut_fmt6($out) . ',setpts=PTS-STARTPTS',
        'af' => 'atrim=start=' . gos_lyre_cut_fmt6($inn) . ':end=' . gos_lyre_cut_fmt6($out) . ',asetpts=PTS-STARTPTS',
    ];
}

function gos_lyre_cut_compiled_movie_key(string $boardId): string
{
    return 'boards/' . $boardId . '/movie.mp4';
}

function gos_lyre_cut_gen_key(string $boardId, int $n): string
{
    return 'boards/' . $boardId . '/movie.g' . $n . '.mp4';
}

function gos_lyre_cut_norm_key(string $raw): string
{
    $key = gos_lyre_storage_key($raw);

    return $key !== null ? $key : ltrim(str_replace('\\', '/', $raw), '/');
}

function gos_lyre_cut_is_compiled_key(string $src, string $boardId = ''): bool
{
    $key = gos_lyre_cut_norm_key($src);
    if ($key === '') {
        return false;
    }
    if (preg_match('#^boards/[^/]+/movie\\.mp4$#', $key) === 1) {
        return true;
    }

    return $boardId !== '' && $key === gos_lyre_cut_compiled_movie_key($boardId);
}

function gos_lyre_cut_movie_input_key(array $board, string $boardId = ''): ?string
{
    $movie = gos_lyre_movie($board);
    $parts = gos_lyre_parts($board);
    $src = is_array($movie) ? gos_lyre_str($movie['src'] ?? '') : '';
    if ($src !== '' && (count($parts) >= 2 || gos_lyre_cut_is_compiled_key($src, $boardId))) {
        return $src;
    }
    $resolved = gos_lyre_resolved_movie($board);
    if (!is_array($resolved)) {
        return null;
    }
    $rsrc = gos_lyre_str($resolved['src'] ?? '');

    return $rsrc !== '' ? $rsrc : null;
}

function gos_lyre_cut_dest_path(string $key): ?string
{
    $root = gos_lyre_files_root();
    $norm = gos_lyre_cut_norm_key($key);
    if ($root === null || $norm === '' || str_contains($norm, '..')) {
        return null;
    }
    $checked = gos_lyre_storage_key($norm);
    if ($checked === null) {
        return null;
    }

    return $root . '/' . $checked;
}

function gos_lyre_cut_tmp_path(string $boardId, string $requestId): ?string
{
    $root = gos_lyre_files_root();
    $safe = gos_lyre_safe_board_id($boardId);
    if ($root === null || $safe === null || !gos_lyre_job_id_ok($requestId)) {
        return null;
    }
    $dir = $root . '/boards/' . $safe . '/tmp';
    if (!is_dir($dir) && !@mkdir($dir, 0775, true) && !is_dir($dir)) {
        return null;
    }

    return $dir . '/' . $requestId . '.mp4';
}

function gos_lyre_cut_stderr_hint(string $stderr): string
{
    $lines = preg_split("/\r\n|\n|\r/", trim($stderr)) ?: [];
    $lines = array_values(array_filter($lines, static fn ($l) => $l !== ''));
    $tail = array_slice($lines, -4);

    return substr(implode(' ', $tail), 0, 240);
}

function gos_lyre_cut_probe_file(string $path, bool $countFrames = true): ?array
{
    $bin = gos_lyre_ffprobe_bin();
    if ($bin === '' || $path === '' || !is_file($path)) {
        return null;
    }
    $cmd = [$bin, '-v', 'error', '-print_format', 'json', '-show_format', '-show_streams'];
    if ($countFrames) {
        $cmd[] = '-count_frames';
    }
    $cmd[] = $path;
    $run = gos_lyre_proc_run($cmd, 60);
    if ($run['code'] !== 0) {
        return null;
    }
    $json = json_decode($run['stdout'], true);

    return gos_lyre_cut_read_probe(is_array($json) ? $json : []);
}

/**
 * @return array{ok: bool, code: ?int, error?: string}
 */
function gos_lyre_cut_ffmpeg_stitch(string $moviePath, string $clipPath, bool $dropLast, ?float $keepSec, string $outPath): array
{
    $bin = gos_lyre_ffmpeg_bin();
    if ($bin === '') {
        return ['ok' => false, 'code' => null, 'error' => 'ffmpeg_missing'];
    }
    $movieInfo = gos_lyre_cut_probe_file($moviePath, true);
    $clipInfo = gos_lyre_cut_probe_file($clipPath, true);
    if ($movieInfo === null || $clipInfo === null) {
        return ['ok' => false, 'code' => null, 'error' => 'source_missing'];
    }
    if (gos_lyre_cut_drop_last_too_short($movieInfo, $dropLast, $keepSec)) {
        return ['ok' => false, 'code' => null, 'error' => 'too_short'];
    }
    $plan = gos_lyre_cut_stitch_graph($movieInfo, $clipInfo, $dropLast, $keepSec);
    $dir = dirname($outPath);
    if (!is_dir($dir) && !@mkdir($dir, 0775, true) && !is_dir($dir)) {
        return ['ok' => false, 'code' => null, 'error' => 'storage_error'];
    }
    $cmd = array_merge(
        [$bin, '-y', '-i', $moviePath, '-i', $clipPath, '-filter_complex', $plan['graph']],
        $plan['maps'],
        ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', '18', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', $outPath]
    );
    $run = gos_lyre_proc_run($cmd, 180);
    if ($run['code'] !== 0 || !is_file($outPath) || filesize($outPath) < 80) {
        return [
            'ok' => false,
            'code' => $run['code'],
            'error' => gos_lyre_cut_stderr_hint($run['stderr']) !== '' ? gos_lyre_cut_stderr_hint($run['stderr']) : 'stitch_failed',
        ];
    }

    return ['ok' => true, 'code' => $run['code']];
}

/**
 * @return array{ok: bool, code: ?int, error?: string}
 */
function gos_lyre_cut_ffmpeg_trim(string $input, string $outPath, float $startSec, float $endSec): array
{
    $bin = gos_lyre_ffmpeg_bin();
    if ($bin === '') {
        return ['ok' => false, 'code' => null, 'error' => 'ffmpeg_missing'];
    }
    $info = gos_lyre_cut_probe_file($input, true);
    if ($info === null) {
        return ['ok' => false, 'code' => null, 'error' => 'source_missing'];
    }
    $plan = gos_lyre_cut_trim_window($startSec, $endSec, $info);
    if (empty($plan['ok'])) {
        return ['ok' => false, 'code' => null, 'error' => (string) ($plan['error'] ?? 'too_short')];
    }
    $dir = dirname($outPath);
    if (!is_dir($dir) && !@mkdir($dir, 0775, true) && !is_dir($dir)) {
        return ['ok' => false, 'code' => null, 'error' => 'storage_error'];
    }
    $cmd = [$bin, '-y', '-i', $input, '-map', '0:v:0', '-vf', (string) $plan['vf']];
    if (!empty($info['hasAudio'])) {
        $cmd = array_merge($cmd, ['-map', '0:a:0', '-af', (string) $plan['af'], '-c:a', 'aac', '-b:a', '192k']);
    } else {
        $cmd[] = '-an';
    }
    $cmd = array_merge($cmd, [
        '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '18', '-pix_fmt', 'yuv420p',
        '-fps_mode', 'cfr', '-r', gos_lyre_cut_fps_token((float) $plan['fps']),
        '-movflags', '+faststart', $outPath,
    ]);
    $run = gos_lyre_proc_run($cmd, 90);
    if ($run['code'] !== 0 || !is_file($outPath) || filesize($outPath) < 80) {
        return [
            'ok' => false,
            'code' => $run['code'],
            'error' => gos_lyre_cut_stderr_hint($run['stderr']) !== '' ? gos_lyre_cut_stderr_hint($run['stderr']) : 'trim_failed',
        ];
    }

    return ['ok' => true, 'code' => $run['code']];
}

function gos_lyre_cut_snapshot_gen(string $fromKey, string $genKey): bool
{
    $dest = gos_lyre_cut_dest_path($genKey);
    if ($dest === null) {
        return false;
    }
    if (is_file($dest) && filesize($dest) > 0) {
        return true;
    }
    $src = gos_lyre_ensure_local_file($fromKey);
    if ($src === null) {
        return false;
    }

    return gos_lyre_copy_file($src, $dest);
}

function gos_lyre_cut_mark_orig_src(array $board, string $clipId): array
{
    return gos_lyre_map_video_clip($board, $clipId, static function (array $clip) {
        if (gos_lyre_str($clip['origSrc'] ?? '') === '') {
            $clip['origSrc'] = $clip['src'] ?? null;
        }
        if (!isset($clip['origDurationSec'])) {
            $clip['origDurationSec'] = $clip['sourceDurationSec'] ?? $clip['durationSec'] ?? null;
        }

        return $clip;
    });
}

/**
 * @return array<string, mixed>
 */
function gos_lyre_cut_status_payload(array $job): array
{
    $kind = gos_lyre_str($job['kind'] ?? '');
    $status = gos_lyre_str($job['status'] ?? 'pending');
    $rid = gos_lyre_str($job['request_id'] ?? '');
    $cut = in_array($kind, ['stitch', 'trim', 'pop'], true);
    $out = [
        'ok' => $status !== 'failed',
        'status' => $status !== '' ? $status : 'pending',
        'request_id' => $rid,
        'kind' => $cut ? $kind : ($kind !== '' ? $kind : 'video'),
        'duration' => $job['duration'] ?? null,
        'fps' => $job['fps'] ?? null,
        'error' => $job['error'] ?? null,
        'ffmpeg_code' => $job['ffmpeg_code'] ?? null,
        'board_id' => $job['board_id'] ?? null,
        'attached' => !empty($job['attached_at']),
    ];
    if (!empty($job['uploaded']) || array_key_exists('uploaded', $job)) {
        $out['uploaded'] = $job['uploaded'];
    }
    if ($cut) {
        $out['movie_key'] = $job['movie_key'] ?? null;
        $out['clip_id'] = $job['clip_id'] ?? null;
        $mk = gos_lyre_str($job['movie_key'] ?? '');
        if ($kind === 'trim') {
            $mk = gos_lyre_str($job['dest_key'] ?? $job['clip_key'] ?? $mk);
        }
        if ($status === 'done' && $mk !== '') {
            $out['src'] = 'me:' . $mk;
        }
    } elseif (!empty($job['key'])) {
        $out['key'] = $job['key'];
        $out['src'] = 'me:' . $job['key'];
    }

    return $out;
}

/**
 * @param array<string, mixed> $job
 */
function gos_lyre_cut_fail_job(array $job, string $error, ?int $ffmpegCode = null): array
{
    $job['status'] = 'failed';
    $job['error'] = $error;
    $job['finished_at'] = time();
    if ($ffmpegCode !== null) {
        $job['ffmpeg_code'] = $ffmpegCode;
    }
    $id = gos_lyre_str($job['request_id'] ?? '');
    if ($id !== '') {
        gos_lyre_job_write($id, $job);
    }

    return $job;
}

/**
 * @param array<string, mixed> $job
 */
function gos_lyre_cut_save_job(array $job): void
{
    $id = gos_lyre_str($job['request_id'] ?? '');
    if ($id !== '') {
        gos_lyre_job_write($id, $job);
    }
}

/** @return array<string, mixed> */
function gos_lyre_director_stitch(array $access, array $body): array
{
    $row = gos_lyre_director_resolve($access, $body, true);
    $boardId = gos_lyre_str($row['board_id'] ?? '');
    $clipIdIn = trim(gos_lyre_str($body['clip_id'] ?? ''));
    $job = gos_lyre_with_board_lock($boardId, function () use ($access, $row, $boardId, $clipIdIn) {
        $pgRow = gos_lyre_pg_select_id($boardId);
        if (!is_array($pgRow)) {
            gos_lyre_fail('not_found', 404);
        }
        $board = gos_lyre_payload_array($pgRow['payload'] ?? null);
        $clip = null;
        if ($clipIdIn === '') {
            $clip = gos_lyre_next_stitch_target($board);
        } else {
            $clip = gos_lyre_find_video_clip($board, $clipIdIn);
        }
        if (!is_array($clip)) {
            gos_lyre_fail('not_stitch_target', 409);
        }
        $clipId = gos_lyre_str($clip['id'] ?? '');
        $clipSrc = gos_lyre_str($clip['src'] ?? '');
        if ($clipSrc === '' || !gos_lyre_can_stitch_clip($board, $clipId, $clipSrc)) {
            gos_lyre_fail('not_stitch_target', 409);
        }
        $parts = gos_lyre_parts($board);
        $genKey = null;
        if (count($parts) >= 2) {
            $genKey = gos_lyre_cut_gen_key($boardId, count($parts) - 1);
        }
        $rid = gos_lyre_cut_new_id();
        $movieKey = gos_lyre_cut_compiled_movie_key($boardId);
        $movieIn = gos_lyre_cut_movie_input_key($board, $boardId);
        $clipKey = gos_lyre_cut_norm_key($clipSrc);
        $job = [
            'request_id' => $rid,
            'kind' => 'stitch',
            'status' => 'pending',
            'board_id' => $boardId,
            'project_id' => gos_lyre_str($row['id'] ?? ''),
            'user_id' => gos_lyre_user_id($access),
            'clip_id' => $clipId,
            'frame_id' => gos_lyre_str($clip['linkedFrameId'] ?? ''),
            'movie_key' => $movieKey,
            'movie_in_key' => $movieIn,
            'clip_key' => $clipKey,
            'gen_key' => $genKey,
            'drop_last' => true,
            'keep_sec' => null,
            'expected_updated_at' => (string) ($pgRow['updated_at'] ?? ''),
            'key' => null,
            'duration' => null,
            'fps' => null,
            'error' => null,
            'ffmpeg_code' => null,
            'created_at' => time(),
            'started_at' => null,
            'finished_at' => null,
            'attached_at' => null,
            'actor' => gos_lyre_is_mcp($access) ? 'bot' : 'phone',
        ];
        gos_lyre_job_write($rid, $job);

        return $job;
    });
    $genKey = gos_lyre_str($job['gen_key'] ?? '');
    $movieIn = gos_lyre_str($job['movie_in_key'] ?? '');
    if ($genKey !== '' && $movieIn !== '') {
        try {
            gos_lyre_cut_snapshot_gen($movieIn, $genKey);
        } catch (Throwable) {
        }
    }

    return [
        'ok' => true,
        'status' => 'pending',
        'request_id' => gos_lyre_str($job['request_id'] ?? ''),
        'kind' => 'stitch',
        'board_id' => $boardId,
    ];
}

/** @return array<string, mixed> */
function gos_lyre_director_pop(array $access, array $body): array
{
    $row = gos_lyre_director_resolve($access, $body, true);
    $boardId = gos_lyre_str($row['board_id'] ?? '');
    $job = gos_lyre_with_board_lock($boardId, function () use ($access, $row, $boardId) {
        $pgRow = gos_lyre_pg_select_id($boardId);
        if (!is_array($pgRow)) {
            gos_lyre_fail('not_found', 404);
        }
        $board = gos_lyre_payload_array($pgRow['payload'] ?? null);
        $parts = [];
        foreach (gos_lyre_parts($board) as $part) {
            if (is_array($part)) {
                $parts[] = $part;
            }
        }
        if (count($parts) <= 1) {
            gos_lyre_fail('nothing_to_pop', 409);
        }
        $last = $parts[count($parts) - 1];
        $remaining = array_slice($parts, 0, -1);
        $remainingOut = [];
        foreach ($remaining as $part) {
            $remainingOut[] = [
                'clipId' => gos_lyre_str($part['clipId'] ?? ''),
                'src' => gos_lyre_cut_norm_key(gos_lyre_str($part['src'] ?? '')),
                'durationSec' => gos_lyre_num($part['durationSec'] ?? 0),
            ];
        }
        $genKey = null;
        if (count($remainingOut) >= 2) {
            $genKey = gos_lyre_cut_gen_key($boardId, count($remainingOut) - 1);
        }
        $rid = gos_lyre_cut_new_id();
        $job = [
            'request_id' => $rid,
            'kind' => 'pop',
            'status' => 'pending',
            'board_id' => $boardId,
            'project_id' => gos_lyre_str($row['id'] ?? ''),
            'user_id' => gos_lyre_user_id($access),
            'clip_id' => gos_lyre_str($last['clipId'] ?? ''),
            'frame_id' => '',
            'movie_key' => gos_lyre_cut_compiled_movie_key($boardId),
            'clip_key' => gos_lyre_cut_norm_key(gos_lyre_str($last['src'] ?? '')),
            'gen_key' => $genKey,
            'remaining_parts' => $remainingOut,
            'drop_last' => true,
            'keep_sec' => null,
            'expected_updated_at' => (string) ($pgRow['updated_at'] ?? ''),
            'key' => null,
            'duration' => null,
            'fps' => null,
            'error' => null,
            'ffmpeg_code' => null,
            'created_at' => time(),
            'started_at' => null,
            'finished_at' => null,
            'attached_at' => null,
            'actor' => gos_lyre_is_mcp($access) ? 'bot' : 'phone',
        ];
        gos_lyre_job_write($rid, $job);

        return $job;
    });

    return [
        'ok' => true,
        'status' => 'pending',
        'request_id' => gos_lyre_str($job['request_id'] ?? ''),
        'kind' => 'pop',
        'board_id' => $boardId,
    ];
}

/**
 * @param array<string, mixed> $mutateOut
 * @return array<string, mixed>|null
 */
function gos_lyre_cut_enqueue_trim(array $access, array $body, array $mutateOut, string $clipId): ?array
{
    if ($clipId === '' || $clipId === 'lc_movie') {
        return null;
    }
    $boardId = gos_lyre_str($mutateOut['board_id'] ?? '');
    if ($boardId === '') {
        return null;
    }
    $pgRow = gos_lyre_pg_select_id($boardId);
    if (!is_array($pgRow)) {
        return null;
    }
    $board = gos_lyre_payload_array($pgRow['payload'] ?? null);
    if (gos_lyre_find_audio_clip($board, $clipId) !== null) {
        return null;
    }
    $clip = gos_lyre_find_video_clip($board, $clipId);
    if (!is_array($clip)) {
        return null;
    }
    if (gos_lyre_str($clip['linkedFrameId'] ?? '') !== '') {
        return null;
    }
    if (gos_lyre_is_stitched_member($board, $clipId) || gos_lyre_clip_in_movie($board, $clipId)) {
        return null;
    }
    $row = gos_lyre_director_resolve($access, $body, true);
    $src = gos_lyre_cut_norm_key(gos_lyre_str($clip['src'] ?? ''));
    $orig = gos_lyre_cut_norm_key(gos_lyre_str($clip['origSrc'] ?? $src));
    if ($src === '') {
        return null;
    }
    $inn = gos_lyre_num($clip['trimInSec'] ?? 0);
    $dur = gos_lyre_num($clip['durationSec'] ?? 0);
    $rid = gos_lyre_cut_new_id();
    $destKey = 'boards/' . $boardId . '/videos/' . gos_lyre_media_id('vid') . '.mp4';
    $job = [
        'request_id' => $rid,
        'kind' => 'trim',
        'status' => 'pending',
        'board_id' => $boardId,
        'project_id' => gos_lyre_str($row['id'] ?? ''),
        'user_id' => gos_lyre_user_id($access),
        'clip_id' => $clipId,
        'frame_id' => gos_lyre_str($clip['linkedFrameId'] ?? $body['frame_id'] ?? ''),
        'movie_key' => null,
        'clip_key' => $src,
        'orig_key' => $orig !== '' ? $orig : $src,
        'input_key' => $src,
        'dest_key' => $destKey,
        'in_sec' => $inn,
        'out_sec' => $inn + $dur,
        'drop_last' => false,
        'keep_sec' => null,
        'expected_updated_at' => (string) ($mutateOut['updated_at'] ?? $pgRow['updated_at'] ?? ''),
        'key' => null,
        'duration' => null,
        'fps' => null,
        'error' => null,
        'ffmpeg_code' => null,
        'created_at' => time(),
        'started_at' => null,
        'finished_at' => null,
        'attached_at' => null,
        'actor' => gos_lyre_is_mcp($access) ? 'bot' : 'phone',
    ];
    gos_lyre_job_write($rid, $job);

    return $job;
}

function gos_lyre_cut_apply_stitch_payload(array $board, array $job, array $probe): array
{
    $resolved = gos_lyre_resolved_movie($board) ?? [];
    $parts = [];
    foreach (gos_lyre_arr($resolved['parts'] ?? []) as $part) {
        if (is_array($part)) {
            $parts[] = $part;
        }
    }
    $clipId = gos_lyre_str($job['clip_id'] ?? '');
    $clipKey = gos_lyre_str($job['clip_key'] ?? '');
    $already = false;
    foreach ($parts as $part) {
        if (gos_lyre_str($part['clipId'] ?? '') === $clipId) {
            $already = true;
            break;
        }
    }
    if (!$already) {
        $clip = gos_lyre_find_video_clip($board, $clipId);
        $dur = is_array($clip) ? gos_lyre_num($clip['durationSec'] ?? 0) : 0.0;
        $parts[] = [
            'clipId' => $clipId,
            'src' => $clipKey,
            'durationSec' => $dur,
        ];
    }
    $movie = gos_lyre_movie($board) ?? [];
    $movie['src'] = gos_lyre_str($job['movie_key'] ?? '');
    $movie['durationSec'] = gos_lyre_cut_playable_duration($probe);
    $fps = (float) ($probe['fps'] ?? 24);
    $movie['fps'] = $fps > 0 ? $fps : 24.0;
    $movie['parts'] = $parts;
    unset($movie['playDurationSec']);
    $board['movie'] = $movie;

    return $board;
}

function gos_lyre_cut_apply_pop_payload(array $board, array $job, array $probe): array
{
    $parts = [];
    foreach (gos_lyre_parts($board) as $part) {
        if (is_array($part)) {
            $parts[] = $part;
        }
    }
    if ($parts !== []) {
        array_pop($parts);
    }
    $remaining = isset($job['remaining_parts']) && is_array($job['remaining_parts'])
        ? $job['remaining_parts']
        : $parts;
    $clean = [];
    foreach ($remaining as $part) {
        if (is_array($part)) {
            $clean[] = [
                'clipId' => gos_lyre_str($part['clipId'] ?? ''),
                'src' => gos_lyre_str($part['src'] ?? ''),
                'durationSec' => gos_lyre_num($part['durationSec'] ?? 0),
            ];
        }
    }
    if (count($clean) <= 1) {
        $first = $clean[0] ?? null;
        $clip = null;
        if (is_array($first)) {
            $clip = gos_lyre_find_video_clip($board, gos_lyre_str($first['clipId'] ?? ''));
        }
        if (!is_array($clip)) {
            $clip = gos_lyre_ordered_video_clips($board)[0] ?? null;
        }
        $src = is_array($clip) ? gos_lyre_str($clip['src'] ?? '') : (is_array($first) ? gos_lyre_str($first['src'] ?? '') : '');
        $dur = is_array($clip)
            ? gos_lyre_js_or($clip['sourceDurationSec'] ?? null, gos_lyre_num($clip['durationSec'] ?? 0))
            : (is_array($first) ? gos_lyre_num($first['durationSec'] ?? 0) : 0.0);
        $part = is_array($first) ? $first : [
            'clipId' => is_array($clip) ? gos_lyre_str($clip['id'] ?? '') : '',
            'src' => $src,
            'durationSec' => is_array($clip) ? gos_lyre_num($clip['durationSec'] ?? 0) : $dur,
        ];
        if ($src !== '') {
            $part['src'] = $src;
        }
        $board['movie'] = [
            'src' => $src,
            'durationSec' => $dur,
            'fps' => null,
            'parts' => [$part],
        ];
    } else {
        $movie = gos_lyre_movie($board) ?? [];
        $movie['parts'] = $clean;
        $movie['src'] = gos_lyre_str($job['movie_key'] ?? '');
        $movie['durationSec'] = gos_lyre_cut_playable_duration($probe);
        $fps = (float) ($probe['fps'] ?? 24);
        $movie['fps'] = $fps > 0 ? $fps : 24.0;
        unset($movie['playDurationSec']);
        $board['movie'] = $movie;
    }

    return $board;
}

function gos_lyre_cut_apply_trim_payload(array $board, array $job, array $probe): array
{
    $clipId = gos_lyre_str($job['clip_id'] ?? '');
    $dest = gos_lyre_str($job['dest_key'] ?? '');
    $orig = gos_lyre_str($job['orig_key'] ?? '');
    $duration = gos_lyre_cut_playable_duration($probe);
    $fps = (float) ($probe['fps'] ?? 0);
    $board = gos_lyre_map_video_clip($board, $clipId, static function (array $clip) use ($dest, $orig, $duration, $fps) {
        if (gos_lyre_str($clip['origSrc'] ?? '') === '' && $orig !== '') {
            $clip['origSrc'] = $orig;
        }
        $clip['src'] = $dest;
        $clip['durationSec'] = $duration;
        $clip['sourceDurationSec'] = $duration;
        $clip['trimInSec'] = 0.0;
        if ($fps > 0) {
            $clip['fps'] = $fps;
        }

        return $clip;
    });
    $clip = gos_lyre_find_video_clip($board, $clipId);
    $fid = is_array($clip) ? gos_lyre_str($clip['linkedFrameId'] ?? '') : '';
    if ($fid !== '') {
        $scenes = [];
        foreach (gos_lyre_arr($board['scenes'] ?? []) as $scene) {
            if (!is_array($scene)) {
                continue;
            }
            $frames = [];
            foreach (gos_lyre_arr($scene['frames'] ?? []) as $frame) {
                if (is_array($frame) && gos_lyre_str($frame['id'] ?? '') === $fid) {
                    $frame['videoSrc'] = $dest;
                    $frame['videoDurationSec'] = $duration;
                }
                $frames[] = $frame;
            }
            $scene['frames'] = $frames;
            $scenes[] = $scene;
        }
        $board['scenes'] = $scenes;
    }

    return $board;
}

function gos_lyre_cut_revalidate(array $job, array $board): ?string
{
    $kind = gos_lyre_str($job['kind'] ?? '');
    $clipId = gos_lyre_str($job['clip_id'] ?? '');
    if ($kind === 'stitch') {
        $clip = gos_lyre_find_video_clip($board, $clipId);
        $src = is_array($clip) ? gos_lyre_cut_norm_key(gos_lyre_str($clip['src'] ?? '')) : '';
        if ($src === '' || !gos_lyre_can_stitch_clip($board, $clipId, $src)) {
            return 'not_stitch_target';
        }
        $next = gos_lyre_next_stitch_target($board);
        if (!is_array($next) || gos_lyre_str($next['id'] ?? '') !== $clipId) {
            return 'not_stitch_target';
        }
        $want = gos_lyre_cut_norm_key(gos_lyre_str($job['clip_key'] ?? ''));
        if ($want !== '' && $src !== $want) {
            return 'not_stitch_target';
        }

        return null;
    }
    if ($kind === 'pop') {
        $parts = gos_lyre_parts($board);
        if ($parts === []) {
            return 'conflict';
        }
        $last = $parts[count($parts) - 1];
        if (!is_array($last) || gos_lyre_str($last['clipId'] ?? '') !== $clipId) {
            return 'conflict';
        }

        return null;
    }
    if ($kind === 'trim') {
        if ($clipId === 'lc_movie' || gos_lyre_is_stitched_member($board, $clipId)) {
            return 'movie_locked';
        }
        $audio = gos_lyre_find_audio_clip($board, $clipId);
        if (is_array($audio)) {
            return 'conflict';
        }
        $clip = gos_lyre_find_video_clip($board, $clipId);
        if (!is_array($clip)) {
            return 'conflict';
        }
        $fid = gos_lyre_str($clip['linkedFrameId'] ?? '');
        if ($fid !== '' && gos_lyre_is_picture_locked($board, $fid)) {
            return 'movie_locked';
        }
        if ($fid === '' && gos_lyre_is_movie_locked($board, $clipId)) {
            return 'movie_locked';
        }

        return null;
    }

    return 'conflict';
}

function gos_lyre_cut_rename_onto(string $tmpPath, string $destPath): bool
{
    $dir = dirname($destPath);
    if (!is_dir($dir) && !@mkdir($dir, 0775, true) && !is_dir($dir)) {
        return false;
    }
    if (@rename($tmpPath, $destPath)) {
        return is_file($destPath) && filesize($destPath) > 0;
    }

    return gos_lyre_copy_file($tmpPath, $destPath);
}

function gos_lyre_cut_job_dest_key(array $job): string
{
    if (gos_lyre_str($job['kind'] ?? '') === 'trim') {
        return gos_lyre_str($job['dest_key'] ?? '');
    }

    return gos_lyre_str($job['movie_key'] ?? '');
}

function gos_lyre_cut_job_keep_sec(array $job): ?float
{
    if (!array_key_exists('keep_sec', $job) || $job['keep_sec'] === null || $job['keep_sec'] === '') {
        return null;
    }
    $n = gos_lyre_num($job['keep_sec'], 0.0);
    if (!is_finite($n)) {
        return null;
    }

    return $n;
}

function gos_lyre_cut_try_publish_local(string $tmpPath, ?string $stagedPath, ?string $destPath): bool
{
    if ($destPath === null || $destPath === '') {
        return false;
    }
    foreach ([$stagedPath, $tmpPath] as $from) {
        if (!is_string($from) || $from === '' || !is_file($from)) {
            continue;
        }
        if (gos_lyre_cut_rename_onto($from, $destPath)) {
            return true;
        }
    }

    return false;
}

function gos_lyre_cut_mark_job_applied(array &$job, array $probe, bool $fileOk): void
{
    $job['status'] = 'done';
    $job['uploaded'] = false;
    $job['duration'] = gos_lyre_cut_playable_duration($probe);
    $job['fps'] = $probe['fps'] ?? null;
    $job['finished_at'] = time();
    if ($fileOk) {
        $job['error'] = null;
    } else {
        $job['error'] = 'storage_error';
    }
    gos_lyre_cut_save_job($job);
}

/**
 * @return 'done'|'failed'
 */
function gos_lyre_cut_cas_apply(array &$job, string $tmpPath, array $probe): string
{
    $boardId = gos_lyre_str($job['board_id'] ?? '');
    $kind = gos_lyre_str($job['kind'] ?? '');
    $userId = (int) ($job['user_id'] ?? 0);
    $projectId = gos_lyre_str($job['project_id'] ?? '');
    $needFile = !($kind === 'pop' && count($job['remaining_parts'] ?? []) <= 1);
    if ($needFile && ($tmpPath === '' || !is_file($tmpPath))) {
        gos_lyre_cut_fail_job($job, 'storage_error', isset($job['ffmpeg_code']) ? (int) $job['ffmpeg_code'] : null);
        $job = gos_lyre_job_read(gos_lyre_str($job['request_id'] ?? '')) ?? $job;

        return 'failed';
    }
    $destKey = gos_lyre_cut_job_dest_key($job);
    $destPath = ($needFile && $destKey !== '') ? gos_lyre_cut_dest_path($destKey) : null;
    $stagedPath = null;
    if ($needFile && $tmpPath !== '' && is_file($tmpPath) && $destPath !== null) {
        $stagedPath = $destPath . '.caspart';
        if (!gos_lyre_copy_file($tmpPath, $stagedPath)) {
            $stagedPath = null;
        }
    }
    $applied = false;
    $fileOk = !$needFile;
    for ($i = 0; $i < GOS_LYRE_CUT_RETRY_MAX; $i++) {
        $fh = null;
        try {
            $fh = gos_lyre_board_lock($boardId);
            $pgRow = gos_lyre_pg_select_id($boardId);
            if (!is_array($pgRow)) {
                gos_lyre_board_unlock($fh);
                gos_lyre_cut_fail_job($job, 'not_found');

                return 'failed';
            }
            $board = gos_lyre_payload_array($pgRow['payload'] ?? null);
            $stamp = (string) ($pgRow['updated_at'] ?? '');
            $expected = gos_lyre_str($job['expected_updated_at'] ?? '');
            if ($stamp !== $expected) {
                $err = gos_lyre_cut_revalidate($job, $board);
                if ($err !== null) {
                    gos_lyre_board_unlock($fh);
                    gos_lyre_cut_fail_job($job, $err, isset($job['ffmpeg_code']) ? (int) $job['ffmpeg_code'] : null);

                    return 'failed';
                }
                $expected = $stamp;
            }
            if ($kind === 'stitch') {
                $next = gos_lyre_cut_apply_stitch_payload($board, $job, $probe);
            } elseif ($kind === 'pop') {
                $next = gos_lyre_cut_apply_pop_payload($board, $job, $probe);
            } else {
                $next = gos_lyre_cut_apply_trim_payload($board, $job, $probe);
            }
            try {
                gos_lyre_pg_update_cas(gos_lyre_pg_maybe(), $boardId, $next, $expected);
            } catch (GosLyreException $e) {
                gos_lyre_board_unlock($fh);
                if ($e->error === 'conflict') {
                    continue;
                }
                gos_lyre_cut_fail_job($job, $e->error);

                return 'failed';
            }
            $applied = true;
            gos_lyre_cut_mark_job_applied($job, $probe, !$needFile);
            if ($userId > 0 && $projectId !== '') {
                gos_lyre_touch_project($userId, $projectId);
            }
            if ($needFile) {
                $fileOk = gos_lyre_cut_try_publish_local($tmpPath, $stagedPath, $destPath);
            }
            $summary = match ($kind) {
                'stitch' => 'Stitched · ' . gos_lyre_str($job['clip_id'] ?? ''),
                'pop' => 'Pop · ' . gos_lyre_str($job['clip_id'] ?? ''),
                default => 'Trim file · ' . gos_lyre_str($job['clip_id'] ?? ''),
            };
            gos_lyre_activity_append_line($boardId, [
                'ts' => (int) round(microtime(true) * 1000),
                'type' => $kind,
                'projectId' => $projectId,
                'clipId' => gos_lyre_str($job['clip_id'] ?? ''),
                'summary' => $summary,
                'actor' => gos_lyre_str($job['actor'] ?? 'bot') !== '' ? gos_lyre_str($job['actor'] ?? 'bot') : 'bot',
            ]);
            gos_lyre_cut_mark_job_applied($job, $probe, $fileOk);
            gos_lyre_board_unlock($fh);
            break;
        } catch (GosLyreException $e) {
            if (is_resource($fh)) {
                gos_lyre_board_unlock($fh);
            }
            if ($applied) {
                gos_lyre_cut_mark_job_applied($job, $probe, $fileOk);
                break;
            }
            if ($e->error === 'lock_timeout' || $e->error === 'conflict') {
                continue;
            }
            gos_lyre_cut_fail_job($job, $e->error);

            return 'failed';
        } catch (Throwable) {
            if (is_resource($fh)) {
                gos_lyre_board_unlock($fh);
            }
            if ($applied) {
                gos_lyre_cut_mark_job_applied($job, $probe, $fileOk);
                break;
            }
            gos_lyre_cut_fail_job($job, 'storage_error');

            return 'failed';
        }
    }
    if (!$applied) {
        gos_lyre_cut_fail_job($job, 'conflict', isset($job['ffmpeg_code']) ? (int) $job['ffmpeg_code'] : null);

        return 'failed';
    }
    if ($needFile && $destKey !== '') {
        if (!$fileOk) {
            $fileOk = gos_lyre_cut_try_publish_local($tmpPath, $stagedPath, $destPath);
        }
        $uploaded = false;
        if ($fileOk && is_string($destPath) && is_file($destPath)) {
            $uploaded = gos_lyre_grokme_put_file($destKey, $destPath);
        }
        $job['status'] = 'done';
        $job['uploaded'] = $uploaded;
        $job['error'] = $fileOk ? null : 'storage_error';
        $job['duration'] = gos_lyre_cut_playable_duration($probe);
        $job['fps'] = $probe['fps'] ?? null;
        $job['finished_at'] = time();
        gos_lyre_cut_save_job($job);
    }

    return 'done';
}

/**
 * @return list<array<string, mixed>>
 */
function gos_lyre_cut_list_claimable(): array
{
    $dir = gos_lyre_jobs_dir();
    if (!is_dir($dir)) {
        return [];
    }
    $hits = [];
    foreach (glob($dir . '/*.json') ?: [] as $path) {
        $raw = file_get_contents($path);
        $job = is_string($raw) ? json_decode($raw, true) : null;
        if (!is_array($job)) {
            continue;
        }
        $kind = gos_lyre_str($job['kind'] ?? '');
        $status = gos_lyre_str($job['status'] ?? '');
        if (!in_array($kind, ['stitch', 'trim', 'pop'], true)) {
            continue;
        }
        if ($status !== 'pending' && $status !== 'running') {
            continue;
        }
        $hits[] = $job;
    }
    usort($hits, static function ($a, $b) {
        $ca = (int) ($a['created_at'] ?? 0);
        $cb = (int) ($b['created_at'] ?? 0);
        if ($ca === $cb) {
            return gos_lyre_str($a['request_id'] ?? '') <=> gos_lyre_str($b['request_id'] ?? '');
        }

        return $ca <=> $cb;
    });

    return $hits;
}

function gos_lyre_cut_pairwise_rebuild(array $parts, string $outPath): array
{
    $paths = [];
    foreach ($parts as $part) {
        if (!is_array($part)) {
            continue;
        }
        $src = gos_lyre_str($part['src'] ?? '');
        if ($src === '') {
            return ['ok' => false, 'code' => null, 'error' => 'source_missing'];
        }
        $local = gos_lyre_ensure_local_file($src);
        if ($local === null) {
            return ['ok' => false, 'code' => null, 'error' => 'source_missing'];
        }
        $paths[] = $local;
    }
    if ($paths === []) {
        return ['ok' => false, 'code' => null, 'error' => 'source_missing'];
    }
    if (count($paths) === 1) {
        return gos_lyre_copy_file($paths[0], $outPath)
            ? ['ok' => true, 'code' => 0]
            : ['ok' => false, 'code' => null, 'error' => 'storage_error'];
    }
    $acc = $paths[0];
    $code = 0;
    for ($i = 1; $i < count($paths); $i++) {
        $step = $outPath . '.r' . $i . '.mp4';
        $enc = gos_lyre_cut_ffmpeg_stitch($acc, $paths[$i], true, null, $step);
        $code = $enc['code'] ?? $code;
        if (empty($enc['ok'])) {
            $enc['code'] = $code;

            return $enc;
        }
        if ($acc !== $paths[0] && is_file($acc) && str_contains($acc, '.r')) {
            @unlink($acc);
        }
        $acc = $step;
    }
    if ($acc !== $outPath) {
        if (!gos_lyre_copy_file($acc, $outPath) && !@rename($acc, $outPath)) {
            return ['ok' => false, 'code' => $code, 'error' => 'storage_error'];
        }
        if ($acc !== $outPath && is_file($acc)) {
            @unlink($acc);
        }
    }

    return ['ok' => true, 'code' => $code];
}

function gos_lyre_cut_run_job(array $job): void
{
    $id = gos_lyre_str($job['request_id'] ?? '');
    $kind = gos_lyre_str($job['kind'] ?? '');
    $boardId = gos_lyre_str($job['board_id'] ?? '');
    $job['status'] = 'running';
    $job['started_at'] = time();
    gos_lyre_cut_save_job($job);
    $tmp = gos_lyre_cut_tmp_path($boardId, $id);
    try {
        if ($kind === 'stitch') {
            if ($tmp === null) {
                gos_lyre_cut_fail_job($job, 'storage_error');

                return;
            }
            $movieKey = gos_lyre_str($job['movie_in_key'] ?? $job['movie_key'] ?? '');
            $clipKey = gos_lyre_str($job['clip_key'] ?? '');
            $genKey = gos_lyre_str($job['gen_key'] ?? '');
            if ($genKey !== '' && $movieKey !== '') {
                gos_lyre_cut_snapshot_gen($movieKey, $genKey);
            }
            $moviePath = gos_lyre_ensure_local_file($movieKey);
            $clipPath = gos_lyre_ensure_local_file($clipKey);
            if ($moviePath === null || $clipPath === null) {
                gos_lyre_cut_fail_job($job, 'source_missing');

                return;
            }
            $enc = gos_lyre_cut_ffmpeg_stitch(
                $moviePath,
                $clipPath,
                (bool) ($job['drop_last'] ?? true),
                gos_lyre_cut_job_keep_sec($job),
                $tmp
            );
            $job['ffmpeg_code'] = $enc['code'];
            gos_lyre_cut_save_job($job);
            if (empty($enc['ok'])) {
                gos_lyre_cut_fail_job($job, (string) ($enc['error'] ?? 'stitch_failed'), $enc['code'] ?? null);

                return;
            }
            $probe = gos_lyre_cut_probe_file($tmp, true) ?? ['duration' => 0.0, 'fps' => 24.0, 'frames' => 1, 'hasAudio' => false, 'width' => 0, 'height' => 0];
            gos_lyre_cut_cas_apply($job, $tmp, $probe);

            return;
        }
        if ($kind === 'trim') {
            if ($tmp === null) {
                gos_lyre_cut_fail_job($job, 'storage_error');

                return;
            }
            $inputKey = gos_lyre_str($job['input_key'] ?? $job['orig_key'] ?? $job['clip_key'] ?? '');
            $input = gos_lyre_ensure_local_file($inputKey);
            if ($input === null) {
                gos_lyre_cut_fail_job($job, 'source_missing');

                return;
            }
            $enc = gos_lyre_cut_ffmpeg_trim(
                $input,
                $tmp,
                gos_lyre_num($job['in_sec'] ?? 0),
                gos_lyre_num($job['out_sec'] ?? 0)
            );
            $job['ffmpeg_code'] = $enc['code'];
            gos_lyre_cut_save_job($job);
            if (empty($enc['ok'])) {
                gos_lyre_cut_fail_job($job, (string) ($enc['error'] ?? 'trim_failed'), $enc['code'] ?? null);

                return;
            }
            $probe = gos_lyre_cut_probe_file($tmp, true) ?? ['duration' => 0.0, 'fps' => 24.0, 'frames' => 1, 'hasAudio' => false, 'width' => 0, 'height' => 0];
            gos_lyre_cut_cas_apply($job, $tmp, $probe);

            return;
        }
        if ($kind === 'pop') {
            $remaining = isset($job['remaining_parts']) && is_array($job['remaining_parts']) ? $job['remaining_parts'] : [];
            $probe = ['duration' => 0.0, 'fps' => 24.0, 'frames' => 1, 'hasAudio' => false, 'width' => 0, 'height' => 0];
            if (count($remaining) <= 1) {
                $job['ffmpeg_code'] = 0;
                gos_lyre_cut_save_job($job);
                $first = $remaining[0] ?? null;
                if (is_array($first)) {
                    $src = gos_lyre_str($first['src'] ?? '');
                    if ($src !== '') {
                        $local = gos_lyre_ensure_local_file($src);
                        if ($local !== null) {
                            $probed = gos_lyre_cut_probe_file($local, true);
                            if (is_array($probed)) {
                                $probe = $probed;
                            }
                        }
                    }
                    if ((float) ($probe['duration'] ?? 0) <= 0) {
                        $probe['duration'] = gos_lyre_num($first['durationSec'] ?? 0);
                    }
                }
                gos_lyre_cut_cas_apply($job, '', $probe);

                return;
            }
            if ($tmp === null) {
                gos_lyre_cut_fail_job($job, 'storage_error');

                return;
            }
            $genKey = gos_lyre_str($job['gen_key'] ?? '');
            $restored = false;
            if ($genKey !== '') {
                $genPath = gos_lyre_local_object_path($genKey);
                if ($genPath === null) {
                    $genPath = gos_lyre_ensure_local_file($genKey);
                }
                if ($genPath !== null) {
                    $restored = gos_lyre_copy_file($genPath, $tmp);
                    $job['ffmpeg_code'] = $restored ? 0 : null;
                }
            }
            if (!$restored) {
                $enc = gos_lyre_cut_pairwise_rebuild($remaining, $tmp);
                $job['ffmpeg_code'] = $enc['code'];
                gos_lyre_cut_save_job($job);
                if (empty($enc['ok'])) {
                    gos_lyre_cut_fail_job($job, (string) ($enc['error'] ?? 'rebuild_failed'), $enc['code'] ?? null);

                    return;
                }
                if ($genKey !== '') {
                    $genDest = gos_lyre_cut_dest_path($genKey);
                    if ($genDest !== null) {
                        gos_lyre_copy_file($tmp, $genDest);
                    }
                }
            } else {
                gos_lyre_cut_save_job($job);
            }
            $probed = gos_lyre_cut_probe_file($tmp, true);
            if (is_array($probed)) {
                $probe = $probed;
            }
            gos_lyre_cut_cas_apply($job, $tmp, $probe);
        }
    } catch (GosLyreException $e) {
        gos_lyre_cut_fail_job($job, $e->error);
    } catch (Throwable) {
        gos_lyre_cut_fail_job($job, 'storage_error');
    }
}

function gos_lyre_cut_pump(): bool
{
    foreach (gos_lyre_cut_list_claimable() as $job) {
        $id = gos_lyre_str($job['request_id'] ?? '');
        if ($id === '' || !gos_lyre_job_id_ok($id)) {
            continue;
        }
        $lockPath = gos_lyre_jobs_dir() . '/' . $id . '.lock';
        $fh = @fopen($lockPath, 'c+');
        if ($fh === false) {
            continue;
        }
        if (!flock($fh, LOCK_EX | LOCK_NB)) {
            fclose($fh);
            continue;
        }
        try {
            $fresh = gos_lyre_job_read($id);
            if (!is_array($fresh)) {
                continue;
            }
            $status = gos_lyre_str($fresh['status'] ?? '');
            $kind = gos_lyre_str($fresh['kind'] ?? '');
            if (!in_array($kind, ['stitch', 'trim', 'pop'], true)) {
                continue;
            }
            if ($status === 'done' || $status === 'failed') {
                continue;
            }
            gos_lyre_cut_run_job($fresh);

            return true;
        } finally {
            flock($fh, LOCK_UN);
            fclose($fh);
        }
    }

    return false;
}
