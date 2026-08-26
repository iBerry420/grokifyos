package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import io.grokify.os.apps.lyre.BoardData
import io.grokify.os.apps.lyre.Frame
import io.grokify.os.apps.lyre.LayerClip
import io.grokify.os.apps.lyre.LyreCache
import io.grokify.os.apps.lyre.LyreClip
import io.grokify.os.apps.lyre.LyreClockTarget
import io.grokify.os.apps.lyre.LyreMovie
import io.grokify.os.apps.lyre.LyrePlayItem
import io.grokify.os.apps.lyre.LyrePlayer
import io.grokify.os.apps.lyre.LyreStore
import io.grokify.os.apps.lyre.StoryboardClip
import io.grokify.os.apps.lyre.lyreClockTarget
import io.grokify.os.apps.lyre.lyreNextVideoClip
import io.grokify.os.apps.lyre.lyreStillsFromPlayItem
import io.grokify.os.ui.theme.GrokifyColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val CHIPS = listOf(
    "scenes" to "Scenes",
    "library" to "Library",
    "bin" to "Bin",
    "activity" to "Activity",
)

@Composable
fun LyreEditor(
    board: BoardData,
    boardId: String,
    cache: LyreCache,
    store: LyreStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember { LyrePlayer(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            player.release()
        }
    }

    val storyboard = remember(board) { LyreClip.movieClips(board.scenes) }
    val duration = remember(storyboard) { storyboard.sumOf { it.length }.toFloat() }
    val programLayers = remember(board) { LyreMovie.movieProgramLayers(board.movie, board.videoLayers) }

    var playhead by remember { mutableFloatStateOf(store.playhead.coerceAtLeast(0f)) }
    var playing by remember { mutableStateOf(false) }
    var chip by remember { mutableStateOf(store.chip.ifBlank { "scenes" }) }
    var museOpen by remember { mutableStateOf(store.museOpen) }
    var switcher by remember { mutableStateOf(false) }
    var stills by remember { mutableStateOf<Map<String, File>>(emptyMap()) }
    var program by remember { mutableStateOf<List<LyrePlayItem>>(emptyList()) }
    var onHold by remember { mutableStateOf(false) }

    LaunchedEffect(board, boardId) {
        val resolved = withContext(Dispatchers.IO) {
            val keys = board.scenes.flatMap { sc -> sc.frames.map { it.src } }
                .filter { it.isNotEmpty() }
                .distinct()
            val map = HashMap<String, File>(keys.size)
            for (key in keys) {
                val f = cache.resolve(boardId, key)
                if (f != null && f.length() > 0L) map[key] = f
            }
            map to LyrePlayer.buildProgram(board, boardId, cache)
        }
        stills = resolved.first
        program = resolved.second
        player.setProgram(resolved.second)
        val t = playhead.coerceIn(0f, duration.coerceAtLeast(0f))
        playhead = t
        applyClock(board, player, t.toDouble(), resume = false, loopClip = store.loopClip) { hold ->
            onHold = hold
            playing = false
        }
    }

    LaunchedEffect(playing, program, board) {
        if (!playing) return@LaunchedEffect
        while (isActive) {
            val item = player.currentItem()
            if (item != null && !onHold) {
                val pos = player.exo.currentPosition / 1000.0
                val t = lyreStillsFromPlayItem(board, item, pos).toFloat()
                playhead = t
                store.playhead = t
                val leftoverId = (lyreClockTarget(board, t.toDouble()) as? LyreClockTarget.Leftover)?.clipId
                player.syncLoop(store.loopClip, leftoverId)
            }
            if (!player.exo.isPlaying && !player.exo.playWhenReady) {
                playing = false
                store.playhead = playhead
                break
            }
            delay(80)
        }
    }

    val current = LyreClip.clipAtTime(storyboard, playhead.toDouble())
    val showStill = onHold || program.isEmpty() || when (val target = lyreClockTarget(board, playhead.toDouble())) {
        is LyreClockTarget.Movie -> program.none { it.id == "lc_movie" }
        is LyreClockTarget.Leftover -> program.none { it.id == target.clipId }
        is LyreClockTarget.Hold -> true
        null -> true
    }

    fun seekTo(t: Double, resume: Boolean) {
        val clamped = t.coerceIn(0.0, duration.toDouble().coerceAtLeast(0.0))
        playhead = clamped.toFloat()
        store.playhead = playhead
        applyClock(board, player, clamped, resume, store.loopClip) { hold ->
            onHold = hold
            playing = resume && !hold
        }
    }

    fun togglePlay() {
        val target = lyreClockTarget(board, playhead.toDouble())
        if (target is LyreClockTarget.Hold) {
            val next = lyreNextVideoClip(board, target.frame.id)
            if (next != null) seekTo(next.start, resume = true)
            return
        }
        if (playing) {
            player.pause()
            playing = false
            store.playhead = playhead
        } else {
            seekTo(playhead.toDouble(), resume = true)
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
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = GrokifyColors.TextPrimary,
                )
            }
            Text(
                board.title.ifBlank { "Untitled" },
                color = GrokifyColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { switcher = true }
                    .padding(end = 8.dp),
            )
            TextButton(
                onClick = {
                    museOpen = !museOpen
                    store.museOpen = museOpen
                },
            ) {
                Text(
                    "Muse",
                    color = if (museOpen) GrokifyColors.GlowRose else GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
            }
        }
        if (museOpen) {
            Text(
                "Muse sheet in a later build",
                color = GrokifyColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        LyrePlayerStage(
            player = player,
            hasVideo = program.isNotEmpty(),
            still = current?.frame,
            stillFile = current?.frame?.src?.let { stills[it] },
            showStill = showStill,
            playing = playing,
            playhead = playhead,
            duration = duration,
            onTogglePlay = { togglePlay() },
        )

        LyreClock(
            clips = storyboard,
            stills = stills,
            playhead = playhead,
            duration = duration,
            onSeek = { seekTo(it, resume = playing && !onHold) },
        )

        LyreStillsRail(
            clips = storyboard,
            stills = stills,
            currentId = current?.frame?.id,
            onSelect = { seekTo(it.start, resume = false) },
        )

        LyreVideoRail(
            clips = programLayers.firstOrNull()?.clips.orEmpty(),
            stills = stills,
            board = board,
            storyboard = storyboard,
            onSelectMovie = {
                val first = storyboard.firstOrNull { LyreMovie.frameInMovie(board.movie, board.videoLayers, it.frame.id) }
                seekTo(first?.start ?: 0.0, resume = false)
            },
            onSelectLeftover = { clip ->
                val sc = clip.linkedFrameId?.let { LyreClip.clipOf(board.scenes, it) }
                if (sc != null) seekTo(sc.start, resume = false)
            },
        )

        if (board.audioLayers.isNotEmpty()) {
            LyreAudioRails(board.audioLayers)
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (chip) {
                "library" -> LyreLibraryPane(board)
                "bin" -> LyreBinPane(board)
                "activity" -> LyreActivityPane()
                else -> LyreScenesPane(board)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CHIPS.forEach { (id, label) ->
                val on = chip == id
                FilterChip(
                    selected = on,
                    onClick = {
                        chip = id
                        store.chip = id
                    },
                    label = { Text(label, fontSize = 12.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GrokifyColors.GlowRose.copy(alpha = 0.22f),
                        selectedLabelColor = GrokifyColors.GlowRose,
                        containerColor = GrokifyColors.PanelSoft,
                        labelColor = GrokifyColors.TextPrimary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = on,
                        borderColor = GrokifyColors.PanelBorder,
                        selectedBorderColor = GrokifyColors.GlowRose,
                    ),
                )
            }
        }
    }

    if (switcher) {
        AlertDialog(
            onDismissRequest = { switcher = false },
            title = { Text("Projects") },
            text = {
                Text("${board.title.ifBlank { "Untitled" }}\nShared with desktop LYRE")
            },
            confirmButton = {
                TextButton(onClick = { switcher = false }) { Text("Close") }
            },
        )
    }
}

private fun applyClock(
    board: BoardData,
    player: LyrePlayer,
    t: Double,
    resume: Boolean,
    loopClip: Boolean,
    onHold: (Boolean) -> Unit,
) {
    when (val target = lyreClockTarget(board, t)) {
        is LyreClockTarget.Hold -> {
            player.pause()
            player.syncLoop(false, null)
            onHold(true)
        }
        is LyreClockTarget.Movie -> {
            val ok = player.seekToItem("lc_movie", target.positionSec)
            if (ok && resume) player.play() else player.pause()
            player.syncLoop(false, null)
            onHold(!ok)
        }
        is LyreClockTarget.Leftover -> {
            val ok = player.seekToItem(target.clipId, target.positionSec)
            if (ok && resume) player.play() else player.pause()
            player.syncLoop(loopClip, target.clipId)
            onHold(!ok)
        }
        null -> {
            player.pause()
            player.syncLoop(false, null)
            onHold(true)
        }
    }
}

@Composable
private fun LyrePlayerStage(
    player: LyrePlayer,
    hasVideo: Boolean,
    still: Frame?,
    stillFile: File?,
    showStill: Boolean,
    playing: Boolean,
    playhead: Float,
    duration: Float,
    onTogglePlay: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .aspectRatio(16f / 9f)
            .background(GrokifyColors.VoidElevated),
    ) {
        if (hasVideo) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player.exo
                        useController = false
                        controllerAutoShow = false
                        hideController()
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    view.player = player.exo
                    view.useController = false
                    view.hideController()
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showStill) {
            if (stillFile != null) {
                AsyncImage(
                    model = stillFile,
                    contentDescription = still?.caption,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(GrokifyColors.Panel),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        still?.caption?.ifBlank { "Still" } ?: "No still",
                        color = GrokifyColors.TextMuted,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        IconButton(
            onClick = onTogglePlay,
            modifier = Modifier
                .align(Alignment.Center)
                .size(56.dp)
                .background(GrokifyColors.Scrim, CircleShape),
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = GrokifyColors.TextPrimary,
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            "${fmtTime(playhead)} / ${fmtTime(duration)}",
            color = GrokifyColors.TextPrimary,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        )
    }
}

@Composable
private fun LyreClock(
    clips: List<StoryboardClip>,
    stills: Map<String, File>,
    playhead: Float,
    duration: Float,
    onSeek: (Double) -> Unit,
) {
    val total = duration.coerceAtLeast(0.1f)
    val sceneStarts = remember(clips) { clips.distinctBy { it.sceneId } }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .padding(horizontal = 8.dp),
        ) {
            sceneStarts.forEach { clip ->
                val x = maxWidth * (clip.start.toFloat() / total)
                Text(
                    clip.sceneTitle,
                    color = GrokifyColors.TextMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset(x = x)
                        .padding(end = 4.dp),
                )
            }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GrokifyColors.Panel)
                .pointerInput(total) {
                    detectTapGestures { offset ->
                        onSeek((offset.x / size.width.toFloat()) * total.toDouble())
                    }
                },
        ) {
            if (clips.isNotEmpty()) {
                Row(Modifier.fillMaxSize()) {
                    clips.forEach { clip ->
                        val w = (clip.length.toFloat() / total).coerceAtLeast(0.02f)
                        val file = stills[clip.frame.src]
                        Box(
                            Modifier
                                .weight(w)
                                .fillMaxHeight()
                                .border(0.5.dp, GrokifyColors.PanelBorder),
                        ) {
                            if (file != null) {
                                AsyncImage(
                                    model = file,
                                    contentDescription = clip.frame.caption,
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(Modifier.fillMaxSize().background(GrokifyColors.PanelSoft))
                            }
                        }
                    }
                }
            }
            val x = maxWidth * (playhead / total).coerceIn(0f, 1f)
            Box(
                Modifier
                    .offset(x = x - 1.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(GrokifyColors.GlowRose),
            )
        }
    }
}

@Composable
private fun LyreStillsRail(
    clips: List<StoryboardClip>,
    stills: Map<String, File>,
    currentId: String?,
    onSelect: (StoryboardClip) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        clips.forEach { clip ->
            val selected = clip.frame.id == currentId
            val file = stills[clip.frame.src]
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        1.5.dp,
                        if (selected) GrokifyColors.GlowRose else GrokifyColors.PanelBorder,
                        RoundedCornerShape(6.dp),
                    )
                    .clickable { onSelect(clip) },
            ) {
                if (file != null) {
                    AsyncImage(
                        model = file,
                        contentDescription = clip.frame.caption,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(GrokifyColors.PanelSoft))
                }
            }
        }
        if (clips.isEmpty()) {
            Text("No stills", color = GrokifyColors.TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LyreVideoRail(
    clips: List<LayerClip>,
    stills: Map<String, File>,
    board: BoardData,
    storyboard: List<StoryboardClip>,
    onSelectMovie: () -> Unit,
    onSelectLeftover: (LayerClip) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (clips.isEmpty()) {
            Text("No video", color = GrokifyColors.TextMuted, fontSize = 12.sp)
            return@Row
        }
        clips.forEach { clip ->
            val locked = clip.id == "lc_movie"
            val poster = posterFile(clip, stills, board, storyboard)
            Box(
                Modifier
                    .height(44.dp)
                    .width(if (locked) 96.dp else 72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(GrokifyColors.Panel)
                    .border(
                        1.dp,
                        if (locked) GrokifyColors.GlowRose else GrokifyColors.PanelBorder,
                        RoundedCornerShape(6.dp),
                    )
                    .clickable { if (locked) onSelectMovie() else onSelectLeftover(clip) },
            ) {
                if (poster != null) {
                    AsyncImage(
                        model = poster,
                        contentDescription = clip.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .background(GrokifyColors.Scrim)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (locked) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = GrokifyColors.GlowRose,
                            modifier = Modifier.size(10.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        clip.name.ifBlank { clip.id },
                        color = GrokifyColors.TextPrimary,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LyreAudioRails(layers: List<io.grokify.os.apps.lyre.MediaLayer>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        layers.forEach { layer ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    layer.name.ifBlank { "Audio" },
                    color = GrokifyColors.TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.width(52.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                layer.clips.forEach { clip ->
                    Text(
                        "${clip.name.ifBlank { clip.id }} · ${"%.1f".format(clip.durationSec)}s",
                        color = GrokifyColors.TextPrimary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .background(GrokifyColors.Panel, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                if (layer.clips.isEmpty()) {
                    Text("—", color = GrokifyColors.TextDim, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun posterFile(
    clip: LayerClip,
    stills: Map<String, File>,
    board: BoardData,
    storyboard: List<StoryboardClip>,
): File? {
    if (clip.id == "lc_movie") {
        val first = storyboard.firstOrNull {
            LyreMovie.frameInMovie(board.movie, board.videoLayers, it.frame.id)
        }
        return first?.frame?.src?.let { stills[it] }
    }
    val sc = clip.linkedFrameId?.let { LyreClip.clipOf(board.scenes, it) }
    return sc?.frame?.src?.let { stills[it] }
}

private fun fmtTime(sec: Float): String {
    val s = sec.coerceAtLeast(0f).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
