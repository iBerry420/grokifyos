<?php
declare(strict_types=1);
define('GOS_SKIP_SESSION', true);
define('GOS_LYRE_NO_ROUTE', true);
require_once __DIR__ . '/lyre.php';
require_once dirname(__DIR__) . '/includes/lyre_mcp.php';
gos_lyre_mcp_handle_request();
