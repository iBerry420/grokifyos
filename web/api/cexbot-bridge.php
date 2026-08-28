<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

gos_require_method('POST');

$remote = (string) ($_SERVER['REMOTE_ADDR'] ?? '');
if (!in_array($remote, ['127.0.0.1', '::1'], true)) {
    gos_api_json(['ok' => false, 'error' => 'forbidden'], 403);
}

$key = (string) (gos_env('GROKIFY_CEXBOT_MINT_KEY', '') ?? '');
$got = (string) ($_SERVER['HTTP_X_CEXBOT_MINT'] ?? '');
if ($key === '' || $got === '' || !hash_equals($key, $got)) {
    gos_api_json(['ok' => false, 'error' => 'unauthorized'], 401);
}

if (!gos_system_chat_tables_ready()) {
    gos_api_json(['ok' => false, 'error' => 'system_chat_not_migrated'], 503);
}

$userId = (int) (gos_env('GROKIFY_CEXBOT_USER_ID', '0') ?? '0');
$user = gos_user_by_id($userId);
if ($user === null || ($user['status'] ?? '') !== 'active') {
    gos_api_json(['ok' => false, 'error' => 'cexbot_user_missing'], 500);
}

$body = gos_json_body();
$title = trim((string) ($body['title'] ?? ''));
if ($title === '' || !preg_match('/^CexBot \d{4}-\d{2}-\d{2}$/', $title)) {
    $title = 'CexBot ' . gmdate('Y-m-d');
}

$sid = trim((string) ($body['session_id'] ?? ''));
if ($sid !== '' && gos_system_chat_session_owned($sid, $userId)) {
    // reuse owned hex session
} else {
    $st = gos_pdo()->prepare(
        'SELECT id FROM system_chat_sessions WHERE user_id = ? AND title = ? ORDER BY updated_at DESC LIMIT 1'
    );
    $st->execute([$userId, $title]);
    $row = $st->fetch(PDO::FETCH_ASSOC);
    if (is_array($row) && gos_system_chat_valid_session_id((string) $row['id'])) {
        $sid = (string) $row['id'];
    } else {
        $sid = gos_system_chat_session_id();
        $ins = gos_pdo()->prepare(
            'INSERT INTO system_chat_sessions (id, user_id, title) VALUES (?, ?, ?)'
        );
        $ins->execute([$sid, $userId, $title]);
    }
}

$site = rtrim(gos_site_url(), '/');
if ($site === '' || str_contains($site, '127.0.0.1') || str_contains($site, 'localhost')) {
    $site = 'https://grokifyos.grokpot.io';
}
$wsPath = gos_system_chat_ws_path();
$wsUrl = preg_replace('#^https:#', 'wss:', $site) . $wsPath;
if (str_starts_with($site, 'http://')) {
    $wsUrl = preg_replace('#^http:#', 'ws:', $site) . $wsPath;
}

gos_api_json([
    'ok' => true,
    'ws_token' => gos_system_chat_ws_token($user),
    'ws_url' => $wsUrl,
    'session_id' => $sid,
    'models' => [],
]);
