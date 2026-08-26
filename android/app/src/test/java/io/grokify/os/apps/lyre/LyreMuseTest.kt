package io.grokify.os.apps.lyre

import io.grokify.os.apps.plugin.INTERNAL_SESSION_TITLE_PREFIX
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyreMuseTest {
    @Test
    fun sessionTitleIsInternalLyreMuse() {
        assertTrue(LyreMuse.SESSION_TITLE.startsWith(INTERNAL_SESSION_TITLE_PREFIX))
        assertEquals("· LYRE Muse", LyreMuse.SESSION_TITLE)
        assertEquals(INTERNAL_SESSION_TITLE_PREFIX + " LYRE Muse", LyreMuse.SESSION_TITLE)
    }

    @Test
    fun digestIncludesRailsAndCaps() {
        val board = leftoverBoard()
        val activity = (1..9).map { i ->
            LyreActivityLine(ts = i.toLong(), type = "edit", projectId = "lyre", summary = "act$i", sceneId = null, frameId = null, clipId = null)
        }
        val focused = LyreMuse.digest(board, "Odyssey", 6.0, activity)
        assertTrue(focused.contains("Project: Odyssey"))
        assertTrue(focused.contains("Scenes: Scene 1"))
        assertTrue(focused.contains("Leftover clips: 1"))
        assertTrue(focused.contains("Focused leftover: B"))
        assertTrue(focused.contains("Dialogue: Speak"))
        assertTrue(focused.contains("Notes: Note"))
        assertTrue(focused.contains("act2"))
        assertTrue(focused.contains("act9"))
        assertFalse(focused.contains("act1"))
        assertTrue(focused.length <= LyreMuse.DIGEST_CAP)

        val atZero = LyreMuse.digest(board, "Odyssey", 0.0, emptyList())
        assertTrue(atZero.contains("Leftover clips: 1"))
        assertFalse(atZero.contains("Focused leftover:"))
        assertTrue(atZero.contains("Movie duration:"))

        val huge = "x".repeat(8000)
        val capped = LyreMuse.digest(board, huge, 6.0, emptyList())
        assertEquals(LyreMuse.DIGEST_CAP, capped.length)
        assertTrue(capped.startsWith("Project: "))
    }

    @Test
    fun parseReplyOkErrorEmpty() {
        val ok = LyreMuse.parseReply("""{"ok":true,"text":"hello"}""")
        assertEquals(true, ok.first)
        assertEquals("hello", ok.second)

        val empty = LyreMuse.parseReply("""{"ok":true,"text":"  "}""")
        assertEquals(false, empty.first)
        assertEquals("empty", empty.second)

        val err = LyreMuse.parseReply("""{"ok":false,"error":"nope"}""")
        assertEquals(false, err.first)
        assertEquals("nope", err.second)

        val hinted = LyreMuse.parseReply("""{"ok":false,"error":"nope","hint":"try again"}""")
        assertEquals(false, hinted.first)
        assertEquals("nope: try again", hinted.second)

        val bad = LyreMuse.parseReply("not-json")
        assertEquals(false, bad.first)
        assertEquals("muse_failed", bad.second)
    }

    @Test
    fun parseUiMessagesSkipsEmpty() {
        val ui = JSONObject().put(
            "museMessages",
            JSONArray()
                .put(JSONObject().put("role", "user").put("text", "hi"))
                .put(JSONObject().put("role", "").put("text", "ok"))
                .put(JSONObject().put("role", "muse").put("text", "")),
        )
        val msgs = LyreMuse.parseUiMessages(ui)
        assertEquals(2, msgs.size)
        assertEquals("user", msgs[0].role)
        assertEquals("hi", msgs[0].text)
        assertEquals("muse", msgs[1].role)
        assertEquals("ok", msgs[1].text)
        assertTrue(LyreMuse.parseUiMessages(null).isEmpty())
        assertTrue(LyreMuse.parseUiMessages(JSONObject()).isEmpty())
    }

    private fun leftoverBoard(): BoardData {
        val raw = fixture("unstitched")
        return raw.copy(
            scenes = raw.scenes.map { scene ->
                scene.copy(
                    frames = scene.frames.map { frame ->
                        if (frame.id != "fr_b") frame else frame.copy(dialogue = "Speak", notes = "Note")
                    },
                )
            },
        )
    }

    private fun fixture(name: String): BoardData {
        val path = "/io/grokify/os/apps/lyre/fixtures/$name.json"
        val text = LyreMuseTest::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("missing $path")
        return LyreBoardCodec.decode(JSONObject(text))
    }
}
