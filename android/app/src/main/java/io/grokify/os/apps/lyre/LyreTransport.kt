package io.grokify.os.apps.lyre

import kotlin.math.abs

object LyreTransport {
    const val SEEK_SLOP_SEC = 0.35

    fun targetId(target: LyreClockTarget?): String? {
        return when (target) {
            is LyreClockTarget.Movie -> "lc_movie"
            is LyreClockTarget.Leftover -> target.clipId
            is LyreClockTarget.Hold, null -> null
        }
    }

    fun itemMatches(target: LyreClockTarget?, item: LyrePlayItem?): Boolean {
        val id = targetId(target) ?: return false
        return item?.id == id
    }

    fun playerEnded(
        item: LyrePlayItem?,
        positionSec: Double,
        stateEnded: Boolean,
        epsilonSec: Double = 0.05,
    ): Boolean {
        if (item == null) return false
        if (stateEnded) return true
        return positionSec >= item.playDurationSec - epsilonSec
    }

    fun followPlayer(
        target: LyreClockTarget?,
        item: LyrePlayItem?,
        exoPlaying: Boolean,
        ended: Boolean,
    ): Boolean {
        if (!exoPlaying || ended) return false
        return itemMatches(target, item)
    }

    fun wantVideoPlay(
        resume: Boolean,
        target: LyreClockTarget?,
        item: LyrePlayItem?,
        ended: Boolean,
    ): Boolean {
        if (!resume) return false
        if (target == null || target is LyreClockTarget.Hold) return false
        if (ended && itemMatches(target, item)) return false
        return true
    }

    fun shouldSeek(
        currentId: String?,
        currentPos: Double,
        targetId: String,
        targetPos: Double,
        prepared: Boolean,
        seekInFlight: Boolean = false,
    ): Boolean {
        if (seekInFlight) return false
        if (currentId != targetId) return true
        if (!prepared) return false
        return abs(currentPos - targetPos) > SEEK_SLOP_SEC
    }

    fun nextPlayhead(
        board: BoardData,
        playhead: Double,
        dt: Double,
        duration: Double,
        item: LyrePlayItem?,
        playerPos: Double,
        follow: Boolean,
    ): Double {
        val raw = if (follow && item != null) {
            lyreStillsFromPlayItem(board, item, playerPos)
        } else {
            playhead + dt
        }
        return raw.coerceIn(0.0, duration.coerceAtLeast(0.0))
    }
}
