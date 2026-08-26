package io.grokify.os.apps.lyre

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyreActivityTest {
    @Test
    fun parseLineBlankAndZeroTs() {
        assertNull(LyreActivity.parseLine(""))
        assertNull(LyreActivity.parseLine("   "))
        assertNull(LyreActivity.parseLine("\n"))
        assertNull(LyreActivity.parseLine("not-json"))
        val missingTs = LyreActivity.parseLine(
            """{"type":"edit","projectId":"lyre","summary":"x","frameId":"fr_b"}""",
        )
        assertEquals(0L, missingTs!!.ts)
        assertEquals("edit", missingTs.type)
        assertEquals("lyre", missingTs.projectId)
        assertEquals("x", missingTs.summary)
        assertEquals("fr_b", missingTs.frameId)
        assertNull(missingTs.sceneId)
        assertNull(missingTs.clipId)

        val full = LyreActivity.parseLine(
            """{"ts":42,"type":"trim","projectId":"p","summary":"cut","sceneId":"sc_1","frameId":"fr_a","clipId":"lc_a"}""",
        )
        assertEquals(42L, full!!.ts)
        assertEquals("trim", full.type)
        assertEquals("sc_1", full.sceneId)
        assertEquals("fr_a", full.frameId)
        assertEquals("lc_a", full.clipId)
    }

    @Test
    fun parseFileEmptyBlankAndTail() {
        val empty = File.createTempFile("lyre-act-empty", ".jsonl")
        empty.writeText("")
        assertEquals(0L, empty.length())
        assertTrue(LyreActivity.parseFile(empty).isEmpty())

        val missing = File(empty.parentFile, "lyre-act-missing.jsonl")
        missing.delete()
        assertTrue(LyreActivity.parseFile(missing).isEmpty())

        val blanks = File.createTempFile("lyre-act-blank", ".jsonl")
        blanks.writeText("\n\n  \n")
        assertTrue(LyreActivity.parseFile(blanks).isEmpty())

        val small = File.createTempFile("lyre-act-small", ".jsonl")
        small.writeText(
            """
            {"ts":1,"type":"a","projectId":"p","summary":"one"}
            {"ts":2,"type":"b","projectId":"p","summary":"two"}

            {"ts":3,"type":"c","projectId":"p","summary":"three"}
            """.trimIndent() + "\n",
        )
        val parsed = LyreActivity.parseFile(small)
        assertEquals(3, parsed.size)
        assertEquals(listOf("one", "two", "three"), parsed.map { it.summary })
        assertEquals(0L, LyreActivity.parseLine("""{"type":"z","projectId":"p","summary":"zero"}""")!!.ts)

        val tail = File.createTempFile("lyre-act-tail", ".jsonl")
        tail.outputStream().use { out ->
            val junk = ByteArray(1024) { 'x'.code.toByte() }
            repeat(1024) { out.write(junk) }
            out.write('\n'.code)
            out.write("""{"ts":9,"type":"tail","projectId":"p","summary":"kept"}""".toByteArray())
            out.write('\n'.code)
        }
        assertTrue(tail.length() > 1_048_576L)
        val kept = LyreActivity.parseFile(tail)
        assertEquals(1, kept.size)
        assertEquals("kept", kept[0].summary)
        assertEquals(9L, kept[0].ts)
    }
}
