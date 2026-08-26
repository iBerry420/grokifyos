package io.grokify.os.apps.lyre.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.grokify.os.apps.lyre.LyreApi
import io.grokify.os.apps.lyre.LyreProject
import io.grokify.os.apps.lyre.lyreWatchGrokmeUrl
import io.grokify.os.apps.lyre.lyreWatchProxyUrl
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyreWatch(
    project: LyreProject?,
    watchBusy: Boolean,
    watchError: String?,
    api: LyreApi,
    onPublish: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isPublic = project?.visibility == "public"
    val token = project?.watchToken?.takeIf { it.matches(WATCH_TOKEN) }
    var playUrl by remember(token, isPublic) { mutableStateOf<String?>(null) }

    LaunchedEffect(token, isPublic) {
        val t = token
        if (!isPublic || t == null) {
            playUrl = null
            return@LaunchedEffect
        }
        val grokme = lyreWatchGrokmeUrl(t)
        val proxy = lyreWatchProxyUrl(t)
        playUrl = withContext(Dispatchers.IO) {
            if (api.publicWatchReachable(grokme)) grokme else proxy
        }
    }

    val qrUrl = when {
        !isPublic -> null
        playUrl != null -> playUrl
        token != null -> lyreWatchProxyUrl(token)
        else -> null
    }
    val qrBmp = remember(qrUrl) {
        qrUrl?.let { runCatching { lyreQrBitmap(it) }.getOrNull() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GrokifyColors.VoidElevated,
        contentColor = GrokifyColors.TextPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                project?.name?.ifBlank { "Watch" } ?: "Watch",
                color = GrokifyColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                if (isPublic) "Public watch link" else "Private — QR after Public",
                color = GrokifyColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WatchChip("Public", selected = isPublic, enabled = !watchBusy) {
                    onPublish(true)
                }
                WatchChip("Private", selected = !isPublic, enabled = !watchBusy) {
                    onPublish(false)
                }
            }
            if (watchBusy) {
                Text(
                    "Publishing…",
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            val err = watchError
            if (!err.isNullOrBlank()) {
                Text(
                    err,
                    color = GrokifyColors.GlowRose,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (isPublic && qrUrl != null) {
                Text(
                    qrUrl,
                    color = GrokifyColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (qrBmp != null) {
                    Image(
                        bitmap = qrBmp.asImageBitmap(),
                        contentDescription = "Watch QR",
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .size(180.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                }
                val url = playUrl
                if (url != null) {
                    LyreWatchPlayer(
                        url = url,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .aspectRatio(16f / 9f)
                            .background(GrokifyColors.Void),
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, fontSize = 12.sp, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = GrokifyColors.GlowRose.copy(alpha = 0.22f),
            selectedLabelColor = GrokifyColors.GlowRose,
            containerColor = GrokifyColors.PanelSoft,
            labelColor = GrokifyColors.TextPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = GrokifyColors.PanelBorder,
            selectedBorderColor = GrokifyColors.GlowRose,
        ),
    )
}

@Composable
private fun LyreWatchPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context.applicationContext).build()
    }
    DisposableEffect(player, url) {
        val factory = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(context.applicationContext),
        )
        player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(Uri.parse(url))))
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                controllerAutoShow = true
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
}

private fun lyreQrBitmap(content: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bmp.setPixel(
                x,
                y,
                if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
            )
        }
    }
    return bmp
}

private val WATCH_TOKEN = Regex("^[a-f0-9]{32}$")
