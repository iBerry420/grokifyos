package io.grokify.os.apps.lyre

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ClipEdges(val head: Boolean, val tail: Boolean)

data class VideoWindow(
    val inn: Double,
    val out: Double,
    val native: Double,
    val step: Double,
    val trimmed: Boolean,
)

/** Storyboard beat on the stills clock. */
data class StoryboardClip(
    val frame: Frame,
    val sceneId: String,
    val sceneTitle: String,
    val start: Double,
    val length: Double,
)

object LyreClip {
    fun frameFps(frame: Frame): Double {
        val fps = frame.videoFps
        return if (fps != null && fps > 1.0) fps else 24.0
    }

    fun frameIn(frame: Frame): Double = max(0.0, frame.videoInSec ?: 0.0)

    fun frameOut(frame: Frame): Double {
        val native = jsOr(frame.videoDurationSec, 0.0)
        val out = frame.videoOutSec ?: native
        val inn = frameIn(frame)
        if (native == 0.0 && frame.videoOutSec == null) return inn + clipLength(frame)
        return max(inn + 1.0 / frameFps(frame), min(jsOr(native, out), out))
    }

    fun clipLength(frame: Frame): Double = max(0.1, frame.durationSec)

    fun clipEdgeTrims(clip: LayerClip): ClipEdges {
        val inn = clip.trimInSec ?: clip.extraNum("videoInSec") ?: 0.0
        val native = jsOr(
            clip.sourceDurationSec,
            jsOr(clip.extraNum("videoDurationSec"), inn + clip.durationSec),
        )
        val out = clip.extraNum("videoOutSec") ?: (inn + clip.durationSec)
        return ClipEdges(
            head = inn > 1.0 / 48.0,
            tail = native - out > 1.0 / 48.0 || native - (inn + clip.durationSec) > 1.0 / 48.0,
        )
    }

    fun clipBackup(clip: LayerClip, frame: Frame?): Pair<String, Double>? {
        val current = jsStr(clip.src).ifEmpty { jsStr(frame?.videoSrc) }
        val src = jsStr(clip.origSrc).ifEmpty { jsStr(frame?.origVideoSrc) }
        if (src.isEmpty() || src == current) return null
        val duration = jsOr(clip.origDurationSec, frame?.origVideoDurationSec ?: 0.0)
        return src to duration
    }

    fun presentedVideoWindow(clip: LayerClip, fps: Double = 24.0): VideoWindow {
        val step = 1.0 / max(1.0, fps)
        val inn = max(0.0, clip.trimInSec ?: clip.extraNum("videoInSec") ?: 0.0)
        val native = jsOr(
            clip.sourceDurationSec,
            jsOr(clip.extraNum("videoDurationSec"), inn + max(0.0, clip.durationSec)),
        )
        val outHint = clip.extraNum("videoOutSec") ?: (inn + max(0.0, clip.durationSec))
        val out = max(inn + step, min(jsOr(native, outHint), outHint))
        return VideoWindow(
            inn = inn,
            out = out,
            native = native,
            step = step,
            trimmed = inn > step / 2.0 || jsOr(native, out) - out > step / 2.0,
        )
    }

    /** Leftover presented window; not stitch drop-last. */
    fun lastFrameTime(clip: LayerClip, fps: Double = 24.0): Double {
        val win = presentedVideoWindow(clip, fps)
        return max(0.0, min(win.native - win.step, win.out - win.step))
    }

    fun movieClips(scenes: List<Scene>): List<StoryboardClip> {
        val out = ArrayList<StoryboardClip>()
        var t = 0.0
        for (scene in scenes) {
            for (frame in scene.frames) {
                val length = clipLength(frame)
                out.add(
                    StoryboardClip(
                        frame = frame,
                        sceneId = scene.id,
                        sceneTitle = scene.title,
                        start = t,
                        length = length,
                    ),
                )
                t += length
            }
        }
        return out
    }

    fun movieDuration(scenes: List<Scene>): Double =
        movieClips(scenes).sumOf { it.length }

    fun clipAtTime(clips: List<StoryboardClip>, t: Double): StoryboardClip? {
        if (clips.isEmpty()) return null
        val last = clips.last()
        if (t >= last.start + last.length) return last
        return clips.firstOrNull { t >= it.start && t < it.start + it.length } ?: clips.first()
    }

    fun clipOf(scenes: List<Scene>, frameId: String): StoryboardClip? =
        movieClips(scenes).firstOrNull { it.frame.id == frameId }

    fun nextClipAfter(scenes: List<Scene>, frameId: String): StoryboardClip? {
        val clips = movieClips(scenes)
        val i = clips.indexOfFirst { it.frame.id == frameId }
        return if (i >= 0) clips.getOrNull(i + 1) else null
    }

    fun snapTime(t: Double, marks: List<Double>, enabled: Boolean, threshold: Double = 0.2): Double {
        if (!enabled) return t
        var best = t
        var dist = threshold
        for (mark in marks) {
            val d = abs(mark - t)
            if (d < dist) {
                dist = d
                best = mark
            }
        }
        return best
    }

    fun timelineMarks(
        total: Double,
        layers: List<MediaLayer>,
        picture: List<StoryboardClip>,
    ): List<Double> {
        val marks = LinkedHashSet<Double>()
        marks.add(0.0)
        marks.add(total)
        var s = 1.0
        while (s < total) {
            marks.add(s)
            s += 1.0
        }
        for (clip in picture) {
            marks.add(clip.start)
            marks.add(clip.start + clip.length)
        }
        for (layer in layers) {
            for (clip in layer.clips) {
                marks.add(clip.startSec)
                marks.add(clip.startSec + clip.durationSec)
            }
        }
        return marks.toList()
    }

    fun labeledDialogue(scene: Scene): String {
        return scene.frames.mapIndexed { i, f ->
            val head = "${i + 1}. ${f.caption.trim().ifEmpty { "Still" }}"
            val body = f.dialogue?.trim()?.ifEmpty { null } ?: "—"
            "$head\n$body"
        }.joinToString("\n\n")
    }

    fun rollupDialogue(scene: Scene): String {
        val parts = scene.frames.mapNotNull { it.dialogue?.trim()?.ifEmpty { null } }
        if (parts.isNotEmpty()) return parts.joinToString("\n\n")
        return scene.dialogue
    }

    fun rollupNotes(scene: Scene): String {
        val parts = scene.frames.mapNotNull { it.notes?.trim()?.ifEmpty { null } }
        if (parts.isNotEmpty()) return parts.joinToString("\n\n")
        return scene.notes
    }

    fun sceneSpoken(scene: Scene): Double {
        val fromFrames = scene.frames.sumOf { spokenSeconds(it.dialogue ?: "") }
        if (fromFrames > 0.0) return fromFrames
        return spokenSeconds(scene.dialogue)
    }

    /** ≈150 wpm: words * 0.4. */
    internal fun spokenSeconds(text: String): Double {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        return words * 0.4
    }

    private fun LayerClip.extraNum(key: String): Double? {
        val e = extra ?: return null
        if (!e.has(key) || e.isNull(key)) return null
        val v = e.optDouble(key, Double.NaN)
        return v.takeUnless { it.isNaN() }
    }

    private fun jsOr(value: Double?, fallback: Double): Double {
        return if (value != null && value != 0.0 && !value.isNaN()) value else fallback
    }

    private fun jsStr(value: String?): String = value?.takeIf { it.isNotEmpty() } ?: ""
}
