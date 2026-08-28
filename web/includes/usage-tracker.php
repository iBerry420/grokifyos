<?php

declare(strict_types=1);

/**
 * Token / time tracker: CLI harvest (via bridge) + chat-message mapping.
 */

function gos_usage_timezone(): DateTimeZone
{
    $env = gos_env('GROKIFY_USAGE_TZ', '');
    if (is_string($env) && $env !== '') {
        try {
            return new DateTimeZone($env);
        } catch (Throwable) {
            // fall through
        }
    }
    if (is_readable('/etc/timezone')) {
        $name = trim((string) file_get_contents('/etc/timezone'));
        if ($name !== '') {
            try {
                return new DateTimeZone($name);
            } catch (Throwable) {
                // fall through
            }
        }
    }
    $name = date_default_timezone_get() ?: 'UTC';
    try {
        return new DateTimeZone($name);
    } catch (Throwable) {
        return new DateTimeZone('UTC');
    }
}

function gos_usage_compact_tokens(int|float $n): string
{
    $n = (float) $n;
    if ($n >= 1000000) {
        return rtrim(rtrim(number_format($n / 1000000, 1, '.', ''), '0'), '.') . 'M';
    }
    if ($n >= 1000) {
        return rtrim(rtrim(number_format($n / 1000, 1, '.', ''), '0'), '.') . 'k';
    }

    return (string) (int) $n;
}

function gos_usage_compact_duration(int $seconds): string
{
    $seconds = max(0, $seconds);
    if ($seconds < 60) {
        return $seconds . 's';
    }
    if ($seconds < 3600) {
        return (int) floor($seconds / 60) . 'm';
    }
    $hours = $seconds / 3600;
    if ($hours < 10) {
        return rtrim(rtrim(number_format($hours, 1, '.', ''), '0'), '.') . 'h';
    }

    return (string) (int) round($hours) . 'h';
}

function gos_usage_iso_to_unix(string $iso): int
{
    $iso = trim($iso);
    if ($iso === '') {
        return 0;
    }
    try {
        return (new DateTimeImmutable($iso))->getTimestamp();
    } catch (Throwable) {
        $t = strtotime($iso);

        return $t === false ? 0 : $t;
    }
}

/** @return array<string, mixed> */
function gos_usage_empty_chat_stats(): array
{
    return [
        'agent_sessions' => 0,
        'model_loops' => 0,
        'tool_calls' => 0,
        'wall_time_s' => 0,
        'model_time_s' => 0,
        'last_context_tokens' => 0,
        'input_tokens' => 0,
        'output_tokens' => 0,
        'message_count' => 0,
        'tokens_estimated' => false,
    ];
}

/** @param array<string, mixed> $dst */
function gos_usage_add_run(array &$dst, array $run): void
{
    $dst['agent_sessions'] = (int) $dst['agent_sessions'] + 1;
    $dst['model_loops'] = (int) $dst['model_loops'] + (int) ($run['model_loops'] ?? 0);
    $dst['tool_calls'] = (int) $dst['tool_calls'] + (int) ($run['tool_calls'] ?? 0);
    $dst['wall_time_s'] = (int) $dst['wall_time_s'] + (int) ($run['wall_time_s'] ?? 0);
    $dst['model_time_s'] = (int) $dst['model_time_s'] + (int) ($run['model_time_s'] ?? 0);
    $ctx = (int) ($run['last_context_tokens'] ?? 0);
    if ($ctx > (int) $dst['last_context_tokens']) {
        $dst['last_context_tokens'] = $ctx;
    }
    $dst['input_tokens'] = (int) $dst['input_tokens'] + (int) ($run['estimated_input_tokens'] ?? 0);
    $dst['output_tokens'] = (int) $dst['output_tokens'] + (int) ($run['estimated_output_tokens'] ?? 0);
    $dst['message_count'] = (int) $dst['message_count'] + (int) ($run['message_count'] ?? 0);
    if (!empty($run['tokens_estimated'])) {
        $dst['tokens_estimated'] = true;
    }
}

/**
 * Map CLI runs onto chat sessions by nearest assistant message start.
 *
 * @param list<array<string, mixed>> $runs
 * @param list<array{session_id: string, ts: int}> $assistant
 * @return array<string, array<string, mixed>>
 */
function gos_usage_map_runs(array $runs, array $assistant): array
{
    $prepared = [];
    foreach ($runs as $run) {
        if (!is_array($run)) {
            continue;
        }
        $ts = gos_usage_iso_to_unix((string) ($run['created'] ?? ''));
        if ($ts <= 0) {
            continue;
        }
        $run['_ts'] = $ts;
        $prepared[] = $run;
    }
    usort($prepared, static fn ($a, $b) => $a['_ts'] <=> $b['_ts']);
    usort($assistant, static fn ($a, $b) => $a['ts'] <=> $b['ts']);

    $out = [];
    $used = [];
    $n = count($assistant);
    $j = 0;
    foreach ($prepared as $run) {
        $ts = (int) $run['_ts'];
        while ($j < $n && (int) $assistant[$j]['ts'] < $ts - 8) {
            $j++;
        }
        $bestK = null;
        $bestDelta = 181;
        for ($k = $j; $k < $n; $k++) {
            if (!empty($used[$k])) {
                continue;
            }
            $dt = (int) $assistant[$k]['ts'] - $ts;
            if ($dt > 180) {
                break;
            }
            if ($dt < -8) {
                continue;
            }
            $delta = abs($dt);
            if ($delta < $bestDelta) {
                $bestDelta = $delta;
                $bestK = $k;
            }
        }
        if ($bestK === null) {
            continue;
        }
        $used[$bestK] = true;
        $sid = (string) $assistant[$bestK]['session_id'];
        if (!isset($out[$sid])) {
            $out[$sid] = gos_usage_empty_chat_stats();
        }
        gos_usage_add_run($out[$sid], $run);
    }

    return $out;
}

/**
 * @return array<string, mixed>
 */
function gos_usage_tracker_from_bridge(bool $forceRefresh = false, bool $includeRuns = false, string $from = '', string $to = ''): array
{
    $q = [];
    if ($forceRefresh) {
        $q[] = 'refresh=1';
    }
    if ($includeRuns) {
        $q[] = 'include_runs=1';
    }
    if ($from !== '') {
        $q[] = 'from=' . rawurlencode($from);
    }
    if ($to !== '') {
        $q[] = 'to=' . rawurlencode($to);
    }
    $path = '/usage-tracker' . ($q ? ('?' . implode('&', $q)) : '');
    $data = gos_bridge_http($path, 'GET', null, 25);
    if (!empty($data['ok']) && isset($data['totals']) && is_array($data['totals'])) {
        return $data;
    }

    return [
        'ok' => false,
        'error' => $data['error'] ?? 'tracker_unavailable',
        'message' => $data['message'] ?? 'CLI usage tracker unavailable',
        'http_code' => $data['http_code'] ?? null,
    ];
}

/**
 * Chat-transcript stats for the same window (message count + metadata duration/tools).
 *
 * @return array{totals: array<string, int>, daily: array<string, array<string, int>>}
 */
function gos_usage_chat_db_stats(int $userId, string $fromIso, string $toIso): array
{
    $empty = [
        'totals' => [
            'message_count' => 0,
            'tool_calls' => 0,
            'model_time_s' => 0,
            'input_tokens' => 0,
            'output_tokens' => 0,
        ],
        'daily' => [],
    ];
    if ($userId < 1 || !gos_table_exists('system_chat_messages')) {
        return $empty;
    }
    $tz = gos_usage_timezone();
    $fromLocal = '';
    $toLocal = '';
    try {
        if ($fromIso !== '') {
            $fromLocal = (new DateTimeImmutable($fromIso))->setTimezone($tz)->format('Y-m-d H:i:s');
        }
        if ($toIso !== '') {
            $toLocal = (new DateTimeImmutable($toIso))->setTimezone($tz)->format('Y-m-d H:i:s');
        }
    } catch (Throwable) {
        $fromLocal = '';
        $toLocal = '';
    }
    $sql = 'SELECT m.created_at, m.role, m.input_tokens, m.output_tokens, m.metadata
            FROM system_chat_messages m
            INNER JOIN system_chat_sessions s ON s.id = m.session_id
            WHERE s.user_id = ?';
    $args = [$userId];
    if ($fromLocal !== '') {
        $sql .= ' AND m.created_at >= ?';
        $args[] = $fromLocal;
    }
    if ($toLocal !== '') {
        $sql .= ' AND m.created_at < ?';
        $args[] = $toLocal;
    }
    try {
        $st = gos_pdo()->prepare($sql);
        $st->execute($args);
        $rows = $st->fetchAll(PDO::FETCH_ASSOC) ?: [];
    } catch (Throwable) {
        return $empty;
    }
    $totals = $empty['totals'];
    $daily = [];
    foreach ($rows as $row) {
        $day = substr((string) ($row['created_at'] ?? ''), 0, 10);
        if ($day === '') {
            continue;
        }
        if (!isset($daily[$day])) {
            $daily[$day] = [
                'message_count' => 0,
                'tool_calls' => 0,
                'model_time_s' => 0,
                'input_tokens' => 0,
                'output_tokens' => 0,
            ];
        }
        $totals['message_count']++;
        $daily[$day]['message_count']++;
        $in = (int) ($row['input_tokens'] ?? 0);
        $out = (int) ($row['output_tokens'] ?? 0);
        $totals['input_tokens'] += $in;
        $totals['output_tokens'] += $out;
        $daily[$day]['input_tokens'] += $in;
        $daily[$day]['output_tokens'] += $out;
        $meta = $row['metadata'] ?? null;
        if (is_string($meta) && $meta !== '') {
            $decoded = json_decode($meta, true);
            $meta = is_array($decoded) ? $decoded : [];
        }
        if (is_array($meta)) {
            $tools = (int) ($meta['tool_count'] ?? 0);
            $durMs = (int) ($meta['duration'] ?? 0);
            $totals['tool_calls'] += $tools;
            $totals['model_time_s'] += (int) floor($durMs / 1000);
            $daily[$day]['tool_calls'] += $tools;
            $daily[$day]['model_time_s'] += (int) floor($durMs / 1000);
        }
    }

    return ['totals' => $totals, 'daily' => $daily];
}

/**
 * Merge CLI tracker + DB chat stats into the usage API payload.
 *
 * @param array<string, mixed> $billing
 * @return array<string, mixed>
 */
function gos_usage_tracker_payload(array $billing, bool $forceRefresh = false, int $userId = 0): array
{
    $from = (string) ($billing['period_start'] ?? '');
    $to = (string) ($billing['period_end'] ?? $billing['reset_at'] ?? '');
    $cli = gos_usage_tracker_from_bridge($forceRefresh, false, $from, $to);
    if (empty($cli['ok'])) {
        return $cli;
    }
    $db = $userId > 0
        ? gos_usage_chat_db_stats($userId, $from, $to)
        : ['totals' => ['message_count' => 0, 'tool_calls' => 0, 'model_time_s' => 0], 'daily' => []];

    $totals = is_array($cli['totals'] ?? null) ? $cli['totals'] : [];
    // Prefer CLI agent metrics; overlay user-facing message count from the transcript.
    if ((int) ($db['totals']['message_count'] ?? 0) > 0) {
        $totals['message_count'] = (int) $db['totals']['message_count'];
        $totals['db_tool_calls'] = (int) ($db['totals']['tool_calls'] ?? 0);
        $totals['db_model_time_s'] = (int) ($db['totals']['model_time_s'] ?? 0);
    }
    $daily = [];
    $cliDaily = is_array($cli['daily'] ?? null) ? $cli['daily'] : [];
    $days = [];
    foreach ($cliDaily as $row) {
        if (!is_array($row)) {
            continue;
        }
        $day = (string) ($row['day'] ?? '');
        if ($day === '') {
            continue;
        }
        $days[$day] = $row;
        if (isset($db['daily'][$day]['message_count'])) {
            $days[$day]['message_count'] = (int) $db['daily'][$day]['message_count'];
        }
    }
    foreach ($db['daily'] as $day => $row) {
        if (!isset($days[$day])) {
            $days[$day] = array_merge([
                'day' => $day,
                'agent_sessions' => 0,
                'model_loops' => 0,
                'tool_calls' => 0,
                'wall_time_s' => 0,
                'model_time_s' => 0,
                'last_context_tokens' => 0,
                'estimated_input_tokens' => 0,
                'estimated_output_tokens' => 0,
            ], $row);
        }
    }
    ksort($days);
    $daily = array_values($days);

    $cli['totals'] = $totals;
    $cli['daily'] = $daily;
    $cli['label_wall'] = gos_usage_compact_duration((int) ($totals['wall_time_s'] ?? 0));
    $cli['label_input'] = gos_usage_compact_tokens((int) ($totals['estimated_input_tokens'] ?? 0));
    $cli['label_output'] = gos_usage_compact_tokens((int) ($totals['estimated_output_tokens'] ?? 0));
    $cli['label_context'] = gos_usage_compact_tokens((int) ($totals['last_context_tokens'] ?? 0));

    return $cli;
}

/**
 * @return array<string, array<string, mixed>>
 */
function gos_usage_chat_session_stats(int $userId, bool $forceRefresh = false): array
{
    if ($userId < 1) {
        return [];
    }
    $cli = gos_usage_tracker_from_bridge($forceRefresh, true, '', '');
    $runs = is_array($cli['runs'] ?? null) ? $cli['runs'] : [];
    $assistant = [];
    if (gos_table_exists('system_chat_messages')) {
        try {
            $st = gos_pdo()->prepare(
                'SELECT m.session_id, UNIX_TIMESTAMP(m.created_at) AS ts
                 FROM system_chat_messages m
                 INNER JOIN system_chat_sessions s ON s.id = m.session_id
                 WHERE s.user_id = ? AND m.role = ?'
            );
            $st->execute([$userId, 'assistant']);
            while ($row = $st->fetch(PDO::FETCH_ASSOC)) {
                $assistant[] = [
                    'session_id' => (string) $row['session_id'],
                    'ts' => (int) $row['ts'],
                ];
            }
        } catch (Throwable) {
            $assistant = [];
        }
    }
    $mapped = gos_usage_map_runs($runs, $assistant);

    // Overlay stored message token columns when present.
    if (gos_table_exists('system_chat_messages')) {
        try {
            $st = gos_pdo()->prepare(
                "SELECT m.session_id,
                        SUM(m.input_tokens) AS input_tokens,
                        SUM(m.output_tokens) AS output_tokens,
                        MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(m.metadata, '$.context_tokens')) AS UNSIGNED)) AS last_context_tokens
                 FROM system_chat_messages m
                 INNER JOIN system_chat_sessions s ON s.id = m.session_id
                 WHERE s.user_id = ?
                 GROUP BY m.session_id"
            );
            $st->execute([$userId]);
            while ($row = $st->fetch(PDO::FETCH_ASSOC)) {
                $sid = (string) $row['session_id'];
                if (!isset($mapped[$sid])) {
                    $mapped[$sid] = gos_usage_empty_chat_stats();
                }
                $in = (int) ($row['input_tokens'] ?? 0);
                $out = (int) ($row['output_tokens'] ?? 0);
                $ctx = (int) ($row['last_context_tokens'] ?? 0);
                if ($in > (int) $mapped[$sid]['input_tokens']) {
                    $mapped[$sid]['input_tokens'] = $in;
                    $mapped[$sid]['tokens_estimated'] = false;
                }
                if ($out > (int) $mapped[$sid]['output_tokens']) {
                    $mapped[$sid]['output_tokens'] = $out;
                }
                if ($ctx > (int) $mapped[$sid]['last_context_tokens']) {
                    $mapped[$sid]['last_context_tokens'] = $ctx;
                }
            }
        } catch (Throwable) {
            // JSON_EXTRACT may be unavailable on very old MariaDB — mapped CLI stats still apply.
        }
    }

    return $mapped;
}
