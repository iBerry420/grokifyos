<?php

declare(strict_types=1);

/**
 * Discord inner-app AI: semantic tagging + conversation summaries.
 * Tag jobs write onto Avalynn Message.tags one message at a time.
 * Analyze jobs read those messages (and tags when present) and store a summary.
 * Jobs run on the server worker so the phone does not need to stay focused.
 */

const GOS_DISCORD_AI_MAX_TAGS = 32;
const GOS_DISCORD_AI_MAX_LIMIT = 1000;
const GOS_DISCORD_AI_ANALYZE_MAX_LIMIT = 250;
const GOS_DISCORD_AI_ANALYZE_CHUNK = 40;
const GOS_DISCORD_AI_MAX_PROMPT = 4000;

require_once __DIR__ . '/discord_ai_llm.php';

function gos_discord_ai_tables_ready(): bool
{
    return gos_table_exists('discord_ai_jobs') && gos_table_exists('discord_ai_results');
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}|null
 */
function gos_discord_try_ai_get(string $action, array $q): ?array
{
    return match ($action) {
        'ai_jobs' => gos_discord_ai_jobs($q),
        'ai_job' => gos_discord_ai_job($q),
        'ai_activity' => gos_discord_ai_activity($q),
        'ai_settings' => gos_discord_ai_settings_get($q),
        'user_tags' => gos_discord_local_user_tags($q),
        default => null,
    };
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}|null
 */
function gos_discord_try_ai_write(string $action, array $body): ?array
{
    return match ($action) {
        'ai_analyze_start' => gos_discord_ai_start($body),
        'ai_analyze_tick' => gos_discord_ai_tick($body),
        'ai_analyze_cancel' => gos_discord_ai_cancel($body),
        'ai_settings_save' => gos_discord_ai_settings_save($body),
        default => null,
    };
}

/**
 * @return list<string>
 */
function gos_discord_ai_normalize_tags(array $tags): array
{
    $out = [];
    $seen = [];
    foreach ($tags as $raw) {
        $t = strtolower(trim((string) $raw));
        $t = preg_replace('/\s+/u', ' ', $t) ?? $t;
        $t = trim($t, " \t\n\r\0\x0B\"'`.,;:!?");
        if ($t === '' || strlen($t) > 48) {
            continue;
        }
        if (function_exists('gos_discord_is_bogus_tag') && gos_discord_is_bogus_tag($t)) {
            continue;
        }
        if (isset($seen[$t])) {
            continue;
        }
        $seen[$t] = true;
        $out[] = $t;
        if (count($out) >= GOS_DISCORD_AI_MAX_TAGS) {
            break;
        }
    }
    return $out;
}

/**
 * @return list<string>
 */
function gos_discord_ai_parse_model_tags(string $raw): array
{
    $s = trim($raw);
    if ($s === '') {
        return [];
    }
    if (preg_match('/```(?:json)?\s*([\s\S]*?)```/i', $s, $m)) {
        $s = trim($m[1]);
    }
    $start = strpos($s, '{');
    $end = strrpos($s, '}');
    if ($start !== false && $end !== false && $end > $start) {
        $s = substr($s, $start, $end - $start + 1);
    }
    $decoded = json_decode($s, true);
    $tags = [];
    if (is_array($decoded)) {
        if (isset($decoded['tags']) && is_array($decoded['tags'])) {
            $tags = $decoded['tags'];
        } elseif (array_is_list($decoded)) {
            $tags = $decoded;
        }
    }
    if ($tags === [] && str_contains($raw, ',')) {
        $tags = explode(',', $raw);
    }
    return gos_discord_ai_normalize_tags(is_array($tags) ? $tags : []);
}

/**
 * Discord mention tokens in a message: user <@id>/ <@!id>, role <@&id>, channel <#id>.
 *
 * @return list<array{kind:string,id:string,token:string,canonical:string}>
 */
function gos_discord_ai_parse_mentions(string $content): array
{
    $out = [];
    $seen = [];
    if ($content === '') {
        return [];
    }
    if (preg_match_all('/<(?:@!?|@&|#)(\d{5,32})>/', $content, $m, PREG_SET_ORDER) < 1) {
        return [];
    }
    foreach ($m as $hit) {
        $token = (string) ($hit[0] ?? '');
        $id = (string) ($hit[1] ?? '');
        if ($token === '' || $id === '') {
            continue;
        }
        $kind = str_starts_with($token, '<#') ? 'channel' : (str_starts_with($token, '<@&') ? 'role' : 'user');
        $key = $kind . ':' . $id;
        if (isset($seen[$key])) {
            continue;
        }
        $seen[$key] = true;
        $canonical = $kind === 'channel' ? '<#' . $id . '>' : ($kind === 'role' ? '<@&' . $id . '>' : '<@' . $id . '>');
        $out[] = [
            'kind' => $kind,
            'id' => $id,
            'token' => $token,
            'canonical' => $canonical,
        ];
    }
    return $out;
}

/**
 * Walk JSON for Discord roleId + roleName pairs.
 *
 * @return array<string, string> roleId => roleName
 */
function gos_discord_ai_extract_role_names(mixed $raw): array
{
    $out = [];
    $walk = static function ($node) use (&$walk, &$out): void {
        if (!is_array($node)) {
            return;
        }
        $id = '';
        if (array_key_exists('roleId', $node)) {
            $id = function_exists('gos_discord_snowflake')
                ? gos_discord_snowflake($node['roleId'])
                : trim((string) $node['roleId']);
            if ($id !== '' && !preg_match('/^[0-9]{5,32}$/', $id)) {
                $id = '';
            }
        }
        $name = trim((string) ($node['roleName'] ?? ''));
        if ($id !== '' && $name !== '') {
            $out[$id] = $name;
        }
        foreach ($node as $v) {
            if (is_array($v)) {
                $walk($v);
            }
        }
    };
    if (is_string($raw)) {
        $s = trim($raw);
        if ($s === '') {
            return [];
        }
        $decoded = json_decode($s, true);
        if (is_array($decoded)) {
            $walk($decoded);
        }
    } elseif (is_array($raw)) {
        $walk($raw);
    }
    return $out;
}

/**
 * @param array<string, mixed> $m
 */
function gos_discord_ai_sf(mixed $raw): string
{
    if (function_exists('gos_discord_snowflake')) {
        return gos_discord_snowflake($raw);
    }
    $s = trim((string) $raw);
    return preg_match('/^[0-9]{5,32}$/', $s) === 1 ? $s : '';
}

/**
 * @param list<mixed>|array<string, mixed> $roles
 */
function gos_discord_ai_format_member_roles(array $roles): string
{
    $names = [];
    $seen = [];
    foreach ($roles as $r) {
        $n = '';
        if (is_string($r) || is_int($r)) {
            $n = trim((string) $r);
        } elseif (is_array($r)) {
            $n = trim((string) ($r['name'] ?? $r['roleName'] ?? ''));
        }
        $n = trim($n, " \t\n\r\0\x0B@");
        $key = strtolower($n);
        if ($n === '' || $key === 'everyone' || isset($seen[$key])) {
            continue;
        }
        if (preg_match('/^[0-9]{5,32}$/', $n) === 1) {
            continue;
        }
        $seen[$key] = true;
        $names[] = $n;
        if (count($names) >= 6) {
            break;
        }
    }
    return implode(', ', $names);
}

/**
 * @param array<string, string> $roles roleId => name
 * @return list<string>
 */
function gos_discord_ai_clean_member_role_names(array $roles, string $guildId = ''): array
{
    $out = [];
    $seen = [];
    $gid = gos_discord_ai_sf($guildId);
    foreach ($roles as $id => $name) {
        $rid = gos_discord_ai_sf($id);
        if ($gid !== '' && $rid === $gid) {
            continue;
        }
        $n = trim((string) $name, " \t\n\r\0\x0B@");
        $key = strtolower($n);
        if ($n === '' || $key === 'everyone' || isset($seen[$key])) {
            continue;
        }
        if (preg_match('/^[0-9]{5,32}$/', $n) === 1) {
            continue;
        }
        $seen[$key] = true;
        $out[] = $n;
        if (count($out) >= 6) {
            break;
        }
    }
    return $out;
}

/**
 * @param array<string, mixed> $m
 */
function gos_discord_ai_mention_label(array $m): string
{
    $kind = (string) ($m['kind'] ?? '');
    $name = trim((string) ($m['name'] ?? ''));
    $display = trim((string) ($m['displayName'] ?? ''));
    $username = trim((string) ($m['username'] ?? ''));
    $roleBit = '';
    if ($kind === 'user') {
        $rawRoles = $m['roles'] ?? [];
        $roleBit = gos_discord_ai_format_member_roles(is_array($rawRoles) ? $rawRoles : []);
        $who = $display !== '' ? $display : $name;
        if ($who === '') {
            $who = $username;
        }
        if ($who === '') {
            return $roleBit;
        }
        $base = ($username !== '' && $username !== $who)
            ? '@' . $who . ' / ' . $username
            : '@' . $who;
        return $roleBit === '' ? $base : $base . ' · ' . $roleBit;
    }
    if ($kind === 'role') {
        $n = $name !== '' ? $name : $display;
        return $n === '' ? '' : '@' . ltrim($n, '@') . ' role';
    }
    if ($kind === 'channel') {
        $n = $name !== '' ? $name : $display;
        return $n === '' ? '' : '#' . ltrim($n, '#');
    }
    return '';
}

/**
 * @param list<array<string, mixed>> $mentions
 * @param array<string, array{username?:string,displayName?:string}> $users
 * @param array<string, string> $roles
 * @param array<string, string> $channels
 * @param array<string, list<string>> $memberRoles discordId => role names in this guild
 * @return list<array<string, mixed>>
 */
function gos_discord_ai_fill_mentions(array $mentions, array $users, array $roles, array $channels, array $memberRoles = []): array
{
    $out = [];
    foreach ($mentions as $m) {
        if (!is_array($m)) {
            continue;
        }
        $id = (string) ($m['id'] ?? '');
        $kind = (string) ($m['kind'] ?? '');
        if ($kind === 'user' && isset($users[$id]) && is_array($users[$id])) {
            $m['username'] = (string) ($users[$id]['username'] ?? '');
            $m['displayName'] = (string) ($users[$id]['displayName'] ?? '');
            $who = trim((string) $m['displayName']);
            $m['name'] = $who !== '' ? $who : (string) $m['username'];
        } elseif ($kind === 'role' && isset($roles[$id]) && is_string($roles[$id])) {
            $m['name'] = $roles[$id];
        } elseif ($kind === 'channel' && isset($channels[$id]) && is_string($channels[$id])) {
            $m['name'] = $channels[$id];
        }
        if ($kind === 'user' && $id !== '' && isset($memberRoles[$id]) && is_array($memberRoles[$id])) {
            $named = [];
            foreach ($memberRoles[$id] as $rn) {
                if (is_string($rn) || is_int($rn)) {
                    $n = trim((string) $rn);
                    if ($n !== '') {
                        $named[$n] = $n;
                    }
                } elseif (is_array($rn)) {
                    $n = trim((string) ($rn['name'] ?? $rn['roleName'] ?? ''));
                    $rid = gos_discord_ai_sf($rn['id'] ?? $rn['roleId'] ?? $n);
                    if ($n !== '') {
                        $named[$rid !== '' ? $rid : $n] = $n;
                    }
                }
            }
            $m['roles'] = gos_discord_ai_clean_member_role_names($named);
        }
        $m['label'] = gos_discord_ai_mention_label($m);
        $out[] = $m;
    }
    return $out;
}

/**
 * Keep the raw <@id>/ <@&id>/ <#id> token and append the resolved name.
 *
 * @param list<array<string, mixed>> $mentions
 */
function gos_discord_ai_annotate_content(string $content, array $mentions): string
{
    if ($content === '' || $mentions === []) {
        return $content;
    }
    $repl = [];
    foreach ($mentions as $m) {
        if (!is_array($m)) {
            continue;
        }
        $label = trim((string) ($m['label'] ?? ''));
        if ($label === '') {
            continue;
        }
        $suffix = ' (' . $label . ')';
        foreach (['token', 'canonical'] as $k) {
            $token = (string) ($m[$k] ?? '');
            if ($token === '' || isset($repl[$token])) {
                continue;
            }
            $repl[$token] = $token . $suffix;
        }
    }
    return $repl === [] ? $content : strtr($content, $repl);
}

function gos_discord_ai_format_when(string $raw): string
{
    $s = trim($raw);
    if ($s === '') {
        return '';
    }
    $s = str_replace('T', ' ', $s);
    $s = preg_replace('/(?:\.\d+)?Z$/', '', $s) ?? $s;
    $s = trim($s);
    $ts = strtotime($s . ' UTC');
    if ($ts === false) {
        $ts = strtotime($s);
    }
    if ($ts === false) {
        return $s;
    }
    return gmdate('Y-m-d H:i:s', $ts) . ' UTC (' . gmdate('l', $ts) . ')';
}

/**
 * @param list<string> $ids
 * @return array<string, array{username:string,displayName:string}>
 */
function gos_discord_ai_lookup_users(PDO $pdo, array $ids): array
{
    $clean = [];
    foreach ($ids as $id) {
        $sf = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($id) : trim((string) $id);
        if ($sf !== '') {
            $clean[$sf] = true;
        }
    }
    $list = array_keys($clean);
    if ($list === []) {
        return [];
    }
    $place = implode(',', array_fill(0, count($list), '?'));
    $st = $pdo->prepare('SELECT discordId, username, displayName FROM User WHERE discordId IN (' . $place . ')');
    $st->execute($list);
    $out = [];
    foreach ($st->fetchAll() ?: [] as $row) {
        $id = (string) ($row['discordId'] ?? '');
        if ($id === '') {
            continue;
        }
        $out[$id] = [
            'username' => (string) ($row['username'] ?? ''),
            'displayName' => (string) ($row['displayName'] ?? ''),
        ];
    }
    $missing = array_values(array_diff($list, array_keys($out)));
    if ($missing !== []) {
        try {
            $bots = $pdo->query('SELECT name, token FROM discord_bots');
            foreach ($bots ? ($bots->fetchAll() ?: []) : [] as $row) {
                $name = trim((string) ($row['name'] ?? ''));
                $token = (string) ($row['token'] ?? '');
                $prefix = explode('.', $token, 2)[0] ?? '';
                if ($name === '' || $prefix === '') {
                    continue;
                }
                $pad = strlen($prefix) % 4;
                if ($pad > 0) {
                    $prefix .= str_repeat('=', 4 - $pad);
                }
                $decoded = base64_decode(strtr($prefix, '-_', '+/'), true);
                $did = is_string($decoded) && preg_match('/^[0-9]{5,32}$/', $decoded) ? $decoded : '';
                if ($did !== '' && isset($clean[$did]) && !isset($out[$did])) {
                    $out[$did] = ['username' => $name, 'displayName' => $name];
                }
            }
        } catch (Throwable) {
            // bots table is optional
        }
    }
    $missing = array_values(array_diff($list, array_keys($out)));
    if ($missing !== []) {
        try {
            $placeMissing = implode(',', array_fill(0, count($missing), '?'));
            $st = $pdo->prepare(
                'SELECT discordId, username FROM GuildMemberEvent WHERE discordId IN (' . $placeMissing . ') ORDER BY id DESC'
            );
            $st->execute($missing);
            foreach ($st->fetchAll() ?: [] as $row) {
                $id = (string) ($row['discordId'] ?? '');
                $name = trim((string) ($row['username'] ?? ''));
                if ($id !== '' && $name !== '' && !isset($out[$id])) {
                    $out[$id] = ['username' => $name, 'displayName' => $name];
                }
            }
        } catch (Throwable) {
            // membership events are optional
        }
    }
    return $out;
}

/**
 * @param list<string> $ids
 * @return array<string, string>
 */
function gos_discord_ai_lookup_channels(PDO $pdo, array $ids): array
{
    $clean = [];
    foreach ($ids as $id) {
        $sf = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($id) : trim((string) $id);
        if ($sf !== '') {
            $clean[$sf] = true;
        }
    }
    $list = array_keys($clean);
    if ($list === []) {
        return [];
    }
    $place = implode(',', array_fill(0, count($list), '?'));
    $st = $pdo->prepare('SELECT channelId, channelName FROM BotChannel WHERE channelId IN (' . $place . ')');
    $st->execute($list);
    $out = [];
    foreach ($st->fetchAll() ?: [] as $row) {
        $id = (string) ($row['channelId'] ?? '');
        $name = trim((string) ($row['channelName'] ?? ''));
        if ($id !== '' && $name !== '') {
            $out[$id] = $name;
        }
    }
    return $out;
}

/**
 * Role names from GuildRolePicker, then GuildAuditEvent before/after JSON for leftovers.
 *
 * @param list<string> $ids
 * @return array<string, string>
 */
function gos_discord_ai_lookup_roles(PDO $pdo, array $ids): array
{
    $want = [];
    foreach ($ids as $id) {
        $sf = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($id) : trim((string) $id);
        if ($sf !== '') {
            $want[$sf] = true;
        }
    }
    if ($want === []) {
        return [];
    }
    static $pickerCache = null;
    if (!is_array($pickerCache)) {
        $pickerCache = [];
        try {
            $st = $pdo->query('SELECT rolesJson FROM GuildRolePicker');
            foreach ($st ? ($st->fetchAll() ?: []) : [] as $row) {
                foreach (gos_discord_ai_extract_role_names($row['rolesJson'] ?? '') as $id => $name) {
                    if ($name !== '') {
                        $pickerCache[$id] = $name;
                    }
                }
            }
        } catch (Throwable) {
            $pickerCache = [];
        }
    }
    $out = [];
    foreach (array_keys($want) as $id) {
        if (isset($pickerCache[$id])) {
            $out[$id] = $pickerCache[$id];
        }
    }
    $missing = array_values(array_diff(array_keys($want), array_keys($out)));
    foreach ($missing as $id) {
        $like = '%"roleId":"' . $id . '"%';
        try {
            $st = $pdo->prepare(
                'SELECT `after`, `before` FROM GuildAuditEvent WHERE `after` LIKE ? OR `before` LIKE ? LIMIT 12'
            );
            $st->execute([$like, $like]);
            foreach ($st->fetchAll() ?: [] as $row) {
                $found = gos_discord_ai_extract_role_names($row['after'] ?? '');
                $found += gos_discord_ai_extract_role_names($row['before'] ?? '');
                if (isset($found[$id]) && trim($found[$id]) !== '') {
                    $out[$id] = $found[$id];
                    $pickerCache[$id] = $found[$id];
                    break;
                }
            }
        } catch (Throwable) {
            // audit JSON is optional
        }
    }
    return $out;
}

/**
 * @param array<string, mixed> $row
 * @return array<string, string> roleId => name
 */
function gos_discord_ai_role_event_bits(array $row): array
{
    $action = strtolower(trim((string) ($row['action'] ?? '')));
    $raw = $action === 'role_remove'
        ? ($row['before'] ?? $row['after'] ?? '')
        : ($row['after'] ?? $row['before'] ?? '');
    return gos_discord_ai_extract_role_names($raw);
}

/**
 * Reconstruct current guild roles per member from assign/remove events (oldest first).
 *
 * @param list<array<string, mixed>> $events
 * @return array<string, array<string, list<string>>> [guildId][discordId] => role names
 */
function gos_discord_ai_member_roles_from_events(array $events): array
{
    $map = [];
    foreach ($events as $row) {
        if (!is_array($row)) {
            continue;
        }
        $gid = gos_discord_ai_sf($row['guildId'] ?? '');
        $did = gos_discord_ai_sf($row['targetId'] ?? $row['discordId'] ?? '');
        if ($gid === '' || $did === '') {
            continue;
        }
        $action = strtolower(trim((string) ($row['action'] ?? '')));
        $bits = gos_discord_ai_role_event_bits($row);
        if ($bits === []) {
            continue;
        }
        if (!isset($map[$gid][$did]) || !is_array($map[$gid][$did])) {
            $map[$gid][$did] = [];
        }
        foreach ($bits as $rid => $name) {
            $rid = gos_discord_ai_sf($rid);
            if ($rid === '') {
                continue;
            }
            if ($action === 'role_remove') {
                unset($map[$gid][$did][$rid]);
            } else {
                $n = trim((string) $name);
                $map[$gid][$did][$rid] = $n !== '' ? $n : $rid;
            }
        }
    }
    $out = [];
    foreach ($map as $gid => $users) {
        if (!is_array($users)) {
            continue;
        }
        foreach ($users as $did => $roles) {
            if (!is_array($roles)) {
                continue;
            }
            $names = gos_discord_ai_clean_member_role_names($roles, (string) $gid);
            if ($names !== []) {
                $out[(string) $gid][(string) $did] = $names;
            }
        }
    }
    return $out;
}

/**
 * Member roles from GuildAuditEvent role_assign / role_remove.
 *
 * @param list<string> $userIds
 * @param list<string> $guildIds
 * @return array<string, array<string, list<string>>>
 */
function gos_discord_ai_lookup_member_roles(PDO $pdo, array $userIds, array $guildIds = []): array
{
    $users = [];
    foreach ($userIds as $id) {
        $sf = gos_discord_ai_sf($id);
        if ($sf !== '') {
            $users[$sf] = true;
        }
    }
    $guilds = [];
    foreach ($guildIds as $id) {
        $sf = gos_discord_ai_sf($id);
        if ($sf !== '') {
            $guilds[$sf] = true;
        }
    }
    $userList = array_keys($users);
    if ($userList === []) {
        return [];
    }
    $events = [];
    foreach (array_chunk($userList, 60) as $chunk) {
        $place = implode(',', array_fill(0, count($chunk), '?'));
        $sql = 'SELECT guildId, targetId, action, `after`, `before`
                FROM GuildAuditEvent
                WHERE targetId IN (' . $place . ')
                  AND action IN (\'role_assign\',\'role_remove\')';
        $params = $chunk;
        $gList = array_keys($guilds);
        if ($gList !== []) {
            $sql .= ' AND guildId IN (' . implode(',', array_fill(0, count($gList), '?')) . ')';
            foreach ($gList as $g) {
                $params[] = $g;
            }
        }
        $sql .= ' ORDER BY createdAt ASC';
        try {
            $st = $pdo->prepare($sql);
            $st->execute($params);
            foreach ($st->fetchAll() ?: [] as $row) {
                if (is_array($row)) {
                    $events[] = $row;
                }
            }
        } catch (Throwable) {
            // audit table is optional
        }
    }
    return gos_discord_ai_member_roles_from_events($events);
}

/**
 * Resolve mention tokens against Avalynn and attach annotated copy + postedAt.
 *
 * @param list<array<string, mixed>> $msgs
 * @return list<array<string, mixed>>
 */
function gos_discord_ai_attach_mentions(array $msgs, ?PDO $avalynn): array
{
    if ($msgs === []) {
        return [];
    }
    $userIds = [];
    $roleIds = [];
    $channelIds = [];
    $guildIds = [];
    $parsed = [];
    foreach ($msgs as $i => $msg) {
        if (!is_array($msg)) {
            $parsed[$i] = [];
            continue;
        }
        $authorId = gos_discord_ai_sf($msg['discordId'] ?? '');
        if ($authorId !== '') {
            $userIds[$authorId] = true;
        }
        $gid = gos_discord_ai_sf($msg['guildId'] ?? '');
        if ($gid !== '') {
            $guildIds[$gid] = true;
        }
        $mentions = gos_discord_ai_parse_mentions((string) ($msg['content'] ?? ''));
        $parsed[$i] = $mentions;
        foreach ($mentions as $m) {
            $id = (string) ($m['id'] ?? '');
            $kind = (string) ($m['kind'] ?? '');
            if ($id === '') {
                continue;
            }
            if ($kind === 'user') {
                $userIds[$id] = true;
            } elseif ($kind === 'role') {
                $roleIds[$id] = true;
            } elseif ($kind === 'channel') {
                $channelIds[$id] = true;
            }
        }
    }
    $users = [];
    $roles = [];
    $channels = [];
    $memberRoles = [];
    if ($avalynn !== null) {
        try {
            $users = gos_discord_ai_lookup_users($avalynn, array_keys($userIds));
        } catch (Throwable) {
            $users = [];
        }
        try {
            $channels = gos_discord_ai_lookup_channels($avalynn, array_keys($channelIds));
        } catch (Throwable) {
            $channels = [];
        }
        try {
            $roles = gos_discord_ai_lookup_roles($avalynn, array_keys($roleIds));
        } catch (Throwable) {
            $roles = [];
        }
        try {
            $memberRoles = gos_discord_ai_lookup_member_roles($avalynn, array_keys($userIds), array_keys($guildIds));
        } catch (Throwable) {
            $memberRoles = [];
        }
    }
    foreach ($msgs as $i => $msg) {
        if (!is_array($msg)) {
            continue;
        }
        $gid = gos_discord_ai_sf($msg['guildId'] ?? '');
        $did = gos_discord_ai_sf($msg['discordId'] ?? '');
        $scoped = ($gid !== '' && isset($memberRoles[$gid]) && is_array($memberRoles[$gid]))
            ? $memberRoles[$gid]
            : [];
        $mentions = gos_discord_ai_fill_mentions($parsed[$i] ?? [], $users, $roles, $channels, $scoped);
        $content = (string) ($msg['content'] ?? '');
        $msg['mentions'] = $mentions;
        $msg['annotatedContent'] = gos_discord_ai_annotate_content($content, $mentions);
        $msg['postedAt'] = gos_discord_ai_format_when((string) ($msg['createdAt'] ?? ''));
        $msg['authorRoles'] = ($did !== '' && isset($scoped[$did]) && is_array($scoped[$did]))
            ? array_values($scoped[$did])
            : [];
        $msgs[$i] = $msg;
    }
    return $msgs;
}

function gos_discord_ai_system_prompt(): string
{
    return <<<'PROMPT'
You are an expert semantic tagger for Discord messages.
Return JSON only: {"tags":["tag one","tag-two",...]} with at most 32 tags.

Each tag is a single word or a 2–3 word phrase, lowercase.

Be fully comprehensive — not just keywords in the sentence. Expand to:
- nearby semantics and closely related meanings
- synonyms, homonyms, idioms, analogies, metaphors
- emotions, tone, speech act (statement, question, joke, rant, off-topic)
- places, people, products, seasons, weather, nature, colors
- the semantics of those semantics (related categories and associations)
- guild name and channel name when they add meaning
- Discord mention tokens and the resolved names: keep both `<@id>` / `<@&id>` / `<#id>` identity and the user, role, or channel display name
- member guild roles shown next to a user name (Author: Name · Role, or `<@id> (@Name · Role)`) — treat those as that person's roles in this guild
- time of day, weekday, recency, and temporal tone when the timestamp adds meaning

Example: "i love the weather in Colorado" may include tags such as
colorado, state, favorite, love, happy, weather, clouds, sunshine, usa,
statement, off-topic, blue sky, sky, mountains, temperature, season,
summer, winter, fall, spring, hot, cold, warm, trees, wind, rain, snow,
desert, life in colorado, denver, pikes peak.

Do not invent tags that contradict the message. Prefer relevant breadth over padding.
PROMPT;
}

/**
 * @param array<string, mixed> $msg
 */
function gos_discord_ai_user_prompt(array $msg): string
{
    $guild = trim((string) ($msg['guildName'] ?? ''));
    $channel = trim((string) ($msg['channelName'] ?? ''));
    $user = trim((string) ($msg['displayName'] ?? ''));
    $username = trim((string) ($msg['username'] ?? ''));
    $content = trim((string) ($msg['annotatedContent'] ?? $msg['content'] ?? ''));
    $when = trim((string) ($msg['postedAt'] ?? ''));
    if ($when === '') {
        $when = gos_discord_ai_format_when((string) ($msg['createdAt'] ?? ''));
    }
    $lines = ['Tag this Discord message.'];
    if ($guild !== '') {
        $lines[] = 'Guild: ' . $guild;
    }
    if ($channel !== '') {
        $lines[] = 'Channel: #' . ltrim($channel, '#');
    }
    $author = $user !== '' ? $user : $username;
    $authorRoles = gos_discord_ai_format_member_roles(is_array($msg['authorRoles'] ?? null) ? $msg['authorRoles'] : []);
    if ($author !== '') {
        $line = 'Author: ' . $author;
        if ($username !== '' && $username !== $author) {
            $line .= ' (@' . $username . ')';
        }
        if ($authorRoles !== '') {
            $line .= ' · ' . $authorRoles;
        }
        $lines[] = $line;
    } elseif ($authorRoles !== '') {
        $lines[] = 'Author roles: ' . $authorRoles;
    }
    if ($when !== '') {
        $lines[] = 'Posted at: ' . $when;
    }
    $mentions = $msg['mentions'] ?? [];
    if (is_array($mentions) && $mentions !== []) {
        $lines[] = 'Mentions (raw token and resolved name):';
        foreach ($mentions as $m) {
            if (!is_array($m)) {
                continue;
            }
            $token = (string) ($m['canonical'] ?? $m['token'] ?? '');
            $label = trim((string) ($m['label'] ?? ''));
            $kind = (string) ($m['kind'] ?? 'mention');
            if ($token === '') {
                continue;
            }
            $lines[] = $label !== ''
                ? '- ' . $kind . ' ' . $token . ' = ' . $label
                : '- ' . $kind . ' ' . $token . ' (unresolved)';
        }
    }
    $lines[] = 'Message:';
    $lines[] = $content;
    return implode("\n", $lines);
}

/**
 * @param array<string, mixed>|null $job
 * @return list<string>
 */
function gos_discord_ai_generate_tags(array $msg, string $prompt = '', ?array $job = null): array
{
    $text = gos_discord_ai_complete(
        gos_discord_ai_with_operator_prompt(gos_discord_ai_system_prompt(), $prompt, 'tag'),
        gos_discord_ai_user_prompt($msg),
        ['temperature' => 0.55, 'max_tokens' => 700, 'timeout' => 90, 'json' => true, 'job' => $job]
    );
    return gos_discord_ai_parse_model_tags($text);
}

function gos_discord_ai_kind_of(array $q): string
{
    $k = strtolower(trim((string) ($q['kind'] ?? $q['jobKind'] ?? 'tag')));
    return $k === 'analyze' ? 'analyze' : 'tag';
}

function gos_discord_ai_has_column(string $col): bool
{
    static $cache = [];
    if (array_key_exists($col, $cache)) {
        return $cache[$col];
    }
    $name = preg_replace('/[^a-zA-Z0-9_]/', '', $col) ?? '';
    if ($name === '') {
        $cache[$col] = false;
        return false;
    }
    try {
        $st = gos_pdo()->query("SHOW COLUMNS FROM discord_ai_jobs LIKE " . gos_pdo()->quote($name));
        $cache[$col] = $st !== false && (bool) $st->fetch();
    } catch (Throwable) {
        $cache[$col] = false;
    }
    return $cache[$col];
}

function gos_discord_ai_has_kind_column(): bool
{
    return gos_discord_ai_has_column('kind');
}

function gos_discord_ai_has_prompt_column(): bool
{
    return gos_discord_ai_has_column('prompt');
}

function gos_discord_ai_prompt_ok(string $raw): string
{
    $s = trim($raw);
    if ($s === '') {
        return '';
    }
    if (function_exists('mb_substr')) {
        $s = mb_substr($s, 0, GOS_DISCORD_AI_MAX_PROMPT);
    } else {
        $s = substr($s, 0, GOS_DISCORD_AI_MAX_PROMPT);
    }
    return trim($s);
}

function gos_discord_ai_with_operator_prompt(string $base, string $prompt, string $kind = 'analyze'): string
{
    $p = gos_discord_ai_prompt_ok($prompt);
    if ($p === '') {
        return $base;
    }
    $intro = $kind === 'tag'
        ? 'Operator instructions (use these to focus the tags; still return JSON tags only; do not contradict the message):'
        : 'Operator instructions (follow these to make the analysis more precise; do not invent evidence that is not in the messages):';
    return $base . "\n\n" . $intro . "\n" . $p;
}

function gos_discord_ai_analyze_system_prompt(): string
{
    return <<<'PROMPT'
You are an expert Discord conversation analyst.

Write a detailed summary of what took place. Use the messages, timestamps, mention tokens, resolved names, and any semantic tags as evidence.

Cover:
- narrative of what happened, in chronological order, with when (timestamps, gaps, time of day, weekday)
- who was involved and how they related — Discord mentions appear as both the raw token (`<@id>`, `<@&id>`, `<#id>`) and the resolved user, role, or channel name; treat those as the same person/role/channel. A member's guild roles may appear next to their name (`@Name · Role`); use them as identity, not as a ping.
- topics, themes, decisions, asks, jokes, conflicts
- mood, emotion, and tone shifts
- notable quotes (short)
- what the tags add when the text is thin or slangy

Write several short paragraphs (not bullets unless listing distinct threads). Be concrete. Do not invent events that are not in the messages. If the window is a slice of a larger span, say so. Stay time-aware.
PROMPT;
}

function gos_discord_ai_analyze_merge_prompt(): string
{
    return <<<'PROMPT'
You merge partial Discord summaries into one detailed narrative.

Combine the sections in order. Remove repetition. Keep concrete names, mention tokens with resolved names, timestamps, topics, quotes, and mood. Write several short paragraphs. Do not invent events. Stay time-aware.
PROMPT;
}

/**
 * @param list<array<string, mixed>> $msgs
 */
function gos_discord_ai_format_transcript(array $msgs, string $heading = ''): string
{
    $lines = [];
    if ($heading !== '') {
        $lines[] = $heading;
        $lines[] = '';
    }
    $n = 0;
    foreach ($msgs as $msg) {
        $n++;
        $when = trim((string) ($msg['postedAt'] ?? ''));
        if ($when === '') {
            $when = gos_discord_ai_format_when((string) ($msg['createdAt'] ?? ''));
        }
        if ($when === '') {
            $when = trim((string) ($msg['createdAt'] ?? ''));
        }
        $who = trim((string) ($msg['displayName'] ?? ''));
        if ($who === '') {
            $who = trim((string) ($msg['username'] ?? 'unknown'));
        }
        $roleBit = gos_discord_ai_format_member_roles(is_array($msg['authorRoles'] ?? null) ? $msg['authorRoles'] : []);
        if ($roleBit !== '') {
            $who .= ' · ' . $roleBit;
        }
        $ch = trim((string) ($msg['channelName'] ?? ''));
        $guild = trim((string) ($msg['guildName'] ?? ''));
        $place = trim($guild . ($ch !== '' ? ' / #' . $ch : ''));
        $content = trim((string) ($msg['annotatedContent'] ?? $msg['content'] ?? ''));
        if (function_exists('mb_substr')) {
            $content = mb_substr($content, 0, 520);
        } else {
            $content = substr($content, 0, 520);
        }
        $tags = $msg['tagList'] ?? [];
        if (!is_array($tags)) {
            $tags = [];
        }
        $head = $n . '. [' . $when . '] @' . $who;
        if ($place !== '') {
            $head .= ' · ' . $place;
        }
        $lines[] = $head;
        $lines[] = $content !== '' ? $content : '(empty)';
        $mentions = $msg['mentions'] ?? [];
        if (is_array($mentions) && $mentions !== []) {
            $bits = [];
            foreach ($mentions as $m) {
                if (!is_array($m)) {
                    continue;
                }
                $token = (string) ($m['canonical'] ?? $m['token'] ?? '');
                $label = trim((string) ($m['label'] ?? ''));
                if ($token === '') {
                    continue;
                }
                $bits[] = $label !== '' ? $token . ' = ' . $label : $token;
            }
            if ($bits !== []) {
                $lines[] = 'mentions: ' . implode('; ', $bits);
            }
        }
        if ($tags !== []) {
            $lines[] = 'tags: ' . implode(', ', array_slice($tags, 0, 32));
        }
        $lines[] = '';
    }
    return implode("\n", $lines);
}

/**
 * @param list<array<string, mixed>> $msgs chrono (oldest first)
 */
function gos_discord_ai_window_heading(array $msgs, bool $partial): string
{
    $n = count($msgs);
    $times = [];
    foreach ($msgs as $msg) {
        $w = trim((string) ($msg['postedAt'] ?? ''));
        if ($w === '') {
            $w = gos_discord_ai_format_when((string) ($msg['createdAt'] ?? ''));
        }
        if ($w !== '') {
            $times[] = $w;
        }
    }
    $span = '';
    if ($times !== []) {
        $first = $times[0];
        $last = $times[count($times) - 1];
        $span = $first === $last ? ' at ' . $first : ' from ' . $first . ' through ' . $last;
    }
    $base = $partial
        ? 'Partial window of a larger Discord span. ' . $n . ' messages' . $span . '.'
        : 'Full window. ' . $n . ' messages' . $span . '.';
    return $base . ' Summarize what took place. Stay time-aware.';
}

function gos_discord_ai_spawn_worker(): void
{
    $script = gos_root() . '/scripts/discord-ai-worker.php';
    if (!is_file($script)) {
        return;
    }
    $php = PHP_BINARY !== '' ? PHP_BINARY : 'php';
    $logDir = gos_root() . '/storage/logs';
    if (!is_dir($logDir)) {
        @mkdir($logDir, 0775, true);
    }
    $log = $logDir . '/discord-ai-worker.log';
    $cmd = 'nohup ' . escapeshellarg($php) . ' ' . escapeshellarg($script)
        . ' --loop >> ' . escapeshellarg($log) . ' 2>&1 &';
    if (function_exists('exec')) {
        @exec($cmd);
    }
}

/**
 * Process one queued/running job step. Returns true when a job was claimed.
 */
function gos_discord_ai_tick_ok(mixed $out): bool
{
    return is_array($out) && !empty($out['ok']);
}

function gos_discord_ai_is_live_job(array $job): bool
{
    return strtolower(trim((string) ($job['scope'] ?? ''))) === 'live';
}

/**
 * Queued/running manual jobs, oldest first. Live auto-tag is a separate lane (id 0).
 *
 * @return list<int>
 */
function gos_discord_ai_busy_job_ids(): array
{
    if (!gos_discord_ai_tables_ready()) {
        return [];
    }
    try {
        $st = gos_pdo()->query(
            "SELECT id FROM discord_ai_jobs
             WHERE status IN ('queued','running') AND scope <> 'live'
             ORDER BY id ASC"
        );
        $rows = $st?->fetchAll(PDO::FETCH_COLUMN) ?: [];
    } catch (Throwable) {
        return [];
    }
    $out = [];
    foreach ($rows as $id) {
        $id = (int) $id;
        if ($id > 0) {
            $out[] = $id;
        }
    }
    return $out;
}

function gos_discord_ai_manual_busy_id(): int
{
    $ids = gos_discord_ai_busy_job_ids();
    return $ids[0] ?? 0;
}

/**
 * @param list<mixed> $manualIds
 * @return list<int>
 */
function gos_discord_ai_pump_lanes(array $manualIds, bool $includeAuto): array
{
    $lanes = [];
    $seen = [];
    foreach ($manualIds as $raw) {
        $id = (int) $raw;
        if ($id <= 0 || isset($seen[$id])) {
            continue;
        }
        $seen[$id] = true;
        $lanes[] = $id;
    }
    if ($includeAuto) {
        $lanes[] = 0;
    }
    return $lanes;
}

/**
 * Rotate so the lane after $lastId is tried first (round-robin).
 *
 * @param list<int> $ids
 * @return list<int>
 */
function gos_discord_ai_rotate_ids(array $ids, int $lastId): array
{
    $ids = array_values($ids);
    if ($ids === []) {
        return [];
    }
    $start = 0;
    foreach ($ids as $i => $id) {
        if ((int) $id === $lastId) {
            $start = $i + 1;
            break;
        }
    }
    if ($start >= count($ids)) {
        $start = 0;
    }
    if ($start === 0) {
        return $ids;
    }
    return array_merge(array_slice($ids, $start), array_slice($ids, 0, $start));
}

function gos_discord_ai_tick_did_work(mixed $out): bool
{
    if (!gos_discord_ai_tick_ok($out) || !is_array($out)) {
        return false;
    }
    $data = is_array($out['data'] ?? null) ? $out['data'] : [];
    if (!empty($data['result'])) {
        return true;
    }
    return empty($data['done']);
}

/**
 * @return list<string>
 */
function gos_discord_ai_auto_guild_ids(PDO $avalynn): array
{
    try {
        $rows = $avalynn->query(
            "SELECT DISTINCT guildId FROM GuildSettings
             WHERE semanticTagging = 1 AND guildId IS NOT NULL AND guildId <> ''"
        )->fetchAll(PDO::FETCH_COLUMN) ?: [];
    } catch (Throwable) {
        return [];
    }
    $out = [];
    foreach ($rows as $gid) {
        $sf = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($gid) : trim((string) $gid);
        if ($sf !== '') {
            $out[$sf] = $sf;
        }
    }
    return array_values($out);
}

function gos_discord_ai_ensure_live_job(PDO $avalynn): int
{
    $gos = gos_pdo();
    $maxId = 0;
    try {
        $maxId = (int) ($avalynn->query('SELECT MAX(id) FROM Message')->fetchColumn() ?: 0);
    } catch (Throwable) {
        $maxId = 0;
    }
    try {
        $st = $gos->query(
            "SELECT id, last_message_id FROM discord_ai_jobs WHERE scope = 'live' ORDER BY id ASC LIMIT 1"
        );
        $row = $st?->fetch();
    } catch (Throwable) {
        return 0;
    }
    if (is_array($row)) {
        $id = (int) ($row['id'] ?? 0);
        if ($id <= 0) {
            return 0;
        }
        $cursor = (int) ($row['last_message_id'] ?? 0);
        try {
            if ($cursor <= 0 && $maxId > 0) {
                $gos->prepare(
                    "UPDATE discord_ai_jobs SET last_message_id = ?, status = 'running', last_error = '' WHERE id = ?"
                )->execute([$maxId, $id]);
            } else {
                $gos->prepare(
                    "UPDATE discord_ai_jobs SET status = 'running', last_error = ''
                     WHERE id = ? AND status <> 'running'"
                )->execute([$id]);
            }
        } catch (Throwable) {
            // job row still usable
        }
        return $id;
    }
    try {
        $cols = ['bot_id', 'scope', 'guild_id', 'channel_id', 'timeframe', 'message_limit', 'skip_tagged', 'status', 'total', 'last_message_id', 'label'];
        $params = [0, 'live', '', '', 'live', 0, 1, 'running', 0, $maxId, 'Auto-tag'];
        if (gos_discord_ai_has_kind_column()) {
            $cols[] = 'kind';
            $params[] = 'tag';
        }
        $placeholders = implode(',', array_fill(0, count($cols), '?'));
        $ins = $gos->prepare(
            'INSERT INTO discord_ai_jobs (' . implode(', ', $cols) . ') VALUES (' . $placeholders . ')'
        );
        $ins->execute($params);
        return (int) $gos->lastInsertId();
    } catch (Throwable) {
        return 0;
    }
}

/**
 * @param list<string> $guildIds
 * @return array<string, mixed>|null
 */
function gos_discord_ai_pick_auto_message(PDO $avalynn, array $guildIds, int $afterId): ?array
{
    if ($guildIds === []) {
        return null;
    }
    $in = implode(',', array_fill(0, count($guildIds), '?'));
    $sql = 'SELECT m.id, m.messageId, m.guildId, m.channelId, m.content, m.tags, m.createdAt, m.userId, m.botId,
                   u.discordId, u.username, u.displayName, u.avatar
            FROM Message m
            LEFT JOIN User u ON u.id = m.userId
            WHERE m.isSpam = 0
              AND TRIM(IFNULL(m.content, \'\')) <> \'\'
              AND m.guildId IN (' . $in . ')
              AND m.id > ?
              AND (m.tags IS NULL OR TRIM(m.tags) = \'\' OR TRIM(m.tags) = \'[]\')
            ORDER BY m.id ASC
            LIMIT 1';
    try {
        $st = $avalynn->prepare($sql);
        $st->execute(array_merge(array_values($guildIds), [$afterId]));
        $row = $st->fetch();
        return is_array($row) ? $row : null;
    } catch (Throwable) {
        return null;
    }
}

function gos_discord_ai_auto_tick(): bool
{
    if (!gos_discord_ai_tables_ready()) {
        return false;
    }
    $avalynn = gos_discord_avalynn_pdo();
    if ($avalynn === null) {
        return false;
    }
    $guildIds = gos_discord_ai_auto_guild_ids($avalynn);
    if ($guildIds === []) {
        return false;
    }
    $jobId = gos_discord_ai_ensure_live_job($avalynn);
    if ($jobId <= 0) {
        return false;
    }
    try {
        $jobSt = gos_pdo()->prepare('SELECT * FROM discord_ai_jobs WHERE id = ? LIMIT 1');
        $jobSt->execute([$jobId]);
        $job = $jobSt->fetch();
    } catch (Throwable) {
        return false;
    }
    if (!is_array($job)) {
        return false;
    }
    $cursor = (int) ($job['last_message_id'] ?? 0);
    $msg = gos_discord_ai_pick_auto_message($avalynn, $guildIds, $cursor);
    if (!is_array($msg)) {
        return false;
    }
    $msgId = (int) ($msg['id'] ?? 0);
    if ($msgId <= 0) {
        return false;
    }
    $guildName = '';
    $channelName = '';
    try {
        $gid = (string) ($msg['guildId'] ?? '');
        $cid = (string) ($msg['channelId'] ?? '');
        if ($gid !== '') {
            $gs = $avalynn->prepare('SELECT guildName FROM GuildSettings WHERE guildId = ? ORDER BY botId IS NULL DESC LIMIT 1');
            $gs->execute([$gid]);
            $guildName = (string) ($gs->fetchColumn() ?: '');
        }
        if ($cid !== '') {
            $cs = $avalynn->prepare('SELECT channelName FROM BotChannel WHERE channelId = ? LIMIT 1');
            $cs->execute([$cid]);
            $channelName = (string) ($cs->fetchColumn() ?: '');
        }
    } catch (Throwable) {
        // names are optional
    }
    $did = (string) ($msg['discordId'] ?? '');
    $avatar = function_exists('gos_discord_avatar_url')
        ? gos_discord_avatar_url($did, (string) ($msg['avatar'] ?? ''))
        : '';
    $payload = [
        'content' => (string) ($msg['content'] ?? ''),
        'guildId' => (string) ($msg['guildId'] ?? ''),
        'guildName' => $guildName,
        'channelName' => $channelName,
        'username' => (string) ($msg['username'] ?? ''),
        'displayName' => (string) ($msg['displayName'] ?? ''),
        'discordId' => $did,
        'createdAt' => (string) ($msg['createdAt'] ?? ''),
    ];
    $enriched = gos_discord_ai_attach_mentions([$payload], $avalynn);
    if (isset($enriched[0]) && is_array($enriched[0])) {
        $payload = $enriched[0];
    }
    $resultStatus = 'ok';
    $err = '';
    $tags = [];
    try {
        $tags = gos_discord_ai_generate_tags($payload, '', $job);
        if ($tags === []) {
            $resultStatus = 'skip';
            $err = 'empty_tags';
        } else {
            $csv = implode(',', $tags);
            $upd = $avalynn->prepare('UPDATE Message SET tags = ? WHERE id = ?');
            $upd->execute([$csv, $msgId]);
        }
    } catch (Throwable $e) {
        $resultStatus = 'error';
        $err = substr($e->getMessage(), 0, 180);
        $tags = [];
    }
    $csv = $tags !== [] ? implode(',', $tags) : '';
    try {
        $ins = gos_pdo()->prepare(
            'INSERT INTO discord_ai_results
             (job_id, message_id, discord_message_id, bot_id, guild_id, guild_name, channel_id, channel_name,
              user_id, discord_user_id, username, display_name, avatar, content, tags, status, error)
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)'
        );
        $ins->execute([
            $jobId,
            $msgId,
            (string) ($msg['messageId'] ?? ''),
            (int) ($msg['botId'] ?? 0),
            (string) ($msg['guildId'] ?? ''),
            $guildName,
            (string) ($msg['channelId'] ?? ''),
            $channelName,
            (int) ($msg['userId'] ?? 0),
            $did,
            (string) ($msg['username'] ?? ''),
            (string) ($msg['displayName'] ?? ''),
            $avatar,
            mb_substr((string) ($msg['content'] ?? ''), 0, 2000),
            $csv,
            $resultStatus,
            $err,
        ]);
        $incTagged = $resultStatus === 'ok' ? 1 : 0;
        $incSkip = $resultStatus === 'skip' ? 1 : 0;
        $incFail = $resultStatus === 'error' ? 1 : 0;
        gos_pdo()->prepare(
            "UPDATE discord_ai_jobs
             SET processed = processed + 1,
                 tagged = tagged + ?,
                 skipped = skipped + ?,
                 failed = failed + ?,
                 last_message_id = ?,
                 last_error = ?,
                 total = GREATEST(total, processed),
                 status = 'running'
             WHERE id = ?"
        )->execute([$incTagged, $incSkip, $incFail, $msgId, $err, $jobId]);
        return true;
    } catch (Throwable) {
        try {
            gos_pdo()->prepare(
                "UPDATE discord_ai_jobs SET last_message_id = ?, last_error = ?, status = 'running' WHERE id = ?"
            )->execute([$msgId, $err !== '' ? $err : 'auto_save_failed', $jobId]);
        } catch (Throwable) {
            // ignore
        }
        return false;
    }
}

function gos_discord_ai_pump(): bool
{
    static $lastId = -1;
    if (!gos_discord_ai_tables_ready()) {
        return false;
    }
    $lanes = gos_discord_ai_pump_lanes(gos_discord_ai_busy_job_ids(), true);
    if ($lanes === []) {
        return false;
    }
    foreach (gos_discord_ai_rotate_ids($lanes, $lastId) as $id) {
        $lastId = $id;
        if ($id === 0) {
            try {
                if (gos_discord_ai_auto_tick()) {
                    return true;
                }
            } catch (Throwable $e) {
                error_log('discord_ai_auto: ' . $e->getMessage());
            }
            continue;
        }
        try {
            $out = gos_discord_ai_tick(['id' => $id]);
        } catch (Throwable $e) {
            error_log('discord_ai_pump: ' . $e->getMessage());
            continue;
        }
        if (!gos_discord_ai_tick_ok($out)) {
            error_log('discord_ai_pump: ' . (string) ($out['error'] ?? 'tick_failed'));
            continue;
        }
        if (gos_discord_ai_tick_did_work($out)) {
            return true;
        }
    }
    return false;
}

/**
 * @param array<string, mixed> $job
 * @return array<string, mixed>
 */
function gos_discord_ai_public_job(array $job): array
{
    $kind = strtolower(trim((string) ($job['kind'] ?? 'tag')));
    if ($kind !== 'analyze') {
        $kind = 'tag';
    }
    return [
        'id' => (int) ($job['id'] ?? 0),
        'botId' => (int) ($job['bot_id'] ?? 0),
        'kind' => $kind,
        'scope' => (string) ($job['scope'] ?? 'all'),
        'guildId' => (string) ($job['guild_id'] ?? ''),
        'channelId' => (string) ($job['channel_id'] ?? ''),
        'userId' => (int) ($job['user_id'] ?? 0),
        'discordUserId' => (string) ($job['discord_user_id'] ?? ''),
        'timeframe' => (string) ($job['timeframe'] ?? ''),
        'fromDate' => (string) ($job['from_date'] ?? ''),
        'toDate' => (string) ($job['to_date'] ?? ''),
        'messageLimit' => (int) ($job['message_limit'] ?? 0),
        'skipTagged' => (int) ($job['skip_tagged'] ?? 1) === 1,
        'status' => (string) ($job['status'] ?? ''),
        'total' => (int) ($job['total'] ?? 0),
        'processed' => (int) ($job['processed'] ?? 0),
        'tagged' => (int) ($job['tagged'] ?? 0),
        'skipped' => (int) ($job['skipped'] ?? 0),
        'failed' => (int) ($job['failed'] ?? 0),
        'lastError' => (string) ($job['last_error'] ?? ''),
        'label' => (string) ($job['label'] ?? ''),
        'prompt' => (string) ($job['prompt'] ?? ''),
        'summary' => (string) ($job['summary'] ?? ''),
        'provider' => (string) ($job['provider'] ?? ''),
        'model' => (string) ($job['model'] ?? ''),
        'reasoningEffort' => (string) ($job['reasoning_effort'] ?? ''),
        'createdAt' => (string) ($job['created_at'] ?? ''),
        'updatedAt' => (string) ($job['updated_at'] ?? ''),
    ];
}

/** True when a discord_ai_results row is a duplicated analyze-job summary, not a tagged message. */
function gos_discord_ai_result_is_job_summary(array $row): bool
{
    $messageId = (int) ($row['message_id'] ?? $row['messageId'] ?? 0);
    $tagsRaw = $row['tags'] ?? '';
    if (is_array($tagsRaw)) {
        return $messageId === 0 && $tagsRaw === [];
    }
    return $messageId === 0 && trim((string) $tagsRaw) === '';
}

/**
 * @param array<string, mixed> $row
 * @return array<string, mixed>
 */
/**
 * Attach reconstructed guild roles onto public AI result rows (user.roles).
 *
 * @param list<array<string, mixed>> $items
 * @return list<array<string, mixed>>
 */
function gos_discord_ai_attach_result_roles(array $items, ?PDO $avalynn = null): array
{
    if ($items === []) {
        return $items;
    }
    if ($avalynn === null) {
        $avalynn = function_exists('gos_discord_avalynn_pdo') ? gos_discord_avalynn_pdo() : null;
    }
    if ($avalynn === null) {
        return $items;
    }
    $userIds = [];
    $guildIds = [];
    foreach ($items as $it) {
        if (!is_array($it)) {
            continue;
        }
        $did = gos_discord_ai_sf($it['user']['discordId'] ?? '');
        $gid = gos_discord_ai_sf($it['guildId'] ?? '');
        if ($did !== '') {
            $userIds[$did] = true;
        }
        if ($gid !== '') {
            $guildIds[$gid] = true;
        }
    }
    if ($userIds === []) {
        return $items;
    }
    try {
        $map = gos_discord_ai_lookup_member_roles($avalynn, array_keys($userIds), array_keys($guildIds));
    } catch (Throwable) {
        return $items;
    }
    foreach ($items as $i => $it) {
        if (!is_array($it)) {
            continue;
        }
        $did = gos_discord_ai_sf($it['user']['discordId'] ?? '');
        $gid = gos_discord_ai_sf($it['guildId'] ?? '');
        $roles = ($gid !== '' && $did !== '' && isset($map[$gid][$did]) && is_array($map[$gid][$did]))
            ? array_values($map[$gid][$did])
            : [];
        if (!isset($it['user']) || !is_array($it['user'])) {
            $it['user'] = [];
        }
        $it['user']['roles'] = $roles;
        $items[$i] = $it;
    }
    return $items;
}

function gos_discord_ai_public_result(array $row): array
{
    $tags = function_exists('gos_discord_parse_message_tags')
        ? gos_discord_parse_message_tags((string) ($row['tags'] ?? ''))
        : [];
    return [
        'id' => (int) ($row['id'] ?? 0),
        'jobId' => (int) ($row['job_id'] ?? 0),
        'messageId' => (int) ($row['message_id'] ?? 0),
        'discordMessageId' => (string) ($row['discord_message_id'] ?? ''),
        'botId' => (int) ($row['bot_id'] ?? 0),
        'guildId' => (string) ($row['guild_id'] ?? ''),
        'guildName' => (string) ($row['guild_name'] ?? ''),
        'channelId' => (string) ($row['channel_id'] ?? ''),
        'channelName' => (string) ($row['channel_name'] ?? ''),
        'content' => (string) ($row['content'] ?? ''),
        'tags' => $tags,
        'status' => (string) ($row['status'] ?? 'ok'),
        'error' => (string) ($row['error'] ?? ''),
        'createdAt' => (string) ($row['created_at'] ?? ''),
        'user' => [
            'id' => (int) ($row['user_id'] ?? 0),
            'discordId' => (string) ($row['discord_user_id'] ?? ''),
            'username' => (string) ($row['username'] ?? ''),
            'displayName' => (string) ($row['display_name'] ?? ''),
            'avatar' => (string) ($row['avatar'] ?? ''),
            'roles' => is_array($row['roles'] ?? null) ? array_values($row['roles']) : [],
        ],
    ];
}

/**
 * @return array{ok:bool,status:int,data:null,error:string}
 */
function gos_discord_ai_not_ready(): array
{
    return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'ai_not_migrated'];
}

function gos_discord_ai_date_ok(string $raw): string
{
    $s = trim($raw);
    if ($s === '') {
        return '';
    }
    if (preg_match('/^(\d{4}-\d{2}-\d{2})(?:[ T](\d{2}:\d{2}(?::\d{2})?)?)?/', $s, $m)) {
        $d = $m[1];
        if (!empty($m[2])) {
            $t = $m[2];
            if (strlen($t) === 5) {
                $t .= ':00';
            }
            return $d . ' ' . $t;
        }
        return $d;
    }
    return '';
}

/**
 * @param array<string, mixed> $q
 * @return array{where:string,params:list<mixed>}
 */
function gos_discord_ai_message_where(array $q, PDO $avalynn): array
{
    $guildId = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($q['guildId'] ?? '') : '';
    $channelId = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($q['channelId'] ?? '') : '';
    $botId = function_exists('gos_discord_id') ? gos_discord_id($q['botId'] ?? '') : '';
    $tf = strtolower(trim((string) ($q['timeframe'] ?? '1d')));
    $seconds = function_exists('gos_discord_timeframe_seconds') ? gos_discord_timeframe_seconds($tf) : 86400;
    $fromDate = gos_discord_ai_date_ok((string) ($q['fromDate'] ?? $q['from_date'] ?? ''));
    $toDate = gos_discord_ai_date_ok((string) ($q['toDate'] ?? $q['to_date'] ?? ''));
    $skipTagged = ($q['skipTagged'] ?? $q['skip_tagged'] ?? true);
    $skip = $skipTagged === true || $skipTagged === 1 || $skipTagged === '1' || $skipTagged === 'true';
    if (gos_discord_ai_kind_of($q) === 'analyze') {
        $skip = false;
    }

    $targetMessage = function_exists('gos_discord_ai_target_message_id')
        ? gos_discord_ai_target_message_id($q)
        : '';
    $where = ["m.isSpam = 0"];
    $params = [];
    if ($targetMessage === '') {
        $where[] = "TRIM(m.content) <> ''";
    } else {
        $where[] = 'm.messageId = ?';
        $params[] = $targetMessage;
    }

    $botIds = function_exists('gos_discord_active_bot_ids') ? gos_discord_active_bot_ids($avalynn) : [];
    if ($botId !== '') {
        $bid = (int) $botId;
        if ($botIds !== [] && !in_array($bid, $botIds, true)) {
            $botIds = [];
        } else {
            $botIds = [$bid];
        }
    }
    if ($botIds !== []) {
        $where[] = 'm.botId IN (' . implode(',', array_map('intval', $botIds)) . ')';
    }
    if ($guildId !== '') {
        $where[] = 'm.guildId = ?';
        $params[] = $guildId;
    }
    if ($channelId !== '') {
        $where[] = 'm.channelId = ?';
        $params[] = $channelId;
    }
    $userFilter = function_exists('gos_discord_messages_user_filter')
        ? gos_discord_messages_user_filter($q)
        : ['clause' => '', 'params' => []];
    if ($userFilter['clause'] !== '') {
        $where[] = $userFilter['clause'];
        foreach ($userFilter['params'] as $p) {
            $params[] = $p;
        }
    }
    if ($targetMessage === '') {
        if ($fromDate !== '') {
            $where[] = 'm.createdAt >= ?';
            $params[] = $fromDate;
            if ($toDate !== '') {
                $where[] = 'm.createdAt <= ?';
                $params[] = strlen($toDate) > 10 ? $toDate : ($toDate . ' 23:59:59.999');
            }
        } elseif ($seconds > 0) {
            $where[] = 'm.createdAt >= DATE_SUB(UTC_TIMESTAMP(3), INTERVAL ' . (int) $seconds . ' SECOND)';
        }
    }
    if ($skip) {
        $where[] = "(m.tags IS NULL OR TRIM(m.tags) = '' OR TRIM(m.tags) = '[]')";
    }
    return ['where' => implode(' AND ', $where), 'params' => $params];
}

/**
 * @param array<string, mixed> $q
 */
function gos_discord_ai_scope_of(array $q): string
{
    $targetMessage = function_exists('gos_discord_ai_target_message_id')
        ? gos_discord_ai_target_message_id($q)
        : '';
    if ($targetMessage !== '') {
        return 'message';
    }
    $channelId = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($q['channelId'] ?? '') : '';
    if ($channelId !== '') {
        return 'channel';
    }
    $userRaw = trim((string) ($q['userId'] ?? $q['discordId'] ?? $q['discordUserId'] ?? ''));
    if ($userRaw !== '') {
        return 'user';
    }
    $guildId = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($q['guildId'] ?? '') : '';
    if ($guildId !== '') {
        return 'guild';
    }
    return 'all';
}

/**
 * @param array<string, mixed> $job
 * @return array<string, mixed>
 */
function gos_discord_ai_job_to_query(array $job): array
{
    $q = [
        'botId' => (string) ($job['bot_id'] ?? ''),
        'guildId' => (string) ($job['guild_id'] ?? ''),
        'channelId' => (string) ($job['channel_id'] ?? ''),
        'timeframe' => (string) ($job['timeframe'] ?? '1d'),
        'fromDate' => (string) ($job['from_date'] ?? ''),
        'toDate' => (string) ($job['to_date'] ?? ''),
        'skipTagged' => (int) ($job['skip_tagged'] ?? 1) === 1,
        'kind' => (string) ($job['kind'] ?? 'tag'),
    ];
    if ((int) ($job['user_id'] ?? 0) > 0) {
        $q['userId'] = (string) $job['user_id'];
    } elseif ((string) ($job['discord_user_id'] ?? '') !== '') {
        $q['userId'] = (string) $job['discord_user_id'];
    }
    $mid = trim((string) ($job['discord_message_id'] ?? ''));
    if ($mid !== '') {
        $q['messageId'] = $mid;
    }
    return $q;
}

function gos_discord_ai_label(array $q, string $scope): string
{
    $kind = gos_discord_ai_kind_of($q);
    $limit = (int) ($q['messageLimit'] ?? $q['limit'] ?? 50);
    $tf = (string) ($q['timeframe'] ?? '1d');
    $from = gos_discord_ai_date_ok((string) ($q['fromDate'] ?? ''));
    $to = gos_discord_ai_date_ok((string) ($q['toDate'] ?? ''));
    $range = $from !== '' ? ($from . ($to !== '' ? ' → ' . $to : '')) : $tf;
    $who = match ($scope) {
        'message' => 'message',
        'channel' => 'channel',
        'guild' => 'guild',
        'user' => 'user',
        default => 'all channels',
    };
    $prefix = $kind === 'analyze' ? 'Analyze' : 'Tag';
    return $prefix . ' · ' . $who . ' · ' . $range . ' · ' . $limit;
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_ai_start(array $body): array
{
    if (!gos_discord_ai_tables_ready()) {
        return gos_discord_ai_not_ready();
    }
    $avalynn = gos_discord_avalynn_pdo();
    if ($avalynn === null) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'db_unavailable'];
    }
    $runtime = gos_discord_ai_runtime();
    if ($runtime['provider'] === 'spacexai' && gos_discord_ai_xai_key() === '') {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'spacexai_key_missing'];
    }
    $kind = gos_discord_ai_kind_of($body);
    $maxLimit = $kind === 'analyze' ? GOS_DISCORD_AI_ANALYZE_MAX_LIMIT : GOS_DISCORD_AI_MAX_LIMIT;
    $limit = function_exists('gos_discord_int_range')
        ? gos_discord_int_range($body['limit'] ?? $body['messageLimit'] ?? 50, 1, $maxLimit, 50)
        : 50;
    if ($kind === 'analyze') {
        $body['skipTagged'] = false;
        $body['kind'] = 'analyze';
    } else {
        $body['kind'] = 'tag';
    }
    $targetMessage = function_exists('gos_discord_ai_target_message_id')
        ? gos_discord_ai_target_message_id($body)
        : '';
    if ($targetMessage !== '') {
        $body['timeframe'] = 'all';
        $body['fromDate'] = '';
        $body['toDate'] = '';
        $body['messageId'] = $targetMessage;
        if ($kind === 'tag') {
            $body['skipTagged'] = false;
        }
        $limit = 1;
    }
    $scope = gos_discord_ai_scope_of($body);
    $filter = gos_discord_ai_message_where($body, $avalynn);
    $sql = 'SELECT COUNT(*) FROM (
                SELECT m.id FROM Message m
                LEFT JOIN User u ON u.id = m.userId
                WHERE ' . $filter['where'] . '
                ORDER BY m.id DESC
                LIMIT ' . $limit . '
            ) t';
    try {
        $st = $avalynn->prepare($sql);
        $st->execute($filter['params']);
        $total = (int) $st->fetchColumn();
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'count_failed'];
    }
    if ($total <= 0) {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'no_messages'];
    }
    $botId = (int) (function_exists('gos_discord_id') ? (gos_discord_id($body['botId'] ?? '') ?: '0') : 0);
    $guildId = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($body['guildId'] ?? '') : '';
    $channelId = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($body['channelId'] ?? '') : '';
    $lookup = function_exists('gos_discord_profile_lookup')
        ? gos_discord_profile_lookup($body)
        : ['byDiscordId' => false, 'key' => ''];
    $userId = 0;
    $discordUserId = '';
    if ($lookup['key'] !== '') {
        if ($lookup['byDiscordId']) {
            $discordUserId = $lookup['key'];
            try {
                $us = $avalynn->prepare('SELECT id FROM User WHERE discordId = ? LIMIT 1');
                $us->execute([$discordUserId]);
                $userId = (int) $us->fetchColumn();
            } catch (Throwable) {
                $userId = 0;
            }
        } else {
            $userId = (int) $lookup['key'];
        }
    }
    $skip = ($body['skipTagged'] ?? true);
    $skipTagged = $kind === 'analyze'
        ? false
        : ($skip === true || $skip === 1 || $skip === '1' || $skip === 'true');
    $tf = strtolower(trim((string) ($body['timeframe'] ?? '1d')));
    $fromDate = gos_discord_ai_date_ok((string) ($body['fromDate'] ?? ''));
    $toDate = gos_discord_ai_date_ok((string) ($body['toDate'] ?? ''));
    $labelIn = trim((string) ($body['label'] ?? ''));
    if ($labelIn !== '') {
        $label = substr($labelIn, 0, 255);
        if (!preg_match('/^(tag|analyze)\b/i', $label)) {
            $label = ($kind === 'analyze' ? 'Analyze' : 'Tag') . ' · ' . $label;
        }
    } else {
        $label = gos_discord_ai_label($body, $scope);
    }
    $prompt = gos_discord_ai_prompt_ok((string) ($body['prompt'] ?? $body['userPrompt'] ?? ''));
    try {
        $cols = ['bot_id'];
        $params = [$botId];
        if (gos_discord_ai_has_kind_column()) {
            $cols[] = 'kind';
            $params[] = $kind;
        }
        array_push(
            $cols,
            'scope',
            'guild_id',
            'channel_id',
            'user_id',
            'discord_user_id',
            'timeframe',
            'from_date',
            'to_date',
            'message_limit',
            'skip_tagged',
            'status',
            'total',
            'label',
        );
        array_push(
            $params,
            $scope,
            $guildId,
            $channelId,
            $userId,
            $discordUserId,
            $tf,
            $fromDate,
            $toDate,
            $limit,
            $skipTagged ? 1 : 0,
            'queued',
            $total,
            $label,
        );
        if (gos_discord_ai_has_prompt_column()) {
            $cols[] = 'prompt';
            $params[] = $prompt;
        }
        if ($targetMessage !== '' && gos_discord_ai_has_column('discord_message_id')) {
            $cols[] = 'discord_message_id';
            $params[] = $targetMessage;
        }
        if (gos_discord_ai_has_column('provider')) {
            $cols[] = 'provider';
            $params[] = $runtime['provider'];
        }
        if (gos_discord_ai_has_column('model')) {
            $cols[] = 'model';
            $params[] = $runtime['model'];
        }
        if (gos_discord_ai_has_column('reasoning_effort')) {
            $cols[] = 'reasoning_effort';
            $params[] = $runtime['reasoningEffort'];
        }
        $placeholders = implode(',', array_fill(0, count($cols), '?'));
        $ins = gos_pdo()->prepare(
            'INSERT INTO discord_ai_jobs (' . implode(', ', $cols) . ') VALUES (' . $placeholders . ')'
        );
        $ins->execute($params);
        $id = (int) gos_pdo()->lastInsertId();
        $job = gos_pdo()->prepare('SELECT * FROM discord_ai_jobs WHERE id = ? LIMIT 1');
        $job->execute([$id]);
        $row = $job->fetch() ?: [];
        gos_discord_ai_spawn_worker();
        return ['ok' => true, 'status' => 200, 'data' => ['job' => gos_discord_ai_public_job($row)], 'error' => null];
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'job_create_failed'];
    }
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_ai_cancel(array $body): array
{
    if (!gos_discord_ai_tables_ready()) {
        return gos_discord_ai_not_ready();
    }
    $id = (int) ($body['id'] ?? $body['jobId'] ?? 0);
    if ($id <= 0) {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'missing_id'];
    }
    try {
        $cur = gos_pdo()->prepare('SELECT scope FROM discord_ai_jobs WHERE id = ? LIMIT 1');
        $cur->execute([$id]);
        $scope = strtolower(trim((string) ($cur->fetchColumn() ?: '')));
        if ($scope === 'live') {
            return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'cannot_cancel_live'];
        }
        $st = gos_pdo()->prepare(
            "UPDATE discord_ai_jobs SET status = 'cancelled', last_error = ''
             WHERE id = ? AND status IN ('queued','running')"
        );
        $st->execute([$id]);
        $job = gos_pdo()->prepare('SELECT * FROM discord_ai_jobs WHERE id = ? LIMIT 1');
        $job->execute([$id]);
        $row = $job->fetch();
        if (!is_array($row)) {
            return ['ok' => false, 'status' => 404, 'data' => null, 'error' => 'not_found'];
        }
        return ['ok' => true, 'status' => 200, 'data' => ['job' => gos_discord_ai_public_job($row)], 'error' => null];
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'cancel_failed'];
    }
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_ai_tick(array $body): array
{
    if (!gos_discord_ai_tables_ready()) {
        return gos_discord_ai_not_ready();
    }
    @set_time_limit(180);
    $id = (int) ($body['id'] ?? $body['jobId'] ?? 0);
    if ($id <= 0) {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'missing_id'];
    }
    $gos = gos_pdo();
    $avalynn = gos_discord_avalynn_pdo();
    if ($avalynn === null) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'db_unavailable'];
    }
    try {
        $gos->beginTransaction();
        $st = $gos->prepare('SELECT * FROM discord_ai_jobs WHERE id = ? FOR UPDATE');
        $st->execute([$id]);
        $job = $st->fetch();
        if (!is_array($job)) {
            $gos->rollBack();
            return ['ok' => false, 'status' => 404, 'data' => null, 'error' => 'not_found'];
        }
        $status = (string) ($job['status'] ?? '');
        if (in_array($status, ['done', 'cancelled', 'error'], true)) {
            $gos->commit();
            return ['ok' => true, 'status' => 200, 'data' => ['job' => gos_discord_ai_public_job($job), 'result' => null, 'done' => true], 'error' => null];
        }
        if ((int) $job['processed'] >= (int) $job['total'] && (int) $job['total'] > 0) {
            $gos->prepare("UPDATE discord_ai_jobs SET status = 'done' WHERE id = ?")->execute([$id]);
            $job['status'] = 'done';
            $gos->commit();
            return ['ok' => true, 'status' => 200, 'data' => ['job' => gos_discord_ai_public_job($job), 'result' => null, 'done' => true], 'error' => null];
        }
        $kind = strtolower(trim((string) ($job['kind'] ?? 'tag')));
        if ($kind === 'analyze') {
            $gos->commit();
            return gos_discord_ai_analyze_step($job);
        }
        $q = gos_discord_ai_job_to_query($job);
        $filter = gos_discord_ai_message_where($q, $avalynn);
        $cursor = (int) ($job['last_message_id'] ?? 0);
        $params = $filter['params'];
        $cursorSql = '';
        if ($cursor > 0) {
            $cursorSql = ' AND m.id < ?';
            $params[] = $cursor;
        }
        $pick = $avalynn->prepare(
            'SELECT m.id, m.messageId, m.guildId, m.channelId, m.content, m.tags, m.createdAt, m.userId, m.botId,
                    u.discordId, u.username, u.displayName, u.avatar
             FROM Message m
             LEFT JOIN User u ON u.id = m.userId
             WHERE ' . $filter['where'] . $cursorSql . '
             ORDER BY m.id DESC
             LIMIT 1'
        );
        $pick->execute($params);
        $msg = $pick->fetch();
        if (!is_array($msg)) {
            $gos->prepare("UPDATE discord_ai_jobs SET status = 'done' WHERE id = ?")->execute([$id]);
            $job['status'] = 'done';
            $gos->commit();
            return ['ok' => true, 'status' => 200, 'data' => ['job' => gos_discord_ai_public_job($job), 'result' => null, 'done' => true], 'error' => null];
        }
        $msgId = (int) $msg['id'];
        $gos->prepare("UPDATE discord_ai_jobs SET status = 'running', last_message_id = ?, last_error = '' WHERE id = ?")
            ->execute([$msgId, $id]);
        $gos->commit();
    } catch (Throwable $e) {
        if ($gos->inTransaction()) {
            $gos->rollBack();
        }
        $err = substr($e->getMessage(), 0, 180);
        try {
            $gos->prepare('UPDATE discord_ai_jobs SET last_error = ? WHERE id = ?')->execute([$err, $id]);
        } catch (Throwable) {
            // still report the tick failure
        }
        error_log('discord_ai_tick: ' . $err);
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'tick_lock_failed'];
    }

    $guildName = '';
    $channelName = '';
    try {
        $gid = (string) ($msg['guildId'] ?? '');
        $cid = (string) ($msg['channelId'] ?? '');
        if ($gid !== '') {
            $gs = $avalynn->prepare('SELECT guildName FROM GuildSettings WHERE guildId = ? ORDER BY botId IS NULL DESC LIMIT 1');
            $gs->execute([$gid]);
            $guildName = (string) ($gs->fetchColumn() ?: '');
        }
        if ($cid !== '') {
            $cs = $avalynn->prepare('SELECT channelName FROM BotChannel WHERE channelId = ? LIMIT 1');
            $cs->execute([$cid]);
            $channelName = (string) ($cs->fetchColumn() ?: '');
        }
    } catch (Throwable) {
        // names are optional context
    }
    $did = (string) ($msg['discordId'] ?? '');
    $avatar = function_exists('gos_discord_avatar_url')
        ? gos_discord_avatar_url($did, (string) ($msg['avatar'] ?? ''))
        : '';
    $payload = [
        'content' => (string) ($msg['content'] ?? ''),
        'guildId' => (string) ($msg['guildId'] ?? ''),
        'guildName' => $guildName,
        'channelName' => $channelName,
        'username' => (string) ($msg['username'] ?? ''),
        'displayName' => (string) ($msg['displayName'] ?? ''),
        'discordId' => $did,
        'createdAt' => (string) ($msg['createdAt'] ?? ''),
    ];
    $enriched = gos_discord_ai_attach_mentions([$payload], $avalynn);
    if (isset($enriched[0]) && is_array($enriched[0])) {
        $payload = $enriched[0];
    }
    $resultStatus = 'ok';
    $err = '';
    $tags = [];
    try {
        $tags = gos_discord_ai_generate_tags($payload, (string) ($job['prompt'] ?? ''), $job);
        if ($tags === []) {
            $resultStatus = 'skip';
            $err = 'empty_tags';
        } else {
            $csv = implode(',', $tags);
            $upd = $avalynn->prepare('UPDATE Message SET tags = ? WHERE id = ?');
            $upd->execute([$csv, $msgId]);
        }
    } catch (Throwable $e) {
        $resultStatus = 'error';
        $err = substr($e->getMessage(), 0, 180);
        $tags = [];
        $csv = '';
    }
    $csv = $tags !== [] ? implode(',', $tags) : '';
    try {
        $ins = $gos->prepare(
            'INSERT INTO discord_ai_results
             (job_id, message_id, discord_message_id, bot_id, guild_id, guild_name, channel_id, channel_name,
              user_id, discord_user_id, username, display_name, avatar, content, tags, status, error)
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)'
        );
        $ins->execute([
            $id,
            $msgId,
            (string) ($msg['messageId'] ?? ''),
            (int) ($msg['botId'] ?? $job['bot_id'] ?? 0),
            (string) ($msg['guildId'] ?? ''),
            $guildName,
            (string) ($msg['channelId'] ?? ''),
            $channelName,
            (int) ($msg['userId'] ?? 0),
            $did,
            (string) ($msg['username'] ?? ''),
            (string) ($msg['displayName'] ?? ''),
            $avatar,
            mb_substr((string) ($msg['content'] ?? ''), 0, 2000),
            $csv,
            $resultStatus,
            $err,
        ]);
        $resultId = (int) $gos->lastInsertId();
        $incTagged = $resultStatus === 'ok' ? 1 : 0;
        $incSkip = $resultStatus === 'skip' ? 1 : 0;
        $incFail = $resultStatus === 'error' ? 1 : 0;
        $gos->prepare(
            'UPDATE discord_ai_jobs
             SET processed = processed + 1,
                 tagged = tagged + ?,
                 skipped = skipped + ?,
                 failed = failed + ?,
                 last_error = ?,
                 status = CASE
                    WHEN processed + 1 >= total THEN \'done\'
                    ELSE \'running\'
                 END
             WHERE id = ?'
        )->execute([$incTagged, $incSkip, $incFail, $err, $id]);
        $jobSt = $gos->prepare('SELECT * FROM discord_ai_jobs WHERE id = ? LIMIT 1');
        $jobSt->execute([$id]);
        $job = $jobSt->fetch() ?: $job;
        $resSt = $gos->prepare('SELECT * FROM discord_ai_results WHERE id = ? LIMIT 1');
        $resSt->execute([$resultId]);
        $resRow = $resSt->fetch() ?: [];
        $done = in_array((string) ($job['status'] ?? ''), ['done', 'cancelled', 'error'], true);
        return [
            'ok' => true,
            'status' => 200,
            'data' => [
                'job' => gos_discord_ai_public_job(is_array($job) ? $job : []),
                'result' => (static function () use ($resRow, $payload) {
                    if ($resRow === []) {
                        return null;
                    }
                    $pub = gos_discord_ai_public_result($resRow);
                    $roles = is_array($payload['authorRoles'] ?? null) ? array_values($payload['authorRoles']) : [];
                    $pub['user']['roles'] = $roles;
                    return $pub;
                })(),
                'done' => $done,
            ],
            'error' => null,
        ];
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'tick_save_failed'];
    }
}

/**
 * @param array<string, mixed> $job
 * @return list<array<string, mixed>>
 */
function gos_discord_ai_fetch_batch(array $job, PDO $avalynn, int $limit): array
{
    $q = gos_discord_ai_job_to_query($job);
    $q['skipTagged'] = false;
    $filter = gos_discord_ai_message_where($q, $avalynn);
    $cursor = (int) ($job['last_message_id'] ?? 0);
    $params = $filter['params'];
    $cursorSql = '';
    if ($cursor > 0) {
        $cursorSql = ' AND m.id < ?';
        $params[] = $cursor;
    }
    $st = $avalynn->prepare(
        'SELECT m.id, m.messageId, m.guildId, m.channelId, m.content, m.tags, m.createdAt, m.userId, m.botId,
                u.discordId, u.username, u.displayName, u.avatar
         FROM Message m
         LEFT JOIN User u ON u.id = m.userId
         WHERE ' . $filter['where'] . $cursorSql . '
         ORDER BY m.id DESC
         LIMIT ' . max(1, $limit)
    );
    $st->execute($params);
    $rows = $st->fetchAll() ?: [];
    if ($rows === []) {
        return [];
    }
    $guildIds = [];
    $channelIds = [];
    foreach ($rows as $row) {
        $gid = (string) ($row['guildId'] ?? '');
        $cid = (string) ($row['channelId'] ?? '');
        if ($gid !== '') {
            $guildIds[$gid] = true;
        }
        if ($cid !== '') {
            $channelIds[$cid] = true;
        }
    }
    $guildNames = [];
    $channelNames = [];
    $gList = array_keys($guildIds);
    $cList = array_keys($channelIds);
    if ($gList !== []) {
        $place = implode(',', array_fill(0, count($gList), '?'));
        try {
            $gs = $avalynn->prepare(
                'SELECT guildId, guildName FROM GuildSettings WHERE guildId IN (' . $place . ')'
            );
            $gs->execute($gList);
            foreach ($gs->fetchAll() ?: [] as $g) {
                $id = (string) ($g['guildId'] ?? '');
                if ($id !== '' && !isset($guildNames[$id])) {
                    $guildNames[$id] = (string) ($g['guildName'] ?? '');
                }
            }
        } catch (Throwable) {
            $guildNames = [];
        }
    }
    if ($cList !== []) {
        $place = implode(',', array_fill(0, count($cList), '?'));
        try {
            $cs = $avalynn->prepare(
                'SELECT channelId, channelName FROM BotChannel WHERE channelId IN (' . $place . ')'
            );
            $cs->execute($cList);
            foreach ($cs->fetchAll() ?: [] as $c) {
                $id = (string) ($c['channelId'] ?? '');
                if ($id !== '' && !isset($channelNames[$id])) {
                    $channelNames[$id] = (string) ($c['channelName'] ?? '');
                }
            }
        } catch (Throwable) {
            $channelNames = [];
        }
    }
    $out = [];
    foreach ($rows as $row) {
        $gid = (string) ($row['guildId'] ?? '');
        $cid = (string) ($row['channelId'] ?? '');
        $tags = function_exists('gos_discord_parse_message_tags')
            ? gos_discord_parse_message_tags((string) ($row['tags'] ?? ''))
            : [];
        $row['guildName'] = $guildNames[$gid] ?? '';
        $row['channelName'] = $channelNames[$cid] ?? '';
        $row['tagList'] = $tags;
        $out[] = $row;
    }
    return gos_discord_ai_attach_mentions($out, $avalynn);
}

/**
 * @param array<string, mixed> $job
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_ai_analyze_step(array $job): array
{
    @set_time_limit(180);
    $id = (int) ($job['id'] ?? 0);
    if ($id <= 0) {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'missing_id'];
    }
    $cursorWas = (int) ($job['last_message_id'] ?? 0);
    $gos = gos_pdo();
    $avalynn = gos_discord_avalynn_pdo();
    if ($avalynn === null) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'db_unavailable'];
    }
    $status = (string) ($job['status'] ?? '');
    if (in_array($status, ['done', 'cancelled', 'error'], true)) {
        return ['ok' => true, 'status' => 200, 'data' => ['job' => gos_discord_ai_public_job($job), 'result' => null, 'done' => true], 'error' => null];
    }
    $chunk = GOS_DISCORD_AI_ANALYZE_CHUNK;
    try {
        $rows = gos_discord_ai_fetch_batch($job, $avalynn, $chunk);
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'analyze_fetch_failed'];
    }
    if ($rows === []) {
        try {
            $gos->prepare("UPDATE discord_ai_jobs SET status = 'done' WHERE id = ?")->execute([$id]);
            $job['status'] = 'done';
        } catch (Throwable) {
            // ignore
        }
        return ['ok' => true, 'status' => 200, 'data' => ['job' => gos_discord_ai_public_job($job), 'result' => null, 'done' => true], 'error' => null];
    }
    $oldestId = (int) ($rows[count($rows) - 1]['id'] ?? 0);
    try {
        $gos->prepare("UPDATE discord_ai_jobs SET status = 'running', last_error = '' WHERE id = ?")
            ->execute([$id]);
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'analyze_claim_failed'];
    }

    $chrono = array_reverse($rows);
    $hadPrior = (int) ($job['processed'] ?? 0) > 0;
    $remaining = max(0, (int) $job['total'] - (int) $job['processed']);
    $moreAfter = $remaining > count($rows);
    $heading = gos_discord_ai_window_heading($chrono, $moreAfter || $hadPrior);
    $transcript = gos_discord_ai_format_transcript($chrono, $heading);
    $operator = gos_discord_ai_prompt_ok((string) ($job['prompt'] ?? ''));
    $err = '';
    $chunkText = '';
    try {
        $chunkText = gos_discord_ai_complete(
            gos_discord_ai_with_operator_prompt(gos_discord_ai_analyze_system_prompt(), $operator),
            $transcript,
            ['temperature' => 0.35, 'max_tokens' => 1800, 'timeout' => 150, 'job' => $job]
        );
        if ($chunkText === '') {
            throw new RuntimeException('empty_summary');
        }
    } catch (Throwable $e) {
        $err = substr($e->getMessage(), 0, 180);
        $failed = (int) ($job['failed'] ?? 0) + 1;
        $statusOut = $failed >= 3 ? 'error' : 'running';
        try {
            $gos->prepare('UPDATE discord_ai_jobs SET failed = ?, last_error = ?, status = ? WHERE id = ?')
                ->execute([$failed, $err, $statusOut, $id]);
            $jobSt = $gos->prepare('SELECT * FROM discord_ai_jobs WHERE id = ? LIMIT 1');
            $jobSt->execute([$id]);
            $job = $jobSt->fetch() ?: $job;
        } catch (Throwable) {
            // ignore
        }
        return [
            'ok' => true,
            'status' => 200,
            'data' => [
                'job' => gos_discord_ai_public_job(is_array($job) ? $job : []),
                'result' => null,
                'done' => $statusOut !== 'running',
            ],
            'error' => null,
        ];
    }

    $existing = trim((string) ($job['summary'] ?? ''));
    $combined = $existing === '' ? $chunkText : ($existing . "\n\n" . $chunkText);
    $processed = (int) ($job['processed'] ?? 0) + count($rows);
    $total = max((int) ($job['total'] ?? 0), $processed);
    $done = $processed >= $total || count($rows) < $chunk;
    $summary = $combined;
    if ($done && $hadPrior) {
        try {
            $summary = gos_discord_ai_complete(
                gos_discord_ai_with_operator_prompt(gos_discord_ai_analyze_merge_prompt(), $operator),
                $combined,
                ['temperature' => 0.3, 'max_tokens' => 2200, 'timeout' => 150, 'job' => $job]
            );
            if ($summary === '') {
                $summary = $combined;
            }
        } catch (Throwable) {
            $summary = $combined;
        }
    }

    try {
        $upd = $gos->prepare(
            'UPDATE discord_ai_jobs
             SET processed = ?, total = ?, summary = ?, last_message_id = ?, last_error = \'\', status = ?
             WHERE id = ? AND last_message_id = ?'
        );
        $upd->execute([$processed, $total, $summary, $oldestId, $done ? 'done' : 'running', $id, $cursorWas]);
        if ($upd->rowCount() < 1) {
            $jobSt = $gos->prepare('SELECT * FROM discord_ai_jobs WHERE id = ? LIMIT 1');
            $jobSt->execute([$id]);
            $job = $jobSt->fetch() ?: $job;
            return [
                'ok' => true,
                'status' => 200,
                'data' => [
                    'job' => gos_discord_ai_public_job(is_array($job) ? $job : []),
                    'result' => null,
                    'done' => in_array((string) ($job['status'] ?? ''), ['done', 'cancelled', 'error'], true),
                ],
                'error' => null,
            ];
        }
        $result = null;
        $jobSt = $gos->prepare('SELECT * FROM discord_ai_jobs WHERE id = ? LIMIT 1');
        $jobSt->execute([$id]);
        $job = $jobSt->fetch() ?: $job;
        return [
            'ok' => true,
            'status' => 200,
            'data' => [
                'job' => gos_discord_ai_public_job(is_array($job) ? $job : []),
                'result' => $result,
                'done' => $done,
            ],
            'error' => null,
        ];
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'analyze_save_failed'];
    }
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_ai_jobs(array $q): array
{
    if (!gos_discord_ai_tables_ready()) {
        return gos_discord_ai_not_ready();
    }
    $limit = function_exists('gos_discord_int_range') ? gos_discord_int_range($q['limit'] ?? 40, 1, 100, 40) : 40;
    $offset = function_exists('gos_discord_int_range') ? gos_discord_int_range($q['offset'] ?? 0, 0, 5_000_000, 0) : 0;
    $botId = function_exists('gos_discord_id') ? gos_discord_id($q['botId'] ?? '') : '';
    $status = strtolower(trim((string) ($q['status'] ?? '')));
    $kind = strtolower(trim((string) ($q['kind'] ?? '')));
    $sort = strtolower(trim((string) ($q['sort'] ?? 'newest')));
    $orderSql = $sort === 'oldest' ? 'id ASC' : 'id DESC';
    $where = ["scope <> 'live'"];
    $params = [];
    if ($botId !== '') {
        $where[] = 'bot_id = ?';
        $params[] = (int) $botId;
    }
    if ($status !== '' && in_array($status, ['queued', 'running', 'done', 'error', 'cancelled'], true)) {
        $where[] = 'status = ?';
        $params[] = $status;
    }
    if ($kind !== '' && in_array($kind, ['tag', 'analyze'], true) && gos_discord_ai_has_kind_column()) {
        $where[] = 'kind = ?';
        $params[] = $kind;
    }
    $sqlWhere = implode(' AND ', $where);
    try {
        $st = gos_pdo()->prepare(
            'SELECT * FROM discord_ai_jobs WHERE ' . $sqlWhere . ' ORDER BY ' . $orderSql . ' LIMIT ' . ($limit + 1) . ' OFFSET ' . $offset
        );
        $st->execute($params);
        $rows = $st->fetchAll() ?: [];
        $hasMore = count($rows) > $limit;
        if ($hasMore) {
            array_pop($rows);
        }
        $jobs = [];
        foreach ($rows as $row) {
            $jobs[] = gos_discord_ai_public_job($row);
        }
        return [
            'ok' => true,
            'status' => 200,
            'data' => ['jobs' => $jobs, 'hasMore' => $hasMore, 'offset' => $offset, 'limit' => $limit],
            'error' => null,
        ];
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'jobs_query_failed'];
    }
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_ai_job(array $q): array
{
    if (!gos_discord_ai_tables_ready()) {
        return gos_discord_ai_not_ready();
    }
    $id = (int) ($q['id'] ?? $q['jobId'] ?? 0);
    if ($id <= 0) {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'missing_id'];
    }
    try {
        $st = gos_pdo()->prepare('SELECT * FROM discord_ai_jobs WHERE id = ? LIMIT 1');
        $st->execute([$id]);
        $row = $st->fetch();
        if (!is_array($row)) {
            return ['ok' => false, 'status' => 404, 'data' => null, 'error' => 'not_found'];
        }
        return ['ok' => true, 'status' => 200, 'data' => ['job' => gos_discord_ai_public_job($row)], 'error' => null];
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'job_query_failed'];
    }
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_ai_activity(array $q): array
{
    if (!gos_discord_ai_tables_ready()) {
        return gos_discord_ai_not_ready();
    }
    $limit = function_exists('gos_discord_int_range') ? gos_discord_int_range($q['limit'] ?? 40, 1, 100, 40) : 40;
    $offset = function_exists('gos_discord_int_range') ? gos_discord_int_range($q['offset'] ?? 0, 0, 5_000_000, 0) : 0;
    $botId = function_exists('gos_discord_id') ? gos_discord_id($q['botId'] ?? '') : '';
    $guildId = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($q['guildId'] ?? '') : '';
    $status = strtolower(trim((string) ($q['status'] ?? '')));
    $search = trim((string) ($q['search'] ?? ''));
    if (strlen($search) > 80) {
        $search = substr($search, 0, 80);
    }
    $sort = strtolower(trim((string) ($q['sort'] ?? 'newest')));
    $tf = strtolower(trim((string) ($q['timeframe'] ?? 'all')));
    $fromDate = gos_discord_ai_date_ok((string) ($q['fromDate'] ?? ''));
    $toDate = gos_discord_ai_date_ok((string) ($q['toDate'] ?? ''));
    $seconds = function_exists('gos_discord_timeframe_seconds') ? gos_discord_timeframe_seconds($tf) : 0;
    $orderSql = match ($sort) {
        'oldest' => 'r.id ASC',
        'tags' => 'CHAR_LENGTH(r.tags) DESC, r.id DESC',
        default => 'r.id DESC',
    };
    $where = ['1=1', 'r.message_id > 0'];
    $params = [];
    if ($botId !== '') {
        $where[] = 'r.bot_id = ?';
        $params[] = (int) $botId;
    }
    if ($guildId !== '') {
        $where[] = 'r.guild_id = ?';
        $params[] = $guildId;
    }
    if ($status !== '' && in_array($status, ['ok', 'skip', 'error'], true)) {
        $where[] = 'r.status = ?';
        $params[] = $status;
    }
    if ($fromDate !== '') {
        $where[] = 'r.created_at >= ?';
        $params[] = $fromDate;
        if ($toDate !== '') {
            $where[] = 'r.created_at <= ?';
            $params[] = strlen($toDate) > 10 ? $toDate : ($toDate . ' 23:59:59');
        }
    } elseif ($seconds > 0) {
        $where[] = 'r.created_at >= DATE_SUB(UTC_TIMESTAMP(), INTERVAL ' . (int) $seconds . ' SECOND)';
    }
    if ($search !== '') {
        $where[] = '(r.username LIKE ? OR r.display_name LIKE ? OR r.tags LIKE ? OR r.content LIKE ? OR r.channel_name LIKE ? OR r.guild_name LIKE ?)';
        $like = '%' . $search . '%';
        array_push($params, $like, $like, $like, $like, $like, $like);
    }
    $sqlWhere = implode(' AND ', $where);
    try {
        $st = gos_pdo()->prepare(
            'SELECT r.* FROM discord_ai_results r WHERE ' . $sqlWhere . ' ORDER BY ' . $orderSql
            . ' LIMIT ' . ($limit + 1) . ' OFFSET ' . $offset
        );
        $st->execute($params);
        $rows = $st->fetchAll() ?: [];
        $hasMore = count($rows) > $limit;
        if ($hasMore) {
            array_pop($rows);
        }
        $items = [];
        foreach ($rows as $row) {
            if (gos_discord_ai_result_is_job_summary($row)) {
                continue;
            }
            $items[] = gos_discord_ai_public_result($row);
        }
        $items = gos_discord_ai_attach_result_roles($items);
        return [
            'ok' => true,
            'status' => 200,
            'data' => ['items' => $items, 'hasMore' => $hasMore, 'offset' => $offset, 'limit' => $limit],
            'error' => null,
        ];
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'activity_query_failed'];
    }
}

/**
 * Full tag aggregation for a user (paginated). Used by profile "View more".
 *
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_user_tags(array $q): array
{
    $pdo = gos_discord_avalynn_pdo();
    if ($pdo === null) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'db_unavailable'];
    }
    $lookup = gos_discord_profile_lookup($q);
    $key = $lookup['key'];
    if ($key === '') {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'missing_id'];
    }
    $limit = function_exists('gos_discord_int_range') ? gos_discord_int_range($q['tagLimit'] ?? $q['limit'] ?? 80, 1, 200, 80) : 80;
    $offset = function_exists('gos_discord_int_range') ? gos_discord_int_range($q['tagOffset'] ?? $q['offset'] ?? 0, 0, 1_000_000, 0) : 0;
    try {
        if ($lookup['byDiscordId']) {
            $st = $pdo->prepare('SELECT id FROM User WHERE discordId = ? LIMIT 1');
            $st->execute([$key]);
        } else {
            $st = $pdo->prepare('SELECT id FROM User WHERE id = ? LIMIT 1');
            $st->execute([(int) $key]);
        }
        $userId = (int) $st->fetchColumn();
        if ($userId <= 0) {
            return ['ok' => false, 'status' => 404, 'data' => null, 'error' => 'not_found'];
        }
        $botIds = gos_discord_active_bot_ids($pdo);
        $botSql = $botIds === [] ? '' : (' AND botId IN (' . implode(',', array_map('intval', $botIds)) . ')');
        $counts = [];
        $cursor = 0;
        $batches = 0;
        $maxBatches = 120;
        $batchSize = 2500;
        $taggedSql = 'hasTags = 1';
        try {
            $pdo->query('SELECT hasTags FROM Message LIMIT 0');
        } catch (Throwable) {
            $taggedSql = "tags IS NOT NULL AND tags <> ''";
        }
        while ($batches < $maxBatches) {
            if ($cursor > 0) {
                $sql = 'SELECT id, tags FROM Message WHERE userId = ? AND ' . $taggedSql . $botSql
                    . ' AND id > ? ORDER BY id ASC LIMIT ' . $batchSize;
                $tst = $pdo->prepare($sql);
                $tst->execute([$userId, $cursor]);
            } else {
                $sql = 'SELECT id, tags FROM Message WHERE userId = ? AND ' . $taggedSql . $botSql
                    . ' ORDER BY id ASC LIMIT ' . $batchSize;
                $tst = $pdo->prepare($sql);
                $tst->execute([$userId]);
            }
            $rows = $tst->fetchAll() ?: [];
            if ($rows === []) {
                break;
            }
            foreach ($rows as $row) {
                foreach (gos_discord_parse_message_tags((string) ($row['tags'] ?? '')) as $tag) {
                    if (gos_discord_is_bogus_tag($tag)) {
                        continue;
                    }
                    $counts[$tag] = ($counts[$tag] ?? 0) + 1;
                }
            }
            $cursor = (int) $rows[count($rows) - 1]['id'];
            $batches++;
            if (count($rows) < $batchSize) {
                break;
            }
        }
        $aggTotal = array_sum($counts);
        $ranked = gos_discord_top_tags($counts, $limit, $offset);
        $numeric = [];
        foreach ($ranked['tags'] as $row) {
            if (preg_match('/^\d+$/', (string) $row['tag'])) {
                $numeric[] = (int) $row['tag'];
            }
        }
        $idToName = [];
        if ($numeric !== []) {
            try {
                $nIn = implode(',', array_map('intval', $numeric));
                $ns = $pdo->query("SELECT id, name FROM Tag WHERE id IN ({$nIn})");
                if ($ns !== false) {
                    foreach ($ns->fetchAll() as $n) {
                        $idToName[(int) $n['id']] = (string) ($n['name'] ?? '');
                    }
                }
            } catch (Throwable) {
                $idToName = [];
            }
        }
        $topTags = [];
        foreach ($ranked['tags'] as $row) {
            $name = gos_discord_resolve_tag_names([(string) $row['tag']], $idToName)[0] ?? (string) $row['tag'];
            $topTags[] = ['tag' => $name, 'count' => (int) $row['count']];
        }
        return [
            'ok' => true,
            'status' => 200,
            'data' => [
                'topTags' => $topTags,
                'totalTagCount' => (int) $aggTotal,
                'uniqueTagCount' => count($counts),
                'hasMoreTags' => $ranked['hasMore'],
                'tagOffset' => $offset,
                'tagLimit' => $limit,
            ],
            'error' => null,
        ];
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'user_tags_failed'];
    }
}
