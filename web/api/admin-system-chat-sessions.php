<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$access = gos_require_system_chat();
$userId = (int) $access['user']['id'];
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'GET') {
    gos_system_chat_audit('info', 'access', 'Sessions listed', [], $userId);
    $st = gos_pdo()->prepare(
        'SELECT s.id, s.title, s.created_at, s.updated_at,
                (SELECT COUNT(*) FROM system_chat_messages m WHERE m.session_id = s.id) AS message_count
         FROM system_chat_sessions s
         WHERE s.user_id = ? ORDER BY s.updated_at DESC LIMIT 200'
    );
    $st->execute([$userId]);
    $sessions = $st->fetchAll(PDO::FETCH_ASSOC) ?: [];
    $stats = gos_usage_chat_session_stats($userId, false);
    foreach ($sessions as &$row) {
        $row['message_count'] = (int) ($row['message_count'] ?? 0);
        $sid = (string) ($row['id'] ?? '');
        $u = $stats[$sid] ?? null;
        $row['input_tokens'] = (int) ($u['input_tokens'] ?? 0);
        $row['output_tokens'] = (int) ($u['output_tokens'] ?? 0);
        $row['last_context_tokens'] = (int) ($u['last_context_tokens'] ?? 0);
        $row['wall_time_s'] = (int) ($u['wall_time_s'] ?? 0);
        $row['tool_calls'] = (int) ($u['tool_calls'] ?? 0);
        $row['tokens_estimated'] = !empty($u['tokens_estimated']);
    }
    unset($row);
    gos_api_json(['ok' => true, 'sessions' => $sessions]);
}

if ($method === 'POST') {
    $body = gos_json_body();
    $action = trim((string) ($body['action'] ?? ''));

    if ($action === 'rename') {
        $id = trim((string) ($body['id'] ?? $body['session_id'] ?? ''));
        if (!gos_system_chat_session_owned($id, $userId)) {
            gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
        }
        $title = mb_substr(trim((string) ($body['title'] ?? '')), 0, 255);
        if ($title === '') {
            gos_api_json(['ok' => false, 'error' => 'invalid_title'], 400);
        }
        $st = gos_pdo()->prepare(
            'UPDATE system_chat_sessions SET title = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?'
        );
        $st->execute([$title, $id, $userId]);
        gos_system_chat_audit('info', 'access', 'Session renamed', [
            'session_id' => $id,
            'title' => $title,
        ], $userId, $id);
        gos_api_json(['ok' => true, 'id' => $id, 'title' => $title]);
    }

    $id = gos_system_chat_session_id();
    $title = mb_substr(trim((string) ($body['title'] ?? 'New Chat')), 0, 255) ?: 'New Chat';
    $st = gos_pdo()->prepare(
        'INSERT INTO system_chat_sessions (id, user_id, title) VALUES (?, ?, ?)'
    );
    $st->execute([$id, $userId, $title]);
    gos_system_chat_audit('info', 'access', 'Session created', ['session_id' => $id], $userId, $id);
    gos_api_json(['ok' => true, 'id' => $id, 'title' => $title]);
}

if ($method === 'PATCH') {
    $body = gos_json_body();
    $id = trim((string) ($body['id'] ?? $body['session_id'] ?? $_GET['id'] ?? ''));
    if (!gos_system_chat_session_owned($id, $userId)) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    $title = mb_substr(trim((string) ($body['title'] ?? '')), 0, 255);
    if ($title === '') {
        gos_api_json(['ok' => false, 'error' => 'invalid_title'], 400);
    }
    $st = gos_pdo()->prepare(
        'UPDATE system_chat_sessions SET title = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?'
    );
    $st->execute([$title, $id, $userId]);
    gos_system_chat_audit('info', 'access', 'Session renamed', [
        'session_id' => $id,
        'title' => $title,
    ], $userId, $id);
    gos_api_json(['ok' => true, 'id' => $id, 'title' => $title]);
}

if ($method === 'DELETE') {
    $id = (string) ($_GET['id'] ?? '');
    if (!gos_system_chat_session_owned($id, $userId)) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    $st = gos_pdo()->prepare('DELETE FROM system_chat_sessions WHERE id = ? AND user_id = ?');
    $st->execute([$id, $userId]);
    gos_system_chat_audit('info', 'access', 'Session deleted', ['session_id' => $id], $userId, $id);
    gos_api_json(['ok' => true]);
}

gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
