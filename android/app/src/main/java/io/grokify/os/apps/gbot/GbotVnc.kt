package io.grokify.os.apps.gbot

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.grokify.os.ui.theme.GrokifyColors
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Cursor VM noVNC routes every HTTP request by `network_token` query param.
 * Relative CSS/JS/SVG from `vnc.html?network_token=…` drop the token and 404
 * ("The request could not be routed") — broken icons and a dead UI.
 */
object GbotVncRouting {
    data class Session(
        val host: String,
        val token: String,
        val resumeLower: String = "",
        val resumeUpper: String = "",
    )

    fun sessionFromPageUrl(url: String): Session? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val token = parsed.queryParameter("network_token").orEmpty()
        if (parsed.host.isBlank() || token.isBlank()) return null
        return Session(
            host = parsed.host,
            token = token,
            resumeLower = parsed.queryParameter("resume_lower_s").orEmpty(),
            resumeUpper = parsed.queryParameter("resume_upper_s").orEmpty(),
        )
    }

    fun decoratePageUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val b = parsed.newBuilder()
        if (parsed.queryParameter("autoconnect").isNullOrBlank()) {
            b.setQueryParameter("autoconnect", "true")
        }
        if (parsed.queryParameter("resize").isNullOrBlank()) {
            b.setQueryParameter("resize", "scale")
        }
        if (parsed.queryParameter("reconnect").isNullOrBlank()) {
            b.setQueryParameter("reconnect", "true")
        }
        return b.build().toString()
    }

    fun applyRouting(url: String, session: Session): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (!parsed.host.equals(session.host, ignoreCase = true)) return url
        if (parsed.scheme != "http" && parsed.scheme != "https") return url
        val b = parsed.newBuilder()
        var changed = false
        if (session.token.isNotBlank() && parsed.queryParameter("network_token").isNullOrBlank()) {
            b.setQueryParameter("network_token", session.token)
            changed = true
        }
        if (session.resumeLower.isNotBlank() && parsed.queryParameter("resume_lower_s").isNullOrBlank()) {
            b.setQueryParameter("resume_lower_s", session.resumeLower)
            changed = true
        }
        if (session.resumeUpper.isNotBlank() && parsed.queryParameter("resume_upper_s").isNullOrBlank()) {
            b.setQueryParameter("resume_upper_s", session.resumeUpper)
            changed = true
        }
        return if (changed) b.build().toString() else url
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun GbotVncPane(url: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val session = remember(url) { GbotVncRouting.sessionFromPageUrl(url) }
    val pageUrl = remember(url) { GbotVncRouting.decoratePageUrl(url) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("Connecting desktop…") }

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

    Column(
        Modifier
            .fillMaxSize()
            .background(GrokifyColors.Void),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = GrokifyColors.TextPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status,
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    webViewRef?.evaluateJavascript(
                        """
                        (function(){
                          var btn = document.getElementById('noVNC_keyboard_button');
                          if (btn) btn.click();
                          var inp = document.getElementById('noVNC_keyboardinput');
                          if (inp) { inp.focus(); inp.click(); }
                        })();
                        """.trimIndent(),
                        null,
                    )
                    webViewRef?.requestFocus()
                },
            ) {
                Icon(Icons.Default.Keyboard, contentDescription = "Keyboard", tint = GrokifyColors.GlowCyan)
            }
            IconButton(onClick = { webViewRef?.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = GrokifyColors.TextPrimary)
            }
            IconButton(
                onClick = {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in browser", tint = GrokifyColors.TextMuted)
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(AndroidColor.parseColor("#05060A"))
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    isFocusable = true
                    isFocusableInTouchMode = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        mediaPlaybackRequiresUserGesture = false
                        // Same viewport rules as CexBot: honor width=device-width.
                        useWideViewPort = true
                        loadWithOverviewMode = false
                        textZoom = 100
                        setSupportZoom(true)
                        builtInZoomControls = false
                        displayZoomControls = false
                        javaScriptCanOpenWindowsAutomatically = true
                        userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                    webChromeClient = WebChromeClient()
                    webViewClient = GbotVncClient(
                        session = session,
                        onStatus = { status = it },
                    )
                    webViewRef = this
                    loadUrl(pageUrl)
                }
            },
            update = { webViewRef = it },
        )
    }
}

private class GbotVncClient(
    private val session: GbotVncRouting.Session?,
    private val onStatus: (String) -> Unit,
) : WebViewClient() {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return true
        val scheme = uri.scheme?.lowercase().orEmpty()
        return scheme != "https" && scheme != "http" && scheme != "wss" && scheme != "ws"
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val sess = session ?: return null
        val raw = request?.url?.toString() ?: return null
        val rewritten = GbotVncRouting.applyRouting(raw, sess)
        if (rewritten == raw) return null
        return fetch(rewritten, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onStatus("Desktop")
        view?.evaluateJavascript(
            """
            (function(){
              var c = document.getElementById('noVNC_connect_button');
              if (c) c.click();
            })();
            """.trimIndent(),
            null,
        )
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        if (request?.isForMainFrame == true) {
            onStatus(error?.description?.toString() ?: "VNC load error")
        }
    }

    private fun fetch(url: String, request: WebResourceRequest): WebResourceResponse? {
        return try {
            val b = Request.Builder().url(url)
            request.requestHeaders.forEach { (k, v) ->
                if (!k.equals("Accept-Encoding", ignoreCase = true)) {
                    b.header(k, v)
                }
            }
            if (request.method.uppercase() != "GET" && request.method.uppercase() != "HEAD") {
                return null
            }
            b.method(request.method, null)
            http.newCall(b.build()).execute().use { resp ->
                val body = resp.body ?: return errorResponse()
                val bytes = body.bytes()
                val ctype = resp.header("Content-Type").orEmpty()
                val mime = ctype.substringBefore(';').trim().ifBlank { guessMime(url) }
                val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
                    .find(ctype)?.groupValues?.getOrNull(1)?.trim()
                    ?: if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) "utf-8" else null
                val headers = HashMap<String, String>()
                resp.headers.forEach { (k, v) ->
                    if (!k.equals("content-encoding", ignoreCase = true) &&
                        !k.equals("transfer-encoding", ignoreCase = true)
                    ) {
                        headers[k] = v
                    }
                }
                if (!headers.keys.any { it.equals("Access-Control-Allow-Origin", ignoreCase = true) }) {
                    headers["Access-Control-Allow-Origin"] = "*"
                }
                WebResourceResponse(
                    mime,
                    charset,
                    resp.code,
                    resp.message.ifBlank { if (resp.isSuccessful) "OK" else "Error" },
                    headers,
                    bytes.inputStream(),
                )
            }
        } catch (_: Exception) {
            errorResponse()
        }
    }

    private fun guessMime(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".js") -> "text/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".ico") -> "image/x-icon"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".html") -> "text/html"
            path.endsWith(".wasm") -> "application/wasm"
            else -> "application/octet-stream"
        }
    }

    private fun errorResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            mapOf("Content-Type" to "text/plain"),
            ByteArray(0).inputStream(),
        )
    }
}
