<?php

declare(strict_types=1);

/**
 * Device-authenticated proxy to local gbotd (headless Grok Bot).
 *
 * Phone inner app talks here. gbotd stays on loopback / UDS.
 * Do not log VNC URLs, login URLs, or the daemon token.
 */

require_once __DIR__ . '/_common.php';

/** Gateway methods the phone may call (CLI surface + read/approve extras). */
function gos_gbot_rpc_allowlist(): array
{
    return [
        'listAgents',
        'countAgents',
        'searchAgents',
        'createAgent',
        'openAgent',
        'updateAgent',
        'kickstartAgent',
        'duplicateAgent',
        'getAgentTranscript',
        'getAgentTranscriptTail',
        'getAgentTranscriptWindow',
        'getAgentThread',
        'sendPrompt',
        'promptAcceptanceStatus',
        'interruptAgentRun',
        'voteFeedback',
        'reactToMessage',
        'uploadAttachment',
        'respondToWidget',
        'dismissWidget',
        'resolveAutoReviewApproval',
        'resolveLocalToolPermission',
        'submitSecret',
        'getForeverBoxStatus',
        'ensureForeverBox',
        'handBackForeverBox',
        'getHostStatus',
        'getHostSettings',
        'setHostSettings',
        'getAsyncTasks',
        'getSubagents',
        'getTrays',
        'dismissTray',
        'searchMedia',
        'getAgentMemories',
        'deleteAgentMemory',
        'clearAgentMemories',
        'getAgentAutomations',
        'getAutomationWebhookCredential',
        'listAllAutomations',
        'setAgentAutomationEnabled',
        'createAgentAutomation',
        'updateAgentAutomation',
        'deleteAgentAutomation',
        'runAgentAutomationNow',
        'getAgentWorkflows',
        'createAgentWorkflow',
        'updateAgentWorkflow',
        'deleteAgentWorkflow',
        'runAgentWorkflowNow',
        'importAgentWorkflowText',
        'importAgentWorkflowUrl',
        'refreshMcp',
        'listBoxMcpServers',
        'getListenerIntegrations',
        'getListenerConnectUrl',
        'completeMcpOAuth',
        'getAgentChannels',
        'connectChannel',
        'disconnectChannel',
        'refreshChannel',
        'getConversationOutline',
        'skillsCatalog',
        'setAgentUnread',
        'setAgentNotificationsEnabled',
        'setAgentNotifyOnUpdates',
        'getCloudAgentInfo',
        'getBoxStoreStatus',
        'getBoxSecretsStatus',
        'getPluginSyncStatus',
        'getAgentAvatar',
        'getAgentNotificationAvatar',
        'updateBotTemplate',
        'getSharingState',
        'createRoomFromAgent',
        'createRoomInvite',
        'joinSharedRoom',
        'respondToRoomJoinRequest',
        'createSharedRoom',
        'addOwnAgentToSharedRoom',
        'removeOwnAgentFromSharedRoom',
        'leaveSharedRoom',
        'requestDiskSaverAudit',
        'startTeachRecording',
        'stopTeachRecording',
        'getTeachRecordingStatus',
        'updateHostNow',
        'autoUpdateBoxNow',
        'updateForeverBox',
        'resetForeverBox',
        'snapshotBoxStoreNow',
        'deleteAgent',
        'deleteAgents',
        'injectChromeCookies',
        'getSkillPublishTargets',
        'publishSkill',
        'resyncPublishedSkill',
        'unpublishSkill',
        'syncPluginSkills',
    ];
}

function gos_gbot_rpc_allowed(string $method): bool
{
    return in_array($method, gos_gbot_rpc_allowlist(), true);
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_gbotd_request(string $httpMethod, string $path, ?string $jsonBody, int $timeoutSec = 45): array
{
    $path = '/' . ltrim($path, '/');
    if (!preg_match('#^/[a-zA-Z0-9._/?=&%-]+$#', $path)) {
        return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'bad_path'];
    }

    $sock = trim((string) (gos_env('GROKIFY_GBOTD_SOCK', '') ?? ''));
    $base = rtrim((string) (gos_env('GROKIFY_GBOTD_URL', 'http://127.0.0.1:8780') ?? 'http://127.0.0.1:8780'), '/');
    $token = (string) (gos_env('GROKIFY_GBOTD_TOKEN', '') ?? '');

    $attempts = [];
    if ($sock !== '' && is_readable($sock)) {
        $attempts[] = ['sock' => $sock, 'url' => 'http://127.0.0.1' . $path, 'token' => ''];
    }
    if ($base !== '') {
        $attempts[] = ['sock' => '', 'url' => $base . $path, 'token' => $token];
    }
    if ($attempts === []) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'gbotd_unconfigured'];
    }

    $last = ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'gbotd_unreachable'];
    foreach ($attempts as $attempt) {
        $last = gos_gbotd_curl(
            $httpMethod,
            $attempt['url'],
            $jsonBody,
            $timeoutSec,
            $attempt['sock'],
            $attempt['token'],
        );
        if ($last['ok'] || (($last['status'] ?? 0) >= 400 && ($last['status'] ?? 0) < 500 && ($last['error'] ?? '') !== 'gbotd_unreachable')) {
            return $last;
        }
    }
    return $last;
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_gbotd_curl(
    string $httpMethod,
    string $url,
    ?string $jsonBody,
    int $timeoutSec,
    string $sock,
    string $token,
): array {
    if (!function_exists('curl_init')) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'curl_missing'];
    }
    $ch = curl_init($url);
    if ($ch === false) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'curl_init'];
    }
    $headers = ['Accept: application/json'];
    if ($jsonBody !== null) {
        $headers[] = 'Content-Type: application/json';
    }
    if ($token !== '') {
        $headers[] = 'Authorization: Bearer ' . $token;
    }
    $opts = [
        CURLOPT_CUSTOMREQUEST => $httpMethod,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => $headers,
        CURLOPT_TIMEOUT => max(3, $timeoutSec),
        CURLOPT_CONNECTTIMEOUT => 3,
        CURLOPT_FOLLOWLOCATION => false,
    ];
    if ($sock !== '') {
        $opts[CURLOPT_UNIX_SOCKET_PATH] = $sock;
    }
    if ($jsonBody !== null) {
        $opts[CURLOPT_POSTFIELDS] = $jsonBody;
    }
    curl_setopt_array($ch, $opts);
    $raw = curl_exec($ch);
    $errno = curl_errno($ch);
    $err = curl_error($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($raw === false || $errno !== 0) {
        return ['ok' => false, 'status' => 503, 'data' => null, 'error' => 'gbotd_unreachable'];
    }
    $decoded = null;
    if (is_string($raw) && $raw !== '') {
        $decoded = json_decode($raw, true);
        if ($decoded === null && json_last_error() !== JSON_ERROR_NONE) {
            $decoded = $raw;
        }
    }
    if ($status >= 400 || $status === 0) {
        $msg = null;
        if (is_array($decoded) && isset($decoded['error'])) {
            $msg = is_string($decoded['error']) ? $decoded['error'] : json_encode($decoded['error']);
        }
        return [
            'ok' => false,
            'status' => $status > 0 ? $status : 502,
            'data' => $decoded,
            'error' => $msg ?: ('gbotd_http_' . $status),
        ];
    }
    return ['ok' => true, 'status' => $status > 0 ? $status : 200, 'data' => $decoded, 'error' => null];
}

function gos_gbot_id_arg(array $args): array
{
    $id = (string) ($args['id'] ?? $args['agentId'] ?? '');
    return $id === '' ? [] : ['id' => $id];
}

/**
 * @param mixed $value
 * @return list<string>
 */
function gos_gbot_str_list($value, int $maxItems, int $maxLen): array
{
    if (!is_array($value)) {
        return [];
    }
    $out = [];
    foreach ($value as $item) {
        if (!is_string($item)) {
            continue;
        }
        $item = trim($item);
        if ($item === '' || strlen($item) > $maxLen) {
            continue;
        }
        $out[] = $item;
        if (count($out) >= $maxItems) {
            break;
        }
    }
    return $out;
}

/**
 * Chrome cookie-editor / Puppeteer-ish objects. Values stay in the RPC body
 * and are not logged.
 *
 * @param mixed $raw
 * @return list<array<string,mixed>>
 */
function gos_gbot_filter_cookies($raw): array
{
    if (!is_array($raw)) {
        return [];
    }
    $rows = $raw;
    if (isset($raw['cookies']) && is_array($raw['cookies'])) {
        $rows = $raw['cookies'];
    }
    $out = [];
    foreach ($rows as $row) {
        if (!is_array($row)) {
            continue;
        }
        $name = trim((string) ($row['name'] ?? ''));
        $domain = trim((string) ($row['domain'] ?? ''));
        $path = (string) ($row['path'] ?? '/');
        if ($name === '' || $domain === '' || !isset($row['value']) || !is_string($row['value'])) {
            continue;
        }
        if (strlen($name) > 256 || strlen($domain) > 256 || strlen($path) > 256 || strlen($row['value']) > 8192) {
            continue;
        }
        $cookie = [
            'name' => $name,
            'value' => $row['value'],
            'domain' => $domain,
            'path' => $path === '' ? '/' : $path,
        ];
        if (array_key_exists('secure', $row)) {
            $cookie['secure'] = !empty($row['secure']);
        }
        if (array_key_exists('httpOnly', $row) || array_key_exists('httponly', $row)) {
            $cookie['httpOnly'] = !empty($row['httpOnly']) || !empty($row['httponly']);
        }
        $same = strtolower(trim((string) ($row['sameSite'] ?? $row['same_site'] ?? '')));
        if (in_array($same, ['lax', 'strict', 'no_restriction', 'none', 'unspecified'], true)) {
            $cookie['sameSite'] = $same;
        }
        if (isset($row['expirationDate']) && is_numeric($row['expirationDate'])) {
            $cookie['expirationDate'] = (float) $row['expirationDate'];
        } elseif (isset($row['expires']) && is_numeric($row['expires'])) {
            $cookie['expirationDate'] = (float) $row['expires'];
        }
        $out[] = $cookie;
        if (count($out) >= 400) {
            break;
        }
    }
    return $out;
}

function gos_gbot_safe_filename(string $name): string
{
    $base = basename(str_replace('\\', '/', $name));
    $base = preg_replace('/[^A-Za-z0-9._-]+/', '_', $base) ?? '';
    $base = trim($base, '._');
    if ($base === '' || $base === '.') {
        return 'upload.bin';
    }
    if (strlen($base) > 120) {
        $base = substr($base, 0, 120);
    }
    return $base;
}

function gos_gbot_cron_ok(string $expr): bool
{
    $expr = trim(preg_replace('/\s+/', ' ', $expr) ?? '');
    $parts = $expr === '' ? [] : explode(' ', $expr);
    if (count($parts) !== 5) {
        return false;
    }
    foreach ($parts as $part) {
        if ($part === '' || strlen($part) > 32 || !preg_match('#^[0-9*,/\-]+$#', $part)) {
            return false;
        }
    }
    return true;
}

/**
 * @param mixed $trigger
 * @return array<string,mixed>|null
 */
function gos_gbot_filter_trigger($trigger): ?array
{
    if (!is_array($trigger)) {
        return null;
    }
    $type = (string) ($trigger['type'] ?? '');
    if ($type === 'cron') {
        $schedule = trim(preg_replace('/\s+/', ' ', (string) ($trigger['schedule'] ?? '')) ?? '');
        if (!gos_gbot_cron_ok($schedule)) {
            return null;
        }
        return ['type' => 'cron', 'schedule' => $schedule];
    }
    if ($type === 'group') {
        $listeners = $trigger['listeners'] ?? [];
        if (!is_array($listeners)) {
            return null;
        }
        $out = [];
        foreach ($listeners as $listener) {
            $one = gos_gbot_filter_trigger($listener);
            if ($one !== null) {
                $out[] = $one;
            }
        }
        if ($out === []) {
            return null;
        }
        if (count($out) === 1) {
            return $out[0];
        }
        return ['type' => 'group', 'listeners' => $out];
    }
    if ($type === 'webhook') {
        return ['type' => 'webhook'];
    }
    $events = ['slack', 'github', 'microsoftTeams', 'linear', 'sentry', 'pagerduty'];
    if (!in_array($type, $events, true)) {
        return null;
    }
    $out = ['type' => $type];
    foreach ($trigger as $key => $value) {
        if (!is_string($key) || $key === 'type' || strlen($key) > 40) {
            continue;
        }
        if (is_string($value) && strlen($value) <= 400) {
            $out[$key] = $value;
        } elseif (is_bool($value) || is_int($value) || is_float($value)) {
            $out[$key] = $value;
        }
    }
    return $out;
}

/**
 * @param mixed $spec
 * @return array{name:string,prompt:string,trigger:array<string,mixed>,isEnabled:bool}
 */
function gos_gbot_filter_automation_spec($spec): array
{
    $empty = [
        'name' => '',
        'prompt' => '',
        'trigger' => ['type' => 'cron', 'schedule' => ''],
        'isEnabled' => false,
    ];
    if (!is_array($spec)) {
        return $empty;
    }
    $name = trim((string) ($spec['name'] ?? ''));
    if (strlen($name) > 80) {
        $name = substr($name, 0, 80);
    }
    $prompt = (string) ($spec['prompt'] ?? '');
    if (strlen($prompt) > 100000) {
        $prompt = substr($prompt, 0, 100000);
    }
    $trigger = gos_gbot_filter_trigger($spec['trigger'] ?? null);
    return [
        'name' => $name,
        'prompt' => $prompt,
        'trigger' => $trigger ?? ['type' => 'cron', 'schedule' => ''],
        'isEnabled' => !empty($spec['isEnabled']),
    ];
}

function gos_gbot_filter_rpc_args(string $method, array $args): array
{
    if ($method === 'setHostSettings') {
        $out = [];
        if (isset($args['localToolPermission']) && is_string($args['localToolPermission'])) {
            $v = $args['localToolPermission'];
            if (in_array($v, ['always', 'ask', 'never'], true)) {
                $out['localToolPermission'] = $v;
            }
        }
        if (isset($args['userTimeZoneOverride']) && is_string($args['userTimeZoneOverride'])) {
            $out['userTimeZoneOverride'] = $args['userTimeZoneOverride'];
        }
        if (isset($args['autoReviewInstructions']) && is_array($args['autoReviewInstructions'])) {
            $cur = $args['autoReviewInstructions'];
            $out['autoReviewInstructions'] = [
                'isEnabled' => !empty($cur['isEnabled']),
                'allowInstructions' => is_array($cur['allowInstructions'] ?? null) ? $cur['allowInstructions'] : [],
                'blockInstructions' => is_array($cur['blockInstructions'] ?? null) ? $cur['blockInstructions'] : [],
            ];
        }
        return $out;
    }
    if ($method === 'createAgent') {
        $name = trim((string) ($args['name'] ?? ''));
        return [
            'name' => $name,
            'description' => (string) ($args['description'] ?? ''),
            'origin' => 'user',
            'isKickstartRequested' => !empty($args['isKickstartRequested']),
        ];
    }
    if ($method === 'sendPrompt') {
        $out = [
            'prompt' => (string) ($args['prompt'] ?? ''),
            'agentId' => (string) ($args['agentId'] ?? ''),
            'clientNonce' => (string) ($args['clientNonce'] ?? ''),
            'enterEpochMs' => (int) ($args['enterEpochMs'] ?? (int) round(microtime(true) * 1000)),
        ];
        $paths = gos_gbot_str_list($args['attachmentPaths'] ?? null, 8, 500);
        if ($paths !== []) {
            $out['attachmentPaths'] = $paths;
        }
        $names = [];
        foreach (gos_gbot_str_list($args['attachmentNames'] ?? null, 8, 120) as $name) {
            $names[] = gos_gbot_safe_filename($name);
        }
        if ($names !== []) {
            $out['attachmentNames'] = $names;
        }
        $reply = trim((string) ($args['replyToId'] ?? ''));
        if ($reply !== '' && strlen($reply) <= 160) {
            $out['replyToId'] = $reply;
        }
        if (!empty($args['isFork'])) {
            $out['isFork'] = true;
        }
        $rich = (string) ($args['richText'] ?? '');
        if ($rich !== '' && strlen($rich) <= 200000) {
            $out['richText'] = $rich;
        }
        return $out;
    }
    if ($method === 'uploadAttachment') {
        $b64 = preg_replace('/\s+/', '', (string) ($args['bytesBase64'] ?? '')) ?? '';
        if (strlen($b64) > 6_000_000 || !preg_match('#^[A-Za-z0-9+/]*={0,2}$#', $b64)) {
            $b64 = '';
        }
        return [
            'agentId' => (string) ($args['agentId'] ?? $args['id'] ?? ''),
            'filename' => gos_gbot_safe_filename((string) ($args['filename'] ?? 'upload.bin')),
            'bytesBase64' => $b64,
        ];
    }
    if ($method === 'interruptAgentRun' || $method === 'requestDiskSaverAudit' || $method === 'clearAgentMemories' || $method === 'getAgentAvatar' || $method === 'getAgentNotificationAvatar' || $method === 'updateForeverBox') {
        return gos_gbot_id_arg($args);
    }
    if ($method === 'voteFeedback') {
        $action = (string) ($args['action'] ?? '');
        $out = [
            'agentId' => (string) ($args['agentId'] ?? $args['id'] ?? ''),
            'entryId' => (string) ($args['entryId'] ?? ''),
            'action' => in_array($action, ['up', 'down', 'submit', 'revert'], true) ? $action : '',
        ];
        if ($out['action'] === 'submit') {
            $out['categories'] = gos_gbot_str_list($args['categories'] ?? null, 12, 80);
            $comment = trim((string) ($args['comment'] ?? ''));
            if ($comment !== '' && strlen($comment) <= 2000) {
                $out['comment'] = $comment;
            }
        }
        return $out;
    }
    if ($method === 'reactToMessage') {
        $emoji = trim((string) ($args['emoji'] ?? ''));
        if (strlen($emoji) > 16) {
            $emoji = substr($emoji, 0, 16);
        }
        return [
            'entryId' => (string) ($args['entryId'] ?? ''),
            'emoji' => $emoji,
            'agentId' => (string) ($args['agentId'] ?? $args['id'] ?? ''),
        ];
    }
    if ($method === 'getAutomationWebhookCredential') {
        return [
            'id' => (string) ($args['id'] ?? $args['agentId'] ?? ''),
            'automationId' => (string) ($args['automationId'] ?? ''),
        ];
    }
    if ($method === 'deleteAgentMemory') {
        return [
            'id' => (string) ($args['id'] ?? $args['agentId'] ?? ''),
            'memoryId' => (string) ($args['memoryId'] ?? ''),
        ];
    }
    if ($method === 'createAgentAutomation') {
        return [
            'id' => (string) ($args['id'] ?? $args['agentId'] ?? ''),
            'spec' => gos_gbot_filter_automation_spec($args['spec'] ?? null),
        ];
    }
    if ($method === 'deleteAgentAutomation') {
        return [
            'id' => (string) ($args['id'] ?? $args['agentId'] ?? ''),
            'automationId' => (string) ($args['automationId'] ?? ''),
        ];
    }
    if ($method === 'deleteAgentWorkflow') {
        return [
            'id' => (string) ($args['id'] ?? $args['agentId'] ?? ''),
            'workflowId' => (string) ($args['workflowId'] ?? ''),
        ];
    }
    if ($method === 'importAgentWorkflowText') {
        $md = (string) ($args['markdown'] ?? '');
        if (strlen($md) > 200000) {
            $md = substr($md, 0, 200000);
        }
        $out = [
            'id' => (string) ($args['id'] ?? $args['agentId'] ?? ''),
            'markdown' => $md,
        ];
        $name = trim((string) ($args['name'] ?? ''));
        if ($name !== '') {
            $out['name'] = strlen($name) > 80 ? substr($name, 0, 80) : $name;
        }
        return $out;
    }
    if ($method === 'importAgentWorkflowUrl') {
        $url = trim((string) ($args['url'] ?? ''));
        if (strlen($url) > 500 || !preg_match('#^https://#i', $url)) {
            $url = '';
        }
        $out = [
            'id' => (string) ($args['id'] ?? $args['agentId'] ?? ''),
            'url' => $url,
        ];
        $name = trim((string) ($args['name'] ?? ''));
        if ($name !== '') {
            $out['name'] = strlen($name) > 80 ? substr($name, 0, 80) : $name;
        }
        return $out;
    }
    if ($method === 'createAgentWorkflow' || $method === 'updateAgentWorkflow') {
        $specIn = is_array($args['spec'] ?? null) ? $args['spec'] : [];
        $name = trim((string) ($specIn['name'] ?? $args['name'] ?? ''));
        if (strlen($name) > 80) {
            $name = substr($name, 0, 80);
        }
        $description = (string) ($specIn['description'] ?? $args['description'] ?? '');
        if (strlen($description) > 4000) {
            $description = substr($description, 0, 4000);
        }
        $out = [
            'id' => (string) ($args['id'] ?? $args['agentId'] ?? ''),
            'spec' => [
                'name' => $name,
                'description' => $description,
            ],
        ];
        if ($method === 'updateAgentWorkflow') {
            $out['workflowId'] = (string) ($args['workflowId'] ?? '');
        }
        return $out;
    }
    if ($method === 'startTeachRecording' || $method === 'stopTeachRecording') {
        return gos_gbot_id_arg($args);
    }
    if (in_array($method, [
        'getTeachRecordingStatus',
        'getSharingState',
        'autoUpdateBoxNow',
        'getSkillPublishTargets',
        'getBoxSecretsStatus',
        'getBoxStoreStatus',
        'snapshotBoxStoreNow',
        'syncPluginSkills',
        'getPluginSyncStatus',
    ], true)) {
        return [];
    }
    if ($method === 'deleteAgent' || $method === 'resetForeverBox') {
        return gos_gbot_id_arg($args);
    }
    if ($method === 'deleteAgents') {
        $ids = gos_gbot_str_list($args['ids'] ?? null, 40, 160);
        if ($ids === [] && isset($args['ids']) && is_string($args['ids'])) {
            $ids = gos_gbot_str_list(explode(',', $args['ids']), 40, 160);
        }
        if ($ids === []) {
            $one = trim((string) ($args['id'] ?? $args['agentId'] ?? ''));
            if ($one !== '' && strlen($one) <= 160) {
                $ids = [$one];
            }
        }
        return ['ids' => array_values(array_unique($ids))];
    }
    if ($method === 'injectChromeCookies') {
        return ['cookies' => gos_gbot_filter_cookies($args['cookies'] ?? $args)];
    }
    if ($method === 'createRoomFromAgent') {
        $id = trim((string) ($args['agentId'] ?? $args['id'] ?? ''));
        return $id === '' ? [] : ['agentId' => $id];
    }
    if ($method === 'createRoomInvite' || $method === 'leaveSharedRoom') {
        $out = [];
        $room = trim((string) ($args['roomId'] ?? $args['id'] ?? ''));
        if ($room !== '' && strlen($room) <= 200) {
            $out['roomId'] = $room;
        }
        $target = trim((string) ($args['targetAuthId'] ?? ''));
        if ($method === 'leaveSharedRoom' && $target !== '' && strlen($target) <= 200) {
            $out['targetAuthId'] = $target;
        }
        return $out;
    }
    if ($method === 'joinSharedRoom') {
        $link = trim((string) ($args['link'] ?? $args['url'] ?? $args['shareUrl'] ?? ''));
        if (strlen($link) > 800) {
            $link = '';
        }
        return $link === '' ? [] : ['link' => $link];
    }
    if ($method === 'respondToRoomJoinRequest') {
        return [
            'requestId' => trim((string) ($args['requestId'] ?? $args['id'] ?? '')),
            'isApproved' => !empty($args['isApproved']) || !empty($args['approved']),
        ];
    }
    if ($method === 'createSharedRoom') {
        $name = trim((string) ($args['name'] ?? ''));
        if (strlen($name) > 80) {
            $name = substr($name, 0, 80);
        }
        $agents = [];
        $rawAgents = $args['agents'] ?? null;
        if (!is_array($rawAgents)) {
            $rawAgents = [];
        }
        foreach ($rawAgents as $row) {
            $agentId = '';
            if (is_string($row)) {
                $agentId = trim($row);
            } elseif (is_array($row)) {
                $agentId = trim((string) ($row['agentId'] ?? $row['id'] ?? ''));
            }
            if ($agentId === '' || strlen($agentId) > 160) {
                continue;
            }
            $agents[] = ['agentId' => $agentId];
            if (count($agents) >= 12) {
                break;
            }
        }
        $out = ['agents' => $agents];
        if ($name !== '') {
            $out['name'] = $name;
        }
        return $out;
    }
    if ($method === 'addOwnAgentToSharedRoom' || $method === 'removeOwnAgentFromSharedRoom') {
        return [
            'roomId' => trim((string) ($args['roomId'] ?? '')),
            'agentId' => trim((string) ($args['agentId'] ?? $args['id'] ?? '')),
        ];
    }
    if ($method === 'publishSkill' || $method === 'resyncPublishedSkill' || $method === 'unpublishSkill') {
        $out = [
            'workflowId' => trim((string) ($args['workflowId'] ?? $args['id'] ?? $args['skillId'] ?? '')),
        ];
        if ($method === 'publishSkill') {
            $team = $args['teamId'] ?? $args['team'] ?? 0;
            $out['teamId'] = is_numeric($team) ? (int) $team : 0;
        }
        return $out;
    }
    if ($method === 'updateHostNow') {
        return ['force' => !isset($args['force']) || !empty($args['force'])];
    }
    if ($method === 'updateBotTemplate') {
        $out = gos_gbot_id_arg($args);
        if (isset($args['isEnabled'])) {
            $out['isEnabled'] = !empty($args['isEnabled']);
        }
        $name = trim((string) ($args['name'] ?? ''));
        if ($name !== '' && strlen($name) <= 80) {
            $out['name'] = $name;
        }
        return $out;
    }
    if ($method === 'searchAgents') {
        $out = ['query' => trim((string) ($args['query'] ?? ''))];
        if (isset($args['limit'])) {
            $out['limit'] = max(1, min(40, (int) $args['limit']));
        }
        return $out;
    }
    if (in_array($method, ['openAgent', 'kickstartAgent', 'duplicateAgent', 'getForeverBoxStatus', 'ensureForeverBox', 'getAgentMemories', 'getAgentAutomations', 'getAgentWorkflows', 'getAgentTranscript', 'getAgentTranscriptTail', 'getConversationOutline', 'getAsyncTasks', 'getSubagents', 'getCloudAgentInfo', 'getAgentChannels'], true)) {
        $out = gos_gbot_id_arg($args);
        if ($method === 'getAgentTranscriptTail' || $method === 'getAgentTranscript') {
            $out['limit'] = max(8, min(400, (int) ($args['limit'] ?? 80)));
        }
        return $out;
    }
    if ($method === 'getAgentTranscriptWindow') {
        $out = gos_gbot_id_arg($args);
        $before = (string) ($args['beforeId'] ?? $args['entryId'] ?? '');
        if ($before !== '') {
            $out['beforeId'] = $before;
        }
        $out['limit'] = max(8, min(200, (int) ($args['limit'] ?? 40)));
        return $out;
    }
    if ($method === 'handBackForeverBox') {
        $out = gos_gbot_id_arg($args);
        $trigger = (string) ($args['trigger'] ?? 'button');
        $out['trigger'] = in_array($trigger, ['button', 'dismissed'], true) ? $trigger : 'button';
        return $out;
    }
    if ($method === 'runAgentWorkflowNow') {
        return [
            'id' => (string) ($args['id'] ?? ''),
            'workflowId' => (string) ($args['workflowId'] ?? ''),
        ];
    }
    if ($method === 'setAgentAutomationEnabled') {
        return [
            'id' => (string) ($args['id'] ?? ''),
            'automationId' => (string) ($args['automationId'] ?? ''),
            'isEnabled' => !empty($args['isEnabled']),
        ];
    }
    if ($method === 'runAgentAutomationNow') {
        return [
            'id' => (string) ($args['id'] ?? ''),
            'automationId' => (string) ($args['automationId'] ?? ''),
        ];
    }
    if ($method === 'updateAgentAutomation') {
        $spec = gos_gbot_filter_automation_spec($args['spec'] ?? null);
        return [
            'id' => (string) ($args['id'] ?? ''),
            'automationId' => (string) ($args['automationId'] ?? ''),
            'spec' => $spec,
        ];
    }
    if ($method === 'getListenerConnectUrl') {
        return ['platform' => (string) ($args['platform'] ?? '')];
    }
    if (in_array($method, ['connectChannel', 'disconnectChannel', 'refreshChannel'], true)) {
        return [
            'id' => (string) ($args['id'] ?? ''),
            'platform' => (string) ($args['platform'] ?? ''),
        ];
    }
    if ($method === 'listBoxMcpServers') {
        $ids = $args['serverIdentifiers'] ?? [];
        $out = [];
        if (is_array($ids)) {
            foreach ($ids as $id) {
                if (!is_string($id) || $id === '' || strlen($id) > 200) {
                    continue;
                }
                $out[] = $id;
                if (count($out) >= 40) {
                    break;
                }
            }
        }
        return ['serverIdentifiers' => $out];
    }
    if ($method === 'setAgentUnread') {
        return [
            'id' => (string) ($args['id'] ?? ''),
            'hasUnread' => !empty($args['hasUnread']),
        ];
    }
    return $args;
}

function gos_gbot_send(array $result): never
{
    $code = (int) ($result['status'] ?? 200);
    if ($code < 100 || $code > 599) {
        $code = $result['ok'] ? 200 : 502;
    }
    $payload = [
        'ok' => (bool) $result['ok'],
        'data' => $result['data'],
    ];
    if (!empty($result['error'])) {
        $payload['error'] = $result['error'];
    }
    gos_api_json($payload, $code);
}

function gos_gbot_login_uuid(string $raw): string
{
    $uuid = trim($raw);
    if ($uuid === '' || !preg_match('/^[A-Za-z0-9._:-]{8,128}$/', $uuid)) {
        return '';
    }
    return $uuid;
}

function gos_gbot_handle_login_start(): never
{
    gos_gbot_send(gos_gbotd_request('POST', '/v1/login/start', '{}', 20));
}

function gos_gbot_handle_login_wait(string $uuid): never
{
    if ($uuid === '') {
        gos_api_json(['ok' => false, 'error' => 'uuid_required'], 400);
    }
    gos_gbot_send(gos_gbotd_request('GET', '/v1/login/wait?uuid=' . rawurlencode($uuid), null, 25));
}

/**
 * Strip automation prompts and keep only the newest few runs so the phone
 * snapshot stays small enough to poll in the background.
 *
 * @param mixed $data
 * @return list<array<string,mixed>>
 */
function gos_gbot_slim_automations($data): array
{
    if (!is_array($data)) {
        return [];
    }
    $rows = $data;
    if (isset($data['automations']) && is_array($data['automations'])) {
        $rows = $data['automations'];
    }
    $out = [];
    foreach ($rows as $row) {
        if (!is_array($row)) {
            continue;
        }
        $agentId = (string) ($row['agentId'] ?? '');
        $auto = $row['automation'] ?? $row;
        if (!is_array($auto)) {
            continue;
        }
        $id = (string) ($auto['id'] ?? '');
        if ($id === '') {
            continue;
        }
        $runs = [];
        $runsIn = $auto['runs'] ?? [];
        if (is_array($runsIn)) {
            $n = 0;
            foreach ($runsIn as $run) {
                if (!is_array($run) || $n >= 3) {
                    break;
                }
                $runs[] = [
                    'id' => (string) ($run['id'] ?? ''),
                    'trigger' => (string) ($run['trigger'] ?? ''),
                    'startedAt' => (int) ($run['startedAt'] ?? 0),
                    'finishedAt' => (int) ($run['finishedAt'] ?? 0),
                    'status' => (string) ($run['status'] ?? ''),
                ];
                $n++;
            }
        }
        $trigger = is_array($auto['trigger'] ?? null) ? $auto['trigger'] : [];
        $out[] = [
            'agentId' => $agentId !== '' ? $agentId : (string) ($auto['agentId'] ?? ''),
            'id' => $id,
            'name' => (string) ($auto['name'] ?? $id),
            'isEnabled' => !empty($auto['isEnabled']),
            'schedule' => (string) ($auto['triggerDescription'] ?? $auto['schedule'] ?? ''),
            'lastRunAt' => (int) ($auto['lastRunAt'] ?? 0),
            'nextRunAt' => (int) ($auto['nextRunAt'] ?? 0),
            'trigger' => isset($trigger['type']) ? ['type' => (string) $trigger['type']] : null,
            'runs' => $runs,
        ];
    }
    return $out;
}

function gos_gbot_handle_snapshot(): never
{
    $health = gos_gbotd_request('GET', '/v1/health', null, 8);
    if (!$health['ok']) {
        gos_gbot_send($health);
    }
    $status = gos_gbotd_request('GET', '/v1/status', null, 12);
    $pending = gos_gbotd_request('GET', '/v1/pending', null, 15);
    $agents = gos_gbotd_request('POST', '/api/listAgents', '{}', 20);
    $host = gos_gbotd_request('POST', '/api/getHostStatus', '{}', 15);
    $settings = gos_gbotd_request('POST', '/api/getHostSettings', '{}', 15);
    $autos = gos_gbotd_request('POST', '/api/listAllAutomations', '{}', 15);
    $computers = gos_gbotd_request('GET', '/v1/computers', null, 8);
    gos_api_json([
        'ok' => true,
        'data' => [
            'health' => $health['data'],
            'status' => $status['ok'] ? $status['data'] : null,
            'pending' => $pending['ok'] ? $pending['data'] : ['cards' => []],
            'agents' => $agents['ok'] ? $agents['data'] : [],
            'host' => $host['ok'] ? $host['data'] : null,
            'settings' => $settings['ok'] ? $settings['data'] : null,
            'automations' => $autos['ok'] ? gos_gbot_slim_automations($autos['data']) : [],
            'computers' => $computers['ok'] ? $computers['data'] : null,
            'upstream' => [
                'status_ok' => $status['ok'],
                'pending_ok' => $pending['ok'],
                'agents_ok' => $agents['ok'],
                'host_ok' => $host['ok'],
                'settings_ok' => $settings['ok'],
                'automations_ok' => $autos['ok'],
                'computers_ok' => $computers['ok'],
            ],
        ],
    ]);
}

if (PHP_SAPI === 'cli') {
    return;
}

gos_require_access();

$httpMethod = strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'));
if ($httpMethod === 'GET') {
    $action = strtolower(trim((string) ($_GET['action'] ?? 'snapshot')));
    if ($action === 'health') {
        gos_gbot_send(gos_gbotd_request('GET', '/v1/health', null, 8));
    }
    if ($action === 'status') {
        gos_gbot_send(gos_gbotd_request('GET', '/v1/status', null, 12));
    }
    if ($action === 'pending') {
        $agent = trim((string) ($_GET['agent_id'] ?? ''));
        $q = $agent !== '' ? ('?agentId=' . rawurlencode($agent)) : '';
        gos_gbot_send(gos_gbotd_request('GET', '/v1/pending' . $q, null, 15));
    }
    if ($action === 'vnc') {
        $agent = trim((string) ($_GET['agent_id'] ?? ''));
        if ($agent === '') {
            gos_api_json(['ok' => false, 'error' => 'agent_id_required'], 400);
        }
        gos_gbot_send(gos_gbotd_request('GET', '/v1/vnc?agentId=' . rawurlencode($agent), null, 20));
    }
    if ($action === 'login_wait') {
        gos_gbot_handle_login_wait(gos_gbot_login_uuid((string) ($_GET['uuid'] ?? '')));
    }
    if ($action === 'snapshot') {
        gos_gbot_handle_snapshot();
    }
    if ($action === 'computers') {
        gos_gbot_send(gos_gbotd_request('GET', '/v1/computers', null, 8));
    }
    gos_api_json(['ok' => false, 'error' => 'unknown_action'], 404);
}

if ($httpMethod !== 'POST') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

$body = gos_json_body();
$action = strtolower(trim((string) ($body['action'] ?? 'rpc')));
if ($action === 'login_start') {
    gos_gbot_handle_login_start();
}
if ($action === 'login_wait') {
    gos_gbot_handle_login_wait(gos_gbot_login_uuid((string) ($body['uuid'] ?? '')));
}
if ($action === 'computers_register') {
    gos_gbot_send(gos_gbotd_request('POST', '/v1/computers/register', '{}', 20));
}
if ($action !== 'rpc') {
    gos_api_json(['ok' => false, 'error' => 'unknown_action'], 400);
}

$method = trim((string) ($body['method'] ?? ''));
if ($method === '' || !preg_match('/^[A-Za-z][A-Za-z0-9]{1,80}$/', $method) || !gos_gbot_rpc_allowed($method)) {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 404);
}

$args = $body['args'] ?? [];
if (!is_array($args)) {
    gos_api_json(['ok' => false, 'error' => 'invalid_args'], 400);
}
$args = gos_gbot_filter_rpc_args($method, $args);
if ($method === 'deleteAgent') {
    $id = trim((string) ($args['id'] ?? ''));
    if ($id === '') {
        gos_api_json(['ok' => false, 'error' => 'id_required'], 400);
    }
    $method = 'deleteAgents';
    $args = ['ids' => [$id]];
}
if ($method === 'createAgent' && trim((string) ($args['name'] ?? '')) === '') {
    gos_api_json(['ok' => false, 'error' => 'name_required'], 400);
}
if ($method === 'sendPrompt') {
    $hasAttach = is_array($args['attachmentPaths'] ?? null) && $args['attachmentPaths'] !== [];
    if (trim((string) ($args['agentId'] ?? '')) === '' || (trim((string) ($args['prompt'] ?? '')) === '' && !$hasAttach)) {
        gos_api_json(['ok' => false, 'error' => 'prompt_required'], 400);
    }
}
if ($method === 'uploadAttachment' && (trim((string) ($args['agentId'] ?? '')) === '' || trim((string) ($args['bytesBase64'] ?? '')) === '')) {
    gos_api_json(['ok' => false, 'error' => 'attachment_required'], 400);
}
if ($method === 'interruptAgentRun' && trim((string) ($args['id'] ?? '')) === '') {
    gos_api_json(['ok' => false, 'error' => 'id_required'], 400);
}
if ($method === 'voteFeedback' && (trim((string) ($args['agentId'] ?? '')) === '' || trim((string) ($args['entryId'] ?? '')) === '' || trim((string) ($args['action'] ?? '')) === '')) {
    gos_api_json(['ok' => false, 'error' => 'vote_required'], 400);
}
if ($method === 'getAutomationWebhookCredential' && (trim((string) ($args['id'] ?? '')) === '' || trim((string) ($args['automationId'] ?? '')) === '')) {
    gos_api_json(['ok' => false, 'error' => 'automation_required'], 400);
}
if ($method === 'deleteAgentMemory' && (trim((string) ($args['id'] ?? '')) === '' || trim((string) ($args['memoryId'] ?? '')) === '')) {
    gos_api_json(['ok' => false, 'error' => 'memory_required'], 400);
}
if (($method === 'deleteAgent' || $method === 'resetForeverBox') && trim((string) ($args['id'] ?? '')) === '') {
    gos_api_json(['ok' => false, 'error' => 'id_required'], 400);
}
if ($method === 'deleteAgents' && (!isset($args['ids']) || !is_array($args['ids']) || $args['ids'] === [])) {
    gos_api_json(['ok' => false, 'error' => 'ids_required'], 400);
}
if ($method === 'injectChromeCookies' && (!isset($args['cookies']) || !is_array($args['cookies']) || $args['cookies'] === [])) {
    gos_api_json(['ok' => false, 'error' => 'cookies_required'], 400);
}
if ($method === 'createRoomFromAgent' && trim((string) ($args['agentId'] ?? '')) === '') {
    gos_api_json(['ok' => false, 'error' => 'agent_required'], 400);
}
if (($method === 'createRoomInvite' || $method === 'leaveSharedRoom') && trim((string) ($args['roomId'] ?? '')) === '') {
    gos_api_json(['ok' => false, 'error' => 'room_required'], 400);
}
if ($method === 'joinSharedRoom' && trim((string) ($args['link'] ?? '')) === '') {
    gos_api_json(['ok' => false, 'error' => 'link_required'], 400);
}
if ($method === 'respondToRoomJoinRequest' && trim((string) ($args['requestId'] ?? '')) === '') {
    gos_api_json(['ok' => false, 'error' => 'request_required'], 400);
}
if ($method === 'createSharedRoom' && (!isset($args['agents']) || !is_array($args['agents']) || $args['agents'] === [])) {
    gos_api_json(['ok' => false, 'error' => 'agents_required'], 400);
}
if (($method === 'addOwnAgentToSharedRoom' || $method === 'removeOwnAgentFromSharedRoom') && (trim((string) ($args['roomId'] ?? '')) === '' || trim((string) ($args['agentId'] ?? '')) === '')) {
    gos_api_json(['ok' => false, 'error' => 'room_agent_required'], 400);
}
if (($method === 'publishSkill' || $method === 'unpublishSkill' || $method === 'resyncPublishedSkill') && trim((string) ($args['workflowId'] ?? '')) === '') {
    gos_api_json(['ok' => false, 'error' => 'workflow_required'], 400);
}
if ($method === 'publishSkill' && (int) ($args['teamId'] ?? 0) <= 0) {
    gos_api_json(['ok' => false, 'error' => 'team_required'], 400);
}
if ($method === 'createAgentAutomation') {
    $spec = is_array($args['spec'] ?? null) ? $args['spec'] : [];
    $trigger = is_array($spec['trigger'] ?? null) ? $spec['trigger'] : [];
    if (trim((string) ($args['id'] ?? '')) === '' || trim((string) ($spec['name'] ?? '')) === '' || trim((string) ($spec['prompt'] ?? '')) === '') {
        gos_api_json(['ok' => false, 'error' => 'spec_required'], 400);
    }
    if ($trigger === [] || ($trigger['type'] ?? '') === '') {
        gos_api_json(['ok' => false, 'error' => 'schedule_required'], 400);
    }
}
if ($method === 'importAgentWorkflowText' && (trim((string) ($args['id'] ?? '')) === '' || trim((string) ($args['markdown'] ?? '')) === '')) {
    gos_api_json(['ok' => false, 'error' => 'markdown_required'], 400);
}
if ($method === 'importAgentWorkflowUrl' && (trim((string) ($args['id'] ?? '')) === '' || trim((string) ($args['url'] ?? '')) === '')) {
    gos_api_json(['ok' => false, 'error' => 'url_required'], 400);
}
if ($method === 'setHostSettings' && $args === []) {
    gos_api_json(['ok' => false, 'error' => 'empty_settings'], 400);
}
if ($method === 'searchAgents' && trim((string) ($args['query'] ?? '')) === '') {
    gos_api_json(['ok' => false, 'error' => 'query_required'], 400);
}
if (in_array($method, ['openAgent', 'kickstartAgent', 'duplicateAgent', 'ensureForeverBox', 'getForeverBoxStatus'], true) && trim((string) ($args['id'] ?? '')) === '') {
    gos_api_json(['ok' => false, 'error' => 'id_required'], 400);
}
if ($method === 'updateAgentAutomation') {
    $spec = is_array($args['spec'] ?? null) ? $args['spec'] : [];
    $trigger = is_array($spec['trigger'] ?? null) ? $spec['trigger'] : [];
    $schedule = (string) ($trigger['schedule'] ?? '');
    if (trim((string) ($args['id'] ?? '')) === '' || trim((string) ($args['automationId'] ?? '')) === '') {
        gos_api_json(['ok' => false, 'error' => 'automation_required'], 400);
    }
    if (trim((string) ($spec['name'] ?? '')) === '' || trim((string) ($spec['prompt'] ?? '')) === '') {
        gos_api_json(['ok' => false, 'error' => 'spec_required'], 400);
    }
    if (($trigger['type'] ?? '') === 'cron' && !gos_gbot_cron_ok($schedule)) {
        gos_api_json(['ok' => false, 'error' => 'schedule_required'], 400);
    }
    if ($trigger === [] || ($trigger['type'] ?? '') === '') {
        gos_api_json(['ok' => false, 'error' => 'schedule_required'], 400);
    }
}

$timeout = 45;
if ($method === 'ensureForeverBox') {
    $timeout = 90;
} elseif ($method === 'sendPrompt') {
    $timeout = 30;
} elseif ($method === 'uploadAttachment') {
    $timeout = 60;
} elseif (in_array($method, ['updateHostNow', 'autoUpdateBoxNow', 'updateForeverBox'], true)) {
    $timeout = 90;
} elseif ($method === 'searchAgents') {
    $timeout = 20;
}
$payload = json_encode($args, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
if (!is_string($payload)) {
    gos_api_json(['ok' => false, 'error' => 'invalid_args'], 400);
}
gos_gbot_send(gos_gbotd_request('POST', '/api/' . $method, $payload, $timeout));
