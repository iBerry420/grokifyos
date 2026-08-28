<?php

declare(strict_types=1);

define('GOS_SKIP_SESSION', true);

require_once dirname(__DIR__) . '/includes/bootstrap.php';

$channel = function_exists('gos_apk_channel')
    ? gos_apk_channel(isset($_GET['channel']) ? (string) $_GET['channel'] : 'phone')
    : 'phone';
$apk = gos_latest_apk($channel);
if ($apk === null) {
    http_response_code(404);
    header('Content-Type: text/plain; charset=utf-8');
    echo 'No APK published for channel=' . $channel . '.';
    exit;
}

$path = function_exists('gos_apk_absolute_path')
    ? gos_apk_absolute_path($apk)
    : (string) ($apk['file_path'] ?? '');
if ($path === '' || !is_readable($path)) {
    // Resolve relative to storage/apk
    $alt = gos_root() . '/storage/apk/' . basename((string) ($apk['file_name'] ?? $apk['file_path'] ?? ''));
    if (is_readable($alt)) {
        $path = $alt;
    } else {
        http_response_code(404);
        header('Content-Type: text/plain; charset=utf-8');
        echo 'APK file missing on disk.';
        exit;
    }
}

clearstatcache(true, $path);
$name = (string) ($apk['file_name'] ?? 'grokifyos.apk');
$size = (int) filesize($path);
$sha = hash_file('sha256', $path);
if ($size < 1 || $sha === false) {
    http_response_code(404);
    header('Content-Type: text/plain; charset=utf-8');
    echo 'APK file missing on disk.';
    exit;
}

while (ob_get_level() > 0) {
    ob_end_clean();
}

header('Content-Type: application/vnd.android.package-archive');
header('Content-Disposition: attachment; filename="' . str_replace('"', '', $name) . '"');
header('Content-Length: ' . $size);
header('Content-Encoding: identity');
header('Cache-Control: private, no-cache, no-store, no-transform');
header('X-Checksum-SHA256: ' . $sha);
readfile($path);
exit;
