package io.grokify.os.apps.lyre

import org.json.JSONObject

data class LyreHead(
    val ok: Boolean,
    val boardId: String,
    val updatedAt: String,
    val activityBytes: Long,
    val movieLocked: Boolean = false,
    val nextStitchClipId: String? = null,
    val error: String? = null,
) {
    companion object {
        fun fromJson(obj: JSONObject): LyreHead {
            return LyreHead(
                ok = obj.optBoolean("ok", false),
                boardId = obj.optString("board_id"),
                updatedAt = obj.optString("updated_at"),
                activityBytes = optLongish(obj, "activity_bytes"),
                movieLocked = obj.optBoolean("movie_locked", false),
                nextStitchClipId = obj.optStringOrNull("next_stitch_clip_id"),
                error = obj.optStringOrNull("error"),
            )
        }

        private fun optLongish(obj: JSONObject, key: String): Long {
            return when (val v = obj.opt(key)) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull() ?: 0L
                else -> 0L
            }
        }
    }
}

enum class LyrePollKind {
    NOOP,
    BANNER,
    RELOAD,
    PULL_ACTIVITY,
    RELOAD_AND_ACTIVITY,
}

data class LyrePollDecision(
    val kind: LyrePollKind,
    val banner: String? = null,
    val reloadBoard: Boolean = false,
    val pullActivity: Boolean = false,
)

enum class LyreSavePoll {
    SAVED,
    CONFLICT_RELOAD,
    KEEP_DIRTY,
    RETRY,
}

object LyrePoll {
    const val INTERVAL_MS = 2000L
    const val BANNER = "Server updated — tap Reload"

    fun shouldFetchHead(
        saveInFlight: Boolean,
        boardDirty: Boolean,
        scrubbing: Boolean,
        genBusy: Boolean,
    ): Boolean {
        if (scrubbing || genBusy) return false
        // Dirty / in-flight saves still fetch so the reload banner can appear.
        return true
    }

    fun skipTick(
        saveInFlight: Boolean,
        boardDirty: Boolean,
        scrubbing: Boolean,
        genBusy: Boolean,
    ): Boolean = saveInFlight || boardDirty || scrubbing || genBusy

    fun decide(
        saveInFlight: Boolean,
        boardDirty: Boolean,
        lastSeenUpdatedAt: String,
        lastActivityBytes: Long,
        head: LyreHead,
    ): LyrePollDecision {
        if (!head.ok) return LyrePollDecision(LyrePollKind.NOOP)
        val stampChanged = head.updatedAt.isNotEmpty() && head.updatedAt != lastSeenUpdatedAt
        val activityChanged = head.activityBytes != lastActivityBytes
        if (!stampChanged && !activityChanged) return LyrePollDecision(LyrePollKind.NOOP)
        if (stampChanged && (boardDirty || saveInFlight)) {
            return LyrePollDecision(
                kind = LyrePollKind.BANNER,
                banner = BANNER,
                pullActivity = false,
            )
        }
        if (stampChanged && activityChanged) {
            return LyrePollDecision(
                kind = LyrePollKind.RELOAD_AND_ACTIVITY,
                reloadBoard = true,
                pullActivity = true,
            )
        }
        if (stampChanged) {
            return LyrePollDecision(
                kind = LyrePollKind.RELOAD,
                reloadBoard = true,
            )
        }
        return LyrePollDecision(
            kind = LyrePollKind.PULL_ACTIVITY,
            pullActivity = true,
        )
    }

    fun onSaveResponse(ok: Boolean, error: String?, queuedLocal: Boolean = false): LyreSavePoll {
        if (ok) return if (queuedLocal) LyreSavePoll.RETRY else LyreSavePoll.SAVED
        if (error == "conflict") {
            return if (queuedLocal) LyreSavePoll.RETRY else LyreSavePoll.CONFLICT_RELOAD
        }
        return LyreSavePoll.KEEP_DIRTY
    }
}

enum class LyreActivityFirstOpen {
    PUSH_LOCAL,
    PULL_SERVER,
    SEED_LOCAL,
}

object LyreActivitySync {
    fun firstOpenPlan(serverBytes: Long, localNonEmpty: Boolean): LyreActivityFirstOpen {
        if (serverBytes <= 0L && localNonEmpty) return LyreActivityFirstOpen.PUSH_LOCAL
        if (serverBytes <= 0L) return LyreActivityFirstOpen.SEED_LOCAL
        return LyreActivityFirstOpen.PULL_SERVER
    }

    fun linesFromJson(obj: JSONObject): List<LyreActivityLine> {
        val arr = obj.optJSONArray("lines") ?: return emptyList()
        val out = ArrayList<LyreActivityLine>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val parsed = LyreActivityLine.fromJson(row) ?: continue
            out += parsed
        }
        return out
    }
}
