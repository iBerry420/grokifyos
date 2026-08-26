package io.grokify.os.apps.plugin

/**
 * Built-in mini-apps shipped with the host APK.
 * Always available in the Apps hub (user can rearrange order).
 */
object BuiltinPluginCatalog {
    const val WIFI_SCANNER = "wifi_scanner"
    const val BT_SCANNER = "bt_scanner"
    const val PLACE_NOTES = "place_notes"
    const val SPOTIFY_CONTROLLER = "spotify_controller"
    const val SPACEXAI_USAGE = "spacexai_usage_analyzer"
    const val GROK_ASSISTANT = "grok_assistant"
    const val COMPANION = "companion"
    const val WATCH_DEPLOY = "watch_deploy"
    const val CEXBOT = "cexbot"
    const val GBOT = "gbot"
    const val DISCORD = "discord"
    const val LYRE = "lyre"

    val all: List<PluginManifest> = listOf(
        PluginManifest(
            id = WIFI_SCANNER,
            title = "Wi‑Fi Scanner",
            subtitle = "Scan nearby networks with GPS, distance, times seen, and alerts (SSID/MAC watch, unseen, strong nearby).",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = WIFI_SCANNER,
            capabilities = listOf("Nearby Wi‑Fi", "Location"),
            accent = PluginAccent.Cyan,
            icon = PluginIconKey.Wifi,
            featured = true,
        ),
        PluginManifest(
            id = BT_SCANNER,
            title = "Bluetooth Tracker",
            subtitle = "BLE + classic discovery with GPS pins, distance, times seen, and alerts (name/MAC watch, unseen, strong nearby).",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = BT_SCANNER,
            capabilities = listOf("Bluetooth", "Location", "Notifications"),
            accent = PluginAccent.Mint,
            icon = PluginIconKey.Bluetooth,
            featured = true,
        ),
        PluginManifest(
            id = PLACE_NOTES,
            title = "Place Notes",
            subtitle = "Pin notes to GPS spots. On enter: notify, open an app, or show an image. List + map + area monitoring.",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = PLACE_NOTES,
            capabilities = listOf("Location", "Notifications"),
            accent = PluginAccent.Violet,
            icon = PluginIconKey.Place,
            featured = true,
        ),
        PluginManifest(
            id = SPOTIFY_CONTROLLER,
            title = "Spotify",
            subtitle = "Lockscreen controls + Live AI DJ booth chat (track history, banter, queue chat). OAuth + media in one host module.",
            version = "2.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = SPOTIFY_CONTROLLER,
            capabilities = listOf("Notifications", "Media control", "AI", "Voice"),
            accent = PluginAccent.Mint,
            icon = PluginIconKey.Music,
            featured = true,
            requiredKeys = listOf(
                PluginRequiredKey(
                    id = "spotify_client_id",
                    label = "Spotify Client ID",
                    description = "From developer.spotify.com — Redirect URI: https://grokifyos.grokpot.io/spotify-callback.php",
                    required = false,
                ),
                PluginRequiredKey(
                    id = "spacexai_api_key",
                    label = "SpaceXAI API key",
                    description = "Optional — Grok Voice TTS for Live DJ banter. Device TTS works without it.",
                    required = false,
                ),
            ),
        ),
        PluginManifest(
            id = SPACEXAI_USAGE,
            title = "SpaceXAI Usage Analyzer",
            subtitle = "Monitor prepaid credits, period spend, limits, and usage breakdown via the SpaceXAI Management API.",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = SPACEXAI_USAGE,
            capabilities = listOf("Billing", "Credits", "Network"),
            accent = PluginAccent.Amber,
            icon = PluginIconKey.Chart,
            featured = true,
            requiredKeys = listOf(
                PluginRequiredKey(
                    id = "spacexai_management_key",
                    label = "SpaceXAI Management key",
                    description = "console.x.ai → Management Keys (billing read). Separate from the inference API key used for Voice TTS.",
                    required = true,
                ),
            ),
        ),
        PluginManifest(
            id = GROK_ASSISTANT,
            title = "Grok Assistant",
            subtitle = "On-device chat + voice assistant. Conversation or Dev mode, editable prompts, Grok Build + TTS.",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = GROK_ASSISTANT,
            capabilities = listOf("AI", "Voice", "Chat"),
            accent = PluginAccent.Violet,
            icon = PluginIconKey.Assistant,
            featured = true,
            requiredKeys = listOf(
                PluginRequiredKey(
                    id = "spacexai_api_key",
                    label = "SpaceXAI API key",
                    description = "Optional — Grok Voice TTS for spoken replies. Device TTS works without it.",
                    required = false,
                ),
            ),
        ),
        PluginManifest(
            id = COMPANION,
            title = "Companion",
            subtitle = "Live2D avatar you can talk to — SpaceXAI voice, chat, custom personality.",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = COMPANION,
            capabilities = listOf("AI", "Voice", "Chat", "Avatar"),
            accent = PluginAccent.Rose,
            icon = PluginIconKey.Avatar,
            featured = true,
            requiredKeys = listOf(
                PluginRequiredKey(
                    id = "spacexai_api_key",
                    label = "SpaceXAI API key",
                    description = "Voice Agent + TTS. Device TTS can cover text-path speak without it.",
                    required = false,
                ),
            ),
        ),
        PluginManifest(
            id = WATCH_DEPLOY,
            title = "Watch Deploy",
            subtitle = "Developer: OTA-download the wear channel APK and install it on a Galaxy Watch over wireless ADB. Data view stub for wear→phone payloads.",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = WATCH_DEPLOY,
            capabilities = listOf("Wear OS", "OTA", "ADB", "Developer"),
            accent = PluginAccent.Cyan,
            icon = PluginIconKey.Watch,
            featured = true,
        ),
        PluginManifest(
            id = CEXBOT,
            title = "CexBot",
            subtitle = "Trade desk + GrokifyOS chat at cexbot.grokpot.io",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = CEXBOT,
            capabilities = listOf("Trading", "AI"),
            accent = PluginAccent.Amber,
            icon = PluginIconKey.CexBot,
            featured = true,
        ),
        PluginManifest(
            id = GBOT,
            title = "Grok Bot",
            subtitle = "Chat-first gbot control plane: bots, approvals, computer, MCP.",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = GBOT,
            capabilities = listOf("AI", "Chat", "Approvals", "VNC"),
            accent = PluginAccent.Violet,
            icon = PluginIconKey.Bot,
            featured = true,
        ),
        PluginManifest(
            id = DISCORD,
            title = "Discord",
            subtitle = "Manage Avalynn Discord ingest: bots, selfbots, feed, guilds, users, emoji, role pickers, captchas, audits, AI tag + analyze, SpaceXAI/bridge models.",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = DISCORD,
            capabilities = listOf("Discord", "Bots", "Moderation", "AI"),
            accent = PluginAccent.Violet,
            icon = PluginIconKey.Forum,
            featured = true,
        ),
        PluginManifest(
            id = LYRE,
            title = "LYRE",
            subtitle = "Native storyboard editor: rails, local cut, Imagine, watch link.",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = LYRE,
            capabilities = listOf("Video", "AI", "Camera", "Media"),
            accent = PluginAccent.Rose,
            icon = PluginIconKey.Lyre,
            featured = true,
            requiredKeys = listOf(
                PluginRequiredKey(
                    id = "spacexai_api_key",
                    label = "SpaceXAI API key",
                    description = "Backup Imagine (stills + video) when me.grokpot.io fails. Muse uses Grok Build without it.",
                    required = false,
                ),
            ),
        ),
    )

    private val byId: Map<String, PluginManifest> = all.associateBy { it.id }

    fun get(id: String): PluginManifest? = byId[id]

    fun isKnown(id: String): Boolean = byId.containsKey(id)
}
