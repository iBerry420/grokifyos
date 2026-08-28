<?php

declare(strict_types=1);

/**
 * LYRE MCP connector + GosLyreException plumbing.
 * Run: php web/tests/lyre_mcp_test.php
 */

$mcpDir = sys_get_temp_dir() . '/lyre-mcp-test-' . bin2hex(random_bytes(4));
putenv('GOS_LYRE_MCP_DIR=' . $mcpDir);
$_ENV['GOS_LYRE_MCP_DIR'] = $mcpDir;
putenv('GOS_LYRE_MCP_TEST_USER_JSON={"id":42,"status":"active","username":"mcp-test"}');
$_ENV['GOS_LYRE_MCP_TEST_USER_JSON'] = '{"id":42,"status":"active","username":"mcp-test"}';

define('GOS_SKIP_SESSION', true);
define('GOS_LYRE_NO_ROUTE', true);

require_once dirname(__DIR__) . '/api/lyre.php';
require_once dirname(__DIR__) . '/includes/lyre_mcp.php';

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

function lyre_mcp_rmdir(string $dir): void
{
    if (!is_dir($dir)) {
        return;
    }
    $it = new RecursiveIteratorIterator(
        new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS),
        RecursiveIteratorIterator::CHILD_FIRST
    );
    foreach ($it as $f) {
        $p = $f->getPathname();
        if ($f->isDir()) {
            @rmdir($p);
        } else {
            @unlink($p);
        }
    }
    @rmdir($dir);
}

function lyre_mcp_spawn(array $opts): array
{
    $root = dirname(__DIR__, 2);
    $script = tempnam(sys_get_temp_dir(), 'lyre-mcp-spawn-');
    $php = <<<'PHP'
<?php
declare(strict_types=1);
if (getenv('TEST_DEFINE_LYRE') === '1') {
    define('GOS_SKIP_SESSION', true);
    define('GOS_LYRE_NO_ROUTE', true);
}
$_SERVER['REQUEST_METHOD'] = getenv('TEST_METHOD') ?: 'GET';
$_SERVER['REQUEST_URI'] = getenv('TEST_URI') ?: '/mcp';
$_SERVER['HTTP_HOST'] = getenv('TEST_HOST') ?: 'grokifyos.grokpot.io';
$auth = getenv('TEST_AUTH');
if (is_string($auth) && $auth !== '') {
    $_SERVER['HTTP_AUTHORIZATION'] = $auth;
}
$accept = getenv('TEST_ACCEPT');
if (is_string($accept) && $accept !== '') {
    $_SERVER['HTTP_ACCEPT'] = $accept;
}
$q = getenv('TEST_TOKEN_QUERY');
$_GET = [];
if (is_string($q) && $q !== '') {
    $_GET['token'] = $q;
}
$pathInfo = getenv('TEST_PATH_INFO');
if (is_string($pathInfo) && $pathInfo !== '') {
    $_SERVER['PATH_INFO'] = $pathInfo;
}
register_shutdown_function(static function (): void {
    fwrite(STDERR, "\n__HEADERS__" . json_encode(headers_list()) . "\n__CODE__" . (string) http_response_code() . "\n");
});
require getenv('TEST_REQUIRE');
PHP;
    file_put_contents($script, $php);

    $prev = [];
    $setEnv = static function (string $key, string $value) use (&$prev): void {
        $prev[$key] = getenv($key);
        putenv($key . '=' . $value);
        $_ENV[$key] = $value;
    };
    $setEnv('TEST_METHOD', (string) ($opts['method'] ?? 'GET'));
    $setEnv('TEST_URI', (string) ($opts['uri'] ?? '/mcp'));
    $setEnv('TEST_REQUIRE', (string) ($opts['require'] ?? ($root . '/web/api/lyre-mcp.php')));
    $setEnv('GOS_LYRE_MCP_DIR', (string) ($opts['mcp_dir'] ?? (getenv('GOS_LYRE_MCP_DIR') ?: '')));
    if (isset($opts['auth'])) {
        $setEnv('TEST_AUTH', (string) $opts['auth']);
    } else {
        $setEnv('TEST_AUTH', '');
    }
    if (isset($opts['accept'])) {
        $setEnv('TEST_ACCEPT', (string) $opts['accept']);
    } else {
        $setEnv('TEST_ACCEPT', '');
    }
    if (isset($opts['token_query'])) {
        $setEnv('TEST_TOKEN_QUERY', (string) $opts['token_query']);
    } else {
        $setEnv('TEST_TOKEN_QUERY', '');
    }
    if (isset($opts['path_info'])) {
        $setEnv('TEST_PATH_INFO', (string) $opts['path_info']);
    } else {
        $setEnv('TEST_PATH_INFO', '');
    }
    if (isset($opts['user_json'])) {
        $setEnv('GOS_LYRE_MCP_TEST_USER_JSON', (string) $opts['user_json']);
    }
    if (!empty($opts['pg_fail'])) {
        $setEnv('GOS_LYRE_TEST_PG_FAIL', '1');
    } else {
        $setEnv('GOS_LYRE_TEST_PG_FAIL', '');
    }
    if (!empty($opts['define_lyre'])) {
        $setEnv('TEST_DEFINE_LYRE', '1');
    } else {
        $setEnv('TEST_DEFINE_LYRE', '');
    }
    if (isset($opts['extra_env']) && is_array($opts['extra_env'])) {
        foreach ($opts['extra_env'] as $k => $v) {
            $setEnv((string) $k, (string) $v);
        }
    }
    $body = (string) ($opts['body'] ?? '');
    $bodyFile = null;
    if ($body !== '') {
        $bodyFile = tempnam(sys_get_temp_dir(), 'lyre-mcp-body-');
        file_put_contents($bodyFile, $body);
        $setEnv('GOS_LYRE_MCP_BODY_FILE', $bodyFile);
    } else {
        $setEnv('GOS_LYRE_MCP_BODY_FILE', '');
    }

    $descriptors = [
        0 => ['pipe', 'r'],
        1 => ['pipe', 'w'],
        2 => ['pipe', 'w'],
    ];
    $cmd = [PHP_BINARY, $script];
    $proc = proc_open($cmd, $descriptors, $pipes, $root, null);
    if (!is_resource($proc)) {
        @unlink($script);
        if (is_string($bodyFile) && $bodyFile !== '') {
            @unlink($bodyFile);
        }
        return ['code' => 1, 'stdout' => '', 'stderr' => 'proc_open_failed', 'http' => 0, 'headers' => []];
    }
    fwrite($pipes[0], $body);
    fclose($pipes[0]);
    $stdout = stream_get_contents($pipes[1]) ?: '';
    $stderr = stream_get_contents($pipes[2]) ?: '';
    fclose($pipes[1]);
    fclose($pipes[2]);
    $code = proc_close($proc);
    @unlink($script);
    if (is_string($bodyFile) && $bodyFile !== '') {
        @unlink($bodyFile);
    }
    foreach ($prev as $k => $v) {
        if ($v === false || $v === null) {
            putenv((string) $k);
            unset($_ENV[$k]);
        } else {
            putenv($k . '=' . $v);
            $_ENV[$k] = $v;
        }
    }

    $headers = [];
    $http = 0;
    if (preg_match('/__HEADERS__(.*)\\n__CODE__(.*)\\n/s', $stderr, $m)) {
        $decoded = json_decode((string) $m[1], true);
        $headers = is_array($decoded) ? $decoded : [];
        $http = (int) $m[2];
    }

    return [
        'code' => $code,
        'stdout' => $stdout,
        'stderr' => $stderr,
        'http' => $http,
        'headers' => $headers,
    ];
}

$plainFixture = 'lyre_mcp_000000000000000000000000000000000000000000000000';
expect_true(preg_match(GOS_LYRE_MCP_TOKEN_RE, $plainFixture) === 1, 'token regex matches 48 hex');
expect_true(preg_match(GOS_LYRE_MCP_TOKEN_RE, 'lyre_mcp_abc') !== 1, 'token regex rejects short');
expect_true(preg_match(GOS_LYRE_MCP_TOKEN_RE, 'cex_mcp_' . str_repeat('0', 48)) !== 1, 'token regex rejects cex prefix');
expect_true(preg_match(GOS_LYRE_MCP_TOKEN_RE, 'gos_' . str_repeat('a', 48)) !== 1, 'token regex rejects gos_');

$prefix = gos_lyre_mcp_token_prefix($plainFixture);
expect_eq($prefix, 'lyre_mcp_0000000…', 'prefix is lyre_mcp_ + 7 hex + ellipsis');
expect_eq(strlen('lyre_mcp_'), 9, 'lyre_mcp_ is 9 chars');
expect_eq(substr($prefix, 0, 16), 'lyre_mcp_0000000', '16-char prefix before ellipsis');

$hash = gos_lyre_mcp_hash_token($plainFixture);
expect_eq($hash, 'edc5e5981f100657dd633fca253013ba6a8aad7e2fe7fc63147e3f958e98e5a2', 'hash fixture sha256(strtolower(plain))');
expect_eq(gos_lyre_mcp_hash_token(strtoupper($plainFixture)), $hash, 'hash is case-insensitive via strtolower');

$_GET = [];
$_SERVER['REQUEST_URI'] = '/mcp/' . $plainFixture;
$_SERVER['PATH_INFO'] = '';
unset($_SERVER['HTTP_AUTHORIZATION'], $_SERVER['REDIRECT_HTTP_AUTHORIZATION']);
expect_eq(gos_lyre_mcp_extract_token_from_request(), $plainFixture, 'extract from REQUEST_URI path');

$_SERVER['REQUEST_URI'] = '/mcp.php/' . $plainFixture . '/';
expect_eq(gos_lyre_mcp_extract_token_from_request(), $plainFixture, 'extract from /mcp.php/token/');

$_SERVER['REQUEST_URI'] = '/api/lyre-mcp.php';
$_GET['token'] = $plainFixture;
expect_eq(gos_lyre_mcp_extract_token_from_request(), $plainFixture, 'extract from query token');
unset($_GET['token']);

$_SERVER['REQUEST_URI'] = '/api/lyre-mcp.php';
$_SERVER['PATH_INFO'] = '/' . $plainFixture;
expect_eq(gos_lyre_mcp_extract_token_from_request(), $plainFixture, 'extract from PATH_INFO fallback');
unset($_SERVER['PATH_INFO']);

$_SERVER['REQUEST_URI'] = '/api/lyre-mcp.php';
$_SERVER['HTTP_AUTHORIZATION'] = 'Bearer ' . $plainFixture;
expect_eq(gos_lyre_mcp_extract_token_from_request(), $plainFixture, 'extract from Bearer lyre_mcp_');
unset($_SERVER['HTTP_AUTHORIZATION']);

$_SERVER['HTTP_AUTHORIZATION'] = 'Bearer gos_deadbeef';
expect_eq(gos_lyre_mcp_extract_token_from_request(), null, 'gos_ Bearer is not a lyre token');
expect_true(gos_lyre_mcp_gos_bearer_presented(), 'gos_ Bearer detected');
unset($_SERVER['HTTP_AUTHORIZATION']);

$ens = gos_lyre_mcp_ensure_for_user(42, true);
$plain = (string) $ens['plain_token'];
expect_true(preg_match(GOS_LYRE_MCP_TOKEN_RE, $plain) === 1, 'minted token matches regex');
$status = gos_lyre_mcp_status_payload(42, null);
expect_eq($status['connector_link'], null, 'mcp_status never returns plaintext connector_link');
expect_true(!isset($status['plain_token']) || $status['plain_token'] === null, 'mcp_status has no plain_token');
expect_true($status['has_connector'] === true, 'status has_connector after mint');
expect_true($status['enabled'] === true, 'status enabled after mint');
$ens2 = gos_lyre_mcp_ensure_for_user(42, false);
expect_eq($ens2['plain_token'], null, 'ensure does not re-mint');

$enFresh = gos_lyre_mcp_enable_for_user(43);
$freshPlain = (string) ($enFresh['plain_token'] ?? '');
expect_true(preg_match(GOS_LYRE_MCP_TOKEN_RE, $freshPlain) === 1, 'enable mints when no token');
$enPayload = gos_lyre_mcp_status_payload(43, $enFresh['plain_token']);
expect_true(
    is_string($enPayload['connector_link'] ?? null) && str_contains((string) $enPayload['connector_link'], $freshPlain),
    'enable mint returns connector_link'
);
$enDisk = gos_lyre_mcp_read_json(gos_lyre_mcp_user_path(43));
expect_eq($enDisk['token_hash'] ?? null, gos_lyre_mcp_hash_token($freshPlain), 'enable stores hash not plaintext');
expect_true(!isset($enDisk['plain_token']) || $enDisk['plain_token'] === null, 'enable does not persist plaintext');
$enAgain = gos_lyre_mcp_enable_for_user(43);
expect_eq($enAgain['plain_token'], null, 'enable existing token does not remint');
$enAgainStatus = gos_lyre_mcp_status_payload(43, $enAgain['plain_token']);
expect_eq($enAgainStatus['connector_link'], null, 'enable existing does not leak connector_link');
gos_lyre_mcp_disable_for_user(43);
$enRe = gos_lyre_mcp_enable_for_user(43);
expect_eq($enRe['plain_token'], null, 're-enable does not remint');
$enableHttpSrc = file_get_contents(dirname(__DIR__) . '/api/lyre.php') ?: '';
expect_true(
    preg_match(
        '/function gos_lyre_http_mcp_enable\(array \$access\): never\s*\{.*?gos_lyre_mcp_status_payload\(\$userId, \$ens\[\'plain_token\'\]\)/s',
        $enableHttpSrc
    ) === 1,
    'http enable passes minted plain_token into status_payload'
);

$access = [
    'user' => ['id' => 42, 'status' => 'active'],
    'device' => null,
    'auth' => 'mcp',
];

$init = gos_lyre_mcp_dispatch_message([
    'jsonrpc' => '2.0',
    'id' => 1,
    'method' => 'initialize',
    'params' => ['protocolVersion' => '2025-03-26'],
], $access);
expect_eq($init['jsonrpc'] ?? '', '2.0', 'initialize jsonrpc');
expect_eq($init['result']['protocolVersion'] ?? '', '2025-03-26', 'protocol negotiate 2025-03-26');
expect_eq($init['result']['serverInfo']['name'] ?? '', 'lyre', 'serverInfo name');
expect_eq($init['result']['capabilities']['tools']['listChanged'] ?? null, true, 'listChanged true');

$list = gos_lyre_mcp_dispatch_message([
    'jsonrpc' => '2.0',
    'id' => 2,
    'method' => 'tools/list',
    'params' => [],
], $access);
$names = [];
foreach ($list['result']['tools'] ?? [] as $t) {
    if (is_array($t) && isset($t['name'])) {
        $names[] = $t['name'];
    }
}
sort($names);
expect_eq($names, ['lyre_create', 'lyre_instructions', 'lyre_open', 'lyre_projects'], 'PR 1 tool allowlist');
expect_true(!in_array('save_board', $names, true), 'save_board absent from tools/list');

$unknown = gos_lyre_mcp_run_tool('save_board', ['id' => 'x', 'data' => []], $access);
expect_eq($unknown['isError'] ?? null, true, 'save_board tool isError');
$unknownText = (string) ($unknown['content'][0]['text'] ?? '');
expect_true(str_contains($unknownText, 'unknown_tool'), 'unknown tool is unknown_tool not save_board');
expect_true(!str_contains($unknownText, 'action'), 'unknown tool body has no action=save_board');

$bogus = gos_lyre_mcp_run_tool('not_a_tool', [], $access);
expect_true(str_contains((string) ($bogus['content'][0]['text'] ?? ''), 'unknown_tool'), 'unknown tool name');

$empty = gos_lyre_empty_board();
$empty['title'] = 'The Return';
$empty['brainstorm'] = 'wine-dark sea';
expect_eq($empty['title'], 'The Return', 'create payload title');
expect_eq($empty['brainstorm'], 'wine-dark sea', 'create payload brainstorm');

putenv('GOS_LYRE_TEST_PG_FAIL=1');
$_ENV['GOS_LYRE_TEST_PG_FAIL'] = '1';
$pgFail = gos_lyre_mcp_dispatch_message([
    'jsonrpc' => '2.0',
    'id' => 9,
    'method' => 'tools/call',
    'params' => [
        'name' => 'lyre_create',
        'arguments' => ['name' => 'PG fail', 'brainstorm' => 'bible'],
    ],
], $access);
expect_eq($pgFail['jsonrpc'] ?? '', '2.0', 'simulated PG failure still JSON-RPC 2.0');
expect_eq($pgFail['result']['isError'] ?? null, true, 'PG failure isError');
expect_true(str_contains((string) ($pgFail['result']['content'][0]['text'] ?? ''), 'lyre_pg_unavailable'), 'PG failure error code');
putenv('GOS_LYRE_TEST_PG_FAIL');
unset($_ENV['GOS_LYRE_TEST_PG_FAIL']);

$odRow = [
    'id' => str_repeat('a', 32),
    'name' => 'Odysseus',
    'visibility' => 'private',
    'board_id' => 'lyre',
    'is_odysseus' => 1,
    'watch_token' => null,
    'compiled_key' => null,
    'created_at' => '',
    'updated_at' => '',
];
expect_true(gos_lyre_is_odysseus_project($odRow), 'odysseus row helper');
$keepBoard = 'lyre_phone_keep-me';
gos_lyre_mcp_persist_open(42, [
    'id' => str_repeat('b', 32),
    'board_id' => $keepBoard,
    'is_odysseus' => 0,
], 'mcp');
expect_eq(gos_lyre_mcp_user_state(42)['mcp_open_board_id'] ?? null, $keepBoard, 'mcp open phone board');
$threw = false;
try {
    gos_lyre_open_resolved($access, $odRow, 'mcp');
} catch (GosLyreException $e) {
    $threw = true;
    expect_eq($e->error, 'odysseus_protected', 'MCP open Odysseus 403');
    expect_eq($e->http, 403, 'MCP open Odysseus http 403');
} catch (Throwable $e) {
    $threw = true;
    fwrite(STDERR, 'FAIL: unexpected ' . $e::class . ' ' . $e->getMessage() . "\n");
    $GLOBALS['fails']++;
}
expect_true($threw, 'MCP open Odysseus throws');
expect_eq(gos_lyre_mcp_user_state(42)['mcp_open_board_id'] ?? null, $keepBoard, 'Odysseus open does not write mcp_open_board_id');
gos_lyre_mcp_persist_open(42, $odRow, 'mcp');
expect_eq(gos_lyre_mcp_user_state(42)['mcp_open_board_id'] ?? null, $keepBoard, 'persist_open skips Odysseus mcp slot');

gos_lyre_mcp_persist_open(42, $odRow, 'phone');
expect_eq(gos_lyre_mcp_user_state(42)['phone_last_board_id'] ?? null, 'lyre', 'phone slot may record Odysseus');
expect_eq(gos_lyre_mcp_user_state(42)['mcp_open_board_id'] ?? null, $keepBoard, 'phone open does not clobber mcp_open');

$spawnGet = lyre_mcp_spawn([
    'method' => 'GET',
    'uri' => '/mcp/' . $plain,
    'mcp_dir' => $mcpDir,
]);
expect_true(str_starts_with(ltrim($spawnGet['stdout']), '{"jsonrpc":"2.0"'), 'GET without device Bearer still jsonrpc 2.0');
expect_eq($spawnGet['http'], 405, 'GET is 405');
$setCookie = false;
foreach ($spawnGet['headers'] as $h) {
    if (stripos((string) $h, 'Set-Cookie') !== false) {
        $setCookie = true;
    }
}
expect_true(!$setCookie, 'GET MCP response has no Set-Cookie');

$spawnGos = lyre_mcp_spawn([
    'method' => 'POST',
    'uri' => '/mcp',
    'auth' => 'Bearer gos_not_an_mcp_token',
    'body' => json_encode(['jsonrpc' => '2.0', 'id' => 1, 'method' => 'ping']),
    'mcp_dir' => $mcpDir,
]);
expect_true(str_starts_with(ltrim($spawnGos['stdout']), '{"jsonrpc":"2.0"'), 'gos_ Bearer still JSON-RPC');
$gosJson = json_decode($spawnGos['stdout'], true);
expect_eq($gosJson['error']['code'] ?? null, -32001, 'gos_ Bearer rejected -32001');

$spawnPost = lyre_mcp_spawn([
    'method' => 'POST',
    'uri' => '/mcp/' . $plain,
    'body' => json_encode([
        'jsonrpc' => '2.0',
        'id' => 3,
        'method' => 'tools/list',
        'params' => new stdClass(),
    ]),
    'mcp_dir' => $mcpDir,
    'user_json' => '{"id":42,"status":"active"}',
]);
expect_true(str_starts_with(ltrim($spawnPost['stdout']), '{"jsonrpc":"2.0"'), 'POST tools/list jsonrpc');
$postJson = json_decode($spawnPost['stdout'], true);
$postNames = [];
foreach ($postJson['result']['tools'] ?? [] as $t) {
    if (is_array($t) && isset($t['name'])) {
        $postNames[] = $t['name'];
    }
}
expect_true(in_array('lyre_instructions', $postNames, true), 'spawn tools/list has instructions');
expect_true(!in_array('save_board', $postNames, true), 'spawn tools/list omits save_board');

$setCookiePost = false;
foreach ($spawnPost['headers'] as $h) {
    if (stripos((string) $h, 'Set-Cookie') !== false || stripos((string) $h, '__grokifyos_sid') !== false) {
        $setCookiePost = true;
    }
}
expect_true(!$setCookiePost, 'POST MCP has no Set-Cookie');

$spawnInactive = lyre_mcp_spawn([
    'method' => 'POST',
    'uri' => '/mcp/' . $plain,
    'body' => json_encode(['jsonrpc' => '2.0', 'id' => 4, 'method' => 'ping']),
    'mcp_dir' => $mcpDir,
    'user_json' => '{"id":42,"status":"disabled"}',
]);
$inactiveJson = json_decode($spawnInactive['stdout'], true);
expect_eq($inactiveJson['jsonrpc'] ?? '', '2.0', 'inactive user still jsonrpc');
expect_eq($inactiveJson['error']['code'] ?? null, -32003, 'inactive user -32003');

$spawnMissing = lyre_mcp_spawn([
    'method' => 'POST',
    'uri' => '/mcp/' . $plain,
    'body' => json_encode(['jsonrpc' => '2.0', 'id' => 5, 'method' => 'ping']),
    'mcp_dir' => $mcpDir,
    'user_json' => '{"id":99,"status":"active"}',
]);
$missingJson = json_decode($spawnMissing['stdout'], true);
expect_eq($missingJson['error']['code'] ?? null, -32003, 'missing user -32003');

gos_lyre_mcp_disable_for_user(42);
$spawnDisabled = lyre_mcp_spawn([
    'method' => 'POST',
    'uri' => '/mcp/' . $plain,
    'body' => json_encode(['jsonrpc' => '2.0', 'id' => 6, 'method' => 'ping']),
    'mcp_dir' => $mcpDir,
    'user_json' => '{"id":42,"status":"active"}',
]);
$disJson = json_decode($spawnDisabled['stdout'], true);
expect_eq($disJson['error']['code'] ?? null, -32003, 'disabled connector -32003');
gos_lyre_mcp_enable_for_user(42);

$spawnPg = lyre_mcp_spawn([
    'method' => 'POST',
    'uri' => '/mcp/' . $plain,
    'body' => json_encode([
        'jsonrpc' => '2.0',
        'id' => 7,
        'method' => 'tools/call',
        'params' => [
            'name' => 'lyre_create',
            'arguments' => ['name' => 'Nope', 'brainstorm' => 'x'],
        ],
    ]),
    'mcp_dir' => $mcpDir,
    'user_json' => '{"id":42,"status":"active"}',
    'pg_fail' => true,
]);
expect_true(str_starts_with(ltrim($spawnPg['stdout']), '{"jsonrpc":"2.0"'), 'spawn PG fail still jsonrpc 2.0');
$pgJson = json_decode($spawnPg['stdout'], true);
expect_eq($pgJson['result']['isError'] ?? null, true, 'spawn PG fail isError not raw 503');
expect_true(str_contains((string) ($pgJson['result']['content'][0]['text'] ?? ''), 'lyre_pg_unavailable'), 'spawn PG fail code');

$noRoute = lyre_mcp_spawn([
    'method' => 'POST',
    'uri' => '/api/lyre.php',
    'body' => json_encode(['action' => 'save_board', 'id' => 'x', 'data' => ['title' => 'hack']]),
    'require' => dirname(__DIR__) . '/api/lyre.php',
    'mcp_dir' => $mcpDir,
    'define_lyre' => true,
]);
expect_true(!str_contains($noRoute['stdout'], 'save_board'), 'requiring lyre.php MCP path does not run save_board');
expect_eq(trim($noRoute['stdout']), '', 'GOS_LYRE_NO_ROUTE require returns without body');

$odysseusSrc = file_get_contents(dirname(__DIR__) . '/api/lyre.php') ?: '';
expect_true(
    preg_match('/function gos_lyre_ensure_odysseus\(int \$userId\): void\s*\{(.*?)\nfunction /s', $odysseusSrc, $ensFn) === 1,
    'ensure_odysseus function extracted'
);
$ensBody = $ensFn[1] ?? '';
expect_true(str_contains($ensBody, "gos_lyre_fail('db_error'"), 'ensure_odysseus insert fail throws via gos_lyre_fail');
expect_true(!str_contains($ensBody, 'gos_api_json'), 'ensure_odysseus does not gos_api_json');

$ensureScript = tempnam(sys_get_temp_dir(), 'lyre-ensure-');
file_put_contents($ensureScript, '<?php
define("GOS_SKIP_SESSION", true);
define("GOS_LYRE_NO_ROUTE", true);
require ' . var_export(dirname(__DIR__) . '/api/lyre.php', true) . ';
try {
    gos_lyre_ensure_odysseus(2147483647);
    echo "NO_THROW";
} catch (GosLyreException $e) {
    echo "THREW:" . $e->error;
} catch (Throwable $e) {
    echo "OTHER:" . $e::class;
}
');
$ensureOut = [];
$ensureCode = 0;
exec(escapeshellarg(PHP_BINARY) . ' ' . escapeshellarg($ensureScript) . ' 2>&1', $ensureOut, $ensureCode);
@unlink($ensureScript);
$ensureText = implode("\n", $ensureOut);
expect_true(!str_starts_with(ltrim($ensureText), '{"ok":false'), 'ensure_odysseus did not gos_api_json');
expect_true(str_contains($ensureText, 'THREW:') || str_contains($ensureText, 'NO_THROW'), 'ensure_odysseus throws GosLyreException or succeeds');
if (str_contains($ensureText, 'THREW:')) {
    expect_true(
        str_contains($ensureText, 'THREW:db_error')
        || str_contains($ensureText, 'THREW:lyre_unconfigured')
        || str_contains($ensureText, 'THREW:lyre_pg_unavailable')
        || str_contains($ensureText, 'THREW:auth_required'),
        'ensure_odysseus throw is a GosLyreException code'
    );
}

$created = null;
try {
    $created = gos_lyre_create_project($access, [
        'name' => 'MCP bible test',
        'brainstorm' => 'wine-dark sea',
    ]);
} catch (GosLyreException $e) {
    $created = ['ok' => false, 'error' => $e->error];
}
if (($created['ok'] ?? false) === true) {
    $boardId = (string) ($created['project']['board_id'] ?? '');
    $row = gos_lyre_pg_select(gos_lyre_pg(), $boardId);
    $payload = gos_lyre_payload_array($row['payload'] ?? null);
    expect_eq((string) ($row['brainstorm'] ?? ''), 'wine-dark sea', 'create persists brainstorm column');
    expect_eq((string) ($payload['brainstorm'] ?? ''), 'wine-dark sea', 'create persists brainstorm on board');
    expect_eq((string) ($payload['title'] ?? ''), 'MCP bible test', 'create persists title');
    expect_eq(gos_lyre_mcp_user_state(42)['mcp_open_board_id'] ?? null, $keepBoard, 'lyre_create does not write mcp_open');
    $pid = (string) ($created['project']['id'] ?? '');
    if ($pid !== '') {
        try {
            $del = gos_lyre_mysql()->prepare('DELETE FROM lyre_projects WHERE id = ? AND user_id = ?');
            $del->execute([$pid, 42]);
            gos_lyre_pg_delete(gos_lyre_pg(), $boardId);
        } catch (Throwable) {
        }
    }
} else {
    expect_true(
        in_array((string) ($created['error'] ?? ''), ['lyre_pg_unavailable', 'lyre_unconfigured', 'db_error', 'auth_required'], true),
        'create without DB fails via GosLyreException codes not raw exit'
    );
}

$front = file_get_contents(dirname(__DIR__) . '/api/lyre-mcp.php') ?: '';
expect_true(str_contains($front, "define('GOS_SKIP_SESSION', true);"), 'front controller skips session');
expect_true(str_contains($front, "define('GOS_LYRE_NO_ROUTE', true);"), 'front controller skips lyre router');

lyre_mcp_rmdir($mcpDir);

if ($fails > 0) {
    fwrite(STDERR, "{$fails} failed\n");
    exit(1);
}
fwrite(STDOUT, "ok\n");
