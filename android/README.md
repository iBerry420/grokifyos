# GrokifyOS Android

Native Kotlin + Jetpack Compose clients for your self-hosted **GrokifyOS** server.

| Module | Gradle | OTA channel | Package id | What |
|--------|--------|-------------|------------|------|
| **Phone host** | `:app` | `phone` (default) | `io.grokify.os` (debug: `.debug`) | Chat, Settings, **Apps** hub, inner apps, OTA |
| **Grokify Wear** | `:wear` | `wear` | **Same** `io.grokify.os` (+ debug suffix) | Radial HUD + Carina AI — **not** a phone UI clone |
| **Watch face** | `:wear-face` | `wear-face` | `io.grokify.os.wear.face` | WFF always-on face (time + HR/steps). Separate APK required by Wear OS |

Phone and wear **must share** `applicationId` + signing cert so Wear Data Layer can sync the SpaceXAI key and device token. Wear Kotlin `namespace` stays `io.grokify.os.wear`.

Agent release defaults (always publish the channel you changed): root **[AGENTS.md](../AGENTS.md)** · product overview: root **[README.md § Grokify Wear OS](../README.md#grokify-wear-os)**.

## Architecture

| Layer | Role |
|-------|------|
| **Dashboard** | Your host — PHP, password admin auth |
| **PHP API** | Device tokens (`gos_…`), multi-channel APK OTA, chat |
| **Bridge** | WebSocket agents (`wss://…/grokify-ws/` or LAN `ws://…`) |
| **Phone app** | Chat, permissions, background service, **Watch Deploy** |
| **Wear app** | Standalone HUD + Carina; self-update over LTE/Wi‑Fi |
| **Watch face** | Resource-only WFF package |

## Requirements

| Tool | Notes |
|------|--------|
| **JDK 17** | `JAVA_HOME` |
| **Android SDK** | `ANDROID_HOME` — platform 35, build-tools 34+ |
| **Gradle wrapper** | `./gradlew` / `gradlew.bat` |

## Configure API endpoints

### Phone (`app/build.gradle.kts` BuildConfig)

| Field | Local example | VPS example |
|-------|---------------|-------------|
| `API_BASE` | `http://192.168.1.10:8787/api` | `https://your.domain/api` |
| `WS_URL` | `ws://192.168.1.10:8787/grokify-ws/` * | `wss://your.domain/grokify-ws/` |
| `SITE_URL` | `http://192.168.1.10:8787` | `https://your.domain` |

\* WebSocket through the PHP dev server may need a separate bridge port or proxy; production uses reverse-proxy to the Node bridge.

### Wear (`wear/build.gradle.kts`)

| Field | Notes |
|-------|--------|
| `API_BASE` | Same host `/api` as the phone (used for `channel=wear` OTA check/download) |

Point both at the same `GROKIFY_SITE_URL` when rebuilding for a user.

## Build

```bash
cd android
./gradlew :app:assembleDebug          # phone only
./gradlew :wear:assembleDebug         # wear only
./gradlew :wear-face:assembleDebug    # face only
# Windows: gradlew.bat …
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `wear/build/outputs/apk/debug/wear-debug.apk` (name may vary slightly by AGP)
- `wear-face/build/outputs/apk/debug/…`

Helpers (Linux/macOS):

```bash
./scripts/build.sh debug phone      # :app
./scripts/build.sh debug wear       # :wear
./scripts/build.sh debug wear-face  # :wear-face
./scripts/build.sh debug all        # all three

./scripts/publish.sh debug --changelog "notes"                      # phone
./scripts/publish.sh debug --channel wear --changelog "notes"       # wear
./scripts/publish.sh debug --channel wear-face --changelog "notes"  # face

./scripts/install-device.sh IP:PORT   # wireless adb (phone or watch)
```

## Install on a phone

1. Open your GrokifyOS dashboard → log in.
2. **Devices** → create token (`gos_…`).
3. Install APK via USB, wireless ADB, or dashboard **Download APK**.
4. Paste token. Runtime permissions (camera, mic, location, …) are **not** requested on first launch.
5. **Settings → Permissions** — toggle each capability when you need it. Grok can also push an in-chat **Allow / Not now** card via `[[permission_request:camera|reason]]` markers (or a `permission_request` WS event).
6. **Settings → Notification access** — enable GrokifyOS so Grok can pull your active notifications (also toggle **Share with Grok** in app Settings).

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Install Grokify Wear (first time)

Wear needs a **shared IP network** for wireless ADB — home Wi‑Fi, cafe Wi‑Fi, or **phone hotspot** (watch joins the hotspot). Bluetooth alone does **not** install APKs.

1. Phone GrokifyOS signed in with a device token; put **SpaceXAI API key** in the phone vault if Carina voice is wanted.
2. On the watch: **Developer options → Wireless debugging** → copy the **IP:port** used to **connect** (not the one-time pairing port, once already paired).
3. Phone → **Apps → Watch Deploy** → set Connect IP:port → **Update & install**  
   (one tap: OTA check `channel=wear` → download → `adb install`).
4. Accept any “install unknown apps” / ADB prompts on the watch.
5. Open the wear app. With phone nearby, Data Layer should push **API key + device token**. Fallback: paste token / key on the watch.
6. **Ongoing updates:** on the watch use **Update app** (check + download + install in one action) over LTE or Wi‑Fi. Or run Watch Deploy again from the phone.

### Watch face

```bash
./scripts/publish.sh debug --channel wear-face --changelog "face notes"
adb install -r wear-face/build/outputs/apk/debug/*.apk   # or install-device.sh
```

Watch Deploy currently targets **`channel=wear`** (the app), not the face.

### Troubleshooting Watch Deploy

| Symptom | Try |
|---------|-----|
| Connect hangs after an app update | **Cancel**, soft reconnect; hard reconnect if the wireless port changed |
| No Wi‑Fi | Phone hotspot → watch joins → same Deploy flow |
| Data Layer never syncs keys | Confirm phone + wear both debug or both release (same package suffix + cert) |
| Wear “Need device token” | Open phone Grokify once nearby, or paste `gos_…` on the watch |

## OTA (multi-channel)

| Channel | Check | Download |
|---------|--------|----------|
| **phone** (default) | `GET /api/update.php?version_code=N` | `GET /api/apk-download.php` |
| **wear** | `…&channel=wear` | `…?channel=wear` |
| **wear-face** | `…&channel=wear-face` | `…?channel=wear-face` |

All require the device Bearer token. **`versionCode` must increase** on each publish **for that channel only**.

## Package / SDK

Phone: `io.grokify.os` · minSdk 26 · targetSdk 35 · compileSdk 35  
Wear: same applicationId · minSdk 30 · namespace `io.grokify.os.wear`  
Face: `io.grokify.os.wear.face`  
AGP 8.7.3 · Kotlin 2.0.21

## Current release

See root **[README.md § Changelog](../README.md#changelog)** for full history. Ship notes:

| Channel | versionName | versionCode | Notes |
|---------|-------------|-------------|--------|
| **phone** | **0.1.295** | **295** | Grok Bot: full Bots pane, connectors, back stack, chat flicker fix |
| **wear** | **0.1.8** | **8** | Standalone AI + radial HUD; on-watch Update app |
| **wear-face** | **0.1.1** | **1** | WFF always-on face (time + HR/steps complications) |
| phone | 0.1.250 | 250 | Grok logout + re-login; Companion world; Android Auto |
| phone | 0.1.233 | 233 | Chat image attach + Companion VRMA / movement agent |
| phone | 0.1.160 | 160 | Chat stick-to-bottom unlock when scrolled up |

**Settings → Working directory** (phone) picks the bridge agent `cwd` (default = GrokifyOS install path).
