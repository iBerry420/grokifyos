# CexBot → cexbot.grokpot.io + GrokifyOS bridge + Android inner app

> **For agents:** Implement task-by-task. Do not start a second GrokifyOS bridge. Do not mount studio / Discord / GIF / tokens / research / agent routers. Phone OTA is `channel=phone`.

**Goal:** Ship the live CexBot Trade desk (index + Portfolio + History + Settings) at `https://cexbot.grokpot.io` with private login, keep `/bot/v1` byte-compatible, point the Trade AI column at the existing GrokifyOS WebSocket, then wrap that same site as a phone inner app.

**Architecture:** One CexBot Node process on loopback `:4200`. Apache terminates TLS and proxies `/api`, `/ws`, `/bot/v1`, `/uploads`. Trade chat opens `wss://grokifyos.grokpot.io/grokify-ws/` (existing `:8876` workers). CexBot backend mints a GrokifyOS session + HMAC token after desk login. Grok skills call `http://127.0.0.1:4200/bot/v1`. Android is a persisted WebView of the same origin.

**Tech stack:** CexBot PHP + Express + TypeORM/MySQL + Redis; GrokifyOS PHP token mint + existing bridge; Apache; Kotlin Compose host module + WebView.

**Locks (already decided):**

1. Option 3 — frontend talks to the existing GrokifyOS WS. No adapter worker, no second bridge process.
2. Ship surface = current `index.php` nav: Trade, Portfolio, Order History, Settings.
3. Out of ship: studio, Discord, GIF, tokens, research, unmounted `agent*.ts` routers.
4. `/bot/v1` paths, bodies, and `Authorization: Bearer <BOT_API_KEY|JWT>` stay intact.
5. Phone and web are interchangeable because they load the same site and the same bridge.

---

## Runtime (no new daemons)

```
Browser / Android WebView
        │
        ▼
https://cexbot.grokpot.io          Apache 443
        │
        ├── PHP pages (login, index, portfolio, history, settings)
        ├── /api  /ws  /bot/v1  /uploads  →  127.0.0.1:4200
        └── GET /api/assistant/bridge     →  CexBot Node
                                              │
                                              └── loopback POST GrokifyOS PHP
                                                    mint ws_token + 32-hex session

Trade AI column
        │
        ▼
wss://grokifyos.grokpot.io/grokify-ws/?token=…     existing :8876
        │
        └── grok spawn (cwd = GrokifyOS workspace)
              skills cexbot-api + crypto-waves
              scripts → http://127.0.0.1:4200/bot/v1
```

CexBot `/bridge` is **not** proxied and **not** used. Leave the TypeScript on disk; do not upgrade `/bridge` in `backend/src/index.ts`.

---

## Why chat.js cannot only change the URL

GrokifyOS `bridge/server.js` vs CexBot `frontend/assets/js/chat.js`:

| | CexBot `/bridge` today | GrokifyOS `:8876` |
|---|---|---|
| Auth | CexBot JWT (`type: auth` + `?token=`) | HMAC `uid.exp` from `gos_system_chat_ws_token` |
| Session id | `day_YYYY-MM-DD` in localStorage | 32 hex row in `system_chat_sessions`, owned by uid |
| First message | `type: auth` then `type: chat` | any non-`reconnect` frame is a prompt |
| Ping | JSON `{type: ping}` | JSON ping would be treated as an invalid prompt |
| Ready | server sends `ready` / `models` | none — models come from HTTP |
| Extra fields | `symbols`, `useWaves`, `liveTrading` | ignored; use `notes` + `history` |

Adapter lives in CexBot `chat.js` + one CexBot HTTP endpoint. Bridge process stays unchanged except env (`BOT_API_KEY`, `CEXBOT_API_BASE`).

---

## Private access (phone-safe)

Do **not** IP-allowlist. The inner app is on cellular.

- TLS on `cexbot.grokpot.io` (A record already `209.126.5.88`).
- Existing single admin password + 30-day JWT.
- `session.cookie_secure=1`, `Secure` on `cexbot_token`, no default `JWT_SECRET` / `ADMIN_PASSWORD`.
- `X-Robots-Tag: noindex`, `robots.txt` Disallow `/`.
- `/bot/v1` remains key-gated (external Grok Bot unchanged).
- Node binds `127.0.0.1` only.

---

## This VPS right now

- Stale unit `/etc/systemd/system/cexbot.service` pointed at `/home/cexbot/backend` and crash-looped on MySQL `root@127.0.0.1` (1.5M+ restarts). **Stopped and disabled** while writing this plan.
- Redis Stack Docker already on `127.0.0.1:6379` (passworded). Reuse it; do not start another Redis.
- No `cexbot` MariaDB database yet. GrokifyOS DB user cannot create one — need MariaDB admin (root `.my.cnf` is stale).
- GrokifyOS admin uid is `1`. Create a dedicated GrokifyOS user `cexbot` so desk threads do not land in personal System Chat.

---

### Task 0: Stop the crash loop (done)

```bash
systemctl stop cexbot.service
systemctl disable cexbot.service
```

Leave `/home/cexbot` as archive. New checkout is `/var/www/cexbot`.

---

### Task 1: MariaDB `cexbot` database + user

**Files:** none in git (ops).

Create database `cexbot` and user `cexbot`@`127.0.0.1` with a new password. Grant only that DB. Do not reuse the GrokifyOS DB or MySQL root.

If `/root/.my.cnf` still fails (`Access denied for root@localhost`), recover MariaDB admin first — nothing else boots without this.

Verify:

```bash
mysql -ucexbot -p -h127.0.0.1 -e 'SELECT DATABASE();'
```

Expected: `cexbot`.

---

### Task 2: Fresh checkout at `/var/www/cexbot`

```bash
git clone git@github.com:iBerry420/cexbot.git /var/www/cexbot
# fallback: https://github.com/iBerry420/cexbot.git
cd /var/www/cexbot/backend && npm ci && npm run build
```

Do not copy the Feb `/home/cexbot` tree (old, no Bot API / skills).

`backend/.env.local` (new secrets; do not reuse defaults):

```
PORT=4200
NODE_ENV=production
BIND_HOST=127.0.0.1
DB_HOST=127.0.0.1
DB_USER=cexbot
DB_PASSWORD=…
DB_NAME=cexbot
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=…   # same as redis-stack
CDC_API_KEY=…      # copy from /home/cexbot/backend/.env.local
CDC_API_SECRET=…
JWT_SECRET=…       # new 32+ bytes
ADMIN_PASSWORD=…   # new, strong
BOT_API_KEY=…      # keep or rotate; external Grok Bot must match
TRADING_MODE=demo
GROK_BIN=/root/.grok/bin/grok
CEXBOT_ROOT=/var/www/cexbot
CEXBOT_API_BASE=http://127.0.0.1:4200/bot/v1
GROKIFY_BRIDGE_MINT_URL=http://127.0.0.1/cexbot-mint.php
# Apache will not hit that path — use a loopback Alias on grokifyos or
# http://127.0.0.1:80 with Host: grokifyos.grokpot.io
GROKIFY_CEXBOT_MINT_KEY=…
```

CDC / trading secrets come from the old env. Auth secrets are new.

TypeORM `synchronize` is `false` and GitHub has no migrations. First boot:

```bash
cd /var/www/cexbot/backend
# one-shot schema from entities (includes unused studio tables — unused, unmounted)
npx typeorm schema:sync -d dist/config/database.js
```

Leave `synchronize: false` after that. `ensure-schema.ts` still adds wave indexes.

---

### Task 3: Bind Node to loopback + skip `/bridge`

**Files:**

- Modify: `/var/www/cexbot/backend/src/index.ts` (listen host; drop `/bridge` upgrade)
- Modify: `/var/www/cexbot/backend/src/config/index.ts` if `BIND_HOST` is not already read

Listen `127.0.0.1:4200`. In `server.on('upgrade')`, handle `/ws` only; destroy `/bridge`.

Do not `app.use` studio / discord / gif / tokens / research / agent routers (already unmounted).

---

### Task 4: GrokifyOS mint endpoint (no new process)

**Files:**

- Create: `web/api/cexbot-bridge.php`
- Modify: `/etc/grokifyos/php.env` — `GROKIFY_CEXBOT_MINT_KEY`, `GROKIFY_CEXBOT_USER_ID`

`POST /api/cexbot-bridge.php` (GrokifyOS origin, loopback only in Apache):

- Header `X-CexBot-Mint: <GROKIFY_CEXBOT_MINT_KEY>` (timing-safe compare).
- Body `{ "title": "CexBot 2026-08-18" }` (optional `session_id` if already hex).
- Resolve user `GROKIFY_CEXBOT_USER_ID` (dedicated `cexbot` user, not admin `1`).
- Upsert `system_chat_sessions` by `(user_id, title)` or create via `gos_system_chat_session_id()`.
- Return:

```json
{
  "ok": true,
  "ws_token": "…",
  "ws_url": "wss://grokifyos.grokpot.io/grokify-ws/",
  "session_id": "32hex",
  "models": []
}
```

`ws_token` = `gos_system_chat_ws_token($user)`.

Apache on `grokifyos.grokpot.io`:

```
<Location /api/cexbot-bridge.php>
    Require local
</Location>
```

Create GrokifyOS user:

```sql
INSERT INTO users (username, display_name, password_hash, role, status)
VALUES ('cexbot', 'CexBot', '*', 'user', 'active');
```

Password unused (mint key only). Record the new `id` in `GROKIFY_CEXBOT_USER_ID`.

---

### Task 5: CexBot `/api/assistant/bridge` + desk context

**Files:**

- Modify: `/var/www/cexbot/backend/src/routes/assistant.ts`

`GET /api/assistant/bridge` (CexBot JWT):

- Proxy POST to GrokifyOS mint with today's title `CexBot YYYY-MM-DD`.
- Return `{ ws_token, ws_url, session_id, models }` to the browser.

`GET /api/assistant/desk-context?symbols=&timeframe=&deviation=` (CexBot JWT):

- Reuse `deskSnapshot()` + `compactWaves()` from `services/bridge/grok-bridge.ts` (extract to a small helper so `/bridge` spawn is unused).
- Return a single string the client puts in `notes`.

Copilot instructions (live trading flag, `cexbot-api.sh` absolute path, never print `BOT_API_KEY`) also go into `notes` on the client. GrokifyOS already forwards `notes` into the prompt (`fitPromptContext`).

---

### Task 6: `chat.js` + `config.js` speak GrokifyOS

**Files:**

- Modify: `/var/www/cexbot/frontend/assets/js/config.js`
- Modify: `/var/www/cexbot/frontend/assets/js/chat.js`
- Modify: `/var/www/cexbot/frontend/includes/config.php`
- Modify: `/var/www/cexbot/frontend/login.php` (Secure cookie)

`config.js` — treat `cexbot.grokpot.io` as same-origin:

```js
const _viaApache = ['cexbot.local', 'cexbot.grokpot.io'].includes(location.hostname);
API_URL: _viaApache ? '/api' : 'http://localhost:4200/api',
WS_URL:  _viaApache
  ? `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`
  : 'ws://localhost:4200/ws',
BRIDGE_WS: null, // filled after /api/assistant/bridge
```

`config.php` — `API_URL` = `http://127.0.0.1:4200/api` for PHP→Node (login curl). Browser never uses that. `session.cookie_secure = 1` when HTTPS. Login `setcookie` gets `'secure' => true`.

`chat.js` adapter (keep the existing bubbles / thoughts / tools UI):

1. Keep `day_*` keys in localStorage for the session rail.
2. Store `bridgeId` (32 hex) on the session object.
3. `connectBridge`:
   - `GET /api/assistant/bridge` with CexBot JWT.
   - `new WebSocket(ws_url + '?token=' + encodeURIComponent(ws_token))`.
   - On open: `{ type: 'reconnect', session_id: bridgeId }`; set `bridgeReady = true` (no `ready` event).
   - Do **not** send `{ type: 'auth' }`.
   - Do **not** send `{ type: 'ping' }`. Rely on browser WS ping or skip.
4. `send`:
   - Payload fields GrokifyOS reads: `prompt`, `session_id` (hex), `model`, `history`, `notes`.
   - `notes` = enabled user notes + desk-context string + copilot instructions (absolute `/var/www/cexbot/scripts/cexbot-api.sh`).
   - Extra CexBot fields may remain; they are ignored.
5. Incoming events already match (`thinking_delta`, `chunk`, `tool_start`, `tool_done`, `media`, `done`, `error`).
6. `loadModels` from the mint payload or GrokifyOS is optional; empty model string uses the bridge default.

---

### Task 7: Skills on the existing GrokifyOS worker

**Files:**

- Create: `/root/grokifyos/.grok/skills/cexbot-api/SKILL.md` (from CexBot, paths rewritten)
- Create: `/root/grokifyos/.grok/skills/crypto-waves/SKILL.md` (same)
- Modify: `/etc/grokifyos/bridge.env` — add `BOT_API_KEY` and `CEXBOT_API_BASE=http://127.0.0.1:4200/bot/v1`
- Do **not** change `bridge_agent_cwd`

Skill text must call `/var/www/cexbot/scripts/cexbot-api.sh` (not `bash scripts/cexbot-api.sh` relative to GrokifyOS). `waves-tool.py` same idea.

Restart only the existing workers after env change:

```bash
systemctl restart grokifyos-bridge-a grokifyos-bridge-b
```

Do not add a CexBot-only worker.

---

### Task 8: Apache vhost + cert

**Files:**

- Create: `deploy/cexbot.grokpot.io-le-ssl.conf.example`
- Create: `/etc/apache2/sites-available/cexbot.grokpot.io.conf` (+ certbot SSL)

HTTP 80: ACME + redirect.

HTTPS 443:

- `ServerName cexbot.grokpot.io`
- `DocumentRoot /var/www/cexbot/frontend`
- PHP for `*.php`
- `ProxyPass /api http://127.0.0.1:4200/api`
- `ProxyPass /bot/v1 http://127.0.0.1:4200/bot/v1`
- `ProxyPass /uploads http://127.0.0.1:4200/uploads`
- WS rewrite `/ws` → `ws://127.0.0.1:4200/ws`
- **No** `/bridge` proxy
- Forward `Authorization` (same rewrite as GrokifyOS `/api`)
- Headers: HSTS, `X-Robots-Tag noindex`, `X-Frame-Options SAMEORIGIN` (WebView is not an iframe)
- `robots.txt` Disallow `/`

```bash
certbot --apache -d cexbot.grokpot.io
apache2ctl configtest && systemctl reload apache2
```

---

### Task 9: systemd unit (replace stale file)

**Files:**

- Create: `deploy/cexbot.service`
- Replace: `/etc/systemd/system/cexbot.service`

```
[Service]
WorkingDirectory=/var/www/cexbot/backend
ExecStart=/usr/bin/node dist/index.js
EnvironmentFile=/var/www/cexbot/backend/.env.local
Restart=on-failure
RestartSec=5
```

`systemctl daemon-reload && systemctl enable --now cexbot`

Health: `curl -sS http://127.0.0.1:4200/health` → `status: ok`. Confirm it is **not** listening on `0.0.0.0:4200`.

---

### Task 10: Verify the live desk + API + chat

1. `https://cexbot.grokpot.io` → login → Trade desk (chart, book, ticket, AI column).
2. Portfolio / History / Settings load; studio/discord URLs are 404 or unlinked (do not add nav).
3. `curl -sS https://cexbot.grokpot.io/bot/v1/ -H "Authorization: Bearer $BOT_API_KEY"` returns the same catalog.
4. `GET /account`, `GET /waves?symbol=BTC_USDT&timeframe=1h`, `POST /ui` still work.
5. Trade AI: send a short prompt; stream events from GrokifyOS; a `cexbot-api.sh GET /account` tool call succeeds.
6. Phone-size viewport: desk is dense; pinch-zoom is acceptable (1:1, not a mobile rewrite).

---

### Task 11: Android inner app (WebView of the same origin)

**Files:**

- Modify: `android/app/src/main/java/io/grokify/os/apps/plugin/BuiltinPluginCatalog.kt`
- Modify: `android/app/src/main/java/io/grokify/os/ui/GrokifyAppRoot.kt` (`AppsPane` `when`, `appsNavShortTitle`)
- Create: `android/app/src/main/java/io/grokify/os/apps/CexBotPane.kt`
- Modify: `android/app/build.gradle.kts` — `versionCode` 276 / `versionName` `0.1.276`

Catalog:

```kotlin
const val CEXBOT = "cexbot"
// title "CexBot", subtitle Trade desk + GrokifyOS chat
// kind HostModule, accent Amber, icon Chart
// capabilities Trading, AI
```

`CexBotPane`:

- Top bar: back to Apps hub, title, refresh.
- `AndroidView` WebView:
  - `javaScriptEnabled`, DOM storage, third-party cookies, file access off.
  - `CookieManager.setAcceptCookie(true)` + `flush` on pause.
  - Load `https://cexbot.grokpot.io/` (login then desk).
  - `WebViewClient` stays on `cexbot.grokpot.io` + `wss`/`https` to `grokifyos.grokpot.io` (chat). Block other hosts.
  - Wide layout + pinch zoom (desk is desktop).
  - Hardware layer: follow existing Wi‑Fi map comment (default layer type; do not Compose-clip).
- Persist cookies so login survives process death.
- No native rewrite of the ticket/chart.

Wire `BuiltinPluginCatalog.CEXBOT` in `AppsPane` and short title `"CexBot"`.

Do **not** inject the phone Grok Assistant token. Interchangeable = same CexBot login + same mint user, not shared Grok Assistant history.

---

### Task 12: Build, publish phone OTA

```bash
cd /root/grokifyos/android
# bump already in Task 11
./scripts/publish.sh debug --channel phone --changelog "CexBot inner app (cexbot.grokpot.io WebView)"
```

Install via in-app updater. Open Apps → CexBot → login → Trade + AI column.

---

## Out of scope

- Native Compose desk / chart.
- Shared sessions with Grok Assistant.
- Studio, Discord, GIF, tokens, research, agent control plane.
- Second GrokifyOS worker or CexBot `/bridge` spawn.
- IP allowlist (breaks the phone).
- Changing `/bot/v1` catalog.

---

## Execution order

0 (done) → 1 DB → 2 checkout/env/schema → 3 bind/skip bridge → 4 mint PHP → 5 assistant routes → 6 chat.js → 7 skills + bridge.env → 8 Apache/cert → 9 systemd → 10 verify site → 11 Android → 12 publish.

Tasks 4–6 can overlap with 1–3 once the mint contract is fixed. Android starts only after Task 10 (the WebView needs a live origin).
