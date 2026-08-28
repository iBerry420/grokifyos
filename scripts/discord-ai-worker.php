#!/usr/bin/env php
<?php

declare(strict_types=1);

/**
 * Drain Discord AI jobs (tag + analyze) without the phone staying in the foreground.
 *
 *   php scripts/discord-ai-worker.php          # run until the queue is empty
 *   php scripts/discord-ai-worker.php --loop   # daemon: idle-sleep, keep pumping, auto-tag live guilds
 */

if (PHP_SAPI !== 'cli') {
    fwrite(STDERR, "discord-ai-worker: cli only\n");
    exit(1);
}

$root = dirname(__DIR__);
require_once $root . '/web/api/discord.php';

@ini_set('memory_limit', '256M');
@set_time_limit(0);

$loop = in_array('--loop', $argv, true);
$lockPath = $root . '/storage/discord-ai-worker.lock';
$lockDir = dirname($lockPath);
if (!is_dir($lockDir)) {
    @mkdir($lockDir, 0775, true);
}

$fh = @fopen($lockPath, 'c+');
if ($fh === false) {
    fwrite(STDERR, "discord-ai-worker: cannot open lock\n");
    exit(1);
}
if (!flock($fh, LOCK_EX | LOCK_NB)) {
    fwrite(STDOUT, "discord-ai-worker: already running\n");
    exit(0);
}

fwrite($fh, (string) getmypid());
fflush($fh);

$idle = 0;
while (true) {
    $did = function_exists('gos_discord_ai_pump') ? gos_discord_ai_pump() : false;
    if ($did) {
        $idle = 0;
        continue;
    }
    if (!$loop) {
        break;
    }
    $idle++;
    $sleep = $idle < 10 ? 2 : 5;
    sleep($sleep);
}

flock($fh, LOCK_UN);
fclose($fh);
exit(0);
