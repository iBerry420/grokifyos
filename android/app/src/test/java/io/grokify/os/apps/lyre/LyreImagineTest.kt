package io.grokify.os.apps.lyre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyreImagineTest {
    @Test
    fun capVoicesMaxThreeKnown() {
        assertEquals(listOf("eve", "leo", "rex"), LyreImagine.capVoices(listOf("eve", "LEO", "nope", "rex", "sal")))
        assertTrue(LyreImagine.capVoices(listOf("not-a-voice")).isEmpty())
    }

    @Test
    fun tagPromptAddsImageAndVoiceTokens() {
        val tagged = LyreImagine.tagPrompt("Walk toward the fire.", 2, 1)
        assertTrue(tagged.contains("<IMAGE_0>"))
        assertTrue(tagged.contains("<IMAGE_1>"))
        assertTrue(tagged.contains("<AUDIO_0>"))
        val already = LyreImagine.tagPrompt("Use <IMAGE_0> please", 2, 0)
        assertFalse(already.contains("<IMAGE_1>"))
    }
}
