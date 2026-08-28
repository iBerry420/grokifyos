package io.grokify.os.apps.lyre

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.PI

/**
 * Desktop LYRE envelope: `{ on, points: [{ t, v, shape }] }`.
 * `t` is 0–1 of presented clip length; `v` is 0–1 gain; `shape` is the
 * interpolation used *leaving* that point (`linear` or `sine`).
 */
data class EnvelopePoint(
    val t: Double,
    val v: Double,
    val shape: String = "linear",
)

data class EnvelopeHandlePos(
    val x: Float,
    val y: Float,
)

data class VolumeEnvelope(
    val on: Boolean,
    val points: List<EnvelopePoint>,
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (p in points) {
            arr.put(
                JSONObject()
                    .put("t", p.t)
                    .put("v", p.v)
                    .put("shape", p.shape.ifBlank { "linear" }),
            )
        }
        return JSONObject().put("on", on).put("points", arr)
    }
}

object LyreEnvelope {
    fun parse(raw: Any?): VolumeEnvelope? {
        val obj = when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: return null
        val arr = obj.optJSONArray("points") ?: return VolumeEnvelope(
            on = obj.optBoolean("on", false),
            points = defaultPoints(1.0),
        )
        val pts = ArrayList<EnvelopePoint>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val t = num(p, "t") ?: continue
            val v = num(p, "v") ?: num(p, "gain") ?: continue
            val shape = p.optString("shape").ifBlank { "linear" }
            pts += EnvelopePoint(t = t.coerceIn(0.0, 1.0), v = v.coerceIn(0.0, 1.0), shape = shape)
        }
        pts.sortBy { it.t }
        if (pts.isEmpty()) return VolumeEnvelope(obj.optBoolean("on", false), defaultPoints(1.0))
        return VolumeEnvelope(on = obj.optBoolean("on", true), points = pts)
    }

    fun parseClip(clip: LayerClip): VolumeEnvelope? = parse(clip.envelope)

    /** Instantaneous gain 0–1 at stills-clock time `t`. */
    fun gainAt(clip: LayerClip, t: Double): Double {
        val vol = (clip.volume ?: 1.0).coerceIn(0.0, 1.0)
        if (vol <= 0.0) return 0.0
        val env = parseClip(clip) ?: return vol
        if (!env.on || env.points.isEmpty()) return vol
        val dur = clip.durationSec.coerceAtLeast(0.0001)
        val local = ((t - clip.startSec) / dur).coerceIn(0.0, 1.0)
        return vol * sample(env.points, local)
    }

    /** Map envelope t/v into a padded rect so v=0/1 handles stay on-screen. */
    fun handlePos(t: Double, v: Double, width: Float, height: Float, pad: Float): EnvelopeHandlePos {
        val innerW = (width - 2f * pad).coerceAtLeast(1f)
        val innerH = (height - 2f * pad).coerceAtLeast(1f)
        val x = pad + t.toFloat().coerceIn(0f, 1f) * innerW
        val y = pad + (1f - v.toFloat().coerceIn(0f, 1f)) * innerH
        return EnvelopeHandlePos(x = x, y = y)
    }

    fun tFromX(x: Float, width: Float, pad: Float): Double {
        val inner = (width - 2f * pad).coerceAtLeast(1f)
        return ((x - pad) / inner).toDouble().coerceIn(0.0, 1.0)
    }

    fun vFromY(y: Float, height: Float, pad: Float): Double {
        val inner = (height - 2f * pad).coerceAtLeast(1f)
        return (1.0 - ((y - pad) / inner).toDouble()).coerceIn(0.0, 1.0)
    }

    fun sample(points: List<EnvelopePoint>, t: Double): Double {
        if (points.isEmpty()) return 1.0
        val u = t.coerceIn(0.0, 1.0)
        if (u <= points.first().t) return points.first().v
        if (u >= points.last().t) return points.last().v
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (u < a.t || u > b.t) continue
            if (u == a.t) return a.v
            val span = (b.t - a.t).coerceAtLeast(1e-9)
            val x = ((u - a.t) / span).coerceIn(0.0, 1.0)
            val w = if (a.shape.equals("sine", ignoreCase = true) ||
                a.shape.equals("cosine", ignoreCase = true) ||
                a.shape.equals("ease", ignoreCase = true)
            ) {
                0.5 - 0.5 * cos(PI * x)
            } else {
                x
            }
            return a.v + (b.v - a.v) * w
        }
        return points.last().v
    }

    fun defaultPoints(gain: Double = 1.0): List<EnvelopePoint> {
        val v = gain.coerceIn(0.0, 1.0)
        return listOf(
            EnvelopePoint(0.0, v, "linear"),
            EnvelopePoint(1.0, v, "linear"),
        )
    }

    fun enabled(clip: LayerClip, gain: Double = clip.volume ?: 1.0): VolumeEnvelope {
        val existing = parseClip(clip)
        val pts = existing?.points?.takeIf { it.size >= 2 } ?: defaultPoints(gain)
        return VolumeEnvelope(on = true, points = pts)
    }

    fun disabled(clip: LayerClip): VolumeEnvelope {
        val existing = parseClip(clip)
        return VolumeEnvelope(on = false, points = existing?.points ?: defaultPoints(clip.volume ?: 1.0))
    }

    fun fadeIn(clip: LayerClip, fadeSec: Double): VolumeEnvelope {
        val env = enabled(clip)
        val dur = clip.durationSec.coerceAtLeast(0.0001)
        val t = (fadeSec.coerceAtLeast(0.05) / dur).coerceIn(0.05, 0.95)
        val peak = env.points.maxOfOrNull { it.v }?.coerceIn(0.05, 1.0) ?: 1.0
        val rest = env.points.filter { it.t > t + 0.02 }.map {
            if (it.t >= 0.999) it.copy(t = 1.0, v = peak.coerceAtLeast(it.v)) else it
        }
        val pts = ArrayList<EnvelopePoint>()
        pts += EnvelopePoint(0.0, 0.0, "sine")
        pts += EnvelopePoint(t, peak, "linear")
        if (rest.none { it.t >= 0.999 }) pts += EnvelopePoint(1.0, peak, "linear")
        pts += rest
        return VolumeEnvelope(on = true, points = merge(pts))
    }

    fun fadeOut(clip: LayerClip, fadeSec: Double): VolumeEnvelope {
        val env = enabled(clip)
        val dur = clip.durationSec.coerceAtLeast(0.0001)
        val t = (1.0 - fadeSec.coerceAtLeast(0.05) / dur).coerceIn(0.05, 0.95)
        val peak = env.points.maxOfOrNull { it.v }?.coerceIn(0.05, 1.0) ?: 1.0
        val head = env.points.filter { it.t < t - 0.02 }.map {
            if (it.t <= 0.001) it.copy(t = 0.0, v = peak.coerceAtLeast(it.v)) else it
        }
        val pts = ArrayList<EnvelopePoint>()
        if (head.none { it.t <= 0.001 }) pts += EnvelopePoint(0.0, peak, "linear")
        pts += head
        pts += EnvelopePoint(t, peak, "sine")
        pts += EnvelopePoint(1.0, 0.0, "linear")
        return VolumeEnvelope(on = true, points = merge(pts))
    }

    fun movePoint(clip: LayerClip, index: Int, t: Double, v: Double): VolumeEnvelope {
        val env = enabled(clip)
        if (index !in env.points.indices) return env
        val pts = env.points.toMutableList()
        val lockedT = when (index) {
            0 -> 0.0
            pts.lastIndex -> 1.0
            else -> t.coerceIn(0.02, 0.98)
        }
        pts[index] = pts[index].copy(t = lockedT, v = v.coerceIn(0.0, 1.0))
        pts.sortBy { it.t }
        return VolumeEnvelope(on = true, points = pts)
    }

    fun addPoint(clip: LayerClip, t: Double, v: Double): VolumeEnvelope {
        val env = enabled(clip)
        val nt = t.coerceIn(0.02, 0.98)
        if (env.points.any { kotlin.math.abs(it.t - nt) < 0.015 }) return env
        val pts = env.points.toMutableList()
        pts += EnvelopePoint(nt, v.coerceIn(0.0, 1.0), "linear")
        pts.sortBy { it.t }
        return VolumeEnvelope(on = true, points = pts)
    }

    fun removePoint(clip: LayerClip, index: Int): VolumeEnvelope {
        val env = enabled(clip)
        if (env.points.size <= 2) return env
        if (index <= 0 || index >= env.points.lastIndex) return env
        val pts = env.points.toMutableList()
        pts.removeAt(index)
        return VolumeEnvelope(on = true, points = pts)
    }

    private fun merge(points: List<EnvelopePoint>): List<EnvelopePoint> {
        val sorted = points.sortedBy { it.t }
        val out = ArrayList<EnvelopePoint>(sorted.size)
        for (p in sorted) {
            val last = out.lastOrNull()
            if (last != null && kotlin.math.abs(last.t - p.t) < 0.008) {
                out[out.lastIndex] = p.copy(t = last.t)
            } else {
                out += p
            }
        }
        if (out.none { it.t <= 0.001 }) out.add(0, EnvelopePoint(0.0, out.firstOrNull()?.v ?: 1.0, "linear"))
        if (out.none { it.t >= 0.999 }) out += EnvelopePoint(1.0, out.lastOrNull()?.v ?: 1.0, "linear")
        return out.mapIndexed { i, p ->
            when (i) {
                0 -> p.copy(t = 0.0)
                out.lastIndex -> p.copy(t = 1.0)
                else -> p
            }
        }
    }

    private fun num(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return try {
            when (val v = obj.get(key)) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

}
