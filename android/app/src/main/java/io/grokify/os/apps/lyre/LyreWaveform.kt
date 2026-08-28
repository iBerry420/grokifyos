package io.grokify.os.apps.lyre

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class WaveformPeaks(
    val min: FloatArray?,
    val max: FloatArray,
    val mid: FloatArray? = null,
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("max", floatArray(max))
        min?.let { o.put("min", floatArray(it)) }
        mid?.let { o.put("mid", floatArray(it)) }
        return o
    }

    private fun floatArray(values: FloatArray): JSONArray {
        val arr = JSONArray()
        for (v in values) arr.put(v.toDouble())
        return arr
    }
}

object LyreWaveform {
    const val BINS = 512

    fun parse(raw: Any?): WaveformPeaks? {
        return when (raw) {
            is JSONObject -> {
                val max = floats(raw.optJSONArray("max")) ?: floats(raw.optJSONArray("peaks"))
                val min = floats(raw.optJSONArray("min"))
                val mid = floats(raw.optJSONArray("mid"))
                when {
                    max != null && max.isNotEmpty() -> WaveformPeaks(min = min, max = max, mid = mid)
                    min != null && min.isNotEmpty() -> WaveformPeaks(min = min, max = min.map { abs(it) }.toFloatArray(), mid = mid)
                    else -> {
                        val arr = raw.optJSONArray("samples") ?: return null
                        val peaks = floats(arr) ?: return null
                        WaveformPeaks(min = null, max = peaks, mid = null)
                    }
                }
            }
            is JSONArray -> {
                val peaks = floats(raw) ?: return null
                if (peaks.isEmpty()) null else WaveformPeaks(min = null, max = peaks, mid = null)
            }
            else -> null
        }
    }

    fun parseClip(clip: LayerClip): WaveformPeaks? = parse(clip.waveform)

    fun slice(peaks: WaveformPeaks, trimInSec: Double, durationSec: Double, sourceDurationSec: Double?): WaveformPeaks {
        val native = sourceDurationSec?.takeIf { it > 0.0 } ?: (trimInSec + durationSec)
        if (native <= 0.0) return peaks
        val start = (trimInSec / native).coerceIn(0.0, 1.0)
        val end = ((trimInSec + durationSec) / native).coerceIn(start + 1e-4, 1.0)
        return WaveformPeaks(
            min = sliceArr(peaks.min, start, end),
            max = sliceArr(peaks.max, start, end) ?: peaks.max,
            mid = sliceArr(peaks.mid, start, end),
        )
    }

    fun downsample(values: FloatArray, bins: Int): FloatArray {
        if (bins <= 0 || values.size <= bins) return values
        val out = FloatArray(bins)
        for (i in 0 until bins) {
            val a = i * values.size / bins
            val b = max(a + 1, ((i + 1) * values.size) / bins)
            var m = 0f
            for (j in a until min(b, values.size)) {
                val v = abs(values[j])
                if (v > m) m = v
            }
            out[i] = m
        }
        return out
    }

    fun peaksFromWav(file: File, bins: Int = BINS): WaveformPeaks? {
        if (!file.isFile || file.length() < 44L) return null
        return runCatching { readWav(file, bins) }.getOrNull()
    }

    fun peaksFromPcm16(samples: ShortArray, bins: Int = BINS): WaveformPeaks {
        val n = samples.size.coerceAtLeast(1)
        val count = bins.coerceAtLeast(1)
        val minA = FloatArray(count) { 0f }
        val maxA = FloatArray(count) { 0f }
        val midA = FloatArray(count) { 0f }
        for (i in 0 until count) {
            val a = i * n / count
            val b = max(a + 1, ((i + 1) * n) / count)
            var lo = 0f
            var hi = 0f
            var sum = 0.0
            var c = 0
            for (j in a until min(b, n)) {
                val v = samples[j] / 32768f
                if (v < lo) lo = v
                if (v > hi) hi = v
                sum += v
                c++
            }
            minA[i] = lo
            maxA[i] = hi
            midA[i] = if (c > 0) (sum / c).toFloat() else 0f
        }
        return WaveformPeaks(min = minA, max = maxA, mid = midA)
    }

    private fun sliceArr(values: FloatArray?, start: Double, end: Double): FloatArray? {
        if (values == null || values.isEmpty()) return values
        val a = (start * values.size).toInt().coerceIn(0, values.size - 1)
        val b = (end * values.size).toInt().coerceIn(a + 1, values.size)
        return values.copyOfRange(a, b)
    }

    private fun floats(arr: JSONArray?): FloatArray? {
        if (arr == null || arr.length() == 0) return null
        val out = FloatArray(arr.length())
        for (i in 0 until arr.length()) {
            out[i] = when (val v = arr.opt(i)) {
                is Number -> v.toFloat()
                is String -> v.toFloatOrNull() ?: 0f
                else -> 0f
            }
        }
        return out
    }

    private fun readWav(file: File, bins: Int): WaveformPeaks? {
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(12)
            if (raf.read(riff) != 12) return null
            if (String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") return null
            var channels = 1
            var bits = 16
            var dataSize = -1L
            while (raf.filePointer + 8 <= raf.length()) {
                val idBytes = ByteArray(4)
                if (raf.read(idBytes) != 4) break
                val size = readU32(raf)
                val id = String(idBytes)
                val next = raf.filePointer + size + (size and 1)
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(min(size.toInt(), 64).coerceAtLeast(16))
                        raf.readFully(fmt)
                        channels = u16(fmt, 2).coerceAtLeast(1)
                        bits = u16(fmt, 14)
                    }
                    "data" -> {
                        dataSize = size
                        if (bits != 16) return null
                        val frames = (size / (channels * 2L)).toInt().coerceAtLeast(1)
                        val minA = FloatArray(bins) { 0f }
                        val maxA = FloatArray(bins) { 0f }
                        val buf = ByteArray(4096)
                        var frame = 0
                        var remaining = size
                        while (remaining > 0 && frame < frames) {
                            val n = raf.read(buf, 0, min(buf.size.toLong(), remaining).toInt())
                            if (n <= 0) break
                            remaining -= n
                            var i = 0
                            while (i + channels * 2 <= n) {
                                var peak = 0
                                for (ch in 0 until channels) {
                                    val off = i + ch * 2
                                    val s = (buf[off].toInt() and 0xff) or (buf[off + 1].toInt() shl 8)
                                    val signed = s.toShort().toInt()
                                    if (abs(signed) > abs(peak)) peak = signed
                                }
                                val bin = (frame.toLong() * bins / frames).toInt().coerceIn(0, bins - 1)
                                val v = peak / 32768f
                                if (v < minA[bin]) minA[bin] = v
                                if (v > maxA[bin]) maxA[bin] = v
                                frame++
                                i += channels * 2
                            }
                        }
                        return WaveformPeaks(min = minA, max = maxA, mid = null)
                    }
                    else -> Unit
                }
                if (dataSize >= 0) break
                raf.seek(min(next, raf.length()))
            }
        }
        return null
    }

    private fun readU32(raf: RandomAccessFile): Long {
        val b0 = raf.readUnsignedByte()
        val b1 = raf.readUnsignedByte()
        val b2 = raf.readUnsignedByte()
        val b3 = raf.readUnsignedByte()
        return b0.toLong() or (b1.toLong() shl 8) or (b2.toLong() shl 16) or (b3.toLong() shl 24)
    }

    private fun u16(buf: ByteArray, off: Int): Int {
        return (buf[off].toInt() and 0xff) or ((buf[off + 1].toInt() and 0xff) shl 8)
    }
}
