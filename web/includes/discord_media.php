<?php

declare(strict_types=1);

/**
 * Private Discord media social graph (likes / follows) + discogram mix.
 * Stored in GrokifyOS — never sent to Discord.
 */

const GOS_DISCORD_MEDIA_FOLLOW_BIAS = 0.4;

function gos_discord_media_tables_ready(): bool
{
    return function_exists('gos_table_exists')
        && gos_table_exists('discord_media_likes')
        && gos_table_exists('discord_media_follows');
}

function gos_discord_operator_id(): int
{
    static $cached = null;
    if ($cached !== null) {
        return $cached;
    }
    $id = 0;
    if (function_exists('gos_auth_from_bearer')) {
        $bearer = gos_auth_from_bearer();
        if (is_array($bearer) && isset($bearer['user']) && is_array($bearer['user'])) {
            $id = (int) ($bearer['user']['id'] ?? 0);
        }
    }
    if ($id <= 0 && function_exists('gos_current_user')) {
        $u = gos_current_user();
        if (is_array($u)) {
            $id = (int) ($u['id'] ?? 0);
        }
    }
    $cached = $id > 0 ? $id : 0;
    return $cached;
}

function gos_discord_attachments_order(string $sort): string
{
    $s = strtolower(trim($sort));
    if ($s === 'oldest' || $s === 'asc' || $s === 'ascending') {
        return 'ASC';
    }
    return 'DESC';
}

/**
 * Inclusive id window for bounded random sampling (never MIN/MAX + JOIN).
 *
 * @return array{0:int,1:int}
 */
function gos_discord_sample_id_window(int $maxId, int $span, ?int $seed = null): array
{
    $maxId = max(0, $maxId);
    $span = max(1, $span);
    if ($maxId <= 0) {
        return [0, 0];
    }
    if ($span >= $maxId) {
        return [1, $maxId];
    }
    if ($seed !== null) {
        mt_srand($seed);
    }
    $hi = mt_rand($span, $maxId);
    $lo = $hi - $span + 1;
    if ($lo < 1) {
        $lo = 1;
        $hi = min($maxId, $lo + $span - 1);
    }
    return [$lo, $hi];
}

/**
 * @param mixed $raw
 * @return list<int>
 */
function gos_discord_id_list(mixed $raw, int $max = 400): array
{
    if (is_array($raw)) {
        $parts = $raw;
    } else {
        $parts = preg_split('/[,\s]+/', trim((string) $raw)) ?: [];
    }
    $out = [];
    foreach ($parts as $p) {
        $n = (int) $p;
        if ($n <= 0 || isset($out[$n])) {
            continue;
        }
        $out[$n] = $n;
        if (count($out) >= $max) {
            break;
        }
    }
    return array_values($out);
}

function gos_discord_media_follow_key(string $raw): string
{
    $s = trim($raw);
    if ($s === '' || !preg_match('/^[0-9]{15,22}$/', $s)) {
        return '';
    }
    return $s;
}

/**
 * @return array<string, int>
 */
function gos_discord_discogram_quota(int $limit): array
{
    $limit = max(0, $limit);
    if ($limit === 0) {
        return [];
    }
    $weights = ['image' => 4, 'video' => 3, 'gif' => 2, 'audio' => 1];
    $sum = array_sum($weights);
    $q = [];
    $used = 0;
    foreach ($weights as $kind => $w) {
        $n = (int) floor($limit * $w / $sum);
        $q[$kind] = $n;
        $used += $n;
    }
    foreach (array_keys($weights) as $kind) {
        if ($used >= $limit) {
            break;
        }
        $q[$kind]++;
        $used++;
    }
    return $q;
}

/**
 * @param list<array<string, mixed>> $list
 * @return list<array<string, mixed>>
 */
function gos_discord_shuffle(array $list, ?int $seed = null): array
{
    $list = array_values($list);
    $n = count($list);
    if ($n <= 1) {
        return $list;
    }
    if ($seed !== null) {
        mt_srand($seed);
    }
    for ($i = $n - 1; $i > 0; $i--) {
        $j = mt_rand(0, $i);
        $tmp = $list[$i];
        $list[$i] = $list[$j];
        $list[$j] = $tmp;
    }
    return $list;
}

/**
 * @param list<array<string, mixed>> $items
 * @param list<string> $followedDiscordIds
 * @param list<int> $excludeIds
 * @return list<array<string, mixed>>
 */
function gos_discord_discogram_pick(
    array $items,
    int $limit,
    array $followedDiscordIds,
    array $excludeIds,
    ?int $seed = null,
): array {
    $limit = max(0, $limit);
    if ($limit === 0 || $items === []) {
        return [];
    }
    $exclude = [];
    foreach ($excludeIds as $id) {
        $n = (int) $id;
        if ($n > 0) {
            $exclude[$n] = true;
        }
    }
    $followed = [];
    foreach ($followedDiscordIds as $did) {
        $k = gos_discord_media_follow_key((string) $did);
        if ($k === '') {
            $k = trim((string) $did);
        }
        if ($k !== '') {
            $followed[$k] = true;
        }
    }
    $seen = [];
    $fav = [];
    $rest = [];
    foreach ($items as $row) {
        if (!is_array($row)) {
            continue;
        }
        $id = (int) ($row['id'] ?? 0);
        if ($id <= 0 || isset($exclude[$id]) || isset($seen[$id])) {
            continue;
        }
        $seen[$id] = true;
        $did = trim((string) ($row['discordId'] ?? ''));
        if ($did === '' && isset($row['user']) && is_array($row['user'])) {
            $did = trim((string) ($row['user']['discordId'] ?? ''));
        }
        if ($did !== '' && isset($followed[$did])) {
            $fav[] = $row;
        } else {
            $rest[] = $row;
        }
    }
    $fav = gos_discord_shuffle($fav, $seed);
    $restSeed = $seed === null ? null : $seed + 17;
    $rest = gos_discord_shuffle($rest, $restSeed);
    $wantFav = $followed === [] ? 0 : (int) ceil($limit * GOS_DISCORD_MEDIA_FOLLOW_BIAS);
    $out = [];
    while (count($out) < $wantFav && $fav !== []) {
        $out[] = array_shift($fav);
    }
    while (count($out) < $limit && $rest !== []) {
        $out[] = array_shift($rest);
    }
    while (count($out) < $limit && $fav !== []) {
        $out[] = array_shift($fav);
    }
    return gos_discord_shuffle($out, $seed === null ? null : $seed + 31);
}

/**
 * @param list<array<string, mixed>> $items
 * @param array<int, bool> $likedIds
 * @param array<string, bool> $followedDiscordIds
 * @return list<array<string, mixed>>
 */
function gos_discord_media_annotate(array $items, array $likedIds, array $followedDiscordIds): array
{
    $out = [];
    foreach ($items as $row) {
        if (!is_array($row)) {
            continue;
        }
        $id = (int) ($row['id'] ?? 0);
        $did = '';
        if (isset($row['user']) && is_array($row['user'])) {
            $did = trim((string) ($row['user']['discordId'] ?? ''));
        }
        if ($did === '') {
            $did = trim((string) ($row['discordId'] ?? ''));
        }
        $liked = $id > 0 && isset($likedIds[$id]);
        $following = $did !== '' && isset($followedDiscordIds[$did]);
        $row['liked'] = $liked;
        $row['following'] = $following;
        if (isset($row['user']) && is_array($row['user'])) {
            $row['user']['following'] = $following;
        }
        $out[] = $row;
    }
    return $out;
}

function gos_discord_attachment_kind_sql(string $kind): string
{
    $ct = strtolower(trim($kind));
    return match ($ct) {
        'gif' => "(a.contentType = 'image/gif' OR a.filename LIKE '%.gif')",
        'video' => "(a.contentType LIKE 'video/%' OR a.filename LIKE '%.mp4' OR a.filename LIKE '%.mov' OR a.filename LIKE '%.webm' OR a.filename LIKE '%.mkv')",
        'audio' => "(a.contentType LIKE 'audio/%' OR a.contentType = 'application/ogg' OR a.filename LIKE '%.mp3' OR a.filename LIKE '%.ogg' OR a.filename LIKE '%.oga' OR a.filename LIKE '%.opus' OR a.filename LIKE '%.wav' OR a.filename LIKE '%.m4a' OR a.filename LIKE '%.flac')",
        'image' => "(a.contentType LIKE 'image/%' AND a.contentType <> 'image/gif' AND a.filename NOT LIKE '%.gif')",
        'playable', 'all', 'discogram' => "(a.contentType LIKE 'image/%' OR a.contentType LIKE 'video/%' OR a.contentType LIKE 'audio/%' OR a.contentType = 'application/ogg' OR a.filename LIKE '%.gif' OR a.filename LIKE '%.mp4' OR a.filename LIKE '%.mov' OR a.filename LIKE '%.webm' OR a.filename LIKE '%.mp3' OR a.filename LIKE '%.m4a' OR a.filename LIKE '%.ogg' OR a.filename LIKE '%.oga' OR a.filename LIKE '%.opus')",
        default => '',
    };
}

/**
 * @param list<int> $ids
 * @return array<int, bool>
 */
function gos_discord_media_liked_set(int $userId, array $ids): array
{
    if ($userId <= 0 || $ids === [] || !gos_discord_media_tables_ready()) {
        return [];
    }
    $clean = [];
    foreach ($ids as $id) {
        $n = (int) $id;
        if ($n > 0) {
            $clean[$n] = $n;
        }
    }
    if ($clean === []) {
        return [];
    }
    try {
        $in = implode(',', array_map('intval', array_values($clean)));
        $st = gos_pdo()->query(
            'SELECT attachment_id FROM discord_media_likes WHERE user_id = ' . (int) $userId
            . ' AND attachment_id IN (' . $in . ')'
        );
        if ($st === false) {
            return [];
        }
        $out = [];
        foreach ($st->fetchAll() as $row) {
            $id = (int) ($row['attachment_id'] ?? 0);
            if ($id > 0) {
                $out[$id] = true;
            }
        }
        return $out;
    } catch (Throwable) {
        return [];
    }
}

/**
 * @param list<string> $discordIds
 * @return array<string, bool>
 */
function gos_discord_media_followed_set(int $userId, array $discordIds): array
{
    if ($userId <= 0 || $discordIds === [] || !gos_discord_media_tables_ready()) {
        return [];
    }
    $clean = [];
    foreach ($discordIds as $did) {
        $k = gos_discord_media_follow_key((string) $did);
        if ($k !== '') {
            $clean[$k] = $k;
        }
    }
    if ($clean === []) {
        return [];
    }
    try {
        $pdo = gos_pdo();
        $in = implode(',', array_map(static fn ($v) => $pdo->quote($v), array_values($clean)));
        $st = $pdo->query(
            'SELECT discord_user_id FROM discord_media_follows WHERE user_id = ' . (int) $userId
            . ' AND discord_user_id IN (' . $in . ')'
        );
        if ($st === false) {
            return [];
        }
        $out = [];
        foreach ($st->fetchAll() as $row) {
            $id = (string) ($row['discord_user_id'] ?? '');
            if ($id !== '') {
                $out[$id] = true;
            }
        }
        return $out;
    } catch (Throwable) {
        return [];
    }
}

/**
 * @return list<string>
 */
function gos_discord_media_followed_ids(int $userId): array
{
    if ($userId <= 0 || !gos_discord_media_tables_ready()) {
        return [];
    }
    try {
        $st = gos_pdo()->prepare('SELECT discord_user_id FROM discord_media_follows WHERE user_id = ?');
        $st->execute([$userId]);
        $out = [];
        foreach ($st->fetchAll() as $row) {
            $id = gos_discord_media_follow_key((string) ($row['discord_user_id'] ?? ''));
            if ($id !== '') {
                $out[] = $id;
            }
        }
        return $out;
    } catch (Throwable) {
        return [];
    }
}

/**
 * @return list<int>
 */
function gos_discord_media_liked_ids(int $userId, int $max = 400): array
{
    if ($userId <= 0 || !gos_discord_media_tables_ready()) {
        return [];
    }
    try {
        $st = gos_pdo()->prepare(
            'SELECT attachment_id FROM discord_media_likes WHERE user_id = ? ORDER BY created_at DESC LIMIT ' . (int) $max
        );
        $st->execute([$userId]);
        $out = [];
        foreach ($st->fetchAll() as $row) {
            $id = (int) ($row['attachment_id'] ?? 0);
            if ($id > 0) {
                $out[] = $id;
            }
        }
        return $out;
    } catch (Throwable) {
        return [];
    }
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_media_set_like(array $body): array
{
    if (!gos_discord_media_tables_ready()) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'media_tables_missing'];
    }
    $userId = gos_discord_operator_id();
    if ($userId <= 0) {
        return ['ok' => false, 'status' => 401, 'data' => null, 'error' => 'auth_required'];
    }
    $attachmentId = (int) ($body['attachmentId'] ?? $body['id'] ?? 0);
    if ($attachmentId <= 0) {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'invalid_attachment'];
    }
    $flag = $body['liked'] ?? $body['like'] ?? true;
    $liked = $flag === true || $flag === 1 || $flag === '1' || $flag === 'true';
    try {
        $pdo = gos_pdo();
        if ($liked) {
            $pdo->prepare(
                'INSERT IGNORE INTO discord_media_likes (user_id, attachment_id) VALUES (?, ?)'
            )->execute([$userId, $attachmentId]);
        } else {
            $pdo->prepare(
                'DELETE FROM discord_media_likes WHERE user_id = ? AND attachment_id = ?'
            )->execute([$userId, $attachmentId]);
        }
        return ['ok' => true, 'status' => 200, 'data' => ['attachmentId' => $attachmentId, 'liked' => $liked], 'error' => null];
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'like_failed'];
    }
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_media_set_follow(array $body): array
{
    if (!gos_discord_media_tables_ready()) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'media_tables_missing'];
    }
    $userId = gos_discord_operator_id();
    if ($userId <= 0) {
        return ['ok' => false, 'status' => 401, 'data' => null, 'error' => 'auth_required'];
    }
    $discordUserId = gos_discord_media_follow_key((string) ($body['discordUserId'] ?? $body['discordId'] ?? $body['userId'] ?? ''));
    if ($discordUserId === '') {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'invalid_user'];
    }
    $flag = $body['following'] ?? $body['follow'] ?? true;
    $following = $flag === true || $flag === 1 || $flag === '1' || $flag === 'true';
    try {
        $pdo = gos_pdo();
        if ($following) {
            $pdo->prepare(
                'INSERT IGNORE INTO discord_media_follows (user_id, discord_user_id) VALUES (?, ?)'
            )->execute([$userId, $discordUserId]);
        } else {
            $pdo->prepare(
                'DELETE FROM discord_media_follows WHERE user_id = ? AND discord_user_id = ?'
            )->execute([$userId, $discordUserId]);
        }
        return [
            'ok' => true,
            'status' => 200,
            'data' => ['discordUserId' => $discordUserId, 'following' => $following],
            'error' => null,
        ];
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'follow_failed'];
    }
}

function gos_discord_ai_target_message_id(array $q): string
{
    $raw = trim((string) ($q['messageId'] ?? $q['discordMessageId'] ?? $q['discord_message_id'] ?? ''));
    if ($raw === '' || !preg_match('/^[0-9]{15,22}$/', $raw)) {
        return '';
    }
    return $raw;
}

/**
 * @param list<array<string, mixed>> $items
 * @return list<array<string, mixed>>
 */
function gos_discord_media_decorate(array $items): array
{
    $userId = gos_discord_operator_id();
    if ($userId <= 0 || $items === []) {
        return gos_discord_media_annotate($items, [], []);
    }
    $attIds = [];
    $dids = [];
    foreach ($items as $row) {
        if (!is_array($row)) {
            continue;
        }
        $id = (int) ($row['id'] ?? 0);
        if ($id > 0) {
            $attIds[] = $id;
        }
        $did = '';
        if (isset($row['user']) && is_array($row['user'])) {
            $did = (string) ($row['user']['discordId'] ?? '');
        }
        if ($did !== '') {
            $dids[] = $did;
        }
    }
    return gos_discord_media_annotate(
        $items,
        gos_discord_media_liked_set($userId, $attIds),
        gos_discord_media_followed_set($userId, $dids),
    );
}

const GOS_DISCORD_PLAYLIST_MAX = 2000;

function gos_discord_playlist_tables_ready(): bool
{
    return function_exists('gos_table_exists')
        && gos_table_exists('discord_media_playlists')
        && gos_table_exists('discord_media_playlist_items');
}

/**
 * @param list<int> $existing
 * @param list<int> $added
 * @return list<int>
 */
function gos_discord_playlist_merge_ids(array $existing, array $added, int $max = GOS_DISCORD_PLAYLIST_MAX): array
{
    $max = max(1, $max);
    $seen = [];
    $out = [];
    foreach (array_merge($existing, $added) as $id) {
        $n = (int) $id;
        if ($n <= 0 || isset($seen[$n])) {
            continue;
        }
        $seen[$n] = true;
        $out[] = $n;
        if (count($out) >= $max) {
            break;
        }
    }
    return $out;
}

/**
 * @return array{id:int,cursor:int,cursorAttachmentId:int,ids:list<int>}
 */
function gos_discord_playlist_empty(): array
{
    return ['id' => 0, 'cursor' => 0, 'cursorAttachmentId' => 0, 'ids' => []];
}

function gos_discord_playlist_guild_key(string $guildId): string
{
    if (function_exists('gos_discord_snowflake')) {
        return gos_discord_snowflake($guildId);
    }
    $s = trim($guildId);
    return preg_match('/^[0-9]{15,22}$/', $s) ? $s : '';
}

/**
 * @return array{id:int,cursor:int,cursorAttachmentId:int,ids:list<int>}
 */
function gos_discord_playlist_load(int $userId, string $guildId): array
{
    if ($userId <= 0 || !gos_discord_playlist_tables_ready()) {
        return gos_discord_playlist_empty();
    }
    $guildKey = gos_discord_playlist_guild_key($guildId);
    try {
        $pdo = gos_pdo();
        $st = $pdo->prepare(
            'SELECT id, cursor_index, cursor_attachment_id FROM discord_media_playlists
             WHERE user_id = ? AND guild_id = ? LIMIT 1'
        );
        $st->execute([$userId, $guildKey]);
        $row = $st->fetch();
        if (!is_array($row)) {
            $pdo->prepare(
                'INSERT IGNORE INTO discord_media_playlists (user_id, guild_id) VALUES (?, ?)'
            )->execute([$userId, $guildKey]);
            $st = $pdo->prepare(
                'SELECT id, cursor_index, cursor_attachment_id FROM discord_media_playlists
                 WHERE user_id = ? AND guild_id = ? LIMIT 1'
            );
            $st->execute([$userId, $guildKey]);
            $row = $st->fetch();
            if (!is_array($row)) {
                return gos_discord_playlist_empty();
            }
        }
        $id = (int) ($row['id'] ?? 0);
        if ($id <= 0) {
            return gos_discord_playlist_empty();
        }
        $items = $pdo->prepare(
            'SELECT attachment_id FROM discord_media_playlist_items WHERE playlist_id = ? ORDER BY position ASC'
        );
        $items->execute([$id]);
        $ids = [];
        foreach ($items->fetchAll() as $it) {
            $aid = (int) ($it['attachment_id'] ?? 0);
            if ($aid > 0) {
                $ids[] = $aid;
            }
        }
        return [
            'id' => $id,
            'cursor' => max(0, (int) ($row['cursor_index'] ?? 0)),
            'cursorAttachmentId' => max(0, (int) ($row['cursor_attachment_id'] ?? 0)),
            'ids' => $ids,
        ];
    } catch (Throwable) {
        return gos_discord_playlist_empty();
    }
}

/**
 * @param list<int> $attachmentIds
 * @return list<int> newly inserted ids in order
 */
function gos_discord_playlist_append(int $playlistId, array $attachmentIds): array
{
    if ($playlistId <= 0 || $attachmentIds === [] || !gos_discord_playlist_tables_ready()) {
        return [];
    }
    try {
        $pdo = gos_pdo();
        $st = $pdo->prepare('SELECT COALESCE(MAX(position), -1) FROM discord_media_playlist_items WHERE playlist_id = ?');
        $st->execute([$playlistId]);
        $pos = (int) $st->fetchColumn();
        $have = $pdo->prepare('SELECT COUNT(*) FROM discord_media_playlist_items WHERE playlist_id = ?');
        $have->execute([$playlistId]);
        $count = (int) $have->fetchColumn();
        $ins = $pdo->prepare(
            'INSERT IGNORE INTO discord_media_playlist_items (playlist_id, position, attachment_id) VALUES (?, ?, ?)'
        );
        $added = [];
        foreach ($attachmentIds as $id) {
            $n = (int) $id;
            if ($n <= 0) {
                continue;
            }
            if ($count >= GOS_DISCORD_PLAYLIST_MAX) {
                break;
            }
            $pos++;
            $ins->execute([$playlistId, $pos, $n]);
            if ($ins->rowCount() > 0) {
                $added[] = $n;
                $count++;
            }
        }
        if ($added !== []) {
            $pdo->prepare(
                'UPDATE discord_media_playlists SET updated_at = CURRENT_TIMESTAMP WHERE id = ?'
            )->execute([$playlistId]);
        }
        return $added;
    } catch (Throwable) {
        return [];
    }
}

function gos_discord_playlist_set_cursor(int $playlistId, int $index, int $attachmentId = 0): bool
{
    if ($playlistId <= 0 || !gos_discord_playlist_tables_ready()) {
        return false;
    }
    try {
        gos_pdo()->prepare(
            'UPDATE discord_media_playlists
             SET cursor_index = ?, cursor_attachment_id = ?, updated_at = CURRENT_TIMESTAMP
             WHERE id = ?'
        )->execute([max(0, $index), max(0, $attachmentId), $playlistId]);
        return true;
    } catch (Throwable) {
        return false;
    }
}

function gos_discord_playlist_cursor_index(array $ids, int $cursor, int $cursorAttachmentId): int
{
    if ($ids === []) {
        return 0;
    }
    if ($cursorAttachmentId > 0) {
        $at = array_search($cursorAttachmentId, $ids, true);
        if ($at !== false) {
            return (int) $at;
        }
    }
    return max(0, min($cursor, count($ids) - 1));
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_media_set_cursor(array $body): array
{
    if (!gos_discord_playlist_tables_ready()) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'playlist_tables_missing'];
    }
    $userId = gos_discord_operator_id();
    if ($userId <= 0) {
        return ['ok' => false, 'status' => 401, 'data' => null, 'error' => 'auth_required'];
    }
    $guildId = gos_discord_playlist_guild_key((string) ($body['guildId'] ?? ''));
    $index = max(0, (int) ($body['cursor'] ?? $body['index'] ?? 0));
    $attachmentId = max(0, (int) ($body['attachmentId'] ?? $body['id'] ?? 0));
    $pl = gos_discord_playlist_load($userId, $guildId);
    if ($pl['id'] <= 0) {
        return ['ok' => false, 'status' => 404, 'data' => null, 'error' => 'playlist_missing'];
    }
    $index = gos_discord_playlist_cursor_index($pl['ids'], $index, $attachmentId);
    if ($attachmentId <= 0 && isset($pl['ids'][$index])) {
        $attachmentId = (int) $pl['ids'][$index];
    }
    gos_discord_playlist_set_cursor($pl['id'], $index, $attachmentId);
    return [
        'ok' => true,
        'status' => 200,
        'data' => [
            'playlistId' => $pl['id'],
            'cursor' => $index,
            'attachmentId' => $attachmentId,
            'guildId' => $guildId,
        ],
        'error' => null,
    ];
}
