<?php

declare(strict_types=1);

require_once dirname(__DIR__) . '/includes/bootstrap.php';

$settings = require dirname(__DIR__) . '/includes/settings.php';
$appName = (string) ($settings['app_name'] ?? 'GrokifyOS');
$user = gos_current_user();
$needsSetup = gos_needs_setup();
$base = gos_web_base();
$h = static function (string $path) use ($base): string {
    return htmlspecialchars($base . $path, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
};

$canAccess = $user !== null;
$displayName = $canAccess ? (string) ($user['display_name'] ?: $user['username']) : '';
$role = $canAccess ? (string) ($user['role'] ?? '') : '';
$chatReady = gos_system_chat_tables_ready();
$devPack = $canAccess ? gos_devices_for_user((int) $user['id']) : ['devices' => [], 'active' => []];
$activeDevices = $devPack['active'];
$latestApk = $canAccess ? gos_latest_apk() : null;
$assetV = '20260822-usage-tracker';
?><!DOCTYPE html>
<html lang="en" class="dark">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <meta name="theme-color" content="#070a10">
  <meta name="color-scheme" content="dark">
  <meta name="robots" content="noindex,nofollow">
  <title><?= htmlspecialchars($appName, ENT_QUOTES) ?></title>
  <link rel="stylesheet" href="<?= $h('/assets/css/app.css') ?>?v=<?= $assetV ?>">
  <link rel="stylesheet" href="<?= $h('/assets/css/system-chat.css') ?>?v=<?= $assetV ?>">
  <link rel="icon" href="<?= $h('/assets/grokify-icon.png') ?>" type="image/png">
</head>
<body>
<?php if (!$canAccess): ?>
  <div class="gf-shell">
    <div class="gf-gate">
      <div class="gf-logo" style="width:3.25rem;height:3.25rem">
        <img src="<?= $h('/assets/grokify-icon.png') ?>" alt="" width="52" height="52" onerror="this.remove()">
      </div>
      <h1><?= htmlspecialchars($appName, ENT_QUOTES) ?></h1>
      <p class="gf-tagline"><?= $needsSetup ? 'Initialize · admin' : 'Access · console' ?></p>
      <form class="gf-gate-form" id="auth-form" autocomplete="on">
        <label class="gf-label" for="username">User</label>
        <input class="gf-input" id="username" name="username" required minlength="3" maxlength="32" pattern="[a-zA-Z0-9_]+" autocomplete="username">
        <label class="gf-label" for="password">Pass</label>
        <input class="gf-input" id="password" name="password" type="password" required minlength="8" autocomplete="<?= $needsSetup ? 'new-password' : 'current-password' ?>">
        <?php if ($needsSetup): ?>
        <label class="gf-label" for="display_name">Display</label>
        <input class="gf-input" id="display_name" name="display_name" maxlength="128" autocomplete="nickname">
        <?php endif; ?>
        <button type="submit" class="gf-btn gf-btn-primary gf-btn-block" style="margin-top:1rem">
          <?= $needsSetup ? 'Boot admin' : 'Enter' ?>
        </button>
        <div class="gf-msg" id="msg"></div>
      </form>
    </div>
  </div>
  <script>
    const base = <?= json_encode($base) ?>;
    const needsSetup = <?= $needsSetup ? 'true' : 'false' ?>;
    const msg = document.getElementById('msg');
    document.getElementById('auth-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      msg.className = 'gf-msg'; msg.textContent = '…';
      const body = {
        username: document.getElementById('username').value.trim(),
        password: document.getElementById('password').value,
      };
      const dn = document.getElementById('display_name');
      if (dn) body.display_name = dn.value.trim();
      const path = needsSetup ? '/api/setup.php' : '/api/login.php';
      const res = await fetch(base + path, {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(body),
      });
      const data = await res.json().catch(() => ({}));
      if (!data.ok) {
        msg.className = 'gf-msg err';
        msg.textContent = data.error || ('HTTP ' + res.status);
        return;
      }
      msg.className = 'gf-msg ok';
      msg.textContent = 'OK';
      location.reload();
    });
  </script>
<?php else: ?>
  <div class="gf-app">
    <header class="gf-header">
      <div class="gf-header-inner">
        <div class="gf-logo">
          <img src="<?= $h('/assets/grokify-icon.png') ?>" alt="" width="36" height="36" onerror="this.parentElement.textContent='G'">
        </div>
        <div class="gf-title">
          <h1><?= htmlspecialchars($appName, ENT_QUOTES) ?></h1>
          <p><?= htmlspecialchars($displayName, ENT_QUOTES) ?><?= $role ? ' · ' . htmlspecialchars($role, ENT_QUOTES) : '' ?></p>
        </div>
        <span class="gf-badge" id="gf-bridge-badge">bridge…</span>
        <button type="button" class="gf-btn gf-btn-inline" id="btn-logout">Out</button>
      </div>
    </header>

    <nav class="gf-nav" aria-label="GrokifyOS">
      <button type="button" class="active" data-goto="home">Home</button>
      <button type="button" data-goto="chat">Chat</button>
      <button type="button" data-goto="devices">Devices</button>
      <button type="button" data-goto="build">Build</button>
    </nav>

    <div class="gf-shell gf-main">
    <section class="gf-panel active" id="panel-home" data-panel="home">
      <div class="gf-stat-grid">
        <div class="gf-stat">
          <div class="n" id="stat-devices"><?= count($activeDevices) ?></div>
          <div class="l">Devices</div>
        </div>
        <div class="gf-stat">
          <div class="n" id="stat-apk"><?= $latestApk ? htmlspecialchars((string) $latestApk['version_name'], ENT_QUOTES) : '—' ?></div>
          <div class="l">APK</div>
        </div>
        <div class="gf-stat">
          <div class="n" id="stat-model">…</div>
          <div class="l">Model</div>
        </div>
        <div class="gf-stat">
          <div class="n" id="stat-bridge">…</div>
          <div class="l">Bridge</div>
        </div>
      </div>

      <div class="gf-home-grid" style="margin-top:0.85rem">
        <div class="gf-card">
          <h2>Deploy</h2>
          <?php if ($latestApk): ?>
            <p class="gf-muted">
              <strong style="color:#fff;font-family:var(--gf-mono)"><?= htmlspecialchars((string) $latestApk['version_name'], ENT_QUOTES) ?></strong>
              · <?= number_format((int) $latestApk['file_size'] / 1048576, 1) ?> MB
            </p>
            <a class="gf-btn gf-btn-accent" style="margin-top:0.75rem;text-align:center"
               href="<?= $h('/api/apk-download.php') ?>" download="grokifyos.apk">Get APK</a>
          <?php else: ?>
            <p class="gf-muted">No release.</p>
          <?php endif; ?>
        </div>

        <div class="gf-card">
          <h2>Jump</h2>
          <div class="gf-row">
            <button type="button" class="gf-btn gf-btn-accent" data-goto="chat">Chat</button>
            <button type="button" class="gf-btn" data-goto="devices">Devices</button>
            <button type="button" class="gf-btn" data-goto="build">Build</button>
          </div>
          <?php if (!$chatReady): ?>
            <p class="gf-badge warn" style="margin-top:0.75rem;display:inline-block">schema</p>
          <?php endif; ?>
        </div>
      </div>
    </section>

    <section class="gf-panel" id="panel-chat" data-panel="chat">
      <?php if ($chatReady): ?>
      <div id="sc-root" class="sc-root">
        <div id="sc-bridge-warn" class="sc-bridge-warn hidden">Bridge offline</div>
        <div class="sc-topbar">
          <span class="sc-topbar-title" id="sc-topbar-title">New Chat</span>
          <span class="sc-status-dot" id="sc-conn-dot" title="WebSocket"></span>
          <button type="button" class="sc-usage-chip" id="sc-usage-chip" title="Usage">Usage …</button>
          <button type="button" class="sc-toolbar-btn" id="sc-open-log" title="Audit">Log</button>
        </div>
        <div class="sc-messages" id="sc-messages">
          <div class="sc-welcome" id="sc-welcome">
            <h3>Ready</h3>
            <p>Session + bridge</p>
          </div>
        </div>
        <div class="sc-toolbar">
          <div class="sc-wrap relative" id="sc-history-wrap">
            <button type="button" class="sc-toolbar-btn" id="sc-history-btn">History</button>
            <div class="sc-popover" id="sc-history-popover">
              <div class="sc-popover-header">
                <span>Sessions</span>
                <button type="button" class="text-xs text-white" id="sc-new-chat">+ New</button>
              </div>
              <div class="sc-popover-body" id="sc-session-list"></div>
            </div>
          </div>
          <button type="button" class="sc-toolbar-btn active" id="sc-context-toggle">Context</button>
          <button type="button" class="sc-toolbar-btn active" id="sc-keep-awake" title="Keep screen on">Screen</button>
          <div class="sc-wrap relative" id="sc-notes-wrap">
            <button type="button" class="sc-toolbar-btn" id="sc-notes-btn">Notes <span id="sc-notes-badge" class="hidden text-[10px] bg-white/20 px-1 rounded"></span></button>
            <div class="sc-popover" id="sc-notes-popover" style="min-width:280px">
              <div class="sc-popover-header"><span>Instructions</span></div>
              <div class="sc-popover-body" id="sc-notes-list"></div>
              <div class="p-2 border-t border-[#272b31] flex gap-1">
                <input type="text" id="sc-notes-input" maxlength="500" placeholder="Add…" class="flex-1 text-xs bg-[#0f1115] border border-[#272b31] rounded px-2 py-1 text-white">
                <button type="button" id="sc-notes-add" class="text-xs px-2 py-1 bg-white text-[#0f1115] rounded font-semibold">Add</button>
              </div>
            </div>
          </div>
          <div class="sc-toolbar-spacer"></div>
          <div class="sc-wrap relative" id="sc-settings-wrap">
            <button type="button" class="sc-toolbar-btn" id="sc-settings-btn">Settings</button>
            <div class="sc-popover sc-settings-popover" id="sc-settings-popover" style="right:0;left:auto">
              <div class="sc-popover-header">Settings</div>
              <div class="sc-settings-body">
                <div id="sc-usage-detail" class="sc-usage-detail">
                  <div class="sc-usage-detail-head">
                    <div>
                      <div class="sc-usage-detail-title">Weekly</div>
                      <span class="sc-usage-detail-tier" id="sc-usage-tier" hidden></span>
                    </div>
                    <button type="button" class="sc-usage-refresh" id="sc-usage-refresh" title="Refresh">↻</button>
                  </div>
                  <div class="sc-usage-detail-body" id="sc-usage-detail-body">…</div>
                  <div class="sc-usage-actions">
                    <button type="button" class="sc-usage-logout" id="sc-usage-logout" title="Sign out Grok Build and open a fresh OAuth link">
                      Log out &amp; get login link
                    </button>
                  </div>
                </div>
                <label class="sc-settings-label" for="sc-model-select">Model</label>
                <select id="sc-model-select" class="sc-select w-full max-w-none"></select>
                <label class="sc-settings-label" for="sc-effort-select">Reasoning</label>
                <select id="sc-effort-select" class="sc-select w-full max-w-none"></select>
                <div class="sc-setting-sub" id="sc-effort-hint">How hard the selected model thinks</div>

                <div class="sc-settings-section">WORKSPACE</div>
                <div class="sc-workdir-block">
                  <div class="sc-setting-title">Working directory</div>
                  <div class="sc-setting-sub" id="sc-workdir-hint">Agent cwd on the bridge server</div>
                  <div class="sc-workdir-path mono" id="sc-workdir-current">…</div>
                  <div class="sc-workdir-row">
                    <input type="text" id="sc-workdir-input" class="sc-workdir-input" spellcheck="false" autocomplete="off" placeholder="/path/on/server">
                    <button type="button" class="sc-toolbar-btn" id="sc-workdir-apply">Set</button>
                  </div>
                  <div class="sc-workdir-actions">
                    <button type="button" class="sc-toolbar-btn" id="sc-workdir-browse">Browse</button>
                    <button type="button" class="sc-toolbar-btn" id="sc-workdir-reset">Default</button>
                  </div>
                  <div class="sc-workdir-browser hidden" id="sc-workdir-browser">
                    <div class="sc-workdir-browser-head">
                      <button type="button" class="sc-toolbar-btn" id="sc-workdir-up" title="Parent">↑</button>
                      <span class="sc-workdir-browser-path mono" id="sc-workdir-browse-path"></span>
                      <button type="button" class="sc-toolbar-btn" id="sc-workdir-use" title="Use this folder">Use</button>
                    </div>
                    <div class="sc-workdir-list" id="sc-workdir-list"></div>
                  </div>
                  <div class="sc-setting-sub" id="sc-workdir-status"></div>
                </div>

                <div class="sc-settings-section">CHAT</div>
                <label class="sc-setting-row" for="sc-set-history">
                  <span class="sc-setting-text">
                    <span class="sc-setting-title">History</span>
                    <span class="sc-setting-sub">Prior messages in prompt</span>
                  </span>
                  <input type="checkbox" id="sc-set-history" class="sc-setting-check" checked>
                </label>
                <label class="sc-setting-row" for="sc-set-keep-awake">
                  <span class="sc-setting-text">
                    <span class="sc-setting-title">Screen on</span>
                    <span class="sc-setting-sub">Wake lock while open</span>
                  </span>
                  <input type="checkbox" id="sc-set-keep-awake" class="sc-setting-check" checked>
                </label>
                <label class="sc-setting-row" for="sc-set-enter-newline">
                  <span class="sc-setting-text">
                    <span class="sc-setting-title">Enter = newline</span>
                    <span class="sc-setting-sub" id="sc-set-enter-hint">Ctrl+Enter sends</span>
                  </span>
                  <input type="checkbox" id="sc-set-enter-newline" class="sc-setting-check" checked>
                </label>
                <label class="sc-setting-row" for="sc-set-show-tools">
                  <span class="sc-setting-text">
                    <span class="sc-setting-title">Tools</span>
                    <span class="sc-setting-sub" id="sc-set-tools-hint">Show tool cards</span>
                  </span>
                  <input type="checkbox" id="sc-set-show-tools" class="sc-setting-check" checked>
                </label>
                <label class="sc-setting-row" for="sc-set-show-thoughts">
                  <span class="sc-setting-text">
                    <span class="sc-setting-title">Thoughts</span>
                    <span class="sc-setting-sub" id="sc-set-thoughts-hint">Show thinking cards</span>
                  </span>
                  <input type="checkbox" id="sc-set-show-thoughts" class="sc-setting-check" checked>
                </label>
              </div>
            </div>
          </div>
        </div>
        <div class="sc-input-area">
          <div class="sc-input-wrap">
            <textarea id="sc-prompt" rows="2" placeholder="Message…"></textarea>
            <button type="button" class="sc-send-btn" id="sc-send-btn" disabled title="Send">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
            </button>
          </div>
        </div>
        <div class="sc-log-panel" id="sc-log-panel">
          <div class="sc-topbar">
            <span class="sc-topbar-title text-sm">Audit</span>
            <select id="sc-log-filter-level" class="sc-select">
              <option value="">All</option>
              <option value="debug">Debug</option>
              <option value="info">Info</option>
              <option value="warning">Warn</option>
              <option value="error">Error</option>
            </select>
            <select id="sc-log-filter-cat" class="sc-select">
              <option value="">Cats</option>
              <option value="access">Access</option>
              <option value="connection">Connection</option>
              <option value="message">Message</option>
              <option value="agent">Agent</option>
              <option value="process">Process</option>
              <option value="agent_done">Done</option>
              <option value="error">Error</option>
            </select>
            <button type="button" class="sc-toolbar-btn" id="sc-close-log">Close</button>
          </div>
          <div class="sc-log-body" id="sc-log-body"></div>
        </div>
      </div>
      <div class="sc-msg-actions" id="sc-msg-actions" aria-hidden="true">
        <button type="button" class="sc-msg-action-btn" data-action="copy" title="Copy">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/></svg>
        </button>
        <button type="button" class="sc-msg-action-btn" data-action="exclude" title="Exclude">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0112 20c-7 0-11-8-11-8a18.45 18.45 0 015.06-5.94"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
        </button>
        <button type="button" class="sc-msg-action-btn" data-action="delete" title="Delete">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/></svg>
        </button>
      </div>
      <?php else: ?>
      <div class="gf-card"><p class="gf-muted">Chat schema missing.</p></div>
      <?php endif; ?>
    </section>

    <section class="gf-panel" id="panel-devices" data-panel="devices">
      <div class="gf-split">
        <div class="gf-card">
          <h2>Mint token</h2>
          <label class="gf-label">Name</label>
          <input type="text" id="device-name" class="gf-input" placeholder="Pixel" maxlength="128">
          <button type="button" class="gf-btn gf-btn-primary" id="btn-create-device" style="margin-top:0.75rem">Create</button>
          <div id="new-token-wrap" class="hidden" style="margin-top:0.85rem">
            <p class="gf-faint" style="margin-bottom:0.35rem">Once:</p>
            <div class="gf-token-box" id="new-token"></div>
            <button type="button" class="gf-btn gf-btn-sm" id="btn-copy-token" style="margin-top:0.5rem">Copy</button>
          </div>
        </div>
        <div class="gf-card">
          <h2>Fleet</h2>
          <div id="device-list">
            <?php if (!$activeDevices): ?>
              <p class="gf-muted">Empty</p>
            <?php else: ?>
              <?php foreach ($activeDevices as $d): ?>
              <div class="gf-device" data-id="<?= (int) $d['id'] ?>">
                <div class="gf-device-body">
                  <strong><?= htmlspecialchars((string) $d['device_name'], ENT_QUOTES) ?></strong>
                  <div class="gf-faint">
                    <?= htmlspecialchars((string) $d['token_prefix'], ENT_QUOTES) ?>…
                    <?php if (!empty($d['app_version_name'])): ?>
                      · v<?= htmlspecialchars((string) $d['app_version_name'], ENT_QUOTES) ?>
                    <?php endif; ?>
                    <?php if (!empty($d['last_seen_at'])): ?>
                      · <?= htmlspecialchars((string) $d['last_seen_at'], ENT_QUOTES) ?>
                    <?php endif; ?>
                  </div>
                </div>
                <button type="button" class="gf-btn gf-btn-danger gf-btn-sm btn-revoke" data-id="<?= (int) $d['id'] ?>">Revoke</button>
              </div>
              <?php endforeach; ?>
            <?php endif; ?>
          </div>
        </div>
      </div>
    </section>

    <section class="gf-panel" id="panel-build" data-panel="build">
      <div class="gf-split">
        <div class="gf-card">
          <h2>Release</h2>
          <?php if ($latestApk): ?>
            <p class="gf-muted">
              <strong style="color:#fff;font-family:var(--gf-mono)"><?= htmlspecialchars((string) $latestApk['version_name'], ENT_QUOTES) ?></strong>
              · code <?= (int) $latestApk['version_code'] ?>
              · <?= number_format((int) $latestApk['file_size'] / 1048576, 1) ?> MB
            </p>
            <?php if (!empty($latestApk['changelog'])): ?>
              <p class="gf-faint" style="margin-top:0.5rem;white-space:pre-wrap"><?= htmlspecialchars((string) $latestApk['changelog'], ENT_QUOTES) ?></p>
            <?php endif; ?>
            <a class="gf-btn gf-btn-accent" style="margin-top:0.85rem;text-align:center"
               href="<?= $h('/api/apk-download.php') ?>" download="grokifyos.apk">Download</a>
          <?php else: ?>
            <p class="gf-muted">No APK</p>
          <?php endif; ?>
        </div>
        <div class="gf-card">
          <h2>Publish</h2>
          <pre class="gf-code">cd android
./scripts/publish.sh debug --changelog "…"</pre>
        </div>
      </div>
    </section>
    </div><!-- .gf-shell -->
  </div><!-- .gf-app -->

  <script>
    window.API_BASE = <?= json_encode($base . '/api') ?>;
  </script>
  <script src="<?= $h('/assets/vendor/marked/marked.min.js') ?>"></script>
  <script src="<?= $h('/assets/system-chat.js') ?>?v=<?= $assetV ?>"></script>
  <script>
  (function () {
    const base = <?= json_encode($base) ?>;
    const $ = (id) => document.getElementById(id);

    function showPanel(name) {
      document.querySelectorAll('.gf-panel').forEach((p) => p.classList.toggle('active', p.dataset.panel === name));
      document.querySelectorAll('.gf-nav button').forEach((b) => b.classList.toggle('active', b.dataset.goto === name));
      if (name === 'chat' && typeof systemChatInit === 'function') systemChatInit();
      if (name !== 'chat' && typeof systemChatOnTabLeave === 'function') systemChatOnTabLeave();
      try { history.replaceState(null, '', '#' + name); } catch (_) {}
    }

    document.querySelectorAll('[data-goto]').forEach((el) => {
      el.addEventListener('click', () => showPanel(el.dataset.goto));
    });

    $('btn-logout')?.addEventListener('click', async () => {
      await fetch(base + '/api/logout.php', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}' });
      location.reload();
    });

    $('btn-create-device')?.addEventListener('click', async () => {
      const name = ($('device-name')?.value || 'Android').trim() || 'Android';
      const res = await fetch(base + '/api/devices.php', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ device_name: name }),
      });
      const data = await res.json().catch(() => ({}));
      if (!data.ok) { alert(data.error || 'failed'); return; }
      $('new-token').textContent = data.token;
      $('new-token-wrap').classList.remove('hidden');
      const list = $('device-list');
      if (list && data.device) {
        const empty = list.querySelector('.gf-muted');
        if (empty) empty.remove();
        const row = document.createElement('div');
        row.className = 'gf-device';
        row.dataset.id = data.device.id;
        row.innerHTML = '<div class="gf-device-body"><strong></strong><div class="gf-faint"></div></div>' +
          '<button type="button" class="gf-btn gf-btn-danger gf-btn-sm btn-revoke" data-id="' + data.device.id + '">Revoke</button>';
        row.querySelector('strong').textContent = data.device.device_name;
        row.querySelector('.gf-faint').textContent = data.device.token_prefix + '…';
        list.prepend(row);
        bindRevoke(row.querySelector('.btn-revoke'));
        const n = $('stat-devices');
        if (n) n.textContent = String((parseInt(n.textContent, 10) || 0) + 1);
      }
    });

    $('btn-copy-token')?.addEventListener('click', async () => {
      const t = $('new-token')?.textContent || '';
      try { await navigator.clipboard.writeText(t); } catch (_) {}
    });

    function bindRevoke(btn) {
      if (!btn || btn.dataset.bound) return;
      btn.dataset.bound = '1';
      btn.addEventListener('click', async () => {
        const id = btn.dataset.id;
        if (!confirm('Revoke?')) return;
        const res = await fetch(base + '/api/devices.php?id=' + encodeURIComponent(id), {
          method: 'DELETE', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify({ id: Number(id) }),
        });
        const data = await res.json().catch(() => ({}));
        if (data.ok) {
          btn.closest('.gf-device')?.remove();
          const n = $('stat-devices');
          if (n) n.textContent = String(Math.max(0, (parseInt(n.textContent, 10) || 1) - 1));
        }
      });
    }
    document.querySelectorAll('.btn-revoke').forEach(bindRevoke);

    async function refreshHomeStats() {
      try {
        const res = await fetch(base + '/api/admin-system-chat-models.php', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
        const data = await res.json().catch(() => ({}));
        if (data.ok) {
          const sel = (data.selected || data.default_model || '').replace(/^gb:/, '');
          if ($('stat-model')) $('stat-model').textContent = sel || '—';
          const ok = data.bridge_healthy !== false;
          if ($('stat-bridge')) $('stat-bridge').textContent = ok ? 'OK' : 'DOWN';
          const badge = $('gf-bridge-badge');
          if (badge) {
            badge.textContent = ok ? 'bridge ok' : 'bridge down';
            badge.classList.toggle('ok', ok);
            badge.classList.toggle('warn', !ok);
          }
        }
      } catch (_) {
        if ($('stat-bridge')) $('stat-bridge').textContent = '?';
      }
    }
    refreshHomeStats();

    const hash = (location.hash || '').replace(/^#/, '');
    if (hash && document.getElementById('panel-' + hash)) showPanel(hash);
  })();
  </script>
<?php endif; ?>
</body>
</html>
