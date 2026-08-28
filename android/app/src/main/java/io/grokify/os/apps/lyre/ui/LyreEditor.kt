package io.grokify.os.apps.lyre.ui

import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.grokify.os.apps.lyre.BoardData
import io.grokify.os.apps.lyre.Frame
import io.grokify.os.apps.lyre.LayerClip
import io.grokify.os.apps.lyre.LyreActivity
import io.grokify.os.apps.lyre.LyreActivityFirstOpen
import io.grokify.os.apps.lyre.LyreActivityLine
import io.grokify.os.apps.lyre.LyreActivitySync
import io.grokify.os.apps.lyre.LyreApi
import io.grokify.os.apps.lyre.LyreHead
import io.grokify.os.apps.lyre.LyrePoll
import io.grokify.os.apps.lyre.LyreSavePoll
import io.grokify.os.apps.lyre.LyreAudioEngine
import io.grokify.os.apps.lyre.LyreBoardCodec
import io.grokify.os.apps.lyre.LyreCache
import io.grokify.os.apps.lyre.LyreClip
import io.grokify.os.apps.lyre.LyreClockTarget
import io.grokify.os.apps.lyre.LyreEdits
import io.grokify.os.apps.lyre.LyreEnvelope
import io.grokify.os.apps.lyre.LyreImagine
import io.grokify.os.apps.lyre.LyreImagineClient
import io.grokify.os.apps.lyre.LyreImagineJob
import io.grokify.os.apps.lyre.LyreImagineMode
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.apps.lyre.LyrePlayItem
import io.grokify.os.apps.lyre.LyrePlayer
import io.grokify.os.apps.lyre.LyrePreview
import io.grokify.os.apps.lyre.LyreStorageKeys
import io.grokify.os.apps.lyre.LyreProject
import io.grokify.os.apps.lyre.LyreStore
import io.grokify.os.apps.lyre.LyreTransport
import io.grokify.os.apps.lyre.LyreUploads
import io.grokify.os.apps.lyre.LyreWaveformDecoder
import io.grokify.os.apps.lyre.MediaLayer
import io.grokify.os.apps.lyre.lyreClockTarget
import io.grokify.os.apps.lyre.lyreJumpTime
import io.grokify.os.ui.theme.GrokifyColors
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val CHIPS = listOf(
    "scenes" to "Scenes",
    "library" to "Library",
    "bin" to "Bin",
    "activity" to "Activity",
)

private sealed class TrackMenu {
    data class Still(val frame: Frame) : TrackMenu()
    data class Video(val clip: LayerClip, val locked: Boolean) : TrackMenu()
    data class Audio(val layer: MediaLayer, val clip: LayerClip) : TrackMenu()
    data class Library(val item: LyreLibraryItem) : TrackMenu()
    data class PickScene(val item: LyreLibraryItem) : TrackMenu()
    data object InsertLibrary : TrackMenu()
}

@Composable
fun LyreEditor(
    board: BoardData,
    boardId: String,
    project: LyreProject,
    cache: LyreCache,
    store: LyreStore,
    api: LyreApi,
    initialUpdatedAt: String = "",
    onBack: () -> Unit,
    onBoardChange: (BoardData) -> Unit = {},
    onProjectOpened: (LyreProject, BoardData, String) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val player = remember { LyrePlayer(context.applicationContext) }
    val audio = remember { LyreAudioEngine(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var editorResumed by remember { mutableStateOf(true) }
    DisposableEffect(player, audio, lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> editorResumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    editorResumed = false
                    if (event == Lifecycle.Event.ON_STOP) {
                        player.pause()
                        audio.pause()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            player.release()
            audio.release()
        }
    }

    var live by remember { mutableStateOf(board) }
    LaunchedEffect(board) { live = board }

    val storyboard = remember(live) { LyreClip.movieClips(live.scenes) }
    val duration = remember(live) { LyreStorageKeys.timelineDuration(live).toFloat() }
    val pictureClips = remember(live) { LyreEdits.pictureVideoClips(live) }

    var playhead by remember { mutableFloatStateOf(store.playhead.coerceAtLeast(0f)) }
    var playing by remember { mutableStateOf(false) }
    var chip by remember { mutableStateOf("") }
    var museOpen by remember { mutableStateOf(store.museOpen) }
    var switcher by remember { mutableStateOf(false) }
    var stills by remember { mutableStateOf<Map<String, File>>(emptyMap()) }
    var program by remember { mutableStateOf<List<LyrePlayItem>>(emptyList()) }
    var onHold by remember { mutableStateOf(false) }
    var activityTick by remember { mutableStateOf(0) }
    var pps by remember { mutableFloatStateOf(store.timelinePps) }
    var menu by remember { mutableStateOf<TrackMenu?>(null) }
    var imagine by remember { mutableStateOf<LyreImagineJob?>(null) }
    var genStatus by remember { mutableStateOf<String?>(null) }
    var genBusy by remember { mutableStateOf(false) }
    var extraRefFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var scrubbing by remember { mutableStateOf(false) }
    val activity = remember(boardId) { LyreActivity(cache.activityFile(boardId)) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var pendingSave by remember(boardId) { mutableStateOf(false) }
    var boardDirty by remember(boardId) { mutableStateOf(false) }
    var lastSeenUpdatedAt by remember(boardId) {
        mutableStateOf(initialUpdatedAt.ifBlank { cache.readBoardStamp(boardId).orEmpty() })
    }
    var lastActivityBytes by remember(boardId) { mutableStateOf(-1L) }
    var serverBanner by remember(boardId) { mutableStateOf<String?>(null) }
    var pollReady by remember(boardId) { mutableStateOf(false) }
    val playEpoch = remember { AtomicInteger(0) }
    val liveRef = rememberUpdatedState(live)
    val stillsRef = rememberUpdatedState(stills)
    val durationRef = rememberUpdatedState(duration)

    fun logActivity(
        type: String,
        summary: String,
        sceneId: String? = null,
        frameId: String? = null,
        clipId: String? = null,
    ) {
        val line = LyreActivityLine(
            ts = System.currentTimeMillis(),
            type = type,
            projectId = store.projectId,
            sceneId = sceneId,
            frameId = frameId,
            clipId = clipId,
            summary = summary,
            actor = "phone",
        )
        activity.append(line)
        activityTick++
        scope.launch(Dispatchers.IO) {
            runCatching { api.activityAppend(boardId, line) }
        }
    }

    suspend fun pullActivity() {
        val bytes = withContext(Dispatchers.IO) {
            val json = api.activity(boardId)
            if (!json.optBoolean("ok", false)) return@withContext null
            activity.replaceFromServer(LyreActivitySync.linesFromJson(json))
            LyreHead.fromJson(api.head(boardId)).activityBytes
        }
        if (bytes != null) lastActivityBytes = bytes
        activityTick++
    }

    suspend fun reloadFromServer() {
        val json = withContext(Dispatchers.IO) { api.board(boardId) }
        if (!json.optBoolean("ok", false)) {
            Toast.makeText(context, json.optString("error").ifBlank { "reload_failed" }, Toast.LENGTH_SHORT).show()
            return
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val stamp = json.optString("updated_at")
        withContext(Dispatchers.IO) {
            cache.writeBoardJson(boardId, data)
            cache.writeBoardStamp(boardId, stamp)
        }
        live = LyreBoardCodec.decode(data)
        onBoardChange(live)
        lastSeenUpdatedAt = stamp
        boardDirty = false
        serverBanner = null
        pullActivity()
    }

    fun scheduleSave() {
        val current = saveJob
        if (current != null && current.isActive) {
            pendingSave = true
            return
        }
        saveJob = scope.launch {
            delay(700)
            do {
                pendingSave = false
                val snapshot = live
                val stamp = lastSeenUpdatedAt
                val resp = withContext(Dispatchers.IO) {
                    val json = LyreBoardCodec.encode(snapshot)
                    cache.writeBoardJson(boardId, json)
                    api.saveBoard(store.projectId, json, stamp)
                }
                val queued = pendingSave
                when (LyrePoll.onSaveResponse(resp.optBoolean("ok", false), resp.optString("error"), queued)) {
                    LyreSavePoll.SAVED -> {
                        val nextStamp = resp.optString("updated_at")
                        if (nextStamp.isNotEmpty()) {
                            lastSeenUpdatedAt = nextStamp
                            withContext(Dispatchers.IO) { cache.writeBoardStamp(boardId, nextStamp) }
                        }
                        boardDirty = false
                        serverBanner = null
                    }
                    LyreSavePoll.RETRY -> {
                        val nextStamp = resp.optString("updated_at")
                        if (nextStamp.isNotEmpty()) {
                            lastSeenUpdatedAt = nextStamp
                            withContext(Dispatchers.IO) { cache.writeBoardStamp(boardId, nextStamp) }
                        }
                        pendingSave = true
                    }
                    LyreSavePoll.CONFLICT_RELOAD -> {
                        boardDirty = false
                        Toast.makeText(context, "conflict", Toast.LENGTH_SHORT).show()
                        reloadFromServer()
                        saveJob = null
                        return@launch
                    }
                    LyreSavePoll.KEEP_DIRTY -> {
                        saveJob = null
                        return@launch
                    }
                }
            } while (pendingSave)
            saveJob = null
        }
    }

    fun commit(next: BoardData, summary: String, type: String = "edit") {
        live = next
        onBoardChange(next)
        boardDirty = true
        logActivity(type, summary)
        scheduleSave()
    }

    LaunchedEffect(boardId) {
        pollReady = false
        val seenAtStart = lastSeenUpdatedAt
        val boardAtOpen = live
        data class OpenSync(val stamp: String, val bytes: Long)
        val sync = withContext(Dispatchers.IO) {
            val headJson = api.head(boardId)
            val head = LyreHead.fromJson(headJson)
            var stamp = seenAtStart
            if (stamp.isEmpty() && head.updatedAt.isNotEmpty()) {
                stamp = head.updatedAt
                cache.writeBoardStamp(boardId, stamp)
            }
            val localNonEmpty = !activity.isEmpty()
            var bytes = head.activityBytes
            when (LyreActivitySync.firstOpenPlan(head.activityBytes, localNonEmpty)) {
                LyreActivityFirstOpen.PUSH_LOCAL -> {
                    api.activityAppend(boardId, activity.readAll())
                    val pulled = api.activity(boardId)
                    activity.replaceFromServer(LyreActivitySync.linesFromJson(pulled))
                    bytes = LyreHead.fromJson(api.head(boardId)).activityBytes
                }
                LyreActivityFirstOpen.PULL_SERVER -> {
                    val pulled = api.activity(boardId)
                    activity.replaceFromServer(LyreActivitySync.linesFromJson(pulled))
                    bytes = head.activityBytes
                }
                LyreActivityFirstOpen.SEED_LOCAL -> {
                    activity.seedFromBoardIfEmpty(boardAtOpen, store.projectId)
                    val seeded = activity.readAll()
                    if (seeded.isNotEmpty()) {
                        api.activityAppend(boardId, seeded)
                        val pulled = api.activity(boardId)
                        activity.replaceFromServer(LyreActivitySync.linesFromJson(pulled))
                        bytes = LyreHead.fromJson(api.head(boardId)).activityBytes
                    } else {
                        bytes = 0L
                    }
                }
            }
            OpenSync(stamp, bytes)
        }
        if (sync.stamp.isNotEmpty()) lastSeenUpdatedAt = sync.stamp
        lastActivityBytes = sync.bytes
        activityTick++
        pollReady = true
        logActivity("open", "Opened ${live.title.ifBlank { "Untitled" }}")
    }

    LaunchedEffect(boardId, editorResumed, pollReady) {
        if (!pollReady) return@LaunchedEffect
        while (isActive) {
            delay(LyrePoll.INTERVAL_MS)
            if (!editorResumed) continue
            if (!LyrePoll.shouldFetchHead(saveJob != null, boardDirty, scrubbing, genBusy)) {
                continue
            }
            val head = withContext(Dispatchers.IO) {
                LyreHead.fromJson(api.head(boardId))
            }
            val decision = LyrePoll.decide(
                saveInFlight = saveJob != null,
                boardDirty = boardDirty,
                lastSeenUpdatedAt = lastSeenUpdatedAt,
                lastActivityBytes = lastActivityBytes,
                head = head,
            )
            if (decision.banner != null) {
                serverBanner = decision.banner
            }
            if (decision.reloadBoard) {
                reloadFromServer()
            } else if (decision.pullActivity) {
                pullActivity()
            }
        }
    }

    LaunchedEffect(boardId) {
        val map = ConcurrentHashMap<String, File>()
        stills.forEach { (k, v) -> map[k] = v }
        val imgSem = Semaphore(8)
        val audSem = Semaphore(2)
        suspend fun pull(keys: List<String>, sem: Semaphore) {
            coroutineScope {
                keys.map { key ->
                    async(Dispatchers.IO) {
                        if (LyreStorageKeys.file(map, key) != null) return@async
                        sem.withPermit {
                            val f = cache.resolve(boardId, key) ?: return@withPermit
                            LyreStorageKeys.index(map, key, f)
                            val snap = HashMap(map)
                            withContext(Dispatchers.Main) { stills = snap }
                        }
                    }
                }.awaitAll()
            }
        }
        snapshotFlow {
            LyreStorageKeys.imageKeys(live) to LyreStorageKeys.audioKeys(live)
        }.collect { (images, audios) ->
            pull(images, imgSem)
            pull(audios, audSem)
        }
    }

    val videoSig = remember(live) {
        buildString {
            append(live.movie?.src.orEmpty())
            live.movie?.parts?.forEach { part ->
                append('#').append(part.clipId)
            }
            live.videoLayers.forEach { layer ->
                layer.clips.forEach { clip ->
                    append('|').append(clip.id).append(':').append(clip.src)
                }
            }
        }
    }
    LaunchedEffect(boardId, videoSig) {
        val built = withContext(Dispatchers.IO) { LyrePlayer.buildProgram(live, boardId, cache) }
        val same = built.size == program.size && built.zip(program).all { (a, b) ->
            a.id == b.id && a.file.absolutePath == b.file.absolutePath
        }
        if (!same) {
            program = built
            player.setProgram(built)
        }
    }

    LaunchedEffect(boardId, stills.size) {
        if (stills.isEmpty()) return@LaunchedEffect
        val (waved, filled) = withContext(Dispatchers.IO) { LyreWaveformDecoder.fillMissing(live, stills) }
        if (filled <= 0) return@LaunchedEffect
        commit(waved, "Set $filled waveforms", "cache")
    }

    fun haltPlayback() {
        playEpoch.incrementAndGet()
        playing = false
        player.invalidatePlay()
        audio.pause()
        store.playhead = playhead
    }

    fun syncTransport(t: Double, sessionPlaying: Boolean) {
        val board = liveRef.value
        val files = stillsRef.value
        val target = lyreClockTarget(board, t)
        val current = player.currentItem()
        val pos = player.exo.currentPosition / 1000.0
        val ended = player.ended()
        val gen = player.playGeneration
        val wantVideo = LyreTransport.wantVideoPlay(sessionPlaying, target, current, ended)
        val tid = LyreTransport.targetId(target)
        val preloadId = LyrePreview.preloadTargetId(board, t)
        if (preloadId != null) player.preloadItem(preloadId)
        when (target) {
            is LyreClockTarget.Hold, null -> {
                player.pause()
                onHold = true
            }
            is LyreClockTarget.Movie -> {
                val promote = LyrePreview.shouldPromoteRam(
                    playing = sessionPlaying,
                    targetId = tid,
                    frontId = current?.id,
                    ended = ended,
                    ramId = player.ramItemId(),
                    ramReady = tid != null && player.ramReady(tid),
                )
                val prepared = player.prepared()
                if (promote && tid != null && player.promoteRam(tid)) {
                    onHold = false
                } else if (
                    tid != null &&
                    LyrePreview.shouldSeekFront(
                        promoteRam = promote,
                        currentId = current?.id,
                        currentPos = pos,
                        targetId = tid,
                        targetPos = target.positionSec,
                        prepared = prepared,
                        seekInFlight = player.seekPending(tid),
                    )
                ) {
                    onHold = !player.seekToItem(tid, target.positionSec)
                } else {
                    onHold = player.seekPending(tid ?: "")
                }
                player.exo.volume = 1f
                if (wantVideo) {
                    if (!player.wantedPlaying) player.play(gen)
                } else if (player.wantedPlaying) {
                    player.pause()
                }
                player.syncLoop(false, null)
            }
            is LyreClockTarget.Leftover -> {
                val promote = LyrePreview.shouldPromoteRam(
                    playing = sessionPlaying,
                    targetId = tid,
                    frontId = current?.id,
                    ended = ended,
                    ramId = player.ramItemId(),
                    ramReady = tid != null && player.ramReady(tid),
                )
                val prepared = player.prepared()
                if (promote && tid != null && player.promoteRam(tid)) {
                    onHold = false
                } else if (
                    tid != null &&
                    LyrePreview.shouldSeekFront(
                        promoteRam = promote,
                        currentId = current?.id,
                        currentPos = pos,
                        targetId = tid,
                        targetPos = target.positionSec,
                        prepared = prepared,
                        seekInFlight = player.seekPending(tid),
                    )
                ) {
                    onHold = !player.seekToItem(tid, target.positionSec)
                } else {
                    onHold = player.seekPending(tid ?: "")
                }
                val muted = board.scenes.asSequence().flatMap { it.frames.asSequence() }
                    .firstOrNull { frame ->
                        board.videoLayers.asSequence().flatMap { it.clips.asSequence() }
                            .any { it.id == target.clipId && it.linkedFrameId == frame.id }
                    }?.videoMuted == true
                player.exo.volume = if (muted) 0f else 1f
                if (wantVideo) {
                    if (!player.wantedPlaying) player.play(gen)
                } else if (player.wantedPlaying) {
                    player.pause()
                }
                player.syncLoop(store.loopClip, target.clipId)
            }
        }
        audio.sync(t, sessionPlaying, board.audioLayers, files)
    }

    fun seekTo(t: Double, resume: Boolean) {
        val clamped = t.coerceIn(0.0, durationRef.value.toDouble().coerceAtLeast(0.0))
        playhead = clamped.toFloat()
        store.playhead = playhead
        if (scrubbing) {
            syncTransport(clamped, false)
            return
        }
        if (resume) {
            playing = true
            syncTransport(clamped, true)
        } else {
            if (playing) haltPlayback()
            syncTransport(clamped, false)
        }
    }

    fun togglePlay() {
        if (playing) {
            haltPlayback()
            syncTransport(playhead.toDouble(), false)
        } else {
            seekTo(playhead.toDouble(), resume = true)
        }
    }

    LaunchedEffect(playing) {
        val epoch = playEpoch.get()
        fun halted(): Boolean = playEpoch.get() != epoch
        if (!playing) {
            player.pause()
            audio.pause()
            audio.sync(playhead.toDouble(), false, liveRef.value.audioLayers, stillsRef.value)
            return@LaunchedEffect
        }
        var last = SystemClock.elapsedRealtime()
        while (isActive) {
            if (halted()) {
                player.pause()
                audio.pause()
                break
            }
            if (scrubbing) {
                last = SystemClock.elapsedRealtime()
                delay(40)
                continue
            }
            val board = liveRef.value
            val now = SystemClock.elapsedRealtime()
            val dt = ((now - last).coerceAtLeast(0L) / 1000.0).coerceAtMost(0.25)
            last = now
            val item = player.currentItem()
            val pos = player.exo.currentPosition / 1000.0
            val target = lyreClockTarget(board, playhead.toDouble())
            val follow = LyreTransport.followPlayer(
                target,
                item,
                player.exo.isPlaying,
                player.ended(),
            )
            val next = LyreTransport.nextPlayhead(
                board = board,
                playhead = playhead.toDouble(),
                dt = dt,
                duration = durationRef.value.toDouble(),
                item = item,
                playerPos = pos,
                follow = follow,
            )
            playhead = next.toFloat()
            if (playhead >= durationRef.value) {
                playhead = durationRef.value
                store.playhead = playhead
                haltPlayback()
                break
            }
            if (halted()) {
                player.pause()
                audio.pause()
                break
            }
            syncTransport(playhead.toDouble(), true)
            store.playhead = playhead
            delay(40)
        }
    }

    val current = LyreClip.clipAtTime(storyboard, playhead.toDouble())
    val clockTarget = lyreClockTarget(live, playhead.toDouble())
    val videoOnScreen = LyreTransport.itemMatches(clockTarget, player.currentItem()) &&
        player.prepared() &&
        !player.ended()
    val showStill = LyrePreview.coverWithStill(clockTarget, playing, videoOnScreen)

    fun handleMenu(id: String) {
        val m = menu ?: return
        when (m) {
            is TrackMenu.Still -> when (id) {
                "play" -> seekTo(
                    LyreClip.clipOf(live.scenes, m.frame.id)?.start ?: playhead.toDouble(),
                    resume = true,
                )
                "loop" -> {
                    store.loopClip = !store.loopClip
                    logActivity("ui", if (store.loopClip) "Loop on" else "Loop off", frameId = m.frame.id)
                }
                "gen_next" -> {
                    imagine = LyreImagineJob(
                        LyreImagineMode.NEXT_STILL,
                        frameId = m.frame.id,
                        title = m.frame.caption.ifBlank { m.frame.id },
                    )
                    extraRefFiles = emptyList()
                }
                "edit_still" -> {
                    imagine = LyreImagineJob(
                        LyreImagineMode.EDIT_STILL,
                        frameId = m.frame.id,
                        title = m.frame.caption.ifBlank { m.frame.id },
                    )
                    extraRefFiles = emptyList()
                }
                "gen_video" -> {
                    imagine = LyreImagineJob(
                        LyreImagineMode.GEN_VIDEO,
                        frameId = m.frame.id,
                        title = m.frame.caption.ifBlank { m.frame.id },
                    )
                    extraRefFiles = emptyList()
                }
            }
            is TrackMenu.Video -> when (id) {
                "play" -> seekTo(m.clip.startSec, resume = true)
                "loop" -> {
                    store.loopClip = !store.loopClip
                    logActivity("ui", if (store.loopClip) "Loop on" else "Loop off", clipId = m.clip.id)
                }
                "mute" -> {
                    val nextMuted = m.clip.linkedFrameId?.let { fid ->
                        live.scenes.flatMap { it.frames }.firstOrNull { it.id == fid }?.videoMuted != true
                    } ?: true
                    commit(LyreEdits.setVideoMuted(live, m.clip.id, nextMuted), if (nextMuted) "Muted clip" else "Unmuted clip")
                }
                "edit_video" -> {
                    imagine = LyreImagineJob(
                        LyreImagineMode.EDIT_VIDEO,
                        frameId = m.clip.linkedFrameId,
                        clipId = m.clip.id,
                        title = m.clip.name.ifBlank { m.clip.id },
                    )
                    extraRefFiles = emptyList()
                }
                "remove" -> commit(LyreEdits.removeClip(live, m.clip.id), "Removed ${m.clip.name.ifBlank { m.clip.id }}")
            }
            is TrackMenu.InsertLibrary -> { }
            is TrackMenu.Audio -> when (id) {
                "play" -> seekTo(m.clip.startSec, resume = true)
                "mute" -> {
                    val muted = (m.clip.volume ?: 1.0) <= 0.001
                    commit(
                        LyreEdits.setClipVolume(live, m.clip.id, if (muted) 1.0 else 0.0),
                        if (muted) "Unmuted ${m.clip.name}" else "Muted ${m.clip.name}",
                    )
                }
                "env_on" -> commit(LyreEdits.setEnvelopeOn(live, m.clip.id, true), "Envelope on")
                "env_off" -> commit(LyreEdits.setEnvelopeOn(live, m.clip.id, false), "Envelope off")
                "fade_in" -> commit(LyreEdits.fadeAudio(live, m.clip.id, fadeInSec = 1.0), "Fade in 1s")
                "fade_out" -> commit(LyreEdits.fadeAudio(live, m.clip.id, fadeOutSec = 1.0), "Fade out 1s")
                "env_point" -> {
                    val local = ((playhead.toDouble() - m.clip.startSec) / m.clip.durationSec.coerceAtLeast(0.0001))
                        .coerceIn(0.02, 0.98)
                    val env = LyreEnvelope.addPoint(m.clip, local, LyreEnvelope.gainAt(m.clip, playhead.toDouble()))
                    commit(LyreEdits.setEnvelope(live, m.clip.id, env), "Envelope point")
                }
                "wave" -> {
                    val file = LyreStorageKeys.file(stills, m.clip.src)
                    if (file != null) {
                        scope.launch(Dispatchers.IO) {
                            val peaks = LyreWaveformDecoder.peaksFromFile(file) ?: return@launch
                            withContext(Dispatchers.Main) {
                                commit(LyreEdits.setClipWaveform(live, m.clip.id, peaks.toJson()), "Set waveform")
                            }
                        }
                    }
                }
                "remove" -> commit(LyreEdits.removeClip(live, m.clip.id), "Removed ${m.clip.name.ifBlank { m.clip.id }}")
            }
            is TrackMenu.Library -> when (id) {
                "scene" -> {
                    menu = TrackMenu.PickScene(m.item)
                    return
                }
                "video" -> when (val item = m.item) {
                    is LyreLibraryItem.Video -> {
                        val poster = LyreStorageKeys.posterSrc(live, item.item.src)
                        commit(
                            LyreEdits.placeVideoAt(
                                live,
                                item.item.src,
                                item.item.name,
                                item.item.durationSec,
                                playhead.toDouble(),
                                poster,
                            ),
                            "Placed ${item.item.name.ifBlank { item.item.id }} on video",
                        )
                    }
                    is LyreLibraryItem.Still -> {
                        val src = item.image.videoSrc
                        if (!src.isNullOrEmpty()) {
                            val dur = item.image.videoDurationSec ?: 6.0
                            commit(
                                LyreEdits.placeVideoAt(
                                    live,
                                    src,
                                    item.image.caption.ifBlank { item.image.id },
                                    dur,
                                    playhead.toDouble(),
                                    item.image.src,
                                ),
                                "Placed clip on video",
                            )
                        }
                    }
                    else -> Unit
                }
                "audio" -> {
                    val a = (m.item as? LyreLibraryItem.Audio)?.item
                    if (a != null) {
                        commit(
                            LyreEdits.placeAudioAt(live, a.src, a.name, a.durationSec, playhead.toDouble()),
                            "Placed ${a.name.ifBlank { a.id }} on audio",
                        )
                    }
                }
                "play" -> {
                    when (val item = m.item) {
                        is LyreLibraryItem.Still -> {
                            val t = item.image.fromFrameId?.let { LyreClip.clipOf(live.scenes, it)?.start }
                            if (t != null) seekTo(t, resume = false)
                        }
                        is LyreLibraryItem.Video -> seekTo(playhead.toDouble(), resume = false)
                        is LyreLibraryItem.Audio -> seekTo(playhead.toDouble(), resume = true)
                    }
                }
            }
            is TrackMenu.PickScene, is TrackMenu.InsertLibrary -> { }
        }
        menu = null
    }

    fun placeLibraryItem(item: LyreLibraryItem) {
        val next = when (item) {
            is LyreLibraryItem.Still -> {
                val sceneId = LyreEdits.sceneIdAt(live, playhead.toDouble())
                var board = LyreEdits.addStillToScene(
                    live,
                    sceneId,
                    item.image.src,
                    item.image.caption.ifBlank { item.image.id },
                    item.image.holdSec ?: 6.0,
                    item.image.videoSrc,
                    item.image.videoDurationSec,
                )
                val clipSrc = item.image.videoSrc
                if (!clipSrc.isNullOrEmpty()) {
                    board = LyreEdits.placeVideoAt(
                        board,
                        clipSrc,
                        item.image.caption.ifBlank { item.image.id },
                        item.image.videoDurationSec ?: 6.0,
                        playhead.toDouble(),
                        item.image.src,
                    )
                }
                board
            }
            is LyreLibraryItem.Video -> LyreEdits.placeVideoAt(
                live,
                item.item.src,
                item.item.name,
                item.item.durationSec,
                playhead.toDouble(),
                LyreStorageKeys.posterSrc(live, item.item.src),
            )
            is LyreLibraryItem.Audio -> LyreEdits.placeAudioAt(
                live,
                item.item.src,
                item.item.name,
                item.item.durationSec,
                playhead.toDouble(),
            )
        }
        val label = when (item) {
            is LyreLibraryItem.Still -> item.image.caption.ifBlank { item.image.id }
            is LyreLibraryItem.Video -> item.item.name.ifBlank { item.item.id }
            is LyreLibraryItem.Audio -> item.item.name.ifBlank { item.item.id }
        }
        commit(next, "Inserted $label")
        menu = null
    }

    fun ingestUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        genBusy = true
        genStatus = "Uploading…"
        val mark = playhead.toDouble()
        scope.launch {
            var next = live
            var afterId = LyreEdits.nextBreakFrameId(next, mark)
            val map = HashMap(stills)
            var count = 0
            suspend fun putObject(key: String, file: File, contentType: String): Boolean {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val stored = withContext(Dispatchers.IO) {
                    cache.writeObject(boardId, key, bytes)
                    api.putStorage(LyreStorageKeys.normalize(key) ?: key.removePrefix("me:"), bytes, contentType)
                }
                if (!stored.optBoolean("ok")) return false
                LyreStorageKeys.index(map, key, cache.objectFile(boardId, key))
                stills = HashMap(map)
                return true
            }
            for (uri in uris) {
                try {
                    val name = LyreUploads.displayName(context, uri).ifBlank { "media" }
                    val mime = LyreUploads.mime(context, uri)
                    val kind = LyreEdits.mediaKind(mime, name) ?: continue
                    val label = name.substringBeforeLast('.').ifBlank { kind }
                    val destExt = when (kind) {
                        "still" -> "jpg"
                        "video" -> LyreUploads.extension(mime, name, "mp4")
                        else -> LyreUploads.extension(mime, name, "m4a")
                    }
                    val key = when (kind) {
                        "still" -> LyreStorageKeys.writeKey(boardId, project.isOdysseus, "stills", LyreEdits.newId("st"), "jpg")
                        "video" -> LyreStorageKeys.writeKey(boardId, project.isOdysseus, "videos", LyreEdits.newId("vid"), destExt)
                        else -> LyreStorageKeys.writeKey(boardId, project.isOdysseus, "audio", LyreEdits.newId("st"), destExt)
                    }
                    val tmp = File(cache.boardDir(boardId), "tmp/${System.currentTimeMillis()}-$count.$destExt")
                    val copied = withContext(Dispatchers.IO) {
                        if (kind == "still") LyreUploads.copyStillJpeg(context, uri, tmp) else LyreUploads.copy(context, uri, tmp)
                    }
                    if (!copied) continue
                    val contentType = mime.ifBlank {
                        when (kind) {
                            "still" -> "image/jpeg"
                            "video" -> "video/mp4"
                            else -> "audio/mp4"
                        }
                    }
                    if (!putObject(key, tmp, contentType)) {
                        tmp.delete()
                        continue
                    }
                    next = when (kind) {
                        "still" -> {
                            val ins = LyreEdits.insertPictureAfter(next, afterId, key, label, 6.0)
                            afterId = ins.frameId.ifBlank { afterId }
                            LyreEdits.addStillToLibrary(ins.board, key, label)
                        }
                        "video" -> {
                            val dur = withContext(Dispatchers.IO) { LyreUploads.durationSec(tmp) }
                            val posterKey = LyreStorageKeys.writeKey(boardId, project.isOdysseus, "stills", LyreEdits.newId("st"), "jpg")
                            val poster = File(cache.boardDir(boardId), "tmp/${System.currentTimeMillis()}-$count-poster.jpg")
                            val framed = withContext(Dispatchers.IO) { LyreUploads.firstFrameJpeg(tmp, poster) }
                            var board = LyreEdits.addVideoToLibrary(next, key, label, dur)
                            if (framed && poster.isFile) {
                                if (putObject(posterKey, poster, "image/jpeg")) {
                                    val ins = LyreEdits.insertPictureAfter(
                                        board,
                                        afterId,
                                        posterKey,
                                        label,
                                        dur,
                                        videoSrc = key,
                                        videoDurationSec = dur,
                                    )
                                    afterId = ins.frameId.ifBlank { afterId }
                                    board = LyreEdits.addStillToLibrary(ins.board, posterKey, label)
                                } else {
                                    board = LyreEdits.placeVideoAt(board, key, label, dur, mark)
                                }
                            } else {
                                board = LyreEdits.placeVideoAt(board, key, label, dur, mark)
                            }
                            poster.delete()
                            board
                        }
                        else -> {
                            val dur = withContext(Dispatchers.IO) { LyreUploads.durationSec(tmp) }
                            LyreEdits.placeAudioOnNewTrack(
                                LyreEdits.addAudioToLibrary(next, key, label, dur),
                                key,
                                label,
                                dur,
                            )
                        }
                    }
                    count++
                    tmp.delete()
                } catch (_: Exception) {
                }
            }
            if (count > 0) commit(next, "Uploaded $count item${if (count == 1) "" else "s"}")
            genBusy = false
            genStatus = if (count == 0) "Upload failed" else null
        }
    }

    fun indexStill(key: String, file: File) {
        val map = HashMap(stills)
        LyreStorageKeys.index(map, key, file)
        stills = map
    }

    fun applyImagineResult(job: LyreImagineJob, src: String, duration: Double) {
        scope.launch(Dispatchers.IO) {
            val file = cache.resolve(boardId, src)
            withContext(Dispatchers.Main) {
                if (file != null) indexStill(src, file)
                val next = when (job.mode) {
                    LyreImagineMode.NEXT_STILL -> {
                        val cap = job.frameId?.let { fid ->
                            live.scenes.flatMap { it.frames }.firstOrNull { it.id == fid }?.caption
                        }.orEmpty().ifBlank { "Generated still" }
                        val afterId = job.frameId ?: LyreEdits.nextBreakFrameId(live, playhead.toDouble())
                        val ins = LyreEdits.insertPictureAfter(live, afterId, src, cap, 6.0)
                        LyreEdits.addStillToLibrary(ins.board, src, cap)
                    }
                    LyreImagineMode.EDIT_STILL -> {
                        val fid = job.frameId ?: return@withContext
                        LyreEdits.replaceStillSrc(live, fid, src)
                    }
                    LyreImagineMode.GEN_VIDEO -> {
                        val fid = job.frameId ?: return@withContext
                        val have = live.scenes.flatMap { it.frames }.firstOrNull { it.id == fid }?.videoSrc.orEmpty()
                        if (have.isNotEmpty() && LyreStorageKeys.normalize(have) == LyreStorageKeys.normalize(src)) {
                            return@withContext
                        }
                        LyreEdits.addVideoToLibrary(
                            LyreEdits.attachGeneratedVideo(live, fid, src, duration),
                            src,
                            job.title,
                            duration,
                        )
                    }
                    LyreImagineMode.EDIT_VIDEO -> {
                        val cid = job.clipId ?: return@withContext
                        LyreEdits.replaceVideoSrc(live, cid, src, duration)
                    }
                }
                commit(next, when (job.mode) {
                    LyreImagineMode.NEXT_STILL -> "Generated still"
                    LyreImagineMode.EDIT_STILL -> "Edited still"
                    LyreImagineMode.GEN_VIDEO -> "Generated video"
                    LyreImagineMode.EDIT_VIDEO -> "Edited video"
                })
            }
        }
    }

    fun runImagine(draft: LyreImagineDraft) {
        val job = imagine ?: return
        genBusy = true
        genStatus = "Generating…"
        scope.launch {
            try {
                val sourceStill = job.frameId?.let { fid ->
                    live.scenes.flatMap { it.frames }.firstOrNull { it.id == fid }?.src
                }?.let { LyreStorageKeys.file(stills, it) }
                val refFiles = draft.refs.mapNotNull { LyreStorageKeys.file(stills, it.src) } + extraRefFiles
                val result = withContext(Dispatchers.IO) {
                    val client = LyreImagineClient(
                        api,
                        HostApiKeyStore.getValue(context, ApiKeyIds.SPACEXAI),
                    )
                    when (job.mode) {
                        LyreImagineMode.NEXT_STILL, LyreImagineMode.EDIT_STILL -> {
                            client.generateStill(
                                draft.prompt,
                                sourceStill,
                                refFiles,
                                draft.aspect,
                                boardId,
                                project.isOdysseus,
                            )
                        }
                        LyreImagineMode.GEN_VIDEO, LyreImagineMode.EDIT_VIDEO -> {
                            val clip = job.clipId?.let { id ->
                                live.videoLayers.flatMap { it.clips }.firstOrNull { it.id == id }
                            }
                            client.startVideo(
                                prompt = draft.prompt,
                                sourceStill = sourceStill ?: clip?.let {
                                    LyreStorageKeys.file(stills, LyreStorageKeys.posterSrc(live, it.src))
                                },
                                refs = refFiles,
                                voices = draft.voices,
                                duration = draft.duration,
                                aspect = draft.aspect,
                                resolution = "720p",
                                mode = if (job.mode == LyreImagineMode.EDIT_VIDEO) "edit" else "generate",
                                videoKey = clip?.src?.let { LyreStorageKeys.normalize(it) },
                                videoFile = clip?.src?.let { LyreStorageKeys.file(stills, it) },
                                boardId = boardId,
                                frameId = job.frameId,
                                clipId = job.clipId,
                            )
                        }
                    }
                }
                if (!result.optBoolean("ok")) {
                    genStatus = result.optString("error").ifBlank { "generate_failed" }
                    genBusy = false
                    return@launch
                }
                if (result.optString("status") == "done" || result.optString("key").isNotBlank()) {
                    val key = result.optString("src").ifBlank { "me:" + result.optString("key") }
                    applyImagineResult(job, key, result.optDouble("duration", draft.duration.toDouble()))
                    genBusy = false
                    genStatus = null
                    imagine = null
                    extraRefFiles = emptyList()
                    return@launch
                }
                val rid = result.optString("request_id").ifBlank { result.optString("id") }
                if (rid.isBlank()) {
                    genStatus = "no_request_id"
                    genBusy = false
                    return@launch
                }
                genStatus = "Rendering video…"
                val deadline = SystemClock.elapsedRealtime() + 10 * 60 * 1000L
                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(5000)
                    val st = withContext(Dispatchers.IO) {
                        LyreImagineClient(
                            api,
                            HostApiKeyStore.getValue(context, ApiKeyIds.SPACEXAI),
                        ).pollVideo(rid, boardId, project.isOdysseus)
                    }
                    val status = st.optString("status")
                    if (st.optBoolean("ok") && (status == "done" || st.optString("key").isNotBlank())) {
                        val key = st.optString("src").ifBlank { "me:" + st.optString("key") }
                        applyImagineResult(job, key, st.optDouble("duration", draft.duration.toDouble()))
                        genBusy = false
                        genStatus = null
                        imagine = null
                        extraRefFiles = emptyList()
                        return@launch
                    }
                    if (!st.optBoolean("ok") && status != "pending") {
                        genStatus = st.optString("error").ifBlank { status.ifBlank { "failed" } }
                        genBusy = false
                        return@launch
                    }
                }
                genStatus = "timed_out"
                genBusy = false
            } catch (e: Exception) {
                genStatus = e.message ?: "failed"
                genBusy = false
            }
        }
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> ingestUris(uris) }
    val pickImagineRef = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val tmp = File(cache.boardDir(boardId), "tmp/ref-${System.currentTimeMillis()}.jpg")
            if (LyreUploads.copyStillJpeg(context, uri, tmp)) {
                withContext(Dispatchers.Main) {
                    extraRefFiles = (extraRefFiles + tmp).take(3)
                }
            }
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
                project.name.ifBlank { live.title.ifBlank { "Untitled" } },
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
        if (serverBanner != null) {
            Text(
                serverBanner!!,
                color = GrokifyColors.GlowAmber,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable { scope.launch { reloadFromServer() } }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (!genStatus.isNullOrBlank()) {
            Text(
                genStatus!!,
                color = if (genBusy) GrokifyColors.GlowAmber else GrokifyColors.GlowRose,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxSize()) {
                LyrePlayerStage(
                    player = player,
                    hasVideo = program.isNotEmpty(),
                    still = current?.frame,
                    stillFile = current?.frame?.src?.let { LyreStorageKeys.file(stills, it) },
                    showStill = showStill,
                    playing = playing,
                    playhead = playhead,
                    duration = duration,
                    swapEpoch = player.swapEpoch,
                    onTogglePlay = { togglePlay() },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                LyreTimeline(
                    board = live,
                    stills = stills,
                    storyboard = storyboard,
                    programClips = pictureClips,
                    playhead = playhead,
                    duration = duration,
                    pps = pps,
                    onPps = {
                        pps = it
                        store.timelinePps = it
                    },
                    onSeek = { seekTo(it, resume = playing && !scrubbing) },
                    onPlayheadDragStart = {
                        scrubbing = true
                        if (playing) {
                            player.pause()
                            audio.pause()
                        }
                    },
                    onPlayheadDrag = { t -> seekTo(t, resume = false) },
                    onPlayheadDragEnd = { scrubbing = false },
                    onMenuStill = { menu = TrackMenu.Still(it.frame) },
                    onMenuVideo = { clip, locked -> menu = TrackMenu.Video(clip, locked = locked) },
                    onMenuAudio = { layer, clip -> menu = TrackMenu.Audio(layer, clip) },
                    onMoveStill = { clip, t ->
                        commit(LyreEdits.moveStillTo(live, clip.frame.id, t), "Moved still")
                    },
                    onMoveVideo = { clip, t ->
                        commit(LyreEdits.moveVideoClip(live, clip.id, t), "Moved clip")
                    },
                    onMoveAudio = { clip, t, lane ->
                        commit(LyreEdits.moveAudioClip(live, clip.id, t, lane), "Moved audio")
                    },
                    onAddAudioTrack = {
                        commit(LyreEdits.addAudioTrack(live), "Added audio track")
                    },
                    onTrimStillLeft = { clip, t ->
                        commit(LyreEdits.trimStillLeft(live, clip.frame.id, t), "Trim still")
                    },
                    onTrimStillRight = { clip, t ->
                        commit(LyreEdits.trimStillRight(live, clip.frame.id, t), "Trim still")
                    },
                    onTrimClip = { clip, start, end ->
                        commit(LyreEdits.trimClip(live, clip.id, start, end), "Trim clip")
                    },
                    onEnvelopePoint = { clip, index, t, v ->
                        commit(
                            LyreEdits.setEnvelope(live, clip.id, LyreEnvelope.movePoint(clip, index, t, v)),
                            "Envelope",
                        )
                    },
                    onEnvelopeAdd = { clip, t, v ->
                        commit(
                            LyreEdits.setEnvelope(live, clip.id, LyreEnvelope.addPoint(clip, t, v)),
                            "Envelope point",
                        )
                    },
                    onUploadMedia = {
                        pickMedia.launch(arrayOf("image/*", "video/*", "audio/*"))
                    },
                    onGenerateImage = {
                        val hostId = LyreEdits.nextBreakFrameId(live, playhead.toDouble())
                        val host = hostId?.let { id -> live.scenes.flatMap { it.frames }.firstOrNull { it.id == id } }
                        imagine = LyreImagineJob(
                            LyreImagineMode.NEXT_STILL,
                            frameId = hostId,
                            title = host?.caption?.ifBlank { "Generate image" } ?: "Generate image",
                        )
                        extraRefFiles = emptyList()
                    },
                    onInsertLibrary = { menu = TrackMenu.InsertLibrary },
                )
            }
            if (chip == "library" || chip == "bin" || chip == "activity" || chip == "scenes") {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(GrokifyColors.Void),
                ) {
                    when (chip) {
                        "library" -> LyreLibraryPane(
                            board = live,
                            stills = stills,
                            onOpen = { item -> menu = TrackMenu.Library(item) },
                            onLongItem = { item -> menu = TrackMenu.Library(item) },
                        )
                        "bin" -> LyreBinPane(live, stills)
                        "activity" -> LyreActivityPane(
                            activity = activity,
                            tick = activityTick,
                            onJump = { line ->
                                val t = lyreJumpTime(live, line) ?: return@LyreActivityPane
                                seekTo(t, resume = false)
                                chip = ""
                                store.chip = ""
                            },
                        )
                        else -> LyreScenesPane(
                            board = live,
                            stills = stills,
                            onSeekScene = { scene ->
                                val t = storyboard.firstOrNull { it.sceneId == scene.id }?.start ?: 0.0
                                seekTo(t, resume = false)
                                chip = ""
                                store.chip = ""
                            },
                            onSeekFrame = { frame ->
                                val t = LyreClip.clipOf(live.scenes, frame.id)?.start ?: return@LyreScenesPane
                                seekTo(t, resume = false)
                                chip = ""
                                store.chip = ""
                            },
                            onLongFrame = { _, frame -> menu = TrackMenu.Still(frame) },
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CHIPS.forEach { (id, label) ->
                val on = chip == id
                FilterChip(
                    selected = on,
                    onClick = {
                        chip = if (on) "" else id
                        store.chip = chip
                        logActivity("ui", if (chip.isEmpty()) "Closed $label" else "Opened $label")
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

    when (val m = menu) {
        is TrackMenu.Still -> LyreActionSheet(
            title = m.frame.caption.ifBlank { m.frame.id },
            subtitle = "Still · ${"%.1f".format(LyreClip.clipLength(m.frame))}s",
            actions = listOf(
                LyreMenuAction("play", "Play from here"),
                LyreMenuAction("loop", if (store.loopClip) "Loop off" else "Loop clip"),
                LyreMenuAction("gen_next", "Generate next image"),
                LyreMenuAction("edit_still", "Edit still"),
                LyreMenuAction("gen_video", "Generate video"),
            ),
            onAction = { handleMenu(it) },
            onDismiss = { menu = null },
        )
        is TrackMenu.Video -> LyreActionSheet(
            title = m.clip.name.ifBlank { m.clip.id },
            subtitle = buildString {
                append(if (m.locked) "Movie" else "Clip")
                append(" · ${"%.1f".format(m.clip.durationSec)}s")
            },
            actions = buildList {
                add(LyreMenuAction("play", "Play from here"))
                if (!m.locked) add(LyreMenuAction("loop", if (store.loopClip) "Loop off" else "Loop this clip"))
                if (!m.locked) add(LyreMenuAction("mute", "Mute / unmute"))
                if (!m.locked) add(LyreMenuAction("edit_video", "Edit video"))
                if (!m.locked) add(LyreMenuAction("remove", "Remove from track", destructive = true))
            },
            onAction = { handleMenu(it) },
            onDismiss = { menu = null },
        )
        is TrackMenu.Audio -> {
            val envOn = LyreEnvelope.parseClip(m.clip)?.on == true
            LyreActionSheet(
                title = m.clip.name.ifBlank { m.clip.id },
                subtitle = "${m.layer.name} · ${"%.1f".format(m.clip.durationSec)}s @ ${"%.1f".format(m.clip.startSec)}s",
                actions = listOf(
                    LyreMenuAction("play", "Play from here"),
                    LyreMenuAction("mute", if ((m.clip.volume ?: 1.0) <= 0.001) "Unmute" else "Mute"),
                    LyreMenuAction(if (envOn) "env_off" else "env_on", if (envOn) "Envelope off" else "Envelope on"),
                    LyreMenuAction("fade_in", "Fade in 1s"),
                    LyreMenuAction("fade_out", "Fade out 1s"),
                    LyreMenuAction("env_point", "Add envelope point at playhead"),
                    LyreMenuAction("wave", "Set waveform"),
                    LyreMenuAction("remove", "Remove from track", destructive = true),
                ),
                onAction = { handleMenu(it) },
                onDismiss = { menu = null },
            )
        }
        is TrackMenu.Library -> {
            val actions = buildList {
                add(LyreMenuAction("scene", "Add to scene…"))
                when (val item = m.item) {
                    is LyreLibraryItem.Video -> add(LyreMenuAction("video", "Place on video track"))
                    is LyreLibraryItem.Audio -> add(LyreMenuAction("audio", "Place on audio track"))
                    is LyreLibraryItem.Still -> {
                        if (!item.image.videoSrc.isNullOrEmpty()) {
                            add(LyreMenuAction("video", "Place clip on video track"))
                        }
                    }
                }
            }.distinctBy { it.id }
            val title = when (val item = m.item) {
                is LyreLibraryItem.Still -> item.image.caption.ifBlank { item.image.id }
                is LyreLibraryItem.Video -> item.item.name.ifBlank { item.item.id }
                is LyreLibraryItem.Audio -> item.item.name.ifBlank { item.item.id }
            }
            LyreActionSheet(
                title = title,
                subtitle = "Library",
                actions = actions,
                onAction = { handleMenu(it) },
                onDismiss = { menu = null },
            )
        }
        is TrackMenu.PickScene -> AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text("Add to scene") },
            text = {
                Column {
                    live.scenes.forEach { scene ->
                        Text(
                            scene.title.ifBlank { scene.id },
                            color = GrokifyColors.TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = when (val item = m.item) {
                                        is LyreLibraryItem.Still -> LyreEdits.addStillToScene(
                                            live,
                                            scene.id,
                                            item.image.src,
                                            item.image.caption.ifBlank { item.image.id },
                                            item.image.holdSec ?: 6.0,
                                            item.image.videoSrc,
                                            item.image.videoDurationSec,
                                        )
                                        is LyreLibraryItem.Video -> LyreEdits.addStillToScene(
                                            live,
                                            scene.id,
                                            LyreStorageKeys.posterSrc(live, item.item.src).orEmpty(),
                                            item.item.name,
                                            item.item.durationSec,
                                            item.item.src,
                                            item.item.durationSec,
                                        )
                                        is LyreLibraryItem.Audio -> LyreEdits.placeAudioAt(
                                            live,
                                            item.item.src,
                                            item.item.name,
                                            item.item.durationSec,
                                            playhead.toDouble(),
                                        )
                                    }
                                    val label = scene.title.ifBlank { scene.id }
                                    commit(next, "Added to $label")
                                    menu = null
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menu = null }) { Text("Cancel") }
            },
        )
        is TrackMenu.InsertLibrary -> {
            val actions = buildList {
                live.refFolders.forEach { folder ->
                    folder.images.forEach { image ->
                        add(LyreMenuAction("s:${folder.id}:${image.id}", "Still · ${image.caption.ifBlank { image.id }}"))
                    }
                }
                live.libraryVideo.filter { it.deletedAt == null }.forEach { video ->
                    add(LyreMenuAction("v:${video.id}", "Video · ${video.name.ifBlank { video.id }}"))
                }
                live.libraryAudio.filter { it.deletedAt == null }.forEach { audio ->
                    add(LyreMenuAction("a:${audio.id}", "Audio · ${audio.name.ifBlank { audio.id }}"))
                }
            }.take(80)
            LyreActionSheet(
                title = "Insert from library",
                subtitle = "Drops at the playhead",
                actions = if (actions.isEmpty()) listOf(LyreMenuAction("empty", "Library is empty")) else actions,
                onAction = { id ->
                    when {
                        id.startsWith("s:") -> {
                            val parts = id.split(":")
                            val folderId = parts.getOrNull(1)
                            val imageId = parts.getOrNull(2)
                            val image = live.refFolders.firstOrNull { it.id == folderId }?.images?.firstOrNull { it.id == imageId }
                            val folder = live.refFolders.firstOrNull { it.id == folderId }
                            if (image != null && folder != null) placeLibraryItem(LyreLibraryItem.Still(folder, image))
                            else menu = null
                        }
                        id.startsWith("v:") -> {
                            val vid = live.libraryVideo.firstOrNull { it.id == id.removePrefix("v:") }
                            if (vid != null) placeLibraryItem(LyreLibraryItem.Video(vid)) else menu = null
                        }
                        id.startsWith("a:") -> {
                            val aud = live.libraryAudio.firstOrNull { it.id == id.removePrefix("a:") }
                            if (aud != null) placeLibraryItem(LyreLibraryItem.Audio(aud)) else menu = null
                        }
                        else -> menu = null
                    }
                },
                onDismiss = { menu = null },
            )
        }
        null -> Unit
    }

    imagine?.let { job ->
        LyreImagineSheet(
            job = job,
            board = live,
            stills = stills,
            busy = genBusy,
            status = genStatus,
            extraFiles = extraRefFiles,
            onGenerate = { runImagine(it) },
            onPickGalleryRef = { pickImagineRef.launch("image/*") },
            onDismiss = {
                if (!genBusy) {
                    imagine = null
                    extraRefFiles = emptyList()
                    genStatus = null
                }
            },
        )
    }

    fun openProject(next: LyreProject) {
        if (next.id == project.id) {
            switcher = false
            return
        }
        scope.launch {
            saveJob?.join()
            val json = withContext(Dispatchers.IO) {
                api.open(next.id)
                api.board(next.boardId)
            }
            if (!json.optBoolean("ok", false)) {
                genStatus = json.optString("error").ifBlank { "open_failed" }
                return@launch
            }
            val data = json.optJSONObject("data") ?: JSONObject()
            val stamp = json.optString("updated_at")
            withContext(Dispatchers.IO) {
                cache.writeBoardJson(next.boardId, data)
                cache.writeBoardStamp(next.boardId, stamp)
            }
            store.projectId = next.id
            switcher = false
            onProjectOpened(next, LyreBoardCodec.decode(data), stamp)
        }
    }

    if (switcher) {
        LyreProjectPicker(
            visible = true,
            current = project,
            api = api,
            onDismiss = { switcher = false },
            onOpen = { openProject(it) },
            onCreated = { created ->
                val row = created
                openProject(row)
            },
        )
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
    swapEpoch: Int,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(132.dp),
) {
    Box(modifier.background(GrokifyColors.VoidElevated)) {
        if (hasVideo) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player.exo
                        useController = false
                        controllerAutoShow = false
                        hideController()
                        isClickable = false
                        isFocusable = false
                        setKeepContentOnPlayerReset(true)
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    if (swapEpoch >= 0 && view.player !== player.exo) {
                        view.player = player.exo
                    }
                    view.setKeepContentOnPlayerReset(true)
                    view.useController = false
                    view.isClickable = false
                    view.hideController()
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showStill) {
            if (stillFile != null) {
                LyreStillImage(
                    file = stillFile,
                    contentDescription = still?.caption,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    fallback = still?.caption,
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
        Box(
            Modifier
                .fillMaxSize()
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            if (!playing) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(GrokifyColors.Scrim, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = GrokifyColors.TextPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
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

private fun fmtTime(sec: Float): String {
    val s = sec.coerceAtLeast(0f).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
