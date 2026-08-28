<?php

declare(strict_types=1);

function gos_pdo_alive(?PDO $pdo): bool
{
    if (!($pdo instanceof PDO)) {
        return false;
    }
    try {
        return $pdo->query('SELECT 1') !== false;
    } catch (Throwable) {
        return false;
    }
}

function gos_pdo(): PDO
{
    /** @var PDO|null $pdo */
    static $pdo = null;
    if (gos_pdo_alive($pdo)) {
        return $pdo;
    }
    $pdo = null;
    $c = require __DIR__ . '/settings.php';
    $dsn = sprintf(
        'mysql:host=%s;port=%d;dbname=%s;charset=%s',
        $c['db_host'],
        $c['db_port'],
        $c['db_name'],
        $c['db_charset']
    );
    $pdo = new PDO($dsn, $c['db_user'], $c['db_pass'], [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
    return $pdo;
}

function gos_table_exists(string $table): bool
{
    try {
        $pdo = gos_pdo();
        $stmt = $pdo->prepare(
            'SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ? LIMIT 1'
        );
        $stmt->execute([$table]);
        return (bool) $stmt->fetchColumn();
    } catch (Throwable) {
        return false;
    }
}
