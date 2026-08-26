<?php

declare(strict_types=1);

/**
 * Unauthenticated public watch stream by 32-hex token.
 * Lookup is token + visibility=public; grokme GET uses the server key.
 * Truncated grokme bodies are not 200; this endpoint does not cache.
 */

require_once __DIR__ . '/_common.php';

$httpMethod = strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'));
if ($httpMethod !== 'GET' && $httpMethod !== 'HEAD') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

$token = strtolower(trim((string) ($_GET['token'] ?? '')));
if (preg_match('/^[a-f0-9]{32}$/', $token) !== 1) {
    gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
}

if (!gos_table_exists('lyre_projects')) {
    gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
}

try {
    $st = gos_pdo()->prepare(
        "SELECT watch_token FROM lyre_projects WHERE watch_token = ? AND visibility = 'public' LIMIT 1"
    );
    $st->execute([$token]);
    $row = $st->fetch();
} catch (Throwable) {
    gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
}
if (!is_array($row)) {
    gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
}

$base = rtrim((string) (gos_env('GROKIFY_LYRE_ME_STORAGE_BASE', 'https://me.grokpot.io/v1/storage') ?? ''), '/');
$apiKey = (string) (gos_env('GROKIFY_LYRE_ME_API_KEY', '') ?? '');
if ($base === '' || $apiKey === '') {
    header('Content-Type: application/json; charset=utf-8');
    gos_api_json(['ok' => false, 'error' => 'lyre_storage_unconfigured'], 503);
}
if (!function_exists('curl_init')) {
    header('Content-Type: application/json; charset=utf-8');
    gos_api_json(['ok' => false, 'error' => 'curl_missing'], 500);
}

$key = 'public/watch/' . $token . '.mp4';
$url = $base . '/' . implode('/', array_map('rawurlencode', explode('/', $key)));

if ($httpMethod === 'HEAD') {
    $status = 0;
    $contentType = 'video/mp4';
    $contentLength = null;
    $ch = curl_init($url);
    if ($ch === false) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    curl_setopt_array($ch, [
        CURLOPT_NOBODY => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_CONNECTTIMEOUT => 30,
        CURLOPT_TIMEOUT => 60,
        CURLOPT_HTTPHEADER => [
            'Authorization: Bearer ' . $apiKey,
            'Accept: */*',
        ],
        CURLOPT_HEADERFUNCTION => static function ($ch, string $header) use (&$status, &$contentType, &$contentLength): int {
            if (preg_match('#^HTTP/\S+\s+(\d+)#', $header, $m) === 1) {
                $status = (int) $m[1];
            } elseif (stripos($header, 'Content-Type:') === 0) {
                $got = trim(substr($header, strlen('Content-Type:')));
                if ($got !== '') {
                    $contentType = $got;
                }
            } elseif (stripos($header, 'Content-Length:') === 0) {
                $contentLength = trim(substr($header, strlen('Content-Length:')));
            }
            return strlen($header);
        },
    ]);
    $ok = curl_exec($ch);
    $errno = curl_errno($ch);
    curl_close($ch);
    if ($ok === false || $errno !== 0 || $status !== 200) {
        if ($status === 404 || $status === 401) {
            gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
        }
        gos_api_json(['ok' => false, 'error' => 'not_found'], $status >= 400 ? $status : 502);
    }
    while (ob_get_level() > 0) {
        ob_end_clean();
    }
    header_remove('Content-Type');
    header('Content-Type: ' . ($contentType !== '' ? $contentType : 'video/mp4'));
    if (is_string($contentLength) && $contentLength !== '' && ctype_digit($contentLength)) {
        header('Content-Length: ' . $contentLength);
    }
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    http_response_code(200);
    exit;
}

$tmp = tmpfile();
if ($tmp === false) {
    gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
}

$status = 0;
$contentType = 'video/mp4';
$contentLength = null;
$bytes = 0;
$ch = curl_init($url);
if ($ch === false) {
    fclose($tmp);
    gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
}
curl_setopt_array($ch, [
    CURLOPT_HTTPGET => true,
    CURLOPT_FOLLOWLOCATION => false,
    CURLOPT_CONNECTTIMEOUT => 30,
    CURLOPT_TIMEOUT => 300,
    CURLOPT_HTTPHEADER => [
        'Authorization: Bearer ' . $apiKey,
        'Accept: */*',
    ],
    CURLOPT_HEADERFUNCTION => static function ($ch, string $header) use (&$status, &$contentType, &$contentLength): int {
        if (preg_match('#^HTTP/\S+\s+(\d+)#', $header, $m) === 1) {
            $status = (int) $m[1];
        } elseif (stripos($header, 'Content-Type:') === 0) {
            $got = trim(substr($header, strlen('Content-Type:')));
            if ($got !== '') {
                $contentType = $got;
            }
        } elseif (stripos($header, 'Content-Length:') === 0) {
            $contentLength = trim(substr($header, strlen('Content-Length:')));
        }
        return strlen($header);
    },
    CURLOPT_WRITEFUNCTION => static function ($ch, string $data) use ($tmp, &$bytes): int {
        $n = fwrite($tmp, $data);
        if ($n === false) {
            return 0;
        }
        $bytes += $n;
        return $n;
    },
]);
$ok = curl_exec($ch);
$errno = curl_errno($ch);
curl_close($ch);

$short = is_string($contentLength) && $contentLength !== '' && ctype_digit($contentLength)
    && $bytes !== (int) $contentLength;
if ($ok === false || $errno !== 0 || $bytes <= 0 || $short || $status !== 200) {
    fclose($tmp);
    if ($status === 404 || $status === 401) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    gos_api_json(['ok' => false, 'error' => 'not_found'], $status >= 400 ? $status : 502);
}

rewind($tmp);
$size = (int) (fstat($tmp)['size'] ?? 0);
if ($size <= 0 || $size !== $bytes) {
    fclose($tmp);
    gos_api_json(['ok' => false, 'error' => 'not_found'], 502);
}

while (ob_get_level() > 0) {
    ob_end_clean();
}
header_remove('Content-Type');
header('Content-Type: ' . ($contentType !== '' ? $contentType : 'video/mp4'));
header('Content-Length: ' . (string) $size);
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
header('X-Accel-Buffering: no');
http_response_code(200);
if (function_exists('flush')) {
    flush();
}
$out = fopen('php://output', 'wb');
if ($out !== false) {
    stream_copy_to_stream($tmp, $out);
}
fclose($tmp);
exit;
