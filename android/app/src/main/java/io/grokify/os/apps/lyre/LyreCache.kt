package io.grokify.os.apps.lyre

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Pending op types: save_board | storage_put | publish. */
data class LyrePendingOp(
    val seq: Long,
    val type: String,
    val boardSnapshot: String? = null,
    val key: String? = null,
    val localPath: String? = null,
    val createdAtMs: Long = 0L,
)

class LyreCache(
    ctx: Context,
    private val api: LyreApi,
    private val isOnline: () -> Boolean = { networkOnline(ctx.applicationContext) },
) {
    private val root = File(ctx.applicationContext.filesDir, "lyre")
    private val misses = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun boardDir(boardId: String): File {
        val safe = boardId.replace(UNSAFE, "_").ifBlank { "_" }
        val dir = File(root, safe)
        dir.mkdirs()
        SUBDIRS.forEach { File(dir, it).mkdirs() }
        return dir
    }

    fun objectFile(boardId: String, objectKey: String): File {
        val key = LyreStorageKeys.normalize(objectKey) ?: objectKey.trim()
        val ext = key.substringAfterLast('.', "").lowercase()
        val safeExt = if (ext.matches(EXT)) ext else "bin"
        return File(File(boardDir(boardId), "objects"), "${sha1Hex(key)}.$safeExt")
    }

    fun activityFile(boardId: String): File = File(boardDir(boardId), "activity.jsonl")

    fun writeObject(boardId: String, objectKey: String, bytes: ByteArray): File {
        val dest = objectFile(boardId, objectKey)
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        misses.remove(LyreStorageKeys.normalize(objectKey) ?: objectKey)
        return dest
    }

    fun writeBoardJson(boardId: String, data: JSONObject) {
        File(boardDir(boardId), "board.json").writeText(data.toString())
    }

    fun readBoardJson(boardId: String): JSONObject? {
        val f = File(boardDir(boardId), "board.json")
        if (!f.isFile || f.length() <= 0L) return null
        return try {
            JSONObject(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun writePending(boardId: String, op: LyrePendingOp): File {
        val type = op.type
        require(type == "save_board" || type == "storage_put" || type == "publish") {
            "unsupported pending type"
        }
        val dir = File(boardDir(boardId), "pending")
        dir.mkdirs()
        val seq = if (op.seq > 0L) op.seq else nextPendingSeq(dir)
        val json = JSONObject()
            .put("seq", seq)
            .put("type", type)
            .put("createdAtMs", op.createdAtMs)
        op.boardSnapshot?.let { json.put("boardSnapshot", it) }
        op.key?.let { json.put("key", it) }
        op.localPath?.let { json.put("localPath", it) }
        val f = File(dir, "$seq.json")
        f.writeText(json.toString())
        return f
    }

    fun resolve(boardId: String, objectKey: String): File? {
        val key = LyreStorageKeys.normalize(objectKey) ?: return null
        val dest = objectFile(boardId, key)
        if (dest.isFile && dest.length() > 0L) {
            misses.remove(key)
            return dest
        }
        val missAt = misses[key]
        if (missAt != null && System.currentTimeMillis() - missAt < MISS_TTL_MS) return null
        if (!isOnline()) return null
        val part = File(dest.path + ".part")
        fun fail(remember: Boolean = false): File? {
            dest.delete()
            part.delete()
            if (remember) misses[key] = System.currentTimeMillis()
            return null
        }
        return try {
            api.getStorage(key).use { resp ->
                if (!resp.isSuccessful) return fail(remember = resp.code == 404 || resp.code == 400)
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("json")) return fail(remember = true)
                val body = resp.body ?: return fail()
                val expected = resp.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
                part.parentFile?.mkdirs()
                val copied = part.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                }
                if (copied <= 0L || part.length() != copied || (expected != null && copied != expected)) {
                    return fail()
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                if (!dest.isFile || dest.length() <= 0L || (expected != null && dest.length() != expected)) {
                    return fail()
                }
                dest
            }
        } catch (_: Exception) {
            fail()
        }
    }

    private fun nextPendingSeq(dir: File): Long {
        var max = 0L
        dir.listFiles()?.forEach { f ->
            val n = f.name.removeSuffix(".json").toLongOrNull() ?: return@forEach
            if (n > max) max = n
        }
        return max + 1L
    }

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
        private val EXT = Regex("[a-z0-9]{1,8}")
        private val SUBDIRS = listOf("objects", "orig", "tmp", "movie-gens", "undo", "pending")
        private const val MISS_TTL_MS = 120_000L

        private fun sha1Hex(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { b -> "%02x".format(b) }
        }

        private fun networkOnline(ctx: Context): Boolean {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
