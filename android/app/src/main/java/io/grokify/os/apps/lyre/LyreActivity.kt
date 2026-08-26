package io.grokify.os.apps.lyre

import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

data class LyreActivityLine(
    val ts: Long,
    val type: String,
    val projectId: String,
    val summary: String,
    val sceneId: String?,
    val frameId: String?,
    val clipId: String?,
)

/** JSONL tail: last 1 MiB, skip a partial first line. */
object LyreActivity {
    private const val TAIL_BYTES = 1_048_576L

    fun read(cache: LyreCache, boardId: String): List<LyreActivityLine> {
        return parseFile(cache.activityFile(boardId))
    }

    fun last(cache: LyreCache, boardId: String, n: Int): List<LyreActivityLine> {
        if (n <= 0) return emptyList()
        val all = read(cache, boardId)
        return if (all.size <= n) all else all.subList(all.size - n, all.size)
    }

    internal fun parseFile(file: File): List<LyreActivityLine> {
        if (!file.isFile || file.length() <= 0L) return emptyList()
        val text = tailText(file) ?: return emptyList()
        return text.lineSequence().mapNotNull { parseLine(it) }.toList()
    }

    fun parseLine(line: String): LyreActivityLine? {
        val t = line.trim()
        if (t.isEmpty()) return null
        return try {
            val o = JSONObject(t)
            LyreActivityLine(
                ts = o.optLong("ts"),
                type = o.optString("type"),
                projectId = o.optString("projectId"),
                summary = o.optString("summary"),
                sceneId = o.optString("sceneId").takeIf { it.isNotEmpty() },
                frameId = o.optString("frameId").takeIf { it.isNotEmpty() },
                clipId = o.optString("clipId").takeIf { it.isNotEmpty() },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun tailText(file: File): String? {
        val len = file.length()
        if (len <= TAIL_BYTES) return runCatching { file.readText() }.getOrNull()
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(len - TAIL_BYTES)
                val buf = ByteArray(TAIL_BYTES.toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8).substringAfter('\n')
            }
        }.getOrNull()
    }
}
