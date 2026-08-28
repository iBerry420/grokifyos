# Install GrokifyOS

Run your own Grokify-style assistant with **Grok Build**. Works **locally** (laptop / LAN) or on a **VPS** for remote access.

Password-only admin auth. Dedicated MySQL database. Real chat and usage — no demo seed data.

---

## Requirements

| Component | Required for | Notes |
|-----------|--------------|--------|
| **PHP 8.1+** | Web + API | Extensions: `pdo_mysql`, `curl`, `json`, `mbstring`, `session` |
| **MySQL 8+** or **MariaDB 10.5+** | Persistence | Dedicated database (do not share with other apps) |
| **Node.js 18+** | Agent bridge | Optional if you only need UI without streaming agents |
| **Android SDK** | Building the APK | Optional if you download a prebuilt APK from your own host |
| **Grok Build CLI auth** | Real agents + usage | `auth.json` from `grok login` |

| Mode | Reachability |
|------|----------------|
| **Local / LAN** | Same machine or same Wi‑Fi (`php -S 0.0.0.0:8787`) |
| **VPS + DNS + TLS** | Anywhere (phone on mobile data, etc.) |

---

## 1. Install the stack by OS

### Ubuntu / Debian

```bash
sudo apt update
sudo apt install -y php-cli php-mysql php-curl php-mbstring php-xml \
  mysql-server git curl

# Node 20 (NodeSource) — or use nvm / distro node if ≥ 18
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

# Optional: full LAMP for production
sudo apt install -y apache2 libapache2-mod-php certbot python3-certbot-apache
sudo a2enmod rewrite proxy proxy_http proxy_wstunnel headers ssl
```

Create a MySQL database and user:

```bash
sudo mysql -e "
  CREATE DATABASE IF NOT EXISTS grokifyos CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  CREATE USER IF NOT EXISTS 'grokifyos'@'localhost' IDENTIFIED BY 'CHANGE_ME_STRONG';
  CREATE USER IF NOT EXISTS 'grokifyos'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME_STRONG';
  GRANT ALL ON grokifyos.* TO 'grokifyos'@'localhost';
  GRANT ALL ON grokifyos.* TO 'grokifyos'@'127.0.0.1';
  FLUSH PRIVILEGES;
"
```

### macOS

```bash
# Homebrew: https://brew.sh
brew install php mysql node git

# Start MySQL (first time may need brew services)
brew services start mysql

mysql -u root -e "
  CREATE DATABASE IF NOT EXISTS grokifyos CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  CREATE USER IF NOT EXISTS 'grokifyos'@'localhost' IDENTIFIED BY 'CHANGE_ME_STRONG';
  GRANT ALL ON grokifyos.* TO 'grokifyos'@'localhost';
  FLUSH PRIVILEGES;
"
```

Optional production frontends: Caddy, nginx, or Apache via Homebrew. For day-to-day local use, the PHP built-in server is enough.

### Windows

**Recommended:** [WSL2](https://learn.microsoft.com/windows/wsl/install) (Ubuntu) and follow the **Ubuntu** section above. That matches production Linux closest.

**Native Windows alternative:**

1. Install [XAMPP](https://www.apachefriends.org/) or [php.net Windows builds](https://windows.php.net/download/) + [MySQL](https://dev.mysql.com/downloads/installer/) or [MariaDB](https://mariadb.org/download/).
2. Install [Node.js LTS](https://nodejs.org/) (18+).
3. Install [Git for Windows](https://git-scm.com/download/win).
4. Enable PHP extensions in `php.ini`: `extension=pdo_mysql`, `extension=curl`, `extension=mbstring`, `extension=openssl`.
5. Create database `grokifyos` and a user in MySQL Workbench / CLI.

```powershell
# From the repo root (PowerShell / cmd)
copy .env.example .env
# edit .env with Notepad

php scripts\install.php --admin=admin --password=your-long-password
php -S 0.0.0.0:8787 scripts\dev-router.php
```

Open `http://127.0.0.1:8787` in a browser.

---

## 2. Clone and configure

```bash
git clone https://github.com/iBerry420/grokifyos.git
cd grokifyos
cp .env.example .env
```

Edit `.env` (minimum):

```env
GROKIFY_APP_NAME=GrokifyOS
GROKIFY_SITE_URL=http://127.0.0.1:8787   # or https://your.domain

GROKIFY_DB_HOST=127.0.0.1
GROKIFY_DB_PORT=3306
GROKIFY_DB_NAME=grokifyos
GROKIFY_DB_USER=grokifyos
GROKIFY_DB_PASS=CHANGE_ME_STRONG

# Generate with: openssl rand -hex 32
GROKIFY_WS_AUTH_SECRET=
GROKIFY_SECRETS_PEPPER=
```

---

## 3. Install schema + first admin

```bash
php scripts/install.php --admin=YOUR_USER --password=YOUR_LONG_PASSWORD
```

Applies `schema/*.sql` (idempotent). Creates the first admin only when the users table is empty.

---

## 4. Run the web app

### Local / LAN (easiest)

```bash
php -S 0.0.0.0:8787 scripts/dev-router.php
```

| Client | URL |
|--------|-----|
| This machine | `http://127.0.0.1:8787` |
| Phone on same Wi‑Fi | `http://YOUR_LAN_IP:8787` |

For Android on LAN, set the app’s API base to `http://YOUR_LAN_IP:8787/api` (or rebuild with that `API_BASE`). Cleartext HTTP is fine on a private network; use HTTPS for anything public.

### VPS (remote access)

1. Point a DNS **A** record at your VPS IP.
2. Install Apache or nginx + PHP-FPM, TLS (Let’s Encrypt).
3. Document root / aliases:

| URL path | Filesystem |
|----------|------------|
| `/` | `web/public` |
| `/api` | `web/api` |
| `/assets` | `web/assets` |
| WebSocket path (e.g. `/grokify-ws/`) | proxy to bridge (default `127.0.0.1:8876`) |

Example Apache configs: `deploy/apache-vhost.conf.example`, `deploy/apache-vhost-ssl.conf.example`.

```bash
# Typical Ubuntu VPS outline
sudo ln -sfn /path/to/grokifyos /var/www/grokifyos
sudo cp deploy/apache-vhost.conf.example /etc/apache2/sites-available/grokifyos.conf
# edit ServerName, paths
sudo a2ensite grokifyos.conf
sudo apache2ctl configtest && sudo systemctl reload apache2
sudo certbot --apache -d your.domain --redirect

# secrets for Apache PHP (optional; .env in repo root also works)
sudo mkdir -p /etc/grokifyos
sudo cp .env /etc/grokifyos/php.env   # or maintain separately
sudo chown root:www-data /etc/grokifyos/php.env
sudo chmod 640 /etc/grokifyos/php.env

sudo chown -R www-data:www-data storage/
```

Set in env:

```env
GROKIFY_SITE_URL=https://your.domain
```

---

## 5. Agent bridge (streaming)

Chat **persists** without a bridge (sessions/messages in MySQL). **Streaming agents** need the Node bridge:

```bash
cd bridge
npm ci
# Port must match .env GROKIFY_BRIDGE_URL
export GROKIFY_BRIDGE_PORT=8876
# or set in environment your bridge already reads
node server.js
```

`.env` on the PHP side:

```env
GROKIFY_BRIDGE_URL=http://127.0.0.1:8876
GROKIFY_BRIDGE_HEALTH=http://127.0.0.1:8876/health
GROKIFY_WS_PATH=/grokify-ws/
GROKIFY_WS_AUTH_SECRET=long-random-string
```

On a VPS, reverse-proxy `GROKIFY_WS_PATH` (WebSocket) to the bridge.  
Example systemd units: `deploy/grokifyos-bridge-*.service`.

---

## 6. Grok Build (agents + usage)

Install the Grok / Grok Build CLI and log in on the host that runs the bridge and PHP:

```bash
# after `grok login`, point at the auth file:
GROKIFY_GROK_AUTH_JSON=/path/to/auth.json
```

Usage endpoints call xAI billing with that token. Missing auth → clear API error, not fake numbers.

**PHP-FPM must be able to read the file.** `~/.grok/auth.json` is usually `0600 root`, so the web pool (`www-data`) cannot open it — the chat usage chip then shows unavailable even though CLI/bridge auth works. After login, sync a web-readable copy:

```bash
# Copies auth → storage/grok-auth.json (www-data:640) and updates .env
./scripts/sync-grok-auth.sh
```

Default production path: `storage/grok-auth.json` (gitignored). Re-run the sync after every `grok login`.

---

## 6b. gbotd (Grok Bot inner app)

The phone **Grok Bot** app is a control plane for the `gbot` CLI / `gbotd` daemon on the same host. PHP never exposes the daemon to the internet; it proxies `/api/gbot.php` to loopback.

```env
GROKIFY_GBOTD_URL=http://127.0.0.1:8780
GROKIFY_GBOTD_TOKEN=contents-of-gbotd-token-file
```

Enable gbotd TCP on loopback (`gbotd install --tcp`, or the drop-in in `deploy/gbotd-http.conf.example`). Token file must be mode 0600 and ≥32 bytes. Put the **token value** in `php.env`, not the path.

## 6c. Avalynn Discord (Discord inner app)

The phone **Discord** app is a control plane for the Avalynn Discord backend on the same host (`avalynn-discord.service`, loopback `:4201`). PHP never exposes the backend password; it logs in server-side and proxies an allowlisted slice of `/api/discord/*`. List endpoints for the feed, users, guilds, channels, audits, and media read MariaDB directly so they stay fast (no Node `COUNT(*)` over millions of rows). Guilds/channels only attach bots that are live members (`GET /api/discord/bots/:id/guilds` from the running clients; REST when a bot is offline). Avatars live in Avalynn `uploads/audit-avatars` and are served (and cached from Discord CDN on miss) at `/api/discord.php?action=avatar`. Message attachments are downloaded on capture into `DISCORD_ATTACHMENT_DIR` (`uploads/discord-files`) and served at `/api/discord.php?action=file`. The phone never loads `cdn.discordapp.com` for media. Expired Discord CDN URLs return 404 instead of waiting on Discord. Guild-filtered audits need `GuildAuditEvent (guildId, createdAt)` (`GuildAuditEvent_guildId_createdAt_idx`). Audit event types are `eventAction` (not the `action=audits` dispatcher verb). Users can be scoped with `guildId` via `GuildMemberEvent`.

```env
GROKIFY_DISCORD_URL=http://127.0.0.1:4201
GROKIFY_DISCORD_AVALYNN_USER_ID=1
GROKIFY_AVALYNN_ENV=/var/www/avalynn/.env
```

`GROKIFY_DISCORD_PASSWORD` is optional — if unset, PHP reads `discord_backend_password` from the Avalynn settings table using credentials in `GROKIFY_AVALYNN_ENV`.

### Check / recover auth (CLI)

When chat returns no reply after an agent dies, auth often expired. On the **server**:

```bash
# Status (path, email, expiry, refresh)
php scripts/check-grok-auth.php

# JSON for scripts/monitoring
php scripts/check-grok-auth.php --json

# Force OIDC refresh from refresh_token
php scripts/check-grok-auth.php --refresh

# Live probe via Grok CLI (slower)
php scripts/check-grok-auth.php --probe

# Re-login (headless), then sync for the PHP usage API
# Preferred from the phone app: Settings → Weekly usage → "Sign in with Grok / xAI"
# (or tap the usage chip when it says re-login) — that starts OIDC device-code and
# opens https://accounts.x.ai/oauth2/device?user_code=… for one-tap approve.
# Manual CLI fallback:
grok login --device-code
./scripts/sync-grok-auth.sh
```

Bridge workers also expose a peek on `GET /health` → `grok_auth`. The Android app surfaces auth failures as a **system chat message** (not a silent empty turn).

---

## 7. Android app

| Item | Value |
|------|--------|
| Source | `android/` |
| Package | `io.grokify.os` (debug: `io.grokify.os.debug`) |
| minSdk / targetSdk | 26 / 35 |

### Build (any OS with Android SDK)

```bash
cd android
# set JAVA_HOME (JDK 17) and ANDROID_HOME
./gradlew :app:assembleDebug        # Linux/macOS
# gradlew.bat :app:assembleDebug    # Windows
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

Configure `API_BASE` / `WS_URL` / `SITE_URL` in `android/app/build.gradle.kts` (BuildConfig) to your host, then rebuild.

### Install on a phone

1. Log into the web dashboard → **Devices** → create token (`gos_…`).
2. Install the APK (USB `adb install -r …`, wireless ADB, or host **Download APK** after upload).
3. Paste the token in the app; grant permissions.

Helper scripts (Linux/macOS): `android/scripts/build.sh`, `publish.sh`, `install-device.sh`. Details: [android/README.md](../android/README.md).

---

## API surface

| Path | Role |
|------|------|
| `/api/health.php` | Health + readiness |
| `/api/setup.php` / `login.php` / `logout.php` / `me.php` | Password auth |
| `/api/devices.php` | Device token mint / list / revoke |
| `/api/admin-system-chat-sessions.php` | Chat sessions |
| `/api/admin-system-chat-messages.php` | Messages (CRUD + stream upsert) |
| `/api/admin-system-chat-notes.php` | Instruction notes |
| `/api/admin-system-chat-models.php` | Models + WS token |
| `/api/admin-system-chat-audit.php` | Audit list / SSE |
| `/api/admin-system-chat-usage.php` | Live Grok Build usage |
| `/api/apk-download.php` / `update.php` | APK download / OTA check (publish via `android/scripts/publish.sh`) |

---

## Security checklist

- [ ] Never commit `.env`, session files, APKs, or `auth.json`
- [ ] HTTPS on any public host
- [ ] Strong DB password + `GROKIFY_WS_AUTH_SECRET` + `GROKIFY_SECRETS_PEPPER`
- [ ] `storage/` writable only by the service user
- [ ] Firewall: only 80/443 public; MySQL and bridge stay on localhost unless you know better

---

## Troubleshooting

| Symptom | Check |
|---------|--------|
| `db` health fails | `.env` credentials; MySQL listening; user host (`localhost` vs `127.0.0.1`) |
| Login loop | Cookies / HTTPS mismatch (`GROKIFY_SITE_URL`); session dir writable |
| Usage unavailable | `GROKIFY_GROK_AUTH_JSON` readable by **www-data** — run `./scripts/sync-grok-auth.sh` after `grok login` |
| Agents don’t stream | Bridge process up; `GROKIFY_BRIDGE_*`; WS proxy path |
| Phone can’t reach LAN server | Same Wi‑Fi; firewall allows 8787; use LAN IP not `127.0.0.1` on the phone |
