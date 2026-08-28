<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$access = gos_require_system_chat();
$userId = (int) $access['user']['id'];
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method !== 'GET') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

$force = isset($_GET['refresh']) && (string) $_GET['refresh'] !== '0' && (string) $_GET['refresh'] !== '';
$usage = gos_grok_build_fetch_usage($force);
$tracker = gos_usage_tracker_payload(is_array($usage) ? $usage : [], $force, $userId);
if (!empty($tracker['ok'])) {
    $usage['tracker'] = $tracker;
} elseif (!isset($usage['tracker'])) {
    $usage['tracker'] = $tracker;
}

if (empty($usage['ok'])) {
    gos_system_chat_audit('warning', 'usage', 'Usage fetch failed', [
        'error' => $usage['error'] ?? 'unknown',
        'http_code' => $usage['http_code'] ?? null,
    ], $userId);
    gos_api_json($usage, 502);
}

gos_api_json($usage);
