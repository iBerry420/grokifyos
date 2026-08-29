#!/usr/bin/env php
<?php

declare(strict_types=1);

/**
 * Drain LYRE stitch/trim/pop jobs. systemd --loop; never spawned from Apache.
 *
 *   php scripts/lyre-cut-worker.php          # run until the queue is empty
 *   php scripts/lyre-cut-worker.php --loop   # daemon: idle-sleep, keep pumping
 */

if (PHP_SAPI !== 'cli') {
    fwrite(STDERR, "lyre-cut-worker: cli only\n");
    exit(1);
}

define('GOS_SKIP_SESSION', true);
define('GOS_LYRE_NO_ROUTE', true);
require_once dirname(__DIR__) . '/web/api/lyre.php';

@ini_set('memory_limit', '256M');
@set_time_limit(0);

$loop = in_array('--loop', $argv, true);
$root = dirname(__DIR__);
$lockPath = $root . '/storage/lyre-cut-worker.lock';
$override = getenv('GOS_LYRE_CUT_LOCK');
if (is_string($override) && $override !== '') {
    $lockPath = $override;
}
$lockDir = dirname($lockPath);
if (!is_dir($lockDir)) {
    @mkdir($lockDir, 0775, true);
}

$fh = @fopen($lockPath, 'c+');
if ($fh === false) {
    fwrite(STDERR, "lyre-cut-worker: cannot open lock\n");
    exit(1);
}
if (!flock($fh, LOCK_EX | LOCK_NB)) {
    fwrite(STDOUT, "lyre-cut-worker: already running\n");
    exit(0);
}

fwrite($fh, (string) getmypid());
fflush($fh);

$idle = 0;
while (true) {
    $did = function_exists('gos_lyre_cut_pump') ? gos_lyre_cut_pump() : false;
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
