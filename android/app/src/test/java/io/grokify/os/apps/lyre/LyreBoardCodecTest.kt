package io.grokify.os.apps.lyre

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyreBoardCodecTest {
    @Test
    fun roundTripPreservesUiObject() {
        val json = JSONObject()
            .put("title", "Odysseus")
            .put("brainstorm", "")
            .put("scenes", JSONArray().put(emptyScene()))
            .put("activeSceneId", "sc_1")
            .put("refFolders", JSONArray().put(emptyFolder()))
            .put("activeFolderId", "lib")
            .put("videoLayers", JSONArray())
            .put("audioLayers", JSONArray())
            .put("libraryAudio", JSONArray())
            .put("libraryVideo", JSONArray())
            .put(
                "ui",
                JSONObject()
                    .put("canvasMode", "video")
                    .put("loopClip", true)
                    .put("asidePane", "still")
                    .put("mysteryFlag", 7)
                    .put("layouts", JSONObject().put("main", "wide")),
            )
        val board = LyreBoardCodec.decode(json)
        val renamed = board.copy(title = "Odyssey")
        val out = LyreBoardCodec.encode(renamed)
        val ui = out.getJSONObject("ui")
        assertEquals("Odyssey", out.getString("title"))
        assertEquals("video", ui.getString("canvasMode"))
        assertTrue(ui.getBoolean("loopClip"))
        assertEquals("still", ui.getString("asidePane"))
        assertEquals(7, ui.getInt("mysteryFlag"))
        assertEquals("wide", ui.getJSONObject("layouts").getString("main"))
    }

    @Test
    fun preservesUnknownTopLevelKeys() {
        val json = JSONObject()
            .put("title", "Untitled")
            .put("brainstorm", "notes")
            .put("scenes", JSONArray().put(emptyScene()))
            .put("activeSceneId", "sc_1")
            .put("refFolders", JSONArray().put(emptyFolder()))
            .put("activeFolderId", "lib")
            .put("futureWidget", JSONObject().put("on", true).put("depth", 2))
            .put("legacyId", "abc")
        val out = LyreBoardCodec.encode(LyreBoardCodec.decode(json))
        assertEquals("abc", out.getString("legacyId"))
        val widget = out.getJSONObject("futureWidget")
        assertTrue(widget.getBoolean("on"))
        assertEquals(2, widget.getInt("depth"))
        assertEquals("notes", out.getString("brainstorm"))
    }

    @Test
    fun emptyBoardDecode() {
        val board = LyreBoardCodec.decode(LyreBoardCodec.emptyBoardJson())
        assertEquals("Untitled", board.title)
        assertEquals("", board.brainstorm)
        assertEquals("sc_1", board.activeSceneId)
        assertEquals("lib", board.activeFolderId)
        assertEquals(1, board.scenes.size)
        assertEquals("sc_1", board.scenes[0].id)
        assertEquals("Scene 1", board.scenes[0].title)
        assertTrue(board.scenes[0].frames.isEmpty())
        assertEquals(1, board.refFolders.size)
        assertEquals("Library", board.refFolders[0].name)
        assertTrue(board.videoLayers.isEmpty())
        assertTrue(board.audioLayers.isEmpty())
        assertTrue(board.libraryAudio.isEmpty())
        assertTrue(board.libraryVideo.isEmpty())
        val round = LyreBoardCodec.encode(board)
        assertEquals("Untitled", round.getString("title"))
        assertEquals("sc_1", round.getJSONArray("scenes").getJSONObject(0).getString("id"))
    }

    @Test
    fun moviePartsAndDualMediaFields() {
        val frame = JSONObject()
            .put("id", "fr_a")
            .put("src", "boards/lyre/frames/fr_a.jpg")
            .put("caption", "A")
            .put("durationSec", 4)
            .put("videoSrc", "boards/lyre/clips/lc_a.mp4")
            .put("origVideoSrc", "boards/lyre/orig/lc_a.mp4")
            .put("videoDurationSec", 4)
            .put("videoFps", 24)
            .put("videoMuted", false)
        val scene = emptyScene().put("frames", JSONArray().put(frame))
        val clip = JSONObject()
            .put("id", "lc_a")
            .put("src", "boards/lyre/clips/lc_a.mp4")
            .put("name", "A")
            .put("startSec", 0)
            .put("durationSec", 4)
            .put("linkedFrameId", "fr_a")
            .put("origSrc", "boards/lyre/orig/lc_a.mp4")
        val layer = JSONObject()
            .put("id", "ly_v")
            .put("kind", "video")
            .put("name", "V")
            .put("clips", JSONArray().put(clip))
        val movie = JSONObject()
            .put("src", "boards/lyre/movie.mp4")
            .put("durationSec", 7)
            .put("playDurationSec", 6.96)
            .put("fps", 24)
            .put("origSrc", "boards/lyre/movie.orig.mp4")
            .put(
                "parts",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("clipId", "lc_a")
                            .put("src", "boards/lyre/clips/lc_a.mp4")
                            .put("durationSec", 4),
                    )
                    .put(
                        JSONObject()
                            .put("clipId", "lc_b")
                            .put("src", "boards/lyre/clips/lc_b.mp4")
                            .put("durationSec", 3),
                    ),
            )
        val json = JSONObject()
            .put("title", "Odysseus")
            .put("brainstorm", "")
            .put("scenes", JSONArray().put(scene))
            .put("activeSceneId", "sc_1")
            .put("refFolders", JSONArray().put(emptyFolder()))
            .put("activeFolderId", "lib")
            .put("videoLayers", JSONArray().put(layer))
            .put("audioLayers", JSONArray())
            .put("libraryAudio", JSONArray())
            .put("libraryVideo", JSONArray())
            .put("movie", movie)

        val board = LyreBoardCodec.decode(json)
        assertEquals("boards/lyre/clips/lc_a.mp4", board.scenes[0].frames[0].videoSrc)
        assertEquals("boards/lyre/orig/lc_a.mp4", board.scenes[0].frames[0].origVideoSrc)
        assertEquals(24.0, board.scenes[0].frames[0].videoFps!!, 0.0)
        assertEquals(false, board.scenes[0].frames[0].videoMuted)
        assertEquals("fr_a", board.videoLayers[0].clips[0].linkedFrameId)
        assertEquals("boards/lyre/orig/lc_a.mp4", board.videoLayers[0].clips[0].origSrc)
        assertNotNull(board.movie)
        assertEquals(2, board.movie!!.parts.size)
        assertEquals("lc_a", board.movie!!.parts[0].clipId)
        assertEquals("lc_b", board.movie!!.parts[1].clipId)
        assertEquals(6.96, board.movie!!.playDurationSec!!, 0.0)

        val out = LyreBoardCodec.encode(board)
        val parts = out.getJSONObject("movie").getJSONArray("parts")
        assertEquals("lc_b", parts.getJSONObject(1).getString("clipId"))
        assertEquals(3, parts.getJSONObject(1).getInt("durationSec"))
        val outFrame = out.getJSONArray("scenes").getJSONObject(0).getJSONArray("frames").getJSONObject(0)
        assertEquals("boards/lyre/clips/lc_a.mp4", outFrame.getString("videoSrc"))
        assertEquals("boards/lyre/orig/lc_a.mp4", outFrame.getString("origVideoSrc"))
        assertFalse(outFrame.getBoolean("videoMuted"))
        val outClip = out.getJSONArray("videoLayers").getJSONObject(0).getJSONArray("clips").getJSONObject(0)
        assertEquals("fr_a", outClip.getString("linkedFrameId"))
        assertEquals("boards/lyre/orig/lc_a.mp4", outClip.getString("origSrc"))
    }

    private fun emptyScene(): JSONObject {
        return JSONObject()
            .put("id", "sc_1")
            .put("title", "Scene 1")
            .put("book", "")
            .put("durationTargetSec", 0)
            .put("logline", "")
            .put("dialogue", "")
            .put("notes", "")
            .put("frames", JSONArray())
    }

    private fun emptyFolder(): JSONObject {
        return JSONObject()
            .put("id", "lib")
            .put("name", "Library")
            .put("images", JSONArray())
    }
}
