package io.grokify.os.apps.discord

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import io.grokify.os.R
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val LocalDiscordOpenMedia = compositionLocalOf<((DiscordAttachment) -> Unit)?> { null }

@Composable
internal fun DiscordMediaBlock(
    att: DiscordAttachment,
    headers: Map<String, String> = emptyMap(),
    large: Boolean = false,
    onOpen: ((DiscordAttachment) -> Unit)? = null,
) {
    val open = onOpen ?: LocalDiscordOpenMedia.current
    Box(
        Modifier.then(
            if (open != null) Modifier.clickable { open(att) } else Modifier,
        ),
    ) {
        when (att.kind) {
            "gif", "image" -> DiscordImageBlock(att, headers, large, animated = att.kind == "gif")
            "video" -> DiscordVideoPreview(att, large, headers)
            "audio" -> DiscordAudioPreview(att)
            else -> DiscordFileChip(att)
        }
    }
}

@Composable
private fun DiscordImageBlock(
    att: DiscordAttachment,
    headers: Map<String, String>,
    large: Boolean,
    animated: Boolean,
) {
    if (att.url.isBlank()) {
        DiscordFileChip(att)
        return
    }
    AsyncImage(
        model = rememberDiscordImageRequest(att.url, headers, crossfade = false),
        contentDescription = att.filename,
        modifier = if (large) {
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(10.dp))
        } else {
            Modifier
                .size(if (animated) 120.dp else 88.dp)
                .clip(RoundedCornerShape(8.dp))
        },
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun DiscordVideoPreview(
    att: DiscordAttachment,
    large: Boolean,
    headers: Map<String, String> = emptyMap(),
) {
    val height = if (large) 180.dp else 110.dp
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(GrokifyColors.PanelSoft),
        contentAlignment = Alignment.Center,
    ) {
        DiscordVideoPoster(att, headers)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play video",
                tint = GrokifyColors.GlowCyan,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text("VIDEO", color = GrokifyColors.GlowCyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            if (att.filename.isNotBlank()) {
                Text(
                    att.filename,
                    color = GrokifyColors.TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun DiscordVideoPoster(att: DiscordAttachment, headers: Map<String, String>) {
    val thumb = att.thumbUrl
    if (thumb.isBlank()) return
    AsyncImage(
        model = rememberDiscordImageRequest(thumb, headers, crossfade = false),
        contentDescription = att.filename,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun DiscordAudioPreview(att: DiscordAttachment) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GrokifyColors.PanelSoft)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Play audio", tint = GrokifyColors.GlowMint, modifier = Modifier.size(22.dp))
        Icon(Icons.Default.AudioFile, null, tint = GrokifyColors.GlowMint, modifier = Modifier.size(16.dp))
        Text(att.filename.ifBlank { "audio" }, color = GrokifyColors.TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DiscordFileChip(att: DiscordAttachment) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(GrokifyColors.PanelSoft)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = GrokifyColors.TextMuted, modifier = Modifier.size(14.dp))
        Text(att.filename.ifBlank { att.contentType.ifBlank { "file" } }, color = GrokifyColors.TextMuted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
internal fun DiscordMediaThumb(
    att: DiscordAttachment,
    headers: Map<String, String> = emptyMap(),
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GrokifyColors.PanelSoft),
        contentAlignment = Alignment.Center,
    ) {
        when (att.kind) {
            "image", "gif" -> {
                if (att.url.isNotBlank()) {
                    AsyncImage(
                        model = rememberDiscordImageRequest(att.url, headers, crossfade = false),
                        contentDescription = att.filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            "video" -> {
                DiscordVideoPoster(att, headers)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PlayArrow, null, tint = GrokifyColors.GlowCyan, modifier = Modifier.size(28.dp))
                    Text("VIDEO", color = GrokifyColors.GlowCyan, fontSize = 11.sp)
                }
            }
            "audio" -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AudioFile, null, tint = GrokifyColors.GlowMint, modifier = Modifier.size(22.dp))
                    Text("AUDIO", color = GrokifyColors.GlowMint, fontSize = 11.sp)
                }
            }
            else -> Text(att.filename.take(18), color = GrokifyColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(6.dp))
        }
        if (!att.playable || att.url.isBlank()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(GrokifyColors.Void.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("STALE", color = GrokifyColors.GlowRose, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun DiscordMediaPane(
    att: DiscordAttachment,
    headers: Map<String, String>,
    onBack: () -> Unit,
    onOpenUser: (DiscordProfileKey) -> Unit = {},
    onDownload: (DiscordAttachment) -> Unit = {},
    downloading: Boolean = false,
) {
    BackHandler(onBack = onBack)
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
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = GrokifyColors.TextPrimary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    att.filename.ifBlank { att.kind.ifBlank { "Media" } },
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val kindLabel = att.kind.uppercase(Locale.US)
                val sizeLabel = if (att.size > 0L) discordFormatBytes(att.size) else ""
                DiscordMeta(listOf(kindLabel, sizeLabel).filter { it.isNotBlank() }.joinToString(" · "))
            }
            IconButton(onClick = { onDownload(att) }, enabled = att.url.isNotBlank() && !downloading) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Download",
                    tint = if (att.url.isNotBlank()) GrokifyColors.GlowCyan else GrokifyColors.TextDim,
                )
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(GrokifyColors.PanelSoft.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!att.playable || att.url.isBlank()) {
                DiscordMeta("Not cached locally and the Discord CDN link expired.")
            } else {
                when (att.kind) {
                    "image", "gif" -> DiscordZoomableImage(att, headers)
                    "video" -> DiscordExoVideo(att, headers)
                    "audio" -> DiscordExoAudio(att, headers)
                    else -> DiscordFileChip(att)
                }
            }
        }
        DiscordMediaMetaCard(att, headers, onOpenUser)
    }
}

@Composable
internal fun DiscordZoomableBox(
    cacheKey: String,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    var scale by remember(cacheKey) { mutableFloatStateOf(1f) }
    var offset by remember(cacheKey) { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(cacheKey) {
                val slop = viewConfiguration.touchSlop
                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                var lastTapUptime = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val downTime = down.uptimeMillis
                    var travel = Offset.Zero
                    var owned = discordImageOwnsPointer(1, scale)
                    var takenByParent = false
                    var event: PointerEvent
                    do {
                        event = awaitPointerEvent()
                        if (!owned && event.changes.any { it.isConsumed }) {
                            takenByParent = true
                            break
                        }
                        val liveCount = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        travel += panChange
                        if (!owned) {
                            owned = discordImageOwnsPointer(liveCount, scale)
                        }
                        if (owned) {
                            val next = (scale * zoomChange).coerceIn(1f, 8f)
                            scale = next
                            offset = if (next <= 1.01f) Offset.Zero else offset + panChange
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!owned && !takenByParent && travel.getDistance() < slop) {
                        if (lastTapUptime > 0L && downTime - lastTapUptime <= doubleTapMs) {
                            if (scale > DISCORD_IMAGE_ZOOM_EPS) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                            lastTapUptime = 0L
                        } else {
                            lastTapUptime = downTime
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

@Composable
internal fun DiscordZoomableImage(att: DiscordAttachment, headers: Map<String, String>) {
    val cacheKey = discordStableMediaKey(att.url).ifBlank { att.url }
    val req = rememberDiscordImageRequest(att.url, headers, crossfade = false)
    DiscordZoomableBox(cacheKey) { zoomMod ->
        AsyncImage(
            model = req,
            contentDescription = att.filename,
            contentScale = ContentScale.Fit,
            modifier = zoomMod,
        )
    }
}

@Composable
internal fun rememberDiscordPlayer(
    url: String,
    headers: Map<String, String>,
    playWhenReady: Boolean = true,
    mimeType: String = "",
): Pair<ExoPlayer, String?> {
    val ctx = LocalContext.current
    val cacheKey = listOf(discordStableMediaKey(url).ifBlank { url }, mimeType).joinToString("|")
    var error by remember(cacheKey) { mutableStateOf<String?>(null) }
    val player = remember {
        val renderers = DefaultRenderersFactory(ctx.applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        ExoPlayer.Builder(ctx.applicationContext).setRenderersFactory(renderers).build()
    }
    val headerSig = headers.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }
    DisposableEffect(cacheKey, headerSig) {
        error = null
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("GrokifyOS-Discord")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        if (headers.isNotEmpty()) {
            http.setDefaultRequestProperties(headers)
        }
        val item = MediaItem.Builder()
            .setUri(url)
            .apply {
                if (mimeType.isNotBlank()) setMimeType(mimeType)
            }
            .build()
        val source = ProgressiveMediaSource.Factory(
            http,
            DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
        ).createMediaSource(item)
        val listener = object : Player.Listener {
            override fun onPlayerError(exception: PlaybackException) {
                error = exception.localizedMessage ?: "Can't play this file"
                runCatching { player.stop() }
            }
        }
        player.addListener(listener)
        runCatching {
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = playWhenReady
        }.onFailure { e ->
            error = e.message ?: "Can't play this file"
        }
        onDispose {
            player.removeListener(listener)
            runCatching {
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
            }
        }
    }
    DisposableEffect(player) {
        onDispose {
            runCatching { player.release() }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                runCatching { player.pause() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(playWhenReady) {
        runCatching {
            player.playWhenReady = playWhenReady
            if (!playWhenReady) player.pause()
        }
    }
    return player to error
}

@Composable
internal fun DiscordExoVideo(
    att: DiscordAttachment,
    headers: Map<String, String>,
    active: Boolean = true,
    controls: Boolean = true,
) {
    val (player, error) = rememberDiscordPlayer(
        att.url,
        headers,
        playWhenReady = active,
        mimeType = discordPlayerMime(att.contentType, att.filename),
    )
    if (error != null) {
        DiscordPlaybackError(att, error)
        return
    }
    val cacheKey = discordStableMediaKey(att.url).ifBlank { att.url }
    val playerView: @Composable (Modifier) -> Unit = { viewMod ->
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.discord_player_view, null, false) as PlayerView).apply {
                    this.player = player
                    useController = controls
                    controllerAutoShow = controls
                    isClickable = controls
                    isFocusable = controls
                    if (!controls) hideController()
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.player = player
                view.useController = controls
                view.controllerAutoShow = controls
                view.isClickable = controls
                view.isFocusable = controls
                if (!controls) view.hideController()
            },
            onRelease = { view -> view.player = null },
            modifier = viewMod,
        )
    }
    if (controls) {
        playerView(Modifier.fillMaxSize())
    } else {
        DiscordZoomableBox(cacheKey, content = playerView)
    }
}

@Composable
internal fun DiscordExoAudio(att: DiscordAttachment, headers: Map<String, String>, active: Boolean = true) {
    val (player, error) = rememberDiscordPlayer(
        att.url,
        headers,
        playWhenReady = active,
        mimeType = discordPlayerMime(att.contentType, att.filename),
    )
    if (error != null) {
        DiscordPlaybackError(att, error)
        return
    }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition.coerceAtLeast(0L)
            val d = player.duration
            dur = if (d > 0L) d else 0L
            playing = player.isPlaying
            delay(200)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.AudioFile, null, tint = GrokifyColors.GlowMint, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(att.filename.ifBlank { "audio" }, color = GrokifyColors.TextPrimary, fontSize = 14.sp, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        Slider(
            value = if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f,
            onValueChange = { frac ->
                if (dur > 0L) player.seekTo((frac * dur).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = GrokifyColors.GlowMint,
                activeTrackColor = GrokifyColors.GlowMint,
                inactiveTrackColor = GrokifyColors.PanelBorder,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DiscordMeta(formatPlayerTime(pos))
            DiscordMeta(if (dur > 0L) formatPlayerTime(dur) else "—")
        }
        IconButton(
            onClick = {
                runCatching {
                    if (player.isPlaying) player.pause() else player.play()
                }
            },
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = GrokifyColors.GlowMint,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun DiscordPlaybackError(att: DiscordAttachment, error: String) {
    Column(
        Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Can't play this file", color = GrokifyColors.GlowRose, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        DiscordMeta(att.contentType.ifBlank { att.filename })
        DiscordMeta(error)
    }
}

@Composable
private fun DiscordMediaMetaCard(
    att: DiscordAttachment,
    headers: Map<String, String>,
    onOpenUser: (DiscordProfileKey) -> Unit,
) {
    val key = discordProfileKey(id = att.userId, discordId = att.discordId)
    val name = att.displayName.ifBlank { att.username }.ifBlank { "unknown" }
    Column(
        Modifier
            .fillMaxWidth()
            .background(GrokifyColors.Panel)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (key.id.isNotBlank()) Modifier.clickable { onOpenUser(key) } else Modifier,
                ),
        ) {
            DiscordAvatar(att.avatar, name, size = 36, headers = headers) {
                if (key.id.isNotBlank()) onOpenUser(key)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                val userLine = listOfNotNull(
                    att.username.takeIf { it.isNotBlank() }?.let { "@$it" },
                    att.discordId.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (userLine.isNotBlank()) DiscordMeta(userLine)
            }
        }
        val place = listOf(
            att.guildName.ifBlank { att.guildId },
            att.channelName.ifBlank { att.channelId }.let { if (it.isBlank()) "" else "#$it" },
        ).filter { it.isNotBlank() }.joinToString(" · ")
        if (place.isNotBlank()) DiscordMeta(place)
        val whenAbs = formatDiscordTimestamp(att.createdAtMs)
        if (whenAbs != "—") {
            DiscordMeta("$whenAbs · ${formatDiscordWhen(att.createdAtMs)}")
        }
        val ids = buildList {
            if (att.discordAttachmentId.isNotBlank()) add("file ${att.discordAttachmentId}")
            else if (att.id > 0) add("file #${att.id}")
            if (att.messageId.isNotBlank()) add("msg ${att.messageId}")
            if (att.channelId.isNotBlank()) add("ch ${att.channelId}")
            if (att.guildId.isNotBlank()) add("guild ${att.guildId}")
        }
        if (ids.isNotEmpty()) DiscordMeta(ids.joinToString(" · "))
        val tech = listOf(
            att.contentType,
            if (att.size > 0L) discordFormatBytes(att.size) else "",
            if (att.local) "cached" else "remote",
            if (att.playable) "playable" else "stale",
        ).filter { it.isNotBlank() }
        if (tech.isNotEmpty()) DiscordMeta(tech.joinToString(" · "))
    }
}

internal fun formatDiscordTimestamp(ms: Long): String {
    if (ms <= 0L) return "—"
    return try {
        SimpleDateFormat("MMM d, yyyy · HH:mm:ss", Locale.getDefault()).format(Date(ms))
    } catch (_: Exception) {
        "—"
    }
}

private fun formatPlayerTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val m = total / 60L
    val s = total % 60L
    return "%d:%02d".format(m, s)
}
