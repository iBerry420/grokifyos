package io.grokify.os.apps.lyre

import kotlin.math.max

data class MovieGroup(
    val clip: LayerClip,
    val members: List<LayerClip>,
)

object LyreMovie {
    fun orderedVideoClips(layers: List<MediaLayer>): List<LayerClip> {
        return layers
            .filter { it.kind == "video" }
            .flatMap { it.clips }
            .filter { it.src.isNotEmpty() }
            .sortedWith(compareBy<LayerClip> { it.startSec }.thenBy { it.id })
    }

    fun moviePlayDuration(movie: BoardMovie): Double {
        val full = max(0.1, movie.durationSec)
        val play = movie.playDurationSec
        if (play == null || play >= full - 1.0 / 48.0) return full
        return max(0.1, play)
    }

    fun movieIsTrimmed(movie: BoardMovie): Boolean {
        return moviePlayDuration(movie) < movie.durationSec - 1.0 / 48.0
    }

    fun resolvedMovie(movie: BoardMovie?, layers: List<MediaLayer>): BoardMovie? {
        if (!movie?.src.isNullOrEmpty()) return movie
        val first = orderedVideoClips(layers).firstOrNull()
        val src = first?.src?.takeIf { it.isNotEmpty() } ?: return null
        val duration = jsOr(first.sourceDurationSec, first.durationSec)
        return BoardMovie(
            src = src,
            durationSec = duration,
            fps = null,
            parts = listOf(
                MoviePart(clipId = first.id, src = src, durationSec = first.durationSec),
            ),
        )
    }

    fun movieProgramLayers(movie: BoardMovie?, layers: List<MediaLayer>): List<MediaLayer> {
        val live = resolvedMovie(movie, layers)
        val ordered = orderedVideoClips(layers)
        if (live == null || live.src.isEmpty()) {
            return layers.filter { it.kind == "video" }
        }
        val playDur = moviePlayDuration(live)
        val members = movieMemberIds(live, layers)
        val clips = ArrayList<LayerClip>(1 + ordered.size)
        clips.add(
            LayerClip(
                id = "lc_movie",
                src = live.src,
                name = if (live.parts.size > 1) "Movie · ${live.parts.size}" else "Movie",
                startSec = 0.0,
                durationSec = playDur,
                trimInSec = 0.0,
                sourceDurationSec = live.durationSec,
            ),
        )
        for (clip in ordered) {
            if (members.contains(clip.id)) continue
            clips.add(clip.copy(generating = false))
        }
        return listOf(MediaLayer(id = "ly_movie", kind = "video", name = "Movie", clips = clips))
    }

    fun clipInMovie(movie: BoardMovie?, clipId: String, layers: List<MediaLayer>): Boolean {
        if (movie?.parts?.any { it.clipId == clipId } == true) return true
        if (!movie?.src.isNullOrEmpty()) return false
        val first = orderedVideoClips(layers).firstOrNull()
        return first != null && first.id == clipId
    }

    fun frameInMovie(movie: BoardMovie?, layers: List<MediaLayer>, frameId: String): Boolean {
        return orderedVideoClips(layers).any { clip ->
            clip.linkedFrameId == frameId && clipInMovie(movie, clip.id, layers)
        }
    }

    fun movieGroupOnLayer(layer: MediaLayer, movie: BoardMovie?, layers: List<MediaLayer>): MovieGroup? {
        if (layer.kind != "video") return null
        val members = layer.clips
            .filter { clipInMovie(movie, it.id, layers) }
            .sortedWith(compareBy<LayerClip> { it.startSec }.thenBy { it.id })
        if (members.isEmpty()) return null
        val first = members.first()
        val start = first.startSec
        val end = members.fold(start) { n, clip -> max(n, clip.startSec + clip.durationSec) }
        val live = resolvedMovie(movie, layers)
        val span = max(0.1, end - start)
        val play = if (live != null) moviePlayDuration(live) else span
        return MovieGroup(
            members = members,
            clip = LayerClip(
                id = first.id,
                src = live?.src?.takeIf { it.isNotEmpty() } ?: first.src,
                name = if (members.size > 1) "Movie · ${members.size}" else first.name.ifEmpty { "Movie" },
                startSec = start,
                durationSec = play,
                trimInSec = 0.0,
                sourceDurationSec = jsOr(live?.durationSec, span),
                linkedFrameId = first.linkedFrameId,
            ),
        )
    }

    fun nextStitchTarget(layers: List<MediaLayer>, movie: BoardMovie?): LayerClip? {
        val ordered = orderedVideoClips(layers)
        if (ordered.size < 2) return null
        if (movie == null || movie.parts.isEmpty()) return ordered[1]
        var lastIdx = -1
        for (part in movie.parts) {
            val i = ordered.indexOfFirst { it.id == part.clipId }
            if (i > lastIdx) lastIdx = i
        }
        if (lastIdx < 0) return ordered[1]
        return ordered.getOrNull(lastIdx + 1)
    }

    fun canStitchClip(
        clipId: String,
        src: String?,
        layers: List<MediaLayer>,
        movie: BoardMovie?,
    ): Boolean {
        if (src.isNullOrEmpty()) return false
        val next = nextStitchTarget(layers, movie)
        return next != null && next.id == clipId
    }

    /** Distribute a trimmed movie length across its parts, eating from the right. */
    fun moviePartDurations(
        originals: List<Double>,
        playDuration: Double,
        minDur: Double = 0.1,
    ): List<Double> {
        val n = originals.size
        if (n == 0) return emptyList()
        val maxes = originals.map { max(minDur, it) }
        val out = MutableList(n) { minDur }
        var left = max(minDur, playDuration)
        for (i in 0 until n) {
            val laterMin = minDur * (n - 1 - i)
            val take = if (i == n - 1) {
                minOf(maxes[i], max(minDur, left))
            } else {
                minOf(maxes[i], max(minDur, left - laterMin))
            }
            out[i] = take
            left -= take
        }
        return out
    }

    private fun movieMemberIds(live: BoardMovie, layers: List<MediaLayer>): Set<String> {
        if (live.parts.isNotEmpty()) return live.parts.map { it.clipId }.toSet()
        val first = orderedVideoClips(layers).firstOrNull()
        return if (first != null) setOf(first.id) else emptySet()
    }

    private fun jsOr(value: Double?, fallback: Double): Double {
        return if (value != null && value != 0.0 && !value.isNaN()) value else fallback
    }
}
