package io.grokify.os.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyDjDislikesTest {
    @Test
    fun artistTokens_splitAmpersandAndFeat() {
        val tokens = djArtistNameTokens("Drake & 21 Savage")
        assertTrue(tokens.contains("drake"))
        assertTrue(tokens.contains("21 savage"))
        val feat = djArtistNameTokens("Metro Boomin feat. Future")
        assertTrue(feat.contains("metro boomin"))
        assertTrue(feat.contains("future"))
    }

    @Test
    fun titleKey_remasterMatchesCleanTitle() {
        val a = djTrackTitleKey("Last Night (Remastered)", "Morgan Wallen")
        val b = djTrackTitleKey("Last Night", "Morgan Wallen")
        assertEquals(a, b)
        assertTrue(a.startsWith("last night"))
    }

    @Test
    fun blockedTrack_hitsRemasterByTitleKey() {
        val key = djTrackTitleKey("Last Night", "Morgan Wallen")
        val blocked = mapOf(
            "spotify:track:aaa" to DjBlockedTrack(
                uri = "spotify:track:aaa",
                name = "Last Night",
                artists = "Morgan Wallen",
                titleKey = key,
            ),
        )
        assertTrue(
            djTrackIsBlocked(
                "spotify:track:bbb",
                "Last Night (Radio Edit)",
                "Morgan Wallen",
                blocked,
            ),
        )
        assertFalse(
            djTrackIsBlocked(
                "spotify:track:ccc",
                "Different Song",
                "Morgan Wallen",
                blocked,
            ),
        )
    }

    @Test
    fun blockedArtist_matchesSplitCredits() {
        val artists = mapOf(
            "name:drake" to DjBlockedArtist(
                key = "name:drake",
                name = "Drake",
                aliases = setOf("drake"),
            ),
        )
        assertTrue(djArtistIsBlocked(emptyList(), "Drake & 21 Savage", "", artists))
        assertTrue(djArtistIsBlocked(emptyList(), "Drake, 21 Savage", "", artists))
        assertFalse(djArtistIsBlocked(emptyList(), "21 Savage", "", artists))
        assertTrue(
            djArtistIsBlocked(
                listOf("spotify:artist:xyz"),
                "Someone",
                "",
                mapOf("xyz" to DjBlockedArtist(key = "xyz", name = "Someone")),
            ),
        )
    }

    @Test
    fun tired_matchesSameTitleDifferentUri() {
        val key = djTrackTitleKey("Espresso", "Sabrina Carpenter")
        val tired = mapOf(
            "spotify:track:old" to DjTiredTrack(
                uri = "spotify:track:old",
                name = "Espresso",
                artists = "Sabrina Carpenter",
                until = System.currentTimeMillis() + 86_400_000L,
                titleKey = key,
            ),
        )
        assertTrue(
            djTrackIsTired(
                "spotify:track:new",
                "Espresso (Album Version)",
                "Sabrina Carpenter",
                tired,
            ),
        )
    }

    @Test
    fun usableLabel_rejectsIdsAndUris() {
        assertFalse(djIsUsableLabel(""))
        assertFalse(djIsUsableLabel("spotify:track:4iV5W9uYEdYUVa79Axb7Rh"))
        assertFalse(djIsUsableLabel("4iV5W9uYEdYUVa79Axb7Rh"))
        assertFalse(djIsUsableLabel("https://open.spotify.com/track/4iV5W9uYEdYUVa79Axb7Rh"))
        assertTrue(djIsUsableLabel("Last Night"))
        assertTrue(djIsUsableLabel("Morgan Wallen"))
    }

    @Test
    fun blockedTrackTitle_usesNameAndArtistsNotUriTail() {
        val labeled = DjBlockedTrack(
            uri = "spotify:track:4iV5W9uYEdYUVa79Axb7Rh",
            name = "Last Night",
            artists = "Morgan Wallen",
            titleKey = "last night|morgan wallen",
        )
        assertEquals("Last Night", djBlockedTrackTitle(labeled))
        assertEquals("Morgan Wallen", djBlockedTrackArtists(labeled))

        val legacy = DjBlockedTrack(
            uri = "spotify:track:4iV5W9uYEdYUVa79Axb7Rh",
            name = "",
            artists = "",
        )
        assertEquals("Unknown song", djBlockedTrackTitle(legacy))
        assertFalse(djBlockedTrackTitle(legacy).contains("4iV5W9uYEdYUVa79Axb7Rh"))

        val fromKey = DjBlockedTrack(
            uri = "spotify:track:aaa",
            name = "spotify:track:aaa",
            artists = "",
            titleKey = "last night|morgan wallen",
        )
        assertEquals("Last Night", djBlockedTrackTitle(fromKey))
        assertEquals("Morgan Wallen", djBlockedTrackArtists(fromKey))
    }

    @Test
    fun decodeLegacyTrack_doesNotTreatUriAsName() {
        val raw = """[{"uri":"spotify:track:4iV5W9uYEdYUVa79Axb7Rh","reasons":["song"],"ts":1}]"""
        val back = decodeBlockedTracks(raw)
        assertEquals(1, back.size)
        assertEquals("", back[0].name)
        assertEquals("Unknown song", djBlockedTrackTitle(back[0]))
    }

    @Test
    fun parseSpotifyTracksObject_readsNameAndArtists() {
        val body = org.json.JSONObject(
            """
            {"tracks":[{"id":"4iV5W9uYEdYUVa79Axb7Rh","uri":"spotify:track:4iV5W9uYEdYUVa79Axb7Rh","name":"Last Night","artists":[{"id":"a1","name":"Morgan Wallen"}]}]}
            """.trimIndent(),
        )
        val parsed = djParseSpotifyTracksObject(body)
        assertEquals(1, parsed.size)
        assertEquals("Last Night", parsed[0].second)
        assertEquals("Morgan Wallen", parsed[0].third)
    }

    @Test
    fun codec_roundTripKeepsCountAndTimestamp() {
        val now = 1_700_000_000_000L
        val raw = encodeBlockedTracks(
            listOf(
                DjBlockedTrack(
                    uri = "spotify:track:1",
                    name = "Song",
                    artists = "Artist",
                    reasons = setOf(DjDislikeReason.SONG, DjDislikeReason.LYRICS),
                    count = 3,
                    firstTs = now - 1000,
                    lastTs = now,
                    titleKey = "song|artist",
                ),
            ),
        )
        val back = decodeBlockedTracks(raw)
        assertEquals(1, back.size)
        assertEquals(3, back[0].count)
        assertEquals(now, back[0].lastTs)
        assertEquals(setOf(DjDislikeReason.SONG, DjDislikeReason.LYRICS), back[0].reasons)
        assertEquals("song|artist", back[0].titleKey)
    }

}
