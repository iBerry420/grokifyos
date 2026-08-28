<?php

declare(strict_types=1);

/**
 * Unit tests for gbot RPC argument filters (automation spec / cron).
 * Run: php web/tests/gbot_rpc_test.php
 */

require_once dirname(__DIR__) . '/api/gbot.php';

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

expect_true(gos_gbot_cron_ok('*/15 * * * *'), '15m 24h cron');
expect_true(gos_gbot_cron_ok('*/15 8-21 * * *'), '15m 8-21 cron');
expect_true(gos_gbot_cron_ok('0 8-21/2 * * *'), '2h window cron');
expect_true(!gos_gbot_cron_ok('Every 15 minutes'), 'human schedule is not cron');
expect_true(!gos_gbot_cron_ok('*/15 * * *'), 'four fields fail');

$filtered = gos_gbot_filter_rpc_args('updateAgentAutomation', [
    'id' => 'bot-1',
    'automationId' => 'desk-book-check',
    'extra' => 'drop-me',
    'spec' => [
        'name' => 'Desk book check',
        'prompt' => 'Check the desk.',
        'isEnabled' => true,
        'trigger' => ['type' => 'cron', 'schedule' => '*/15 8-21 * * *', 'junk' => true],
        'filePath' => '/etc/passwd',
    ],
]);
expect_eq($filtered['id'] ?? null, 'bot-1', 'agent id kept');
expect_eq($filtered['automationId'] ?? null, 'desk-book-check', 'automation id kept');
expect_true(!isset($filtered['extra']), 'unknown top-level dropped');
expect_eq($filtered['spec']['trigger']['schedule'] ?? null, '*/15 8-21 * * *', 'cron kept');
expect_true(!isset($filtered['spec']['trigger']['junk']), 'trigger junk dropped');
expect_true(!isset($filtered['spec']['filePath']), 'filePath dropped');

$group = gos_gbot_filter_trigger([
    'type' => 'group',
    'listeners' => [
        ['type' => 'slack', 'channel' => 'desk'],
        ['type' => 'cron', 'schedule' => '*/30 * * * *'],
    ],
]);
expect_eq($group['type'] ?? null, 'group', 'group trigger kept');
expect_eq(count($group['listeners'] ?? []), 2, 'both listeners kept');

$hook = gos_gbot_filter_trigger(['type' => 'webhook', 'secret' => 'drop-me']);
expect_eq($hook['type'] ?? null, 'webhook', 'webhook trigger kept');
expect_true(!isset($hook['secret']), 'webhook extra dropped');

expect_true(gos_gbot_rpc_allowed('interruptAgentRun'), 'interrupt allowlisted');
expect_true(gos_gbot_rpc_allowed('voteFeedback'), 'vote allowlisted');
expect_true(gos_gbot_rpc_allowed('uploadAttachment'), 'upload allowlisted');
expect_true(gos_gbot_rpc_allowed('getAutomationWebhookCredential'), 'webhook cred allowlisted');
expect_true(gos_gbot_rpc_allowed('deleteAgentMemory'), 'delete memory allowlisted');
expect_true(gos_gbot_rpc_allowed('startTeachRecording'), 'teach allowlisted');
expect_true(gos_gbot_rpc_allowed('updateHostNow'), 'host update allowlisted');
expect_true(gos_gbot_rpc_allowed('deleteAgent'), 'deleteAgent allowlisted');
expect_true(gos_gbot_rpc_allowed('deleteAgents'), 'deleteAgents allowlisted');
expect_true(gos_gbot_rpc_allowed('injectChromeCookies'), 'cookie import allowlisted');
expect_true(gos_gbot_rpc_allowed('resetForeverBox'), 'reset box allowlisted');
expect_true(gos_gbot_rpc_allowed('createRoomFromAgent'), 'share room allowlisted');
expect_true(gos_gbot_rpc_allowed('publishSkill'), 'publish skill allowlisted');
expect_true(!gos_gbot_rpc_allowed('setBoxSecrets'), 'setBoxSecrets still blocked');
expect_true(!gos_gbot_rpc_allowed('clearBoxStoreNow'), 'clearBoxStoreNow still blocked');
expect_true(!gos_gbot_rpc_allowed('prepareBoxForRecreate'), 'prepareBoxForRecreate still blocked');

$send = gos_gbot_filter_rpc_args('sendPrompt', [
    'prompt' => 'see this',
    'agentId' => 'bot-1',
    'clientNonce' => 'n1',
    'enterEpochMs' => 1,
    'attachmentPaths' => ['/tmp/a.png', 12, ''],
    'attachmentNames' => ['photo.png', '../etc/passwd'],
    'replyToId' => 'entry-9',
    'isFork' => true,
    'traceparent' => 'drop-me',
]);
expect_eq($send['attachmentPaths'] ?? null, ['/tmp/a.png'], 'attachment path kept');
expect_eq($send['replyToId'] ?? null, 'entry-9', 'replyTo kept');
expect_eq($send['isFork'] ?? null, true, 'isFork kept');
expect_true(!isset($send['traceparent']), 'traceparent dropped');
expect_eq($send['attachmentNames'][1] ?? null, 'passwd', 'pathy attachment name basenamed');

$vote = gos_gbot_filter_rpc_args('voteFeedback', [
    'agentId' => 'bot-1',
    'entryId' => 'e1',
    'action' => 'explode',
    'comment' => 'nope',
]);
expect_eq($vote['action'] ?? null, '', 'bad vote action dropped');

$up = gos_gbot_filter_rpc_args('uploadAttachment', [
    'agentId' => 'bot-1',
    'filename' => '../../etc/passwd.png',
    'bytesBase64' => 'QQ==',
    'extra' => true,
]);
expect_eq($up['filename'] ?? null, 'passwd.png', 'upload filename sanitized');
expect_true(!isset($up['extra']), 'upload extra dropped');

$stop = gos_gbot_filter_rpc_args('interruptAgentRun', ['agentId' => 'bot-1', 'junk' => 1]);
expect_eq($stop, ['id' => 'bot-1'], 'interrupt maps agentId to id');

$cookies = gos_gbot_filter_rpc_args('injectChromeCookies', [
    'cookies' => [
        ['name' => 'sid', 'value' => 'abc', 'domain' => '.x.com', 'path' => '/', 'secure' => true, 'trace' => 1],
        ['name' => '', 'value' => 'x', 'domain' => '.x.com', 'path' => '/'],
        'nope',
    ],
    'extra' => true,
]);
expect_eq(count($cookies['cookies'] ?? []), 1, 'one valid cookie kept');
expect_eq($cookies['cookies'][0]['name'] ?? null, 'sid', 'cookie name kept');
expect_eq($cookies['cookies'][0]['value'] ?? null, 'abc', 'cookie value kept');
expect_true(!isset($cookies['cookies'][0]['trace']), 'cookie extra dropped');
expect_true(!isset($cookies['extra']), 'cookie batch extra dropped');

$wipe = gos_gbot_filter_rpc_args('deleteAgents', ['ids' => ['a', 'b', 'a', 3], 'junk' => true]);
expect_eq($wipe['ids'] ?? null, ['a', 'b'], 'deleteAgents ids unique strings');

$share = gos_gbot_filter_rpc_args('createRoomFromAgent', ['id' => 'bot-1', 'extra' => 1]);
expect_eq($share, ['agentId' => 'bot-1'], 'share maps id to agentId');

$join = gos_gbot_filter_rpc_args('respondToRoomJoinRequest', ['requestId' => 'r1', 'isApproved' => true, 'hack' => 1]);
expect_eq($join['requestId'] ?? null, 'r1', 'join request id kept');
expect_eq($join['isApproved'] ?? null, true, 'join approved kept');
expect_true(!isset($join['hack']), 'join extra dropped');

$pub = gos_gbot_filter_rpc_args('publishSkill', ['workflowId' => 'wf-1', 'teamId' => '9', 'path' => '/etc']);
expect_eq($pub['workflowId'] ?? null, 'wf-1', 'publish workflow kept');
expect_eq($pub['teamId'] ?? null, 9, 'publish team int');
expect_true(!isset($pub['path']), 'publish path dropped');

if ($fails > 0) {
    fwrite(STDERR, "gbot_rpc_test: {$fails} failed\n");
    exit(1);
}
fwrite(STDOUT, "gbot_rpc_test ok\n");
