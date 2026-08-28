package io.grokify.os.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpotifyDjPromptsTest {

    private fun customNews(): DjPromptTemplate = DjPromptTemplate(
        id = "custom_research_news",
        kind = DjPromptKind.Research,
        label = "USA news",
        body = "NEWS: USA headlines this week. City: {{CITY}}.",
        enabled = true,
        builtIn = false,
    )

    private fun customBanter(): DjPromptTemplate = DjPromptTemplate(
        id = "custom_banter_sports",
        kind = DjPromptKind.Banter,
        label = "Sports desk",
        body = "BANTER BIT: Drop one real US sports score or headline. City: {{CITY}}.",
        enabled = true,
        builtIn = false,
    )

    @Test
    fun pickResearch_rotatesAcrossEnabledIncludingCustom() {
        val custom = customNews()
        val all = DjPromptDefaults.researchAngles() + custom
        val counts = HashMap<String, Int>()
        var customEveryTime = true
        repeat(200) { seed ->
            val picked = pickResearchTemplates(all, Random(seed.toLong()))
            assertTrue(picked.isNotEmpty())
            assertTrue("pack too big: ${picked.map { it.id }}", picked.size in 1..3)
            assertTrue(picked.all { it.enabled && it.body.isNotBlank() })
            if (picked.none { it.id == custom.id }) customEveryTime = false
            picked.forEach { t -> counts[t.id] = (counts[t.id] ?: 0) + 1 }
        }
        val customHits = counts[custom.id] ?: 0
        assertTrue("custom never picked: $counts", customHits > 8)
        assertFalse("custom forced every pack ($customHits/200): $counts", customEveryTime)
        assertTrue("custom dominates every pack: $customHits", customHits < 160)
        val builtInHits = counts.filterKeys { it != custom.id }.values.sum()
        assertTrue("built-ins starved: $counts", builtInHits > 80)
        DjPromptDefaults.researchAngles().forEach { a ->
            assertTrue("${a.id} never picked: $counts", (counts[a.id] ?: 0) > 0)
        }
    }

    @Test
    fun pickResearch_disabledStayOutOfPool() {
        val offBuiltIn = DjPromptDefaults.researchAngles().map {
            it.copy(enabled = it.id == "lyrics_themes")
        }
        val offCustom = customNews().copy(enabled = false)
        val all = offBuiltIn + offCustom
        repeat(40) { seed ->
            val picked = pickResearchTemplates(all, Random(seed.toLong()))
            assertTrue(picked.all { it.id == "lyrics_themes" })
            assertTrue(picked.none { it.id == offCustom.id })
        }
    }

    @Test
    fun pickResearch_onlyCustomWhenBuiltInsOff() {
        val custom = customNews().copy(id = "custom_only")
        val builtOff = DjPromptDefaults.researchAngles().map { it.copy(enabled = false) }
        val picked = pickResearchTemplates(builtOff + custom, Random(1))
        assertEquals(listOf("custom_only"), picked.map { it.id })
    }

    @Test
    fun pickResearch_prefersUnusedOverRecent() {
        val all = DjPromptDefaults.researchAngles()
        val unused = all.last()
        val recent = all.dropLast(1).map { it.id }
        repeat(25) { seed ->
            val picked = pickResearchTemplates(all, Random(seed.toLong()), recentIds = recent)
            assertTrue(
                "unused ${unused.id} missing on seed=$seed picked=${picked.map { it.id }}",
                picked.any { it.id == unused.id },
            )
        }
    }

    @Test
    fun pickBanter_rotatesCustomWithBuiltInExtras() {
        val all = DjPromptDefaults.all() + customBanter()
        var customHits = 0
        var extraHits = 0
        repeat(80) { seed ->
            val picked = pickBanterTemplates(all, Random(seed.toLong()))
            assertTrue(picked.any { it.id == DjPromptDefaults.ID_BANTER_HANDOFF })
            if (picked.any { it.id == "custom_banter_sports" }) customHits++
            if (picked.any { it.id != DjPromptDefaults.ID_BANTER_HANDOFF && it.id != "custom_banter_sports" }) {
                extraHits++
            }
        }
        assertTrue("custom banter never picked ($customHits/80)", customHits > 4)
        assertTrue("custom banter forced every talk ($customHits/80)", customHits < 75)
        assertTrue("built-in extras starved ($extraHits/80)", extraHits > 10)
    }

    @Test
    fun pickBanter_disabledCustomNotForced() {
        val off = customBanter().copy(enabled = false)
        val all = DjPromptDefaults.all() + off
        repeat(20) { seed ->
            val picked = pickBanterTemplates(all, Random(seed.toLong()))
            assertTrue(picked.none { it.id == off.id })
        }
    }

    @Test
    fun talkingPointsForPack_onlyRequiresCustomResearchWhenPicked() {
        val handoff = DjPromptDefaults.banterBits().first { it.id == DjPromptDefaults.ID_BANTER_HANDOFF }
        val lyrics = DjPromptDefaults.researchAngles().first { it.id == "lyrics_themes" }
        val without = talkingPointsForPack(
            DjTalkPack(research = listOf(lyrics), banterBits = listOf(handoff)),
        )
        assertTrue(without.none { it.id == "custom_research_news" })
        assertTrue(without.any { it.id == DjPromptDefaults.ID_BANTER_HANDOFF })

        val withCustom = talkingPointsForPack(
            DjTalkPack(
                research = listOf(customNews(), lyrics),
                banterBits = listOf(handoff, customBanter()),
            ),
        )
        assertTrue(withCustom.any { it.id == "custom_research_news" })
        assertTrue(withCustom.any { it.id == "custom_banter_sports" })
    }

    @Test
    fun rememberRecentPromptIds_keepsNewestAndDropsOldest() {
        val next = rememberRecentPromptIds(
            existing = listOf("a", "b", "c", "d"),
            picked = listOf("e", "f"),
            cap = 4,
        )
        assertEquals(listOf("e", "f", "a", "b"), next)
    }

    @Test
    fun researchFieldMask_matchesPickedAnglesOnly() {
        val lyrics = DjPromptDefaults.researchAngles().first { it.id == "lyrics_themes" }
        val custom = customNews()
        val mask = researchFieldMask(listOf(lyrics, custom))
        assertTrue(mask.lyrics)
        assertTrue(mask.custom)
        assertFalse(mask.albumArtist)
        assertFalse(mask.shows)
        assertFalse(mask.social)
        assertFalse(mask.radio)

        val album = DjPromptDefaults.researchAngles().first { it.id == "album_song_facts" }
        val albumMask = researchFieldMask(listOf(album))
        assertTrue(albumMask.albumArtist)
        assertFalse(albumMask.custom)
        assertFalse(albumMask.lyrics)
    }

    @Test
    fun talkingPointsBlock_fillsCityAndMarksRequired() {
        val points = listOf(customNews(), customBanter())
        val block = formatBanterTalkingPointsBlock(points, city = "Denver")
        assertTrue(block.contains("REQUIRED ON-AIR TALKING POINTS"))
        assertTrue(block.contains("USA news"))
        assertTrue(block.contains("Sports desk"))
        assertTrue(block.contains("Denver"))
        assertFalse(block.contains("{{CITY}}"))
        assertTrue(block.contains("generic", ignoreCase = true) || block.contains("that was"))
    }

    @Test
    fun banterUserPrompt_requiresTemplatesInsteadOfOnlyHandoff() {
        val points = listOf(customNews(), customBanter())
        val prompt = buildBanterUserPrompt(
            DjBanterUserPromptInput(
                behaviorLabel = "Default",
                listenerName = "Sam",
                city = "Denver",
                genres = listOf("country"),
                prevCleanTitle = "Last Night",
                prevPrimary = "Morgan Wallen",
                prevSource = "LIVE DJ",
                nextCleanTitle = "Fast Car",
                nextPrimary = "Luke Combs",
                nextSource = "LIVE DJ",
                tracksUntilTalk = 0,
                upcoming = listOf("Tennessee Whiskey" to "Chris Stapleton"),
                research = emptyList(),
                talkingPoints = points,
                unhinged = false,
            ),
        )
        assertTrue(prompt.contains("USA news"))
        assertTrue(prompt.contains("Sports desk"))
        assertTrue(prompt.contains("NEWS: USA headlines"))
        assertTrue(prompt.contains("Denver"))
        assertTrue(
            prompt.contains("REQUIRED ON-AIR TALKING POINTS") ||
                prompt.contains("You MUST cover"),
        )
        assertFalse(
            "prompt still forces only was/next:\n$prompt",
            prompt.contains("close out the previous vibe, drop one researched beat"),
        )
        assertFalse(
            "empty research overrode templates:\n$prompt",
            prompt.contains("pure handoff"),
        )
    }

    @Test
    fun merge_seedsBanterBitsAndKeepsEditedBanterSystem() {
        val edited = DjPromptDefaults.banterSystem().copy(
            body = "EDITED BANTER SYSTEM: always mention tacos.",
        )
        val merged = mergePromptTemplates(listOf(edited, customBanter()))
        assertTrue(merged.any { it.kind == DjPromptKind.Banter && it.builtIn })
        val sys = merged.first { it.kind == DjPromptKind.BanterSystem }
        assertEquals("EDITED BANTER SYSTEM: always mention tacos.", sys.body)
        assertTrue(merged.any { it.id == "custom_banter_sports" })
    }

    @Test
    fun banterUserPrompt_focusLineIsNotAFakeCustomBeat() {
        val prompt = buildBanterUserPrompt(
            DjBanterUserPromptInput(
                behaviorLabel = "Default",
                listenerName = "Sam",
                city = "Denver",
                prevCleanTitle = "Last Night",
                prevPrimary = "Morgan Wallen",
                nextCleanTitle = "Fast Car",
                nextPrimary = "Luke Combs",
                research = listOf("Research focus: USA news (custom) + Lyrics & meaning"),
                talkingPoints = listOf(customNews()),
            ),
        )
        assertFalse(
            "focus-only pack must not demand a Custom:/News: beat that does not exist:\n$prompt",
            prompt.contains("CUSTOM ANGLE REQUIRED"),
        )
        assertTrue(prompt.contains("REQUIRED ON-AIR TALKING POINTS"))
        assertTrue(prompt.contains("USA news"))
    }

    @Test
    fun appendTalkingPointsToSystem_keepsEditedRules() {
        val system = appendBanterTalkingPointsToSystem(
            "EDITED RULES: whisper only.",
            formatBanterTalkingPointsBlock(listOf(customNews()), city = "Austin"),
        )
        assertTrue(system.startsWith("EDITED RULES: whisper only."))
        assertTrue(system.contains("USA news"))
        assertTrue(system.contains("Austin"))
    }
}
