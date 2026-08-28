package io.grokify.os.apps.lyre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LyreActivityTest {
    @Test
    fun appendAndReadNewestFirst() {
        val dir = createTempDirectory("lyre-act").toFile()
        val log = LyreActivity(File(dir, "activity.jsonl"))
        log.append(LyreActivityLine(1L, "still", "p1", frameId = "fr_a", summary = "first"))
        log.append(LyreActivityLine(2L, "clip", "p1", clipId = "lc_a", summary = "second"))
        val newest = log.readNewestFirst()
        assertEquals(2, newest.size)
        assertEquals("second", newest[0].summary)
        assertEquals("first", newest[1].summary)
        assertTrue(newest[0].jumpable)
    }

    @Test
    fun actorRoundTripAndBotPrefix() {
        val line = LyreActivityLine(
            ts = 9L,
            type = "place",
            projectId = "p1",
            summary = "Placed Hall clip",
            actor = "bot",
        )
        val parsed = LyreActivityLine.fromJson(line.toJson())
        assertEquals("bot", parsed?.actor)
        assertEquals("Placed Hall clip", parsed?.summary)
        assertEquals("Bot · Placed Hall clip", parsed?.displaySummary())
        val phone = LyreActivityLine(3L, "edit", "p1", summary = "Trim", actor = "phone")
        assertEquals("Trim", phone.displaySummary())
        assertEquals("phone", LyreActivityLine.fromJson(phone.toJson())?.actor)
    }

    @Test
    fun seedWritesOnce() {
        val dir = createTempDirectory("lyre-act").toFile()
        val file = File(dir, "activity.jsonl")
        val log = LyreActivity(file)
        val board = BoardData(
            title = "Odysseus",
            brainstorm = "",
            scenes = listOf(
                Scene(
                    id = "sc_1",
                    title = "Hall",
                    book = "",
                    durationTargetSec = 0.0,
                    logline = "",
                    dialogue = "",
                    notes = "",
                    frames = listOf(
                        Frame(
                            id = "fr_a",
                            src = "me:stills/st_a.jpg",
                            caption = "Hearth",
                            durationSec = 4.0,
                            videoSrc = "me:videos/vid_a.mp4",
                            createdAt = 1000L,
                        ),
                    ),
                ),
            ),
            activeSceneId = "sc_1",
            refFolders = listOf(RefFolder(id = "lib", name = "Library", images = emptyList())),
            activeFolderId = "lib",
            videoLayers = listOf(
                MediaLayer(
                    id = "ly_v",
                    kind = "video",
                    name = "V",
                    clips = listOf(
                        LayerClip(
                            id = "lc_a",
                            src = "me:videos/vid_a.mp4",
                            name = "A",
                            startSec = 0.0,
                            durationSec = 4.0,
                            linkedFrameId = "fr_a",
                        ),
                    ),
                ),
            ),
            movie = BoardMovie(
                src = "me:videos/vid_movie.mp4",
                durationSec = 4.0,
                parts = listOf(MoviePart("lc_a", "me:videos/vid_a.mp4", 4.0)),
            ),
        )
        log.seedFromBoardIfEmpty(board, "proj")
        log.seedFromBoardIfEmpty(board, "proj")
        val all = log.readAll()
        assertTrue(all.any { it.type == "seed" })
        assertTrue(all.any { it.type == "still" || it.type == "clip" })
        assertTrue(all.any { it.type == "movie" })
        assertEquals(all.size, log.readAll().size)
        val jump = lyreJumpTime(board, all.first { it.frameId == "fr_a" })
        assertEquals(0.0, jump)
    }

    @Test
    fun replaceFromServerEmptyTruncatesToZeroBytes() {
        val dir = createTempDirectory("lyre-act").toFile()
        val file = File(dir, "activity.jsonl")
        val log = LyreActivity(file)
        log.append(LyreActivityLine(1L, "still", "p1", summary = "keep"))
        assertTrue(file.length() > 0L)
        log.replaceFromServer(emptyList())
        assertEquals(0L, file.length())
        assertTrue(log.isEmpty())
        assertTrue(log.readAll().isEmpty())
    }

    @Test
    fun skipsBlankLines() {
        val dir = createTempDirectory("lyre-act").toFile()
        val file = File(dir, "activity.jsonl")
        file.writeText("\n{not json}\n{\"ts\":9,\"type\":\"ui\",\"projectId\":\"p\",\"summary\":\"ok\"}\n")
        val log = LyreActivity(file)
        val all = log.readAll()
        assertEquals(1, all.size)
        assertEquals("ok", all[0].summary)
    }
}
