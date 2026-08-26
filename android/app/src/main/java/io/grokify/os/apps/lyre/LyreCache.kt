package io.grokify.os.apps.lyre

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.ErrnoException
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Pending op types: save_board | storage_put | publish. */
data class LyrePendingOp(
    val seq: Long,
    val type: String,
    val boardSnapshot: String? = null,
    val key: String? = null,
    val localPath: String? = null,
    val createdAtMs: Long = 0L,
    val failCount: Int = 0,
)

class LyreCache(
    ctx: Context,
    private val api: LyreApi,
    private val isOnline: () -> Boolean = { networkOnline(ctx.applicationContext) },
) {
    private val root = File(ctx.applicationContext.filesDir, "lyre")

    fun boardDir(boardId: String): File {
        val safe = boardId.replace(UNSAFE, "_").ifBlank { "_" }
        val dir = File(root, safe)
        dir.mkdirs()
        SUBDIRS.forEach { File(dir, it).mkdirs() }
        return dir
    }

    fun objectFile(boardId: String, objectKey: String): File {
        val ext = objectKey.substringAfterLast('.', "").lowercase()
        val safeExt = if (ext.matches(EXT)) ext else "bin"
        return File(File(boardDir(boardId), "objects"), "${sha1Hex(objectKey)}.$safeExt")
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

    fun writePending(boardId: String, op: LyrePendingOp, snapshotJson: JSONObject? = null): File {
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
        val snapshotRel = if (snapshotJson != null) {
            File(dir, "$seq.board.json").writeText(snapshotJson.toString())
            "pending/$seq.board.json"
        } else {
            op.boardSnapshot
        }
        snapshotRel?.let { json.put("boardSnapshot", it) }
        op.key?.let { json.put("key", it) }
        op.localPath?.let { json.put("localPath", it) }
        json.put("failCount", op.failCount)
        val f = File(dir, "$seq.json")
        f.writeText(json.toString())
        return f
    }

    fun online(): Boolean = isOnline()

    fun origFile(boardId: String, objectKey: String): File {
        return File(File(boardDir(boardId), "orig"), objectFile(boardId, objectKey).name)
    }

    fun tmpFile(boardId: String, suffix: String): File {
        val dir = File(boardDir(boardId), "tmp")
        dir.mkdirs()
        val ext = if (suffix.startsWith(".")) suffix else ".$suffix"
        return File(dir, "t${System.nanoTime()}$ext")
    }

    fun gensDir(boardId: String): File = File(boardDir(boardId), "movie-gens").also { it.mkdirs() }

    fun genFile(boardId: String, n: Int): File = File(gensDir(boardId), "g$n.mp4")

    fun undoDir(boardId: String): File = File(boardDir(boardId), "undo").also { it.mkdirs() }

    fun activityFile(boardId: String): File = File(boardDir(boardId), "activity.jsonl")

    fun objectRel(boardId: String, objectKey: String): String =
        "objects/${objectFile(boardId, objectKey).name}"

    fun pendingFile(op: LyrePendingOp, boardId: String): File? {
        val path = op.localPath ?: return null
        val f = if (path.startsWith("/")) File(path) else File(boardDir(boardId), path)
        return f.takeIf { it.isFile && it.length() > 0L }
    }

    fun listPending(boardId: String): List<Pair<File, LyrePendingOp>> {
        val dir = File(boardDir(boardId), "pending")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.matches(PENDING_NAME) }
            ?.mapNotNull { f -> readPending(f)?.let { f to it } }
            ?.sortedBy { it.second.seq }
            .orEmpty()
    }

    fun readPending(file: File): LyrePendingOp? {
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            val o = JSONObject(file.readText())
            val type = o.optString("type")
            if (type != "save_board" && type != "storage_put" && type != "publish") return null
            LyrePendingOp(
                seq = o.optLong("seq"),
                type = type,
                boardSnapshot = o.optString("boardSnapshot").takeIf { it.isNotEmpty() },
                key = o.optString("key").takeIf { it.isNotEmpty() },
                localPath = o.optString("localPath").takeIf { it.isNotEmpty() },
                createdAtMs = o.optLong("createdAtMs"),
                failCount = o.optInt("failCount"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun deletePending(file: File) {
        val seq = file.name.removeSuffix(".json")
        file.parentFile?.let { File(it, "$seq.board.json").delete() }
        file.delete()
    }

    fun hasPendingSave(boardId: String): Boolean =
        listPending(boardId).any { it.second.type == "save_board" }

    fun readPendingSnapshot(boardId: String, op: LyrePendingOp): JSONObject? {
        val rel = op.boardSnapshot ?: return null
        if (rel == "board.json") return readBoardJson(boardId)
        val f = File(boardDir(boardId), rel)
        if (!f.isFile || f.length() <= 0L) return null
        return try {
            JSONObject(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun ensureOrig(boardId: String, objectKey: String): File? {
        val orig = origFile(boardId, objectKey)
        if (orig.isFile && orig.length() > 0L) return orig
        val live = objectFile(boardId, objectKey)
        if (!live.isFile || live.length() <= 0L) return null
        copyReplace(live, orig)
        return orig
    }

    fun importObject(boardId: String, objectKey: String, src: File): File {
        val dest = objectFile(boardId, objectKey)
        copyReplace(src, dest)
        return dest
    }

    fun moveReplace(src: File, dest: File) {
        dest.parentFile?.mkdirs()
        if (src.absolutePath == dest.absolutePath) return
        Files.move(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun copyReplace(src: File, dest: File) {
        dest.parentFile?.mkdirs()
        if (src.absolutePath == dest.absolutePath) return
        val tmp = File(dest.parentFile, dest.name + ".part")
        src.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun linkOrCopy(src: File, dest: File) {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        try {
            Os.link(src.absolutePath, dest.absolutePath)
        } catch (_: ErrnoException) {
            copyReplace(src, dest)
        }
    }

    fun appendActivity(boardId: String, line: JSONObject) {
        val f = activityFile(boardId)
        f.appendText(line.toString() + "\n")
    }

    fun evict(boardId: String, keepObjectKeys: Set<String>) {
        val root = boardDir(boardId)
        val undo = File(root, "undo")
        val seqs = undo.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.toIntOrNull() ?: 0 }
            .orEmpty()
        if (seqs.size > UNDO_CAP) {
            seqs.dropLast(UNDO_CAP).forEach { it.deleteRecursively() }
        }
        fun total(): Long = dirSize(root)
        if (total() <= DISK_BUDGET) return
        val remaining = undo.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.toIntOrNull() ?: 0 }
            .orEmpty()
        for (d in remaining) {
            if (total() <= DISK_BUDGET) return
            d.deleteRecursively()
        }
        val keepNames = keepObjectKeys.map { objectFile(boardId, it).name }.toSet()
        val objects = File(root, "objects").listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        for (f in objects) {
            if (total() <= DISK_BUDGET) return
            if (f.name.endsWith(".part")) {
                f.delete()
                continue
            }
            if (f.name in keepNames) continue
            f.delete()
        }
        val origs = File(root, "orig").listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        for (f in origs) {
            if (total() <= DISK_BUDGET) return
            if (f.name in keepNames) continue
            f.delete()
        }
    }

    fun resolve(boardId: String, objectKey: String): File? {
        val dest = objectFile(boardId, objectKey)
        if (dest.isFile && dest.length() > 0L) {
            dest.setLastModified(System.currentTimeMillis())
            return dest
        }
        if (!isOnline()) return null
        val part = File(dest.path + ".part")
        fun fail(): File? {
            dest.delete()
            part.delete()
            return null
        }
        return try {
            api.getStorage(objectKey).use { resp ->
                if (!resp.isSuccessful) return fail()
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
        private val PENDING_NAME = Regex("""\d+\.json""")
        private val SUBDIRS = listOf("objects", "orig", "tmp", "movie-gens", "undo", "pending")
        const val DISK_BUDGET = 2L * 1024 * 1024 * 1024
        const val UNDO_CAP = 100

        private fun dirSize(dir: File): Long {
            if (!dir.exists()) return 0L
            var n = 0L
            dir.walkTopDown().forEach { if (it.isFile) n += it.length() }
            return n
        }

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
