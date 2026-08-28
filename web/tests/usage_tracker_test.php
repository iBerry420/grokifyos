<?php

declare(strict_types=1);

/**
 * Unit tests for usage tracker mapping / formatters.
 * Run: php web/tests/usage_tracker_test.php
 */

require_once dirname(__DIR__) . '/includes/paths.php';
require_once dirname(__DIR__) . '/includes/usage-tracker.php';

$fails = 0;

function expect_true(bool $cond, string $msg): void
{
    global $fails;
    if (!$cond) {
        $fails++;
        fwrite(STDERR, "FAIL: {$msg}\n");
    }
}

function expect_eq(mixed $got, mixed $want, string $msg): void
{
    expect_true($got === $want, $msg . ' got=' . var_export($got, true) . ' want=' . var_export($want, true));
}

expect_eq(gos_usage_compact_tokens(830), '830', 'tokens under 1k');
expect_eq(gos_usage_compact_tokens(79050), '79.1k', 'tokens thousands');
expect_eq(gos_usage_compact_tokens(1500000), '1.5M', 'tokens millions');
expect_eq(gos_usage_compact_duration(45), '45s', 'dur seconds');
expect_eq(gos_usage_compact_duration(409), '6m', 'dur minutes');
expect_eq(gos_usage_compact_duration(7200), '2h', 'dur hours');

$runs = [
    [
        'id' => 'cli-1',
        'created' => '2026-08-21T10:00:00Z',
        'last_context_tokens' => 79050,
        'estimated_input_tokens' => 1500000,
        'estimated_output_tokens' => 8000,
        'model_loops' => 22,
        'tool_calls' => 49,
        'wall_time_s' => 409,
        'model_time_s' => 120,
        'message_count' => 12,
        'tokens_estimated' => false,
    ],
    [
        'id' => 'cli-orphan',
        'created' => '2026-08-21T18:00:00Z',
        'last_context_tokens' => 1000,
        'estimated_input_tokens' => 1000,
        'estimated_output_tokens' => 10,
        'model_loops' => 1,
        'tool_calls' => 0,
        'wall_time_s' => 5,
        'model_time_s' => 3,
        'message_count' => 1,
        'tokens_estimated' => true,
    ],
];
$assistant = [
    ['session_id' => 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'ts' => strtotime('2026-08-21T10:00:02Z')],
];
$mapped = gos_usage_map_runs($runs, $assistant);
expect_eq(count($mapped), 1, 'one chat mapped');
$hit = $mapped['aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'] ?? null;
expect_true(is_array($hit), 'mapped stats present');
expect_eq((int) ($hit['input_tokens'] ?? 0), 1500000, 'input mapped');
expect_eq((int) ($hit['last_context_tokens'] ?? 0), 79050, 'context mapped');
expect_eq((int) ($hit['tool_calls'] ?? 0), 49, 'tools mapped');
expect_eq((int) ($hit['agent_sessions'] ?? 0), 1, 'one agent session attached');

if ($fails > 0) {
    fwrite(STDERR, "usage_tracker_test: {$fails} failure(s)\n");
    exit(1);
}
echo "usage_tracker tests ok\n";
