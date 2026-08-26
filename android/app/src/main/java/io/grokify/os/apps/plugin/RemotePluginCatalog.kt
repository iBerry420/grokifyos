package io.grokify.os.apps.plugin

import org.json.JSONObject

/**
 * Parse + merge server marketplace catalog with built-in host modules.
 */
object RemotePluginCatalog {

    data class FetchResult(
        val plugins: List<PluginManifest>,
        val source: String,
        val catalogVersion: Int = 1,
        val updatedAt: String? = null,
    )

    fun parseResponse(json: JSONObject): FetchResult {
        val arr = json.optJSONArray("plugins")
        val remote = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    parsePlugin(o)?.let { add(it) }
                }
            }
        }
        return FetchResult(
            plugins = mergeWithBuiltins(remote),
            source = json.optString("source", "server").ifBlank { "server" },
            catalogVersion = json.optInt("catalog_version", 1),
            updatedAt = json.optString("updated_at").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /**
     * Merge rules:
     * - Host modules from server that map to a known built-in replace metadata
     * - Unknown host_module ids are dropped (no APK code to run them)
     * - WebView scripts are kept as remote packages
     * - Built-ins not listed on the server remain available (offline / partial catalog)
     */
    fun mergeWithBuiltins(remote: List<PluginManifest>): List<PluginManifest> {
        val byId = LinkedHashMap<String, PluginManifest>()
        BuiltinPluginCatalog.all.forEach { byId[it.id] = it }

        remote.forEach { r ->
            when (r.kind) {
                PluginKind.HostModule -> {
                    val hostId = r.resolvedHostModuleId()
                    if (!BuiltinPluginCatalog.isKnown(hostId)) return@forEach
                    val base = BuiltinPluginCatalog.get(hostId) ?: return@forEach
                    byId[hostId] = base.copy(
                        // Keep stable hub id = host module id for routing
                        id = hostId,
                        title = r.title.ifBlank { base.title },
                        subtitle = r.subtitle.ifBlank { base.subtitle },
                        version = r.version.ifBlank { base.version },
                        author = r.author.ifBlank { base.author },
                        source = PluginSource.Builtin,
                        kind = PluginKind.HostModule,
                        hostModuleId = hostId,
                        capabilities = r.capabilities.ifEmpty { base.capabilities },
                        accent = r.accent,
                        icon = r.icon,
                        packageUrl = null,
                        featured = r.featured,
                        requiredKeys = r.requiredKeys.ifEmpty { base.requiredKeys },
                    )
                }
                PluginKind.WebView -> {
                    // WebView Spotify DJ was retired — use host module Spotify instead.
                    if (r.id == "spotify_dj" || r.id == BuiltinPluginCatalog.SPOTIFY_CONTROLLER) {
                        return@forEach
                    }
                    if (r.packageUrl.isNullOrBlank()) return@forEach
                    byId[r.id] = r.copy(
                        source = PluginSource.Remote,
                        kind = PluginKind.WebView,
                        hostModuleId = null,
                    )
                }
            }
        }
        return byId.values.toList()
    }

    fun parsePlugin(o: JSONObject): PluginManifest? {
        val id = o.optString("id", "").trim()
        if (id.isEmpty()) return null

        val kindRaw = o.optString("kind", "host_module").lowercase()
        val kind = when (kindRaw) {
            "webview", "web_view", "script" -> PluginKind.WebView
            else -> PluginKind.HostModule
        }
        val sourceRaw = o.optString("source", if (kind == PluginKind.WebView) "remote" else "builtin")
            .lowercase()
        val source = when (sourceRaw) {
            "remote" -> PluginSource.Remote
            else -> PluginSource.Builtin
        }

        val caps = buildList {
            val arr = o.optJSONArray("capabilities")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val c = arr.optString(i, "").trim()
                    if (c.isNotEmpty()) add(c)
                }
            }
        }

        val packageUrl = o.optString("package_url")
            .takeIf { it.isNotBlank() && it != "null" }

        val requiredKeys = parseRequiredKeys(o)

        return PluginManifest(
            id = id,
            title = o.optString("title", id).ifBlank { id },
            subtitle = o.optString("subtitle", ""),
            version = o.optString("version", "1.0.0").ifBlank { "1.0.0" },
            author = o.optString("author", "GrokifyOS").ifBlank { "GrokifyOS" },
            source = source,
            kind = kind,
            hostModuleId = o.optString("host_module_id")
                .takeIf { it.isNotBlank() && it != "null" },
            capabilities = caps,
            accent = parseAccent(o.optString("accent", "cyan")),
            icon = parseIcon(o.optString("icon", "apps")),
            packageUrl = packageUrl,
            featured = o.optBoolean("featured", false),
            requiredKeys = requiredKeys,
        )
    }

    fun parseRequiredKeys(o: JSONObject): List<PluginRequiredKey> {
        val arr = o.optJSONArray("required_keys") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                if (item != null) {
                    val kid = item.optString("id", "").trim()
                    if (kid.isEmpty()) continue
                    add(
                        PluginRequiredKey(
                            id = kid,
                            label = item.optString("label", kid).ifBlank { kid },
                            description = item.optString("description", "")
                                .ifBlank { item.optString("hint", "") },
                            required = item.optBoolean("required", true),
                        ),
                    )
                } else {
                    val kid = arr.optString(i, "").trim()
                    if (kid.isNotEmpty()) {
                        add(PluginRequiredKey(id = kid, label = kid))
                    }
                }
            }
        }
    }

    fun parseAccent(raw: String): PluginAccent = when (raw.lowercase().trim()) {
        "mint" -> PluginAccent.Mint
        "violet" -> PluginAccent.Violet
        "amber" -> PluginAccent.Amber
        "rose" -> PluginAccent.Rose
        "blue" -> PluginAccent.Blue
        else -> PluginAccent.Cyan
    }

    fun parseIcon(raw: String): PluginIconKey = when (raw.lowercase().trim()) {
        "wifi" -> PluginIconKey.Wifi
        "bluetooth", "bt" -> PluginIconKey.Bluetooth
        "place", "location" -> PluginIconKey.Place
        "music", "spotify" -> PluginIconKey.Music
        "assistant", "grok", "grok_assistant" -> PluginIconKey.Assistant
        "companion", "avatar" -> PluginIconKey.Avatar
        "extension", "script", "plugin" -> PluginIconKey.Extension
        "chart", "usage", "analytics", "billing" -> PluginIconKey.Chart
        "watch", "wear", "watch_deploy" -> PluginIconKey.Watch
        "cexbot", "trade" -> PluginIconKey.CexBot
        "bot", "gbot", "grok_bot" -> PluginIconKey.Bot
        "forum", "discord" -> PluginIconKey.Forum
        "lyre" -> PluginIconKey.Lyre
        else -> PluginIconKey.Apps
    }
}
