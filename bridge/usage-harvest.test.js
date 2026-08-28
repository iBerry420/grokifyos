'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const harvest = require('./usage-harvest');

function write(file, body) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, typeof body === 'string' ? body : JSON.stringify(body));
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'usage-harvest-'));
const cwd = '/tmp/usage-ws';
const parent = path.join(tmp, harvest.encodeCwd(cwd));
const id = '01a00000-1111-2222-3333-444444444444';
const dir = path.join(parent, id);

write(path.join(dir, 'summary.json'), {
    info: { id, cwd },
    session_summary: 'Fixture chat',
    generated_title: 'Fixture chat',
    created_at: '2026-08-21T10:00:00.000Z',
    updated_at: '2026-08-21T10:20:00.000Z',
    current_model_id: 'grok-4.6',
    reasoning_effort: 'xhigh',
    num_chat_messages: 12,
});
write(path.join(dir, 'signals.json'), {
    contextTokensUsed: 79050,
    contextWindowUsage: 15,
    contextWindowTokens: 500000,
    toolCallCount: 49,
    toolsUsed: ['read_file', 'grep'],
    sessionDurationSeconds: 409,
    assistantMessageCount: 22,
    userMessageCount: 1,
});
write(path.join(dir, 'events.jsonl'), [
    '{"ts":"2026-08-21T10:00:01Z","type":"loop_started","loop_index":0}',
    '{"ts":"2026-08-21T10:00:02Z","type":"tool_started","tool_name":"read_file"}',
    '{"ts":"2026-08-21T10:00:03Z","type":"loop_started","loop_index":1}',
].join('\n') + '\n');
write(
    path.join(dir, 'updates.jsonl'),
    JSON.stringify({ method: 'session/update', params: { update: { sessionUpdate: 'agent_message_chunk' }, _meta: { totalTokens: 79050 } } }) +
    '\n' +
    JSON.stringify({
        method: '_x.ai/session/update',
        params: {
            update: {
                sessionUpdate: 'turn_completed',
                usage: {
                    inputTokens: 1500000,
                    outputTokens: 8000,
                    totalTokens: 1508000,
                    cachedReadTokens: 900000,
                    reasoningTokens: 6000,
                    modelCalls: 22,
                    apiDurationMs: 120000,
                },
            },
        },
    }) + '\n'
);

const row = harvest.parseSessionDir(dir);
assert.strictEqual(row.id, id);
assert.strictEqual(row.last_context_tokens, 79050);
assert.strictEqual(row.estimated_input_tokens, 1500000);
assert.strictEqual(row.estimated_output_tokens, 8000);
assert.strictEqual(row.model_loops, 22);
assert.strictEqual(row.tool_calls, 49);
assert.strictEqual(row.wall_time_s, 409);
assert.strictEqual(row.model_time_s, 120);
assert.strictEqual(row.tokens_estimated, false);
assert.deepStrictEqual(row.tools.sort(), ['grep', 'read_file']);

const usage = harvest.parseTurnUsage(path.join(dir, 'updates.jsonl'));
assert.strictEqual(usage.input_tokens, 1500000);
assert.strictEqual(usage.reasoning_tokens, 6000);

const collected = harvest.collect({ sessionsRoot: tmp, cwdAllow: [cwd] });
assert.strictEqual(collected.scanned, 1);
assert.strictEqual(collected.parsed, 1);

const week = harvest.aggregate(collected.sessions, {
    from: '2026-08-20T00:00:00Z',
    to: '2026-08-27T00:00:00Z',
    timeZone: 'UTC',
    includeRuns: true,
});
assert.strictEqual(week.totals.agent_sessions, 1);
assert.strictEqual(week.totals.estimated_input_tokens, 1500000);
assert.strictEqual(week.daily.length, 1);
assert.strictEqual(week.daily[0].day, '2026-08-21');
assert.strictEqual(week.runs.length, 1);

const outside = harvest.aggregate(collected.sessions, {
    from: '2026-08-01T00:00:00Z',
    to: '2026-08-02T00:00:00Z',
    timeZone: 'UTC',
});
assert.strictEqual(outside.totals.agent_sessions, 0);

assert.strictEqual(harvest.dayKey('2026-08-21T22:30:00Z', 'Europe/Berlin'), '2026-08-22');
assert.strictEqual(harvest.dayKey('2026-08-21T22:30:00Z', 'UTC'), '2026-08-21');

const found = harvest.findUsage({ params: { update: { usage: { inputTokens: 3, outputTokens: 1 } } } });
assert.strictEqual(found.inputTokens, 3);

const agent = { startTime: Date.now() - 1000 };
harvest.applyHarvestToAgent(agent, row);
assert.strictEqual(agent.estimatedInputTokens, 1500000);
assert.strictEqual(agent.contextTokens, 79050);
assert.strictEqual(agent._grokCliSessionId, id);

const tracker = harvest.buildTracker({
    sessionsRoot: tmp,
    cwdAllow: [cwd],
    from: '2026-08-20T00:00:00Z',
    to: '2026-08-27T00:00:00Z',
    timeZone: 'UTC',
});
assert.strictEqual(tracker.ok, true);
assert.strictEqual(tracker.totals.tool_calls, 49);
assert.ok(!tracker.runs);

// cache reuse
const cacheFile = path.join(tmp, 'cache.json');
const first = harvest.buildTracker({
    sessionsRoot: tmp,
    cwdAllow: [cwd],
    cacheFile,
    timeZone: 'UTC',
});
assert.ok(first.parsed >= 1);
const second = harvest.buildTracker({
    sessionsRoot: tmp,
    cwdAllow: [cwd],
    cacheFile,
    timeZone: 'UTC',
});
assert.strictEqual(second.reused, 1);
assert.strictEqual(second.parsed, 0);

fs.rmSync(tmp, { recursive: true, force: true });
console.log('usage-harvest tests ok');
