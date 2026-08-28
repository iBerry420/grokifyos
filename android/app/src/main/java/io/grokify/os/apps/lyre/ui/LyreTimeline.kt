package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.lyre.BoardData
import io.grokify.os.apps.lyre.LayerClip
import io.grokify.os.apps.lyre.LyreClip
import io.grokify.os.apps.lyre.LyreEdits
import io.grokify.os.apps.lyre.LyreEnvelope
import io.grokify.os.apps.lyre.LyreMovie
import io.grokify.os.apps.lyre.LyreStorageKeys
import io.grokify.os.apps.lyre.LyreWaveform
import io.grokify.os.apps.lyre.MediaLayer
import io.grokify.os.apps.lyre.StoryboardClip
import io.grokify.os.apps.lyre.VolumeEnvelope
import io.grokify.os.ui.theme.GrokifyColors
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal val LYRE_PPS_MIN = 8f
internal val LYRE_PPS_MAX = 96f
private val PIC_H = 36.dp
private val VID_H = 40.dp
private val AUD_H = 48.dp
private val CLOCK_H = 14.dp
private val HANDLE = 16.dp

private sealed class Grab {
    data class Move(val id: String, val dx: Float, val dy: Float) : Grab()
    data class Trim(val id: String, val left: Boolean, val dx: Float) : Grab()
}

@Composable
fun LyreTimeline(
    board: BoardData,
    stills: Map<String, File>,
    storyboard: List<StoryboardClip>,
    programClips: List<LayerClip>,
    playhead: Float,
    duration: Float,
    pps: Float,
    onPps: (Float) -> Unit,
    onSeek: (Double) -> Unit,
    onPlayheadDragStart: () -> Unit = {},
    onPlayheadDrag: (Double) -> Unit = onSeek,
    onPlayheadDragEnd: () -> Unit = {},
    onMenuStill: (StoryboardClip) -> Unit,
    onMenuVideo: (LayerClip, locked: Boolean) -> Unit,
    onMenuAudio: (MediaLayer, LayerClip) -> Unit,
    onMoveStill: (StoryboardClip, Double) -> Unit,
    onMoveVideo: (LayerClip, Double) -> Unit,
    onMoveAudio: (LayerClip, Double, Int) -> Unit,
    onAddAudioTrack: () -> Unit = {},
    onTrimStillLeft: (StoryboardClip, Double) -> Unit,
    onTrimStillRight: (StoryboardClip, Double) -> Unit,
    onTrimClip: (LayerClip, Double, Double) -> Unit,
    onEnvelopePoint: (LayerClip, Int, Double, Double) -> Unit,
    onEnvelopeAdd: (LayerClip, Double, Double) -> Unit,
    onUploadMedia: () -> Unit = {},
    onGenerateImage: () -> Unit = {},
    onInsertLibrary: () -> Unit = {},
) {
    val total = duration.coerceAtLeast(0.1f)
    val density = LocalDensity.current
    val trackWidth = with(density) { (total * pps).toDp() }.coerceAtLeast(120.dp)
    val scroll = rememberScrollState()
    val sceneStarts = remember(storyboard) { storyboard.distinctBy { it.sceneId } }
    val laneLayers = remember(board.audioLayers) {
        if (board.audioLayers.isNotEmpty()) {
            board.audioLayers
        } else {
            listOf(MediaLayer(id = "ly_a_placeholder", kind = "audio", name = "A1", clips = emptyList()))
        }
    }
    var grab by remember { mutableStateOf<Grab?>(null) }
    var draggingHead by remember { mutableStateOf(false) }
    var pressCount by remember { mutableIntStateOf(0) }
    val playheadHold = remember { mutableFloatStateOf(playhead) }
    playheadHold.floatValue = playhead
    val interacting = grab != null || draggingHead || pressCount > 0
    fun beginPress() {
        pressCount++
    }
    fun endPress() {
        pressCount = (pressCount - 1).coerceAtLeast(0)
    }

    fun relatedIds(id: String): Set<String> {
        val ids = mutableSetOf(id)
        val still = storyboard.firstOrNull { it.frame.id == id }
        val video = programClips.firstOrNull { it.id == id }
        val fid = still?.frame?.id ?: video?.linkedFrameId
        if (!fid.isNullOrEmpty()) {
            ids.add(fid)
            programClips.filter { it.linkedFrameId == fid }.forEach { ids.add(it.id) }
        }
        return ids
    }
    fun chrome(id: String): Triple<Boolean, String?, Boolean> {
        val g = grab
        val trim = g as? Grab.Trim
        val related = relatedIds(id)
        val trimming = trim != null && (trim.id == id || trim.id in related)
        val label = when {
            trim != null && trimming && trim.left -> "TRIM IN"
            trim != null && trimming -> "TRIM OUT"
            else -> null
        }
        return Triple(trimming, label, grab != null && !trimming)
    }
    val ppsPx = pps
    val audioLaneCount = max(1, laneLayers.size)
    val tracksH = CLOCK_H + PIC_H + VID_H + AUD_H * audioLaneCount

    fun liveStart(id: String, base: Double): Double {
        val g = grab as? Grab.Trim ?: return base
        if (g.id != id && g.id !in relatedIds(id)) return base
        return if (g.left) (base + g.dx / ppsPx).toDouble() else base
    }

    fun liveDur(id: String, start: Double, dur: Double): Double {
        val g = grab as? Grab.Trim ?: return dur
        if (g.id != id && g.id !in relatedIds(id)) return dur
        val end = start + dur
        return if (g.left) {
            (end - liveStart(id, start)).coerceAtLeast(0.1)
        } else {
            (dur + g.dx / ppsPx).toDouble().coerceAtLeast(0.1)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Tracks",
                color = GrokifyColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { onPps((pps / 1.35f).coerceAtLeast(LYRE_PPS_MIN)) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Zoom out", tint = GrokifyColors.TextPrimary)
            }
            Text(
                "${pps.roundToInt()} px/s",
                color = GrokifyColors.TextMuted,
                fontSize = 10.sp,
            )
            IconButton(
                onClick = { onPps((pps * 1.35f).coerceAtMost(LYRE_PPS_MAX)) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Zoom in", tint = GrokifyColors.TextPrimary)
            }
            TextButton(
                onClick = onUploadMedia,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text("Upload", color = GrokifyColors.TextPrimary, fontSize = 12.sp)
            }
            TextButton(
                onClick = onGenerateImage,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text("Generate", color = GrokifyColors.GlowAmber, fontSize = 12.sp)
            }
            IconButton(onClick = onInsertLibrary, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.LibraryAdd, contentDescription = "Insert from library", tint = GrokifyColors.TextPrimary)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(28.dp).padding(top = CLOCK_H)) {
                LaneLabel("Pic", PIC_H)
                LaneLabel("V1", VID_H)
                laneLayers.forEachIndexed { i, layer ->
                    LaneLabel(layer.name.ifBlank { "A${i + 1}" }, AUD_H)
                }
                IconButton(
                    onClick = onAddAudioTrack,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add audio track",
                        tint = GrokifyColors.GlowAmber,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .horizontalScroll(scroll, enabled = !interacting)
                    .pointerInput(pps, total, audioLaneCount) {
                        detectTrackGestures(
                            onPinch = { zoom ->
                                if (zoom != 1f) onPps((pps * zoom).coerceIn(LYRE_PPS_MIN, LYRE_PPS_MAX))
                            },
                            onTwoFinger = { off ->
                                val t = (off.x / ppsPx).toDouble()
                                val y = off.y
                                val clock = CLOCK_H.toPx()
                                val pic = PIC_H.toPx()
                                val vid = VID_H.toPx()
                                val aud = AUD_H.toPx()
                                when {
                                    y < clock + pic -> {
                                        val clip = storyboard.firstOrNull {
                                            t >= it.start && t < it.start + it.length
                                        }
                                        if (clip != null) onMenuStill(clip)
                                    }
                                    y < clock + pic + vid -> {
                                        val clip = programClips.firstOrNull {
                                            t >= it.startSec && t < it.startSec + it.durationSec
                                        }
                                        if (clip != null) {
                                            onMenuVideo(
                                                clip,
                                                clip.id == "lc_movie" ||
                                                    LyreMovie.isStitchedMember(board.movie, clip.id),
                                            )
                                        }
                                    }
                                    else -> {
                                        val lane = ((y - clock - pic - vid) / aud).toInt()
                                            .coerceIn(0, (laneLayers.size - 1).coerceAtLeast(0))
                                        val layer = laneLayers.getOrNull(lane)
                                        val clip = layer?.clips?.firstOrNull {
                                            t >= it.startSec && t < it.startSec + it.durationSec
                                        }
                                        if (layer != null && clip != null) {
                                            val home = board.audioLayers.firstOrNull { l ->
                                                l.clips.any { it.id == clip.id }
                                            } ?: layer
                                            onMenuAudio(home, clip)
                                        }
                                    }
                                }
                            },
                        )
                    },
            ) {
                Column(Modifier.width(trackWidth)) {
                    Box(Modifier.fillMaxWidth().height(CLOCK_H)) {
                        sceneStarts.forEach { clip ->
                            val x = with(density) { (clip.start.toFloat() * pps).toDp() }
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
                    Lane(PIC_H) {
                        storyboard.forEach { clip ->
                            val id = clip.frame.id
                            val locked = LyreEdits.isPictureLocked(board, id)
                            val start = liveStart(id, clip.start)
                            val dur = liveDur(id, clip.start, clip.length)
                            val (active, label, dim) = chrome(id)
                            TimedClip(
                                clipId = id,
                                startSec = start,
                                durationSec = dur,
                                pps = pps,
                                height = PIC_H,
                                locked = locked,
                                accent = GrokifyColors.PanelBorder,
                                dragging = active,
                                dragLabel = label,
                                dimmed = dim,
                                onPress = { beginPress() },
                                onPressEnd = { endPress() },
                                onMove = if (locked) {
                                    null
                                } else {
                                    { dx, _ -> grab = Grab.Move(id, dx, 0f) }
                                },
                                onMoveEnd = if (locked) {
                                    null
                                } else {
                                    {
                                        val g = grab as? Grab.Move
                                        if (g != null && kotlin.math.abs(g.dx) > 6f) {
                                            onMoveStill(clip, (clip.start + g.dx / ppsPx).coerceAtLeast(0.0))
                                        }
                                        grab = null
                                    }
                                },
                                onTrim = if (locked) {
                                    null
                                } else {
                                    { left, dx -> grab = Grab.Trim(id, left, dx) }
                                },
                                onTrimEnd = if (locked) {
                                    null
                                } else {
                                    { left ->
                                        val g = grab as? Grab.Trim
                                        grab = null
                                        if (g == null || kotlin.math.abs(g.dx) < 4f) return@TimedClip
                                        val ns = if (left) clip.start + g.dx / ppsPx else clip.start
                                        val ne = if (left) {
                                            clip.start + clip.length
                                        } else {
                                            clip.start + clip.length + g.dx / ppsPx
                                        }
                                        if (left) {
                                            onTrimStillLeft(clip, ns.toDouble())
                                        } else {
                                            onTrimStillRight(clip, ne.toDouble())
                                        }
                                    }
                                },
                            ) {
                                LyreStillImage(
                                    file = LyreStorageKeys.file(stills, clip.frame.src),
                                    contentDescription = clip.frame.caption,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize(),
                                    fallback = clip.frame.caption,
                                )
                                if (locked) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = GrokifyColors.GlowRose,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(3.dp)
                                            .size(10.dp),
                                    )
                                }
                            }
                        }
                    }
                    Lane(VID_H) {
                        programClips.forEach { clip ->
                            val locked = clip.id == "lc_movie" ||
                                LyreMovie.isStitchedMember(board.movie, clip.id)
                            val poster = videoPoster(clip, stills, board, storyboard)
                            val start = liveStart(clip.id, clip.startSec)
                            val dur = liveDur(clip.id, clip.startSec, clip.durationSec)
                            val (active, label, dim) = chrome(clip.id)
                            TimedClip(
                                clipId = clip.id,
                                startSec = start,
                                durationSec = dur,
                                pps = pps,
                                height = VID_H,
                                locked = locked,
                                accent = GrokifyColors.GlowCyan,
                                dragging = active,
                                dragLabel = label,
                                dimmed = dim,
                                onPress = { beginPress() },
                                onPressEnd = { endPress() },
                                onMove = if (locked) {
                                    null
                                } else {
                                    { dx, _ -> grab = Grab.Move(clip.id, dx, 0f) }
                                },
                                onMoveEnd = if (locked) {
                                    null
                                } else {
                                    {
                                        val g = grab as? Grab.Move
                                        if (g != null && kotlin.math.abs(g.dx) > 6f) {
                                            onMoveVideo(clip, (clip.startSec + g.dx / ppsPx).coerceAtLeast(0.0))
                                        }
                                        grab = null
                                    }
                                },
                                onTrim = if (locked) {
                                    null
                                } else {
                                    { left, dx -> grab = Grab.Trim(clip.id, left, dx) }
                                },
                                onTrimEnd = if (locked) {
                                    null
                                } else {
                                    { left ->
                                        val g = grab as? Grab.Trim
                                        grab = null
                                        if (g == null || kotlin.math.abs(g.dx) < 4f) return@TimedClip
                                        val ns = if (left) {
                                            clip.startSec + g.dx / ppsPx
                                        } else {
                                            clip.startSec
                                        }
                                        val ne = if (left) {
                                            clip.startSec + clip.durationSec
                                        } else {
                                            clip.startSec + clip.durationSec + g.dx / ppsPx
                                        }
                                        onTrimClip(clip, ns.toDouble(), ne.toDouble())
                                    }
                                },
                            ) {
                                LyreStillImage(
                                    file = poster,
                                    contentDescription = clip.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize(),
                                    fallback = clip.name,
                                )
                                Row(
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .background(GrokifyColors.Scrim)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
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
                    laneLayers.forEachIndexed { lane, layer ->
                        val tint = if (lane % 2 == 0) GrokifyColors.GlowAmber else GrokifyColors.GlowViolet
                        Lane(AUD_H) {
                            layer.clips.forEach { clip ->
                                val muted = (clip.volume ?: 1.0) <= 0.001
                                val start = liveStart(clip.id, clip.startSec)
                                val dur = liveDur(clip.id, clip.startSec, clip.durationSec)
                                val env = LyreEnvelope.parseClip(clip)
                                val peaks = LyreWaveform.parseClip(clip)?.let {
                                    LyreWaveform.slice(
                                        it,
                                        clip.trimInSec ?: 0.0,
                                        clip.durationSec,
                                        clip.sourceDurationSec,
                                    )
                                }
                                val (active, label, dim) = chrome(clip.id)
                                TimedClip(
                                    clipId = clip.id,
                                    startSec = start,
                                    durationSec = dur,
                                    pps = pps,
                                    height = AUD_H,
                                    accent = if (muted) GrokifyColors.TextDim else tint,
                                    fill = (if (muted) GrokifyColors.PanelSoft else tint).copy(alpha = 0.28f),
                                    dragging = active,
                                    dragLabel = label,
                                    dimmed = dim,
                                    overlay = {
                                        if (env != null && env.on) {
                                            EnvelopePaint(
                                                envelope = env,
                                                color = GrokifyColors.TextPrimary,
                                                modifier = Modifier.matchParentSize(),
                                                onMovePoint = { index, t, v -> onEnvelopePoint(clip, index, t, v) },
                                                onAddPoint = { t, v -> onEnvelopeAdd(clip, t, v) },
                                            )
                                        }
                                    },
                                    onPress = { beginPress() },
                                    onPressEnd = { endPress() },
                                    onMove = { dx, dy -> grab = Grab.Move(clip.id, dx, dy) },
                                    onMoveEnd = {
                                        val g = grab as? Grab.Move
                                        if (g != null && (kotlin.math.abs(g.dx) > 6f || kotlin.math.abs(g.dy) > 8f)) {
                                            val laneH = with(density) { AUD_H.toPx() }
                                            val dest = (lane + (g.dy / laneH).roundToInt())
                                                .coerceIn(0, laneLayers.size)
                                            onMoveAudio(
                                                clip,
                                                (clip.startSec + g.dx / ppsPx).coerceAtLeast(0.0),
                                                dest,
                                            )
                                        }
                                        grab = null
                                    },
                                    onTrim = { left, dx -> grab = Grab.Trim(clip.id, left, dx) },
                                    onTrimEnd = { left ->
                                        val g = grab as? Grab.Trim
                                        grab = null
                                        if (g == null || kotlin.math.abs(g.dx) < 4f) return@TimedClip
                                        val ns = if (left) clip.startSec + g.dx / ppsPx else clip.startSec
                                        val ne = if (left) {
                                            clip.startSec + clip.durationSec
                                        } else {
                                            clip.startSec + clip.durationSec + g.dx / ppsPx
                                        }
                                        onTrimClip(clip, ns.toDouble(), ne.toDouble())
                                    },
                                ) {
                                    WaveformPaint(
                                        peaks = peaks,
                                        color = (if (muted) GrokifyColors.TextDim else tint).copy(alpha = 0.9f),
                                        modifier = Modifier.matchParentSize(),
                                    )
                                    Text(
                                        clip.name.ifBlank { clip.id },
                                        color = GrokifyColors.TextPrimary,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                val moving = grab as? Grab.Move
                if (moving != null) {
                    MoveGhost(
                        grab = moving,
                        storyboard = storyboard,
                        programClips = programClips,
                        laneLayers = laneLayers,
                        stills = stills,
                        board = board,
                        pps = pps,
                    )
                }
                val x = with(density) { (playhead.coerceIn(0f, total) * pps).toDp() }
                val headTint = if (draggingHead) GrokifyColors.GlowAmber else GrokifyColors.GlowRose
                Box(
                    Modifier
                        .offset(x = x - 16.dp)
                        .width(32.dp)
                        .height(tracksH)
                        .zIndex(6f)
                        .pointerInput(pps, total) {
                            var acc = 0f
                            var origin = 0f
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    acc = 0f
                                    origin = playheadHold.floatValue
                                    draggingHead = true
                                    onPlayheadDragStart()
                                },
                                onDragCancel = {
                                    draggingHead = false
                                    onPlayheadDragEnd()
                                },
                                onDragEnd = {
                                    draggingHead = false
                                    onPlayheadDragEnd()
                                },
                                onHorizontalDrag = { change, dx ->
                                    change.consume()
                                    acc += dx
                                    onPlayheadDrag(
                                        (origin + acc / ppsPx).toDouble().coerceIn(0.0, total.toDouble()),
                                    )
                                },
                            )
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .size(14.dp)
                            .background(headTint, CircleShape)
                            .border(2.dp, GrokifyColors.Void, CircleShape),
                    )
                    Box(
                        Modifier
                            .padding(top = 12.dp)
                            .width(if (draggingHead) 3.dp else 2.dp)
                            .fillMaxHeight()
                            .background(headTint),
                    )
                    if (draggingHead) {
                        Text(
                            formatPlayhead(playhead),
                            color = GrokifyColors.Void,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = (-16).dp)
                                .background(headTint, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LaneLabel(label: String, height: Dp) {
    Box(
        Modifier
            .width(28.dp)
            .height(height + 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            color = GrokifyColors.TextDim,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun Lane(
    height: Dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(vertical = 1.dp)
            .background(GrokifyColors.Panel, RoundedCornerShape(4.dp)),
        content = content,
    )
}

@Composable
private fun TimedClip(
    clipId: String,
    startSec: Double,
    durationSec: Double,
    pps: Float,
    height: Dp,
    locked: Boolean = false,
    accent: Color = GrokifyColors.PanelBorder,
    fill: Color? = null,
    dragging: Boolean = false,
    dragLabel: String? = null,
    dimmed: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    onTap: (() -> Unit)? = null,
    onPress: () -> Unit = {},
    onPressEnd: () -> Unit = {},
    onMove: ((dx: Float, dy: Float) -> Unit)?,
    onMoveEnd: (() -> Unit)?,
    onTrim: ((left: Boolean, dx: Float) -> Unit)?,
    onTrimEnd: ((left: Boolean) -> Unit)?,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val x = with(density) { (startSec.toFloat() * pps).toDp() }
    val w = with(density) { (durationSec.toFloat() * pps).toDp() }.coerceAtLeast(10.dp)
    val shape = RoundedCornerShape(4.dp)
    val stroke = if (dragging) GrokifyColors.GlowAmber else accent
    val tapState = rememberUpdatedState(onTap)
    val moveState = rememberUpdatedState(onMove)
    val moveEndState = rememberUpdatedState(onMoveEnd)
    val pressState = rememberUpdatedState(onPress)
    val pressEndState = rememberUpdatedState(onPressEnd)
    Box(
        Modifier
            .offset(x = x)
            .width(w)
            .height(height)
            .graphicsLayer {
                scaleX = if (dragging) 1.03f else 1f
                scaleY = if (dragging) 1.08f else 1f
                alpha = when {
                    dragging -> 1f
                    dimmed -> 0.42f
                    else -> 1f
                }
                shadowElevation = if (dragging) 12f else 0f
            }
            .then(if (fill != null) Modifier.background(fill, shape) else Modifier.background(GrokifyColors.PanelSoft, shape))
            .border(if (dragging) 2.dp else 1.dp, stroke, shape)
            .then(
                if (onTap != null) {
                    Modifier.pointerInput(clipId) {
                        detectTapGestures(onTap = { tapState.value?.invoke() })
                    }
                } else {
                    Modifier
                },
            )
            .then(
                if (onMove != null && onMoveEnd != null) {
                    Modifier.pointerInput(clipId) {
                        detectHoldDrag(
                            holdMs = 140L,
                            onPress = { pressState.value() },
                            onDrag = { acc -> moveState.value?.invoke(acc.x, acc.y) },
                            onEnd = {
                                moveEndState.value?.invoke()
                                pressEndState.value()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(shape),
            content = content,
        )
        overlay()
        if (dragging && !dragLabel.isNullOrEmpty()) {
            Text(
                dragLabel,
                color = GrokifyColors.Void,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(GrokifyColors.GlowAmber, RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (!locked && onTrim != null && onTrimEnd != null) {
            TrimHandle(
                left = true,
                hot = dragging && dragLabel == "TRIM IN",
                onPress = onPress,
                onDrag = { onTrim(true, it) },
                onEnd = {
                    onTrimEnd(true)
                    onPressEnd()
                },
            )
            TrimHandle(
                left = false,
                hot = dragging && dragLabel == "TRIM OUT",
                onPress = onPress,
                onDrag = { onTrim(false, it) },
                onEnd = {
                    onTrimEnd(false)
                    onPressEnd()
                },
            )
        }
    }
}

@Composable
private fun BoxScope.TrimHandle(
    left: Boolean,
    hot: Boolean = false,
    onPress: () -> Unit = {},
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
) {
    var acc by remember { mutableStateOf(0f) }
    val pressState = rememberUpdatedState(onPress)
    val endState = rememberUpdatedState(onEnd)
    val dragState = rememberUpdatedState(onDrag)
    Box(
        Modifier
            .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
            .width(HANDLE)
            .fillMaxHeight()
            .pointerInput(left) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        acc = 0f
                        pressState.value()
                    },
                    onDragCancel = { endState.value() },
                    onDragEnd = { endState.value() },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        acc += dx
                        dragState.value(acc)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(if (hot) 4.dp else 3.dp)
                .height(if (hot) 22.dp else 18.dp)
                .background(
                    if (hot) GrokifyColors.GlowAmber else GrokifyColors.TextPrimary.copy(alpha = 0.7f),
                    RoundedCornerShape(1.dp),
                ),
        )
    }
}

@Composable
private fun WaveformPaint(
    peaks: io.grokify.os.apps.lyre.WaveformPeaks?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val maxArr = peaks?.max ?: return@Canvas
        if (maxArr.isEmpty() || size.width <= 0f) return@Canvas
        val minArr = peaks.min
        val cols = size.width.toInt().coerceAtLeast(1)
        val sampled = LyreWaveform.downsample(maxArr, cols)
        val sampledMin = minArr?.let { LyreWaveform.downsample(it.map { v -> abs(v) }.toFloatArray(), cols) }
        val mid = size.height / 2f
        for (x in sampled.indices) {
            val hi = sampled[x].coerceIn(0f, 1f)
            val lo = sampledMin?.getOrNull(x)?.coerceIn(0f, 1f) ?: hi
            val y1 = mid - hi * mid
            val y2 = mid + lo * mid
            drawLine(
                color = color,
                start = Offset(x.toFloat(), y1),
                end = Offset(x.toFloat(), y2.coerceAtLeast(y1 + 1f)),
                strokeWidth = 1.2f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun EnvelopePaint(
    envelope: VolumeEnvelope,
    color: Color,
    modifier: Modifier = Modifier,
    onMovePoint: (Int, Double, Double) -> Unit,
    @Suppress("UNUSED_PARAMETER") onAddPoint: (Double, Double) -> Unit,
) {
    val pts = envelope.points
    val pad = with(LocalDensity.current) { 10.dp.toPx() }
    val handlePx = with(LocalDensity.current) { 22.dp.toPx() }
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        Canvas(Modifier.matchParentSize()) {
            if (pts.size < 2 || size.width <= 0f) return@Canvas
            val path = Path()
            val fill = Path()
            val steps = 24
            var started = false
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                for (s in 0..steps) {
                    val u = s / steps.toFloat()
                    val t = a.t + (b.t - a.t) * u
                    val v = LyreEnvelope.sample(pts, t)
                    val pos = LyreEnvelope.handlePos(t, v, size.width, size.height, pad)
                    if (!started) {
                        path.moveTo(pos.x, pos.y)
                        fill.moveTo(pos.x, size.height - pad)
                        fill.lineTo(pos.x, pos.y)
                        started = true
                    } else {
                        path.lineTo(pos.x, pos.y)
                        fill.lineTo(pos.x, pos.y)
                    }
                }
            }
            if (started) {
                val last = LyreEnvelope.handlePos(pts.last().t, pts.last().v, size.width, size.height, pad)
                fill.lineTo(last.x, size.height - pad)
                fill.close()
                drawPath(fill, color.copy(alpha = 0.16f))
            }
            drawPath(path, color, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
        }
        pts.forEachIndexed { i, p ->
            var liveT by remember(p.t, i) { mutableStateOf(p.t) }
            var liveV by remember(p.v, i) { mutableStateOf(p.v) }
            var dragging by remember(i) { mutableStateOf(false) }
            val pos = LyreEnvelope.handlePos(liveT, liveV, w, h, pad)
            Box(
                Modifier
                    .offset {
                        IntOffset((pos.x - handlePx / 2f).toInt(), (pos.y - handlePx / 2f).toInt())
                    }
                    .size(22.dp)
                    .zIndex(if (dragging) 3f else 1f)
                    .pointerInput(i, w, h, pad) {
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDrag = { change, amount ->
                                change.consume()
                                val next = LyreEnvelope.handlePos(liveT, liveV, w, h, pad)
                                liveT = LyreEnvelope.tFromX(next.x + amount.x, w, pad)
                                liveV = LyreEnvelope.vFromY(next.y + amount.y, h, pad)
                            },
                            onDragEnd = {
                                dragging = false
                                onMovePoint(i, liveT, liveV)
                            },
                            onDragCancel = {
                                dragging = false
                                onMovePoint(i, liveT, liveV)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (dragging) 18.dp else 14.dp)
                        .background(if (dragging) GrokifyColors.GlowAmber else color, CircleShape)
                        .border(2.dp, GrokifyColors.Void, CircleShape),
                )
            }
            if (dragging) {
                Text(
                    "${(liveV * 100).roundToInt()}%",
                    color = GrokifyColors.Void,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .offset {
                            IntOffset((pos.x - 14f).toInt(), (pos.y - handlePx - 8f).toInt())
                        }
                        .background(GrokifyColors.GlowAmber, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun MoveGhost(
    grab: Grab.Move,
    storyboard: List<StoryboardClip>,
    programClips: List<LayerClip>,
    laneLayers: List<MediaLayer>,
    stills: Map<String, File>,
    board: BoardData,
    pps: Float,
) {
    val density = LocalDensity.current
    val grabbedStill = storyboard.firstOrNull { it.frame.id == grab.id }
    val grabbedVideo = programClips.firstOrNull { it.id == grab.id }
    val pairId = grabbedStill?.frame?.id ?: grabbedVideo?.linkedFrameId
    val still = grabbedStill ?: pairId?.let { fid -> storyboard.firstOrNull { it.frame.id == fid } }
    val video = grabbedVideo ?: pairId?.let { fid -> programClips.firstOrNull { it.linkedFrameId == fid } }
    val audioHit = if (still == null && video == null) {
        laneLayers.mapIndexed { i, layer ->
            i to layer.clips.firstOrNull { it.id == grab.id }
        }.firstOrNull { it.second != null }
    } else {
        null
    }
    val audio = audioHit?.second
    if (still != null) {
        GhostClip(
            startSec = still.start,
            durationSec = still.length,
            y = CLOCK_H,
            height = PIC_H,
            dx = grab.dx,
            pps = pps,
            poster = LyreStorageKeys.file(stills, still.frame.src),
            caption = still.frame.caption,
        )
    }
    if (video != null) {
        GhostClip(
            startSec = video.startSec,
            durationSec = video.durationSec,
            y = CLOCK_H + PIC_H,
            height = VID_H,
            dx = grab.dx,
            pps = pps,
            poster = videoPoster(video, stills, board, storyboard),
            caption = video.name,
        )
    }
    if (audio != null) {
        val y = CLOCK_H + PIC_H + VID_H + AUD_H * (audioHit?.first ?: 0) + with(density) { grab.dy.toDp() }
        GhostClip(
            startSec = audio.startSec,
            durationSec = audio.durationSec,
            y = y,
            height = AUD_H,
            dx = grab.dx,
            pps = pps,
            poster = null,
            caption = audio.name,
        )
    }
}

@Composable
private fun GhostClip(
    startSec: Double,
    durationSec: Double,
    y: Dp,
    height: Dp,
    dx: Float,
    pps: Float,
    poster: File?,
    caption: String,
) {
    val density = LocalDensity.current
    val x = with(density) { (startSec.toFloat() * pps + dx).toDp() }
    val w = with(density) { (durationSec.toFloat() * pps).toDp() }.coerceAtLeast(10.dp)
    Box(
        Modifier
            .offset(x = x, y = y)
            .width(w)
            .height(height)
            .zIndex(8f)
            .graphicsLayer {
                alpha = 0.94f
                shadowElevation = 16f
            }
            .background(GrokifyColors.PanelSoft, RoundedCornerShape(4.dp))
            .border(2.dp, GrokifyColors.GlowAmber, RoundedCornerShape(4.dp)),
    ) {
        if (poster != null) {
            LyreStillImage(
                file = poster,
                contentDescription = caption,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                fallback = caption,
            )
        }
        Text(
            "MOVE",
            color = GrokifyColors.Void,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.Center)
                .background(GrokifyColors.GlowAmber, RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun formatPlayhead(sec: Float): String {
    val s = sec.coerceAtLeast(0f)
    val m = (s / 60f).toInt()
    val r = s - m * 60
    return "%d:%05.2f".format(m, r)
}

private fun videoPoster(
    clip: LayerClip,
    stills: Map<String, File>,
    board: BoardData,
    storyboard: List<StoryboardClip>,
): File? {
    if (clip.id == "lc_movie") {
        val first = storyboard.firstOrNull {
            LyreMovie.frameInMovie(board.movie, board.videoLayers, it.frame.id)
        }
        return LyreStorageKeys.file(stills, first?.frame?.src)
    }
    LyreStorageKeys.file(stills, LyreStorageKeys.posterSrc(board, clip.src))?.let { return it }
    val sc = clip.linkedFrameId?.let { LyreClip.clipOf(board.scenes, it) }
    return LyreStorageKeys.file(stills, sc?.frame?.src)
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectHoldDrag(
    holdMs: Long,
    onPress: () -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val origin = down.position
        val startTime = down.uptimeMillis
        val slop = viewConfiguration.touchSlop
        var armed = false
        var dragging = false
        onPress()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                onEnd()
                break
            }
            val dt = change.uptimeMillis - startTime
            val delta = change.position - origin
            if (!armed && dt >= holdMs) {
                armed = true
            }
            if (armed && (dragging || delta.getDistance() > slop)) {
                dragging = true
                change.consume()
                onDrag(delta)
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTrackGestures(
    onPinch: (Float) -> Unit,
    onTwoFinger: (Offset) -> Unit,
) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val start = first.position
        val slop = viewConfiguration.touchSlop
        var two = false
        var moved = false
        var lastSpan = 0f
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                two = true
                val span = (pressed[0].position - pressed[1].position).getDistance()
                if (lastSpan == 0f) lastSpan = span
                if (abs(span - lastSpan) > slop) {
                    moved = true
                    val zoom = if (lastSpan > 0f) span / lastSpan else 1f
                    onPinch(zoom)
                }
                lastSpan = span
                pressed.forEach { it.consume() }
            } else if (pressed.size == 1 && !two) {
                val p = pressed[0]
                if ((p.position - start).getDistance() > slop) moved = true
            }
            if (pressed.isEmpty()) {
                if (two && !moved) onTwoFinger(start)
                break
            }
        }
    }
}
