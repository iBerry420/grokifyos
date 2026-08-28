<?php

declare(strict_types=1);

require_once dirname(__DIR__) . '/includes/bootstrap.php';

if (session_status() === PHP_SESSION_ACTIVE) {
    session_write_close();
}

if (PHP_SAPI !== 'cli') {
    header('Content-Type: application/json; charset=utf-8');
}

function gos_api_json(mixed $data, int $code = 200): never
{
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function gos_require_method(string $method): void
{
    if (($_SERVER['REQUEST_METHOD'] ?? '') !== $method) {
        gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
    }
}

function gos_json_body(): array
{
    $raw = file_get_contents('php://input') ?: '';
    if ($raw === '') {
        return [];
    }
    $data = json_decode($raw, true);
    return is_array($data) ? $data : [];
}

/**
 * Session cookie or device Bearer.
 *
 * @return array{user: array, device: ?array, auth: 'session'|'token'}
 */
function gos_require_access(): array
{
    $bearer = gos_auth_from_bearer();
    if ($bearer !== null) {
        return ['user' => $bearer['user'], 'device' => $bearer['device'], 'auth' => 'token'];
    }

    $authHeader = gos_authorization_header();
    if (preg_match('/^\s*Bearer\s+\S+\s*$/i', $authHeader)) {
        gos_api_json(['ok' => false, 'error' => 'invalid_token'], 401);
    }

    // Re-open session for login checks after write_close in this file
    if (session_status() !== PHP_SESSION_ACTIVE) {
        gos_session_start();
    }
    $user = gos_current_user();
    if ($user === null) {
        gos_api_json(['ok' => false, 'error' => 'auth_required'], 401);
    }
    return ['user' => $user, 'device' => null, 'auth' => 'session'];
}
