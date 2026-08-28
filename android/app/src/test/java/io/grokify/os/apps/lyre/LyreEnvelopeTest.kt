package io.grokify.os.apps.lyre

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyreEnvelopeTest {
    @Test
    fun missingEnvelopeIsFullVolume() {
        val clip = LayerClip("lc", "a.wav", "A", 0.0, 10.0, volume = 0.8)
        assertEquals(0.8, LyreEnvelope.gainAt(clip, 3.0), 0.0001)
        assertEquals(0.0, LyreEnvelope.gainAt(clip.copy(volume = 0.0), 3.0), 0.0)
    }

    @Test
    fun odysseusNormalizedPoints() {
        val env = JSONObject()
            .put("on", true)
            .put(
                "points",
                JSONArray()
                    .put(point(0.0, 1.0, "linear"))
                    .put(point(0.058, 1.0, "linear"))
                    .put(point(0.099, 0.0, "sine"))
                    .put(point(1.0, 0.0, "linear")),
            )
        val clip = LayerClip(
            id = "lc",
            src = "a.wav",
            name = "Nostos",
            startSec = 0.0,
            durationSec = 231.28,
            volume = 1.0,
            envelope = env,
        )
        assertEquals(1.0, LyreEnvelope.gainAt(clip, 0.0), 0.001)
        assertEquals(1.0, LyreEnvelope.gainAt(clip, 0.05 * 231.28), 0.001)
        assertEquals(0.0, LyreEnvelope.gainAt(clip, 0.2 * 231.28), 0.001)
        val midT = (0.058 + 0.099) / 2.0
        val mid = LyreEnvelope.gainAt(clip, midT * 231.28)
        assertTrue(mid in 0.35..0.65)
        val parsed = LyreEnvelope.parse(env)!!
        assertTrue(parsed.on)
        assertEquals(4, parsed.points.size)
        val json = parsed.toJson()
        assertTrue(json.getBoolean("on"))
        assertEquals(4, json.getJSONArray("points").length())
    }

    @Test
    fun offEnvelopeIgnoresPoints() {
        val env = JSONObject()
            .put("on", false)
            .put("points", JSONArray().put(point(0.0, 0.0, "linear")).put(point(1.0, 0.0, "linear")))
        val clip = LayerClip("lc", "a.wav", "A", 0.0, 4.0, volume = 1.0, envelope = env)
        assertEquals(1.0, LyreEnvelope.gainAt(clip, 1.0), 0.001)
    }

    @Test
    fun sineEaseIsSmooth() {
        val pts = listOf(
            EnvelopePoint(0.0, 0.0, "sine"),
            EnvelopePoint(1.0, 1.0, "linear"),
        )
        assertEquals(0.0, LyreEnvelope.sample(pts, 0.0), 0.0001)
        assertEquals(1.0, LyreEnvelope.sample(pts, 1.0), 0.0001)
        val half = LyreEnvelope.sample(pts, 0.5)
        assertEquals(0.5, half, 0.0001)
        val q = LyreEnvelope.sample(pts, 0.25)
        assertTrue(q < 0.25)
        assertFalse(LyreEnvelope.disabled(LayerClip("lc", "a", "a", 0.0, 1.0)).on)
    }

    @Test
    fun handleLayoutInsetsZeroGainOffTheFloor() {
        val pad = 10f
        val h = 48f
        val w = 200f
        val zero = LyreEnvelope.handlePos(0.0, 0.0, w, h, pad)
        val peak = LyreEnvelope.handlePos(1.0, 1.0, w, h, pad)
        val left = LyreEnvelope.handlePos(0.0, 0.5, w, h, pad)
        val right = LyreEnvelope.handlePos(1.0, 0.5, w, h, pad)
        assertEquals(h - pad, zero.y, 0.01f)
        assertEquals(pad, peak.y, 0.01f)
        assertEquals(pad, left.x, 0.01f)
        assertEquals(w - pad, right.x, 0.01f)
        assertTrue(zero.y < h)
        assertTrue(peak.y > 0f)
        assertEquals(0.0, LyreEnvelope.vFromY(zero.y, h, pad), 0.001)
        assertEquals(1.0, LyreEnvelope.tFromX(right.x, w, pad), 0.001)
    }

    private fun point(t: Double, v: Double, shape: String) =
        JSONObject().put("t", t).put("v", v).put("shape", shape)
}
