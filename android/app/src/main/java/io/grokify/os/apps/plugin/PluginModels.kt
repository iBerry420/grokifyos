package io.grokify.os.apps.plugin

/**
 * Plugin / inner-app catalog model.
 *
 * Phase 1: built-in modules ship in the host APK; "install" only enables them
 * in the Apps hub. Hardware/permissions stay on the host — plugins use them
 * through host APIs (same as today's mini-apps).
 *
 * Phase 2: [PluginSource.Remote] / [PluginKind.WebView] packages downloadable
 * from the server marketplace and run in a sandboxed WebView.
 */
enum class PluginSource {
    /** Compiled into the host APK; install = enable in hub. */
    Builtin,
    /** Signed/packaged from marketplace server (script or host_module listing). */
    Remote,
}

/** How the host runs the plugin once installed. */
enum class PluginKind {
    /** Native Compose mini-app already in the APK ([hostModuleId]). */
    HostModule,
    /** HTML/JS package downloaded from the server, run in WebView. */
    WebView,
}

/**
 * Stable accent / icon keys so the catalog stays free of Compose types.
 * UI maps these to colors and ImageVectors.
 */
enum class PluginAccent { Cyan, Mint, Violet, Amber, Rose, Blue }

enum class PluginIconKey {
    Wifi,
    Bluetooth,
    Place,
    Music,
    Apps,
    Assistant,
    Avatar,
    Extension,
    Chart,
    Watch,
    CexBot,
    Bot,
    Forum,
    Lyre,
}

/**
 * API key a plugin needs from the host vault.
 * Scripts call GrokifyHost.getApiKey / saveApiKey for these ids only.
 */
data class PluginRequiredKey(
    val id: String,
    val label: String = id,
    val description: String = "",
    /** If true, plugin cannot run without it. */
    val required: Boolean = true,
)

data class PluginManifest(
    val id: String,
    val title: String,
    val subtitle: String,
    val version: String,
    val author: String = "GrokifyOS",
    val source: PluginSource = PluginSource.Builtin,
    val kind: PluginKind = PluginKind.HostModule,
    /**
     * For [PluginKind.HostModule]: which built-in screen to open.
     * Defaults to [id] when null.
     */
    val hostModuleId: String? = null,
    /** Human-readable capability tags (e.g. "Location", "Nearby Wi‑Fi"). */
    val capabilities: List<String> = emptyList(),
    val accent: PluginAccent = PluginAccent.Cyan,
    val icon: PluginIconKey = PluginIconKey.Apps,
    /** Absolute package download URL when [kind] is [PluginKind.WebView]. */
    val packageUrl: String? = null,
    val featured: Boolean = false,
    /**
     * Host API keys this plugin expects. Missing keys show a gate UI;
     * bridge only exposes these ids (plus internal OAuth tokens the host manages).
     */
    val requiredKeys: List<PluginRequiredKey> = emptyList(),
) {
    fun resolvedHostModuleId(): String = hostModuleId?.takeIf { it.isNotBlank() } ?: id

    fun isRunnableHostModule(): Boolean =
        kind == PluginKind.HostModule && BuiltinPluginCatalog.isKnown(resolvedHostModuleId())

    fun isWebView(): Boolean = kind == PluginKind.WebView

    fun allowedKeyIds(): Set<String> = requiredKeys.map { it.id }.toSet()
}

data class InstalledPlugin(
    val id: String,
    val installedAtEpochMs: Long = System.currentTimeMillis(),
)
