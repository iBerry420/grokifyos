package io.grokify.os.apps.lyre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyreStorageKeysTest {
    @Test
    fun stripsMePrefix() {
        assertEquals("stills/st_n05cjkwshekr.jpg", LyreStorageKeys.normalize("me:stills/st_n05cjkwshekr.jpg"))
        assertEquals("videos/vid_rqhfchf1vana.mp4", LyreStorageKeys.normalize("me:videos/vid_rqhfchf1vana.mp4"))
        assertEquals("audio/st_4s24pyzzl4qw.mp3", LyreStorageKeys.normalize("me:audio/st_4s24pyzzl4qw.mp3"))
    }

    @Test
    fun publicStillsAndBoardKeys() {
        assertEquals("stills/hall-01.jpg", LyreStorageKeys.normalize("/stills/hall-01.jpg"))
        assertEquals(
            "boards/lyre/clips/lc_b.mp4",
            LyreStorageKeys.normalize("boards/lyre/clips/lc_b.mp4"),
        )
        assertEquals(
            "public/watch/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.mp4",
            LyreStorageKeys.normalize("public/watch/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.mp4"),
        )
    }

    @Test
    fun storageUrls() {
        assertEquals(
            "stills/st_abc.jpg",
            LyreStorageKeys.normalize("https://me.grokpot.io/v1/storage/stills/st_abc.jpg"),
        )
        assertEquals(
            "videos/vid_abc.mp4",
            LyreStorageKeys.normalize("https://me.grokpot.io/v1/storage/videos/vid_abc.mp4?download=1"),
        )
        assertEquals(
            "stills/st_abc.jpg",
            LyreStorageKeys.normalize("https://lyre.grok.me/api/storage/stills/st_abc.jpg"),
        )
        assertEquals(
            "stills/st_abc.jpg",
            LyreStorageKeys.normalize("https://lyre.grok.me/api/media?p=stills%2Fst_abc.jpg"),
        )
    }

    @Test
    fun rejectsTraversalAndUnknown() {
        assertNull(LyreStorageKeys.normalize("me:stills/../secret.jpg"))
        assertNull(LyreStorageKeys.normalize("etc/passwd"))
        assertNull(LyreStorageKeys.normalize(""))
        assertNull(LyreStorageKeys.normalize("me:"))
        assertEquals(
            "stills/st_abc.jpg",
            LyreStorageKeys.normalize("https://cdn.example/v1/storage/stills/st_abc.jpg"),
        )
    }

    @Test
    fun nestedMediaAndSeedKeys() {
        assertEquals("stills/folder/x.jpg", LyreStorageKeys.normalize("me:stills/folder/x.jpg"))
        assertEquals("seed/stills/hall-01.jpg", LyreStorageKeys.normalize("seed/stills/hall-01.jpg"))
        assertEquals("public/stills/ship-01.jpg", LyreStorageKeys.normalize("/public/stills/ship-01.jpg"))
        assertEquals("audio/st_ou28brulfs6h.wav", LyreStorageKeys.normalize("me:audio/st_ou28brulfs6h.wav"))
    }

    @Test
    fun stillSrcDetectsImagesNotVideoOrAudio() {
        assertEquals(true, LyreStorageKeys.isStillSrc("me:stills/st_a.jpg"))
        assertEquals(true, LyreStorageKeys.isStillSrc("/stills/hall-01.jpg"))
        assertEquals(true, LyreStorageKeys.isStillSrc("seed/stills/ship-01.jpg"))
        assertEquals(false, LyreStorageKeys.isStillSrc("me:videos/vid_a.mp4"))
        assertEquals(false, LyreStorageKeys.isStillSrc("me:audio/bed.wav"))
        assertEquals(false, LyreStorageKeys.isStillSrc("boards/lyre/clips/lc_b.mp4"))
    }
}
