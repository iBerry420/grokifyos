package io.grokify.os.apps.lyre

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class LyreWaveformTest {
    @Test
    fun parseMinMaxMidObject() {
        val raw = JSONObject()
            .put("max", JSONArray().put(0.2).put(0.9).put(0.1))
            .put("min", JSONArray().put(-0.1).put(-0.8).put(-0.05))
            .put("mid", JSONArray().put(0.0).put(0.0).put(0.0))
        val peaks = LyreWaveform.parse(raw)!!
        assertEquals(3, peaks.max.size)
        assertEquals(0.9f, peaks.max[1], 0.0001f)
        assertEquals(-0.8f, peaks.min!![1], 0.0001f)
        val json = peaks.toJson()
        assertEquals(3, json.getJSONArray("max").length())
        val sliced = LyreWaveform.slice(peaks, 1.0, 1.0, 3.0)
        assertEquals(1, sliced.max.size)
    }

    @Test
    fun parseNumberArray() {
        val peaks = LyreWaveform.parse(JSONArray().put(0.1).put(0.5).put(0.2))!!
        assertEquals(0.5f, peaks.max[1], 0.0001f)
    }

    @Test
    fun downsampleKeepsPeaks() {
        val src = FloatArray(100) { if (it == 50) 0.8f else 0.01f }
        val out = LyreWaveform.downsample(src, 10)
        assertEquals(10, out.size)
        assertTrue(out.maxOrNull()!! >= 0.79f)
    }

    @Test
    fun wavSineHasEnergy() {
        val dir = kotlin.io.path.createTempDirectory("lyre-wav").toFile()
        val file = File(dir, "sine.wav")
        writeSineWav(file, seconds = 0.2, rate = 8000)
        val peaks = LyreWaveform.peaksFromWav(file, bins = 32)
        assertNotNull(peaks)
        assertTrue(peaks!!.max.maxOrNull()!! > 0.2f)
        val pcm = LyreWaveform.peaksFromPcm16(
            ShortArray(256) { i -> (sin(2 * PI * i / 16.0) * 20000).toInt().toShort() },
            bins = 8,
        )
        assertTrue(pcm.max.maxOrNull()!! > 0.4f)
    }

    private fun writeSineWav(file: File, seconds: Double, rate: Int) {
        val n = (seconds * rate).toInt()
        val data = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = (sin(2 * PI * 440.0 * i / rate) * 16000).toInt()
            data[i * 2] = (s and 0xff).toByte()
            data[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff)
            out.write((v shr 24) and 0xff)
        }
        fun u16(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }
        out.write("RIFF".toByteArray())
        u32(36 + data.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        u32(16)
        u16(1)
        u16(1)
        u32(rate)
        u32(rate * 2)
        u16(2)
        u16(16)
        out.write("data".toByteArray())
        u32(data.size)
        out.write(data)
        file.writeBytes(out.toByteArray())
    }
}
