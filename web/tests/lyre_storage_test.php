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

if ($fails > 0) {
    fwrite(STDERR, "{$fails} failed\n");
    exit(1);
}
fwrite(STDOUT, "ok\n");
