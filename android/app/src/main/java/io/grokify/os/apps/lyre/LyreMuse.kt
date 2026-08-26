package io.grokify.os.apps.lyre

import android.content.Context
import io.grokify.os.apps.plugin.HostAiClient
import io.grokify.os.apps.plugin.INTERNAL_SESSION_TITLE_PREFIX
import org.json.JSONObject

data class LyreMuseMessage(
    val role: String,
    val text: String,
)

object LyreMuse {
    const val SESSION_TITLE = INTERNAL_SESSION_TITLE_PREFIX + " LYRE Muse"
    const val DIGEST_CAP = 6000
    const val TRANSCRIPT_CAP = 100

    private const val SYSTEM =
        "You are LYRE Muse, a storyboard editor for this board. " +
            "Answer from the rails digest. Do not request or invent binary media."

    fun digest(
        board: BoardData,
        projectName: String,
        playheadSec: Double,
        activity: List<LyreActivityLine>,
    ): String {
        val clips = LyreClip.movieClips(board.scenes)
        val focused = LyreClip.clipAtTime(clips, playheadSec)
        val leftovers = LyreMovie.orderedVideoClips(board.videoLayers).count { clip ->
            !LyreMovie.clipInMovie(board.movie, clip.id, board.videoLayers)
        }
        val movieDur = board.movie?.let { LyreMovie.moviePlayDuration(it) }
            ?: LyreClip.movieDuration(board.scenes)
        val text = buildString {
            append("Project: ")
            appendLine(projectName.ifBlank { board.title }.ifBlank { "Untitled" })
            append("Scenes: ")
            appendLine(board.scenes.joinToString { it.title.ifBlank { it.id } })
            append("Movie duration: ")
            append(movieDur)
            appendLine("s")
            append("Leftover clips: ")
            appendLine(leftovers)
            val leftover = focused?.frame?.id?.let { LyreRules.leftoverFrame(board, it) }
            if (leftover != null) {
                append("Focused leftover: ")
                append(leftover.caption.ifBlank { leftover.id })
                append(" (")
                append(focused.sceneTitle.ifBlank { focused.sceneId })
                appendLine(")")
                leftover.dialogue?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append("Dialogue: ")
                    appendLine(it)
                }
                leftover.notes?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append("Notes: ")
                    appendLine(it)
                }
            }
            val recent = if (activity.size <= 8) activity else activity.subList(activity.size - 8, activity.size)
            if (recent.isNotEmpty()) {
                appendLine("Recent activity:")
                recent.forEach { line ->
                    append("- ")
                    appendLine(line.summary)
                }
            }
        }
        return if (text.length <= DIGEST_CAP) text else text.substring(0, DIGEST_CAP)
    }

    /** Blocking CountDownLatch complete; caller must use Dispatchers.IO. */
    fun complete(ctx: Context, userText: String, digest: String): String {
        val options = JSONObject()
            .put("session_title", SESSION_TITLE)
            .put("system", SYSTEM + "\n\n" + digest)
            .toString()
        return HostAiClient.complete(ctx.applicationContext, userText, options)
    }

    fun parseReply(raw: String): Pair<Boolean, String> {
        val env = runCatching { JSONObject(raw) }.getOrNull()
            ?: return false to "muse_failed"
        if (!env.optBoolean("ok", false)) {
            val err = env.optString("error").ifBlank { "muse_failed" }
            val hint = env.optString("hint")
            return false to if (hint.isNotEmpty()) "$err: $hint" else err
        }
        val text = env.optString("text").trim()
        return if (text.isEmpty()) false to "empty" else true to text
    }

    fun parseUiMessages(ui: JSONObject?): List<LyreMuseMessage> {
        val arr = ui?.optJSONArray("museMessages") ?: return emptyList()
        val out = ArrayList<LyreMuseMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = o.optString("role")
            val text = o.optString("text")
            if (text.isEmpty()) continue
            out.add(LyreMuseMessage(role = role.ifBlank { "muse" }, text = text))
        }
        return out
    }
}
