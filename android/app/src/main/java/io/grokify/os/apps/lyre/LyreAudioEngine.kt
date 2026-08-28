package io.grokify.os.apps.lyre

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.abs

/**
 * Mixes overlapping audio-layer clips against the stills clock. One ExoPlayer per
 * currently-audible clip (capped), so beds that stack (Odysseus A1) both play.
 */
class LyreAudioEngine(context: Context) {
    private val appCtx = context.applicationContext
    private val players: Array<ExoPlayer> = Array(MAX) {
        ExoPlayer.Builder(appCtx).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
            volume = 1f
        }
    }
    private val assigned = arrayOfNulls<String>(MAX)

    @Volatile
    private var wantedPlaying: Boolean = false

    fun sync(t: Double, playing: Boolean, layers: List<MediaLayer>, files: Map<String, File>) {
        wantedPlaying = playing
        val active = ArrayList<LayerClip>(MAX)
        for (layer in layers) {
            for (clip in layer.clips) {
                if (clip.src.isEmpty()) continue
                val vol = (clip.volume ?: 1.0)
                if (vol <= 0.001) continue
                if (t >= clip.startSec && t < clip.startSec + clip.durationSec) {
                    active += clip
                    if (active.size >= MAX) break
                }
            }
            if (active.size >= MAX) break
        }
        val keep = active.map { it.id }.toHashSet()
        for (i in 0 until MAX) {
            val id = assigned[i] ?: continue
            if (id !in keep) {
                players[i].playWhenReady = false
                players[i].pause()
                assigned[i] = null
            }
        }
        for (clip in active) {
            var slot = assigned.indexOf(clip.id)
            if (slot < 0) slot = assigned.indexOf(null)
            if (slot < 0) continue
            val file = LyreStorageKeys.file(files, clip.src) ?: continue
            val player = players[slot]
            if (assigned[slot] != clip.id) {
                assigned[slot] = clip.id
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
            }
            val local = (t - clip.startSec) + (clip.trimInSec ?: 0.0)
            val wantMs = (local.coerceAtLeast(0.0) * 1000.0).toLong()
            if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                if (abs(player.currentPosition - wantMs) > 220L) {
                    player.seekTo(wantMs)
                }
            } else if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.volume = LyreEnvelope.gainAt(clip, t).toFloat().coerceIn(0f, 1f)
            player.playWhenReady = playing && wantedPlaying
            if (playing && wantedPlaying) player.play() else player.pause()
        }
        if (!playing || !wantedPlaying) {
            for (p in players) {
                p.playWhenReady = false
                p.pause()
            }
        }
    }

    fun pause() {
        wantedPlaying = false
        for (p in players) {
            p.playWhenReady = false
            p.pause()
        }
    }

    fun release() {
        for (p in players) {
            runCatching { p.release() }
        }
        for (i in assigned.indices) assigned[i] = null
    }

    companion object {
        const val MAX = 4
    }
}
