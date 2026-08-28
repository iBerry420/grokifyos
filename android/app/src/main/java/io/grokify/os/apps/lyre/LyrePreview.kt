package io.grokify.os.apps.lyre

/**
 * RAM-preview decisions: keep the next video decoded on a standby player and
 * only cover the stage with a still during holds (or a paused poster).
 */
object LyrePreview {
    fun coverWithStill(
        target: LyreClockTarget?,
        playing: Boolean,
        videoReady: Boolean,
    ): Boolean {
        return when (target) {
            is LyreClockTarget.Hold, null -> true
            is LyreClockTarget.Movie, is LyreClockTarget.Leftover -> !playing && !videoReady
        }
    }

    fun preloadTargetId(board: BoardData, t: Double): String? {
        val sc = LyreClip.clipAtTime(LyreClip.movieClips(board.scenes), t) ?: return null
        val current = LyreTransport.targetId(lyreClockTarget(board, t))
        val next = lyreNextVideoClip(board, sc.frame.id) ?: return null
        val nextId = LyreTransport.targetId(lyreClockTarget(board, next.start))
        if (nextId == null || nextId == current) return null
        return nextId
    }

    fun shouldPromoteRam(
        playing: Boolean,
        targetId: String?,
        frontId: String?,
        ended: Boolean,
        ramId: String?,
        ramReady: Boolean,
    ): Boolean {
        if (!playing || !ramReady) return false
        if (targetId == null) return false
        if (targetId == frontId) return false
        return ramId == targetId
    }

    fun shouldSeekFront(
        promoteRam: Boolean,
        currentId: String?,
        currentPos: Double,
        targetId: String?,
        targetPos: Double,
        prepared: Boolean,
        seekInFlight: Boolean,
    ): Boolean {
        if (promoteRam) return false
        if (targetId == null) return false
        return LyreTransport.shouldSeek(
            currentId,
            currentPos,
            targetId,
            targetPos,
            prepared,
            seekInFlight,
        )
    }
}
