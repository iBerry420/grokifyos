<?php

declare(strict_types=1);

/**
 * Unit tests for Discord local list helpers (membership + audit SQL).
 * Run: php web/tests/discord_local_test.php
 */

require_once dirname(__DIR__) . '/api/_common.php';
require_once dirname(__DIR__) . '/api/discord.php';

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

$g = [
    'guildId' => '1',
    'guildName' => 'GREYCORD',
    'isWatched' => true,
    'respondToMentions' => false,
    'respondToReplies' => false,
    'semanticTagging' => false,
    'analyzeFiles' => false,
    'bots' => [
        ['botId' => 2, 'isWatched' => true, 'respondToMentions' => false, 'respondToReplies' => false, 'semanticTagging' => false, 'analyzeFiles' => false],
    ],
];
$out = gos_discord_finalize_guild_bots($g, [2 => true], [2 => 'LYNX', 3 => 'AvalynnAI NG', 4 => 'AvalynnAI']);
expect_eq(count($out['bots']), 1, 'non-member bots must not be attached');
expect_eq($out['bots'][0]['botId'] ?? null, 2, 'member bot 2 stays');
expect_eq($out['bots'][0]['name'] ?? '', 'LYNX', 'member bot named');

$empty = $g;
$empty['bots'] = [];
$filled = gos_discord_finalize_guild_bots($empty, [4 => true], [2 => 'LYNX', 4 => 'AvalynnAI']);
expect_eq(count($filled['bots']), 1, 'member without settings still listed');
expect_eq($filled['bots'][0]['botId'] ?? null, 4, 'only the member bot is filled');

$none = gos_discord_finalize_guild_bots($empty, [], [2 => 'LYNX', 4 => 'AvalynnAI']);
expect_eq(count($none['bots']), 0, 'guild with no members lists no bots');

$liveMap = gos_discord_membership_from_bot_guilds([
    2 => ['974907746002554891', '1320903119827112047', '1310482523188494456'],
    3 => [],
    4 => ['1310482523188494456', '1328604162845446227'],
]);
expect_eq(array_keys($liveMap['1320903119827112047'] ?? []), [2], 'GROK COMMUNITY is LYNX only');
expect_true(!isset($liveMap['1320903119827112047'][4]), 'AvalynnAI is not in GROK COMMUNITY');
expect_true(!isset($liveMap['974907746002554891'][3]), 'stopped NG is not in GREYCORD');
expect_eq(count($liveMap['1310482523188494456'] ?? []), 2, 'Avalynn.ai has LYNX + AvalynnAI');
expect_true(isset($liveMap['1328604162845446227'][4]), 'AvalynnAI is in Cat\'s Cafe');
expect_true(!isset($liveMap['1328604162845446227'][2]), 'LYNX is not in Cat\'s Cafe');

$ids = gos_discord_extract_guild_ids([
    'running' => true,
    'guilds' => [
        ['id' => '1310482523188494456', 'name' => 'Avalynn.ai'],
        ['guildId' => '974907746002554891'],
        ['id' => 'nope'],
    ],
]);
expect_eq($ids, ['1310482523188494456', '974907746002554891'], 'live guild payload yields snowflakes');

$att = gos_discord_public_attachment([
    'id' => 7,
    'filename' => 'dance.gif',
    'contentType' => 'image/gif',
    'discordUrl' => 'https://cdn.discordapp.com/attachments/1114305545478877377/1540373626258194432/dance.gif?ex=dead',
    'localPath' => '/var/www/avalynn/uploads/discord-files/7_dance.gif',
    'size' => 12345,
    'createdAt' => '2026-08-21 20:23:43.000',
    'guildId' => '1114305545478877377',
    'guildName' => 'GREYCORD',
    'channelId' => '1131685490647642112',
    'channelName' => 'clips',
    'messageId' => '1540373626258194432',
    'user' => [
        'id' => 7964,
        'discordId' => '123456789012345678',
        'username' => 'iberry420',
        'displayName' => 'iBerry',
        'avatar' => '',
    ],
]);
expect_true(str_contains((string) ($att['url'] ?? ''), 'action=file'), 'attachments serve via local API');
expect_true(str_contains((string) ($att['url'] ?? ''), 'id=7'), 'attachment url includes id');
expect_true(empty($att['discordUrl']), 'client payload must not include Discord CDN url');
expect_eq($att['local'] ?? null, true, 'localPath marks attachment local');
expect_eq($att['playable'] ?? null, true, 'cached attachment is playable');
expect_eq($att['kind'] ?? '', 'gif', 'gif kind from content type');
expect_eq(gos_discord_mime_from_name('voice-message.ogg'), 'audio/ogg', 'ogg files are audio/ogg');
expect_eq(gos_discord_mime_from_name('note.opus'), 'audio/ogg', 'opus files are ogg containers');
expect_eq(gos_discord_kind('application/ogg', 'clip.ogg'), 'audio', 'application/ogg is audio');
expect_eq(gos_discord_kind('audio/opus', 'voice-message.ogg'), 'audio', 'opus content type is audio');
expect_eq($att['size'] ?? null, 12345, 'attachment size');
expect_eq($att['createdAt'] ?? '', '2026-08-21 20:23:43.000', 'attachment timestamp');
expect_eq($att['guildName'] ?? '', 'GREYCORD', 'attachment guild name');
expect_eq($att['channelName'] ?? '', 'clips', 'attachment channel name');
expect_eq($att['discordAttachmentId'] ?? '', '1540373626258194432', 'cdn attachment snowflake');
expect_eq($att['user']['discordId'] ?? '', '123456789012345678', 'attachment user discord id');

$range = gos_discord_parse_range('bytes=0-1023', 5000);
expect_eq($range['start'] ?? null, 0, 'range start');
expect_eq($range['end'] ?? null, 1023, 'range end');
expect_eq($range['length'] ?? null, 1024, 'range length');
expect_eq($range['partial'] ?? null, true, 'range is partial');
$full = gos_discord_parse_range(null, 5000);
expect_eq($full['start'] ?? null, 0, 'full start');
expect_eq($full['end'] ?? null, 4999, 'full end');
expect_eq($full['partial'] ?? null, false, 'full is not partial');
$openEnd = gos_discord_parse_range('bytes=100-', 5000);
expect_eq($openEnd['start'] ?? null, 100, 'open-end start');
expect_eq($openEnd['end'] ?? null, 4999, 'open-end end');
$bad = gos_discord_parse_range('bytes=9000-9999', 5000);
expect_eq($bad, null, 'unsatisfiable range is null');

$remote = gos_discord_public_attachment([
    'id' => 8,
    'filename' => 'clip.mp4',
    'contentType' => 'video/mp4',
    'discordUrl' => 'https://cdn.discordapp.com/attachments/1/2/clip.mp4?ex=' . dechex(time() + 3600),
    'localPath' => '',
]);
expect_true(str_contains((string) ($remote['url'] ?? ''), 'action=file'), 'live CDN still uses our file endpoint');
expect_true(empty($remote['discordUrl']), 'un-cached attachment still omits Discord CDN url');
expect_eq($remote['local'] ?? null, false, 'empty localPath is not local');
expect_eq($remote['playable'] ?? null, true, 'signed live CDN is playable');

$stale = gos_discord_public_attachment([
    'id' => 9,
    'filename' => 'old.mp4',
    'contentType' => 'video/mp4',
    'discordUrl' => 'https://cdn.discordapp.com/attachments/1/2/old.mp4?ex=' . dechex(time() - 3600),
    'localPath' => '',
]);
expect_eq($stale['url'] ?? 'x', '', 'expired CDN without cache has no playable url');
expect_eq($stale['playable'] ?? null, false, 'expired CDN without cache is not playable');
expect_eq($stale['local'] ?? null, false, 'expired CDN is not local');

$unsigned = gos_discord_public_attachment([
    'id' => 10,
    'filename' => 'bare.ogg',
    'contentType' => 'audio/ogg',
    'discordUrl' => 'https://cdn.discordapp.com/attachments/1/2/bare.ogg',
    'localPath' => '',
]);
expect_eq($unsigned['playable'] ?? null, false, 'unsigned Discord CDN is not playable');
expect_eq($unsigned['url'] ?? 'x', '', 'unsigned Discord CDN has no playable url');

$cachedDeadCdn = gos_discord_public_attachment([
    'id' => 11,
    'filename' => 'keep.ogg',
    'contentType' => 'audio/ogg',
    'discordUrl' => 'https://cdn.discordapp.com/attachments/1/2/keep.ogg?ex=dead',
    'localPath' => '/var/www/avalynn/uploads/discord-files/11_keep.ogg',
]);
expect_eq($cachedDeadCdn['playable'] ?? null, true, 'cached file stays playable after CDN expires');
expect_true(str_contains((string) ($cachedDeadCdn['url'] ?? ''), 'id=11'), 'cached file still has local url');

expect_true(gos_discord_cdn_url_usable('https://cdn.discordapp.com/a.png?ex=' . dechex(time() + 120)), 'future ex= is usable');
expect_true(!gos_discord_cdn_url_usable('https://cdn.discordapp.com/a.png?ex=' . dechex(time() - 120)), 'past ex= is stale');
expect_true(!gos_discord_cdn_url_usable('https://cdn.discordapp.com/a.png'), 'unsigned CDN is stale');
expect_true(!gos_discord_cdn_url_usable(''), 'empty CDN is stale');
expect_true(gos_discord_attachment_is_playable(['localPath' => '/tmp/x.ogg', 'discordUrl' => '']), 'localPath is playable');
expect_true(!gos_discord_attachment_is_playable(['localPath' => '', 'discordUrl' => 'https://cdn.discordapp.com/a.ogg']), 'bare CDN is not playable');
expect_true(
    str_contains(gos_discord_attachment_playable_sql('a'), 'localPath'),
    'playable SQL prefers cached files',
);
expect_true(gos_discord_query_flag('1'), 'query flag 1 is on');
expect_true(gos_discord_query_flag('true'), 'query flag true is on');
expect_true(!gos_discord_query_flag('0'), 'query flag 0 is off');

$ch = gos_discord_channel_bot_rows(
    [['id' => 2, 'name' => 'LYNX'], ['id' => 3, 'name' => 'AvalynnAI NG'], ['id' => 4, 'name' => 'AvalynnAI']],
    [2 => true],
    [],
    [],
    'c1',
);
expect_eq(count($ch), 1, 'channel toggles only for bots in the guild');
expect_eq($ch[0]['botId'] ?? null, 2, 'channel bot is LYNX');

expect_eq(gos_discord_classify_channel_kind(0, 'general', '1'), 'channel', 'slug is a channel');
expect_eq(gos_discord_classify_channel_kind(0, 'android-feedback', '1'), 'channel', 'hyphen slug is a channel');
expect_eq(gos_discord_classify_channel_kind(0, '✈️┃greyday-tour', '1'), 'channel', 'emoji prefix channel');
expect_eq(gos_discord_classify_channel_kind(0, 'help', '1'), 'channel', 'lowercase help is a channel');
expect_eq(gos_discord_classify_channel_kind(0, 'Help', '1'), 'thread', 'title-case is a forum post');
expect_eq(gos_discord_classify_channel_kind(0, '???', '1'), 'thread', 'punctuation title is a thread');
expect_eq(gos_discord_classify_channel_kind(0, 'I love the weather in Colorado', '1'), 'thread', 'spaces mark forum posts');
expect_eq(gos_discord_classify_channel_kind(0, 'https://example.com/x', '1'), 'thread', 'url title is a thread');
expect_eq(gos_discord_classify_channel_kind(11, 'general', '1'), 'thread', 'discord thread type');
expect_eq(gos_discord_classify_channel_kind(15, 'ideas', '1'), 'channel', 'forum parent is a channel');
expect_eq(gos_discord_classify_channel_kind(5, 'announcements', '1'), 'channel', 'announcement channel');
expect_eq(gos_discord_classify_channel_kind(0, 'I love Colorado', '99', ['99' => true]), 'channel', 'live guild id wins');
expect_eq(gos_discord_classify_channel_kind(0, 'general', '99', ['1' => true]), 'thread', 'missing from live set is thread');
expect_eq(gos_discord_channel_list_kind([]), 'channels', 'default kind is channels');
expect_eq(gos_discord_channel_list_kind(['threads' => '1']), 'all', 'legacy threads=1 is all');
expect_eq(gos_discord_channel_list_kind(['kind' => 'threads']), 'threads', 'kind threads');
expect_true(gos_discord_name_contains('GREYDAY Tour', 'tour'), 'case-insensitive name search');

$sql = gos_discord_audits_select_sql(
    ['guildId' => '1105891499641684019', 'timeframe' => '7d', 'limit' => 40, 'offset' => 0],
    [2, 3, 4],
);
expect_true(str_contains($sql['sql'], 'guildId = ?'), 'guild audits filter guildId');
expect_true(str_contains($sql['sql'], 'UNION ALL'), 'guild audits merge per-bot range scans');
expect_true(str_contains($sql['sql'], 'GuildAuditEvent_botId_createdAt_idx'), 'guild audits use bot+time index');
expect_true(!str_contains($sql['sql'], 'botId IN'), 'guild audits do not scan every bot via IN');
expect_true(str_contains($sql['sql'], '`before`'), 'audits select before JSON');
expect_true(str_contains($sql['sql'], '`after`'), 'audits select after JSON');
expect_true(str_contains($sql['sql'], 'e.metadata'), 'audits select metadata JSON');
expect_eq($sql['params'][1] ?? null, '1105891499641684019', 'guildId bound with first bot');

$act = gos_discord_audits_select_sql(
    ['guildId' => '1105891499641684019', 'action' => 'message_delete', 'timeframe' => '7d', 'limit' => 40, 'offset' => 0],
    [2, 3, 4],
);
expect_true(str_contains($act['sql'], 'GuildAuditEvent_guildId_action_createdAt_idx'), 'guild+action uses action index');

$all = gos_discord_audits_select_sql(
    ['timeframe' => '1d', 'limit' => 40, 'offset' => 0],
    [2, 3, 4],
);
expect_true(str_contains($all['sql'], 'UNION ALL'), 'all-guild audits merge per-bot range scans');
expect_true(str_contains($all['sql'], 'GuildAuditEvent_botId_createdAt_idx'), 'all-guild audits use bot+time index');

$collided = gos_discord_audits_select_sql(
    ['action' => 'audits', 'timeframe' => '1d', 'limit' => 40, 'offset' => 0],
    [2, 3, 4],
);
expect_true(!in_array('audits', $collided['params'], true), 'dispatcher action=audits is not an event filter');
expect_true(!str_contains($collided['sql'], 'e.action = ?'), 'API verb must not add an action predicate');

$byEvent = gos_discord_audits_select_sql(
    [
        'action' => 'audits',
        'eventAction' => 'message_delete',
        'guildId' => '1105891499641684019',
        'timeframe' => '7d',
        'limit' => 40,
        'offset' => 0,
    ],
    [2, 3, 4],
);
expect_true(str_contains($byEvent['sql'], 'GuildAuditEvent_guildId_action_createdAt_idx'), 'eventAction uses guild+action index');
expect_true(in_array('message_delete', $byEvent['params'], true), 'eventAction bound as audit action');

$usersAll = gos_discord_users_select_sql(['limit' => 40, 'offset' => 0, 'sort' => 'lastActive']);
expect_true(str_contains($usersAll['sql'], 'FROM User'), 'global users read User');
expect_true(!str_contains($usersAll['sql'], 'GuildMemberEvent'), 'global users do not scan member events');

$usersGuild = gos_discord_users_select_sql([
    'guildId' => '1320903119827112047',
    'limit' => 40,
    'offset' => 0,
]);
expect_true(str_contains($usersGuild['sql'], 'GuildMemberEvent'), 'guild users come from member events');
expect_true(in_array('1320903119827112047', $usersGuild['params'], true), 'guildId bound for users');

$usersFind = gos_discord_users_select_sql([
    'guildId' => '1320903119827112047',
    'search' => 'iberry',
    'limit' => 40,
    'offset' => 0,
]);
expect_true(str_contains($usersFind['sql'], 'username LIKE ?'), 'search+guild keeps username prefix match');
expect_true(!str_contains($usersFind['sql'], 'displayName LIKE'), 'name search uses username index only');
expect_eq($usersFind['guildId'] ?? '', '1320903119827112047', 'search+guild keeps guildId for membership filter');
expect_true(!str_contains($usersFind['sql'], 'EXISTS'), 'membership is not an EXISTS on the User scan');

$del = gos_discord_public_audit_event(
    [
        'id' => 11,
        'botId' => 2,
        'guildId' => 'g1',
        'action' => 'message_delete',
        'targetType' => 'message',
        'targetId' => 'm9',
        'actorId' => 'u1',
        'before' => json_encode([
            'content' => 'bye world',
            'authorId' => 'u1',
            'channelId' => 'c1',
            'attachments' => [
                ['id' => 7, 'filename' => 'clip.gif', 'contentType' => 'image/gif'],
            ],
        ], JSON_UNESCAPED_SLASHES),
        'after' => null,
        'metadata' => json_encode(['channelName' => 'general', 'username' => 'mod', 'displayName' => 'Mod'], JSON_UNESCAPED_SLASHES),
        'createdAt' => '2026-08-20 17:40:13.770',
    ],
    ['username' => 'mod', 'displayName' => 'Mod', 'avatar' => 'abc'],
    [],
    ['guildName' => 'OpenAI', 'guildIcon' => ''],
);
expect_eq($del['beforeText'] ?? '', 'bye world', 'delete exposes message body');
expect_eq($del['channelName'] ?? '', 'general', 'delete exposes channel name');
expect_eq(count($del['beforeAttachments'] ?? []), 1, 'delete keeps cached attachments');
expect_true(str_contains((string) ($del['beforeAttachments'][0]['url'] ?? ''), 'action=file'), 'delete attachment is local file url');
expect_true(empty($del['beforeAttachments'][0]['discordUrl'] ?? null), 'delete attachment omits Discord CDN');

$edit = gos_discord_public_audit_event(
    [
        'id' => 12,
        'botId' => 2,
        'guildId' => 'g1',
        'action' => 'message_edit',
        'targetType' => 'message',
        'targetId' => 'm8',
        'actorId' => 'u1',
        'before' => '{"content":"ni","attachments":[]}',
        'after' => json_encode([
            'content' => 'no',
            'attachments' => [
                ['id' => '123456789012345678', 'filename' => 'x.png', 'contentType' => 'image/png', 'discordUrl' => 'https://cdn.discordapp.com/attachments/1/2/x.png'],
            ],
        ], JSON_UNESCAPED_SLASHES),
        'metadata' => '{"channelName":"merch-chat","username":"nynabtw"}',
        'createdAt' => '2026-08-20 17:39:18.594',
    ],
    ['username' => 'nynabtw', 'displayName' => '', 'avatar' => ''],
    [],
    ['guildName' => 'GREYCORD'],
);
expect_eq($edit['beforeText'] ?? '', 'ni', 'edit before text');
expect_eq($edit['afterText'] ?? '', 'no', 'edit after text');
expect_eq($edit['afterAttachments'][0]['url'] ?? 'x', '', 'snowflake attachment ids are not local files');
expect_true(empty($edit['afterAttachments'][0]['discordUrl'] ?? null), 'edit after omits Discord CDN');

$mediaOnly = gos_discord_public_audit_event(
    [
        'id' => 13,
        'botId' => 2,
        'guildId' => 'g1',
        'action' => 'message_delete',
        'targetType' => 'message',
        'targetId' => '1540063367421497456',
        'actorId' => 'u1',
        'before' => json_encode([
            'content' => '',
            'authorId' => 'u1',
            'channelId' => 'c1',
            'attachments' => [
                ['id' => 575004, 'filename' => 'shot.jpg', 'contentType' => 'image/jpeg'],
            ],
        ], JSON_UNESCAPED_SLASHES),
        'after' => null,
        'metadata' => json_encode(['channelName' => 'nsfw'], JSON_UNESCAPED_SLASHES),
        'createdAt' => '2026-08-20 18:22:24.997',
    ],
    ['username' => 'excessive_fours'],
    [],
    ['guildName' => 'OpenAI'],
);
expect_eq($mediaOnly['beforeText'] ?? 'x', '', 'image-only delete has empty text');
expect_eq($mediaOnly['beforeAttachments'][0]['kind'] ?? '', 'image', 'image-only delete keeps image kind');
expect_true(str_contains((string) ($mediaOnly['beforeAttachments'][0]['url'] ?? ''), 'id=575004'), 'image-only delete serves local file');

$resolvedEdit = gos_discord_merge_audit_attachments(
    [
        [
            'id' => '123456789012345678',
            'filename' => 'x.png',
            'contentType' => 'image/png',
            'discordUrl' => 'https://cdn.discordapp.com/attachments/1/123456789012345678/x.png',
        ],
    ],
    [
        [
            'id' => 99,
            'filename' => 'x.png',
            'contentType' => 'image/png',
            'localPath' => '/var/www/avalynn/uploads/discord-files/ab_x.png',
            'discordUrl' => 'https://cdn.discordapp.com/attachments/1/123456789012345678/x.png',
        ],
    ],
);
expect_eq(count($resolvedEdit), 1, 'snowflake attachment maps onto cached row');
expect_eq($resolvedEdit[0]['id'] ?? 0, 99, 'mapped attachment uses MessageAttachment id');
expect_true(str_contains((string) ($resolvedEdit[0]['url'] ?? ''), 'id=99'), 'mapped attachment is local file url');
expect_true(empty($resolvedEdit[0]['discordUrl'] ?? null), 'mapped attachment omits Discord CDN');

$filled = gos_discord_merge_audit_attachments(
    [],
    [
        [
            'id' => 7,
            'filename' => 'clip.gif',
            'contentType' => 'image/gif',
            'localPath' => '/var/www/avalynn/uploads/discord-files/clip.gif',
        ],
    ],
    true,
);
expect_eq(count($filled), 1, 'delete with empty JSON still hydrates cached media');
expect_eq($filled[0]['kind'] ?? '', 'gif', 'hydrated delete media keeps gif kind');
expect_true(str_contains((string) ($filled[0]['url'] ?? ''), 'id=7'), 'hydrated delete media is local file url');

$editEmpty = gos_discord_merge_audit_attachments(
    [],
    [['id' => 7, 'filename' => 'clip.gif', 'contentType' => 'image/gif', 'localPath' => '/tmp/clip.gif']],
    false,
);
expect_eq($editEmpty, [], 'edit after does not invent attachments from db');

$hydratedDelete = gos_discord_public_audit_event(
    [
        'id' => 14,
        'botId' => 2,
        'guildId' => 'g1',
        'action' => 'message_delete',
        'targetType' => 'message',
        'targetId' => 'm-media',
        'actorId' => 'u1',
        'before' => '{"content":"","attachments":[]}',
        'after' => null,
        'metadata' => '{"channelName":"general"}',
        'createdAt' => '2026-08-20 18:22:24.997',
    ],
    [],
    [],
    ['guildName' => 'OpenAI'],
    [
        [
            'id' => 42,
            'filename' => 'clip.mp4',
            'contentType' => 'video/mp4',
            'localPath' => '/var/www/avalynn/uploads/discord-files/42_clip.mp4',
        ],
    ],
);
expect_eq($hydratedDelete['beforeText'] ?? 'x', '', 'hydrated media delete has empty text');
expect_eq(count($hydratedDelete['beforeAttachments'] ?? []), 1, 'hydrated media delete includes db attachment');
expect_eq($hydratedDelete['beforeAttachments'][0]['kind'] ?? '', 'video', 'hydrated media delete keeps video kind');
expect_true(str_contains((string) ($hydratedDelete['beforeAttachments'][0]['url'] ?? ''), 'id=42'), 'hydrated media delete serves local file');

$hashDir = sys_get_temp_dir() . '/gos-att-hash';
@mkdir($hashDir, 0775, true);
$hashFile = $hashDir . '/c929038dbdcdbfb6bac248e258b936e4_shot.jpg';
file_put_contents($hashFile, 'jpeg-bytes');
putenv('GROKIFY_DISCORD_FILES_DIR=' . $hashDir);
$foundHash = gos_discord_resolve_attachment_path(null, 'shot.jpg', 575004, 'c929038dbdcdbfb6bac248e258b936e4');
expect_eq($foundHash, $hashFile, 'fileHash locates cached attachment');
putenv('GROKIFY_DISCORD_FILES_DIR');
@unlink($hashFile);
@rmdir($hashDir);

$avatars = [];
foreach (['aa11', 'bb22', 'cc33'] as $i => $hash) {
    $avatars[] = gos_discord_public_audit_event(
        [
            'id' => 20 + $i,
            'botId' => 2,
            'guildId' => 'g1',
            'action' => 'avatar_change',
            'targetType' => 'user',
            'targetId' => '737393121561673789',
            'actorId' => '',
            'before' => '{"avatar":"old' . $hash . '"}',
            'after' => '{"avatar":"' . $hash . '"}',
            'metadata' => json_encode([
                'username' => 'amlous',
                'displayName' => 'mari',
                'beforeAvatarPath' => '/uploads/audit-avatars/737393121561673789_old' . $hash . '.png',
                'afterAvatarPath' => '/uploads/audit-avatars/737393121561673789_' . $hash . '.png',
            ], JSON_UNESCAPED_SLASHES),
            'createdAt' => '2026-08-20 18:1' . $i . ':00.000',
        ],
        [],
        ['username' => 'amlous', 'displayName' => 'mari', 'avatar' => $hash],
        ['guildName' => 'GREYCORD'],
    );
}
expect_eq(count($avatars), 3, 'avatar history is not capped at 2');
expect_true(str_contains((string) ($avatars[0]['beforeAvatar'] ?? ''), 'action=avatar'), 'avatar before is signed local url');
expect_true(str_contains((string) ($avatars[2]['afterAvatar'] ?? ''), 'cc33'), 'third avatar change keeps its own hash');
expect_true(!str_contains((string) json_encode($avatars[0]), 'beforeAvatarPath'), 'raw filesystem avatar paths stay off the wire');

$name = gos_discord_public_audit_event(
    [
        'id' => 30,
        'action' => 'username_change',
        'targetType' => 'user',
        'targetId' => 'u2',
        'guildId' => 'g1',
        'before' => '{"value":"ydkyvngchrist"}',
        'after' => '{"value":"ydkyvngcutthroat"}',
        'metadata' => '{"username":"ydkyvngcutthroat"}',
        'createdAt' => '2026-08-20 18:07:28.837',
    ],
    [],
    ['username' => 'ydkyvngcutthroat', 'displayName' => 'vice'],
    ['guildName' => 'GREYCORD'],
);
expect_eq($name['beforeText'] ?? '', 'ydkyvngchrist', 'username before');
expect_eq($name['afterText'] ?? '', 'ydkyvngcutthroat', 'username after');

$nick = gos_discord_public_audit_event(
    [
        'id' => 31,
        'action' => 'displayname_change',
        'targetType' => 'user',
        'targetId' => 'u2',
        'guildId' => 'g1',
        'before' => '{"value":"abu"}',
        'after' => '{"value":"vice"}',
        'metadata' => '{}',
        'createdAt' => '2026-08-20 17:52:55.449',
    ],
    [],
    ['username' => 'ydkyvngcutthroat', 'displayName' => 'vice'],
    ['guildName' => 'GREYCORD'],
);
expect_eq($nick['beforeText'] ?? '', 'abu', 'display name before');
expect_eq($nick['afterText'] ?? '', 'vice', 'display name after');

$role = gos_discord_public_audit_event(
    [
        'id' => 40,
        'action' => 'role_assign',
        'targetType' => 'user',
        'targetId' => 'u3',
        'guildId' => 'g1',
        'before' => null,
        'after' => '{"roleId":"1124425291620679753","roleName":"Member"}',
        'metadata' => '{"username":"wxtto5o9","displayName":"wxtto5o9"}',
        'createdAt' => '2026-08-20 18:33:47.758',
    ],
    [],
    ['username' => 'wxtto5o9', 'displayName' => 'wxtto5o9'],
    ['guildName' => 'GREYCORD'],
);
expect_eq($role['afterText'] ?? '', 'Member', 'role assign name');
expect_eq($role['targetUsername'] ?? '', 'wxtto5o9', 'role target from user row');

$tagsJson = gos_discord_parse_message_tags('["alpha","beta",""]');
expect_eq($tagsJson, ['alpha', 'beta'], 'json tag array parsed');
$tagsCsv = gos_discord_parse_message_tags('grok community,off-topic,wow');
expect_eq($tagsCsv, ['grok community', 'off-topic', 'wow'], 'csv tags parsed');
expect_true(gos_discord_is_bogus_tag('3'), 'single-digit numeric tags are bogus');
expect_true(!gos_discord_is_bogus_tag('0'), 'frustration tag 0 is kept');
expect_true(!gos_discord_is_bogus_tag('grok community'), 'named tags are kept');

$agg = gos_discord_tag_counts([
    'alpha,beta,3',
    '["alpha","wow"]',
    'beta',
]);
expect_eq($agg['counts']['alpha'] ?? 0, 2, 'tag counts stack');
expect_eq($agg['counts']['beta'] ?? 0, 2, 'csv and later rows stack');
expect_eq($agg['counts']['wow'] ?? 0, 1, 'json tag counted');
expect_true(!isset($agg['counts']['3']), 'bogus tags dropped from counts');
expect_eq($agg['total'], 5, 'total tag occurrences');
expect_eq($agg['unique'], 3, 'unique tag count');

$ranked = gos_discord_top_tags($agg['counts'], 2, 0);
expect_eq($ranked['tags'][0]['tag'] ?? '', 'alpha', 'top tag first');
expect_eq($ranked['tags'][0]['count'] ?? 0, 2, 'top tag count');
expect_eq(count($ranked['tags']), 2, 'tag limit applied');
expect_true($ranked['hasMore'], 'has more tags when truncated');

$resolved = gos_discord_resolve_tag_names(['11', 'wow'], [11 => '11:11']);
expect_eq($resolved, ['11:11', 'wow'], 'numeric tag ids resolve to names');

$hist = gos_discord_history_changes(
    [
        ['field' => 'username', 'oldValue' => 'old', 'newValue' => 'iberry420', 'changedAt' => '2026-03-01 12:00:00'],
        ['field' => 'displayName', 'oldValue' => 'x', 'newValue' => 'iBerry', 'changedAt' => '2026-03-02 12:00:00'],
        ['field' => 'avatar', 'oldValue' => 'aaa', 'newValue' => 'bbb', 'changedAt' => '2026-03-03 12:00:00'],
        ['field' => 'username', 'oldValue' => 'older', 'newValue' => 'old', 'changedAt' => '2026-02-01 12:00:00'],
    ],
    'username',
    20,
);
expect_eq(count($hist), 2, 'username history filtered');
expect_eq($hist[0]['newValue'] ?? '', 'iberry420', 'newest username change first');

$avatars = gos_discord_history_changes(
    [
        ['field' => 'avatar', 'oldValue' => 'aaa111', 'newValue' => 'bbb222', 'changedAt' => '2026-03-03 12:00:00'],
    ],
    'avatar',
    20,
    '1113336067689562112',
);
expect_true(str_contains((string) ($avatars[0]['oldValue'] ?? ''), 'action=avatar'), 'avatar history old is local url');
expect_true(str_contains((string) ($avatars[0]['newValue'] ?? ''), 'action=avatar'), 'avatar history new is local url');
expect_true(str_contains((string) ($avatars[0]['oldValue'] ?? ''), 'user=1113336067689562112'), 'avatar history binds discord id');

$xpNeed = gos_discord_xp_for_level(2);
expect_eq($xpNeed, 150, 'level 2 needs 150 xp');
$prog = gos_discord_level_progress(75, 2);
expect_eq($prog, 50.0, '75/150 is 50 percent');

$lookupSnowflake = gos_discord_profile_lookup(['id' => '1113336067689562112']);
expect_true($lookupSnowflake['byDiscordId'], 'long snowflake id is discord lookup');
expect_eq($lookupSnowflake['key'] ?? '', '1113336067689562112', 'snowflake key kept');
$lookupInternal = gos_discord_profile_lookup(['id' => '9']);
expect_true(!$lookupInternal['byDiscordId'], 'small id is internal user id');
$lookupFlag = gos_discord_profile_lookup(['id' => '1113336067689562112', 'byDiscordId' => 'true']);
expect_true($lookupFlag['byDiscordId'], 'byDiscordId flag honored');

$userFilter = gos_discord_messages_user_filter(['userId' => '9']);
expect_true(str_contains($userFilter['clause'], 'm.userId = ?'), 'internal userId filters Message.userId');
expect_eq($userFilter['params'], [9], 'internal userId bound as int');
$didFilter = gos_discord_messages_user_filter(['userId' => '1113336067689562112']);
expect_true(str_contains($didFilter['clause'], 'u.discordId = ?'), 'snowflake userId filters User.discordId');

$profile = gos_discord_profile_payload(
    [
        'id' => 9,
        'discordId' => '1113336067689562112',
        'username' => 'iberry420',
        'displayName' => 'iBerry',
        'avatar' => '37ce103ed73d651d36f1b3698fe401e6',
        'level' => 16,
        'xp' => 75,
        'totalXp' => 92989,
        'activityScore' => 100,
        'lastActivity' => '2026-08-20 12:00:00',
        'createdAt' => '2024-01-01 00:00:00',
    ],
    2519,
    [
        ['id' => '1320903119827112047', 'name' => 'GROK COMMUNITY', 'messageCount' => 12],
    ],
    [
        ['id' => 'c1', 'name' => 'bot-chat', 'guildId' => '1320903119827112047', 'guildName' => 'GROK COMMUNITY', 'messageCount' => 8],
    ],
    [
        ['oldValue' => 'old', 'newValue' => 'iberry420', 'changedAt' => '2026-03-01 12:00:00'],
    ],
    [
        ['oldValue' => 'x', 'newValue' => 'iBerry', 'changedAt' => '2026-03-02 12:00:00'],
    ],
    [
        ['oldValue' => 'https://host/api/discord.php?action=avatar&user=1&hash=a', 'newValue' => 'https://host/api/discord.php?action=avatar&user=1&hash=b', 'changedAt' => '2026-03-03 12:00:00'],
    ],
    [
        'topTags' => [['tag' => 'grok community', 'count' => 40]],
        'totalTagCount' => 40,
        'uniqueTagCount' => 1,
        'hasMoreTags' => false,
    ],
);
expect_eq($profile['username'] ?? '', 'iberry420', 'profile username');
expect_eq($profile['messageCount'] ?? 0, 2519, 'profile message count');
expect_eq($profile['activeGuilds'][0]['name'] ?? '', 'GROK COMMUNITY', 'profile guilds');
expect_eq($profile['activeChannels'][0]['name'] ?? '', 'bot-chat', 'profile channels');
expect_eq($profile['usernameChanges'][0]['newValue'] ?? '', 'iberry420', 'profile username history');
expect_eq($profile['topTags'][0]['tag'] ?? '', 'grok community', 'profile tag chart data');
expect_true(str_contains((string) ($profile['avatar'] ?? ''), 'action=avatar'), 'profile avatar is local');
expect_eq($profile['xpToNextLevel'] ?? 0, gos_discord_xp_for_level(16), 'xp to next level');

$norm = gos_discord_ai_normalize_tags([
    'Colorado', '  LOVE  ', 'weather', 'weather', '3', '0', 'this tag is way too long for the semantic tagger and should be dropped',
    'blue sky', 'Colorado',
]);
expect_eq($norm[0] ?? '', 'colorado', 'tags lowercased');
expect_eq($norm[1] ?? '', 'love', 'tags trimmed');
expect_true(!in_array('3', $norm, true), 'bogus numeric tags dropped');
expect_true(in_array('0', $norm, true), 'frustration tag 0 kept');
expect_eq(count(array_filter($norm, static fn ($t) => $t === 'colorado')), 1, 'duplicate tags collapsed');
expect_true(!in_array('this tag is way too long for the semantic tagger and should be dropped', $norm, true), 'overlong tags dropped');

$padded = [];
for ($i = 0; $i < 40; $i++) {
    $padded[] = 'tag' . $i;
}
expect_eq(count(gos_discord_ai_normalize_tags($padded)), 32, 'cap at 32 tags');

$parsedJson = gos_discord_ai_parse_model_tags('```json
{"tags":["Colorado","love","happy","3"]}
```');
expect_eq($parsedJson[0] ?? '', 'colorado', 'fenced json parsed');
expect_true(in_array('happy', $parsedJson, true), 'json tags kept');
expect_true(!in_array('3', $parsedJson, true), 'bogus tags stripped from model json');

$parsedBare = gos_discord_ai_parse_model_tags('here you go {"tags":["mountains","sky"]} thanks');
expect_eq($parsedBare, ['mountains', 'sky'], 'json object extracted from prose');

expect_eq(gos_discord_ai_scope_of(['channelId' => '1320903119827112047']), 'channel', 'channel scope');
expect_eq(gos_discord_ai_scope_of(['guildId' => '1320903119827112047']), 'guild', 'guild scope');
expect_eq(gos_discord_ai_scope_of(['userId' => '9']), 'user', 'user scope');
expect_eq(gos_discord_ai_scope_of([]), 'all', 'all-channels scope');
expect_eq(gos_discord_ai_date_ok('2026-08-01'), '2026-08-01', 'date only');
expect_eq(gos_discord_ai_date_ok('2026-08-01T14:30'), '2026-08-01 14:30:00', 'datetime normalized');
expect_eq(gos_discord_ai_date_ok('nope'), '', 'invalid date rejected');

$prompt = gos_discord_ai_user_prompt([
    'guildName' => 'GROK COMMUNITY',
    'channelName' => 'bot-chat',
    'displayName' => 'iBerry',
    'content' => 'i love the weather in Colorado',
]);
expect_true(str_contains($prompt, 'GROK COMMUNITY'), 'prompt includes guild');
expect_true(str_contains($prompt, '#bot-chat'), 'prompt includes channel');
expect_true(str_contains($prompt, 'i love the weather in Colorado'), 'prompt includes message');

$mentions = gos_discord_ai_parse_mentions('hey <@!1449629894131847188> ping <@&1341640319799660544> in <#1432177610351185951> and <@1449629894131847188>');
expect_eq(count($mentions), 3, 'unique user/role/channel mentions');
expect_eq($mentions[0]['kind'] ?? '', 'user', 'user mention kind');
expect_eq($mentions[0]['canonical'] ?? '', '<@1449629894131847188>', 'user mention canonical token');
expect_eq($mentions[1]['kind'] ?? '', 'role', 'role mention kind');
expect_eq($mentions[1]['canonical'] ?? '', '<@&1341640319799660544>', 'role mention canonical token');
expect_eq($mentions[2]['kind'] ?? '', 'channel', 'channel mention kind');
expect_eq($mentions[2]['canonical'] ?? '', '<#1432177610351185951>', 'channel mention canonical token');

$filled = gos_discord_ai_fill_mentions($mentions, [
    '1449629894131847188' => ['username' => 'avalynn', 'displayName' => 'Avalynn'],
], [
    '1341640319799660544' => 'Developer',
], [
    '1432177610351185951' => 'bot-chat',
]);
expect_eq($filled[0]['label'] ?? '', '@Avalynn / avalynn', 'user mention label');
expect_eq($filled[1]['label'] ?? '', '@Developer role', 'role mention label');
expect_eq($filled[2]['label'] ?? '', '#bot-chat', 'channel mention label');

$filledWithRole = gos_discord_ai_fill_mentions($mentions, [
    '1449629894131847188' => ['username' => 'avalynn', 'displayName' => 'Avalynn'],
], [
    '1341640319799660544' => 'Developer',
], [
    '1432177610351185951' => 'bot-chat',
], [
    '1449629894131847188' => ['Developer', 'Gamer'],
]);
expect_eq($filledWithRole[0]['label'] ?? '', '@Avalynn / avalynn · Developer, Gamer', 'user mention label includes member roles');
expect_true(str_contains(gos_discord_ai_annotate_content(
    'hey <@1449629894131847188>',
    $filledWithRole,
), '<@1449629894131847188> (@Avalynn / avalynn · Developer, Gamer)'), 'annotates member role next to name');

$roleEvents = [
    ['guildId' => '1320903119827112047', 'targetId' => '1449629894131847188', 'action' => 'role_assign', 'after' => '{"roleId":"1002964899376418866","roleName":"Member"}'],
    ['guildId' => '1320903119827112047', 'targetId' => '1449629894131847188', 'action' => 'role_assign', 'after' => '{"roleId":"1341640319799660544","roleName":"Developer"}'],
    ['guildId' => '1320903119827112047', 'targetId' => '1449629894131847188', 'action' => 'role_remove', 'before' => '{"roleId":"1002964899376418866","roleName":"Member"}'],
    ['guildId' => '1320903119827112047', 'targetId' => '1449629894131847188', 'action' => 'role_assign', 'after' => '{"roleId":"1320903119827112047","roleName":"@everyone"}'],
];
$fromEvents = gos_discord_ai_member_roles_from_events($roleEvents);
expect_eq($fromEvents['1320903119827112047']['1449629894131847188'] ?? null, ['Developer'], 'member roles reconstruct assign/remove and skip @everyone');
expect_eq(gos_discord_ai_format_member_roles(['Developer', 'Gamer', 'everyone']), 'Developer, Gamer', 'format skips everyone');

$rolePrompt = gos_discord_ai_user_prompt([
    'guildName' => 'GROK COMMUNITY',
    'channelName' => 'bot-chat',
    'displayName' => 'Avalynn',
    'username' => 'avalynn',
    'content' => 'hey',
    'authorRoles' => ['Developer', 'Gamer'],
]);
expect_true(str_contains($rolePrompt, 'Author: Avalynn (@avalynn) · Developer, Gamer'), 'tag prompt puts member roles next to author');

$roleTranscript = gos_discord_ai_format_transcript([
    [
        'createdAt' => '2026-08-21 12:00:00',
        'displayName' => 'Avalynn',
        'username' => 'avalynn',
        'guildName' => 'GROK COMMUNITY',
        'channelName' => 'bot-chat',
        'content' => 'hey',
        'authorRoles' => ['Developer'],
        'mentions' => $filledWithRole,
    ],
]);
expect_true(str_contains($roleTranscript, '@Avalynn · Developer'), 'transcript puts member role next to author');
expect_true(str_contains($roleTranscript, '@Avalynn / avalynn · Developer, Gamer'), 'transcript mention list includes member roles');
$annotated = gos_discord_ai_annotate_content(
    'hey <@!1449629894131847188> ping <@&1341640319799660544> in <#1432177610351185951>',
    $filled,
);
expect_true(str_contains($annotated, '<@!1449629894131847188> (@Avalynn / avalynn)'), 'annotates user token + name');
expect_true(str_contains($annotated, '<@&1341640319799660544> (@Developer role)'), 'annotates role token + name');
expect_true(str_contains($annotated, '<#1432177610351185951> (#bot-chat)'), 'annotates channel token + name');

$roles = gos_discord_ai_extract_role_names('[{"emoji":"💻","roleId":"1341640319799660544","roleName":"Developer"}]');
expect_eq($roles['1341640319799660544'] ?? '', 'Developer', 'extract role name from picker json');

$when = gos_discord_ai_format_when('2026-08-21 18:04:11.123');
expect_true(str_contains($when, '2026-08-21 18:04:11 UTC'), 'timestamp formatted in UTC');
expect_true(str_contains($when, 'Friday'), 'timestamp includes weekday');

$mentionPrompt = gos_discord_ai_user_prompt([
    'guildName' => 'GROK COMMUNITY',
    'channelName' => 'bot-chat',
    'displayName' => 'iBerry',
    'username' => 'iberry420',
    'createdAt' => '2026-08-21 18:04:11.123',
    'content' => 'hey <@1449629894131847188>',
    'annotatedContent' => 'hey <@1449629894131847188> (@Avalynn / avalynn)',
    'mentions' => $filled,
]);
expect_true(str_contains($mentionPrompt, 'Posted at:'), 'tag prompt includes timestamp');
expect_true(str_contains($mentionPrompt, '<@1449629894131847188> = @Avalynn / avalynn'), 'tag prompt lists user mention');
expect_true(str_contains($mentionPrompt, '<@&1341640319799660544> = @Developer role'), 'tag prompt lists role mention');
expect_true(str_contains($mentionPrompt, '<#1432177610351185951> = #bot-chat'), 'tag prompt lists channel mention');
expect_true(str_contains($mentionPrompt, '(@Avalynn / avalynn)'), 'tag prompt keeps annotated message');
expect_true(str_contains(gos_discord_ai_system_prompt(), '<@id>'), 'tag system prompt covers mention tokens');
expect_true(str_contains(gos_discord_ai_analyze_system_prompt(), 'time-aware'), 'analyze system prompt is time-aware');

expect_eq(gos_discord_ai_kind_of(['kind' => 'analyze']), 'analyze', 'analyze kind');
expect_eq(gos_discord_ai_kind_of(['kind' => 'tag']), 'tag', 'tag kind');
expect_eq(gos_discord_ai_kind_of([]), 'tag', 'default kind is tag');
expect_true(str_starts_with(gos_discord_ai_label(['kind' => 'analyze', 'limit' => 25, 'timeframe' => '1d'], 'guild'), 'Analyze'), 'analyze label prefix');
expect_true(str_starts_with(gos_discord_ai_label(['kind' => 'tag', 'limit' => 25, 'timeframe' => '1d'], 'channel'), 'Tag'), 'tag label prefix');

expect_eq(gos_discord_ai_prompt_ok("  focus on drama \n"), 'focus on drama', 'prompt trimmed');
expect_eq(gos_discord_ai_prompt_ok(''), '', 'blank prompt stays empty');
expect_eq(gos_discord_ai_prompt_ok("   \n\t  "), '', 'whitespace-only prompt is empty');
$longPrompt = str_repeat('a', 5000);
expect_eq(strlen(gos_discord_ai_prompt_ok($longPrompt)), 4000, 'prompt capped at 4000');

$analyzeBase = gos_discord_ai_analyze_system_prompt();
expect_eq(gos_discord_ai_with_operator_prompt($analyzeBase, ''), $analyzeBase, 'blank operator prompt is a no-op');
$focused = gos_discord_ai_with_operator_prompt($analyzeBase, 'Focus on arguments and who started them.');
expect_true(str_contains($focused, 'Focus on arguments and who started them.'), 'analyze includes operator prompt');
expect_true(str_contains($focused, 'Operator instructions'), 'analyze marks operator instructions');
expect_true(str_starts_with($focused, $analyzeBase), 'operator prompt is appended to analyze system prompt');

$mergeBase = gos_discord_ai_analyze_merge_prompt();
$merged = gos_discord_ai_with_operator_prompt($mergeBase, 'Keep the trolling timeline.');
expect_true(str_contains($merged, 'Keep the trolling timeline.'), 'merge includes operator prompt');

$tagBase = gos_discord_ai_system_prompt();
$tagFocused = gos_discord_ai_with_operator_prompt($tagBase, 'only sports terms', 'tag');
expect_true(str_contains($tagFocused, 'only sports terms'), 'tag includes operator prompt');

$pub = gos_discord_ai_public_job([
    'id' => 7,
    'kind' => 'analyze',
    'prompt' => 'Focus on arguments.',
    'summary' => 'They argued.',
]);
expect_eq($pub['prompt'] ?? '', 'Focus on arguments.', 'public job includes prompt');
expect_eq($pub['kind'] ?? '', 'analyze', 'public job keeps analyze kind');

expect_true(gos_discord_ai_result_is_job_summary(['message_id' => 0, 'tags' => '']), 'message_id 0 empty tags is a job summary row');
expect_true(gos_discord_ai_result_is_job_summary(['messageId' => 0, 'tags' => []]), 'camelCase empty tags is a job summary row');
expect_true(!gos_discord_ai_result_is_job_summary(['message_id' => 99, 'tags' => 'colorado']), 'tagged message is not a job summary');
expect_true(!gos_discord_ai_result_is_job_summary(['message_id' => 12, 'tags' => '']), 'real message_id is not a job summary');

$transcript = gos_discord_ai_format_transcript([
    [
        'createdAt' => '2026-08-21 12:00:00',
        'displayName' => 'iBerry',
        'username' => 'iberry420',
        'guildName' => 'GROK COMMUNITY',
        'channelName' => 'bot-chat',
        'content' => 'hey <@1449629894131847188> in <#1432177610351185951>',
        'annotatedContent' => 'hey <@1449629894131847188> (@Avalynn / avalynn) in <#1432177610351185951> (#bot-chat)',
        'mentions' => $filled,
        'tagList' => ['colorado', 'love', 'weather'],
    ],
], 'Full window. Summarize what took place.');
expect_true(str_contains($transcript, '(@Avalynn / avalynn)'), 'transcript includes annotated user mention');
expect_true(str_contains($transcript, 'mentions:'), 'transcript lists mentions');
expect_true(str_contains($transcript, '<@1449629894131847188> = @Avalynn / avalynn'), 'transcript pairs user token and name');
expect_true(str_contains($transcript, '2026-08-21 12:00:00 UTC'), 'transcript includes formatted timestamp');
expect_true(str_contains($transcript, 'tags: colorado, love, weather'), 'transcript includes tags');
expect_true(str_contains($transcript, '@iBerry'), 'transcript includes author');
expect_true(str_contains($transcript, 'Full window'), 'transcript includes heading');

$heading = gos_discord_ai_window_heading([
    ['createdAt' => '2026-08-21 10:00:00'],
    ['createdAt' => '2026-08-21 18:00:00'],
], false);
expect_true(str_contains($heading, '2 messages'), 'window heading includes count');
expect_true(str_contains($heading, 'from 2026-08-21 10:00:00 UTC'), 'window heading includes start');
expect_true(str_contains($heading, 'through 2026-08-21 18:00:00 UTC'), 'window heading includes end');
expect_true(str_contains($heading, 'Stay time-aware'), 'window heading asks for time awareness');

expect_eq(gos_discord_ai_provider_ok('SpaceXAI'), 'spacexai', 'provider spacexai');
expect_eq(gos_discord_ai_provider_ok('grok-build'), 'bridge', 'provider bridge alias');
expect_eq(gos_discord_ai_model_ok('gb:grok-4.6'), 'grok-4.6', 'strip gb prefix');
expect_eq(gos_discord_ai_model_ok('grok:grok-4.5'), 'grok-4.5', 'strip grok prefix');
expect_eq(gos_discord_ai_model_ok('not a model'), 'grok-4.6', 'invalid model falls back');
expect_true(gos_discord_ai_model_supports_reasoning('grok-4.6'), '4.6 is reasoning');
expect_true(gos_discord_ai_model_supports_reasoning('grok-4.5'), '4.5 is reasoning');
expect_true(!gos_discord_ai_model_supports_reasoning('grok-4-fast'), '4-fast is not reasoning');
expect_true(in_array('xhigh', gos_discord_ai_efforts_for_model('grok-4.6'), true), '4.6 has xhigh');
expect_true(!in_array('xhigh', gos_discord_ai_efforts_for_model('grok-4.5'), true), '4.5 has no xhigh');
expect_eq(gos_discord_ai_effort_ok('grok-4.6', 'xhigh'), 'xhigh', 'xhigh kept on 4.6');
expect_eq(gos_discord_ai_effort_ok('grok-4.5', 'xhigh'), 'high', 'xhigh clamped on 4.5');
expect_eq(gos_discord_ai_effort_ok('grok-4-fast', 'high'), '', 'no effort on non-reasoning');
expect_eq(gos_discord_ai_key_hint('xai-abcdefghijklmnopqrstuvwxyz'), '…wxyz', 'key hint last 4');

expect_true(gos_discord_ai_spacexai_is_text_model(['id' => 'grok-4.6', 'output_modalities' => ['text']]), 'keep grok-4.6');
expect_true(!gos_discord_ai_spacexai_is_text_model(['id' => 'grok-imagine-image', 'image_price' => 1]), 'drop imagine');
expect_true(!gos_discord_ai_spacexai_is_text_model(['id' => 'grok-imagine-video-1.5']), 'drop video');
expect_true(!gos_discord_ai_spacexai_is_text_model(['id' => 'latest']), 'drop latest alias');

$extracted = gos_discord_ai_extract_spacexai_model_rows([
    'models' => [
        ['id' => 'grok-4.6', 'output_modalities' => ['text', 'image']],
        ['id' => 'grok-imagine-image', 'output_modalities' => ['image']],
        ['id' => 'grok-4.5', 'output_modalities' => ['text']],
    ],
]);
expect_eq(array_column($extracted, 'id'), ['grok-4.6', 'grok-4.5'], 'language-models text filter');

$normalized = gos_discord_ai_normalize_model_rows([
    ['id' => 'grok-3', 'name' => 'grok-3'],
    ['id' => 'grok-4.6', 'name' => 'grok-4.6'],
    ['id' => 'gb:grok-4.5', 'name' => 'grok-4.5'],
], 'spacexai');
expect_eq($normalized[0]['id'] ?? '', 'grok-4.6', '4.6 sorts first');
expect_eq($normalized[0]['provider'] ?? '', 'spacexai', 'provider stamped');
expect_true(in_array('xhigh', $normalized[0]['reasoning_efforts'] ?? [], true), 'normalized 4.6 efforts');

$payload46 = gos_discord_ai_spacexai_payload('sys', 'user', ['model' => 'grok-4.6', 'reasoningEffort' => 'high'], ['max_tokens' => 700, 'json' => true]);
expect_eq($payload46['model'] ?? '', 'grok-4.6', 'payload model');
expect_eq($payload46['reasoning_effort'] ?? '', 'high', 'payload reasoning');
expect_true(!isset($payload46['temperature']), 'reasoning omits temperature');
expect_eq($payload46['response_format']['type'] ?? '', 'json_object', 'tag jobs request json');

$payloadFast = gos_discord_ai_spacexai_payload('sys', 'user', ['model' => 'grok-4-fast', 'reasoningEffort' => 'high'], ['temperature' => 0.4]);
expect_true(!isset($payloadFast['reasoning_effort']), 'non-reasoning omits effort');
expect_eq($payloadFast['temperature'] ?? null, 0.4, 'non-reasoning keeps temperature');

$choice = gos_discord_ai_extract_choice_text(['choices' => [['message' => ['content' => '  hello  ']]]]);
expect_eq($choice, 'hello', 'choice content trimmed');

$rt = gos_discord_ai_runtime(['provider' => 'bridge', 'model' => 'grok-4.6', 'reasoning_effort' => 'xhigh']);
expect_eq($rt['provider'], 'bridge', 'runtime provider from job');
expect_eq($rt['model'], 'grok-4.6', 'runtime model from job');
expect_eq($rt['reasoningEffort'], 'xhigh', 'runtime effort from job');

$pubModel = gos_discord_ai_public_job([
    'id' => 8,
    'kind' => 'tag',
    'provider' => 'spacexai',
    'model' => 'grok-4.6',
    'reasoning_effort' => 'high',
]);
expect_eq($pubModel['provider'] ?? '', 'spacexai', 'public job provider');
expect_eq($pubModel['model'] ?? '', 'grok-4.6', 'public job model');
expect_eq($pubModel['reasoningEffort'] ?? '', 'high', 'public job effort');

function discord_test_mysql_admin(): ?PDO
{
    $env = gos_discord_parse_env_file(gos_discord_avalynn_env_path());
    $user = (string) ($env['DB_USER'] ?? 'root');
    $pass = (string) ($env['DB_PASS'] ?? $env['DB_PASSWORD'] ?? '');
    $name = (string) ($env['DB_NAME'] ?? 'avalynn_chat');
    try {
        return new PDO(
            'mysql:unix_socket=/var/run/mysqld/mysqld.sock;dbname=' . $name . ';charset=utf8mb4',
            $user,
            $pass,
            [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
        );
    } catch (Throwable) {
        return null;
    }
}

function discord_test_pdo_still_works(callable $grab, string $label): void
{
    $pdo = $grab();
    expect_true($pdo instanceof PDO, $label . ' connected');
    if (!($pdo instanceof PDO)) {
        return;
    }
    $admin = discord_test_mysql_admin();
    if ($admin === null) {
        fwrite(STDERR, "skip {$label} reconnect: no admin pdo\n");
        return;
    }
    $cid = (int) $pdo->query('SELECT CONNECTION_ID()')->fetchColumn();
    if ($cid <= 0) {
        fwrite(STDERR, "skip {$label} reconnect: no connection id\n");
        return;
    }
    try {
        $admin->exec('KILL ' . $cid);
    } catch (Throwable $e) {
        fwrite(STDERR, "skip {$label} reconnect: kill failed " . $e->getMessage() . "\n");
        return;
    }
    $fresh = $grab();
    $ok = false;
    try {
        $ok = $fresh instanceof PDO && $fresh->query('SELECT 1') !== false;
    } catch (Throwable) {
        $ok = false;
    }
    expect_true($ok, $label . ' reconnects after server drop');
}

expect_true(!gos_pdo_alive(null), 'null pdo is not alive');
expect_true(gos_pdo_alive(gos_pdo()), 'live grokifyos pdo pings');
discord_test_pdo_still_works(static fn () => gos_pdo(), 'grokifyos pdo');
discord_test_pdo_still_works(static fn () => gos_discord_avalynn_pdo(), 'avalynn pdo');

$livePdo = gos_discord_avalynn_pdo();
if ($livePdo instanceof PDO) {
    try {
        $liveMsgs = gos_discord_ai_attach_mentions([
            [
                'content' => 'hi',
                'guildId' => '1002292111942635562',
                'discordId' => '1064057108032655390',
                'displayName' => 'Owie',
                'username' => 'system64x_',
                'createdAt' => '2026-08-22 00:44:22',
            ],
        ], $livePdo);
        $liveRoles = $liveMsgs[0]['authorRoles'] ?? null;
        expect_true(is_array($liveRoles), 'live authorRoles is a list');
        if (is_array($liveRoles) && $liveRoles !== []) {
            $livePrompt = gos_discord_ai_user_prompt($liveMsgs[0] + ['guildName' => 'x', 'channelName' => 'y']);
            expect_true(str_contains($livePrompt, ' · '), 'live author prompt includes member roles');
        }
    } catch (Throwable $e) {
        fwrite(STDERR, 'skip live member roles: ' . $e->getMessage() . "\n");
    }
}

expect_true(!gos_discord_ai_tick_ok(['ok' => false, 'error' => 'tick_lock_failed']), 'failed tick is not ok');
expect_true(gos_discord_ai_tick_ok(['ok' => true, 'status' => 200]), 'successful tick is ok');

expect_eq(gos_discord_parse_id_list('2,4'), [2, 4], 'csv bot ids');
expect_eq(gos_discord_parse_id_list('2 4'), [2, 4], 'space bot ids');
expect_eq(gos_discord_parse_id_list(['2', 4, 0, 'x']), [2, 4], 'array bot ids drop junk');
expect_eq(gos_discord_parse_id_list(''), [], 'blank bot ids');

$filtered = gos_discord_filter_guilds_by_bots([
    [
        'guildId' => 'g1',
        'guildName' => 'GREYCORD',
        'isWatched' => true,
        'bots' => [
            ['botId' => 2, 'name' => 'LYNX', 'isWatched' => true],
            ['botId' => 4, 'name' => 'AvalynnAI', 'isWatched' => false],
        ],
    ],
    [
        'guildId' => 'g2',
        'guildName' => 'Cafe',
        'isWatched' => true,
        'bots' => [
            ['botId' => 4, 'name' => 'AvalynnAI', 'isWatched' => true],
        ],
    ],
], [2]);
expect_eq(count($filtered), 1, 'bot pill keeps only member guilds');
expect_eq($filtered[0]['guildId'] ?? '', 'g1', 'LYNX guild stays');
expect_eq(count($filtered[0]['bots'] ?? []), 1, 'trimmed to selected bot');
expect_eq($filtered[0]['bots'][0]['botId'] ?? null, 2, 'selected bot is LYNX');
expect_true(!empty($filtered[0]['isWatched']), 'watched follows selected bot');

$none = gos_discord_filter_guilds_by_bots($filtered, [4]);
expect_eq(count($none), 0, 'non-member selected bot empties the list');

expect_true(gos_discord_ai_is_live_job(['scope' => 'live']), 'live scope is live');
expect_true(!gos_discord_ai_is_live_job(['scope' => 'guild']), 'guild scope is not live');

expect_eq(gos_discord_ai_pump_lanes([3, 1], true), [3, 1, 0], 'manual jobs then auto lane');
expect_eq(gos_discord_ai_pump_lanes([3], false), [3], 'no auto lane when live tagging is off');
expect_eq(gos_discord_ai_pump_lanes([], true), [0], 'auto-only lane');
expect_eq(gos_discord_ai_pump_lanes(['3', 0, 3], true), [3, 0], 'lanes drop junk and dupes');
expect_eq(gos_discord_ai_rotate_ids([3, 7, 0], 3), [7, 0, 3], 'after first job goes to the next');
expect_eq(gos_discord_ai_rotate_ids([3, 7, 0], 0), [3, 7, 0], 'after auto wraps to the first manual job');
expect_eq(gos_discord_ai_rotate_ids([3, 7, 0], 99), [3, 7, 0], 'unknown last id starts at the first lane');
expect_eq(gos_discord_ai_rotate_ids([], 1), [], 'empty lanes stay empty');
expect_true(gos_discord_ai_tick_did_work(['ok' => true, 'data' => ['done' => false]]), 'in-progress tick counts as work');
expect_true(!gos_discord_ai_tick_did_work(['ok' => true, 'data' => ['done' => true]]), 'already-done tick is not work');
expect_true(!gos_discord_ai_tick_did_work(['ok' => false]), 'failed tick is not work');

$seed = gos_discord_guild_settings_copyable([
    'guildName' => 'GREYCORD',
    'guildIcon' => 'https://cdn/x.png',
    'isWatched' => 1,
    'respondToMentions' => 1,
    'respondToReplies' => 0,
    'respondInConversation' => 0,
    'semanticTagging' => 0,
    'analyzeFiles' => 0,
]);
expect_eq($seed['isWatched'], 1, 'copy keeps watched');
expect_eq($seed['semanticTagging'], 0, 'copy keeps tagging off');
$patched = gos_discord_guild_settings_apply_patch($seed, ['semanticTagging' => true]);
expect_eq($patched['isWatched'], 1, 'tagging-only patch must not unwatch');
expect_eq($patched['respondToMentions'], 1, 'tagging-only patch keeps mentions');
expect_eq($patched['semanticTagging'], 1, 'tagging-only patch sets tagging');
$unwatch = gos_discord_guild_settings_apply_patch($patched, ['isWatched' => false]);
expect_eq($unwatch['isWatched'], 0, 'explicit unwatch still works');
expect_eq($unwatch['semanticTagging'], 1, 'explicit unwatch keeps tagging');

expect_eq(
    gos_discord_guild_settings_guild_wide_flags(null, ['semanticTagging' => false]),
    ['semanticTagging' => false],
    'guild-wide auto-tag off fans out to every row',
);
expect_eq(
    gos_discord_guild_settings_guild_wide_flags(null, ['semanticTagging' => true]),
    ['semanticTagging' => true],
    'guild-wide auto-tag on fans out to every row',
);
expect_eq(
    gos_discord_guild_settings_guild_wide_flags(2, ['semanticTagging' => false]),
    [],
    'per-bot auto-tag write does not fan out',
);
expect_eq(
    gos_discord_guild_settings_guild_wide_flags(null, ['isWatched' => false]),
    [],
    'watch-only write stays on the targeted row',
);

$tagStuck = $g;
$tagStuck['semanticTagging'] = false;
$tagStuck['bots'] = [
    ['botId' => 2, 'isWatched' => true, 'respondToMentions' => false, 'respondToReplies' => false, 'semanticTagging' => true, 'analyzeFiles' => false],
];
$tagStuckOut = gos_discord_finalize_guild_bots($tagStuck, [2 => true], [2 => 'LYNX']);
expect_true(!empty($tagStuckOut['semanticTagging']), 'a leftover bot auto-tag row turns the guild switch on');

$bucketA = gos_discord_media_exp(1_700_000_000);
$bucketB = gos_discord_media_exp(1_700_000_000 + 3600);
$bucketC = gos_discord_media_exp(1_700_000_000 + 13 * 3600);
expect_eq($bucketA, $bucketB, 'signed media expiry stays put inside a 12h bucket');
expect_true($bucketC !== $bucketA, 'signed media expiry moves after the bucket');
expect_true($bucketA >= 1_700_000_000 + 7 * 86400, 'expiry is at least seven days out');

expect_eq(gos_discord_attachments_order(''), 'DESC', 'media default is newest first');
expect_eq(gos_discord_attachments_order('newest'), 'DESC', 'newest is descending');
expect_eq(gos_discord_attachments_order('desc'), 'DESC', 'desc is newest first');
expect_eq(gos_discord_attachments_order('oldest'), 'ASC', 'oldest is ascending');
expect_eq(gos_discord_attachments_order('asc'), 'ASC', 'asc is oldest first');

expect_eq(gos_discord_id_list('1, 2,2, no, 3'), [1, 2, 3], 'exclude id list is unique positive ints');
expect_eq(gos_discord_id_list(['9', 0, -1, 9, 12]), [9, 12], 'array id list drops junk');

$src = (string) file_get_contents(dirname(__DIR__) . '/includes/discord_local.php');
expect_true(
    !preg_match('/SELECT\s+MIN\(a\.id\),\s*MAX\(a\.id\)/i', $src),
    'discogram sampling must not MIN/MAX-join MessageAttachment',
);
expect_true(str_contains($src, 'gos_discord_sync_autotag_from_global'), 'guild list syncs leftover bot auto-tag rows');
expect_true(str_contains($src, 'gos_discord_guild_settings_guild_wide_flags'), 'guild-wide auto-tag write fans out');
$win = gos_discord_sample_id_window(100_000, 8_000, 7);
expect_eq($win[1] - $win[0] + 1, 8_000, 'sample window span is inclusive');
expect_true($win[0] >= 1 && $win[1] <= 100_000, 'sample window stays inside id range');
$again = gos_discord_sample_id_window(100_000, 8_000, 7);
expect_eq($win, $again, 'seeded sample window is stable');
$tiny = gos_discord_sample_id_window(100, 8_000, 1);
expect_eq($tiny, [1, 100], 'span larger than max covers the whole table');
expect_eq(gos_discord_sample_id_window(0, 8_000), [0, 0], 'empty table has no window');

$quota = gos_discord_discogram_quota(10);
expect_eq($quota['image'] ?? 0, 4, 'discogram mix keeps images as the largest slice');
expect_eq(($quota['image'] ?? 0) + ($quota['video'] ?? 0) + ($quota['gif'] ?? 0) + ($quota['audio'] ?? 0), 10, 'discogram quota fills the page');

$pool = [];
for ($i = 1; $i <= 20; $i++) {
    $pool[] = [
        'id' => $i,
        'discordId' => $i <= 5 ? 'star' : 'other',
        'kind' => 'image',
    ];
}
$picked = gos_discord_discogram_pick($pool, 10, ['star'], [3], 42);
$pickedIds = array_map(static fn ($row) => (int) ($row['id'] ?? 0), $picked);
expect_eq(count($picked), 10, 'discogram page is 10');
expect_true(!in_array(3, $pickedIds, true), 'already-seen ids stay out of the next page');
$star = 0;
foreach ($picked as $row) {
    if (($row['discordId'] ?? '') === 'star') {
        $star++;
    }
}
expect_eq($star, 4, 'followed creators take about 40% of a discogram page');
$again = gos_discord_discogram_pick($pool, 10, ['star'], [3], 42);
expect_eq(
    array_map(static fn ($row) => (int) ($row['id'] ?? 0), $again),
    $pickedIds,
    'seeded discogram pick is stable',
);
$noFollow = gos_discord_discogram_pick($pool, 5, [], [1, 2], 7);
foreach ($noFollow as $row) {
    expect_true(($row['id'] ?? 0) > 2, 'unfollowed random pick still honors exclude');
}

$marked = gos_discord_media_annotate(
    [
        ['id' => 7, 'user' => ['discordId' => '111']],
        ['id' => 8, 'user' => ['discordId' => '222']],
    ],
    [7 => true],
    ['111' => true],
);
expect_eq($marked[0]['liked'] ?? null, true, 'liked id is flagged');
expect_eq($marked[0]['following'] ?? null, true, 'followed creator is flagged');
expect_eq($marked[1]['liked'] ?? null, false, 'other media is not liked');
expect_eq($marked[1]['following'] ?? null, false, 'other creator is not followed');

expect_eq(gos_discord_ai_target_message_id(['messageId' => '1540373626258194432']), '1540373626258194432', 'analyze can pin a discord message');
expect_eq(gos_discord_ai_target_message_id(['discordMessageId' => '12']), '', 'short ids are not snowflakes');
expect_eq(gos_discord_media_follow_key(' 123456789012345678 '), '123456789012345678', 'follow key is a snowflake');
expect_eq(gos_discord_media_follow_key('nyna'), '', 'follow key rejects names');

$videoPub = gos_discord_public_attachment([
    'id' => 42,
    'filename' => 'clip.mp4',
    'contentType' => 'video/mp4',
    'discordUrl' => '',
    'localPath' => '/var/www/avalynn/uploads/discord-files/42_clip.mp4',
]);
expect_eq($videoPub['kind'] ?? '', 'video', 'mp4 is video');
expect_true(str_contains((string) ($videoPub['thumbUrl'] ?? ''), 'action=file'), 'video thumb uses local file action');
expect_true(str_contains((string) ($videoPub['thumbUrl'] ?? ''), 'thumb=1'), 'video thumb is marked');
expect_true(str_contains((string) ($videoPub['thumbUrl'] ?? ''), 'id=42'), 'video thumb keeps attachment id');
expect_eq($att['thumbUrl'] ?? '', '', 'gif has no video thumb url');

$poster = gos_discord_cdn_poster_url('https://cdn.discordapp.com/attachments/1/2/clip.mp4?ex=abc&is=x&hm=y');
expect_true(str_starts_with($poster, 'https://media.discordapp.net/'), 'poster host is media proxy');
expect_true(str_contains($poster, 'format=jpeg'), 'poster asks for jpeg');
expect_eq(gos_discord_cdn_poster_url('https://example.com/x.mp4'), '', 'non-discord poster is rejected');

expect_eq(gos_discord_around_range(1_700_000_000, 3600), [1_700_000_000 - 3600, 1_700_000_000 + 3600], 'around window is symmetric');
expect_eq(gos_discord_around_range(1_700_000_000, 0), [0, 0], 'unbounded around has no window');
expect_eq(gos_discord_parse_ts('1700000000000'), 1_700_000_000, 'ms timestamps convert to seconds');
expect_eq(gos_discord_parse_ts('2026-08-21 20:23:43.000'), strtotime('2026-08-21 20:23:43'), 'sql datetime parses');
expect_eq(gos_discord_sql_datetime(1_700_000_000), gmdate('Y-m-d H:i:s', 1_700_000_000), 'sql datetime is utc');

expect_true(str_contains(gos_discord_messages_id_search_clause(), 'm.channelId'), 'numeric search includes channel id');
$srcMessages = (string) file_get_contents(dirname(__DIR__) . '/includes/discord_local.php');
expect_true(str_contains($srcMessages, 'gos_discord_messages_id_search_clause'), 'messages query uses id search helper');
expect_true(str_contains($srcMessages, 'aroundMessageId'), 'messages feed supports around a post');

expect_eq(
    gos_discord_playlist_merge_ids([1, 2], [2, 3, 1, 4], 3),
    [1, 2, 3],
    'playlist merge keeps order, unique ids, and max',
);
expect_eq(gos_discord_playlist_cursor_index([10, 20, 30], 9, 20), 1, 'cursor prefers attachment id');
expect_eq(gos_discord_playlist_cursor_index([10, 20, 30], 2, 0), 2, 'cursor falls back to index');
expect_eq(gos_discord_playlist_cursor_index([10, 20], 8, 99), 1, 'cursor clamps to last item');

if ($fails > 0) {
    fwrite(STDERR, "discord_local_test: {$fails} failed\n");
    exit(1);
}
fwrite(STDOUT, "discord_local_test: ok\n");
exit(0);
