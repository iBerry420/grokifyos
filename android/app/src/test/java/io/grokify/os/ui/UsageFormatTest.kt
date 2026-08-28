package io.grokify.os.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageFormatTest {
    @Test
    fun compactTokens() {
        assertEquals("830", UsageFormat.compactTokens(830))
        assertEquals("79.1k", UsageFormat.compactTokens(79_050))
        assertEquals("1.5M", UsageFormat.compactTokens(1_500_000))
        assertEquals("12M", UsageFormat.compactTokens(12_000_000))
    }

    @Test
    fun compactDuration() {
        assertEquals("45s", UsageFormat.compactDuration(45))
        assertEquals("6m", UsageFormat.compactDuration(409))
        assertEquals("2h", UsageFormat.compactDuration(7_200))
        assertEquals("1.5h", UsageFormat.compactDuration(5_400))
    }
}
