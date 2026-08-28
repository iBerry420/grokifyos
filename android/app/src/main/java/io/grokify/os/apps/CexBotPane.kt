package io.grokify.os.apps

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.grokify.os.apps.plugin.BuiltinPluginCatalog
import io.grokify.os.apps.plugin.PluginFaviconImage
import io.grokify.os.apps.plugin.PluginIconKey
import io.grokify.os.ui.theme.GrokifyColors

private const val CEXBOT_ORIGIN = "https://cexbot.grokpot.io/"
private val ALLOWED_HOSTS = setOf("cexbot.grokpot.io", "grokifyos.grokpot.io")

private fun hostAllowed(host: String?): Boolean {
    val h = host?.lowercase()?.trim('.').orEmpty()
    return h in ALLOWED_HOSTS
}

/**
 * Persisted WebView of the live CexBot desk. Same origin as the browser —
 * CexBot login + GrokifyOS Trade AI. Do not Compose-clip the WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CexBotPane(
    onBack: () -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        CookieManager.getInstance().setAcceptCookie(true)
        onDispose {
            CookieManager.getInstance().flush()
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = GrokifyColors.TextPrimary,
                )
            }
            PluginFaviconImage(
                pluginId = BuiltinPluginCatalog.CEXBOT,
                fallback = PluginIconKey.CexBot,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "CexBot",
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Text(
                    "cexbot.grokpot.io",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = { webViewRef?.reload() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = GrokifyColors.TextPrimary,
                )
            }
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(AndroidColor.parseColor("#05060A"))
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    // Default layer type — HARDWARE blanks WebView surfaces on some OEMs.
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mediaPlaybackRequiresUserGesture = true
                        // Honor width=device-width. Do not setInitialScale(100) — that is
                        // 1 CSS px = 1 screen px on xxhdpi, so the desk draws ~3× too small.
                        useWideViewPort = true
                        loadWithOverviewMode = false
                        textZoom = 100
                        // Zoom must stay enabled or Chromium ignores viewport initial-scale.
                        setSupportZoom(true)
                        builtInZoomControls = false
                        displayZoomControls = false
                        userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = false
                    }
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val uri = request?.url ?: return true
                            val scheme = uri.scheme?.lowercase().orEmpty()
                            if (scheme != "https" && scheme != "wss") return true
                            return !hostAllowed(uri.host)
                        }
                    }
                    webViewRef = this
                    loadUrl(CEXBOT_ORIGIN)
                }
            },
            update = { view ->
                webViewRef = view
            },
        )
    }
}
