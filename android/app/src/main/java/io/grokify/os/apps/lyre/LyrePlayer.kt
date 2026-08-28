package io.grokify.os.apps.lyre

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import java.io.File
import kotlin.math.max

data class LyrePlayItem(
    val id: String,
    val kind: Kind,
    val file: File,
    val startUs: Long,
    val endUs: Long?,
    val playDurationSec: Double,
    val linkedFrameId: String?,
) {
    enum class Kind { MOVIE, LEFTOVER }
}

sealed class LyreClockTarget {
    data class Movie(val positionSec: Double) : LyreClockTarget()
    data class Leftover(val clipId: String, val positionSec: Double) : LyreClockTarget()
    data class Hold(val frame: Frame) : LyreClockTarget()
}

class LyrePlayer(context: Context) {
    private val appCtx = context.applicationContext
    private val dataSource = DefaultDataSource.Factory(appCtx)
    private val sources = ProgressiveMediaSource.Factory(
        dataSource,
        DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
    )
    private val pool: Array<ExoPlayer> = arrayOf(createPlayer(0), createPlayer(1))
    private var frontIndex = 0

    val exo: ExoPlayer get() = pool[frontIndex]
    private val ram: ExoPlayer get() = pool[1 - frontIndex]

    @Volatile
    var wantedPlaying: Boolean = false
        private set

    @Volatile
    var playGeneration: Int = 0
        private set

    @Volatile
    var swapEpoch: Int = 0
        private set

    @Volatile
    private var seekTargetId: String? = null

    @Volatile
    private var ramTargetId: String? = null

    var items: List<LyrePlayItem> = emptyList()
        private set

    private fun createPlayer(slot: Int): ExoPlayer {
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 30_000, 250, 500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(appCtx).setLoadControl(load).build().apply {
            pauseAtEndOfMediaItems = true
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_OFF
            setPreloadConfiguration(ExoPlayer.PreloadConfiguration(4_000_000L))
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val front = slot == frontIndex
                    if (isPlaying && front && !wantedPlaying) {
                        playWhenReady = false
                        pause()
                    }
                    if (isPlaying && !front) {
                        playWhenReady = false
                        pause()
                    }
                }
            })
        }
    }

    private fun mediaOf(program: List<LyrePlayItem>): List<MediaSource> {
        return program.map { item ->
            val base = sources.createMediaSource(MediaItem.fromUri(Uri.fromFile(item.file)))
            val end = item.endUs
            when {
                end != null && end > item.startUs -> ClippingMediaSource(base, item.startUs, end)
                item.startUs > 0L -> ClippingMediaSource(base, item.startUs, C.TIME_END_OF_SOURCE)
                else -> base
            }
        }
    }

    fun setProgram(program: List<LyrePlayItem>) {
        items = program
        wantedPlaying = false
        seekTargetId = null
        ramTargetId = null
        frontIndex = 0
        swapEpoch += 1
        for (player in pool) {
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.pauseAtEndOfMediaItems = true
            player.playWhenReady = false
            player.pause()
            val media = mediaOf(program)
            if (media.isEmpty()) {
                player.clearMediaItems()
            } else {
                player.setMediaSources(media)
                player.prepare()
            }
        }
    }

    fun preloadItem(id: String): Boolean {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        if (ramTargetId == id && ram.currentMediaItemIndex == idx) {
            if (ram.playbackState == Player.STATE_IDLE) ram.prepare()
            return true
        }
        ramTargetId = id
        ram.playWhenReady = false
        ram.pause()
        ram.seekTo(idx, 0L)
        if (ram.playbackState == Player.STATE_IDLE) ram.prepare()
        return true
    }

    fun ramItemId(): String? = ramTargetId

    fun ramReady(id: String): Boolean {
        if (id.isEmpty() || ramTargetId != id) return false
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        return ram.currentMediaItemIndex == idx && ram.playbackState == Player.STATE_READY
    }

    fun promoteRam(id: String): Boolean {
        if (!ramReady(id)) return false
        val old = exo
        old.playWhenReady = false
        old.pause()
        frontIndex = 1 - frontIndex
        ramTargetId = null
        seekTargetId = id
        swapEpoch += 1
        if (wantedPlaying) {
            exo.playWhenReady = true
            exo.play()
        }
        return true
    }

    fun seekToItem(id: String, positionSec: Double): Boolean {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val item = items[idx]
        val maxMs = (item.playDurationSec * 1000.0).toLong().coerceAtLeast(0L)
        val ms = (positionSec * 1000.0).toLong().coerceIn(0L, maxMs)
        seekTargetId = id
        exo.seekTo(idx, ms)
        return true
    }

    fun seekPending(targetId: String): Boolean {
        return seekTargetId == targetId && currentItem()?.id != targetId
    }

    fun play(generation: Int = playGeneration) {
        if (generation != playGeneration) {
            wantedPlaying = false
            exo.playWhenReady = false
            exo.pause()
            return
        }
        wantedPlaying = true
        exo.playWhenReady = true
        exo.play()
    }

    fun pause() {
        wantedPlaying = false
        exo.playWhenReady = false
        exo.pause()
    }

    fun invalidatePlay() {
        playGeneration += 1
        pause()
    }

    fun prepared(): Boolean {
        return exo.playbackState == Player.STATE_READY
    }

    fun ended(): Boolean {
        return LyreTransport.playerEnded(
            currentItem(),
            exo.currentPosition / 1000.0,
            exo.playbackState == Player.STATE_ENDED,
        )
    }

    fun syncLoop(loopClip: Boolean, leftoverClipId: String?) {
        val current = currentItem()
        val onFocusedLeftover = current != null &&
            current.kind == LyrePlayItem.Kind.LEFTOVER &&
            leftoverClipId != null &&
            current.id == leftoverClipId
        exo.repeatMode = if (loopClip && onFocusedLeftover) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    fun currentItem(): LyrePlayItem? = items.getOrNull(exo.currentMediaItemIndex)

    fun release() {
        for (player in pool) {
            runCatching { player.release() }
        }
        items = emptyList()
        ramTargetId = null
    }

    companion object {
        fun buildProgram(board: BoardData, boardId: String, cache: LyreCache): List<LyrePlayItem> {
            val layers = board.videoLayers
            val out = ArrayList<LyrePlayItem>()
            val live = LyreMovie.resolvedMovie(board.movie, layers)
            if (live != null && live.src.isNotEmpty()) {
                val file = cache.resolve(boardId, live.src)
                if (file != null && file.length() > 0L) {
                    val play = LyreMovie.moviePlayDuration(live)
                    val linked = live.parts.firstOrNull()?.let { part ->
                        LyreMovie.orderedVideoClips(layers).find { it.id == part.clipId }?.linkedFrameId
                    }
                    out.add(
                        LyrePlayItem(
                            id = "lc_movie",
                            kind = LyrePlayItem.Kind.MOVIE,
                            file = file,
                            startUs = 0L,
                            endUs = (play * 1_000_000.0).toLong().coerceAtLeast(1L),
                            playDurationSec = play,
                            linkedFrameId = linked,
                        ),
                    )
                } else {
                    Log.i("Lyre", "skip missing ${live.src}")
                }
            }
            for (clip in LyreMovie.orderedVideoClips(layers)) {
                if (LyreMovie.clipInMovie(board.movie, clip.id, layers)) continue
                val file = cache.resolve(boardId, clip.src)
                if (file == null || file.length() <= 0L) {
                    Log.i("Lyre", "skip missing ${clip.src}")
                    continue
                }
                val win = LyreClip.presentedVideoWindow(clip)
                val startUs = (win.inn * 1_000_000.0).toLong().coerceAtLeast(0L)
                val endUs = (win.out * 1_000_000.0).toLong()
                out.add(
                    LyrePlayItem(
                        id = clip.id,
                        kind = LyrePlayItem.Kind.LEFTOVER,
                        file = file,
                        startUs = startUs,
                        endUs = if (endUs > startUs) endUs else null,
                        playDurationSec = max(0.0, win.out - win.inn),
                        linkedFrameId = clip.linkedFrameId,
                    ),
                )
            }
            return out
        }
    }
}

fun lyreClockTarget(board: BoardData, t: Double): LyreClockTarget? {
    val sc = LyreClip.clipAtTime(LyreClip.movieClips(board.scenes), t) ?: return null
    val layers = board.videoLayers
    if (LyreMovie.frameInMovie(board.movie, layers, sc.frame.id)) {
        return LyreClockTarget.Movie(movieFilePosition(board, sc, t))
    }
    val leftover = LyreMovie.orderedVideoClips(layers).firstOrNull { clip ->
        clip.linkedFrameId == sc.frame.id && !LyreMovie.clipInMovie(board.movie, clip.id, layers)
    }
    if (leftover != null) {
        val win = LyreClip.presentedVideoWindow(leftover)
        val localMax = max(0.0, win.out - win.inn)
        val local = (t - sc.start).coerceIn(0.0, localMax)
        return LyreClockTarget.Leftover(leftover.id, local)
    }
    return LyreClockTarget.Hold(sc.frame)
}

fun lyreStillsFromPlayItem(board: BoardData, item: LyrePlayItem, positionSec: Double): Double {
    return when (item.kind) {
        LyrePlayItem.Kind.MOVIE -> stillsFromMovieFile(board, positionSec)
        LyrePlayItem.Kind.LEFTOVER -> {
            val sc = item.linkedFrameId?.let { LyreClip.clipOf(board.scenes, it) }
            (sc?.start ?: 0.0) + positionSec
        }
    }
}

fun lyreNextVideoClip(board: BoardData, frameId: String): StoryboardClip? {
    var next = LyreClip.nextClipAfter(board.scenes, frameId)
    while (next != null) {
        when (lyreClockTarget(board, next.start)) {
            is LyreClockTarget.Hold -> next = LyreClip.nextClipAfter(board.scenes, next.frame.id)
            else -> return next
        }
    }
    return null
}

private fun movieFilePosition(board: BoardData, sc: StoryboardClip, t: Double): Double {
    val layers = board.videoLayers
    val live = LyreMovie.resolvedMovie(board.movie, layers) ?: return 0.0
    val mapped = LyreMovie.moviePartDurations(
        live.parts.map { it.durationSec },
        LyreMovie.moviePlayDuration(live),
    )
    val ordered = LyreMovie.orderedVideoClips(layers)
    val member = ordered.firstOrNull { clip ->
        clip.linkedFrameId == sc.frame.id && LyreMovie.clipInMovie(board.movie, clip.id, layers)
    }
    val idx = if (member != null) live.parts.indexOfFirst { it.clipId == member.id } else -1
    val local = (t - sc.start).coerceAtLeast(0.0)
    if (idx < 0) return local.coerceAtMost(LyreMovie.moviePlayDuration(live))
    val partLen = mapped.getOrElse(idx) { 0.0 }
    var prefix = 0.0
    for (i in 0 until idx) prefix += mapped[i]
    return prefix + local.coerceAtMost(partLen)
}

private fun stillsFromMovieFile(board: BoardData, fileTime: Double): Double {
    val layers = board.videoLayers
    val live = LyreMovie.resolvedMovie(board.movie, layers) ?: return fileTime
    val mapped = LyreMovie.moviePartDurations(
        live.parts.map { it.durationSec },
        LyreMovie.moviePlayDuration(live),
    )
    val ordered = LyreMovie.orderedVideoClips(layers)
    var remain = fileTime.coerceAtLeast(0.0)
    val last = live.parts.lastIndex
    live.parts.forEachIndexed { i, part ->
        val dur = mapped.getOrElse(i) { 0.0 }
        val frameId = ordered.find { it.id == part.clipId }?.linkedFrameId
        val sc = frameId?.let { LyreClip.clipOf(board.scenes, it) }
        if (i == last || remain <= dur + 1e-6) {
            return (sc?.start ?: 0.0) + remain.coerceAtMost(max(dur, 0.0))
        }
        remain -= dur
    }
    return LyreClip.movieDuration(board.scenes)
}
