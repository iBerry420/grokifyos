<?php

declare(strict_types=1);

/**
 * PHP built-in server router for local smoke tests.
 * Usage: php -S 127.0.0.1:8787 scripts/dev-router.php
 */

$uri = urldecode(parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/');
$root = dirname(__DIR__) . '/web';

if (preg_match('#^/mcp(?:\.php)?(/|$)#', $uri) === 1) {
    require $root . '/api/lyre-mcp.php';
    return true;
}

if (str_starts_with($uri, '/api/')) {
    $file = $root . $uri;
    if (is_file($file)) {
        require $file;
        return true;
    }
    http_response_code(404);
    header('Content-Type: application/json');
    echo json_encode(['ok' => false, 'error' => 'not_found']);
    return true;
}

if (str_starts_with($uri, '/assets/')) {
    $file = $root . $uri;
    if (is_file($file)) {
        return false; // let built-in server serve static
    }
}

// Imagine media + system-chat uploads live outside web/
if (str_starts_with($uri, '/uploads/')) {
    $file = dirname(__DIR__) . $uri;
    if (is_file($file) && is_readable($file)) {
        $ext = strtolower(pathinfo($file, PATHINFO_EXTENSION));
        $types = [
            'jpg' => 'image/jpeg',
            'jpeg' => 'image/jpeg',
            'png' => 'image/png',
            'gif' => 'image/gif',
            'webp' => 'image/webp',
            'bmp' => 'image/bmp',
            'mp4' => 'video/mp4',
            'webm' => 'video/webm',
            'mov' => 'video/quicktime',
            'm4v' => 'video/x-m4v',
            'json' => 'application/json',
        ];
        if (isset($types[$ext])) {
            header('Content-Type: ' . $types[$ext]);
            header('Content-Length: ' . (string) filesize($file));
            header('Cache-Control: public, max-age=86400');
            readfile($file);
            return true;
        }
        return false;
    }
    http_response_code(404);
    echo 'Not found';
    return true;
}

if ($uri === '/' || $uri === '') {
    require $root . '/public/index.php';
    return true;
}

$file = $root . '/public' . $uri;
if (is_file($file)) {
    return false;
}

http_response_code(404);
echo 'Not found';
return true;
