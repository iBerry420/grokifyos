package io.grokify.os.apps.lyre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyreMovieGensTest {
    @Test
    fun pictureCompileKeyUsesOrigWhenLiveIsBurn() {
        val burn = BoardMovie(
            src = "boards/lyre/movie.burn.mp4",
            durationSec = 1.0,
            origSrc = "boards/lyre/movie.mp4",
            parts = emptyList(),
        )
        assertEquals("boards/lyre/movie.mp4", pictureCompileKey(burn))
    }

    @Test
    fun pictureCompileKeyUsesSrcWhenPicture() {
        val pic = BoardMovie(
            src = "boards/lyre/movie.mp4",
            durationSec = 1.0,
            parts = emptyList(),
        )
        assertEquals("boards/lyre/movie.mp4", pictureCompileKey(pic))
    }

    @Test
    fun pictureCompileKeyNullWhenBurnMissingOrig() {
        val burn = BoardMovie(
            src = "boards/x/movie.burn.mp4",
            durationSec = 1.0,
            origSrc = "",
            parts = emptyList(),
        )
        assertNull(pictureCompileKey(burn))
    }
}
