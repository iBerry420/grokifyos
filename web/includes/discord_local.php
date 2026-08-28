<?php

declare(strict_types=1);

/**
 * Fast MariaDB reads + local avatar/attachment serving for the phone Discord app.
 * Bypasses Node :4201 for list endpoints that scan Message / GuildAuditEvent.
 */

function gos_discord_avatar_dir(): string
{
    $override = trim((string) (gos_env('GROKIFY_DISCORD_AVATAR_DIR', '') ?? ''));
    if ($override !== '' && is_dir($override)) {
        return rtrim($override, '/');
    }
    return '/var/www/avalynn/uploads/audit-avatars';
}

function gos_discord_files_dir(): string
{
    $override = trim((string) (gos_env('GROKIFY_DISCORD_FILES_DIR', '') ?? ''));
    if ($override !== '' && is_dir($override)) {
        return rtrim($override, '/');
    }
    $primary = '/var/www/avalynn/uploads/discord-files';
    if (is_dir($primary) || @mkdir($primary, 0775, true)) {
        return $primary;
    }
    return '/var/www/avalynn/discord-backend/attachments';
}

function gos_discord_attachments_legacy_dir(): string
{
    return '/var/www/avalynn/discord-backend/attachments';
}

function gos_discord_media_secret(): string
{
    $pepper = trim((string) (gos_env('GROKIFY_SECRETS_PEPPER', '') ?? ''));
    if ($pepper !== '') {
        return $pepper;
    }
    return 'grokify-discord-media';
}

function gos_discord_media_exp(?int $now = null): int
{
    $now = $now ?? time();
    if ($now < 0) {
        $now = 0;
    }
    $ttl = 7 * 86400;
    $bucket = 12 * 3600;
    return intdiv($now + $ttl + $bucket - 1, $bucket) * $bucket;
}

function gos_discord_media_sig(string $payload, int $exp): string
{
    return hash_hmac('sha256', $payload . '|' . (string) $exp, gos_discord_media_secret());
}

function gos_discord_media_ok(string $payload, string $sig, int $exp): bool
{
    if ($exp < time() - 30 || $exp > time() + 30 * 86400) {
        return false;
    }
    $want = gos_discord_media_sig($payload, $exp);
    return hash_equals($want, strtolower($sig)) || hash_equals($want, $sig);
}

function gos_discord_public_base(): string
{
    return rtrim(gos_site_url(), '/') . '/api/discord.php';
}

function gos_discord_signed_url(array $query): string
{
    $exp = gos_discord_media_exp();
    $query['exp'] = (string) $exp;
    $kind = (string) ($query['action'] ?? '');
    if ($kind === 'avatar') {
        $payload = 'avatar|' . (string) ($query['user'] ?? '') . '|' . (string) ($query['hash'] ?? '') . '|' . (string) ($query['name'] ?? '');
    } else {
        $payload = 'file|' . (string) ($query['id'] ?? '');
    }
    $query['sig'] = gos_discord_media_sig($payload, $exp);
    $pairs = [];
    foreach ($query as $k => $v) {
        $s = trim((string) $v);
        if ($s === '') {
            continue;
        }
        $pairs[] = rawurlencode((string) $k) . '=' . rawurlencode($s);
    }
    return gos_discord_public_base() . '?' . implode('&', $pairs);
}

function gos_discord_avatar_hash(string $raw): string
{
    $s = trim($raw);
    if ($s === '') {
        return '';
    }
    if (preg_match('#/uploads/audit-avatars/([0-9]+)_([A-Za-z0-9_-]{1,64})\.[A-Za-z0-9]+#', $s, $m)) {
        return $m[2];
    }
    if (preg_match('#cdn\.discordapp\.com/avatars/[0-9]+/([A-Za-z0-9_-]{1,64})\.#', $s, $m)) {
        return $m[1];
    }
    if (preg_match('/^[A-Za-z0-9_-]{1,64}$/', $s)) {
        return $s;
    }
    return '';
}

function gos_discord_avatar_filename(string $discordId, string $hash): string
{
    $safe = preg_replace('/[^A-Za-z0-9_-]/', '_', $hash) ?? '';
    $safe = substr($safe, 0, 64);
    $ext = str_starts_with($hash, 'a_') ? 'gif' : 'png';
    return $discordId . '_' . $safe . '.' . $ext;
}

function gos_discord_avatar_url(string $discordId, string $avatar): string
{
    $discordId = gos_discord_snowflake($discordId);
    if ($discordId === '') {
        return '';
    }
    $hash = gos_discord_avatar_hash($avatar);
    $q = ['action' => 'avatar', 'user' => $discordId];
    if ($hash !== '') {
        $q['hash'] = $hash;
    } elseif (preg_match('#/uploads/audit-avatars/([A-Za-z0-9._-]+)$#', $avatar, $m)) {
        $q['name'] = $m[1];
    } elseif ($avatar === '') {
        return '';
    } else {
        $q['hash'] = substr(preg_replace('/[^A-Za-z0-9_-]/', '_', $avatar) ?? '', 0, 64);
    }
    return gos_discord_signed_url($q);
}

function gos_discord_file_url(int $id): string
{
    if ($id <= 0) {
        return '';
    }
    return gos_discord_signed_url(['action' => 'file', 'id' => (string) $id]);
}

function gos_discord_thumb_url(int $id): string
{
    if ($id <= 0) {
        return '';
    }
    return gos_discord_signed_url(['action' => 'file', 'id' => (string) $id, 'thumb' => '1']);
}

function gos_discord_thumbs_dir(): string
{
    $override = trim((string) (gos_env('GROKIFY_DISCORD_THUMB_DIR', '') ?? ''));
    if ($override !== '') {
        return rtrim($override, '/');
    }
    return rtrim(gos_root(), '/') . '/storage/discord-thumbs';
}

function gos_discord_ffmpeg_bin(): string
{
    foreach (['/usr/bin/ffmpeg', '/usr/local/bin/ffmpeg'] as $bin) {
        if (is_executable($bin)) {
            return $bin;
        }
    }
    return '';
}

/**
 * Discord CDN poster for a video attachment (media proxy JPEG).
 */
function gos_discord_cdn_poster_url(string $discordUrl): string
{
    $url = trim($discordUrl);
    if ($url === '' || !str_starts_with($url, 'https://')) {
        return '';
    }
    $host = strtolower((string) (parse_url($url, PHP_URL_HOST) ?? ''));
    if (!in_array($host, ['cdn.discordapp.com', 'media.discordapp.net'], true)) {
        return '';
    }
    $url = str_replace('https://cdn.discordapp.com/', 'https://media.discordapp.net/', $url);
    if (preg_match('/[?&]format=/i', $url)) {
        return $url;
    }
    $sep = str_contains($url, '?') ? '&' : '?';
    return $url . $sep . 'format=jpeg&width=480';
}

function gos_discord_parse_ts(string $raw): int
{
    $s = trim($raw);
    if ($s === '') {
        return 0;
    }
    if (preg_match('/^[0-9]{10,13}$/', $s)) {
        $n = (int) $s;
        if ($n > 10_000_000_000) {
            $n = intdiv($n, 1000);
        }
        return $n > 0 ? $n : 0;
    }
    $s = str_replace('T', ' ', $s);
    $s = preg_replace('/\.\d+/', '', $s) ?? $s;
    $s = preg_replace('/Z$/', '', $s) ?? $s;
    $s = preg_replace('/[+-]\d{2}:\d{2}$/', '', $s) ?? $s;
    $t = strtotime($s);
    return $t === false ? 0 : $t;
}

/**
 * Inclusive unix window around a timestamp. $seconds 0 = unbounded (both 0).
 *
 * @return array{0:int,1:int}
 */
function gos_discord_around_range(int $aroundTs, int $seconds): array
{
    if ($aroundTs <= 0) {
        return [0, 0];
    }
    if ($seconds <= 0) {
        return [0, 0];
    }
    return [$aroundTs - $seconds, $aroundTs + $seconds];
}

function gos_discord_sql_datetime(int $ts): string
{
    return gmdate('Y-m-d H:i:s', max(0, $ts));
}

function gos_discord_messages_id_search_clause(): string
{
    return '(u.discordId = ? OR m.messageId = ? OR m.channelId = ?)';
}

function gos_discord_http_get(string $url, int $timeout = 12): ?string
{
    if (!function_exists('curl_init')) {
        return null;
    }
    $host = strtolower((string) (parse_url($url, PHP_URL_HOST) ?? ''));
    if (!in_array($host, ['cdn.discordapp.com', 'media.discordapp.net'], true)) {
        return null;
    }
    $ch = curl_init($url);
    if ($ch === false) {
        return null;
    }
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS => 3,
        CURLOPT_TIMEOUT => max(3, $timeout),
        CURLOPT_CONNECTTIMEOUT => 3,
        CURLOPT_USERAGENT => 'GrokifyOS-Discord/1.0',
        CURLOPT_PROTOCOLS => CURLPROTO_HTTPS,
        CURLOPT_SSL_VERIFYPEER => true,
    ]);
    $raw = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($code !== 200 || !is_string($raw) || $raw === '') {
        return null;
    }
    if (strlen($raw) > 12 * 1024 * 1024) {
        return null;
    }
    return $raw;
}

/**
 * @return array{start:int,end:int,length:int,partial:bool}|null
 */
function gos_discord_parse_range(?string $header, int $size): ?array
{
    if ($size <= 0) {
        return null;
    }
    $end = $size - 1;
    $header = trim((string) $header);
    if ($header === '' || !preg_match('/bytes=(\d*)-(\d*)/', $header, $m)) {
        return [
            'start' => 0,
            'end' => $end,
            'length' => $size,
            'partial' => false,
        ];
    }
    $startRaw = $m[1];
    $endRaw = $m[2];
    if ($startRaw === '' && $endRaw === '') {
        return [
            'start' => 0,
            'end' => $end,
            'length' => $size,
            'partial' => false,
        ];
    }
    if ($startRaw === '') {
        $suffix = (int) $endRaw;
        if ($suffix <= 0) {
            return null;
        }
        $start = max(0, $size - $suffix);
        return [
            'start' => $start,
            'end' => $end,
            'length' => $end - $start + 1,
            'partial' => $start > 0,
        ];
    }
    $start = (int) $startRaw;
    $rangeEnd = $endRaw === '' ? $end : min($end, (int) $endRaw);
    if ($start > $rangeEnd || $start >= $size) {
        return null;
    }
    return [
        'start' => $start,
        'end' => $rangeEnd,
        'length' => $rangeEnd - $start + 1,
        'partial' => $start > 0 || $rangeEnd < $end,
    ];
}

function gos_discord_send_file(string $path, string $mime): never
{
    $size = (int) filesize($path);
    $header = isset($_SERVER['HTTP_RANGE']) ? (string) $_SERVER['HTTP_RANGE'] : null;
    $range = gos_discord_parse_range($header, $size);
    if ($range === null) {
        header('HTTP/1.1 416 Range Not Satisfiable');
        header('Content-Range: bytes */' . $size);
        exit;
    }
    if (function_exists('apache_setenv')) {
        @apache_setenv('no-gzip', '1');
    }
    @ini_set('zlib.output_compression', '0');
    header_remove('Content-Type');
    header('Content-Type: ' . $mime);
    header('Accept-Ranges: bytes');
    header('Cache-Control: private, max-age=86400');
    header('X-Content-Type-Options: nosniff');
    header('Content-Length: ' . (string) $range['length']);
    if ($range['partial']) {
        header('HTTP/1.1 206 Partial Content');
        header('Content-Range: bytes ' . $range['start'] . '-' . $range['end'] . '/' . $size);
    }
    $fp = fopen($path, 'rb');
    if ($fp === false) {
        exit;
    }
    if ($range['start'] > 0) {
        fseek($fp, $range['start']);
    }
    $left = $range['length'];
    while ($left > 0 && !feof($fp)) {
        $chunk = fread($fp, min(131072, $left));
        if ($chunk === false || $chunk === '') {
            break;
        }
        echo $chunk;
        $left -= strlen($chunk);
    }
    fclose($fp);
    exit;
}

function gos_discord_mime_from_name(string $name, string $fallback = 'application/octet-stream'): string
{
    $ext = strtolower(pathinfo($name, PATHINFO_EXTENSION));
    return match ($ext) {
        'png' => 'image/png',
        'jpg', 'jpeg' => 'image/jpeg',
        'gif' => 'image/gif',
        'webp' => 'image/webp',
        'mp4' => 'video/mp4',
        'webm' => 'video/webm',
        'mov' => 'video/quicktime',
        'mp3', 'mpeg' => 'audio/mpeg',
        'ogg', 'oga', 'opus' => 'audio/ogg',
        'wav' => 'audio/wav',
        'm4a' => 'audio/mp4',
        'pdf' => 'application/pdf',
        default => $fallback,
    };
}

function gos_discord_kind(string $contentType, string $filename): string
{
    $c = strtolower($contentType);
    $f = strtolower($filename);
    if ($c === 'image/gif' || str_ends_with($f, '.gif')) {
        return 'gif';
    }
    if (str_starts_with($c, 'video/') || preg_match('/\.(mp4|webm|mov|mkv)$/', $f)) {
        return 'video';
    }
    if (str_starts_with($c, 'audio/') || $c === 'application/ogg' || preg_match('/\.(mp3|ogg|oga|opus|wav|m4a|flac)$/', $f)) {
        return 'audio';
    }
    if (str_starts_with($c, 'image/') || preg_match('/\.(png|jpe?g|webp|bmp)$/', $f)) {
        return 'image';
    }
    return 'file';
}

function gos_discord_cdn_expiry(string $url): int
{
    if (preg_match('/[?&]ex=([0-9a-fA-F]+)/', $url, $m)) {
        return (int) hexdec($m[1]);
    }
    return 0;
}

function gos_discord_cdn_url_usable(string $url): bool
{
    $url = trim($url);
    if ($url === '' || !str_starts_with($url, 'https://')) {
        return false;
    }
    $exp = gos_discord_cdn_expiry($url);
    if ($exp <= 0) {
        return false;
    }
    return $exp >= time() + 5;
}

function gos_discord_query_flag(mixed $raw): bool
{
    if ($raw === true || $raw === 1) {
        return true;
    }
    $s = strtolower(trim((string) $raw));
    return $s === '1' || $s === 'true' || $s === 'yes' || $s === 'on';
}

/**
 * Cached on disk, or Discord CDN signature still live.
 *
 * @param array<string, mixed> $row
 */
function gos_discord_attachment_is_playable(array $row): bool
{
    if (trim((string) ($row['localPath'] ?? '')) !== '') {
        return true;
    }
    return gos_discord_cdn_url_usable((string) ($row['discordUrl'] ?? ''));
}

function gos_discord_attachment_playable_sql(string $alias = 'a'): string
{
    $a = preg_replace('/[^a-zA-Z0-9_]/', '', $alias) ?: 'a';
    return '('
        . "({$a}.localPath IS NOT NULL AND {$a}.localPath <> '')"
        . ' OR ('
        . "CONV(SUBSTRING_INDEX(SUBSTRING_INDEX(CONCAT({$a}.discordUrl, '&'), 'ex=', -1), '&', 1), 16, 10)"
        . ' > UNIX_TIMESTAMP() + 5'
        . '))';
}

function gos_discord_membership_cache_path(): string
{
    return rtrim(gos_root(), '/') . '/storage/discord-bot-guilds.json';
}

/**
 * @param array<string, mixed> $stored guildId => list of bot ids
 * @return array<string, array<int, true>>
 */
function gos_discord_membership_from_stored(array $stored): array
{
    $map = [];
    foreach ($stored as $gid => $ids) {
        if (!is_array($ids)) {
            continue;
        }
        foreach ($ids as $id) {
            $bid = (int) $id;
            if ($bid > 0) {
                $map[(string) $gid][$bid] = true;
            }
        }
    }
    return $map;
}

/**
 * Live Discord membership: botId => list of guild snowflakes.
 *
 * @param array<int|string, mixed> $botToGuildIds
 * @return array<string, array<int, true>> guildId => botId => true
 */
function gos_discord_membership_from_bot_guilds(array $botToGuildIds): array
{
    $map = [];
    foreach ($botToGuildIds as $botId => $guildIds) {
        $bid = (int) $botId;
        if ($bid <= 0 || !is_array($guildIds)) {
            continue;
        }
        foreach ($guildIds as $gidRaw) {
            $gid = function_exists('gos_discord_snowflake')
                ? gos_discord_snowflake($gidRaw)
                : (preg_match('/^[0-9]{5,32}$/', trim((string) $gidRaw)) ? trim((string) $gidRaw) : '');
            if ($gid === '') {
                continue;
            }
            $map[$gid][$bid] = true;
        }
    }
    return $map;
}

/**
 * @param mixed $data
 * @return list<string>
 */
function gos_discord_extract_guild_ids(mixed $data): array
{
    $rows = [];
    if (is_array($data)) {
        if (isset($data['guilds']) && is_array($data['guilds'])) {
            $rows = $data['guilds'];
        } elseif (array_is_list($data)) {
            $rows = $data;
        }
    }
    $ids = [];
    $seen = [];
    foreach ($rows as $row) {
        if (is_string($row) || is_int($row)) {
            $raw = (string) $row;
        } elseif (is_array($row)) {
            $raw = (string) ($row['guildId'] ?? $row['id'] ?? '');
        } else {
            continue;
        }
        $gid = function_exists('gos_discord_snowflake')
            ? gos_discord_snowflake($raw)
            : (preg_match('/^[0-9]{5,32}$/', trim($raw)) ? trim($raw) : '');
        if ($gid === '' || isset($seen[$gid])) {
            continue;
        }
        $seen[$gid] = true;
        $ids[] = $gid;
    }
    return $ids;
}

/**
 * Ask the running Discord clients (and REST when a bot is offline) which guilds
 * they are actually in. Historical audit/settings rows are not membership.
 *
 * @return array<string, array<int, true>>|null
 */
function gos_discord_refresh_live_membership(): ?array
{
    if (!function_exists('gos_discord_backend')) {
        return null;
    }
    $botsRes = gos_discord_backend('GET', '/api/discord/bots', null, 20);
    if (empty($botsRes['ok']) || !is_array($botsRes['data'])) {
        return null;
    }
    $bots = $botsRes['data'];
    if (!array_is_list($bots) && isset($bots['bots']) && is_array($bots['bots'])) {
        $bots = $bots['bots'];
    }
    if (!is_array($bots) || $bots === []) {
        return null;
    }
    $botToGuilds = [];
    $got = false;
    foreach ($bots as $bot) {
        if (!is_array($bot)) {
            continue;
        }
        $id = (int) ($bot['id'] ?? 0);
        if ($id <= 0) {
            continue;
        }
        $running = !empty($bot['isRunning']);
        $guildIds = [];
        $live = gos_discord_backend('GET', '/api/discord/bots/' . $id . '/guilds', null, 15);
        if (
            !empty($live['ok'])
            && is_array($live['data'])
            && (array_key_exists('running', $live['data']) || array_key_exists('guilds', $live['data']))
        ) {
            $guildIds = gos_discord_extract_guild_ids($live['data']);
            $got = true;
        } elseif ($running) {
            $legacy = gos_discord_backend('GET', '/api/discord/guilds?botId=' . $id, null, 15);
            if (!empty($legacy['ok']) && is_array($legacy['data'])) {
                $guildIds = gos_discord_extract_guild_ids($legacy['data']);
                $got = true;
            }
        } else {
            $rest = gos_discord_backend('GET', '/api/discord/tools/emojis/bot-guilds?botId=' . $id, null, 20);
            if (!empty($rest['ok']) && is_array($rest['data']) && !isset($rest['data']['error'])) {
                $guildIds = gos_discord_extract_guild_ids($rest['data']);
            }
            $got = true;
        }
        $botToGuilds[$id] = $guildIds;
    }
    if (!$got) {
        return null;
    }
    return gos_discord_membership_from_bot_guilds($botToGuilds);
}

/**
 * Live Discord client/REST membership. Never inferred from audit logs.
 *
 * @return array<string, array<int, true>> guildId => botId => true
 */
function gos_discord_bot_guild_membership(?PDO $pdo = null): array
{
    static $mem = null;
    static $at = 0;
    if (is_array($mem) && (time() - $at) < 120) {
        return $mem;
    }
    $path = gos_discord_membership_cache_path();
    $live = gos_discord_refresh_live_membership();
    if (is_array($live)) {
        $mem = $live;
        $at = time();
        $store = ['at' => $at, 'source' => 'live', 'map' => []];
        foreach ($live as $gid => $bots) {
            $store['map'][$gid] = array_map('intval', array_keys($bots));
        }
        @file_put_contents($path, json_encode($store), LOCK_EX);
        return $mem;
    }
    if (is_readable($path)) {
        $raw = json_decode((string) file_get_contents($path), true);
        if (
            is_array($raw)
            && ($raw['source'] ?? '') === 'live'
            && isset($raw['map'])
            && is_array($raw['map'])
        ) {
            $mem = gos_discord_membership_from_stored($raw['map']);
            $at = (int) ($raw['at'] ?? time());
            return $mem;
        }
    }
    $mem = [];
    $at = time();
    return $mem;
}

/**
 * Client-facing attachment. Always a local API URL — never a Discord CDN link.
 *
 * @param array<string, mixed> $row
 * @return array<string, mixed>
 */
function gos_discord_public_attachment(array $row): array
{
    $id = (int) ($row['id'] ?? 0);
    $filename = (string) ($row['filename'] ?? '');
    $ctype = (string) ($row['contentType'] ?? '');
    $localPath = trim((string) ($row['localPath'] ?? ''));
    $playable = gos_discord_attachment_is_playable($row);
    $discordMessageId = trim((string) ($row['discordMessageId'] ?? ''));
    $messageId = $discordMessageId !== '' ? $discordMessageId : (string) ($row['messageId'] ?? '');
    $created = trim((string) ($row['createdAt'] ?? ''));
    if ($created === '') {
        $created = trim((string) ($row['messageCreatedAt'] ?? ''));
    }
    $kind = gos_discord_kind($ctype, $filename);
    $out = [
        'id' => $id,
        'filename' => $filename,
        'contentType' => $ctype,
        'kind' => $kind,
        'url' => ($playable && $id > 0) ? gos_discord_file_url($id) : '',
        'thumbUrl' => ($kind === 'video' && $playable && $id > 0) ? gos_discord_thumb_url($id) : '',
        'local' => $localPath !== '',
        'playable' => $playable,
        'size' => (int) ($row['size'] ?? 0),
        'createdAt' => $created,
        'guildId' => (string) ($row['guildId'] ?? ''),
        'guildName' => (string) ($row['guildName'] ?? ''),
        'channelId' => (string) ($row['channelId'] ?? ''),
        'channelName' => (string) ($row['channelName'] ?? ''),
        'messageId' => $messageId,
        'discordAttachmentId' => gos_discord_attachment_cdn_id($row),
    ];
    if (isset($row['user']) && is_array($row['user'])) {
        $out['user'] = $row['user'];
    }
    return $out;
}

/**
 * Fill guild / channel / user / timestamp on attachments taken from a parent message.
 *
 * @param list<array<string, mixed>> $attachments
 * @param array<string, mixed> $origin
 * @return list<array<string, mixed>>
 */
function gos_discord_attach_message_origin(array $attachments, array $origin): array
{
    $out = [];
    foreach ($attachments as $att) {
        if (!is_array($att)) {
            continue;
        }
        foreach (['guildId', 'guildName', 'channelId', 'channelName'] as $k) {
            if (trim((string) ($att[$k] ?? '')) === '' && isset($origin[$k])) {
                $att[$k] = $origin[$k];
            }
        }
        if (trim((string) ($att['createdAt'] ?? '')) === '' && isset($origin['createdAt'])) {
            $att['createdAt'] = $origin['createdAt'];
        }
        if (trim((string) ($att['messageId'] ?? '')) === '' && isset($origin['discordMessageId'])) {
            $att['messageId'] = $origin['discordMessageId'];
        } elseif (isset($origin['discordMessageId']) && preg_match('/^[0-9]{15,22}$/', (string) $origin['discordMessageId'])) {
            $att['messageId'] = (string) $origin['discordMessageId'];
        }
        if ((!isset($att['user']) || !is_array($att['user'])) && isset($origin['user']) && is_array($origin['user'])) {
            $att['user'] = $origin['user'];
        }
        $out[] = $att;
    }
    return $out;
}

/**
 * @param array<string, mixed> $g
 * @param array<int, true> $memberBotIds
 * @param array<int, string> $botNames
 * @return array<string, mixed>
 */
function gos_discord_finalize_guild_bots(array $g, array $memberBotIds, array $botNames): array
{
    $bots = [];
    $have = [];
    foreach ($g['bots'] ?? [] as $br) {
        if (!is_array($br)) {
            continue;
        }
        $bid = (int) ($br['botId'] ?? 0);
        if ($bid <= 0 || !isset($memberBotIds[$bid])) {
            continue;
        }
        $br['botId'] = $bid;
        $br['name'] = $botNames[$bid] ?? (string) ($br['name'] ?? ('bot-' . $bid));
        $br['inherited'] = false;
        $bots[] = $br;
        $have[$bid] = true;
    }
    foreach ($memberBotIds as $bid => $_) {
        $bid = (int) $bid;
        if ($bid <= 0 || isset($have[$bid])) {
            continue;
        }
        $bots[] = [
            'botId' => $bid,
            'name' => $botNames[$bid] ?? ('bot-' . $bid),
            'isWatched' => (bool) ($g['isWatched'] ?? false),
            'respondToMentions' => (bool) ($g['respondToMentions'] ?? false),
            'respondToReplies' => (bool) ($g['respondToReplies'] ?? false),
            'semanticTagging' => (bool) ($g['semanticTagging'] ?? false),
            'analyzeFiles' => (bool) ($g['analyzeFiles'] ?? false),
            'inherited' => true,
        ];
    }
    usort($bots, static fn ($a, $b) => ((int) ($a['botId'] ?? 0)) <=> ((int) ($b['botId'] ?? 0)));
    $g['bots'] = $bots;
    foreach ($bots as $b) {
        if (!empty($b['isWatched'])) {
            $g['isWatched'] = true;
        }
        if (!empty($b['semanticTagging'])) {
            $g['semanticTagging'] = true;
        }
        if (!empty($b['analyzeFiles'])) {
            $g['analyzeFiles'] = true;
        }
    }
    return $g;
}

/**
 * @param mixed $raw comma/space-separated ids, or a list
 * @return list<int>
 */
function gos_discord_parse_id_list(mixed $raw): array
{
    if (is_array($raw)) {
        $parts = $raw;
    } else {
        $s = trim((string) $raw);
        if ($s === '') {
            return [];
        }
        $parts = preg_split('/[,\s]+/', $s) ?: [];
    }
    $ids = [];
    foreach ($parts as $p) {
        if (is_int($p) || is_float($p)) {
            $n = (int) $p;
        } else {
            $id = function_exists('gos_discord_id') ? gos_discord_id($p) : trim((string) $p);
            $n = $id !== '' && ctype_digit((string) $id) ? (int) $id : 0;
        }
        if ($n > 0) {
            $ids[$n] = $n;
        }
    }
    return array_values($ids);
}

/**
 * Keep guilds that include at least one selected bot, and trim the bot list to those.
 *
 * @param list<array<string, mixed>> $guilds
 * @param list<int> $botIds
 * @return list<array<string, mixed>>
 */
function gos_discord_filter_guilds_by_bots(array $guilds, array $botIds): array
{
    if ($botIds === []) {
        return $guilds;
    }
    $want = [];
    foreach ($botIds as $id) {
        $id = (int) $id;
        if ($id > 0) {
            $want[$id] = true;
        }
    }
    if ($want === []) {
        return $guilds;
    }
    $out = [];
    foreach ($guilds as $g) {
        if (!is_array($g)) {
            continue;
        }
        $keep = [];
        foreach ($g['bots'] ?? [] as $b) {
            if (!is_array($b)) {
                continue;
            }
            $bid = (int) ($b['botId'] ?? 0);
            if ($bid > 0 && isset($want[$bid])) {
                $keep[] = $b;
            }
        }
        if ($keep === []) {
            continue;
        }
        $g['bots'] = $keep;
        $watched = false;
        foreach ($keep as $b) {
            if (!empty($b['isWatched'])) {
                $watched = true;
                break;
            }
        }
        $g['isWatched'] = $watched;
        $out[] = $g;
    }
    return $out;
}

/**
 * @param list<array<string, mixed>> $activeBots
 * @param array<int, true> $memberBotIds
 * @param array<string, array<int, array<string, mixed>>> $byBot
 * @param array<string, array<string, mixed>> $global
 * @return list<array<string, mixed>>
 */
function gos_discord_channel_bot_rows(array $activeBots, array $memberBotIds, array $byBot, array $global, string $cid): array
{
    $botRows = [];
    foreach ($activeBots as $b) {
        $bid = (int) ($b['id'] ?? $b['botId'] ?? 0);
        if ($bid <= 0 || !isset($memberBotIds[$bid])) {
            continue;
        }
        $row = $byBot[$cid][$bid] ?? $global[$cid] ?? null;
        $enabled = $row ? (int) ($row['isEnabled'] ?? 1) === 1 : true;
        $muted = $row ? (int) ($row['isMuted'] ?? 0) === 1 : false;
        $botRows[] = [
            'botId' => $bid,
            'name' => (string) ($b['name'] ?? ('bot-' . $bid)),
            'isEnabled' => $enabled && !$muted,
            'isMuted' => $muted,
            'respondToAll' => $row ? (int) ($row['respondToAll'] ?? 0) === 1 : false,
            'hasOwnSettings' => isset($byBot[$cid][$bid]),
        ];
    }
    return $botRows;
}

/**
 * Audit event type from the phone query. `action` is the GrokifyOS dispatcher
 * verb (`audits`), so event filters must use eventAction / auditAction — or a
 * non-reserved `action` value such as message_delete.
 *
 * @param array<string, mixed> $q
 */
function gos_discord_audit_event_action(array $q): string
{
    $raw = trim((string) ($q['eventAction'] ?? $q['auditAction'] ?? ''));
    if ($raw === '') {
        $raw = trim((string) ($q['action'] ?? ''));
    }
    if ($raw === '' || strlen($raw) > 40 || !preg_match('/^[A-Za-z0-9_]+$/', $raw)) {
        return '';
    }
    static $reserved = [
        'audits' => true,
        'users' => true,
        'user' => true,
        'messages' => true,
        'attachments' => true,
        'media_like' => true,
        'media_follow' => true,
        'guilds' => true,
        'guild_settings' => true,
        'channels' => true,
        'live_channels' => true,
        'roles' => true,
        'bots' => true,
        'bot' => true,
        'bot_guilds' => true,
        'health' => true,
        'avatar' => true,
        'file' => true,
        'emoji' => true,
        'emojis_local' => true,
        'emojis_guild' => true,
        'emojis_bot_guilds' => true,
        'role_pickers' => true,
        'role_picker' => true,
        'captchas' => true,
        'captcha' => true,
        'captcha_attempts' => true,
    ];
    if (isset($reserved[strtolower($raw)])) {
        return '';
    }
    return $raw;
}

/**
 * @param array<string, mixed> $q
 * @param list<int> $botIds
 * @return array{sql:string,params:list<mixed>}
 */
function gos_discord_audits_select_sql(array $q, array $botIds): array
{
    $limit = gos_discord_int_range($q['limit'] ?? 40, 1, 100, 40);
    $offset = gos_discord_int_range($q['offset'] ?? 0, 0, 5_000_000, 0);
    $order = strtolower(trim((string) ($q['sort'] ?? $q['order'] ?? 'desc'))) === 'asc' ? 'ASC' : 'DESC';
    $guildId = gos_discord_snowflake($q['guildId'] ?? '');
    $action = gos_discord_audit_event_action($q);
    $tf = strtolower(trim((string) ($q['timeframe'] ?? '1d')));
    $seconds = gos_discord_timeframe_seconds($tf);
    $fromDate = trim((string) ($q['fromDate'] ?? $q['dateFrom'] ?? ''));
    $botId = function_exists('gos_discord_id') ? gos_discord_id($q['botId'] ?? '') : trim((string) ($q['botId'] ?? ''));
    $take = $limit + 1;
    $cols = 'e.id, e.botId, e.guildId, e.action, e.targetType, e.targetId, e.actorId, e.`before`, e.`after`, e.metadata, e.createdAt';
    $timeSql = '';
    $timeParams = [];
    if ($fromDate !== '' && preg_match('/^\d{4}-\d{2}-\d{2}/', $fromDate)) {
        $timeSql = 'e.createdAt >= ?';
        $timeParams[] = $fromDate;
    } elseif ($seconds > 0) {
        $timeSql = 'e.createdAt >= DATE_SUB(UTC_TIMESTAMP(3), INTERVAL ' . (int) $seconds . ' SECOND)';
    }

    $appendTime = static function (array $where, array $params) use ($timeSql, $timeParams): array {
        if ($timeSql !== '') {
            $where[] = $timeSql;
            foreach ($timeParams as $p) {
                $params[] = $p;
            }
        }
        return [$where, $params];
    };

    if ($guildId !== '' && $action !== '') {
        $where = ['e.guildId = ?', 'e.action = ?'];
        $params = [$guildId, $action];
        if ($botId !== '') {
            $where[] = 'e.botId = ?';
            $params[] = (int) $botId;
        }
        [$where, $params] = $appendTime($where, $params);
        $sql = "SELECT {$cols}
            FROM GuildAuditEvent e
            FORCE INDEX (GuildAuditEvent_guildId_action_createdAt_idx)
            WHERE " . implode(' AND ', $where) . "
            ORDER BY e.createdAt {$order}
            LIMIT {$take} OFFSET {$offset}";
        return ['sql' => $sql, 'params' => $params];
    }

    $ids = [];
    if ($botId !== '') {
        $bid = (int) $botId;
        $ids = in_array($bid, $botIds, true) ? [$bid] : $botIds;
    } else {
        $ids = $botIds;
    }
    if ($ids === []) {
        $sql = "SELECT {$cols} FROM GuildAuditEvent e WHERE 0 LIMIT 0";
        return ['sql' => $sql, 'params' => []];
    }

    if ($guildId !== '' && count($ids) === 1) {
        $where = ['e.botId = ?', 'e.guildId = ?'];
        $params = [(int) $ids[0], $guildId];
        [$where, $params] = $appendTime($where, $params);
        $sql = "SELECT {$cols}
            FROM GuildAuditEvent e
            FORCE INDEX (GuildAuditEvent_botId_createdAt_idx)
            WHERE " . implode(' AND ', $where) . "
            ORDER BY e.createdAt {$order}
            LIMIT {$take} OFFSET {$offset}";
        return ['sql' => $sql, 'params' => $params];
    }
    if ($guildId !== '' && count($ids) > 1) {
        $parts = [];
        $params = [];
        $eachTake = $offset + $take;
        foreach ($ids as $bid) {
            $w = ['e.botId = ?', 'e.guildId = ?'];
            $params[] = (int) $bid;
            $params[] = $guildId;
            if ($timeSql !== '') {
                $w[] = $timeSql;
                foreach ($timeParams as $p) {
                    $params[] = $p;
                }
            }
            $parts[] = "(SELECT {$cols}
                FROM GuildAuditEvent e
                FORCE INDEX (GuildAuditEvent_botId_createdAt_idx)
                WHERE " . implode(' AND ', $w) . "
                ORDER BY e.createdAt {$order}
                LIMIT {$eachTake})";
        }
        $sql = 'SELECT * FROM (' . implode(' UNION ALL ', $parts) . ") x
            ORDER BY createdAt {$order}
            LIMIT {$take} OFFSET {$offset}";
        return ['sql' => $sql, 'params' => $params];
    }

    if (count($ids) === 1) {
        $where = ['e.botId = ?'];
        $params = [(int) $ids[0]];
        if ($action !== '') {
            $where[] = 'e.action = ?';
            $params[] = $action;
        }
        [$where, $params] = $appendTime($where, $params);
        $sql = "SELECT {$cols}
            FROM GuildAuditEvent e
            FORCE INDEX (GuildAuditEvent_botId_createdAt_idx)
            WHERE " . implode(' AND ', $where) . "
            ORDER BY e.createdAt {$order}
            LIMIT {$take} OFFSET {$offset}";
        return ['sql' => $sql, 'params' => $params];
    }

    $parts = [];
    $params = [];
    $eachTake = $offset + $take;
    foreach ($ids as $bid) {
        $w = ['e.botId = ?'];
        $params[] = (int) $bid;
        if ($action !== '') {
            $w[] = 'e.action = ?';
            $params[] = $action;
        }
        if ($timeSql !== '') {
            $w[] = $timeSql;
            foreach ($timeParams as $p) {
                $params[] = $p;
            }
        }
        $parts[] = "(SELECT {$cols}
            FROM GuildAuditEvent e
            FORCE INDEX (GuildAuditEvent_botId_createdAt_idx)
            WHERE " . implode(' AND ', $w) . "
            ORDER BY e.createdAt {$order}
            LIMIT {$eachTake})";
    }
    $sql = 'SELECT * FROM (' . implode(' UNION ALL ', $parts) . ") x
        ORDER BY createdAt {$order}
        LIMIT {$take} OFFSET {$offset}";
    return ['sql' => $sql, 'params' => $params];
}

/**
 * @return array<string, mixed>
 */
function gos_discord_json_assoc(mixed $raw): array
{
    if (is_array($raw)) {
        return $raw;
    }
    if (!is_string($raw)) {
        return [];
    }
    $s = trim($raw);
    if ($s === '' || $s === 'null') {
        return [];
    }
    $decoded = json_decode($s, true);
    return is_array($decoded) ? $decoded : [];
}

/**
 * @param array<string, mixed> $obj
 */
function gos_discord_audit_plain(array $obj): string
{
    foreach (['content', 'value', 'nickname', 'roleName', 'name'] as $k) {
        if (!array_key_exists($k, $obj) || is_array($obj[$k])) {
            continue;
        }
        return (string) $obj[$k];
    }
    return '';
}

function gos_discord_local_attachment_id(mixed $idRaw): int
{
    if (is_int($idRaw) || (is_string($idRaw) && preg_match('/^[0-9]{1,10}$/', $idRaw))) {
        $n = (int) $idRaw;
        if ($n > 0 && $n < 2_000_000_000) {
            return $n;
        }
    }
    return 0;
}

/**
 * Discord CDN attachment snowflake from an audit/DB row (never a Prisma id).
 *
 * @param array<string, mixed> $row
 */
function gos_discord_attachment_cdn_id(array $row): string
{
    $idRaw = $row['id'] ?? '';
    if (is_string($idRaw) && preg_match('/^[0-9]{15,22}$/', $idRaw)) {
        return $idRaw;
    }
    foreach (['discordUrl', 'url', 'proxyUrl'] as $k) {
        $url = (string) ($row[$k] ?? '');
        if (preg_match('#/attachments/[0-9]+/([0-9]{15,22})/#', $url, $m)) {
            return $m[1];
        }
    }
    return '';
}

/**
 * @param array<string, mixed> $obj
 * @return list<array<string, mixed>>
 */
function gos_discord_audit_attachments(array $obj): array
{
    $raw = $obj['attachments'] ?? [];
    if (!is_array($raw)) {
        return [];
    }
    $out = [];
    foreach ($raw as $a) {
        if (!is_array($a)) {
            continue;
        }
        $id = gos_discord_local_attachment_id($a['id'] ?? 0);
        $filename = (string) ($a['filename'] ?? $a['name'] ?? '');
        $ctype = (string) ($a['contentType'] ?? '');
        $out[] = [
            'id' => $id,
            'filename' => $filename,
            'contentType' => $ctype,
            'kind' => gos_discord_kind($ctype, $filename),
            'url' => $id > 0 ? gos_discord_file_url($id) : '',
            'local' => $id > 0,
        ];
    }
    return $out;
}

/**
 * Map audit-JSON attachments onto cached MessageAttachment rows (Prisma id or CDN snowflake).
 *
 * @param list<mixed> $eventAtts
 * @param list<array<string, mixed>> $dbRows
 * @return list<array<string, mixed>>
 */
function gos_discord_merge_audit_attachments(array $eventAtts, array $dbRows, bool $fillIfEmpty = false): array
{
    $byId = [];
    $byName = [];
    $byCdn = [];
    $publicDb = [];
    foreach ($dbRows as $row) {
        if (!is_array($row)) {
            continue;
        }
        $pub = gos_discord_public_attachment($row);
        $pid = (int) ($pub['id'] ?? 0);
        if ($pid <= 0) {
            continue;
        }
        $publicDb[] = $pub;
        $byId[$pid] = $pub;
        $fn = strtolower((string) ($pub['filename'] ?? ''));
        if ($fn !== '' && !isset($byName[$fn])) {
            $byName[$fn] = $pub;
        }
        $cdn = gos_discord_attachment_cdn_id($row);
        if ($cdn !== '') {
            $byCdn[$cdn] = $pub;
        }
    }
    $clean = [];
    foreach ($eventAtts as $a) {
        if (is_array($a)) {
            $clean[] = $a;
        }
    }
    if ($clean === []) {
        return $fillIfEmpty ? array_values($publicDb) : [];
    }
    $out = [];
    $seen = [];
    foreach ($clean as $a) {
        $localId = gos_discord_local_attachment_id($a['id'] ?? 0);
        $cdn = gos_discord_attachment_cdn_id($a);
        $fn = strtolower((string) ($a['filename'] ?? $a['name'] ?? ''));
        $hit = null;
        if ($localId > 0 && isset($byId[$localId])) {
            $hit = $byId[$localId];
        } elseif ($cdn !== '' && isset($byCdn[$cdn])) {
            $hit = $byCdn[$cdn];
        } elseif ($fn !== '' && isset($byName[$fn])) {
            $hit = $byName[$fn];
        }
        if ($hit !== null) {
            $id = (int) ($hit['id'] ?? 0);
            if ($id > 0 && isset($seen[$id])) {
                continue;
            }
            if ($id > 0) {
                $seen[$id] = true;
            }
            $out[] = $hit;
            continue;
        }
        $formatted = gos_discord_audit_attachments(['attachments' => [$a]]);
        if ($formatted === []) {
            continue;
        }
        $item = $formatted[0];
        $id = (int) ($item['id'] ?? 0);
        if ($id > 0 && isset($seen[$id])) {
            continue;
        }
        if ($id > 0) {
            $seen[$id] = true;
        }
        if ($id <= 0 && trim((string) ($item['filename'] ?? '')) === '') {
            continue;
        }
        $out[] = $item;
    }
    return $out;
}

/**
 * @param array<string, mixed> $obj
 * @param array<string, mixed> $meta
 */
function gos_discord_audit_avatar_url(string $userId, array $obj, array $meta, string $side): string
{
    $key = $side === 'before' ? 'beforeAvatarPath' : 'afterAvatarPath';
    $path = trim((string) ($meta[$key] ?? ''));
    if ($path !== '') {
        return gos_discord_avatar_url($userId, $path);
    }
    $hash = trim((string) ($obj['avatar'] ?? ''));
    if ($hash === '' || $userId === '') {
        return '';
    }
    return gos_discord_avatar_url($userId, $hash);
}

/**
 * @param array<string, mixed> $row
 * @param array<string, mixed> $actor
 * @param array<string, mixed> $target
 * @param array<string, mixed> $guild
 * @param list<array<string, mixed>> $dbAttachments
 * @return array<string, mixed>
 */
function gos_discord_public_audit_event(array $row, array $actor = [], array $target = [], array $guild = [], array $dbAttachments = []): array
{
    $before = gos_discord_json_assoc($row['before'] ?? null);
    $after = gos_discord_json_assoc($row['after'] ?? null);
    $meta = gos_discord_json_assoc($row['metadata'] ?? null);
    $gid = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($row['guildId'] ?? '') : (string) ($row['guildId'] ?? '');
    $actorId = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($row['actorId'] ?? '') : (string) ($row['actorId'] ?? '');
    $targetId = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($row['targetId'] ?? '') : (string) ($row['targetId'] ?? '');
    $targetType = (string) ($row['targetType'] ?? '');
    $action = (string) ($row['action'] ?? '');
    $beforeRaw = $before['attachments'] ?? [];
    $afterRaw = $after['attachments'] ?? [];
    $beforeAtt = gos_discord_merge_audit_attachments(
        is_array($beforeRaw) ? $beforeRaw : [],
        $dbAttachments,
        $action === 'message_delete',
    );
    $afterAtt = gos_discord_merge_audit_attachments(
        is_array($afterRaw) ? $afterRaw : [],
        $dbAttachments,
        false,
    );
    $actorUsername = (string) ($actor['username'] ?? $meta['username'] ?? '');
    $actorDisplay = (string) ($actor['displayName'] ?? $meta['displayName'] ?? '');
    $targetUsername = (string) ($target['username'] ?? '');
    $targetDisplay = (string) ($target['displayName'] ?? '');
    if ($targetType === 'user') {
        if ($targetUsername === '') {
            $targetUsername = (string) ($meta['username'] ?? '');
        }
        if ($targetDisplay === '') {
            $targetDisplay = (string) ($meta['displayName'] ?? '');
        }
    }
    $avatarUser = $targetType === 'user' && $targetId !== '' ? $targetId : $actorId;
    $publicMeta = [];
    foreach (['channelName', 'channelId', 'username', 'displayName'] as $k) {
        if (isset($meta[$k]) && !is_array($meta[$k])) {
            $publicMeta[$k] = $meta[$k];
        }
    }
    $beforePublic = $before;
    unset($beforePublic['attachments'], $beforePublic['authorId']);
    if ($beforeAtt !== []) {
        $beforePublic['attachments'] = $beforeAtt;
    }
    $afterPublic = $after;
    unset($afterPublic['attachments']);
    if ($afterAtt !== []) {
        $afterPublic['attachments'] = $afterAtt;
    }
    return [
        'id' => (int) ($row['id'] ?? 0),
        'botId' => isset($row['botId']) && $row['botId'] !== null && $row['botId'] !== '' ? (int) $row['botId'] : null,
        'guildId' => $gid,
        'guildName' => (string) ($guild['guildName'] ?? ''),
        'guildIcon' => (string) ($guild['guildIcon'] ?? ''),
        'action' => $action,
        'targetType' => $targetType,
        'targetId' => $targetId,
        'actorId' => $actorId,
        'actorUsername' => $actorUsername,
        'actorDisplayName' => $actorDisplay,
        'actorAvatar' => gos_discord_avatar_url($actorId, (string) ($actor['avatar'] ?? '')),
        'targetUsername' => $targetUsername,
        'targetDisplayName' => $targetDisplay,
        'targetAvatar' => gos_discord_avatar_url($avatarUser, (string) ($target['avatar'] ?? '')),
        'before' => $beforePublic,
        'after' => $afterPublic,
        'metadata' => $publicMeta,
        'beforeText' => gos_discord_audit_plain($before),
        'afterText' => gos_discord_audit_plain($after),
        'beforeAvatar' => $action === 'avatar_change' ? gos_discord_audit_avatar_url($targetId, $before, $meta, 'before') : '',
        'afterAvatar' => $action === 'avatar_change' ? gos_discord_audit_avatar_url($targetId, $after, $meta, 'after') : '',
        'beforeAttachments' => $beforeAtt,
        'afterAttachments' => $afterAtt,
        'channelName' => (string) ($meta['channelName'] ?? ''),
        'channelId' => function_exists('gos_discord_snowflake') ? gos_discord_snowflake($meta['channelId'] ?? '') : (string) ($meta['channelId'] ?? ''),
        'createdAt' => (string) ($row['createdAt'] ?? ''),
    ];
}

/**
 * @param array<string, mixed> $q
 */
function gos_discord_media_authorized(array $q, string $payload): bool
{
    $sig = trim((string) ($q['sig'] ?? ''));
    $exp = (int) ($q['exp'] ?? 0);
    if ($sig !== '' && $exp > 0 && gos_discord_media_ok($payload, $sig, $exp)) {
        return true;
    }
    $auth = gos_auth_from_bearer();
    return $auth !== null;
}

function gos_discord_serve_avatar(array $q): never
{
    $user = gos_discord_snowflake($q['user'] ?? '');
    $hash = gos_discord_avatar_hash((string) ($q['hash'] ?? ''));
    $name = gos_discord_filename($q['name'] ?? '');
    $payload = 'avatar|' . $user . '|' . $hash . '|' . $name;
    if (!gos_discord_media_authorized($q, $payload)) {
        gos_api_json(['ok' => false, 'error' => 'auth_required'], 401);
    }
    $dir = gos_discord_avatar_dir();
    $realDir = realpath($dir);
    $candidates = [];
    if ($name !== '') {
        $candidates[] = $dir . '/' . $name;
    }
    if ($user !== '' && $hash !== '') {
        $candidates[] = $dir . '/' . gos_discord_avatar_filename($user, $hash);
        $gif = $dir . '/' . $user . '_' . $hash . '.gif';
        $png = $dir . '/' . $user . '_' . $hash . '.png';
        $candidates[] = $gif;
        $candidates[] = $png;
    }
    foreach ($candidates as $path) {
        $real = realpath($path);
        if ($real !== false && $realDir !== false && str_starts_with($real, $realDir . DIRECTORY_SEPARATOR) && is_file($real)) {
            gos_discord_send_file($real, gos_discord_mime_from_name($real, 'image/png'));
        }
    }
    if ($user !== '' && $hash !== '') {
        $cdn = 'https://cdn.discordapp.com/avatars/' . $user . '/' . $hash . '.' . (str_starts_with($hash, 'a_') ? 'gif' : 'png') . '?size=256';
        $bytes = gos_discord_http_get($cdn, 12);
        if ($bytes !== null) {
            if (!is_dir($dir)) {
                @mkdir($dir, 0775, true);
            }
            $dest = $dir . '/' . gos_discord_avatar_filename($user, $hash);
            @file_put_contents($dest, $bytes, LOCK_EX);
            @chmod($dest, 0664);
            if (is_file($dest)) {
                gos_discord_send_file($dest, gos_discord_mime_from_name($dest, 'image/png'));
            }
        }
    }
    gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
}

function gos_discord_resolve_attachment_path(?string $localPath, string $filename, int $id, string $fileHash = ''): ?string
{
    $tries = [];
    if (is_string($localPath) && $localPath !== '') {
        $tries[] = $localPath;
        $base = basename($localPath);
        $tries[] = gos_discord_attachments_legacy_dir() . '/' . $base;
        $tries[] = gos_discord_files_dir() . '/' . $base;
    }
    $safe = gos_discord_filename($filename);
    $hash = strtolower(preg_replace('/[^a-f0-9]/i', '', $fileHash) ?? '');
    if ($hash !== '' && $safe !== '') {
        $tries[] = gos_discord_files_dir() . '/' . $hash . '_' . $safe;
        $tries[] = gos_discord_attachments_legacy_dir() . '/' . $hash . '_' . $safe;
    }
    if ($safe !== '') {
        $tries[] = gos_discord_attachments_legacy_dir() . '/' . $safe;
        $tries[] = gos_discord_files_dir() . '/' . $safe;
    }
    if ($id > 0 && $safe !== '') {
        $tries[] = gos_discord_files_dir() . '/' . $id . '_' . $safe;
        $tries[] = gos_discord_attachments_legacy_dir() . '/' . $id . '_' . $safe;
    }
    foreach ($tries as $p) {
        if (is_string($p) && $p !== '' && is_file($p)) {
            return $p;
        }
    }
    return null;
}

function gos_discord_thumb_path(int $id): string
{
    return gos_discord_thumbs_dir() . '/' . $id . '.jpg';
}

function gos_discord_write_thumb_file(string $dest, string $bytes): bool
{
    if ($bytes === '' || strlen($bytes) < 32) {
        return false;
    }
    $dir = dirname($dest);
    if (!is_dir($dir)) {
        @mkdir($dir, 0775, true);
    }
    $ok = @file_put_contents($dest, $bytes, LOCK_EX);
    if ($ok === false) {
        return false;
    }
    @chmod($dest, 0664);
    return is_file($dest) && filesize($dest) > 32;
}

function gos_discord_ffmpeg_thumb(string $src, string $dest): bool
{
    $bin = gos_discord_ffmpeg_bin();
    if ($bin === '' || $src === '' || !is_file($src)) {
        return false;
    }
    $dir = dirname($dest);
    if (!is_dir($dir)) {
        @mkdir($dir, 0775, true);
    }
    $cmd = [
        $bin,
        '-hide_banner',
        '-loglevel',
        'error',
        '-ss',
        '0.4',
        '-i',
        $src,
        '-frames:v',
        '1',
        '-vf',
        'scale=480:-2',
        '-q:v',
        '4',
        '-y',
        $dest,
    ];
    $pipes = [];
    $proc = @proc_open(
        $cmd,
        [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']],
        $pipes,
        null,
        null,
        ['bypass_shell' => true],
    );
    if (!is_resource($proc)) {
        return false;
    }
    fclose($pipes[0]);
    stream_set_timeout($pipes[1], 8);
    stream_set_timeout($pipes[2], 8);
    stream_get_contents($pipes[1]);
    stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    $code = proc_close($proc);
    return $code === 0 && is_file($dest) && filesize($dest) > 32;
}

function gos_discord_serve_thumb(int $id, string $filename, string $mime, ?string $path, string $discordUrl): never
{
    $kind = gos_discord_kind($mime, $filename);
    if ($kind !== 'video') {
        gos_api_json(['ok' => false, 'error' => 'not_video'], 404);
    }
    $dest = gos_discord_thumb_path($id);
    if (is_file($dest) && filesize($dest) > 32) {
        gos_discord_send_file($dest, 'image/jpeg');
    }
    if ($path !== null && gos_discord_ffmpeg_thumb($path, $dest) && is_file($dest)) {
        gos_discord_send_file($dest, 'image/jpeg');
    }
    $poster = gos_discord_cdn_poster_url($discordUrl);
    if ($poster !== '' && gos_discord_cdn_url_usable($discordUrl)) {
        $bytes = gos_discord_http_get($poster, 8);
        if (is_string($bytes) && gos_discord_write_thumb_file($dest, $bytes)) {
            gos_discord_send_file($dest, 'image/jpeg');
        }
    }
    gos_api_json(['ok' => false, 'error' => 'thumb_unavailable'], 404);
}

function gos_discord_serve_file(array $q): never
{
    $id = (int) ($q['id'] ?? 0);
    if ($id <= 0) {
        gos_api_json(['ok' => false, 'error' => 'invalid_id'], 400);
    }
    if (!gos_discord_media_authorized($q, 'file|' . (string) $id)) {
        gos_api_json(['ok' => false, 'error' => 'auth_required'], 401);
    }
    $pdo = gos_discord_avalynn_pdo();
    if ($pdo === null) {
        gos_api_json(['ok' => false, 'error' => 'db_unavailable'], 503);
    }
    $stmt = $pdo->prepare('SELECT id, filename, contentType, discordUrl, localPath, fileHash FROM MessageAttachment WHERE id = ? LIMIT 1');
    $stmt->execute([$id]);
    $row = $stmt->fetch();
    if (!is_array($row)) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    $filename = (string) ($row['filename'] ?? 'file');
    $mime = (string) ($row['contentType'] ?? '');
    if ($mime === '' || $mime === '0') {
        $mime = gos_discord_mime_from_name($filename);
    }
    $named = gos_discord_mime_from_name($filename, $mime);
    if ($named === 'audio/ogg') {
        $mime = 'audio/ogg';
    }
    $path = gos_discord_resolve_attachment_path(
        isset($row['localPath']) ? (string) $row['localPath'] : null,
        $filename,
        $id,
        (string) ($row['fileHash'] ?? ''),
    );
    if (gos_discord_query_flag($q['thumb'] ?? false)) {
        gos_discord_serve_thumb($id, $filename, $mime, $path, (string) ($row['discordUrl'] ?? ''));
    }
    if ($path !== null) {
        gos_discord_send_file($path, $mime);
    }
    $url = trim((string) ($row['discordUrl'] ?? ''));
    if (!gos_discord_cdn_url_usable($url)) {
        gos_api_json(['ok' => false, 'error' => 'cdn_expired'], 404);
    }
    $bytes = gos_discord_http_get($url, 6);
    if ($bytes === null) {
        gos_api_json(['ok' => false, 'error' => 'cdn_unavailable'], 502);
    }
    $dir = gos_discord_files_dir();
    if (!is_dir($dir)) {
        @mkdir($dir, 0775, true);
    }
    $safe = gos_discord_filename($filename);
    if ($safe === '') {
        $safe = 'att-' . $id . '.bin';
    }
    $dest = $dir . '/' . $id . '_' . $safe;
    @file_put_contents($dest, $bytes, LOCK_EX);
    @chmod($dest, 0664);
    try {
        $upd = $pdo->prepare('UPDATE MessageAttachment SET localPath = ? WHERE id = ?');
        $upd->execute([$dest, $id]);
    } catch (Throwable) {
    }
    if (is_file($dest)) {
        gos_discord_send_file($dest, $mime);
    }
    gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
}

/**
 * @param array<string, mixed>|list<mixed> $data
 * @return array<string, mixed>|list<mixed>
 */
function gos_discord_rewrite_tree(mixed $data): mixed
{
    if (!is_array($data)) {
        return $data;
    }
    $isUserish = isset($data['avatar']) && (isset($data['discordId']) || isset($data['username']));
    if ($isUserish) {
        $did = (string) ($data['discordId'] ?? '');
        $data['avatar'] = gos_discord_avatar_url($did, (string) ($data['avatar'] ?? ''));
    }
    $isAtt = isset($data['filename']) && (isset($data['discordUrl']) || isset($data['url']) || isset($data['proxyUrl']));
    if ($isAtt && isset($data['id'])) {
        $fid = (int) $data['id'];
        if ($fid > 0) {
            $local = gos_discord_file_url($fid);
            $data['url'] = $local;
            $data['proxyUrl'] = $local;
            $data['kind'] = gos_discord_kind((string) ($data['contentType'] ?? ''), (string) ($data['filename'] ?? ''));
            if ($data['kind'] === 'video') {
                $data['thumbUrl'] = gos_discord_thumb_url($fid);
            }
            unset($data['discordUrl']);
        }
    }
    foreach ($data as $k => $v) {
        if (is_array($v)) {
            $data[$k] = gos_discord_rewrite_tree($v);
        }
    }
    return $data;
}

function gos_discord_int_range(mixed $raw, int $min, int $max, int $fallback): int
{
    $n = (int) $raw;
    if ($n < $min) {
        return $fallback;
    }
    return min($max, max($min, $n));
}

function gos_discord_timeframe_seconds(string $tf): int
{
    return match (strtolower(trim($tf))) {
        '1h' => 3600,
        '6h' => 6 * 3600,
        '12h' => 12 * 3600,
        '1d', '24h' => 86400,
        '3d' => 3 * 86400,
        '7d', '1w' => 7 * 86400,
        '30d', '1m' => 30 * 86400,
        '3m' => 90 * 86400,
        '6m' => 180 * 86400,
        '1y' => 365 * 86400,
        'all' => 0,
        default => 86400,
    };
}

/**
 * @return list<int>
 */
function gos_discord_active_bot_ids(PDO $pdo): array
{
    try {
        $bl = $pdo->query('SELECT id FROM discord_bots WHERE is_active = 1');
        if ($bl === false) {
            return [];
        }
        $ids = [];
        foreach ($bl->fetchAll() as $b) {
            $ids[] = (int) $b['id'];
        }
        return $ids;
    } catch (Throwable) {
        return [];
    }
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_users(array $q): array
{
    $pdo = gos_discord_avalynn_pdo();
    if ($pdo === null) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'db_unavailable'];
    }
    try {
        return gos_discord_local_users_run($pdo, $q);
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'users_query_failed'];
    }
}

/**
 * @param array<string, mixed> $q
 * @return array{sql:string,params:list<mixed>,kind:string,sortCol:string,order:string,search:string,guildId:string,limit:int,offset:int,take:int}
 */
function gos_discord_users_select_sql(array $q): array
{
    $limit = gos_discord_int_range($q['limit'] ?? 40, 1, 100, 40);
    $offset = gos_discord_int_range($q['offset'] ?? 0, 0, 1_000_000, 0);
    $sortKey = strtolower(trim((string) ($q['sort'] ?? 'lastActive')));
    $sortMap = [
        'lastactive' => 'lastActivity',
        'lastactivity' => 'lastActivity',
        'level' => 'level',
        'username' => 'username',
        'createdat' => 'createdAt',
        'totalxp' => 'totalXp',
        'activityscore' => 'activityScore',
    ];
    $sortCol = $sortMap[$sortKey] ?? 'lastActivity';
    $order = strtolower(trim((string) ($q['order'] ?? 'desc'))) === 'asc' ? 'ASC' : 'DESC';
    $search = trim((string) ($q['search'] ?? ''));
    if (str_starts_with($search, '@')) {
        $search = ltrim($search, '@');
    }
    if (strlen($search) > 80) {
        $search = substr($search, 0, 80);
    }
    $guildId = gos_discord_snowflake($q['guildId'] ?? '');
    $take = $limit + 1;

    if ($guildId !== '' && $search === '') {
        $fetch = 2000;
        $sql = "SELECT discordId, username, avatar, createdAt
            FROM GuildMemberEvent
            WHERE guildId = ?
            ORDER BY createdAt DESC
            LIMIT {$fetch}";
        return [
            'sql' => $sql,
            'params' => [$guildId],
            'kind' => 'member_ids',
            'sortCol' => $sortCol,
            'order' => $order,
            'search' => $search,
            'guildId' => $guildId,
            'limit' => $limit,
            'offset' => $offset,
            'take' => $take,
        ];
    }

    $where = [];
    $params = [];
    if ($search !== '') {
        if (preg_match('/^[0-9]{5,32}$/', $search)) {
            $where[] = 'discordId = ?';
            $params[] = $search;
        } else {
            $where[] = 'username LIKE ?';
            $params[] = $search . '%';
        }
    }
    $sqlOffset = $offset;
    $sqlTake = $take;
    if ($guildId !== '' && $search !== '') {
        $sqlOffset = 0;
        $sqlTake = max($take, 80);
    }
    $sqlWhere = $where === [] ? '' : ('WHERE ' . implode(' AND ', $where));
    $sql = "SELECT id, discordId, username, displayName, avatar, level, totalXp, lastActivity, createdAt
            FROM User {$sqlWhere}
            ORDER BY {$sortCol} {$order}
            LIMIT {$sqlTake} OFFSET {$sqlOffset}";
    return [
        'sql' => $sql,
        'params' => $params,
        'kind' => 'user',
        'sortCol' => $sortCol,
        'order' => $order,
        'search' => $search,
        'guildId' => $guildId,
        'limit' => $limit,
        'offset' => $offset,
        'take' => $take,
    ];
}

/**
 * @param array<string, mixed> $row
 */
function gos_discord_user_in_guild(PDO $pdo, string $guildId, array $row): bool
{
    if ($guildId === '') {
        return true;
    }
    $id = (int) ($row['id'] ?? 0);
    $did = (string) ($row['discordId'] ?? '');
    if ($id > 0) {
        $st = $pdo->prepare('SELECT 1 FROM Message WHERE userId = ? AND guildId = ? LIMIT 1');
        $st->execute([$id, $guildId]);
        if ($st->fetchColumn()) {
            return true;
        }
    }
    if ($did !== '') {
        $st = $pdo->prepare('SELECT 1 FROM GuildMemberEvent WHERE discordId = ? AND guildId = ? LIMIT 1');
        $st->execute([$did, $guildId]);
        if ($st->fetchColumn()) {
            return true;
        }
    }
    return false;
}

/**
 * @param array<string, mixed> $q
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_users_run(PDO $pdo, array $q): array
{
    $built = gos_discord_users_select_sql($q);
    $limit = (int) $built['limit'];
    $offset = (int) $built['offset'];
    $take = (int) $built['take'];
    $sortKey = strtolower(trim((string) ($q['sort'] ?? 'lastActive')));
    $order = (string) $built['order'];
    $search = (string) $built['search'];
    $guildId = (string) $built['guildId'];

    if ($built['kind'] === 'member_ids') {
        $stmt = $pdo->prepare($built['sql']);
        $stmt->execute($built['params']);
        $eventRows = $stmt->fetchAll();
        if (!is_array($eventRows)) {
            $eventRows = [];
        }
        $seen = [];
        $ordered = [];
        $meta = [];
        foreach ($eventRows as $er) {
            $did = (string) ($er['discordId'] ?? '');
            if ($did === '' || isset($seen[$did])) {
                continue;
            }
            $seen[$did] = true;
            $ordered[] = $did;
            $meta[$did] = $er;
        }
        $pageIds = array_slice($ordered, $offset, $take);
        $hasMore = count($ordered) > $offset + $limit;
        if (count($pageIds) > $limit) {
            array_pop($pageIds);
            $hasMore = true;
        }
        $byDid = [];
        if ($pageIds !== []) {
            $in = implode(',', array_map(static fn ($v) => $pdo->quote((string) $v), $pageIds));
            $ustmt = $pdo->query(
                "SELECT id, discordId, username, displayName, avatar, level, totalXp, lastActivity, createdAt
                 FROM User WHERE discordId IN ({$in})"
            );
            if ($ustmt !== false) {
                foreach ($ustmt->fetchAll() as $u) {
                    $byDid[(string) $u['discordId']] = $u;
                }
            }
        }
        $rows = [];
        foreach ($pageIds as $did) {
            if (isset($byDid[$did])) {
                $rows[] = $byDid[$did];
                continue;
            }
            $m = $meta[$did] ?? [];
            $rows[] = [
                'id' => 0,
                'discordId' => $did,
                'username' => (string) ($m['username'] ?? ''),
                'displayName' => null,
                'avatar' => (string) ($m['avatar'] ?? ''),
                'level' => 0,
                'totalXp' => 0,
                'lastActivity' => (string) ($m['createdAt'] ?? ''),
                'createdAt' => (string) ($m['createdAt'] ?? ''),
            ];
        }
    } else {
        $stmt = $pdo->prepare($built['sql']);
        $stmt->execute($built['params']);
        $rows = $stmt->fetchAll();
        if (!is_array($rows)) {
            $rows = [];
        }
        if ($guildId !== '' && $search !== '') {
            $kept = [];
            foreach ($rows as $row) {
                if (gos_discord_user_in_guild($pdo, $guildId, $row)) {
                    $kept[] = $row;
                }
            }
            $hasMore = count($kept) > $offset + $limit;
            $rows = array_slice($kept, $offset, $limit);
        } else {
            $hasMore = count($rows) > $limit;
            if ($hasMore) {
                array_pop($rows);
            }
        }
    }
    $ids = [];
    foreach ($rows as $row) {
        $id = (int) ($row['id'] ?? 0);
        if ($id > 0) {
            $ids[] = $id;
        }
    }
    $counts = [];
    if ($ids !== []) {
        $in = implode(',', array_map('intval', $ids));
        try {
            $cstmt = $pdo->query("SELECT userId, COUNT(*) AS c FROM Message WHERE userId IN ({$in}) GROUP BY userId");
            if ($cstmt !== false) {
                foreach ($cstmt->fetchAll() as $cr) {
                    $counts[(int) $cr['userId']] = (int) $cr['c'];
                }
            }
        } catch (Throwable) {
            $counts = [];
        }
    }
    $users = [];
    foreach ($rows as $row) {
        $id = (int) ($row['id'] ?? 0);
        $did = (string) ($row['discordId'] ?? '');
        $users[] = [
            'id' => $id,
            'discordId' => $did,
            'username' => (string) ($row['username'] ?? ''),
            'displayName' => $row['displayName'],
            'avatar' => gos_discord_avatar_url($did, (string) ($row['avatar'] ?? '')),
            'level' => (int) ($row['level'] ?? 0),
            'totalXp' => (int) ($row['totalXp'] ?? 0),
            'messageCount' => $counts[$id] ?? 0,
            'lastActive' => (string) ($row['lastActivity'] ?? ''),
            'lastActivity' => (string) ($row['lastActivity'] ?? ''),
            'createdAt' => (string) ($row['createdAt'] ?? ''),
        ];
    }
    $total = $offset + count($users) + ($hasMore ? 1 : 0);
    if ($search === '' && $offset === 0 && $guildId === '') {
        try {
            $est = $pdo->query("SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'User'");
            if ($est !== false) {
                $n = (int) ($est->fetchColumn() ?: 0);
                if ($n > 0) {
                    $total = $n;
                }
            }
        } catch (Throwable) {
        }
    }
    return [
        'ok' => true,
        'status' => 200,
        'data' => [
            'users' => $users,
            'total' => $total,
            'limit' => $limit,
            'offset' => $offset,
            'hasMore' => $hasMore,
            'sort' => $sortKey,
            'order' => strtolower($order),
        ],
        'error' => null,
    ];
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_audits(array $q): array
{
    $pdo = gos_discord_avalynn_pdo();
    if ($pdo === null) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'db_unavailable'];
    }
    try {
        return gos_discord_local_audits_run($pdo, $q);
    } catch (Throwable $e) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'audits_query_failed'];
    }
}

/**
 * @param array<string, mixed> $q
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_audits_run(PDO $pdo, array $q): array
{
    $limit = gos_discord_int_range($q['limit'] ?? 40, 1, 100, 40);
    $offset = gos_discord_int_range($q['offset'] ?? 0, 0, 5_000_000, 0);
    $tf = strtolower(trim((string) ($q['timeframe'] ?? '1d')));
    $botIds = gos_discord_active_bot_ids($pdo);
    $built = gos_discord_audits_select_sql($q, $botIds);
    $take = $limit + 1;
    try {
        $stmt = $pdo->prepare($built['sql']);
        $stmt->execute($built['params']);
    } catch (Throwable) {
        $fallback = preg_replace('/FORCE INDEX \([^)]+\)/i', '', $built['sql']) ?? $built['sql'];
        $stmt = $pdo->prepare($fallback);
        $stmt->execute($built['params']);
    }
    $rows = $stmt->fetchAll();
    if (!is_array($rows)) {
        $rows = [];
    }
    $hasMore = count($rows) > $limit;
    if ($hasMore) {
        array_pop($rows);
    }
    $guildIds = [];
    $userIds = [];
    $msgIds = [];
    foreach ($rows as $row) {
        $gid = (string) ($row['guildId'] ?? '');
        if ($gid !== '') {
            $guildIds[$gid] = true;
        }
        foreach (['actorId', 'targetId'] as $uk) {
            $uid = (string) ($row[$uk] ?? '');
            if ($uid !== '') {
                $userIds[$uid] = true;
            }
        }
        $act = (string) ($row['action'] ?? '');
        if ($act === 'message_delete' || $act === 'message_edit') {
            $mid = function_exists('gos_discord_snowflake')
                ? gos_discord_snowflake($row['targetId'] ?? '')
                : (string) ($row['targetId'] ?? '');
            if ($mid !== '') {
                $msgIds[$mid] = true;
            }
        }
    }
    $attByMsg = [];
    if ($msgIds !== []) {
        try {
            $in = implode(',', array_map(static fn ($v) => $pdo->quote((string) $v), array_keys($msgIds)));
            $ast = $pdo->query(
                "SELECT m.messageId, a.id, a.filename, a.contentType, a.discordUrl, a.localPath, a.fileHash, a.size
                 FROM MessageAttachment a
                 INNER JOIN Message m ON m.id = a.messageId
                 WHERE m.messageId IN ({$in})",
            );
            if ($ast !== false) {
                foreach ($ast->fetchAll() as $a) {
                    $mid = (string) ($a['messageId'] ?? '');
                    if ($mid === '') {
                        continue;
                    }
                    $attByMsg[$mid][] = $a;
                }
            }
        } catch (Throwable) {
            $attByMsg = [];
        }
    }
    $guildMap = [];
    if ($guildIds !== []) {
        $gIn = implode(',', array_map(static fn ($v) => $pdo->quote((string) $v), array_keys($guildIds)));
        $gs = $pdo->query("SELECT guildId, guildName, guildIcon FROM GuildSettings WHERE botId IS NULL AND guildId IN ({$gIn})");
        if ($gs !== false) {
            foreach ($gs->fetchAll() as $g) {
                $guildMap[(string) $g['guildId']] = $g;
            }
        }
    }
    $userMap = [];
    if ($userIds !== []) {
        $uIn = implode(',', array_map(static fn ($v) => $pdo->quote((string) $v), array_keys($userIds)));
        $us = $pdo->query("SELECT discordId, username, displayName, avatar FROM User WHERE discordId IN ({$uIn})");
        if ($us !== false) {
            foreach ($us->fetchAll() as $u) {
                $userMap[(string) $u['discordId']] = $u;
            }
        }
    }
    $events = [];
    foreach ($rows as $row) {
        $gid = (string) ($row['guildId'] ?? '');
        $actorId = (string) ($row['actorId'] ?? '');
        $targetId = (string) ($row['targetId'] ?? '');
        $msgKey = function_exists('gos_discord_snowflake') ? gos_discord_snowflake($targetId) : $targetId;
        $events[] = gos_discord_public_audit_event(
            $row,
            $userMap[$actorId] ?? [],
            $userMap[$targetId] ?? [],
            $guildMap[$gid] ?? [],
            $attByMsg[$msgKey] ?? [],
        );
    }
    return [
        'ok' => true,
        'status' => 200,
        'data' => [
            'events' => $events,
            'total' => $offset + count($events) + ($hasMore ? 1 : 0),
            'hasMore' => $hasMore,
            'limit' => $limit,
            'offset' => $offset,
            'timeframe' => $tf,
        ],
        'error' => null,
    ];
}

function gos_discord_is_thread_type(int $type): bool
{
    return in_array($type, [10, 11, 12], true);
}

function gos_discord_is_guild_channel_type(int $type): bool
{
    return in_array($type, [2, 4, 5, 13, 15, 16], true);
}

function gos_discord_name_looks_like_thread(string $name): bool
{
    $n = trim($name);
    if ($n === '') {
        return false;
    }
    if (preg_match('/\s/u', $n) === 1) {
        return true;
    }
    $lower = strtolower($n);
    if (str_starts_with($lower, 'http://') || str_starts_with($lower, 'https://')) {
        return true;
    }
    $slug = $n;
    if (preg_match('/^(?:.*[┃|｜])(.+)$/u', $n, $m) === 1) {
        $slug = trim((string) $m[1]);
    }
    if ($slug !== '' && preg_match('/^[a-z0-9](?:[a-z0-9_-]{0,98}[a-z0-9])?$/', $slug) === 1) {
        return false;
    }
    return true;
}

/**
 * Avalynn stores almost every BotChannel as type 0, including forum posts.
 * Discord text/forum channel names cannot contain spaces; thread titles can.
 *
 * @param array<string, true>|null $liveGuildChannelIds
 */
function gos_discord_classify_channel_kind(
    int $type,
    string $name,
    string $channelId,
    ?array $liveGuildChannelIds = null,
): string {
    if (gos_discord_is_thread_type($type)) {
        return 'thread';
    }
    if (gos_discord_is_guild_channel_type($type)) {
        return 'channel';
    }
    if (is_array($liveGuildChannelIds)) {
        return isset($liveGuildChannelIds[$channelId]) ? 'channel' : 'thread';
    }
    return gos_discord_name_looks_like_thread($name) ? 'thread' : 'channel';
}

function gos_discord_name_contains(string $name, string $needle): bool
{
    if ($needle === '') {
        return true;
    }
    if (function_exists('mb_stripos')) {
        return mb_stripos($name, $needle) !== false;
    }
    return stripos($name, $needle) !== false;
}

function gos_discord_channel_list_kind(array $q): string
{
    $kind = strtolower(trim((string) ($q['kind'] ?? '')));
    if (in_array($kind, ['channels', 'threads', 'all'], true)) {
        return $kind;
    }
    $includeThreads = ($q['threads'] ?? '') === '1' || ($q['threads'] ?? '') === 'true';
    return $includeThreads ? 'all' : 'channels';
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_channels(array $q): array
{
    $pdo = gos_discord_avalynn_pdo();
    if ($pdo === null) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'db_unavailable'];
    }
    $guildId = gos_discord_snowflake($q['guildId'] ?? '');
    if ($guildId === '') {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'guildId_required'];
    }
    $kind = gos_discord_channel_list_kind($q);
    $search = trim((string) ($q['search'] ?? ''));
    if (strlen($search) > 80) {
        $search = substr($search, 0, 80);
    }
    $defaultLimit = $kind === 'threads' ? 80 : 200;
    $limit = (int) ($q['limit'] ?? $defaultLimit);
    if ($limit < 1) {
        $limit = $defaultLimit;
    }
    if ($limit > 200) {
        $limit = 200;
    }
    $offset = (int) ($q['offset'] ?? 0);
    if ($offset < 0) {
        $offset = 0;
    }
    $ch = $pdo->prepare('SELECT channelId, channelName, channelType, enabled FROM BotChannel WHERE guildId = ? ORDER BY channelName ASC');
    $ch->execute([$guildId]);
    $channels = $ch->fetchAll() ?: [];
    $st = $pdo->prepare('SELECT id, channelId, botId, isEnabled, isMuted, respondToAll, channelName, channelType FROM ChannelSettings WHERE guildId = ?');
    $st->execute([$guildId]);
    $settings = $st->fetchAll() ?: [];
    $bots = $pdo->query('SELECT id, name FROM discord_bots WHERE is_active = 1 ORDER BY id ASC')->fetchAll() ?: [];
    $members = gos_discord_bot_guild_membership($pdo)[$guildId] ?? [];
    $byBot = [];
    $global = [];
    foreach ($settings as $s) {
        $cid = (string) ($s['channelId'] ?? '');
        if (($s['botId'] ?? null) === null || $s['botId'] === '') {
            $global[$cid] = $s;
        } else {
            $byBot[$cid][(int) $s['botId']] = $s;
        }
    }
    $channelCount = 0;
    $threadCount = 0;
    $matched = [];
    foreach ($channels as $c) {
        $type = (int) ($c['channelType'] ?? 0);
        $cid = (string) ($c['channelId'] ?? '');
        $name = (string) ($c['channelName'] ?? '');
        $rowKind = gos_discord_classify_channel_kind($type, $name, $cid, null);
        if ($rowKind === 'thread') {
            $threadCount++;
        } else {
            $channelCount++;
        }
        if ($kind === 'channels' && $rowKind !== 'channel') {
            continue;
        }
        if ($kind === 'threads' && $rowKind !== 'thread') {
            continue;
        }
        if ($search !== '' && !gos_discord_name_contains($name, $search) && !gos_discord_name_contains($cid, $search)) {
            continue;
        }
        $matched[] = [
            'channelId' => $cid,
            'channelName' => $name,
            'channelType' => $type,
            'enabled' => (int) ($c['enabled'] ?? 1) === 1,
            'kind' => $rowKind,
        ];
    }
    $total = count($matched);
    $slice = array_slice($matched, $offset, $limit);
    $out = [];
    foreach ($slice as $c) {
        $cid = $c['channelId'];
        $botRows = gos_discord_channel_bot_rows($bots, $members, $byBot, $global, $cid);
        $out[] = [
            'channelId' => $cid,
            'guildId' => $guildId,
            'channelName' => $c['channelName'],
            'channelType' => $c['channelType'],
            'kind' => $c['kind'],
            'enabled' => $c['enabled'],
            'bots' => $botRows,
            'settings' => [
                'isEnabled' => $botRows[0]['isEnabled'] ?? true,
                'isMuted' => $botRows[0]['isMuted'] ?? false,
                'respondToAll' => $botRows[0]['respondToAll'] ?? false,
            ],
        ];
    }
    return [
        'ok' => true,
        'status' => 200,
        'data' => [
            'channels' => $out,
            'guildId' => $guildId,
            'kind' => $kind,
            'total' => $total,
            'hasMore' => ($offset + count($out)) < $total,
            'channelCount' => $channelCount,
            'threadCount' => $threadCount,
        ],
        'error' => null,
    ];
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_guilds(array $q): array
{
    $pdo = gos_discord_avalynn_pdo();
    if ($pdo === null) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'db_unavailable'];
    }
    $search = trim((string) ($q['search'] ?? ''));
    if (strlen($search) > 80) {
        $search = substr($search, 0, 80);
    }
    $watched = $q['watched'] ?? null;
    $sortKey = strtolower(trim((string) ($q['sort'] ?? 'name')));
    gos_discord_repair_autotag_watch($pdo);
    gos_discord_sync_autotag_from_global($pdo);
    $rows = $pdo->query(
        'SELECT id, guildId, guildName, guildIcon, botId, isWatched, respondToMentions, respondToReplies, semanticTagging, analyzeFiles
         FROM GuildSettings',
    )->fetchAll() ?: [];
    $bots = $pdo->query('SELECT id, name FROM discord_bots WHERE is_active = 1 ORDER BY id ASC')->fetchAll() ?: [];
    $grouped = [];
    foreach ($rows as $row) {
        $gid = (string) ($row['guildId'] ?? '');
        if ($gid === '') {
            continue;
        }
        if (!isset($grouped[$gid])) {
            $grouped[$gid] = [
                'id' => (int) ($row['id'] ?? 0),
                'guildId' => $gid,
                'guildName' => (string) ($row['guildName'] ?? $gid),
                'guildIcon' => (string) ($row['guildIcon'] ?? ''),
                'botId' => null,
                'isWatched' => false,
                'respondToMentions' => false,
                'respondToReplies' => false,
                'semanticTagging' => false,
                'analyzeFiles' => false,
                'bots' => [],
            ];
        }
        $g = &$grouped[$gid];
        $bid = $row['botId'] === null || $row['botId'] === '' ? null : (int) $row['botId'];
        $entry = [
            'botId' => $bid,
            'isWatched' => (int) ($row['isWatched'] ?? 0) === 1,
            'respondToMentions' => (int) ($row['respondToMentions'] ?? 0) === 1,
            'respondToReplies' => (int) ($row['respondToReplies'] ?? 0) === 1,
            'semanticTagging' => (int) ($row['semanticTagging'] ?? 0) === 1,
            'analyzeFiles' => (int) ($row['analyzeFiles'] ?? 0) === 1,
        ];
        if ($bid === null) {
            $g['id'] = (int) ($row['id'] ?? $g['id']);
            if ((string) ($row['guildName'] ?? '') !== '') {
                $g['guildName'] = (string) $row['guildName'];
            }
            if ((string) ($row['guildIcon'] ?? '') !== '') {
                $g['guildIcon'] = (string) $row['guildIcon'];
            }
            if ($entry['isWatched']) {
                $g['isWatched'] = true;
            }
            if ($entry['respondToMentions']) {
                $g['respondToMentions'] = true;
            }
            if ($entry['respondToReplies']) {
                $g['respondToReplies'] = true;
            }
            if ($entry['semanticTagging']) {
                $g['semanticTagging'] = true;
            }
            if ($entry['analyzeFiles']) {
                $g['analyzeFiles'] = true;
            }
        } else {
            $g['bots'][] = $entry + ['name' => ''];
            if ($entry['isWatched']) {
                $g['isWatched'] = true;
            }
            if ($entry['semanticTagging']) {
                $g['semanticTagging'] = true;
            }
            if ($entry['analyzeFiles']) {
                $g['analyzeFiles'] = true;
            }
        }
        unset($g);
    }
    $botNames = [];
    foreach ($bots as $b) {
        $botNames[(int) $b['id']] = (string) $b['name'];
    }
    $out = array_values($grouped);
    $membership = gos_discord_bot_guild_membership($pdo);
    foreach ($out as &$g) {
        $gid = (string) ($g['guildId'] ?? '');
        $g = gos_discord_finalize_guild_bots($g, $membership[$gid] ?? [], $botNames);
    }
    unset($g);
    $botIds = gos_discord_parse_id_list($q['botIds'] ?? $q['botId'] ?? '');
    $out = gos_discord_filter_guilds_by_bots($out, $botIds);
    if ($search !== '') {
        $needle = strtolower($search);
        $out = array_values(array_filter($out, static function ($g) use ($needle) {
            return str_contains(strtolower((string) $g['guildName']), $needle)
                || str_contains((string) $g['guildId'], $needle);
        }));
    }
    if ($watched === '1' || $watched === 'true') {
        $out = array_values(array_filter($out, static fn ($g) => !empty($g['isWatched'])));
    } elseif ($watched === '0' || $watched === 'false') {
        $out = array_values(array_filter($out, static fn ($g) => empty($g['isWatched'])));
    }
    usort($out, static function ($a, $b) use ($sortKey) {
        if ($sortKey === 'watched') {
            $c = ((int) !empty($b['isWatched'])) <=> ((int) !empty($a['isWatched']));
            if ($c !== 0) {
                return $c;
            }
        }
        return strcasecmp((string) $a['guildName'], (string) $b['guildName']);
    });
    return ['ok' => true, 'status' => 200, 'data' => $out, 'error' => null];
}

/**
 * @param list<array<string, mixed>> $rows
 * @return list<array<string, mixed>>
 */
function gos_discord_map_attachment_rows(PDO $pdo, array $rows): array
{
    $guildIds = [];
    $channelIds = [];
    $userIds = [];
    foreach ($rows as $row) {
        $gid = (string) ($row['guildId'] ?? '');
        $cid = (string) ($row['channelId'] ?? '');
        $uid = (int) ($row['userId'] ?? 0);
        if ($gid !== '') {
            $guildIds[$gid] = true;
        }
        if ($cid !== '') {
            $channelIds[$cid] = true;
        }
        if ($uid > 0) {
            $userIds[$uid] = true;
        }
    }
    $guildMap = [];
    if ($guildIds !== []) {
        $gIn = implode(',', array_map(static fn ($v) => $pdo->quote((string) $v), array_keys($guildIds)));
        $gs = $pdo->query("SELECT guildId, guildName FROM GuildSettings WHERE botId IS NULL AND guildId IN ({$gIn})");
        if ($gs !== false) {
            foreach ($gs->fetchAll() as $g) {
                $guildMap[(string) $g['guildId']] = (string) ($g['guildName'] ?? '');
            }
        }
    }
    $chanMap = [];
    if ($channelIds !== []) {
        $cIn = implode(',', array_map(static fn ($v) => $pdo->quote((string) $v), array_keys($channelIds)));
        $cs = $pdo->query("SELECT channelId, channelName FROM BotChannel WHERE channelId IN ({$cIn})");
        if ($cs !== false) {
            foreach ($cs->fetchAll() as $c) {
                $chanMap[(string) $c['channelId']] = (string) ($c['channelName'] ?? '');
            }
        }
    }
    $userMap = [];
    if ($userIds !== []) {
        $uIn = implode(',', array_map('intval', array_keys($userIds)));
        $us = $pdo->query("SELECT id, username, displayName, discordId, avatar FROM User WHERE id IN ({$uIn})");
        if ($us !== false) {
            foreach ($us->fetchAll() as $u) {
                $userMap[(int) $u['id']] = $u;
            }
        }
    }
    $items = [];
    foreach ($rows as $row) {
        $id = (int) ($row['id'] ?? 0);
        $filename = (string) ($row['filename'] ?? '');
        $ctype = (string) ($row['contentType'] ?? '');
        $uid = (int) ($row['userId'] ?? 0);
        $u = $userMap[$uid] ?? [];
        $did = (string) ($u['discordId'] ?? $row['discordId'] ?? '');
        $gid = (string) ($row['guildId'] ?? '');
        $cid = (string) ($row['channelId'] ?? '');
        $pub = gos_discord_public_attachment([
            'id' => $id,
            'filename' => $filename,
            'contentType' => $ctype,
            'localPath' => (string) ($row['localPath'] ?? ''),
            'size' => (int) ($row['size'] ?? 0),
            'createdAt' => (string) ($row['createdAt'] ?? ''),
            'messageCreatedAt' => (string) ($row['messageCreatedAt'] ?? ''),
            'discordUrl' => (string) ($row['discordUrl'] ?? ''),
            'discordMessageId' => (string) ($row['discordMessageId'] ?? ''),
            'messageId' => (string) ($row['messageId'] ?? ''),
            'guildId' => $gid,
            'guildName' => $guildMap[$gid] ?? '',
            'channelName' => $chanMap[$cid] ?? '',
            'channelId' => $cid,
            'user' => [
                'id' => $uid,
                'username' => (string) ($u['username'] ?? $row['username'] ?? ''),
                'displayName' => (string) ($u['displayName'] ?? $row['displayName'] ?? ''),
                'discordId' => $did,
                'avatar' => gos_discord_avatar_url($did, (string) ($u['avatar'] ?? $row['avatar'] ?? '')),
            ],
        ]);
        $pub['discordId'] = $did;
        $items[] = $pub;
    }
    return function_exists('gos_discord_media_decorate') ? gos_discord_media_decorate($items) : $items;
}

/**
 * @param list<int> $excludeIds
 * @param list<string> $followedDiscordIds
 * @return list<array<string, mixed>>
 */
function gos_discord_sample_attachment_rows(
    PDO $pdo,
    string $kind,
    int $want,
    array $excludeIds,
    string $guildId,
    array $followedDiscordIds,
    bool $playableOnly = true,
): array {
    $want = max(1, min(40, $want));
    $where = ['a.isUserDeleted = 0'];
    $params = [];
    $kindSql = function_exists('gos_discord_attachment_kind_sql') ? gos_discord_attachment_kind_sql($kind) : '';
    if ($kindSql !== '') {
        $where[] = $kindSql;
    }
    if ($playableOnly) {
        $where[] = gos_discord_attachment_playable_sql('a');
    }
    if ($guildId !== '') {
        $where[] = 'm.guildId = ?';
        $params[] = $guildId;
    }
    if ($excludeIds !== []) {
        $where[] = 'a.id NOT IN (' . implode(',', array_map('intval', $excludeIds)) . ')';
    }
    if ($followedDiscordIds !== []) {
        $in = implode(',', array_map(static fn ($v) => $pdo->quote((string) $v), $followedDiscordIds));
        $where[] = "a.userId IN (SELECT id FROM User WHERE discordId IN ({$in}))";
    }
    $sqlWhere = implode(' AND ', $where);
    $from = 'FROM MessageAttachment a STRAIGHT_JOIN Message m ON m.id = a.messageId LEFT JOIN User u ON u.id = a.userId';
    $select = "SELECT a.id, a.filename, a.contentType, a.discordUrl, a.localPath, a.size, a.userId,
                      a.createdAt, a.messageId,
                      m.guildId, m.channelId, m.messageId AS discordMessageId, m.createdAt AS messageCreatedAt,
                      u.discordId, u.username, u.displayName, u.avatar
               {$from}
               WHERE {$sqlWhere}";
    try {
        $st = $pdo->prepare("{$select} ORDER BY a.id DESC LIMIT {$want}");
        $st->execute($params);
        $rows = $st->fetchAll() ?: [];
        $max = 0;
        try {
            $max = (int) $pdo->query('SELECT MAX(id) FROM MessageAttachment')->fetchColumn();
        } catch (Throwable) {
            $max = 0;
        }
        $span = min(12_000, max(2_000, intdiv(max($max, 1), 8)));
        $window = function_exists('gos_discord_sample_id_window')
            ? gos_discord_sample_id_window($max, $span)
            : [0, 0];
        $lo = (int) ($window[0] ?? 0);
        $hi = (int) ($window[1] ?? 0);
        if ($lo > 0 && $hi >= $lo) {
            $st2 = $pdo->prepare("{$select} AND a.id BETWEEN ? AND ? ORDER BY a.id DESC LIMIT {$want}");
            $st2->execute(array_merge($params, [$lo, $hi]));
            $more = $st2->fetchAll() ?: [];
            if ($more !== []) {
                $rows = array_merge($rows, $more);
            }
        }
        return $rows;
    } catch (Throwable) {
        return [];
    }
}

/**
 * @param list<int> $ids
 * @return list<array<string, mixed>>
 */
function gos_discord_attachments_by_ids(PDO $pdo, array $ids, bool $playableOnly = true): array
{
    $clean = [];
    foreach ($ids as $id) {
        $n = (int) $id;
        if ($n > 0) {
            $clean[$n] = $n;
        }
    }
    $clean = array_values($clean);
    if ($clean === []) {
        return [];
    }
    $in = implode(',', array_map('intval', $clean));
    $play = $playableOnly ? ' AND ' . gos_discord_attachment_playable_sql('a') : '';
    $sql = "SELECT a.id, a.filename, a.contentType, a.discordUrl, a.localPath, a.size, a.userId,
                   a.createdAt, a.messageId,
                   m.guildId, m.channelId, m.messageId AS discordMessageId, m.createdAt AS messageCreatedAt
            FROM MessageAttachment a
            STRAIGHT_JOIN Message m ON m.id = a.messageId
            WHERE a.isUserDeleted = 0 AND a.id IN ({$in}){$play}";
    try {
        $st = $pdo->query($sql);
        $rows = $st !== false ? ($st->fetchAll() ?: []) : [];
    } catch (Throwable) {
        return [];
    }
    $byId = [];
    foreach ($rows as $row) {
        $id = (int) ($row['id'] ?? 0);
        if ($id > 0) {
            $byId[$id] = $row;
        }
    }
    $ordered = [];
    foreach ($ids as $id) {
        $n = (int) $id;
        if ($n > 0 && isset($byId[$n])) {
            $ordered[] = $byId[$n];
        }
    }
    return gos_discord_map_attachment_rows($pdo, $ordered);
}

/**
 * @param list<int> $excludeIds
 * @return list<array<string, mixed>>
 */
function gos_discord_discogram_sample_page(
    PDO $pdo,
    int $limit,
    array $excludeIds,
    string $guildId,
    array $followed,
): array {
    $quota = function_exists('gos_discord_discogram_quota') ? gos_discord_discogram_quota($limit) : ['image' => $limit];
    $pool = [];
    foreach ($quota as $kind => $n) {
        if ($n <= 0) {
            continue;
        }
        $want = max($n * 4, 8);
        $pool = array_merge($pool, gos_discord_sample_attachment_rows($pdo, $kind, $want, $excludeIds, $guildId, []));
        if ($followed !== []) {
            $pool = array_merge(
                $pool,
                gos_discord_sample_attachment_rows($pdo, $kind, $want, $excludeIds, $guildId, $followed),
            );
        }
    }
    return function_exists('gos_discord_discogram_pick')
        ? gos_discord_discogram_pick($pool, $limit, $followed, $excludeIds, null)
        : array_slice($pool, 0, $limit);
}

/**
 * @param list<int> $excludeIds
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_discogram(PDO $pdo, array $q, int $limit, array $excludeIds): array
{
    $guildId = gos_discord_snowflake($q['guildId'] ?? '');
    $limit = max(1, min(20, $limit));
    $operator = function_exists('gos_discord_operator_id') ? gos_discord_operator_id() : 0;
    $followed = function_exists('gos_discord_media_followed_ids') ? gos_discord_media_followed_ids($operator) : [];
    $append = $excludeIds !== [] || (function_exists('gos_discord_query_flag') && gos_discord_query_flag($q['append'] ?? false));
    $playlist = function_exists('gos_discord_playlist_load')
        ? gos_discord_playlist_load($operator, $guildId)
        : ['id' => 0, 'cursor' => 0, 'cursorAttachmentId' => 0, 'ids' => []];
    $max = defined('GOS_DISCORD_PLAYLIST_MAX') ? GOS_DISCORD_PLAYLIST_MAX : 2000;

    if (!$append && ($playlist['ids'] ?? []) !== []) {
        $items = gos_discord_attachments_by_ids($pdo, $playlist['ids']);
        $ids = [];
        foreach ($items as $row) {
            $id = (int) ($row['id'] ?? 0);
            if ($id > 0) {
                $ids[] = $id;
            }
        }
        $cursor = function_exists('gos_discord_playlist_cursor_index')
            ? gos_discord_playlist_cursor_index(
                $ids,
                (int) ($playlist['cursor'] ?? 0),
                (int) ($playlist['cursorAttachmentId'] ?? 0),
            )
            : 0;
        return [
            'ok' => true,
            'status' => 200,
            'data' => [
                'attachments' => $items,
                'total' => count($items),
                'limit' => $limit,
                'offset' => 0,
                'hasMore' => count($playlist['ids']) < $max,
                'mode' => 'discogram',
                'playlistId' => (int) ($playlist['id'] ?? 0),
                'cursor' => $cursor,
            ],
            'error' => null,
        ];
    }

    $exclude = ($playlist['ids'] ?? []) !== [] ? $playlist['ids'] : $excludeIds;
    if (count($exclude) >= $max) {
        return [
            'ok' => true,
            'status' => 200,
            'data' => [
                'attachments' => [],
                'total' => count($exclude),
                'limit' => $limit,
                'offset' => 0,
                'hasMore' => false,
                'mode' => 'discogram',
                'playlistId' => (int) ($playlist['id'] ?? 0),
                'cursor' => (int) ($playlist['cursor'] ?? 0),
            ],
            'error' => null,
        ];
    }
    $picked = gos_discord_discogram_sample_page($pdo, $limit, $exclude, $guildId, $followed);
    $newIds = [];
    foreach ($picked as $row) {
        $id = (int) ($row['id'] ?? 0);
        if ($id > 0) {
            $newIds[] = $id;
        }
    }
    if ($newIds !== [] && ($playlist['id'] ?? 0) > 0 && function_exists('gos_discord_playlist_append')) {
        gos_discord_playlist_append((int) $playlist['id'], $newIds);
    }
    $items = gos_discord_map_attachment_rows($pdo, $picked);
    $full = count($items) >= $limit;
    $cursor = 0;
    if (!$append && function_exists('gos_discord_playlist_cursor_index')) {
        $ids = [];
        foreach ($items as $row) {
            $id = (int) ($row['id'] ?? 0);
            if ($id > 0) {
                $ids[] = $id;
            }
        }
        $cursor = gos_discord_playlist_cursor_index($ids, 0, 0);
    }
    return [
        'ok' => true,
        'status' => 200,
        'data' => [
            'attachments' => $items,
            'total' => count($items) + ($full ? 1 : 0),
            'limit' => $limit,
            'offset' => 0,
            'hasMore' => $full,
            'mode' => 'discogram',
            'playlistId' => (int) ($playlist['id'] ?? 0),
            'cursor' => $cursor,
        ],
        'error' => null,
    ];
}

/**
 * @return array{clause:string,params:list<mixed>}
 */
function gos_discord_attachments_user_filter(array $q): array
{
    $raw = trim((string) ($q['userId'] ?? $q['discordId'] ?? $q['discordUserId'] ?? ''));
    if ($raw === '') {
        return ['clause' => '', 'params' => []];
    }
    if (preg_match('/^[0-9]{15,22}$/', $raw)) {
        return ['clause' => 'a.userId IN (SELECT id FROM User WHERE discordId = ?)', 'params' => [$raw]];
    }
    $id = (int) $raw;
    if ($id <= 0) {
        return ['clause' => '', 'params' => []];
    }
    return ['clause' => 'a.userId = ?', 'params' => [$id]];
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_attachments(array $q): array
{
    $pdo = gos_discord_avalynn_pdo();
    if ($pdo === null) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'db_unavailable'];
    }
    $limit = gos_discord_int_range($q['limit'] ?? 40, 1, 100, 40);
    $offset = gos_discord_int_range($q['offset'] ?? 0, 0, 5_000_000, 0);
    $guildId = gos_discord_snowflake($q['guildId'] ?? '');
    $ct = trim((string) ($q['contentType'] ?? $q['kind'] ?? ''));
    $mode = strtolower(trim((string) ($q['mode'] ?? '')));
    $exclude = function_exists('gos_discord_id_list') ? gos_discord_id_list($q['exclude'] ?? $q['excludeIds'] ?? '') : [];
    $playableOnly = $mode === 'discogram'
        || gos_discord_query_flag($q['playable'] ?? $q['hideStale'] ?? $q['hide_stale'] ?? false);
    if ($mode === 'discogram') {
        return gos_discord_local_discogram($pdo, $q, min($limit, 20), $exclude);
    }
    $where = ['a.isUserDeleted = 0'];
    $params = [];
    if ($playableOnly) {
        $where[] = gos_discord_attachment_playable_sql('a');
    }
    if ($guildId !== '') {
        $where[] = 'm.guildId = ?';
        $params[] = $guildId;
    }
    $userFilter = gos_discord_attachments_user_filter($q);
    if ($userFilter['clause'] !== '') {
        $where[] = $userFilter['clause'];
        foreach ($userFilter['params'] as $p) {
            $params[] = $p;
        }
    }
    $likedFlag = $q['liked'] ?? $q['likes'] ?? false;
    $likedOnly = $likedFlag === true || $likedFlag === 1 || $likedFlag === '1' || $likedFlag === 'true';
    if ($likedOnly) {
        $op = function_exists('gos_discord_operator_id') ? gos_discord_operator_id() : 0;
        $likedIds = function_exists('gos_discord_media_liked_ids') ? gos_discord_media_liked_ids($op) : [];
        if ($likedIds === []) {
            return [
                'ok' => true,
                'status' => 200,
                'data' => [
                    'attachments' => [],
                    'total' => 0,
                    'limit' => $limit,
                    'offset' => $offset,
                    'hasMore' => false,
                ],
                'error' => null,
            ];
        }
        $where[] = 'a.id IN (' . implode(',', array_map('intval', $likedIds)) . ')';
    }
    if ($exclude !== []) {
        $where[] = 'a.id NOT IN (' . implode(',', array_map('intval', $exclude)) . ')';
    }
    if ($ct === 'gif') {
        $where[] = "(a.contentType = 'image/gif' OR a.filename LIKE '%.gif')";
    } elseif ($ct === 'video' || str_starts_with($ct, 'video/')) {
        $where[] = "(a.contentType LIKE 'video/%' OR a.filename LIKE '%.mp4' OR a.filename LIKE '%.mov' OR a.filename LIKE '%.webm')";
    } elseif ($ct === 'audio' || str_starts_with($ct, 'audio/') || $ct === 'application/ogg') {
        $where[] = "(a.contentType LIKE 'audio/%' OR a.contentType = 'application/ogg' OR a.filename LIKE '%.mp3' OR a.filename LIKE '%.ogg' OR a.filename LIKE '%.oga' OR a.filename LIKE '%.opus' OR a.filename LIKE '%.wav' OR a.filename LIKE '%.m4a')";
    } elseif ($ct === 'image' || str_starts_with($ct, 'image/')) {
        $where[] = "a.contentType LIKE 'image/%'";
    }
    $order = function_exists('gos_discord_attachments_order')
        ? gos_discord_attachments_order((string) ($q['sort'] ?? $q['order'] ?? ''))
        : 'DESC';
    if ($order !== 'ASC') {
        $order = 'DESC';
    }
    $sqlWhere = 'WHERE ' . implode(' AND ', $where);
    $take = $limit + 1;
    $sql = "SELECT a.id, a.filename, a.contentType, a.discordUrl, a.localPath, a.size, a.userId,
                   a.createdAt, a.messageId,
                   m.guildId, m.channelId, m.messageId AS discordMessageId, m.createdAt AS messageCreatedAt
            FROM MessageAttachment a
            STRAIGHT_JOIN Message m ON m.id = a.messageId
            {$sqlWhere}
            ORDER BY a.id {$order}
            LIMIT {$take} OFFSET {$offset}";
    $stmt = $pdo->prepare($sql);
    $stmt->execute($params);
    $rows = $stmt->fetchAll() ?: [];
    $hasMore = count($rows) > $limit;
    if ($hasMore) {
        array_pop($rows);
    }
    $items = gos_discord_map_attachment_rows($pdo, $rows);
    return [
        'ok' => true,
        'status' => 200,
        'data' => [
            'attachments' => $items,
            'total' => $offset + count($items) + ($hasMore ? 1 : 0),
            'limit' => $limit,
            'offset' => $offset,
            'hasMore' => $hasMore,
        ],
        'error' => null,
    ];
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_messages(array $q): array
{
    $pdo = gos_discord_avalynn_pdo();
    if ($pdo === null) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'db_unavailable'];
    }
    try {
        return gos_discord_local_messages_run($pdo, $q);
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'messages_query_failed'];
    }
}

/**
 * Fast feed: no COUNT(*) over Message. Pagination is limit+1 / hasMore.
 *
 * @param array<string, mixed> $q
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_messages_run(PDO $pdo, array $q): array
{
    $limit = gos_discord_int_range($q['limit'] ?? 40, 1, 100, 40);
    $page = gos_discord_int_range($q['page'] ?? 1, 1, 100000, 1);
    $offset = gos_discord_int_range($q['offset'] ?? (($page - 1) * $limit), 0, 5_000_000, 0);
    $guildId = gos_discord_snowflake($q['guildId'] ?? '');
    $channelId = gos_discord_snowflake($q['channelId'] ?? '');
    $botId = gos_discord_id($q['botId'] ?? '');
    $search = trim((string) ($q['search'] ?? ''));
    if (strlen($search) > 80) {
        $search = substr($search, 0, 80);
    }
    $tf = strtolower(trim((string) ($q['timeframe'] ?? '1d')));
    $seconds = gos_discord_timeframe_seconds($tf);
    $fromDate = trim((string) ($q['fromDate'] ?? $q['dateFrom'] ?? ''));
    $toDate = trim((string) ($q['toDate'] ?? $q['dateTo'] ?? ''));
    $aroundMessageId = gos_discord_snowflake($q['aroundMessageId'] ?? $q['around'] ?? '');
    $aroundAt = trim((string) ($q['aroundAt'] ?? ''));
    $beforeAt = trim((string) ($q['beforeAt'] ?? $q['before'] ?? ''));
    if ($aroundMessageId !== '') {
        try {
            $look = $pdo->prepare('SELECT guildId, channelId, createdAt FROM Message WHERE messageId = ? LIMIT 1');
            $look->execute([$aroundMessageId]);
            $hit = $look->fetch();
            if (is_array($hit)) {
                if ($guildId === '') {
                    $guildId = gos_discord_snowflake((string) ($hit['guildId'] ?? ''));
                }
                if ($channelId === '') {
                    $channelId = gos_discord_snowflake((string) ($hit['channelId'] ?? ''));
                }
                if ($aroundAt === '') {
                    $aroundAt = (string) ($hit['createdAt'] ?? '');
                }
            }
        } catch (Throwable) {
        }
    }
    $aroundTs = gos_discord_parse_ts($aroundAt);
    $beforeTs = gos_discord_parse_ts($beforeAt);

    $botIds = gos_discord_active_bot_ids($pdo);
    if ($botId !== '') {
        $bid = (int) $botId;
        $botIds = in_array($bid, $botIds, true) ? [$bid] : $botIds;
    }

    $where = ['m.isSpam = 0'];
    $params = [];
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
    $userFilter = gos_discord_messages_user_filter($q);
    if ($userFilter['clause'] !== '') {
        $where[] = $userFilter['clause'];
        foreach ($userFilter['params'] as $p) {
            $params[] = $p;
        }
    }
    $centered = $aroundTs > 0 && $beforeTs <= 0;
    if ($beforeTs > 0) {
        $where[] = 'm.createdAt < ?';
        $params[] = gos_discord_sql_datetime($beforeTs);
        if ($aroundTs > 0 && $seconds > 0) {
            $where[] = 'm.createdAt >= ?';
            $params[] = gos_discord_sql_datetime($aroundTs - $seconds);
        }
    } elseif ($centered) {
        if ($seconds > 0) {
            [$lo, $hi] = gos_discord_around_range($aroundTs, $seconds);
            $where[] = 'm.createdAt >= ?';
            $params[] = gos_discord_sql_datetime($lo);
            $where[] = 'm.createdAt <= ?';
            $params[] = gos_discord_sql_datetime($hi);
        }
    } elseif ($fromDate !== '' && preg_match('/^\d{4}-\d{2}-\d{2}/', $fromDate)) {
        $where[] = 'm.createdAt >= ?';
        $params[] = $fromDate;
        if ($toDate !== '' && preg_match('/^\d{4}-\d{2}-\d{2}/', $toDate)) {
            $where[] = 'm.createdAt <= ?';
            $params[] = $toDate . (strlen($toDate) > 10 ? '' : ' 23:59:59.999');
        }
    } elseif ($seconds > 0) {
        $where[] = 'm.createdAt >= DATE_SUB(UTC_TIMESTAMP(3), INTERVAL ' . (int) $seconds . ' SECOND)';
    }
    if ($search !== '') {
        if (preg_match('/^[0-9]{5,32}$/', $search)) {
            $where[] = gos_discord_messages_id_search_clause();
            $params[] = $search;
            $params[] = $search;
            $params[] = $search;
        } else {
            $where[] = '(u.username LIKE ? OR u.displayName LIKE ? OR m.tags LIKE ?)';
            $like = $search . '%';
            $params[] = $like;
            $params[] = $like;
            $params[] = '%' . $search . '%';
        }
    }
    $sqlWhere = 'WHERE ' . implode(' AND ', $where);
    $select = "SELECT m.id, m.messageId, m.guildId, m.channelId, m.content, m.botId, m.tags, m.createdAt, m.userId,
                   u.discordId, u.username, u.displayName, u.avatar, u.level
            FROM Message m
            INNER JOIN User u ON u.id = m.userId
            {$sqlWhere}";
    $hasMore = false;
    if ($centered) {
        $beforeN = max(10, min(40, $limit));
        $afterN = max(10, min(40, $limit));
        $aroundSql = gos_discord_sql_datetime($aroundTs);
        $stAfter = $pdo->prepare($select . ' AND m.createdAt >= ? ORDER BY m.createdAt ASC LIMIT ' . (int) $afterN);
        $stAfter->execute(array_merge($params, [$aroundSql]));
        $after = $stAfter->fetchAll() ?: [];
        $stBefore = $pdo->prepare($select . ' AND m.createdAt <= ? ORDER BY m.createdAt DESC LIMIT ' . (int) ($beforeN + 1));
        $stBefore->execute(array_merge($params, [$aroundSql]));
        $before = $stBefore->fetchAll() ?: [];
        $hasMore = count($before) > $beforeN;
        if ($hasMore) {
            array_pop($before);
        }
        $seen = [];
        $rows = [];
        foreach (array_reverse($after) as $row) {
            $mid = (int) ($row['id'] ?? 0);
            if ($mid <= 0 || isset($seen[$mid])) {
                continue;
            }
            $seen[$mid] = true;
            $rows[] = $row;
        }
        foreach ($before as $row) {
            $mid = (int) ($row['id'] ?? 0);
            if ($mid <= 0 || isset($seen[$mid])) {
                continue;
            }
            $seen[$mid] = true;
            $rows[] = $row;
        }
        $offset = 0;
    } else {
        $take = $limit + 1;
        $sql = $select . ' ORDER BY m.createdAt DESC LIMIT ' . (int) $take . ' OFFSET ' . (int) ($beforeTs > 0 ? 0 : $offset);
        $stmt = $pdo->prepare($sql);
        $stmt->execute($params);
        $rows = $stmt->fetchAll() ?: [];
        $hasMore = count($rows) > $limit;
        if ($hasMore) {
            array_pop($rows);
        }
        if ($beforeTs > 0) {
            $offset = 0;
        }
    }

    $msgIds = [];
    $guildIds = [];
    $channelIds = [];
    foreach ($rows as $row) {
        $msgIds[] = (int) ($row['id'] ?? 0);
        $gid = (string) ($row['guildId'] ?? '');
        $cid = (string) ($row['channelId'] ?? '');
        if ($gid !== '') {
            $guildIds[$gid] = true;
        }
        if ($cid !== '') {
            $channelIds[$cid] = true;
        }
    }

    $attByMsg = [];
    if ($msgIds !== []) {
        $in = implode(',', array_map('intval', $msgIds));
        $ast = $pdo->query(
            "SELECT id, messageId, filename, contentType, discordUrl, localPath, size, createdAt, userId
             FROM MessageAttachment
             WHERE messageId IN ({$in}) AND isUserDeleted = 0",
        );
        if ($ast !== false) {
            foreach ($ast->fetchAll() as $a) {
                $mid = (int) ($a['messageId'] ?? 0);
                $attByMsg[$mid][] = gos_discord_public_attachment([
                    'id' => (int) ($a['id'] ?? 0),
                    'filename' => (string) ($a['filename'] ?? ''),
                    'contentType' => (string) ($a['contentType'] ?? ''),
                    'localPath' => (string) ($a['localPath'] ?? ''),
                    'size' => (int) ($a['size'] ?? 0),
                    'createdAt' => (string) ($a['createdAt'] ?? ''),
                    'discordUrl' => (string) ($a['discordUrl'] ?? ''),
                    'messageId' => $mid,
                ]);
            }
        }
    }

    $guildMap = [];
    if ($guildIds !== []) {
        $gIn = implode(',', array_map(static fn ($v) => $pdo->quote((string) $v), array_keys($guildIds)));
        $gs = $pdo->query("SELECT guildId, guildName FROM GuildSettings WHERE botId IS NULL AND guildId IN ({$gIn})");
        if ($gs !== false) {
            foreach ($gs->fetchAll() as $g) {
                $guildMap[(string) $g['guildId']] = (string) ($g['guildName'] ?? '');
            }
        }
    }
    $chanMap = [];
    if ($channelIds !== []) {
        $cIn = implode(',', array_map(static fn ($v) => $pdo->quote((string) $v), array_keys($channelIds)));
        $cs = $pdo->query("SELECT channelId, channelName FROM BotChannel WHERE channelId IN ({$cIn})");
        if ($cs !== false) {
            foreach ($cs->fetchAll() as $c) {
                $chanMap[(string) $c['channelId']] = (string) ($c['channelName'] ?? '');
            }
        }
    }

    $messages = [];
    foreach ($rows as $row) {
        $id = (int) ($row['id'] ?? 0);
        $did = (string) ($row['discordId'] ?? '');
        $gid = (string) ($row['guildId'] ?? '');
        $cid = (string) ($row['channelId'] ?? '');
        $messages[] = [
            'id' => $id,
            'messageId' => (string) ($row['messageId'] ?? ''),
            'guildId' => $gid,
            'channelId' => $cid,
            'content' => (string) ($row['content'] ?? ''),
            'tags' => (string) ($row['tags'] ?? ''),
            'createdAt' => (string) ($row['createdAt'] ?? ''),
            'botId' => $row['botId'] !== null ? (int) $row['botId'] : null,
            'guildName' => $guildMap[$gid] ?? '',
            'channelName' => $chanMap[$cid] ?? '',
            'user' => [
                'id' => (int) ($row['userId'] ?? 0),
                'discordId' => $did,
                'username' => (string) ($row['username'] ?? ''),
                'displayName' => $row['displayName'],
                'avatar' => gos_discord_avatar_url($did, (string) ($row['avatar'] ?? '')),
                'level' => (int) ($row['level'] ?? 0),
            ],
            'attachments' => gos_discord_attach_message_origin(
                $attByMsg[$id] ?? [],
                [
                    'guildId' => $gid,
                    'guildName' => $guildMap[$gid] ?? '',
                    'channelId' => $cid,
                    'channelName' => $chanMap[$cid] ?? '',
                    'createdAt' => (string) ($row['createdAt'] ?? ''),
                    'discordMessageId' => (string) ($row['messageId'] ?? ''),
                    'user' => [
                        'id' => (int) ($row['userId'] ?? 0),
                        'discordId' => $did,
                        'username' => (string) ($row['username'] ?? ''),
                        'displayName' => $row['displayName'],
                        'avatar' => gos_discord_avatar_url($did, (string) ($row['avatar'] ?? '')),
                    ],
                ],
            ),
        ];
    }

    $pageOut = $limit > 0 ? (int) floor($offset / $limit) + 1 : 1;
    $totalGuess = $offset + count($messages) + ($hasMore ? 1 : 0);
    return [
        'ok' => true,
        'status' => 200,
        'data' => [
            'messages' => $messages,
            'total' => $totalGuess,
            'page' => $pageOut,
            'pageSize' => $limit,
            'totalPages' => $hasMore ? $pageOut + 1 : max(1, $pageOut),
            'hasMore' => $hasMore,
            'timeframe' => $tf,
        ],
        'error' => null,
    ];
}

/**
 * @return list<string>
 */
function gos_discord_parse_message_tags(?string $raw): array
{
    $s = trim((string) $raw);
    if ($s === '') {
        return [];
    }
    if (str_starts_with($s, '[')) {
        $arr = json_decode($s, true);
        if (is_array($arr)) {
            $out = [];
            foreach ($arr as $t) {
                $t = trim((string) $t);
                if ($t !== '') {
                    $out[] = $t;
                }
            }
            return $out;
        }
    }
    $out = [];
    foreach (explode(',', $s) as $t) {
        $t = trim($t);
        if ($t !== '') {
            $out[] = $t;
        }
    }
    return $out;
}

function gos_discord_is_bogus_tag(string $tag): bool
{
    if ($tag === '0') {
        return false;
    }
    return (bool) preg_match('/^\d{1,2}$/', $tag);
}

/**
 * @param list<string|array<string, mixed>> $tagRows
 * @return array{counts:array<string,int>,total:int,unique:int}
 */
function gos_discord_tag_counts(array $tagRows): array
{
    $counts = [];
    $total = 0;
    foreach ($tagRows as $raw) {
        $s = is_array($raw) ? (string) ($raw['tags'] ?? '') : (string) $raw;
        foreach (gos_discord_parse_message_tags($s) as $tag) {
            if (gos_discord_is_bogus_tag($tag)) {
                continue;
            }
            $counts[$tag] = ($counts[$tag] ?? 0) + 1;
            $total++;
        }
    }
    return ['counts' => $counts, 'total' => $total, 'unique' => count($counts)];
}

/**
 * @param array<string, int> $counts
 * @return array{tags:list<array{tag:string,count:int}>,hasMore:bool}
 */
function gos_discord_top_tags(array $counts, int $limit = 40, int $offset = 0): array
{
    arsort($counts, SORT_NUMERIC);
    $all = [];
    foreach ($counts as $tag => $count) {
        $all[] = ['tag' => (string) $tag, 'count' => (int) $count];
    }
    $limit = max(1, $limit);
    $offset = max(0, $offset);
    $slice = array_slice($all, $offset, $limit);
    return ['tags' => $slice, 'hasMore' => ($offset + $limit) < count($all)];
}

/**
 * @param list<string> $tags
 * @param array<int, string> $idToName
 * @return list<string>
 */
function gos_discord_resolve_tag_names(array $tags, array $idToName): array
{
    $out = [];
    foreach ($tags as $t) {
        $t = (string) $t;
        if (preg_match('/^\d+$/', $t)) {
            $n = (int) $t;
            $out[] = isset($idToName[$n]) ? (string) $idToName[$n] : $t;
        } else {
            $out[] = $t;
        }
    }
    return $out;
}

/**
 * @param list<array<string, mixed>> $rows
 * @return list<array{oldValue:string,newValue:string,changedAt:string}>
 */
function gos_discord_history_changes(array $rows, string $field, int $limit = 20, string $discordId = ''): array
{
    $out = [];
    foreach ($rows as $row) {
        if ((string) ($row['field'] ?? '') !== $field) {
            continue;
        }
        $old = (string) ($row['oldValue'] ?? '');
        $new = (string) ($row['newValue'] ?? '');
        if ($field === 'avatar' && $discordId !== '') {
            if ($old !== '') {
                $old = gos_discord_avatar_url($discordId, $old);
            }
            if ($new !== '') {
                $new = gos_discord_avatar_url($discordId, $new);
            }
        }
        $out[] = [
            'oldValue' => $old,
            'newValue' => $new,
            'changedAt' => (string) ($row['changedAt'] ?? ''),
        ];
        if (count($out) >= $limit) {
            break;
        }
    }
    return $out;
}

function gos_discord_xp_for_level(int $level): int
{
    $level = max(1, $level);
    return (int) floor(100 * (1.5 ** ($level - 1)));
}

function gos_discord_level_progress(int $xp, int $level): float
{
    $need = gos_discord_xp_for_level($level);
    if ($need <= 0) {
        return 0.0;
    }
    return round(min(100.0, max(0.0, ($xp / $need) * 100.0)), 1);
}

/**
 * @param array<string, mixed> $q
 * @return array{byDiscordId:bool,key:string}
 */
function gos_discord_profile_lookup(array $q): array
{
    $id = trim((string) ($q['id'] ?? $q['userId'] ?? ''));
    $did = trim((string) ($q['discordId'] ?? ''));
    $flag = $q['byDiscordId'] ?? false;
    $by = $flag === true || $flag === 1 || $flag === '1' || $flag === 'true';
    if ($did !== '') {
        return ['byDiscordId' => true, 'key' => $did];
    }
    if ($id !== '' && (strlen($id) >= 15 || $by)) {
        return ['byDiscordId' => true, 'key' => $id];
    }
    return ['byDiscordId' => false, 'key' => $id];
}

/**
 * @param array<string, mixed> $q
 * @return array{clause:string,params:list<mixed>}
 */
function gos_discord_messages_user_filter(array $q): array
{
    $raw = trim((string) ($q['userId'] ?? $q['discordId'] ?? ''));
    $id = function_exists('gos_discord_id') ? gos_discord_id($raw) : '';
    if ($id === '' && preg_match('/^[0-9]{1,24}$/', $raw)) {
        $id = $raw;
    }
    if ($id === '') {
        return ['clause' => '', 'params' => []];
    }
    if (strlen($id) >= 15) {
        return ['clause' => 'u.discordId = ?', 'params' => [$id]];
    }
    return ['clause' => 'm.userId = ?', 'params' => [(int) $id]];
}

/**
 * @param array<string, mixed> $user
 * @param list<array<string, mixed>> $guilds
 * @param list<array<string, mixed>> $channels
 * @param list<array<string, mixed>> $usernameChanges
 * @param list<array<string, mixed>> $displayNameChanges
 * @param list<array<string, mixed>> $avatarChanges
 * @param array<string, mixed> $tagBlock
 * @return array<string, mixed>
 */
function gos_discord_profile_payload(
    array $user,
    int $messageCount,
    array $guilds,
    array $channels,
    array $usernameChanges,
    array $displayNameChanges,
    array $avatarChanges,
    array $tagBlock,
): array {
    $did = (string) ($user['discordId'] ?? '');
    $level = (int) ($user['level'] ?? 1);
    $xp = (int) ($user['xp'] ?? 0);
    $need = gos_discord_xp_for_level(max(1, $level));
    return [
        'id' => (int) ($user['id'] ?? 0),
        'discordId' => $did,
        'username' => (string) ($user['username'] ?? ''),
        'displayName' => $user['displayName'] ?? '',
        'avatar' => gos_discord_avatar_url($did, (string) ($user['avatar'] ?? '')),
        'level' => $level,
        'xp' => $xp,
        'totalXp' => (int) ($user['totalXp'] ?? 0),
        'activityScore' => (float) ($user['activityScore'] ?? 0),
        'messageCount' => $messageCount,
        'xpToNextLevel' => $need,
        'levelProgress' => gos_discord_level_progress($xp, max(1, $level)),
        'lastActive' => (string) ($user['lastActivity'] ?? $user['lastActive'] ?? ''),
        'lastActivity' => (string) ($user['lastActivity'] ?? $user['lastActive'] ?? ''),
        'createdAt' => (string) ($user['createdAt'] ?? ''),
        'activeGuilds' => $guilds,
        'activeChannels' => $channels,
        'usernameChanges' => $usernameChanges,
        'displayNameChanges' => $displayNameChanges,
        'avatarChanges' => $avatarChanges,
        'topTags' => $tagBlock['topTags'] ?? [],
        'totalTagCount' => (int) ($tagBlock['totalTagCount'] ?? 0),
        'uniqueTagCount' => (int) ($tagBlock['uniqueTagCount'] ?? 0),
        'hasMoreTags' => (bool) ($tagBlock['hasMoreTags'] ?? false),
    ];
}

/**
 * @param array<string, mixed> $q
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_user(array $q): array
{
    $pdo = gos_discord_avalynn_pdo();
    if ($pdo === null) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'db_unavailable'];
    }
    try {
        return gos_discord_local_user_run($pdo, $q);
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'user_query_failed'];
    }
}

/**
 * @param array<string, mixed> $q
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_local_user_run(PDO $pdo, array $q): array
{
    $lookup = gos_discord_profile_lookup($q);
    $key = $lookup['key'];
    if ($key === '') {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'missing_id'];
    }
    if ($lookup['byDiscordId']) {
        $st = $pdo->prepare(
            'SELECT id, discordId, username, displayName, avatar, level, xp, totalXp, activityScore, lastActivity, createdAt
             FROM User WHERE discordId = ? LIMIT 1'
        );
        $st->execute([$key]);
    } else {
        $st = $pdo->prepare(
            'SELECT id, discordId, username, displayName, avatar, level, xp, totalXp, activityScore, lastActivity, createdAt
             FROM User WHERE id = ? LIMIT 1'
        );
        $st->execute([(int) $key]);
    }
    $user = $st->fetch();
    if (!is_array($user)) {
        return ['ok' => false, 'status' => 404, 'data' => null, 'error' => 'not_found'];
    }
    $userId = (int) ($user['id'] ?? 0);
    $discordId = (string) ($user['discordId'] ?? '');
    $botIds = gos_discord_active_bot_ids($pdo);
    $botSql = $botIds === [] ? '' : (' AND botId IN (' . implode(',', array_map('intval', $botIds)) . ')');

    $cst = $pdo->prepare('SELECT COUNT(*) FROM Message WHERE userId = ?' . $botSql);
    $cst->execute([$userId]);
    $messageCount = (int) $cst->fetchColumn();

    $loc = $pdo->prepare(
        'SELECT guildId, channelId FROM Message WHERE userId = ?' . $botSql . ' ORDER BY id DESC LIMIT 2500'
    );
    $loc->execute([$userId]);
    $guildCounts = [];
    $channelCounts = [];
    $channelGuild = [];
    foreach ($loc->fetchAll() ?: [] as $row) {
        $gid = (string) ($row['guildId'] ?? '');
        $cid = (string) ($row['channelId'] ?? '');
        if ($gid !== '') {
            $guildCounts[$gid] = ($guildCounts[$gid] ?? 0) + 1;
        }
        if ($cid !== '') {
            $channelCounts[$cid] = ($channelCounts[$cid] ?? 0) + 1;
            if ($gid !== '') {
                $channelGuild[$cid] = $gid;
            }
        }
    }
    arsort($guildCounts, SORT_NUMERIC);
    arsort($channelCounts, SORT_NUMERIC);
    $guildIds = array_slice(array_keys($guildCounts), 0, 40);
    $channelIds = array_slice(array_keys($channelCounts), 0, 30);

    $guildNames = [];
    if ($guildIds !== []) {
        $gIn = implode(',', array_map(static fn ($v) => $pdo->quote((string) $v), $guildIds));
        $gs = $pdo->query("SELECT guildId, guildName FROM GuildSettings WHERE botId IS NULL AND guildId IN ({$gIn})");
        if ($gs !== false) {
            foreach ($gs->fetchAll() as $g) {
                $guildNames[(string) $g['guildId']] = (string) ($g['guildName'] ?? '');
            }
        }
    }
    $channelNames = [];
    if ($channelIds !== []) {
        $cIn = implode(',', array_map(static fn ($v) => $pdo->quote((string) $v), $channelIds));
        $cs = $pdo->query("SELECT channelId, channelName FROM BotChannel WHERE channelId IN ({$cIn})");
        if ($cs !== false) {
            foreach ($cs->fetchAll() as $c) {
                $channelNames[(string) $c['channelId']] = (string) ($c['channelName'] ?? '');
            }
        }
    }
    $guilds = [];
    foreach ($guildIds as $gid) {
        $guilds[] = [
            'id' => $gid,
            'name' => ($guildNames[$gid] ?? '') !== '' ? $guildNames[$gid] : $gid,
            'messageCount' => (int) $guildCounts[$gid],
        ];
    }
    $channels = [];
    foreach ($channelIds as $cid) {
        $gid = $channelGuild[$cid] ?? '';
        $channels[] = [
            'id' => $cid,
            'name' => ($channelNames[$cid] ?? '') !== '' ? $channelNames[$cid] : $cid,
            'guildId' => $gid,
            'guildName' => $guildNames[$gid] ?? '',
            'messageCount' => (int) $channelCounts[$cid],
        ];
    }

    $histStmt = $pdo->prepare(
        'SELECT field, oldValue, newValue, changedAt FROM UserHistory WHERE userId = ? AND field = ? ORDER BY changedAt DESC LIMIT 20'
    );
    $histStmt->execute([$userId, 'username']);
    $usernameChanges = gos_discord_history_changes($histStmt->fetchAll() ?: [], 'username', 20);
    $histStmt->execute([$userId, 'displayName']);
    $displayNameChanges = gos_discord_history_changes($histStmt->fetchAll() ?: [], 'displayName', 20);
    $histStmt->execute([$userId, 'avatar']);
    $avatarChanges = gos_discord_history_changes($histStmt->fetchAll() ?: [], 'avatar', 20, $discordId);

    $tagRows = [];
    try {
        $tst = $pdo->prepare(
            'SELECT tags FROM Message WHERE userId = ? AND hasTags = 1' . $botSql . ' ORDER BY id DESC LIMIT 2000'
        );
        $tst->execute([$userId]);
        $tagRows = $tst->fetchAll(PDO::FETCH_COLUMN) ?: [];
    } catch (Throwable) {
        $tst = $pdo->prepare(
            "SELECT tags FROM Message WHERE userId = ? AND tags IS NOT NULL AND tags <> ''" . $botSql . ' ORDER BY id DESC LIMIT 2000'
        );
        $tst->execute([$userId]);
        $tagRows = $tst->fetchAll(PDO::FETCH_COLUMN) ?: [];
    }
    $agg = gos_discord_tag_counts(is_array($tagRows) ? $tagRows : []);
    $ranked = gos_discord_top_tags($agg['counts'], 24, 0);
    if ($agg['unique'] > count($ranked['tags'])) {
        $ranked['hasMore'] = true;
    }
    $idToName = [];
    $numeric = [];
    foreach ($ranked['tags'] as $row) {
        if (preg_match('/^\d+$/', (string) $row['tag'])) {
            $numeric[] = (int) $row['tag'];
        }
    }
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

    $payload = gos_discord_profile_payload(
        $user,
        $messageCount,
        $guilds,
        $channels,
        $usernameChanges,
        $displayNameChanges,
        $avatarChanges,
        [
            'topTags' => $topTags,
            'totalTagCount' => $agg['total'],
            'uniqueTagCount' => $agg['unique'],
            'hasMoreTags' => $ranked['hasMore'],
        ],
    );
    return [
        'ok' => true,
        'status' => 200,
        'data' => $payload,
        'error' => null,
    ];
}

/**
 * Boolean columns copied when creating a bot-specific GuildSettings row.
 *
 * @return list<string>
 */
function gos_discord_guild_settings_flag_columns(): array
{
    return [
        'isWatched',
        'respondToMentions',
        'respondToReplies',
        'respondInConversation',
        'semanticTagging',
        'analyzeFiles',
    ];
}

/**
 * @param array<string, mixed> $row
 * @return array<string, mixed>
 */
function gos_discord_guild_settings_copyable(array $row): array
{
    $out = [
        'guildName' => (string) ($row['guildName'] ?? ''),
        'guildIcon' => (string) ($row['guildIcon'] ?? ''),
    ];
    foreach (gos_discord_guild_settings_flag_columns() as $col) {
        $out[$col] = !empty($row[$col]) ? 1 : 0;
    }
    return $out;
}

/**
 * Apply only the provided flags so a tagging-only write cannot reset isWatched.
 *
 * @param array<string, mixed> $base
 * @param array<string, bool> $patch
 * @return array<string, mixed>
 */
function gos_discord_guild_settings_apply_patch(array $base, array $patch): array
{
    foreach ($patch as $col => $v) {
        if (!is_string($col) || $col === '') {
            continue;
        }
        $base[$col] = $v ? 1 : 0;
    }
    return $base;
}

/**
 * Auto-tag used to INSERT bot-specific rows with Prisma defaults (isWatched=0).
 * That hid the guild from the watched list and made Avalynn skip capture.
 */
function gos_discord_repair_autotag_watch(PDO $pdo): int
{
    try {
        $n = $pdo->exec(
            'UPDATE GuildSettings b
             INNER JOIN GuildSettings g ON g.guildId = b.guildId AND g.botId IS NULL
             SET b.isWatched = 1
             WHERE b.botId IS NOT NULL
               AND b.isWatched = 0
               AND g.isWatched = 1
               AND b.semanticTagging = 1'
        );
        return is_int($n) ? $n : 0;
    } catch (Throwable) {
        return 0;
    }
}

/**
 * The phone Auto tagging switch is guild-wide. A write with no botId must copy
 * semanticTagging onto every GuildSettings row for that guild, not only the
 * global (botId IS NULL) row — leftover bot rows kept the switch on after off.
 *
 * @param array<string, bool> $patch
 * @return array<string, bool>
 */
function gos_discord_guild_settings_guild_wide_flags(?int $botId, array $patch): array
{
    if ($botId !== null || !array_key_exists('semanticTagging', $patch)) {
        return [];
    }
    return ['semanticTagging' => !empty($patch['semanticTagging'])];
}

/**
 * Keep bot-specific rows in lockstep with the global auto-tag flag.
 */
function gos_discord_sync_autotag_from_global(PDO $pdo): int
{
    try {
        $n = $pdo->exec(
            'UPDATE GuildSettings b
             INNER JOIN GuildSettings g ON g.guildId = b.guildId AND g.botId IS NULL
             SET b.semanticTagging = g.semanticTagging
             WHERE b.botId IS NOT NULL
               AND b.semanticTagging <> g.semanticTagging'
        );
        return is_int($n) ? $n : 0;
    } catch (Throwable) {
        return 0;
    }
}

/**
 * Partial UPDATE, or INSERT that copies watch/mention flags from the global row.
 *
 * @param array<string, mixed> $body
 * @return array{ok:bool,status:int,data:mixed,error:?string}|null
 */
function gos_discord_local_update_guild_settings(array $body): ?array
{
    $pdo = gos_discord_avalynn_pdo();
    if ($pdo === null) {
        return null;
    }
    $guildId = function_exists('gos_discord_snowflake')
        ? gos_discord_snowflake($body['guildId'] ?? '')
        : trim((string) ($body['guildId'] ?? ''));
    if ($guildId === '') {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'guild_required'];
    }
    $botId = null;
    $rawBot = $body['botId'] ?? null;
    if ($rawBot !== null && $rawBot !== '' && $rawBot !== 'null') {
        $n = (int) $rawBot;
        if ($n > 0) {
            $botId = $n;
        }
    }
    $patch = [];
    foreach (gos_discord_guild_settings_flag_columns() as $col) {
        $v = function_exists('gos_discord_bool') ? gos_discord_bool($body[$col] ?? null) : null;
        if ($v !== null) {
            $patch[$col] = $v;
        }
    }
    if ($patch === []) {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'no_settings'];
    }
    gos_discord_repair_autotag_watch($pdo);
    try {
        if ($botId === null) {
            $find = $pdo->prepare('SELECT * FROM GuildSettings WHERE guildId = ? AND botId IS NULL LIMIT 1');
            $find->execute([$guildId]);
        } else {
            $find = $pdo->prepare('SELECT * FROM GuildSettings WHERE guildId = ? AND botId = ? LIMIT 1');
            $find->execute([$guildId, $botId]);
        }
        $existing = $find->fetch();
        if (is_array($existing)) {
            $sets = [];
            $params = [];
            foreach ($patch as $col => $v) {
                $sets[] = $col . ' = ?';
                $params[] = $v ? 1 : 0;
            }
            $params[] = (int) ($existing['id'] ?? 0);
            $pdo->prepare('UPDATE GuildSettings SET ' . implode(', ', $sets) . ' WHERE id = ?')->execute($params);
            $id = (int) ($existing['id'] ?? 0);
            $row = gos_discord_guild_settings_apply_patch(gos_discord_guild_settings_copyable($existing), $patch);
        } else {
            $base = [
                'guildName' => '',
                'guildIcon' => '',
            ];
            foreach (gos_discord_guild_settings_flag_columns() as $col) {
                $base[$col] = 0;
            }
            $src = $pdo->prepare(
                'SELECT * FROM GuildSettings WHERE guildId = ? ORDER BY botId IS NULL DESC, id ASC LIMIT 1'
            );
            $src->execute([$guildId]);
            $from = $src->fetch();
            if (is_array($from)) {
                $base = gos_discord_guild_settings_copyable($from);
            }
            $row = gos_discord_guild_settings_apply_patch($base, $patch);
            $ins = $pdo->prepare(
                'INSERT INTO GuildSettings
                    (guildId, guildName, guildIcon, botId, isWatched, respondToMentions, respondToReplies,
                     respondInConversation, semanticTagging, analyzeFiles)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
            );
            $ins->execute([
                $guildId,
                $row['guildName'] !== '' ? $row['guildName'] : null,
                $row['guildIcon'] !== '' ? $row['guildIcon'] : null,
                $botId,
                (int) $row['isWatched'],
                (int) $row['respondToMentions'],
                (int) $row['respondToReplies'],
                (int) $row['respondInConversation'],
                (int) $row['semanticTagging'],
                (int) $row['analyzeFiles'],
            ]);
            $id = (int) $pdo->lastInsertId();
        }
        $fanout = gos_discord_guild_settings_guild_wide_flags($botId, $patch);
        if ($fanout !== []) {
            $fanSets = [];
            $fanParams = [];
            foreach ($fanout as $col => $v) {
                $fanSets[] = $col . ' = ?';
                $fanParams[] = $v ? 1 : 0;
            }
            $fanParams[] = $guildId;
            $pdo->prepare(
                'UPDATE GuildSettings SET ' . implode(', ', $fanSets) . ' WHERE guildId = ?'
            )->execute($fanParams);
        }
    } catch (Throwable) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'update_failed'];
    }
    $data = [
        'id' => $id,
        'guildId' => $guildId,
        'botId' => $botId,
        'guildName' => $row['guildName'] ?? '',
        'guildIcon' => $row['guildIcon'] ?? '',
    ];
    foreach (gos_discord_guild_settings_flag_columns() as $col) {
        $data[$col] = !empty($row[$col]);
    }
    return ['ok' => true, 'status' => 200, 'data' => $data, 'error' => null];
}

/**
 * @param array<string, mixed> $q
 * @return array{ok:bool,status:int,data:mixed,error:?string}|null
 */
function gos_discord_try_local_get(string $action, array $q): ?array
{
    return match ($action) {
        'users' => gos_discord_local_users($q),
        'user' => gos_discord_local_user($q),
        'audits' => gos_discord_local_audits($q),
        'channels' => gos_discord_local_channels($q),
        'guilds' => gos_discord_local_guilds($q),
        'attachments' => gos_discord_local_attachments($q),
        'messages' => gos_discord_local_messages($q),
        default => null,
    };
}

/**
 * @param array<string, mixed> $body
 * @return array{ok:bool,status:int,data:mixed,error:?string}|null
 */
function gos_discord_try_local_write(string $action, array $body): ?array
{
    return match ($action) {
        'update_guild_settings' => gos_discord_local_update_guild_settings($body),
        'media_like' => function_exists('gos_discord_media_set_like') ? gos_discord_media_set_like($body) : null,
        'media_follow' => function_exists('gos_discord_media_set_follow') ? gos_discord_media_set_follow($body) : null,
        'media_playlist_cursor' => function_exists('gos_discord_media_set_cursor') ? gos_discord_media_set_cursor($body) : null,
        default => null,
    };
}
