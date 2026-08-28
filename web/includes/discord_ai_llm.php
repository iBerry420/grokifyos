<?php

declare(strict_types=1);

/**
 * Discord AI LLM routing: SpaceXAI chat completions or GrokifyOS bridge (Grok Build).
 * Model lists are fetched live so new text models show up without a code change.
 */

const GOS_DISCORD_AI_DEFAULT_MODEL = 'grok-4.6';
const GOS_DISCORD_AI_DEFAULT_EFFORT = 'high';
const GOS_DISCORD_AI_SPACEXAI_BASE = 'https://api.x.ai/v1';

/**
 * @return 'spacexai'|'bridge'
 */
function gos_discord_ai_provider_ok(string $raw): string
{
    $p = strtolower(trim($raw));
    if ($p === 'bridge' || $p === 'grok-build' || $p === 'gb' || $p === 'grokify') {
        return 'bridge';
    }

    return 'spacexai';
}

function gos_discord_ai_model_ok(string $raw, string $fallback = GOS_DISCORD_AI_DEFAULT_MODEL): string
{
    $m = strtolower(trim($raw));
    $m = (string) preg_replace('/^(gb:|grok:)/', '', $m);
    if ($m === '' || preg_match('/^[a-z0-9][a-z0-9._-]{0,79}$/', $m) !== 1) {
        return $fallback !== '' ? $fallback : GOS_DISCORD_AI_DEFAULT_MODEL;
    }

    return $m;
}

function gos_discord_ai_model_supports_reasoning(string $model): bool
{
    $real = gos_discord_ai_model_ok($model);
    if (str_contains($real, 'reasoning')) {
        return true;
    }
    if (preg_match('/^grok-(\d+)(?:\.(\d+))?/', $real, $m) === 1) {
        $major = (int) $m[1];
        $minor = isset($m[2]) ? (int) $m[2] : 0;
        if ($major > 4) {
            return true;
        }
        if ($major === 4 && $minor >= 5) {
            return true;
        }
    }

    return false;
}

/**
 * @return list<string>
 */
function gos_discord_ai_efforts_for_model(string $model): array
{
    if (!gos_discord_ai_model_supports_reasoning($model)) {
        return [];
    }
    if (function_exists('gos_reasoning_efforts_for_model')) {
        return gos_reasoning_efforts_for_model($model);
    }
    $real = gos_discord_ai_model_ok($model);
    if (preg_match('/^grok-(\d+)(?:\.(\d+))?/', $real, $m) === 1) {
        $major = (int) $m[1];
        $minor = isset($m[2]) ? (int) $m[2] : 0;
        if ($major > 4 || ($major === 4 && $minor >= 6)) {
            return ['low', 'medium', 'high', 'xhigh'];
        }
    }

    return ['low', 'medium', 'high'];
}

function gos_discord_ai_effort_ok(string $model, string $effort): string
{
    $allowed = gos_discord_ai_efforts_for_model($model);
    if ($allowed === []) {
        return '';
    }
    $req = strtolower(trim($effort));
    if (in_array($req, $allowed, true)) {
        return $req;
    }
    if (function_exists('gos_default_reasoning_effort_for_model')) {
        $def = gos_default_reasoning_effort_for_model($model);
        if (in_array($def, $allowed, true)) {
            return $def;
        }
    }

    return in_array(GOS_DISCORD_AI_DEFAULT_EFFORT, $allowed, true)
        ? GOS_DISCORD_AI_DEFAULT_EFFORT
        : $allowed[count($allowed) - 1];
}

function gos_discord_ai_key_hint(string $key): string
{
    $k = trim($key);
    if ($k === '') {
        return '';
    }
    $len = strlen($k);

    return $len <= 4 ? str_repeat('•', $len) : ('…' . substr($k, -4));
}

function gos_discord_ai_xai_key(): string
{
    if (function_exists('gos_setting_get')) {
        $stored = trim((string) gos_setting_get('discord_ai_spacexai_key', ''));
        if ($stored !== '') {
            return $stored;
        }
    }
    $env = trim((string) (gos_env('GROKIFY_XAI_API_KEY', gos_env('XAI_API_KEY', '') ?? '') ?? ''));
    if ($env !== '') {
        return $env;
    }
    $pdo = function_exists('gos_discord_avalynn_pdo') ? gos_discord_avalynn_pdo() : null;
    if ($pdo === null) {
        return '';
    }
    try {
        $st = $pdo->prepare('SELECT setting_value FROM settings WHERE setting_key = ? LIMIT 1');
        $st->execute(['llm_xai_key']);
        $v = $st->fetchColumn();

        return is_string($v) ? trim($v) : '';
    } catch (Throwable) {
        return '';
    }
}

function gos_discord_ai_xai_key_source(): string
{
    if (function_exists('gos_setting_get')) {
        $stored = trim((string) gos_setting_get('discord_ai_spacexai_key', ''));
        if ($stored !== '') {
            return 'settings';
        }
    }
    $env = trim((string) (gos_env('GROKIFY_XAI_API_KEY', gos_env('XAI_API_KEY', '') ?? '') ?? ''));
    if ($env !== '') {
        return 'env';
    }
    $pdo = function_exists('gos_discord_avalynn_pdo') ? gos_discord_avalynn_pdo() : null;
    if ($pdo === null) {
        return '';
    }
    try {
        $st = $pdo->prepare('SELECT setting_value FROM settings WHERE setting_key = ? LIMIT 1');
        $st->execute(['llm_xai_key']);
        $v = $st->fetchColumn();

        return is_string($v) && trim($v) !== '' ? 'avalynn' : '';
    } catch (Throwable) {
        return '';
    }
}

function gos_discord_ai_model(): string
{
    return gos_discord_ai_settings()['model'];
}

/**
 * @return array{provider:string,model:string,reasoningEffort:string}
 */
function gos_discord_ai_settings(): array
{
    $envProvider = trim((string) (gos_env('GROKIFY_DISCORD_AI_PROVIDER', '') ?? ''));
    $envModel = trim((string) (gos_env('GROKIFY_DISCORD_AI_MODEL', '') ?? ''));
    $envEffort = trim((string) (gos_env('GROKIFY_DISCORD_AI_REASONING_EFFORT', '') ?? ''));
    $model = $envModel !== '' ? $envModel : GOS_DISCORD_AI_DEFAULT_MODEL;
    $effort = $envEffort !== '' ? $envEffort : GOS_DISCORD_AI_DEFAULT_EFFORT;
    $savedProvider = '';
    if (function_exists('gos_setting_get')) {
        $savedProvider = trim((string) gos_setting_get('discord_ai_provider', ''));
        $sm = trim((string) gos_setting_get('discord_ai_model', ''));
        $se = trim((string) gos_setting_get('discord_ai_reasoning_effort', ''));
        if ($sm !== '') {
            $model = $sm;
        }
        if ($se !== '') {
            $effort = $se;
        }
    }
    if ($savedProvider !== '') {
        $provider = $savedProvider;
    } elseif ($envProvider !== '') {
        $provider = $envProvider;
    } else {
        $provider = gos_discord_ai_xai_key() !== '' ? 'spacexai' : 'bridge';
    }
    $provider = gos_discord_ai_provider_ok($provider);
    $model = gos_discord_ai_model_ok($model);
    $effort = gos_discord_ai_effort_ok($model, $effort);

    return [
        'provider' => $provider,
        'model' => $model,
        'reasoningEffort' => $effort,
    ];
}

/**
 * @param array<string, mixed>|null $job
 * @return array{provider:string,model:string,reasoningEffort:string}
 */
function gos_discord_ai_runtime(?array $job = null): array
{
    $cfg = gos_discord_ai_settings();
    if (!is_array($job)) {
        return $cfg;
    }
    $p = trim((string) ($job['provider'] ?? ''));
    $m = trim((string) ($job['model'] ?? ''));
    $e = trim((string) ($job['reasoning_effort'] ?? $job['reasoningEffort'] ?? ''));
    if ($p !== '') {
        $cfg['provider'] = gos_discord_ai_provider_ok($p);
    }
    if ($m !== '') {
        $cfg['model'] = gos_discord_ai_model_ok($m, $cfg['model']);
    }
    if ($e !== '' || $m !== '') {
        $cfg['reasoningEffort'] = gos_discord_ai_effort_ok($cfg['model'], $e !== '' ? $e : $cfg['reasoningEffort']);
    }

    return $cfg;
}

/**
 * @param array<string, mixed> $row
 */
function gos_discord_ai_spacexai_is_text_model(array $row): bool
{
    $id = strtolower(trim((string) ($row['id'] ?? $row['name'] ?? '')));
    if ($id === '' || $id === 'latest') {
        return false;
    }
    if (preg_match('/imagine|video|tts|whisper|embed|voice|audio|realtime|image-gen|grok-imagine/', $id) === 1) {
        return false;
    }
    $out = $row['output_modalities'] ?? $row['outputModalities'] ?? null;
    if (is_array($out) && $out !== []) {
        $outs = array_map('strtolower', array_map('strval', $out));
        if (!in_array('text', $outs, true)) {
            return false;
        }
    } elseif (array_key_exists('image_price', $row) && !array_key_exists('completion_text_token_price', $row)) {
        return false;
    }

    return true;
}

/**
 * @param list<array<string, mixed>> $rows
 * @return list<array{id:string,name:string,provider:string,reasoning_efforts:list<string>,default_reasoning_effort:string}>
 */
function gos_discord_ai_normalize_model_rows(array $rows, string $provider): array
{
    $out = [];
    $seen = [];
    foreach ($rows as $row) {
        if (!is_array($row)) {
            continue;
        }
        $id = gos_discord_ai_model_ok((string) ($row['id'] ?? $row['name'] ?? ''), '');
        if ($id === '' || isset($seen[$id])) {
            continue;
        }
        $seen[$id] = true;
        $efforts = gos_discord_ai_efforts_for_model($id);
        $name = trim((string) ($row['name'] ?? $id));
        if ($name === '') {
            $name = $id;
        }
        $out[] = [
            'id' => $id,
            'name' => $name,
            'provider' => $provider,
            'reasoning_efforts' => $efforts,
            'default_reasoning_effort' => $efforts === [] ? '' : gos_discord_ai_effort_ok($id, ''),
        ];
    }
    usort($out, static function (array $a, array $b): int {
        $rank = static function (string $id): int {
            if ($id === 'grok-4.6') {
                return 0;
            }
            if (str_starts_with($id, 'grok-4.6')) {
                return 1;
            }
            if (str_starts_with($id, 'grok-4')) {
                return 2;
            }
            if (str_starts_with($id, 'grok-')) {
                return 3;
            }

            return 4;
        };
        $d = $rank($a['id']) <=> $rank($b['id']);

        return $d !== 0 ? $d : strcmp($a['id'], $b['id']);
    });

    return $out;
}

/**
 * @param array<string, mixed> $decoded
 * @return list<array<string, mixed>>
 */
function gos_discord_ai_extract_spacexai_model_rows(array $decoded): array
{
    $rows = [];
    if (isset($decoded['models']) && is_array($decoded['models'])) {
        $rows = $decoded['models'];
    } elseif (isset($decoded['data']) && is_array($decoded['data'])) {
        $rows = $decoded['data'];
    }
    $keep = [];
    foreach ($rows as $row) {
        if (is_array($row) && gos_discord_ai_spacexai_is_text_model($row)) {
            $keep[] = $row;
        }
    }

    return $keep;
}

/**
 * @return array{ok:bool,status:int,data:?array,error:?string}
 */
function gos_discord_ai_http(string $url, string $method, ?array $body, array $headers, int $timeout): array
{
    $ch = curl_init($url);
    if ($ch === false) {
        return ['ok' => false, 'status' => 0, 'data' => null, 'error' => 'curl_init'];
    }
    $opts = [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => max(8, $timeout),
        CURLOPT_CONNECTTIMEOUT => 8,
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_HTTPHEADER => $headers,
    ];
    $verb = strtoupper($method);
    if ($verb === 'POST') {
        $opts[CURLOPT_POST] = true;
        $json = $body === null ? '{}' : json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        $opts[CURLOPT_POSTFIELDS] = is_string($json) ? $json : '{}';
    } elseif ($verb !== 'GET') {
        $opts[CURLOPT_CUSTOMREQUEST] = $verb;
    }
    if (str_starts_with($url, 'https://')) {
        $opts[CURLOPT_PROTOCOLS] = CURLPROTO_HTTPS;
    }
    curl_setopt_array($ch, $opts);
    $raw = curl_exec($ch);
    $errno = curl_errno($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($raw === false || $errno !== 0) {
        return ['ok' => false, 'status' => $status, 'data' => null, 'error' => 'unreachable'];
    }
    $decoded = json_decode((string) $raw, true);

    return [
        'ok' => $status >= 200 && $status < 300,
        'status' => $status,
        'data' => is_array($decoded) ? $decoded : null,
        'error' => $status >= 400
            ? substr((string) (is_array($decoded) ? ($decoded['error']['message'] ?? $decoded['error'] ?? ('http_' . $status)) : ('http_' . $status)), 0, 180)
            : null,
    ];
}

/**
 * @return list<array{id:string,name:string,provider:string,reasoning_efforts:list<string>,default_reasoning_effort:string}>
 */
function gos_discord_ai_list_spacexai_models(string $key, bool $refresh = false): array
{
    static $cache = ['at' => 0, 'key' => '', 'models' => []];
    $now = time();
    if (!$refresh && $cache['models'] !== [] && $cache['key'] === $key && ($now - (int) $cache['at']) < 60) {
        return $cache['models'];
    }
    if ($key === '') {
        return [];
    }
    $headers = [
        'Accept: application/json',
        'Authorization: Bearer ' . $key,
        'User-Agent: grokifyos-discord-ai',
    ];
    $rows = [];
    $lang = gos_discord_ai_http(GOS_DISCORD_AI_SPACEXAI_BASE . '/language-models', 'GET', null, $headers, 20);
    if ($lang['ok'] && is_array($lang['data'])) {
        $rows = gos_discord_ai_extract_spacexai_model_rows($lang['data']);
    }
    if ($rows === []) {
        $all = gos_discord_ai_http(GOS_DISCORD_AI_SPACEXAI_BASE . '/models', 'GET', null, $headers, 20);
        if ($all['ok'] && is_array($all['data'])) {
            $rows = gos_discord_ai_extract_spacexai_model_rows($all['data']);
        }
    }
    $models = gos_discord_ai_normalize_model_rows($rows, 'spacexai');
    if ($models !== []) {
        $cache = ['at' => $now, 'key' => $key, 'models' => $models];
    }

    return $models;
}

function gos_discord_ai_bridge_url(): string
{
    if (function_exists('gos_system_chat_bridge_url')) {
        return rtrim(gos_system_chat_bridge_url(), '/');
    }
    $url = gos_env('GROKIFY_BRIDGE_URL', '') ?? '';
    if ($url !== '') {
        return rtrim($url, '/');
    }

    return 'http://127.0.0.1:8876';
}

/**
 * @return array{ok:bool,healthy:bool,models:list<array{id:string,name:string,provider:string,reasoning_efforts:list<string>,default_reasoning_effort:string}>,error:?string}
 */
function gos_discord_ai_list_bridge_models(): array
{
    $res = gos_discord_ai_http(gos_discord_ai_bridge_url() . '/models', 'GET', null, ['Accept: application/json'], 8);
    $rows = [];
    if ($res['ok'] && is_array($res['data'])) {
        $list = $res['data']['grok_models'] ?? [];
        if (is_array($list)) {
            foreach ($list as $m) {
                if (!is_array($m)) {
                    continue;
                }
                $id = (string) ($m['id'] ?? '');
                if ($id === '') {
                    continue;
                }
                $rows[] = [
                    'id' => $id,
                    'name' => (string) ($m['name'] ?? $id),
                ];
            }
        }
    }
    $models = gos_discord_ai_normalize_model_rows($rows, 'bridge');

    return [
        'ok' => $res['ok'],
        'healthy' => $res['ok'],
        'models' => $models,
        'error' => $res['ok'] ? null : ($res['error'] ?? 'bridge_unreachable'),
    ];
}

/**
 * @param array<string, mixed> $opt
 * @return array<string, mixed>
 */
function gos_discord_ai_spacexai_payload(string $system, string $user, array $cfg, array $opt = []): array
{
    $model = gos_discord_ai_model_ok((string) ($cfg['model'] ?? GOS_DISCORD_AI_DEFAULT_MODEL));
    $payload = [
        'model' => $model,
        'messages' => [
            ['role' => 'system', 'content' => $system],
            ['role' => 'user', 'content' => $user],
        ],
        'max_tokens' => (int) ($opt['max_tokens'] ?? 1600),
    ];
    $effort = gos_discord_ai_effort_ok($model, (string) ($cfg['reasoningEffort'] ?? ''));
    if ($effort !== '') {
        $payload['reasoning_effort'] = $effort;
    } else {
        $payload['temperature'] = (float) ($opt['temperature'] ?? 0.4);
    }
    if (!empty($opt['json'])) {
        $payload['response_format'] = ['type' => 'json_object'];
    }

    return $payload;
}

function gos_discord_ai_extract_choice_text(array $decoded): string
{
    $text = (string) ($decoded['choices'][0]['message']['content'] ?? '');
    if ($text !== '') {
        return trim($text);
    }
    if (isset($decoded['output']) && is_array($decoded['output'])) {
        foreach ($decoded['output'] as $item) {
            if (!is_array($item)) {
                continue;
            }
            $content = $item['content'] ?? null;
            if (!is_array($content)) {
                continue;
            }
            foreach ($content as $part) {
                if (is_array($part) && (($part['type'] ?? '') === 'output_text' || isset($part['text']))) {
                    $t = trim((string) ($part['text'] ?? ''));
                    if ($t !== '') {
                        return $t;
                    }
                }
            }
        }
    }
    $plain = trim((string) ($decoded['text'] ?? $decoded['output_text'] ?? ''));

    return $plain;
}

/**
 * @param array<string, mixed> $opt
 */
function gos_discord_ai_complete(string $system, string $user, array $opt = []): string
{
    $job = is_array($opt['job'] ?? null) ? $opt['job'] : null;
    $cfg = gos_discord_ai_runtime($job);
    if ($cfg['provider'] === 'bridge') {
        return gos_discord_ai_complete_bridge($system, $user, $cfg, $opt);
    }

    return gos_discord_ai_complete_spacexai($system, $user, $cfg, $opt);
}

/**
 * @param array{provider:string,model:string,reasoningEffort:string} $cfg
 * @param array<string, mixed> $opt
 */
function gos_discord_ai_complete_spacexai(string $system, string $user, array $cfg, array $opt = []): string
{
    $key = gos_discord_ai_xai_key();
    if ($key === '') {
        throw new RuntimeException('spacexai_key_missing');
    }
    $payload = gos_discord_ai_spacexai_payload($system, $user, $cfg, $opt);
    $timeout = (int) ($opt['timeout'] ?? 90);
    $res = gos_discord_ai_http(
        GOS_DISCORD_AI_SPACEXAI_BASE . '/chat/completions',
        'POST',
        $payload,
        [
            'Content-Type: application/json',
            'Authorization: Bearer ' . $key,
        ],
        max(20, $timeout)
    );
    if (!$res['ok']) {
        throw new RuntimeException($res['error'] ?? 'spacexai_http_' . (int) $res['status']);
    }
    $text = is_array($res['data']) ? gos_discord_ai_extract_choice_text($res['data']) : '';
    if ($text === '') {
        throw new RuntimeException('empty_completion');
    }

    return $text;
}

/**
 * @param array{provider:string,model:string,reasoningEffort:string} $cfg
 * @param array<string, mixed> $opt
 */
function gos_discord_ai_complete_bridge(string $system, string $user, array $cfg, array $opt = []): string
{
    $timeout = (int) ($opt['timeout'] ?? 150);
    $body = [
        'model' => $cfg['model'],
        'reasoning_effort' => $cfg['reasoningEffort'],
        'system' => $system,
        'prompt' => $user,
        'json' => !empty($opt['json']),
        'timeout_ms' => max(20000, $timeout * 1000),
    ];
    $res = gos_discord_ai_http(
        gos_discord_ai_bridge_url() . '/complete',
        'POST',
        $body,
        ['Content-Type: application/json', 'Accept: application/json'],
        max(30, $timeout + 10)
    );
    if (!$res['ok'] || !is_array($res['data'])) {
        throw new RuntimeException($res['error'] ?? 'bridge_unreachable');
    }
    $text = trim((string) ($res['data']['text'] ?? ''));
    if ($text === '') {
        throw new RuntimeException((string) ($res['data']['error'] ?? 'empty_completion'));
    }

    return $text;
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_ai_settings_get(array $q): array
{
    $cfg = gos_discord_ai_settings();
    $want = strtolower(trim((string) ($q['provider'] ?? '')));
    $provider = $want !== '' ? gos_discord_ai_provider_ok($want) : $cfg['provider'];
    $key = gos_discord_ai_xai_key();
    $source = gos_discord_ai_xai_key_source();
    $refresh = !empty($q['refresh']) && (string) $q['refresh'] !== '0';
    $bridgeHealthy = false;
    $bridgeError = '';
    $models = [];
    if ($provider === 'bridge') {
        $bridge = gos_discord_ai_list_bridge_models();
        $bridgeHealthy = $bridge['healthy'];
        $bridgeError = (string) ($bridge['error'] ?? '');
        $models = $bridge['models'];
        if ($models === []) {
            $models = gos_discord_ai_normalize_model_rows([
                ['id' => 'grok-4.6', 'name' => 'grok-4.6'],
                ['id' => 'grok-4.5', 'name' => 'grok-4.5'],
            ], 'bridge');
        }
    } else {
        $models = $key !== '' ? gos_discord_ai_list_spacexai_models($key, $refresh) : [];
        $bridge = gos_discord_ai_list_bridge_models();
        $bridgeHealthy = $bridge['healthy'];
    }
    $ids = array_column($models, 'id');
    $model = $cfg['model'];
    if ($ids !== [] && !in_array($model, $ids, true)) {
        $model = in_array(GOS_DISCORD_AI_DEFAULT_MODEL, $ids, true)
            ? GOS_DISCORD_AI_DEFAULT_MODEL
            : (string) $ids[0];
    }
    $effort = gos_discord_ai_effort_ok($model, $cfg['reasoningEffort']);

    return [
        'ok' => true,
        'status' => 200,
        'data' => [
            'provider' => $cfg['provider'],
            'listingProvider' => $provider,
            'model' => $model,
            'reasoningEffort' => $effort,
            'models' => $models,
            'keySet' => $key !== '',
            'keyHint' => gos_discord_ai_key_hint($key),
            'keySource' => $source,
            'bridgeHealthy' => $bridgeHealthy,
            'bridgeError' => $bridgeError,
            'defaultModel' => GOS_DISCORD_AI_DEFAULT_MODEL,
            'defaultEffort' => GOS_DISCORD_AI_DEFAULT_EFFORT,
        ],
        'error' => null,
    ];
}

/**
 * @return array{ok:bool,status:int,data:mixed,error:?string}
 */
function gos_discord_ai_settings_save(array $body): array
{
    if (!function_exists('gos_setting_set')) {
        return ['ok' => false, 'status' => 500, 'data' => null, 'error' => 'settings_unavailable'];
    }
    $provider = gos_discord_ai_provider_ok((string) ($body['provider'] ?? gos_discord_ai_settings()['provider']));
    $model = gos_discord_ai_model_ok((string) ($body['model'] ?? GOS_DISCORD_AI_DEFAULT_MODEL));
    $effort = gos_discord_ai_effort_ok($model, (string) ($body['reasoningEffort'] ?? $body['reasoning_effort'] ?? GOS_DISCORD_AI_DEFAULT_EFFORT));
    gos_setting_set('discord_ai_provider', $provider);
    gos_setting_set('discord_ai_model', $model);
    gos_setting_set('discord_ai_reasoning_effort', $effort);
    $clearKey = !empty($body['clearKey']) || !empty($body['clear_key']);
    if ($clearKey) {
        gos_setting_set('discord_ai_spacexai_key', '');
    } else {
        $keyIn = trim((string) ($body['apiKey'] ?? $body['spacexaiKey'] ?? $body['key'] ?? ''));
        if ($keyIn !== '') {
            if (strlen($keyIn) < 12 || strlen($keyIn) > 256) {
                return ['ok' => false, 'status' => 400, 'data' => null, 'error' => 'invalid_api_key'];
            }
            gos_setting_set('discord_ai_spacexai_key', $keyIn);
        }
    }

    return gos_discord_ai_settings_get(['provider' => $provider]);
}
