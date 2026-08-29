# GrokifyOS (open source)

**A mobile Android development kit** — self-hosted web control plane + native phone client — so you can **build custom versions of your own AI-powered phone** by working *through* the device, not just *on* it.

Think of it as **slipping the phone on for the phone**: you pair a real Android handset to *your* server, open hardware (camera, mic, GPS, Wi‑Fi, Bluetooth, notifications, media), stream agents via [Grok Build](https://grok.com), ship features as **built-in inner apps** in the host APK, and **publish APKs OTA** so the handset (and optional **Wear OS** app) updates itself.

| | |
|--|--|
| **What it is** | Self-hosted Android MDK + AI assistant stack |
| **Clients** | Web dashboard (browser) · phone host (`io.grokify.os`) · Wear OS app (same package id) · optional watch face |
| **Stack** | PHP 8.1+, MySQL/MariaDB, Node 18+ bridge, optional Android SDK |
| **Run mode** | Laptop / LAN **or** remote VPS with HTTPS |
| **Auth** | Username + password (web) · device Bearer tokens `gos_…` (phone + wear OTA) |
| **License** | [MIT](LICENSE) |

> **Not affiliated.** GrokifyOS is an independent, open-source project. It is **not** affiliated with, endorsed by, or sponsored by SpaceXAI/SpaceX/xAI, X (prev. Twitter), Grok, Grok Build, Mapbox, Spotify, or any related company. Product names above are trademarks of their respective owners; we only document how to use *your* accounts and APIs with *your* self-hosted stack.

---

## Why this exists

Most “AI phone” demos are a chat UI glued to an API. GrokifyOS is different:

1. **You own the host** — chat, devices, sessions, APKs, and secrets live on *your* machine or VPS.
2. **The phone is the runtime** — full permission model for real hardware: camera, microphone, location, nearby Wi‑Fi, Bluetooth, notifications, media session control.
3. **Inner apps are first-class** — Wi‑Fi / BT scanners, place notes, Spotify Live DJ, maps, and **Watch Deploy** ship as **built-in host modules** in the APK (no script sideloading).
4. **Wear is part of the loop** — standalone Grokify Wear (radial HUD + Carina voice) + optional WFF watch face; phone pushes first install via Watch Deploy; watch self-updates over LTE/Wi‑Fi.
5. **Grok Build is the builder** — agents run against *your* Grok Build login on the host; you (or another agent) edit the repo, rebuild, and **push OTA**.
6. **Closed loop** — change code → `publish.sh --channel …` → phone/watch sees a new `versionCode` → install update → keep iterating without a cable.

Endless surface area: new Kotlin host modules, vault keys, maps, scanners, media, geofences, wear HUD — all under one paired device token.

---

## What you get

| Piece | Role |
|-------|------|
| **Web dashboard** | Login, chat sessions, notes, device pairing, APK release store |
| **REST APIs** | Auth, devices, chat, models, live Grok Build usage, OTA |
| **Agent bridge** | Node WebSocket gateway for streaming agents |
| **Android host** | Compose client: Chat · Settings · **Apps** hub · permission cards · OTA |
| **Grokify Wear** | Standalone Wear OS app: radial telemetry HUD + Carina AI (not a phone UI clone) |
| **Watch face** | Separate WFF APK (`io.grokify.os.wear.face`) — always-on time + HR/steps complications |
| **API key vault** | On-device keys for maps, Spotify, xAI voice (phone → watch via Data Layer) |
| **Inner apps** | Built-in host modules in the phone APK (`BuiltinPluginCatalog`), including Watch Deploy |

Architecture overview: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**  
Full install (Ubuntu / Windows / macOS, TLS, bridge): **[docs/INSTALL.md](docs/INSTALL.md)**  
Android build / pair / OTA / Wear: **[android/README.md](android/README.md)**  
Agent release rules (three APK channels): **[AGENTS.md](AGENTS.md)**

---

## Quick start (humans)

```bash
git clone https://github.com/iBerry420/grokifyos.git
cd grokifyos
cp .env.example .env
# edit .env → MySQL credentials + secrets (see docs/INSTALL.md)

php scripts/install.php --admin=admin --password='your-long-password'

# local / LAN (phones on same Wi‑Fi: http://YOUR_LAN_IP:8787)
php -S 0.0.0.0:8787 scripts/dev-router.php
```

Open `http://127.0.0.1:8787` → sign in → **Devices** → mint a `gos_…` token → install the APK → paste the token.

For streaming agents + real usage numbers, log into Grok Build on the host and point the stack at that auth file (see [Grok Build](#grok-build-agents--usage) below).

---

## AI / Grok Build: clone and set up for a user

Paste this block (or point an agent at this section) when you want **Grok Build or another coding agent** to stand up GrokifyOS with minimal human steps.

### Agent checklist (idempotent)

```text
GOAL: Install GrokifyOS so a user can open the web UI and pair an Android app.

PREREQS (install if missing):
- PHP 8.1+ with pdo_mysql, curl, json, mbstring, session
- MySQL 8+ or MariaDB 10.5+ (dedicated database; do not share)
- Node.js 18+ (for agent bridge)
- git, openssl
- Optional: Android SDK + JDK 17 only if building APKs on this machine

STEPS:
1. Clone: git clone https://github.com/iBerry420/grokifyos.git && cd grokifyos
2. cp .env.example .env
3. Create MySQL database + user (utf8mb4). Put credentials in .env:
     GROKIFY_DB_HOST, GROKIFY_DB_PORT, GROKIFY_DB_NAME, GROKIFY_DB_USER, GROKIFY_DB_PASS
4. Generate secrets into .env:
     openssl rand -hex 32  → GROKIFY_WS_AUTH_SECRET
     openssl rand -hex 32  → GROKIFY_SECRETS_PEPPER
5. Set GROKIFY_SITE_URL:
     Local: http://127.0.0.1:8787  (or http://LAN_IP:8787 for phones)
     VPS:   https://user.domain
6. php scripts/install.php --admin=ADMIN_USER --password='LONG_PASSWORD'
7. Start web: php -S 0.0.0.0:8787 scripts/dev-router.php
8. Bridge (optional but recommended for streaming chat):
     cd bridge && npm ci && GROKIFY_BRIDGE_PORT=8876 node server.js
     Ensure GROKIFY_BRIDGE_URL / GROKIFY_BRIDGE_HEALTH / GROKIFY_WS_PATH match .env
9. Grok Build auth (for real agents + usage, not fake numbers):
     On host: grok login   (or grok login --device-code)
     ./scripts/sync-grok-auth.sh
     Verify: php scripts/check-grok-auth.php
10. NEVER commit: .env, storage/sessions/*, storage/apk/*, storage/grok-auth.json,
    android/local.properties, real API tokens, Mapbox pk. secrets, Spotify secrets.
11. Hand user:
     - Dashboard URL
     - Admin username
     - How to open Devices → create gos_… token
     - How to install phone APK (dashboard Download or android/scripts/publish.sh)
12. Android endpoints (if rebuilding APKs): set API_BASE / WS_URL / SITE_URL in
    android/app/build.gradle.kts and API_BASE in android/wear/build.gradle.kts
    to the same host as GROKIFY_SITE_URL.
13. Wear (optional, Galaxy Watch / Wear OS):
     - Three APK channels — never mix versionCodes: phone | wear | wear-face
     - After wear code change: bump android/wear/build.gradle.kts, then
         cd android && ./scripts/publish.sh debug --channel wear --changelog "notes"
     - First install: phone Apps → Watch Deploy (wireless ADB IP:port) → Update & install
     - Later updates: on-watch Update app (LTE/Wi‑Fi) or Watch Deploy again
     - Package id must match phone (io.grokify.os / .debug) for Data Layer key sync
     - Full wear setup: README § Grokify Wear + AGENTS.md + android/README.md

SUCCESS:
- GET /api/health.php reports ok
- Browser login works
- Device token can be created
- (If bridge + auth) chat streams and usage is non-empty or a clear auth error
- (If wear) channel=wear APK publishes; watch can install via Deploy or self-update

DO NOT:
- Invent usage stats when auth.json is missing
- Bake secrets into the repo or mapbox_access_token.xml (there is no compile-time token file)
- Share MySQL with unrelated apps
- Bump phone versionCode when only wear changed (channels are independent)
- Change wear applicationId away from the phone package (breaks Data Layer)
```

### One-shot shell sketch for agents

```bash
set -euo pipefail
git clone https://github.com/iBerry420/grokifyos.git
cd grokifyos
cp .env.example .env

# Fill DB_* and SITE_URL in .env, then:
echo "GROKIFY_WS_AUTH_SECRET=$(openssl rand -hex 32)" >> .env
echo "GROKIFY_SECRETS_PEPPER=$(openssl rand -hex 32)" >> .env

php scripts/install.php --admin=admin --password='CHANGE_ME_LONG'
php -S 0.0.0.0:8787 scripts/dev-router.php &
( cd bridge && npm ci && GROKIFY_BRIDGE_PORT=8876 node server.js ) &
```

VPS + TLS + systemd examples: `deploy/`. Deep steps: **[docs/INSTALL.md](docs/INSTALL.md)**.

---

## Inner apps (built-in)

Open the **phone** Android app → **Apps** tab. Every app is a **native host module** compiled into the phone APK (`BuiltinPluginCatalog`). There is no script sideload / remote WebView plugin path — new apps ship by editing Kotlin and publishing a new phone APK OTA.

| App | What it does | Hardware / services | Keys |
|-----|----------------|---------------------|------|
| **Wi‑Fi Scanner** | Scan nearby networks; GPS pins, distance, times seen; alerts (SSID/MAC watch, unseen, strong nearby); Mapbox map of hits | Nearby Wi‑Fi, Location | **Mapbox** `pk.…` for maps |
| **Bluetooth Tracker** | BLE + classic discovery; GPS pins, distance, times seen; watch/unseen/strong alerts; map | Bluetooth, Location, Notifications | **Mapbox** for maps |
| **Place Notes** | Pin notes to GPS spots; on enter: notify, open an app, or show an image; list + map + area monitoring | Location, Notifications | **Mapbox** for maps |
| **Companion** | Offline **VRM** stage (Three.js + three-vrm) + **xAI Voice Agent** + **bridge CLI movement agent** in tandem; soft-hang rest, lip-sync, AI-planned keyframes (`ai_move` / observe / look); import your own `.vrm`; text + voice with TTS fallback | Microphone, network; host bridge for motion planning | **SpaceXAI API key** (`spacexai_api_key`) for Voice Agent; device token + bridge for movement |
| **Grok Assistant** | Floating mini overlay / system assist entry; wake listen; Voice Agent tools for the main chat surface | Microphone, notifications, network | **SpaceXAI API key** for voice |
| **Spotify** | Lockscreen / media controls; **Live AI DJ** booth (banter, queue chat); research/build/edit playlists via host Grok Build; optional Grok Voice TTS | Notifications, Media session, mic (voice), network | **Spotify Client ID** (+ optional secret); **SpaceXAI API key** for Grok Voice (device TTS works without it) |
| **SpaceXAI API Usage Analyzer** | Prepaid credit balance, period spend, soft/hard limits, 7‑day usage by model, balance history | Network | **SpaceXAI Management key** vault id `spacexai_management_key` (billing read on [management-api.x.ai](https://management-api.x.ai)) |
| **Watch Deploy** | Dev tooling: download OTA `channel=wear` APK and install on a Galaxy Watch over **wireless ADB** (bundled `libadb.so`). One-tap **Update & install**. Data tab is a stub for future wear→phone payloads. | Network (phone + watch on same Wi‑Fi or phone hotspot); Wireless debugging on watch | Phone device token (for OTA download); no third-party vault key |
| **CexBot** | Live trade desk WebView at cexbot.grokpot.io (same origin as the browser). Tokenized MCP connector on Settings (`/mcp/cex_mcp_…`) | Network | Desk login (not a vault key) |
| **Grok Bot** | Full **gbot** / gbotd control plane: list/create/switch bots, chat + tail, pending approvals (local-tool / widget / secret / handback), computer VNC, host settings, MCP connect/refresh, raw gateway | Network (device token → `/api/gbot.php` → loopback gbotd) | Phone device token; server `GROKIFY_GBOTD_URL` + `GROKIFY_GBOTD_TOKEN` |
| **Discord** | Avalynn Discord manager: markdown feed, local avatars/media, bots/selfbots only on guilds they are live members of, per-bot channel visibility, users/guilds/media/audits with guild filters, emoji, role pickers, captchas | Network (device token → `/api/discord.php`; lists hit MariaDB, writes proxy to avalynn-discord `:4201`) | Phone device token; Avalynn `discord_backend_password` (or `GROKIFY_DISCORD_PASSWORD`) |
| **LYRE** | Native storyboard editor (Odysseus + phone projects): rails, Imagine stills/video, leftover track, server stitch/pop. Tokenized MCP director `https://grokifyos.grokpot.io/mcp/lyre_mcp_…` (hashed on disk; copy from LYRE project picker) | Network (device Bearer → `/api/lyre.php`); Camera, Media | Phone device token; optional **SpaceXAI API key** (`spacexai_api_key`) for backup Imagine |

Capabilities are gated by Android permissions (Settings → Permissions, or in-chat `[[permission_request:…]]` cards). Keys live in **Settings → API key vault** on the device — never in git.

> **Grokify Wear is not an inner app.** It is a **separate Wear OS APK** (`:wear` module). Watch Deploy is the phone-side installer for that APK. See [Grokify Wear](#grokify-wear-os) below.

---

## Grokify Wear OS

Standalone AI assistant + radial telemetry HUD for Wear OS (Galaxy Watch and similar). **Not** a clone of the phone host UI.

| Piece | Module | Package / channel | Role |
|-------|--------|-------------------|------|
| **Wear app** | `android/wear` (`:wear`) | `applicationId` = **`io.grokify.os`** (same as phone + same debug suffix) · OTA **`channel=wear`** | Carina chat/voice, radial HUD (time, HR, steps, compass, location, weather, battery, media/notifications) |
| **Watch face** | `android/wear-face` (`:wear-face`) | `io.grokify.os.wear.face` · OTA **`channel=wear-face`** | Always-on WFF face (time + system complications). Resource-only (`hasCode=false`). Must stay a **separate** APK from the wear app (Wear OS / Play rule). |
| **Watch Deploy** | phone inner app | phone host only | First install / recovery install of the wear app via wireless ADB |

**Why shared package id:** Wear Data Layer (MessageClient / DataClient) only syncs between identical `applicationId` + signing cert. Kotlin `namespace` on wear stays `io.grokify.os.wear`.

**Independent version streams:** phone, wear, and wear-face each have their own `versionCode` / `versionName`. Never reuse or “merge” them when publishing.

### User setup (first time)

1. **Phone** — GrokifyOS paired with a `gos_…` device token; SpaceXAI API key in vault if you want Carina voice on the watch.
2. **Watch** — Developer options → **Wireless debugging** → note **IP:port** (Pairing port is different from the connect port; use the active connect line).
3. **Same network** — phone and watch on the same Wi‑Fi, **or** turn on the **phone hotspot** and join the watch (works off home Wi‑Fi / travel).
4. **Phone** → **Apps → Watch Deploy** → set Connect IP:port → **Update & install** (one tap: check OTA + download wear APK + `adb install`).
5. **Allow install** prompts on the watch if asked.
6. Open Grokify on the watch. With phone nearby and both apps signed the same, **Data Layer** pushes the SpaceXAI key and device token automatically. Manual paste on the watch is the fallback.
7. **Later updates (LTE / Wi‑Fi):** on the watch open Carina / settings → **Update app** (one-step check → download → install). Needs device token on the watch + “install unknown apps” once.
8. **Watch face (optional):** publish `--channel wear-face`, then `adb install -r` the face APK (Watch Deploy targets `channel=wear` for the app today).

### Off Wi‑Fi / remote

| Goal | How |
|------|-----|
| First install or recovery | Phone **hotspot** + watch joins → Watch Deploy (wireless ADB needs a shared IP network; **not** Bluetooth alone) |
| Already on wear ≥ self-update | Watch **Update app** over LTE or any Wi‑Fi — no phone push |
| Computer nearby | USB/wireless ADB from a laptop: `adb install -r wear-debug.apk` |

Bluetooth Data Layer is for **keys/tokens**, not multi‑MB APK install.

### Agent / AI checklist (Wear)

```text
GOAL: Ship or set up Grokify Wear for a user who already has the phone host.

PREREQS:
- Phone GrokifyOS installed, device token active, same debug/release signing as wear builds
- Android SDK + JDK 17 if building APKs
- Wear OS device with Wireless debugging (first install) or LTE/Wi‑Fi (self-update)

STEPS:
1. Confirm three channels: phone (:app), wear (:wear), wear-face (:wear-face). Do not mix.
2. Wear applicationId MUST remain io.grokify.os (+ .debug suffix in debug). Never rename for Data Layer.
3. Set API_BASE in android/wear/build.gradle.kts to the user's host /api URL.
4. Bump versionCode/versionName only in the module you changed.
5. Publish:
     cd android && ./scripts/publish.sh debug --channel wear --changelog "short notes"
     # face: --channel wear-face
     # phone: --channel phone  (or omit --channel)
6. Tell user first install path: Apps → Watch Deploy → IP:port → Update & install.
   No home Wi‑Fi → phone hotspot + watch joins that network.
7. After first install: phone open pushes API key + device token; watch Update app uses channel=wear.
8. If install hangs in Watch Deploy: Cancel, soft reconnect, retry; hard reconnect if port stale after OTA.
9. Do not wait for the user to ask — after shippable wear/phone/face code changes, bump + publish that channel (see AGENTS.md).

SUCCESS:
- Watch app launches; key source shows phone sync or manual key works
- Update app reports up to date or installs newer wear build
- Data Layer works only when phone and wear package ids match

DO NOT:
- Bundle wear app code into the watch face APK
- Use phone versionCode for wear releases
- Promise Bluetooth-only APK install (unsupported; use hotspot + ADB or LTE self-update)
```

Build helpers and channel flags: **[android/README.md](android/README.md)** · release defaults for agents: **[AGENTS.md](AGENTS.md)**.

---

## Keys & tokens — how to get them

All third-party keys are **optional until you use the feature**. Store them **on the phone** in Settings (API key vault / Mapbox / Spotify cards). The server device token (`gos_…`) is separate: it only authenticates the app to *your* GrokifyOS host.

### 1. GrokifyOS device token (`gos_…`)

| | |
|--|--|
| **Where** | Web dashboard → **Devices** → create |
| **Used for** | Phone API + WebSocket auth; wear OTA (`update.php` / `apk-download.php?channel=wear`) |
| **Paste** | First-run / Settings on the phone; watch receives it via Data Layer or manual paste |

### 2. Grok Build (server-side — agents + usage)

| | |
|--|--|
| **Where** | Host machine: [Grok / Grok Build CLI](https://grok.com) → `grok login` |
| **Wire-up** | `GROKIFY_GROK_AUTH_JSON=…` then `./scripts/sync-grok-auth.sh` → `storage/grok-auth.json` |
| **Used for** | Streaming agents, chat, playlist research, live usage chip |
| **Not** | Not the same as the on-device SpaceXAI API key |

```bash
grok login          # or: grok login --device-code
./scripts/sync-grok-auth.sh
php scripts/check-grok-auth.php
```

Missing auth → APIs return a **clear error** (no invented usage).

### 3. Mapbox public token (`pk.…`)

| | |
|--|--|
| **Where** | [mapbox.com](https://www.mapbox.com/) → Account → Access tokens → create a **public** token |
| **Paste** | Android **Settings → Mapbox** (or vault id `mapbox_access_token`) |
| **Used for** | Maps in Wi‑Fi Scanner, Bluetooth Tracker, Place Notes |
| **Note** | Vault-only. There is **no** baked-in `mapbox_access_token.xml` fallback. |

### 4. Spotify (Controller / Live DJ / playlists)

| | |
|--|--|
| **Where** | [developer.spotify.com](https://developer.spotify.com/) → Dashboard → Create app |
| **Client ID** | Paste in Settings / vault (`spotify_client_id`) |
| **Client secret** | Optional (PKCE works with Client ID alone); vault `spotify_client_secret` |
| **Redirect URI** | Must match what your app/build expects (default documented in-app; sample hosts use `https://…/spotify-callback.php`) |
| **OAuth tokens** | Access/refresh are stored **internally** after login — not typed by hand |

### 5. SpaceXAI keys (Voice TTS + Usage Analyzer)

| | Inference API key | Management key |
|--|--|--|
| **Where** | [console.x.ai](https://console.x.ai/) → **API Keys** | [console.x.ai](https://console.x.ai/) → **Management Keys** (billing read) |
| **Vault id** | `spacexai_api_key` (legacy `xai_api_key` auto-migrated) | `spacexai_management_key` |
| **Settings** | SpaceXAI API key card | SpaceXAI Management key card |
| **Used for** | Grok Voice TTS (`api.x.ai`) for Live DJ banter | Usage Analyzer prepaid balance / spend / limits (`management-api.x.ai`) |
| **Not used for** | Playlist research / main chat — those use **host Grok Build** + device token | same |

> These are **different product types**. Keep both filled if you use Voice and Usage Analyzer. Usage Analyzer prefers the Management key field; it may fall back to the inference vault only if Management is empty (pre-split installs).

---

## Develop → rebuild → OTA

Closed loop for custom forks — **three APK channels** (phone, wear, wear-face). Each keeps its own `versionCode`.

```bash
cd android

# Phone host (default channel)
# bump versionCode / versionName in android/app/build.gradle.kts
./scripts/publish.sh debug --changelog "What changed"
# same: ./scripts/publish.sh debug --channel phone --changelog "…"

# Wear app
# bump android/wear/build.gradle.kts
./scripts/publish.sh debug --channel wear --changelog "Wear notes"

# Watch face
# bump android/wear-face/build.gradle.kts
./scripts/publish.sh debug --channel wear-face --changelog "Face notes"
```

That builds the APK, registers it with your host’s APK store, and makes it downloadable for paired devices.

| Client | Check | Download | Install path |
|--------|--------|----------|--------------|
| **Phone** | `GET /api/update.php?version_code=N` (+ default channel phone) | `apk-download.php` + device token | In-app OTA installer |
| **Wear** | `update.php?version_code=N&channel=wear` | `apk-download.php?channel=wear` | On-watch **Update app**, or phone **Watch Deploy** (ADB) |
| **Wear face** | `channel=wear-face` | same pattern | Wireless ADB / install-device for now |

`versionCode` must **increase** on each ship **for that channel**.

Helpers: `android/scripts/build.sh`, `publish.sh`, `install-device.sh` (wireless ADB). Details: **[android/README.md](android/README.md)** · agent always-publish rules: **[AGENTS.md](AGENTS.md)**.

Point Grok Build at this repo on the **same host** (or a remote with deploy access) so agents can edit Kotlin/PHP, run `publish.sh`, and the handset/watch pick up the new build without USB.

---

## Repository layout

```text
web/           PHP app (public UI, API, includes, assets)
schema/        SQL schema (greenfield install)
bridge/        Node WebSocket agent gateway
android/       Phone host (:app) + Wear (:wear) + WFF face (:wear-face)
  app/         Phone Kotlin + Compose + inner apps (incl. Watch Deploy)
  wear/        Grokify Wear — radial HUD + Carina
  wear-face/   Watch Face Format package
  scripts/     build.sh, publish.sh (multi-channel), install-device.sh
scripts/       install.php, dev router, grok-auth helpers
deploy/        Apache vhost + systemd unit examples
docs/          install + architecture
AGENTS.md      Agent release notes (phone / wear / wear-face channels)
storage/       sessions, bridge runtime, APKs (gitignored contents)
uploads/       chat media (gitignored)
```

---

## Auth model

| Client | Method |
|--------|--------|
| Browser | Username + password → session cookie `__grokifyos_sid` |
| Android | Device Bearer `gos_…` minted in the web UI after login |
| Bridge WS | Shared `GROKIFY_WS_AUTH_SECRET` + device/session tokens as designed |

Password-only admin auth keeps self-hosting simple. Optional OAuth can be added later as config — not required to run.

---

## Grok Build (agents + usage)

```env
GROKIFY_GROK_AUTH_JSON=/path/to/auth.json   # from `grok login`
```

Prefer the synced copy for PHP-FPM readability:

```bash
./scripts/sync-grok-auth.sh    # → storage/grok-auth.json + .env update
```

Usage endpoints call billing with **your** credentials only. No phone-home to a central GrokifyOS SaaS.

---

## Security

- Never commit `.env`, `storage/sessions/*`, `storage/apk/*`, `storage/grok-auth.json`, or real vault keys
- Use HTTPS on any host reachable from the public internet
- Strong DB password, `GROKIFY_WS_AUTH_SECRET`, and `GROKIFY_SECRETS_PEPPER`
- Keep `storage/` writable only by the web/bridge user
- Treat Mapbox `pk.` / Spotify / xAI keys as secrets even when “public” client tokens

---

## Contributing

Issues and PRs welcome. Prefer small, focused changes. Keep secrets out of the tree. If you add an **inner app**, implement it as a built-in host module (`BuiltinPluginCatalog` + Compose pane) and document its capabilities and required vault key ids in this README. If you change **Wear** or the **watch face**, keep package/channel rules in **AGENTS.md**, bump the correct module only, and document user-facing setup under [Grokify Wear OS](#grokify-wear-os).

---

## Changelog

Android host versions (`versionName` / `versionCode` in `android/app/build.gradle.kts`). Wear and watch-face channels keep **independent** version streams (`android/wear`, `android/wear-face`). Newest first. OTA notes on the phone/watch come from `publish.sh --changelog`; this section is the longer human history.

### 0.1.295 — Grok Bot connectors, full Bots pane, chat flicker

**Phone host `0.1.295` (versionCode 295)** — Grok Bot inner app.

- gbotd can add and use MCP connectors when instructed (`add-connector` workflow, `refreshMcp`, box MCP servers, listener connect URLs). Details shows connectors, listeners, and channels; **Add connector** runs the workflow in chat.
- Chat no longer flips between an older tail and the current one: stale polls are dropped, earlier history is kept, and a failed agents list does not wipe the bot list.
- **Bots** (and the other toolbar sheets) are full panes instead of a short popup. Android back / toolbar back pops Details → pane → chat, then leaves the inner app.

### 0.1.294 — Discord media pane (no video crash)

**Phone host `0.1.294` (versionCode 294)** — Discord inner app.

- Videos no longer play inside scrolling lists (that crashed the host). Tap a clip to open a full-screen player with ExoPlayer, HTTP range, and an error state instead of a native crash.
- Media pane shows origin meta: user, Discord id, guild, channel, timestamp, size, and ids. Images and GIFs pinch-zoom and pan (double-tap to reset).

### 0.1.293 — Grok Bot automation schedule + prompt edit

**Phone host `0.1.293` (versionCode 293)** — Grok Bot inner app.

- Automations sheet can set frequency (5m–daily) and hours (**24 hours**, **8–21**, **9–17**, or a custom from/to). Cron is saved through gbotd `updateAgentAutomation`.
- Prompt: **Copy** and **Edit**. Hours use the bot timezone (Setup override).

### 0.1.292 — Discord user profiles

**Phone host `0.1.292` (versionCode 292)** — Discord inner app.

- Tap a username or avatar in Feed, Users, or Audits to open a profile sheet.
- Profile shows active guilds and channels, username / display-name / avatar history, message count, a semantic tag chart when tags exist, and paginated messages with markdown and media.

### 0.1.291 — Discord audit deleted media

**Phone host `0.1.291` (versionCode 291)** — Discord inner app.

- Delete/edit audit cards show cached images, GIFs, video, and files from the original message (including media-only deletes that had no text).
- Snowflake Discord attachment ids are mapped onto local `MessageAttachment` rows so the phone loads our file endpoint instead of an expired CDN URL.

### 0.1.290 — Discord audit event content

**Phone host `0.1.290` (versionCode 290)** — Discord inner app.

- Audits now include stored before/after payloads: deleted message text, edit diffs, role names, and every historical avatar / username / display-name / nickname change (not capped at 2).
- Avatar events show the archived before and after images from `audit-avatars`; message events keep local attachments.

### 0.1.289 — Discord audits feed + users guild filter

**Phone host `0.1.289` (versionCode 289)** — Discord inner app.

- Audits were empty because `?action=audits` was applied as a GuildAuditEvent action filter. Event types now use `eventAction`.
- Users has the same searchable guild filter as Media/Audits (recent members, or username/id search inside that guild).

### 0.1.288 — Discord live guild membership + capture-time media

**Phone host `0.1.288` (versionCode 288)** — Discord inner app.

- Guild bot lists come from the live Discord clients (and REST when a bot is offline), not leftover audit/settings rows. GROK COMMUNITY / GREYCORD / Suno are LYNX-only; Cat's Cafe is AvalynnAI-only; AvalynnAI NG (offline) is on no guilds.
- Attachments are downloaded when messages are captured (`DISCORD_ATTACHMENT_DIR`) and served from GrokifyOS. The phone never plays `cdn.discordapp.com` URLs.

### 0.1.287 — Discord guild membership, media/audit guild filters

**Phone host `0.1.287` (versionCode 287)** — Discord inner app.

- Guilds and channel toggles only list bots that actually have data in that guild (LYNX / AvalynnAI / NG are no longer copied onto every server).
- Media and Audits have their own searchable guild filters (independent of the Feed guild). Media grid is thumbnails only; tap to play GIFs/video/audio. Expired Discord CDN links 404 immediately instead of hanging.
- Guild-filtered audits use a `(guildId, createdAt)` index instead of scanning millions of rows.

### 0.1.286 — Discord avatars, media, and list speed

**Phone host `0.1.286` (versionCode 286)** — Discord inner app.

- Avatars and attachments are served from the GrokifyOS API (`/api/discord.php?action=avatar|file`) with on-demand Discord CDN cache. Bots already store avatars on ingest (`uploads/audit-avatars`); missing files are cached on first view. Attachments that were never saved locally are cached into `uploads/discord-files` when opened.
- Feed, users, guilds, channels, audits, and media lists query MariaDB directly (no Node `COUNT(*)` over millions of rows). Users/guilds/audits have filter + sort chips. Expanding a guild loads **that** guild’s channels with a per-bot visibility toggle.
- Feed renders Discord markdown (bold/italic/code plus mentions/emoji/spoilers) and plays GIFs, video, audio, and other attachments.

### 0.1.285 — Discord inner app

**Phone host `0.1.285` (versionCode 285)** — Discord manager inner app.

- New **Apps → Discord** host module. Same void/cyan theme as the rest of the phone. Tabs: Feed, Bots, Guilds, Users, Media, Roles, Captchas, Emoji, Audits.
- Phone talks to `/api/discord.php` (device Bearer). PHP allowlists paths and proxies to local **avalynn-discord** (`127.0.0.1:4201`). Captured emoji files are served from Avalynn `uploads/emojis`. Avalynn.ai itself is unchanged.

### 0.1.284 — Grok Bot automations sheet + stale-alert fix

**Phone host `0.1.284` (versionCode 284)** — Grok Bot inner app.

- Background alerts no longer quote the newest chat line unless that entry appeared during the run. Start/idle notices stay on status, not an old preview.
- Toolbar **Autos** chip next to **Bots**: schedule, last/next run, prompt, run history, on/off, run now.

### 0.1.282 — Grok Bot multiline + automation alerts

**Phone host `0.1.282` (versionCode 282)** — Grok Bot inner app.

- Composer: **Enter inserts a newline**. Send is the cyan button only.
- Background alerts (on by default, Setup toggle): notify when an automation starts, finishes, or needs approval. Tap opens **Apps → Grok Bot**. Snapshot now includes slim `listAllAutomations` (no prompts).

### 0.1.281 — Grok Bot chat UI, inbox, VNC

**Phone host `0.1.281` (versionCode 281)** — Grok Bot inner app polish.

- Chat-first layout matching System Chat (YOU/bot bubbles, markdown, timestamps, composer, stick-to-bottom). Bots / Inbox / Computer / Setup are toolbar sheets.
- Inbox no longer shuffles: pending cards are keyed by agent+entry, enrichment does not cross bots that share `t1s3`-style ids, and poll merges keep order plus already-loaded prompts.
- Computer (noVNC) stamps `network_token` on every same-host asset request so CSS/JS/SVG stop 404ing. Autoconnect, scale, on-screen keyboard.

### 0.1.280 — Grok Bot inner app (gbot)

**Phone host `0.1.280` (versionCode 280)** — Grok Bot inner app.

- New **Apps → Grok Bot** host module. The phone talks to `/api/gbot.php` (device Bearer); PHP proxies to local **gbotd** (loopback HTTP or UDS). Same surface as the `gbot` CLI: bots, send/tail, pending approve/deny/answer/dismiss/secret, box ensure/VNC/handback, settings, MCP, transcript search, workflows/skills, `gbot login`, raw gateway.
- Server: `GROKIFY_GBOTD_URL` + `GROKIFY_GBOTD_TOKEN` (see `.env.example` and `deploy/gbotd-http.conf.example`). Nuclear gbotd methods stay blocked in PHP.

### 0.1.275 — Live DJ: skip no longer waits on queue refill

**Phone host `0.1.275` (versionCode 275)** — Spotify Live DJ inner app.

- Skip with a short UP NEXT (e.g. 3 songs) held the **transition** lock while the booth crawled Spotify + asked Grok to rank more cuts. Further skips were ignored until that fill finished.
- Skip / hard-skip / queue jump now play the next already-queued cut first. Refill runs **after** the lock drops. Status may show **filling** — Skip still works.
- Empty list is the only case that waits on a fill, and a double-tap abort can cancel that wait.

### 0.1.274 — Live DJ: research angles share one lottery

**Phone host `0.1.274` (versionCode 274)** — Spotify Live DJ inner app.

- Custom research (and custom banter bits) were **forced every talk**, so toggling more built-in angles never rotated the pack.
- Enabled research angles — built-in + custom — now sit in **one lottery**. Each talk draws **1–3**, preferring ones you have not heard recently. Off stays out.
- Banter bits: track handoff stays on; other bits (including custom) rotate. A custom news angle is required on-air **only when it was drawn this cycle**.
- Research JSON fields follow the drawn pack (lyrics-only no longer also dumps album/artist facts).
- Booth chat, queue-rank, and behavior still use the saved templates as written (one active behavior; no lottery there).

### 0.1.273 — Live DJ: custom banter no longer replaced by the canned fallback

**Phone host `0.1.273` (versionCode 273)** — Spotify Live DJ inner app + chat/bridge history cap.

- A real AI line was being thrown out: anything over **400 characters**, or a sentence with everyday radio/news wording (`let me`, `tool`, `verifying that`), counted as “process talk” and the booth spoke the canned “finishing up with some X / up next Y” instead.
- Spoken lines may now be up to **900 characters**. Only true research-process leaks are stripped.
- Research JSON is parsed even when the model thinks out loud first. A custom angle with no `custom_notes`/`news` is retried once. Timeout partials are used.
- Live handoff waits up to **90s** when a custom angle is still researching, and will research at the mic if the bake missed — it no longer skips straight to the local fallback.
- **Chat / bridge:** long sessions no longer pass the whole history as `grok -p` (that hit Linux `MAX_ARG_STRLEN` / `E2BIG` and killed the worker). Android sends a capped window (last **20** turns, **80k** chars, thinking stripped). The bridge clips prompt + history and keeps the argv `-p` under the kernel limit.

### 0.1.272 — Live DJ: long banter is not cut off

**Phone host `0.1.272` (versionCode 272)** — Spotify Live DJ inner app.

- Banter length no longer clamps to **18s**. Word-count estimate, baked TTS duration, and MP3 size are combined so a long custom-angle clip keeps its real length.
- Talk-over starts with enough outro for that clip (up to 90s). If the line will outlast the track, Spotify is paused so autoplay cannot talk over the last sentences.
- TTS wait follows the clip (and keeps waiting while audio is still playing) instead of killing the player at 90s.

### 0.1.270 — Live DJ: bake banter two songs ahead

**Phone host `0.1.270` (versionCode 270)** — Spotify Live DJ inner app.

- Research + TTS start when talk is **two songs away** and the clip is **held** until that handoff.
- Silent skip/advance of the earlier cut keeps the bake. Skip on the song *before* banter plays the ready clip immediately, then the next track as usual.
- Double-tap / hard skip still dumps the hold and jumps.

### 0.1.269 — Live DJ: Banter bits + custom/edited templates actually go on-air

**Phone host `0.1.269` (versionCode 269)** — Spotify Live DJ inner app.

- Spoken lines were hardcoded to “close the last song, name the next one,” so Banter system edits and custom templates never landed on-air.
- Each cycle now builds **required talking points**: enabled custom **Banter bits** always fire; built-ins rotate; custom research angles (e.g. USA news) are required beats, not optional color.
- Settings → Prompt templates → **Banter bits** to enable/edit, or **+ Add banter bit**. The Banter system template is still the rules layer.

### 0.1.268 — Live DJ: BLOCKS tab shows song name + artists

**Phone host `0.1.268` (versionCode 268)** — Spotify Live DJ inner app.

- BLOCKS was storing Spotify URIs only, so the list showed the last 22 characters of each URI (the track id).
- Rows now show **song name — artist(s)** (and real names in the artist list). Opening the tab backfills titles from DJ chat / queue, then Spotify, and saves them. New dislikes store title and credits up front.

### 0.1.267 — Live DJ: custom research, hard skip, dislike blocks

**Phone host `0.1.267` (versionCode 267)** — Spotify Live DJ inner app.

- **Custom research angles always fire** when enabled (they are no longer a 1-in-7 lottery). Findings land in `custom_notes` / `news` and banter is required to use them instead of glossing over with a song fact.
- **Double-tap skip** (booth, queue SKIP+TALK, headset next) cuts upcoming research / banter and plays the next cut now.
- **Dislikes stick**: remasters and `Artist & Guest` vs `Artist, Guest` match the same block; counts + last-seen timestamps; tired cooldown is title-aware. The queue-system “Disliked” chip can no longer be turned off (that used to re-queue blocked artists).
- New Live DJ tab **BLOCKS** to list / clear songs, artists, and 14-day cooldowns.

### 0.1.266 — grok-4.6 default + per-model reasoning effort

**Phone host `0.1.266` (versionCode 266)** — Settings → **MODEL** now has a **REASONING** row. Options follow the selected Grok Build model so `grok-4.5` never receives `xhigh` (that combination is a CLI error).

- **Default headless agents** (web system chat, Android Chat, plugin / Live DJ host AI): `-m grok-4.6` and `--reasoning-effort xhigh`.
- **Per-model efforts**: `grok-4.6` → `low` / `medium` / `high` / `xhigh`; `grok-4.5` (and older / unknown) → `low` / `medium` / `high`. Last pick is remembered per model.
- **Bridge clamp**: WebSocket `prompt` accepts `reasoning_effort`; unsupported values are snapped before spawn (`GROKIFY_GROK_DEFAULT_MODEL` / `GROKIFY_REASONING_EFFORT` still override the preferred default).
- **Web + Android pickers** read `reasoning_efforts` / `default_reasoning_effort` from `/models`. Switching to 4.5 while on `xhigh` snaps to `high`.
- Voice Agent realtime path is unchanged (`none` / deep-think `high`).

### 0.1.251–0.1.265 — Wear OS closed loop (phone host)

**Phone host `0.1.265` (versionCode 265)** — multi-channel OTA, Watch Deploy, Data Layer key sync.

- **Three OTA channels** (never mix): `phone` (`:app`), `wear` (`:wear`), `wear-face` (`:wear-face`). Publish with `./scripts/publish.sh debug --channel <name> --changelog "…"`. Schema `003_apk_channel.sql` + `gos_latest_apk($channel)` / `update.php?channel=`.
- **Watch Deploy** (Apps hub): wireless ADB to Galaxy Watch via bundled arm64 `libadb.so`. Pair / Connect IP:port, **Update & install** (one tap: check + download wear APK + install), soft-then-hard reconnect after OTA so open no longer hangs on a stale port (`0.1.264`).
- **Data Layer wear bridge**: `WearApiKeySync` + `WearApiKeyListenerService` push Grok API key and device token to the watch when package ids match (`io.grokify.os` / debug twin). ADB key inject remains a fallback.
- **Wear OTA auth**: phone device token can authorize wear update checks so the watch can self-update over LTE after the first Deploy install.
- **Build helpers**: `./scripts/build.sh debug phone|wear|wear-face|all`.

**Wear app `0.1.8` (versionCode 8)** — standalone AI assistant + radial telemetry HUD (not a phone UI clone).

- Radial breathing HUD: time, HR, steps, compass, location, weather (Open-Meteo), battery, media/messages (notification listener).
- Carina chat + on-watch **Update app** (check → download → install) once `0.1.8+` is installed.
- `applicationId` aligned with phone for Wear Data Layer; Kotlin `namespace` stays `io.grokify.os.wear`.

**Watch face `0.1.1` (versionCode 1)** — Watch Face Format package (`io.grokify.os.wear.face`), resource-only (`hasCode=false`). Separate APK from `:wear` (Play / Wear OS requirement). Always-on WFF twin (time + system complications for HR/steps); interactive HUD stays in the wear app.

Also in this window (Companion / stage polish shipped with the same tree): companion stage, amplitude, and system-chat updates used by phone host builds through `0.1.265`.

### 0.1.250 — Grok logout + re-login (switch accounts)

- **Settings → Weekly usage → “Log out & get login link”** (Android + web): clears host Grok Build OAuth, starts a fresh OIDC device-code login, and opens the xAI verification URL so you can approve a different account.
- **Bridge** `POST /grok-login/logout`: wipes `~/.grok/auth.json` + `storage/grok-auth.json`, cancels any in-flight device login, returns `verification_uri_complete` (same shape as start). Gateway proxies the new path; PHP `admin-system-chat-login.php` accepts `{ "logout": true }`.
- One host Grok session at a time — logout then re-login is the intentional multi-account path.

Also in this ship window (0.1.234–0.1.249 OTA range, same tree):

- **Companion body self-collision**: hips-local spheres + torso capsule + clothes ellipsoid push wrists/elbows out of the mesh; hand–hand soft separation; debug wireframe colliders.
- **Companion World in-app**: playable maps (`proto_arena`, `kenney_plaza`, `courtyard`, `mini_dungeon`) bundled under `assets/companion/world/`; Settings → **Open Companion World**; stick / jump / MAP on stage. Source under `godot/companion-world` + plan docs.
- **Android Auto Live DJ**: `MediaBrowserService` music source when Live DJ is on air; Spotify pane master switch; car meta-data on the application.

### 0.1.233 — Main chat image attach + Companion VRMA / movement agent

- **Main chat media button**: bottom-left attach control in the composer (Android Chat). Photo picker (up to 4 images), client JPEG compress, optional media-cache upload for durable history thumbs, and vision analysis via the bridge.
- **Bridge multimodal prompts**: WebSocket `prompt` accepts optional `images` (`data` base64 + `mimeType`). Spawns Grok Build with `--prompt-file` ACP content blocks (text + image) so ARG_MAX stays safe; image-only turns get a default analyze prompt; temp prompt files cleaned on agent finalize.
- **Android send path**: `BridgeClient.sendPrompt(…, images)`, `GrokifyApi.createMessage` metadata for user photos, `prepareChatImage` + user-bubble thumbnails.
- **Companion movement agent** (`CompanionMovementAgent`): voice tools `body_pose` / `ai_move` / `body_gesture` prefer joint-XYZ + **bundled VRMA** templates rebuilt for the loaded avatar; bridge CLI plans only novel poses. Keyword fallback if the voice model skips tools.
- **Bundled VRMA pack**: portable `.vrma` clips under `assets/companion/animations/` (wave/clap/think/jump/emotions, MIT). Stage gains `three-vrm-animation`, `playVrma` / mixer; soft-hang IK suspends while a clip owns the skeleton.
- **Companion stage / body tools**: richer observe + gesture catalog, side inference for wave/point, tests for body tools + movement agent. Inner-app table documents bridge CLI motion planning.

### Bridge — default reasoning effort `high`

- Headless Grok agents (web system chat, Android Chat, plugin / Live DJ host AI) spawn with `--reasoning-effort high`. Override with `GROKIFY_REASONING_EFFORT`. (`xhigh` was tried early but is not released yet.) Voice Agent realtime path is unchanged (`none` / deep-think `high`). See **0.1.266** for the later grok-4.6 / per-model effort change.

### 0.1.212 — Companion: soft hang, calmer head, tighter lip-sync, first-reply audio

- **Rest pose**: soft arm hang (~72° from T), lower hand rests, less idle flare — no permanent Y-pose.
- **Head**: stopped stacking bone pitch + `lookAt` (that read as a constant nod); gaze is eyes-first; rare soft nod only while listening.
- **Lip-sync**: faster envelope + stronger mid-range open; mouth level published from PCM *write* path (what leaves the speaker), not only socket receive time.
- **First spoken reply**: flush/re-prime `AudioTrack` per response + short silence pad so cold OEM tracks do not drop the first PCM; session instructions force **spoken audio first** (body tools optional garnish, never instead of speech).
- **Body control**: voice agent tools (`ai_move`, `body_gesture`, `set_hands`, `look_at`, `reset_body`) hand off to **`CompanionMovementAgent`**, which plans wrist/look keyframes via the host **bridge CLI** (Grok Build) from live measured joints — works for any VRM. Stage applies frames with two-bone IK + soft hang.
- **Audit fixes (0.1.206–0.1.211)**: atomic VRM import (staging dir, never wipe previous on failed copy); synchronized chat history append; text send stops active voice session; status toast auto-dismiss; preserve avatar state across model reload; normalize user vs bundled load source; `stopInternal` publishes final Idle before clearing listener (no stuck Connecting); Thinking keeps mic open until `response.created` (half-duplex after commit) and **never** clears `input_audio_buffer` on `speech_stopped` / mute into Thinking; thinking nudge + empty-turn recovery before hard timeout; loudspeaker / media usage priming.

### 0.1.203–0.1.205 — Companion: poses, orbit camera, voice connect

- **0.1.205**: stop wiping user audio on `speech_stopped` / mute-into-Thinking (same class of bug as Grok Assistant); wait for `session.created` before `session.update`; retry WS open with API key / without stale `conversation_id`.
- **0.1.204**: persist orbit camera framing across restarts (double-tap clears); align Voice Agent handshake with xAI docs; hard-fail Connecting on WS/config errors; persist `conversation_id` for resume.
- **0.1.203**: life-like rest + blended listen/think/speak postures; fix pan/zoom snap-back (multi-touch finger-up was treated as double-tap reset); wait for real WS `onOpen` before session update; parallel audio prep + tighter connect timeouts.

### 0.1.195–0.1.202 — Companion inner app (VRM stage + Voice Agent)

- **New Apps hub module**: offline Three.js + `@pixiv/three-vrm` stage (bundled Seed-san), durable chat turns, xAI Voice Agent realtime + HostAiClient TTS fallback.
- VRM load via Kotlin bridge (`openVrm` / base64 read) — no `file://` fetch; cancel/dispose superseded loads so models do not stack.
- OrbitControls rotate/pan/pinch-zoom; canvas no longer opens chat or starts voice by itself.
- Earlier half-duplex / first-turn speech hardening and selectable Cubism packs on the Live2D experiment path (stage later standardized on VRM).

### 0.1.161–0.1.172 — Grok Assistant + wake (highlights)

| Version | Notes |
|---------|--------|
| **0.1.172** | Unstick Voice Agent thinking UX |
| **0.1.167** | Ephemeral assistant overlay + wake listen handoff |
| **0.1.165** | Hey Grok wake word + system assist / BT / Auto entry |
| **0.1.163–0.1.164** | Floating mini overlay, bottom-center layout, Look-at-screen crop |
| **0.1.162** | Grok Assistant MVP inner app |
| **0.1.161** | Chat auto-scroll stick-to-bottom fix |

### 0.1.160 — Chat stick-to-bottom unlock

- **Auto-scroll only while at bottom**: scrolling up unlocks stick-to-bottom so new stream chunks cannot yank the viewport.
- Expanding an older tool/thought card unlocks immediately so expanded content stays put mid-stream.
- Returning to the bottom re-enables pin-to-bottom.

### 0.1.159 — Bridge agent working directory

- **Global workspace setting**: choose the agent process `cwd` on the bridge host (default = GrokifyOS install / `GROKIFY_WORKSPACE`).
- Bridge install paths (runtime logs, uploads, auth) stay on the install workspace; only agent `cwd` changes.
- Stored as `app_settings.bridge_agent_cwd` (empty/missing → default).
- Bridge HTTP: `GET/POST /work-dir`, `GET /work-dir/list` (directory browser); gateway proxies the same routes.
- PHP: `GET/POST /api/admin-system-chat-workdir.php` (get, set, reset, list).
- **Android Settings** + **web system-chat Settings**: show current path, type a path, browse folders on the server, reset to default.
- New chats/spawns use the selected directory after save.

### 0.1.158 — Remove shared radio from Live DJ

- **Removed** in-app shared radio (TX/RX codes, tune-in, host publish) from Spotify Live DJ.
- Live DJ is local booth only again — no shared-room client hooks.
- Radio stays a separate project if/when rebuilt.

### 0.1.157 — Spotify keys on-device only

- Removed the temporary host-keys dashboard / DB sync for Spotify Client ID/Secret.
- OAuth credentials stay in the on-device vault (and Mapbox / other vault keys as before).
- First-account-is-admin behavior from 0.1.151 remains.

### 0.1.152–0.1.156 — Web console skin + Live DJ radio experiment

- **Web dashboard**: high-tech console skin (styles extracted to `web/assets/css/app.css`); system-chat chrome updated to match.
- **Spotify OAuth**: pure PKCE token exchange first, secret fallback only when Spotify rejects confidential-client; bounce page (`spotify-callback.php`) intent URLs + App Link polish.
- **Live DJ**: console restyle (ON AIR chip, CHAT · QUEUE · SETTINGS tabs).
- **Shared radio (later removed in 0.1.158)**: TX/RX room codes and mid-song sync shipped briefly in 0.1.153–0.1.156, then pulled.

### 0.1.151 — First-account admin (+ short-lived host keys)

- **First account is admin** (setup / install). Later accounts default to `user` (`schema/001_init.sql` default role + `gos_default_new_user_role()`).
- A host-keys Admin panel and `host_keys` device sync shipped here and were **removed in 0.1.157** — do not rely on `admin-settings` / `003_host_keys` (they are not in tree).

### 0.1.150 — Live DJ: AI queue-rank prompts as templates

- **Queue rank system** + **Queue rank request** under Prompt templates — the music-director system rules and candidate request message used when **AI rank next tracks** is on.
- Editable / resettable like banter · research · chat system cores. Placeholders: `{{N}}`, `{{GENRE_BIAS}}`, `{{CURRENT}}`, `{{BEHAVIOR}}`, `{{CANDIDATES}}`, etc.
- Default text matches the previous hard-coded prompt.

### 0.1.149 — Live DJ: queue system sources + playlists

- **Queue system** in Live DJ → Settings (next to prompt templates): toggle the radio-pool selection processes — Liked, Top tracks, Top artists, Recent, History, Disliked, Artist radio, Related, Song radio, Playlists.
- **Default = current full blend** (all sources on; playlists randomly sample ~3 each fill).
- **Multi-select playlists**: Load playlists → pin any number (up to 12). Pinned only those feed the pool; empty pin list keeps the random-sample default.
- **Reset to default mix** restores all sources + clears pins.

### 0.1.148 — System back navigation

- **Inner app → Apps hub**: Android system back closes the open inner app instead of leaving the activity.
- **Apps hub → Chat**: from the Apps tab (no open app), back switches to Chat.
- **Chat double-back → minimize**: first back toasts “Press back again to minimize”; second within 2s calls `moveTaskToBack` so the app runs in the background (does not exit).
- **Overlays first**: open settings, history/notes panels, rename, and notification-access dialogs close on back before tab navigation.
- **Home / Update**: back returns to Chat for a consistent stack.

### 0.1.147 — Live DJ: next song advances again

- **Root cause (0.1.146 regression)**: end-wait abort on Spotify `is_playing=false` called `holdAutoHandoff`, which froze *all* auto handoffs — songs ended into dead air and never started the next cut.
- **Fix**: wait abort only cancels that handoff and re-arms; it no longer freezes the booth. Outro detect treats empty/paused-near-end as “done” so the set keeps moving.
- Still avoids mid-song skips: sustained pause only aborts when remain is clearly mid-track (~25s+).

### 0.1.146 — Live DJ: stop mid-song / early end skips

- **Root cause**: handoff wait treated a single empty / `is_playing=false` API blip as “track ended,” then pause + direct-play next mid-cut. Silent handoffs also hard-cut at ~3.5s remain.
- **End wait**: require sustained empty/pause near the true outro; mid-track pause aborts handoff (hold set, no skip).
- **Near-end arming**: tighter banter thresholds (cap ~18s talkover), no arm in the first half of a long cut; silent path waits until ~0.8s remain before next.
- **Talkover finish**: wait for natural end scales with remaining time (no fixed 12s force-next).
- **External reclaim debounce**: foreign URI must stick ~2.2s before reclaiming (ad/metadata blips).
- **Session fallback**: need two consecutive low-remain polls before near-end.

### 0.1.145 — Spotify live lockscreen controls, custom media notif, widgets

- **Custom Spotify media card**: shade + lockscreen use RemoteViews (compact + expanded) with real **title / artist**, **album art** (large art for lockscreen background), and **prev · play/pause · next** always visible (white icons on dark discs; mint play/pause).
- **Notification durability**: HIGH-importance channel (v9+), MediaSession metadata + art re-sync, FGS re-promote when the controller card vanishes, and Live DJ no longer tears down the controller notif on booth teardown.
- **MediaSession ownership**: controller keeps its own session token on the card; Live DJ session stays for BT/headset; transport routes to the booth when Live DJ is on air.
- **Home Spotify widget**: layout/control refresh so 1×4 transport matches the new notif icon set.
- **Place Notes**: further monitoring / background FGS hardening for enter alerts when the app is closed.
- **Bridge**: default `--reasoning-effort high` for headless Grok agents (`GROKIFY_REASONING_EFFORT` override). See **0.1.266** for grok-4.6 / per-model effort.

### 0.1.125 — Live DJ pause freeze, idle lockscreen ticks, full chat timestamps

- **Live DJ pause-hold**: while paused, no auto next / banter / stuck-end recovery — hold stays until you press play (or skip/play still works). Empty player mid-pause freezes instead of force-next; held booth polls ~every 60s.
- **Spotify lockscreen / FGS**: progress bar ticks only while playing (~1s); paused idles (~30s) and wakes on play/pause/session change.
- **Chat timestamps**: full date + time to the second (e.g. `Jul 16, 2026 · 14:32:05`) in main chat, Live DJ chat, and web system chat.

### 0.1.124 — Place Notes background FGS, Spotify 429 gate, home widgets

- **Place Notes monitoring**: location **foreground service** keeps area monitoring alive when the app is closed so enter alerts (notify / open app / image) actually fire. Ongoing “Place Notes monitoring” notification; re-arms after reboot and OTA.
- **Home-screen widgets**: Spotify (1×4 + controls), Place Notes (compact + full), Wi‑Fi / BT scanners, SpaceXAI usage — tap opens GrokifyOS into the matching inner app; Spotify transport actions work from the widget.
- **Spotify API 429 gate**: process-wide cool-down so Control / widget / Live DJ stop hammering the Web API after rate limits.
- Media-cache endpoint + album-art mirror helpers for widgets and lockscreen art.

### 0.1.110 — Live DJ: More like this mix (similar-first)

- **More like this** no longer dumps mostly same-artist tops. Batch is mixed (~¼ same-artist deep cuts, ~½ related artists, rest genre / playlist-radio / your liked+tops blend).
- Related artists expanded (more seeds, random depth), album B-sides for same-artist variety, genre tags from the seed, public playlist radio searches.
- Artist-diverse pick + interleaved order so you don’t get the same name three times in a row; chat lists `[same]` / `[related]` / `[genre]` / `[mix]` tags.

### 0.1.109 — Apps tab: last-app icon & name

- When you’re on **Home / Chat / Update** and a mini-app was last open, the **Apps** tab shows that app’s **icon + short name** (resume on tap).
- While **inside** a mini-app, the tab switches back to **Apps** (grid) so one tap returns to the hub drawer (replaces double-tap).

### 0.1.108 — Live DJ: more-like-this summary + prompt templates

- **More like this**: sticky “Finding…” indicator clears when done; system chat lists every added track (no talk).
- **Prompt templates** in Live DJ → Settings: research angles (enable for random pack), behaviors (pick / edit / add custom), plus editable banter / research / chat system prompts with placeholders.

### 0.1.107 — Live DJ: More like this

- **More like this** on current + past chat tracks (and Control transport): same artist top cuts + related-artist radio, **prepended** to UP NEXT so they play next.
- Listener-attributed queue reason so banter can credit the request correctly.

### 0.1.106 — Live DJ: banter cadence + queue attribution

- **Talk only when due**: prefetched banter no longer forces speech every handoff — countdown / Skip+talk only.
- **No double-count** on play landing: silent handoffs and late Spotify syncs no longer both increment the banter counter (was accelerating to “every song”).
- **Who queued it**: DJ radio picks (liked/top/artist/genre) are labeled LIVE DJ; only chat/request cuts count as LISTENER. Prompts forbid “you queued this” on AI-picked tracks.
- Keep original pool `reason` on AI set picks (don’t overwrite with banter notes that looked like user requests).

### 0.1.105 — Live DJ chat: like past songs

- **Heart on previously played tracks** in DJ booth chat (not only now-playing) — saves/removes from Spotify Liked Songs.
- Batch liked-status lookup for chat history so hearts reflect library state when you scroll back.

### 0.1.104 — Live DJ: inter-song buffer (no false pause)

- **Between-track grace**: empty / not-playing flickers after a handoff or play no longer freeze auto-handoff as “paused / idle”.
- **Mid-pause debounce**: requires ~4.5s of sustained mid-track pause before treating it as a real user pause (Spotify often reports paused while buffering the next cut).
- **Longer empty-player buffer** before idle advance or session-hold; sticky `wasPlaying` through end-of-track blips so the set keeps moving.

### 0.1.103 — Live DJ: your name + rotating research

- **Your name** in Live DJ settings (manual, or **From Spotify** display name). Auto-fills once when empty so the DJ can address you on mic.
- **Name ≠ city**: prompts hard-separate listener name from metro/city so banter never greets you as your location.
- **Random research angles** each talk: lyrics & meaning · album/song facts · artist facts · shows & tours (local + national) · recent X/social · classic radio host color — 1–3 angles per cycle so packs stay varied.
- **Unhinged / Hype Unhinged**: stronger taste roasts using the current set + research pack (still no protected-class slurs).

### 0.1.102 — Spotify re-authorize without logout

- **Settings → Spotify** and **Spotify → Account**: **Re-authorize** while still connected (forces consent dialog for full scopes, including Liked Songs / library modify).
- Like / library permission errors surface a **Re-authorize Spotify** CTA instead of a dead-end message.
- OAuth helper: `SpotifyOAuth.reauthorize()` with `show_dialog=true` so scope upgrades don’t require logout.

### 0.1.98–0.1.101 — Live DJ: genre board, behavior modes, richer research

- **Genre board** (optional, multi-select): chips discovered from your Spotify top artists; biases queue building and banter context when set.
- **Behavior modes** (tone after research + queueing): Default · Hype · Hype Unhinged · Comedy · Soothing · Unhinged.
- **Listener city**: set your metro so research can surface **upcoming shows** and lightly inject tracks from artists touring near you (including artists you already listen to).
- **Deeper on-air research**: lyrics themes (current + next), album / release context, better artist–song–album facts, show/tour bullets — still tool-backed via host Grok, no full lyric dumps.
- Banter / handoff polish: pre-bake TTS (`synthesize_only` / `audio_path`) for smoother talkovers; sine-style banter frequency updates retained under Default and friends.

### 0.1.95–0.1.97 — Maps + Place Notes

- **Leaflet assets** shipped in-app (`android/app/src/main/assets/map/`) so the WebView map shell loads offline-friendly; basemap still uses your Mapbox vault token.
- Shared **WifiMapView** upgrades: radius rings, tap-to-pin (`onMapTapped`), auto-fit control, empty-state hints, place-friendly popups.
- **Place Notes**: labeled **List | Map** toggle; map of all places with radius rings; editor map preview with GPS + tap-to-set pin.

### 0.1.94 and earlier (highlights)

| Version | Notes |
|---------|--------|
| **0.1.94** | Live DJ: hold auto-handoff on pause; double-tap Apps hub |
| **0.1.92** | Mapbox maps fix; BT/Wi‑Fi scan persistence |
| **0.1.91** | Live DJ booth mode (chat/queue/play without auto-handoff) |
| **0.1.90** | Live DJ direct-play (stop using Spotify’s queue as source of truth) |
| **0.1.88–0.1.89** | Queue ↔ Spotify Up Next alignment; less thrash mid-song |
| **0.1.87** | One-tap Grok/xAI device OAuth when usage needs re-login |
| **0.1.83–0.1.86** | Native queue sync, resume after restart, drift fixes, 1:1 mirror |
| **0.1.79–0.1.80** | SpaceXAI Management key split; usage history/threshold fixes |
| **0.1.76** | Chat stick-to-bottom + bubble menus |

Also in this tree (chat UI): markdown **tappable links** (markdown + bare `https://` / `www.` URLs; safe href allowlist).

When you ship a new APK, **bump** `versionCode` / `versionName`, publish OTA, then add a short bullet block under a new `### 0.x.y` heading at the top of this list.

---

## Disclaimer

**GrokifyOS is not affiliated with SpaceXAI/xAI/SpaceX, Grok, Grok Build, Mapbox, Spotify, or any other third party.**  
It is a community MIT project that lets you self-host tooling which *may* call third-party APIs using credentials **you** provide. You are responsible for complying with each provider’s terms of service and for securing your own deployment.

---

## License

[MIT](LICENSE) — use it, fork it, host it, ship your own custom phone OS.
