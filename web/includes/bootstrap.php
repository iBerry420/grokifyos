<?php

declare(strict_types=1);

require_once __DIR__ . '/paths.php';
require_once __DIR__ . '/db.php';
require_once __DIR__ . '/session.php';
require_once __DIR__ . '/auth.php';
require_once __DIR__ . '/system-chat.php';

if (!defined('GOS_SKIP_SESSION') || !GOS_SKIP_SESSION) {
    gos_session_start();
}
