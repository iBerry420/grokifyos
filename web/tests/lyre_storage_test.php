<?php

declare(strict_types=1);

/**
 * Unit tests for gos_lyre_storage_key.
 * Run: php web/tests/lyre_storage_test.php
 */

define('GOS_SKIP_SESSION', true);
define('GOS_LYRE_NO_ROUTE', true);

require_once dirname(__DIR__) . '/api/lyre.php';

$fails = 0;

function expect_eq(mixed $got, mixed $want, string $msg): void
{
    global $fails;
    if ($got !== $want) {
        $fails++;
        fwrite(STDERR, "FAIL: {$msg} got=" . var_export($got, true) . " want=" . var_export($want, true) . "\n");
    }
}

function expect_true(bool $cond, string $msg): void
{
    expect_eq($cond, true, $msg);
}

expect_eq(gos_lyre_storage_key('me:stills/st_n05cjkwshekr.jpg'), 'stills/st_n05cjkwshekr.jpg', 'me stills');
expect_eq(gos_lyre_storage_key('me:videos/vid_rqhfchf1vana.mp4'), 'videos/vid_rqhfchf1vana.mp4', 'me videos');
expect_eq(gos_lyre_storage_key('me:audio/st_4s24pyzzl4qw.mp3'), 'audio/st_4s24pyzzl4qw.mp3', 'me audio');
expect_eq(gos_lyre_storage_key('/stills/hall-01.jpg'), 'stills/hall-01.jpg', 'public still');
expect_eq(gos_lyre_storage_key('boards/lyre/clips/lc_b.mp4'), 'boards/lyre/clips/lc_b.mp4', 'board key');
expect_eq(
    gos_lyre_storage_key('https://me.grokpot.io/v1/storage/stills/st_abc.jpg'),
    'stills/st_abc.jpg',
    'grokme url'
);
expect_eq(
    gos_lyre_storage_key('https://lyre.grok.me/api/media?p=stills%2Fst_abc.jpg'),
    'stills/st_abc.jpg',
    'media query'
);
expect_eq(gos_lyre_storage_key('me:stills/../secret.jpg'), null, 'traversal');
expect_eq(gos_lyre_storage_key('etc/passwd'), null, 'unknown prefix');
expect_eq(gos_lyre_storage_key(''), null, 'empty');
expect_eq(gos_lyre_storage_key('me:stills/folder/x.jpg'), 'stills/folder/x.jpg', 'nested still');
expect_eq(gos_lyre_storage_key('seed/stills/hall-01.jpg'), 'seed/stills/hall-01.jpg', 'seed still');
expect_eq(gos_lyre_storage_key('me:audio/st_ou28brulfs6h.wav'), 'audio/st_ou28brulfs6h.wav', 'wav');
$rel = gos_lyre_storage_relatives('stills/hall-01.jpg');
expect_eq($rel[0] ?? null, 'stills/hall-01.jpg', 'rel self');
expect_eq(in_array('seed/stills/hall-01.jpg', $rel, true), true, 'rel seed');
expect_eq(in_array('public/stills/hall-01.jpg', $rel, true), true, 'rel public');
$relVid = gos_lyre_storage_relatives('videos/vid_a.mp4');
expect_eq($relVid, ['videos/vid_a.mp4'], 'video relatives stay self');
expect_eq(gos_lyre_voice_ids(['eve', 'LEO', 'nope', 'rex', 'sal']), ['eve', 'leo', 'rex'], 'voices cap 3');
expect_eq(gos_lyre_voice_ids(['zzz']), [], 'unknown voice dropped');
$tagged = gos_lyre_tag_prompt('Walk toward the fire.', 2, 1);
expect_eq(str_contains($tagged, '<IMAGE_0>'), true, 'image 0');
expect_eq(str_contains($tagged, '<IMAGE_1>'), true, 'image 1');
expect_eq(str_contains($tagged, '<AUDIO_0>'), true, 'audio 0');
$already = gos_lyre_tag_prompt('Use <IMAGE_0> please', 2, 0);
expect_eq(str_contains($already, '<IMAGE_1>'), false, 'do not retag');
$edit = gos_lyre_image_edit_payload('edit', ['data:image/jpeg;base64,abc'], '16:9');
expect_eq($edit['model'] ?? '', 'grok-imagine-image-2.0', 'image model');
expect_eq(is_array($edit['image'] ?? null), true, 'single image field');
$multi = gos_lyre_image_edit_payload('edit', ['data:image/jpeg;base64,a', 'data:image/jpeg;base64,b', 'data:image/jpeg;base64,c', 'data:image/jpeg;base64,d'], '');
expect_eq(count($multi['images'] ?? []), 3, 'image edit caps at 3');
$vid = gos_lyre_video_payload('go', ['data:image/jpeg;base64,a'], [], 6, '16:9', '720p', null, 'generate');
expect_eq($vid['model'] ?? '', 'grok-imagine-video-1.5', 'video model');
expect_eq(isset($vid['image']), true, 'i2v uses image');
$voiceOnly = gos_lyre_video_payload('go', ['data:image/jpeg;base64,a'], ['eve'], 6, '16:9', '720p', null, 'generate');
expect_eq(isset($voiceOnly['reference_audios']), true, 'voices force reference-to-video');
$refv = gos_lyre_video_payload('go', ['data:image/jpeg;base64,a', 'data:image/jpeg;base64,b'], ['eve', 'leo'], 8, '16:9', '1080p', null, 'generate');
expect_eq(isset($refv['reference_images']), true, 'refs');
expect_eq(count($refv['reference_audios'] ?? []), 2, 'two voices');
expect_eq($refv['resolution'] ?? '', '720p', 'ref-to-video caps 720p');
$edv = gos_lyre_video_payload('fix', [], [], 6, '16:9', '720p', 'data:video/mp4;base64,xx', 'edit');
expect_eq(isset($edv['video']), true, 'edit video field');
expect_eq(isset($edv['duration']), false, 'edit inherits duration');
expect_eq(gos_lyre_safe_board_id('lyre_phone_abc-def'), 'lyre_phone_abc-def', 'safe board hyphens');
expect_eq(gos_lyre_safe_board_id('lyre'), 'lyre', 'safe odysseus id');
expect_eq(gos_lyre_safe_board_id(''), null, 'safe empty');
expect_eq(gos_lyre_safe_board_id('../'), null, 'safe rejects ../');
expect_eq(gos_lyre_safe_board_id('..'), null, 'safe rejects ..');
expect_eq(gos_lyre_safe_board_id('foo/../bar'), null, 'safe rejects nested traversal');

$odRow = ['board_id' => 'lyre', 'is_odysseus' => 1];
expect_eq(gos_lyre_media_prefix($odRow), '', 'odysseus prefix empty');
$odStill = gos_lyre_media_key($odRow, 'stills', 'jpg');
expect_true(str_starts_with($odStill, 'stills/st_'), 'odysseus stills stay at root');
expect_eq(gos_lyre_storage_key($odStill), $odStill, 'odysseus still key is storage-safe');
$odVid = gos_lyre_media_key($odRow, 'videos', 'mp4');
expect_true(str_starts_with($odVid, 'videos/vid_'), 'odysseus videos stay at root');
expect_eq(gos_lyre_storage_key($odVid), $odVid, 'odysseus video key is storage-safe');

$phoneRow = ['board_id' => 'lyre_phone_abc-def', 'is_odysseus' => 0];
expect_eq(gos_lyre_media_prefix($phoneRow), 'boards/lyre_phone_abc-def/', 'phone prefix boards/id/');
$phStill = gos_lyre_media_key($phoneRow, 'stills', 'jpg');
expect_true(str_starts_with($phStill, 'boards/lyre_phone_abc-def/stills/st_'), 'phone stills under boards/id');
expect_eq(gos_lyre_storage_key($phStill), $phStill, 'phone still key is storage-safe');
$phVid = gos_lyre_media_key($phoneRow, 'videos', 'mp4');
expect_true(str_starts_with($phVid, 'boards/lyre_phone_abc-def/videos/vid_'), 'phone videos under boards/id');
expect_eq(gos_lyre_storage_key($phVid), $phVid, 'phone video key is storage-safe');
$phAud = gos_lyre_media_key($phoneRow, 'audio', 'wav');
expect_true(str_starts_with($phAud, 'boards/lyre_phone_abc-def/audio/st_'), 'phone audio under boards/id');
expect_eq(gos_lyre_storage_key($phAud), $phAud, 'phone audio key is storage-safe');
expect_eq(gos_lyre_storage_key('me:boards/lyre_phone_abc/stills/st_a.jpg'), 'boards/lyre_phone_abc/stills/st_a.jpg', 'me boards still');
expect_eq(gos_lyre_src_equal('me:videos/vid_a.mp4', 'videos/vid_a.mp4'), true, 'src equal me vs raw');
expect_eq(gos_lyre_src_equal('me:boards/x/videos/v.mp4', 'boards/x/videos/v.mp4'), true, 'src equal board prefix');
expect_eq(gos_lyre_src_equal('videos/a.mp4', 'videos/b.mp4'), false, 'src unequal');
expect_eq(gos_lyre_me_src('stills/st_a.jpg'), 'me:stills/st_a.jpg', 'me src prefix');
expect_eq(gos_lyre_me_src('me:stills/st_a.jpg'), 'me:stills/st_a.jpg', 'me src already');
expect_eq(gos_lyre_job_kind(['kind' => 'stitch', 'key' => null, 'movie_key' => 'boards/x/movie.mp4']), 'stitch', 'job kind stitch');
expect_eq(gos_lyre_job_kind(['kind' => 'video', 'key' => 'videos/vid_a.mp4']), 'video', 'job kind video');
expect_eq(gos_lyre_job_kind(['movie_key' => 'boards/x/movie.mp4']), 'stitch', 'job kind from movie_key');
expect_eq(gos_lyre_job_kind([]), 'video', 'job kind default video');
$phoneVidKey = gos_lyre_imagine_video_key(['board_id' => 'lyre_phone_abc']);
expect_true(is_string($phoneVidKey) && str_starts_with((string) $phoneVidKey, 'boards/lyre_phone_abc/videos/vid_'), 'status video key uses board prefix');
$odVidKey = gos_lyre_imagine_video_key(['board_id' => 'lyre']);
expect_true(is_string($odVidKey) && str_starts_with((string) $odVidKey, 'videos/vid_'), 'odysseus status video key is root');
$emptyVidKey = gos_lyre_imagine_video_key([]);
expect_true(is_string($emptyVidKey) && str_starts_with((string) $emptyVidKey, 'videos/vid_'), 'empty board_id video key is root');
$cf = tempnam(sys_get_temp_dir(), 'lyre-cf');
file_put_contents((string) $cf, 'x');
expect_eq(gos_lyre_commit_file('etc/passwd', (string) $cf), false, 'commit_file rejects unknown prefix');
expect_eq(gos_lyre_commit_file('../secret.mp4', (string) $cf), false, 'commit_file rejects traversal');
@unlink((string) $cf);

if ($fails > 0) {
    fwrite(STDERR, "{$fails} failed\n");
    exit(1);
}
fwrite(STDOUT, "ok\n");
