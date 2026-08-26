package io.grokify.os.apps.lyre

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyreImagineClientTest {
    @Test
    fun grokmeUnavailableAndFailed() {
        val ok = JSONObject().put("ok", true).put("path", "boards/lyre/frames/fr.jpg")
        assertFalse(LyreImagine.grokmeFailed(ok))
        assertFalse(LyreImagine.grokmeUnavailable(ok))
        val miss = JSONObject().put("ok", false).put("error", "grokme_unavailable")
        assertTrue(LyreImagine.grokmeFailed(miss))
        assertTrue(LyreImagine.grokmeUnavailable(miss))
        val net = JSONObject().put("ok", false).put("error", "http_502")
        assertTrue(LyreImagine.grokmeUnavailable(net))
    }

    @Test
    fun filterVoicesDropsUnknownAndCaps() {
        assertEquals(
            listOf("eve", "ara", "leo"),
            LyreImagine.filterVoices(listOf("EVE", "nope", "ara", "leo", "rex")),
        )
        assertTrue(LyreImagine.filterVoices(listOf("not-a-voice")).isEmpty())
    }

    @Test
    fun taggedPromptAddsMissingTags() {
        assertEquals(
            "walk <IMAGE_0> <IMAGE_1> <AUDIO_0>",
            LyreImagine.taggedPrompt("walk", 2, 1),
        )
        assertEquals(
            "see <IMAGE_0> now <AUDIO_0>",
            LyreImagine.taggedPrompt("see <IMAGE_0> now", 1, 1),
        )
    }

    @Test
    fun coerceDurationAspectResolution() {
        assertEquals(6, LyreImagine.coerceDuration(6))
        assertEquals(10, LyreImagine.coerceDuration(10))
        assertEquals(10, LyreImagine.coerceDuration(12))
        assertEquals(6, LyreImagine.coerceDuration(3))
        assertEquals("16:9", LyreImagine.coerceAspect("wide"))
        assertEquals("9:16", LyreImagine.coerceAspect("9:16"))
        assertEquals("720p", LyreImagine.coerceResolution("1080p"))
        assertEquals("480p", LyreImagine.coerceResolution("480p"))
        assertEquals(6.0, LyreImagine.coerceEditDuration(6), 0.0)
        assertEquals(LyreImagine.XAI_EDIT_MAX_SEC, LyreImagine.coerceEditDuration(10), 0.0)
        assertEquals(LyreImagine.XAI_EDIT_MAX_SEC, LyreImagine.coerceEditDuration(12), 0.0)
    }

    @Test
    fun grokmeStatusBlipIsNotJobFailed() {
        val blip = JSONObject().put("ok", false).put("error", "http_502")
        assertTrue(LyreImagine.grokmeFailed(blip))
        assertFalse(LyreImagine.jobFailed(blip.optString("status")))
        val failed = JSONObject().put("ok", true).put("status", "failed")
        assertFalse(LyreImagine.grokmeFailed(failed))
        assertTrue(LyreImagine.jobFailed(failed.optString("status")))
    }

    @Test
    fun harvestObjectKeyAndUrl() {
        val still = JSONObject().put("ok", true).put("path", "boards/lyre/frames/fr.jpg")
        assertEquals("boards/lyre/frames/fr.jpg", LyreImagine.harvestObjectKey(still))
        val nested = JSONObject().put(
            "video",
            JSONObject().put("url", "https://vidgen.x.ai/a.mp4").put("path", "boards/lyre/clips/x.mp4"),
        )
        assertEquals("boards/lyre/clips/x.mp4", LyreImagine.harvestObjectKey(nested))
        assertEquals("https://vidgen.x.ai/a.mp4", LyreImagine.harvestUrl(nested))
        val data = JSONObject().put(
            "data",
            JSONArray().put(JSONObject().put("url", "https://cdn.x.ai/i.jpg")),
        )
        assertEquals("https://cdn.x.ai/i.jpg", LyreImagine.harvestUrl(data))
        assertEquals("abc12345", LyreImagine.requestId(JSONObject().put("request_id", "abc12345")))
        assertNull(LyreImagine.requestId(JSONObject().put("id", "short")))
    }

    @Test
    fun jobDoneAndFailed() {
        assertTrue(LyreImagine.jobDone("done"))
        assertTrue(LyreImagine.jobDone("completed"))
        assertFalse(LyreImagine.jobDone("pending"))
        assertTrue(LyreImagine.jobFailed("expired"))
        assertTrue(LyreImagine.jobFailed("failed"))
        assertFalse(LyreImagine.jobFailed("pending"))
    }

    @Test
    fun inlineImageReadsJpegBytes() {
        val f = File.createTempFile("lyre", ".jpg")
        try {
            f.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02))
            val img = LyreImagine.inlineImage(f)
            assertEquals("image/jpeg", img?.mimeType)
            assertTrue(img?.data?.isNotEmpty() == true)
            val url = LyreImagine.dataUrl(f)
            assertTrue(url!!.startsWith("data:image/jpeg;base64,"))
        } finally {
            f.delete()
        }
        assertNull(LyreImagine.inlineImage(File("/no/such/lyre.jpg")))
    }

    @Test
    fun dataUrlEncodesMp4() {
        val f = File.createTempFile("lyre", ".mp4")
        try {
            f.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70))
            val url = LyreImagine.dataUrl(f)
            assertTrue(url!!.startsWith("data:video/mp4;base64,"))
        } finally {
            f.delete()
        }
    }
}
