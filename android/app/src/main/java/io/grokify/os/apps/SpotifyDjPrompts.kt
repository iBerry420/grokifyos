package io.grokify.os.apps

import org.json.JSONArray
import org.json.JSONObject

/**
 * Editable prompt templates for Live DJ.
 *
 * Categories:
 * - [DjPromptKind.Research] — research angle briefs (multi-enable → random 1–3 each talk)
 * - [DjPromptKind.Behavior] — on-mic personality (one active)
 * - [DjPromptKind.Banter] — on-air talking points (multi-enable; handoff + rotating extras)
 * - [DjPromptKind.BanterSystem] — full on-air banter system rules (one body)
 * - [DjPromptKind.ResearchSystem] — research agent envelope (one body)
 * - [DjPromptKind.ChatSystem] — booth chat system rules (one body)
 * - [DjPromptKind.QueueRankSystem] — AI rank next-tracks music director (one body)
 * - [DjPromptKind.QueueRankUser] — AI rank user/request message shell (one body)
 *
 * Placeholders replaced at runtime (leave them in the body):
 * - Research angle: `{{CITY}}`
 * - Banter bit: `{{CITY}}`
 * - Research system: `{{ANGLE_BRIEFS}}`
 * - Banter system: `{{WORD_CAP}}`, `{{BEHAVIOR_STYLE}}`, `{{UNHINGED_EXTRA}}`, `{{NAME_BLOCK}}`
 * - Chat system: `{{BEHAVIOR_STYLE}}`
 * - Queue rank system: `{{N}}`, `{{GENRE_BIAS}}`
 * - Queue rank user: `{{CURRENT}}`, `{{BEHAVIOR}}`, `{{GENRE_BOARD_LINE}}`,
 *   `{{CITY_LINE}}`, `{{VIBE_LINE}}`, `{{CANDIDATES}}`, `{{N}}`
 */
enum class DjPromptKind {
    Research,
    Behavior,
    Banter,
    BanterSystem,
    ResearchSystem,
    ChatSystem,
    QueueRankSystem,
    QueueRankUser,
    ;

    val storageKey: String
        get() = when (this) {
            Research -> "research"
            Behavior -> "behavior"
            Banter -> "banter_talk"
            BanterSystem -> "banter_system"
            ResearchSystem -> "research_system"
            ChatSystem -> "chat_system"
            QueueRankSystem -> "queue_rank_system"
            QueueRankUser -> "queue_rank_user"
        }

    val sectionLabel: String
        get() = when (this) {
            Research -> "Research angles"
            Behavior -> "Behaviors"
            Banter -> "Banter bits"
            BanterSystem -> "Banter system"
            ResearchSystem -> "Research system"
            ChatSystem -> "Chat system"
            QueueRankSystem -> "Queue rank system"
            QueueRankUser -> "Queue rank request"
        }

    val sectionBlurb: String
        get() = when (this) {
            Research ->
                "Every enabled angle — built-in or custom — sits in one lottery. " +
                    "Each talk draws 1–3, preferring ones you have not heard recently. " +
                    "Off switches stay out. Edit briefs or add your own. Use {{CITY}} " +
                    "for the listener metro. A custom angle is required on-air only " +
                    "when it is in this cycle's pack."
            Behavior ->
                "Pick one personality for on-mic delivery (after research). " +
                    "Edit body or add a custom vibe."
            Banter ->
                "Spoken talking points. Track handoff stays on when enabled so the " +
                    "next cut is named. Other bits (built-in + custom) rotate. " +
                    "Use {{CITY}} for the listener metro."
            BanterSystem ->
                "Core rules for spoken handoff lines. Placeholders: " +
                    "{{WORD_CAP}} {{BEHAVIOR_STYLE}} {{UNHINGED_EXTRA}} {{NAME_BLOCK}}"
            ResearchSystem ->
                "Envelope for the music researcher agent. " +
                    "Must keep JSON-only reply shape. Placeholder: {{ANGLE_BRIEFS}}"
            ChatSystem ->
                "Booth chat steering rules (JSON actions). Placeholder: {{BEHAVIOR_STYLE}}"
            QueueRankSystem ->
                "System rules when AI rank picks next tracks from the candidate pool. " +
                    "Must keep JSON-only reply shape. Placeholders: {{N}} {{GENRE_BIAS}}"
            QueueRankUser ->
                "Request message sent with candidates (AI rank on). Placeholders: " +
                    "{{CURRENT}} {{BEHAVIOR}} {{GENRE_BOARD_LINE}} {{CITY_LINE}} " +
                    "{{VIBE_LINE}} {{CANDIDATES}} {{N}}"
        }

    companion object {
        fun fromStorage(raw: String?): DjPromptKind? =
            when (raw?.lowercase()?.trim()) {
                "research" -> Research
                "behavior" -> Behavior
                "banter_talk", "banter_bit", "talking_point" -> Banter
                "banter_system", "banter" -> BanterSystem
                "research_system" -> ResearchSystem
                "chat_system", "chat" -> ChatSystem
                "queue_rank_system", "queue_rank", "ai_rank", "ai_rank_system" -> QueueRankSystem
                "queue_rank_user", "ai_rank_user" -> QueueRankUser
                else -> null
            }
    }
}

data class DjPromptTemplate(
    val id: String,
    val kind: DjPromptKind,
    val label: String,
    val blurb: String = "",
    /** Prompt body / personality block / angle brief. */
    val body: String,
    /** Research: include in random pool. Behavior: available to select. */
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    /**
     * Optional flags for runtime (e.g. "unhinged_taste" adds roast requirements).
     */
    val flags: List<String> = emptyList(),
) {
    fun hasFlag(flag: String): Boolean =
        flags.any { it.equals(flag, ignoreCase = true) }

    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("kind", kind.storageKey)
            .put("label", label)
            .put("blurb", blurb)
            .put("body", body)
            .put("enabled", enabled)
            .put("builtIn", builtIn)
            .put(
                "flags",
                JSONArray().also { arr -> flags.forEach { arr.put(it) } },
            )

    companion object {
        fun fromJson(o: JSONObject?): DjPromptTemplate? {
            if (o == null) return null
            val id = o.optString("id", "").trim()
            val kind = DjPromptKind.fromStorage(o.optString("kind", "")) ?: return null
            if (id.isBlank()) return null
            val flagsArr = o.optJSONArray("flags")
            val flags = buildList {
                if (flagsArr != null) {
                    for (i in 0 until flagsArr.length()) {
                        val f = flagsArr.optString(i, "").trim()
                        if (f.isNotBlank()) add(f)
                    }
                }
            }
            return DjPromptTemplate(
                id = id,
                kind = kind,
                label = o.optString("label", id).ifBlank { id },
                blurb = o.optString("blurb", ""),
                body = o.optString("body", ""),
                enabled = o.optBoolean("enabled", true),
                builtIn = o.optBoolean("builtIn", false),
                flags = flags,
            )
        }
    }
}

/** Built-in defaults — also used for “Reset to default”. */
object DjPromptDefaults {
    const val FLAG_UNHINGED_TASTE = "unhinged_taste"

    const val ID_BANTER_HANDOFF = "banter_handoff"
    const val ID_BANTER_RESEARCH = "banter_research"
    const val ID_BANTER_TEASE = "banter_tease"
    const val ID_BANTER_SYSTEM = "banter_system_core"
    const val ID_RESEARCH_SYSTEM = "research_system_core"
    const val ID_CHAT_SYSTEM = "chat_system_core"
    const val ID_QUEUE_RANK_SYSTEM = "queue_rank_system_core"
    const val ID_QUEUE_RANK_USER = "queue_rank_user_core"

    fun all(): List<DjPromptTemplate> =
        researchAngles() + behaviors() + banterBits() + listOf(
            banterSystem(),
            researchSystem(),
            chatSystem(),
            queueRankSystem(),
            queueRankUser(),
        )

    fun researchAngles(): List<DjPromptTemplate> = listOf(
        DjPromptTemplate(
            id = "lyrics_themes",
            kind = DjPromptKind.Research,
            label = "Lyrics & meaning",
            blurb = "Themes / story of current + next songs",
            body =
                "LYRICS & MEANING: Look up what the CURRENT and NEXT songs are about — themes, " +
                    "story, vibe of the lyrics. Paraphrase only (≤28 words each). Never paste long " +
                    "lyric blocks or copyrighted lines.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "album_song_facts",
            kind = DjPromptKind.Research,
            label = "Album / song facts",
            blurb = "Release year, writers, samples, charts",
            body =
                "ALBUM / SONG FACTS: Album name, release year, writers, samples, chart peaks, " +
                    "awards, collabs, notable production notes. Prefer verified + recent when news.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "artist_facts",
            kind = DjPromptKind.Research,
            label = "Artist facts",
            blurb = "Career color, milestones, trivia",
            body =
                "ARTIST FACTS: Career color, recent milestones, side projects, beefs (tasteful), " +
                    "band lineup notes, fun verified trivia — not Wikipedia dump.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "shows_tours",
            kind = DjPromptKind.Research,
            label = "Shows & tours",
            blurb = "Concerts / tour legs; uses {{CITY}} when set",
            body =
                "SHOWS & TOURS: Real upcoming concerts / tour legs for these artists " +
                    "(city, date, venue when known). " +
                    "If listener city is set ({{CITY}}), check near that metro AND flag major " +
                    "national/international dates if more notable. Also note if familiar artists " +
                    "are coming to {{CITY}}. If city blank, national/global dates are fine.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "recent_x_social",
            kind = DjPromptKind.Research,
            label = "Recent X / social",
            blurb = "Buzz from the last ~2 weeks",
            body =
                "RECENT X / SOCIAL: Search recent posts or headlines about these artists/songs " +
                    "on X (Twitter) or breaking music social buzz in the last ~2 weeks. " +
                    "Short paraphrase only — no full post quotes, no invented viral moments.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "radio_host_color",
            kind = DjPromptKind.Research,
            label = "Radio host color",
            blurb = "Classic on-air spice / did-you-know",
            body =
                "RADIO HOST COLOR: Classic on-air spice — origin stories, sample credits, " +
                    "genre context, \"did you know\" moments, cultural placement, funny true " +
                    "anecdotes that a good radio host would drop. Verified only.",
            builtIn = true,
        ),
    )

    fun behaviors(): List<DjPromptTemplate> = listOf(
        DjPromptTemplate(
            id = "default",
            kind = DjPromptKind.Behavior,
            label = "Default",
            blurb = "Warm radio host — natural handoffs",
            body =
                "PERSONALITY: Default radio host — warm, curious, natural. " +
                    "Sound like a good friend on the air, not a hype reel.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "hype",
            kind = DjPromptKind.Behavior,
            label = "Hype",
            blurb = "High energy hypeman — big energy, still clean",
            body =
                "PERSONALITY: HYPE MODE — high energy, hypeman energy, punchy short sentences, " +
                    "celebrate the drop/cut. Keep it clean enough for a car ride. Use words like " +
                    "\"let's go\", \"this one hits\", \"turn it up\" sparingly (not every line).",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "hype_unhinged",
            kind = DjPromptKind.Behavior,
            label = "Hype Unhinged",
            blurb = "Savage party chaos — loud, explicit, roast-heavy hype",
            body =
                "PERSONALITY: HYPE UNHINGED — absolute party chaos with TEETH. Loud, explicit, " +
                    "over-the-top hype PLUS hard roasts: insult the listener's music taste, the last " +
                    "track, and the next one with love-hate energy (\"this ass-kicker\", \"your trash " +
                    "taste is somehow elite\", \"shut up and turn it up\"). Use RESEARCH as roast fuel " +
                    "when present. Address by LISTENER NAME when set — NEVER treat city as a name. " +
                    "Swear freely (fuck/shit/damn/ass ok). Be cocky, crude, a little mean — still " +
                    "land the handoff clearly. NO hate speech, slurs targeting protected groups, or " +
                    "real threats. Roast songs/vibe/listener playfully savage, not bigoted.",
            builtIn = true,
            flags = listOf(FLAG_UNHINGED_TASTE),
        ),
        DjPromptTemplate(
            id = "comedy",
            kind = DjPromptKind.Behavior,
            label = "Comedy",
            blurb = "Witty comic — jokes, bits, playful roasts",
            body =
                "PERSONALITY: COMEDY MODE — witty stand-up radio. Light jokes, wordplay, gentle " +
                    "roasts of the vibe (not cruel). Punchlines welcome. Still hand off the song " +
                    "cleanly. Prefer clever over cringe.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "soothing",
            kind = DjPromptKind.Behavior,
            label = "Soothing",
            blurb = "Soft late-night — calm, intimate, gentle",
            body =
                "PERSONALITY: SOOTHING MODE — soft late-night host. Slow, intimate, gentle, " +
                    "comforting. Lower intensity language. No yelling energy. Think quiet drive " +
                    "or winding down.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "unhinged",
            kind = DjPromptKind.Behavior,
            label = "Unhinged",
            blurb = "Mean goblin energy — crude, insulting, unfiltered",
            body =
                "PERSONALITY: UNHINGED — chaotic mean goblin DJ. Weird tangents, absurdist takes, " +
                    "AND explicit insults: dunk on the track, the artist, the album, AND the " +
                    "listener's music taste (\"your trash ears\", \"this is why friends skip your " +
                    "aux\"). Only say \"you queued this\" when SOURCE is LISTENER; for DJ picks " +
                    "roast the cut without claiming they requested it. Use RESEARCH as roast fuel " +
                    "when present. Address them by LISTENER NAME when set — NEVER treat city/location " +
                    "as a name. Crude/sexual-adjacent jokes ok if brief. Swear freely. " +
                    "Sound unfiltered and a little unhinged, not corporate-safe. Still hand off " +
                    "so the listener knows what's next. Don't invent facts; weirdness + meanness " +
                    "is in delivery. NO hate speech / protected-class slurs / real threats.",
            builtIn = true,
            flags = listOf(FLAG_UNHINGED_TASTE),
        ),
    )

    fun banterBits(): List<DjPromptTemplate> = listOf(
        DjPromptTemplate(
            id = ID_BANTER_HANDOFF,
            kind = DjPromptKind.Banter,
            label = "Track handoff",
            blurb = "Close the last cut and name the next one",
            body =
                "HANDOFF: Close the previous cut's vibe in one short clause, then clearly " +
                    "introduce the NEXT clean title and primary artist. The listener must know " +
                    "what is starting. Prefer vibe phrasing over reciting full credits.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = ID_BANTER_RESEARCH,
            kind = DjPromptKind.Banter,
            label = "Research beat",
            blurb = "Lead with a researched / custom-angle beat",
            body =
                "RESEARCH BEAT: Lead with one vivid researched or custom-angle beat " +
                    "(news, lyric theme, show, artist fact, X/social). If a custom or News " +
                    "talking point is listed, that beat is required — do not replace it with " +
                    "a generic song fact. Then still hand off the next cut.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = ID_BANTER_TEASE,
            kind = DjPromptKind.Banter,
            label = "Later in set",
            blurb = "Tease a later cut when the setlist has one",
            body =
                "SET TEASE: If SETLIST AHEAD lists later cuts, briefly tease one. " +
                    "Always still introduce the immediate next cut clearly.",
            builtIn = true,
        ),
    )

    fun banterSystem(): DjPromptTemplate = DjPromptTemplate(
        id = ID_BANTER_SYSTEM,
        kind = DjPromptKind.BanterSystem,
        label = "On-air banter rules",
        blurb = "Spoken handoff system prompt",
        body =
            "You are a live radio AI DJ speaking aloud ON AIR. Write 1–3 short sentences " +
                "(max {{WORD_CAP}} words). Rules:\n" +
                "• {{BEHAVIOR_STYLE}}\n" +
                "{{UNHINGED_EXTRA}}" +
                "{{NAME_BLOCK}}\n" +
                "• QUEUE ATTRIBUTION (critical): Who put the NEXT cut in the set is given as SOURCE. " +
                "If SOURCE is LIVE DJ, YOU chose it — never say \"you queued\", \"you put this on\", " +
                "\"your request\", \"as you asked\", or \"from your queue\". " +
                "If SOURCE is LISTENER, they requested it — you may credit them once. " +
                "Liked/top/radio/genre reasons mean DJ pick, not a listener request.\n" +
                "• Prefer vibe intros over full titles: e.g. \"finishing up with some Morgan Wallen\" " +
                "or \"sliding into a little [short title]\" — not \"That was Song Name by Artist Name\".\n" +
                "• Song titles often include (feat. X) / (with X) / - feat. X. NEVER read parentheses " +
                "featuring credits. NEVER say the artist name twice because it appears in the title " +
                "and the artist field. Use the CLEAN title and PRIMARY artist only.\n" +
                "• BANTER BITS / REQUIRED TALKING POINTS are this cycle's drawn templates. " +
                "Cover each required talking point — never collapse to only " +
                "\"that was [artist] / here's [next]\" when more is listed. " +
                "RESEARCH is a random 1–3 pack from the listener's enabled angles " +
                "(built-in lyrics / album / artist / shows / X / radio color AND any " +
                "custom angles they added). Custom is a peer in that lottery — not every talk. " +
                "If RESEARCH includes Custom: or News: lines, weave at least one of those " +
                "beats. Otherwise weave ONE vivid beat from this pack (lyric theme, album+year, " +
                "artist fact, show date — city only as location — X/social buzz, host color, " +
                "or a later-set tease). Do not invent a custom/news beat when this pack " +
                "has none. Then hand off with \"here's [clean title] by [primary artist]\" " +
                "or similar.\n" +
                "• Lyric themes: comment on what the song is about in your own words; " +
                "do NOT recite long lyrics or copyrighted lines.\n" +
                "• If RESEARCH is empty, still do a solid handoff; do not invent tour dates, " +
                "lyrics meaning, X posts, or news.\n" +
                "• CRITICAL: Output ONLY words a listener would hear on the radio. " +
                "NEVER narrate process, research, tools, planning, or drafting. Forbidden phrases include: " +
                "\"Checking for…\", \"Looking up…\", \"Searching…\", \"Before writing…\", \"Let me…\", " +
                "\"I'll check…\", \"public tidbit\", \"writing the DJ line\", \"as I research\", " +
                "\"according to my research\", \"Research focus\". " +
                "Do not mention that you looked anything up.\n" +
                "• No markdown, no hashtags, no emoji, no quotation marks wrapping the whole line.\n" +
                "• Always put a space after periods, commas, question marks, and exclamation points " +
                "(e.g. \"…vibe. Here's…\" not \"…vibe.Here's…\").\n" +
                "• Reply with ONLY the on-air spoken line — nothing before or after it.",
        builtIn = true,
    )

    fun researchSystem(): DjPromptTemplate = DjPromptTemplate(
        id = ID_RESEARCH_SYSTEM,
        kind = DjPromptKind.ResearchSystem,
        label = "Research agent rules",
        blurb = "Tool-backed fact pack for banter",
        body =
            "You are a music researcher for a live radio AI DJ. " +
                "You HAVE tools/web search — USE them for REAL information. " +
                "This turn focuses ONLY on these research angles (ignore others):\n" +
                "{{ANGLE_BRIEFS}}\n" +
                "Also: if SETLIST LOOKAHEAD is provided, you may add 0–2 short teases for later cuts.\n" +
                "Reply with JSON ONLY (no markdown fences):\n" +
                "{" +
                "\"current_lyrics_theme\":\"≤28 words or empty\"," +
                "\"next_lyrics_theme\":\"≤28 words or empty\"," +
                "\"album_facts\":[\"≤22 words\"]," +
                "\"facts\":[\"≤22 words\"]," +
                "\"shows\":[\"city/date/venue or tour if real\"]," +
                "\"x_social\":[\"≤22 words recent X/social buzz\"]," +
                "\"radio_color\":[\"≤22 words host-y verified color\"]," +
                "\"setlist_tease\":[\"≤20 words each for later cuts\"]," +
                "\"custom_notes\":[\"≤28 words — required for any custom angle\"]," +
                "\"news\":[\"≤22 words — world/US/local news when that angle is on\"]" +
                "}\n" +
                "Rules: fill ONLY fields that match this turn's angles (others empty); " +
                "when a custom / user-written angle is listed above, put findings in " +
                "custom_notes (and news if they are news) — never drop it because it is " +
                "not lyrics/album/shows; if no custom angle is listed, leave custom_notes/news empty; " +
                "max 2 album_facts, 4 facts, 3 shows, 3 x_social, 3 radio_color, 2 setlist_tease, " +
                "4 custom_notes, 3 news; " +
                "prefer verifiable/recent; NEVER invent tour dates, chart numbers, lyric meaning, " +
                "or viral posts. No commentary outside JSON. " +
                "Listener NAME and CITY are different — city is a place, not a person.",
        builtIn = true,
    )

    fun chatSystem(): DjPromptTemplate = DjPromptTemplate(
        id = ID_CHAT_SYSTEM,
        kind = DjPromptKind.ChatSystem,
        label = "Booth chat rules",
        blurb = "JSON steering for live radio chat",
        body =
            "You are the Live AI DJ booth chat (not general Grok chat). User is steering live Spotify radio. " +
                "{{BEHAVIOR_STYLE}} " +
                "Reply with JSON only, no markdown fences: " +
                "{\"reply\":\"1-3 short casual sentences\",\"vibe\":\"optional short vibe tag\"," +
                "\"actions\":[" +
                "{\"op\":\"enqueue_search\",\"q\":\"spotify search query\",\"n\":3}," +
                "{\"op\":\"new_queue\"},{\"op\":\"refill\"},{\"op\":\"clear_queue\"}," +
                "{\"op\":\"remove_track\",\"match\":\"song or artist substring in upcoming queue\"}," +
                "{\"op\":\"drop_artist\",\"artist\":\"name\"}," +
                "{\"op\":\"track_info\",\"q\":\"song name\"},{\"op\":\"artist_info\",\"q\":\"artist name\"}," +
                "{\"op\":\"skip\"},{\"op\":\"pause\"},{\"op\":\"play\"}" +
                "]} " +
                "Rules: new_queue = wipe upcoming + build a different set. refill = append more tracks. " +
                "remove_track / drop_artist prune the upcoming list. " +
                "track_info / artist_info answer questions (still put a short reply; the client also fetches Spotify facts). " +
                "Prefer enqueue_search for “play more X / queue some Y”. Keep reply under 50 words. " +
                "Write the reply field in the active behavior personality.",
        builtIn = true,
    )

    /**
     * System rules for AI rank (music director) — used only when “AI rank next tracks” is on.
     * Placeholders: {{N}} pick count, {{GENRE_BIAS}} genre lean sentence or ".".
     */
    fun queueRankSystem(): DjPromptTemplate = DjPromptTemplate(
        id = ID_QUEUE_RANK_SYSTEM,
        kind = DjPromptKind.QueueRankSystem,
        label = "Queue rank (AI pick) rules",
        blurb = "Music director system when AI ranks the pool",
        body =
            "You are a radio DJ music director (Spotify DJ style). Reply ONLY with valid JSON: " +
                "{\"picks\":[{\"uri\":\"spotify:track:...\",\"banter_note\":\"short why\"}],\"banter\":\"\"}. " +
                "Pick exactly {{N}} tracks from the CANDIDATES list only (use their uris). " +
                "Blend liked/top seeds with artist-radio variety{{GENRE_BIAS}} " +
                "Candidates already exclude recently played and already-heard tracks — never re-pick those. " +
                "Avoid stacking the same primary artist twice in a row. " +
                "Leave banter empty (spoken lines are generated separately). No markdown.",
        builtIn = true,
    )

    /**
     * User/request shell for AI rank. Data lines are filled via placeholders at fill time.
     */
    fun queueRankUser(): DjPromptTemplate = DjPromptTemplate(
        id = ID_QUEUE_RANK_USER,
        kind = DjPromptKind.QueueRankUser,
        label = "Queue rank request",
        blurb = "User message with current cut + candidates",
        body =
            "CURRENT: {{CURRENT}}\n" +
                "Behavior mode (queue energy, not spoken line): {{BEHAVIOR}}\n" +
                "{{GENRE_BOARD_LINE}}" +
                "{{CITY_LINE}}" +
                "{{VIBE_LINE}}" +
                "\n" +
                "CANDIDATES (not recently played):\n" +
                "{{CANDIDATES}}\n" +
                "\n" +
                "Pick {{N}} next tracks for a continuous live DJ set. Do not repeat recently heard songs.",
        builtIn = true,
    )

    fun defaultBody(id: String): String? =
        all().firstOrNull { it.id == id }?.body

    fun defaultFor(id: String): DjPromptTemplate? =
        all().firstOrNull { it.id == id }
}

/** Apply simple {{PLACEHOLDER}} replacements. Missing keys → empty string. */
fun applyPromptPlaceholders(body: String, vars: Map<String, String>): String {
    var out = body
    // Support both {{KEY}} and longer keys; replace known first then strip leftovers lightly.
    vars.forEach { (k, v) ->
        out = out.replace("{{$k}}", v)
    }
    return out
}

/**
 * Merge saved templates with built-in defaults:
 * - Keep user edits (body/label/blurb/enabled/flags) for existing ids
 * - Add any new built-ins the user doesn't have yet
 * - Keep custom (non-builtIn) templates
 * - Ensure system kinds always have exactly one template (re-seed if deleted)
 */
fun mergePromptTemplates(saved: List<DjPromptTemplate>): List<DjPromptTemplate> {
    val defaults = DjPromptDefaults.all()
    val byId = LinkedHashMap<String, DjPromptTemplate>()
    // Start with defaults
    defaults.forEach { byId[it.id] = it }
    // Overlay saved
    for (s in saved) {
        val base = byId[s.id]
        if (base != null && base.builtIn) {
            byId[s.id] = s.copy(builtIn = true, kind = base.kind)
        } else if (!s.builtIn) {
            byId[s.id] = s.copy(builtIn = false)
        } else {
            byId[s.id] = s
        }
    }
    // Ensure system singles exist
    listOf(
        DjPromptDefaults.banterSystem(),
        DjPromptDefaults.researchSystem(),
        DjPromptDefaults.chatSystem(),
        DjPromptDefaults.queueRankSystem(),
        DjPromptDefaults.queueRankUser(),
    ).forEach { sys ->
        if (byId.values.none { it.kind == sys.kind }) {
            byId[sys.id] = sys
        }
    }
    // Ensure at least one behavior + research + banter bits
    if (byId.values.none { it.kind == DjPromptKind.Behavior }) {
        DjPromptDefaults.behaviors().forEach { byId[it.id] = it }
    }
    if (byId.values.none { it.kind == DjPromptKind.Research }) {
        DjPromptDefaults.researchAngles().forEach { byId[it.id] = it }
    }
    if (byId.values.none { it.kind == DjPromptKind.Banter }) {
        DjPromptDefaults.banterBits().forEach { byId[it.id] = it }
    }
    return byId.values.toList().sortedWith(
        compareBy<DjPromptTemplate> { it.kind.ordinal }
            .thenBy { if (it.builtIn) 0 else 1 }
            .thenBy { it.label.lowercase() },
    )
}

/** One coordinated research + banter-bit draw for a single talk cycle. */
data class DjTalkPack(
    val research: List<DjPromptTemplate>,
    val banterBits: List<DjPromptTemplate>,
) {
    val customResearch: List<DjPromptTemplate>
        get() = research.filter { !it.builtIn }

    val hasCustomResearch: Boolean
        get() = customResearch.isNotEmpty()
}

/** Which research JSON fields this cycle's angle pack should fill. */
data class DjResearchFieldMask(
    val lyrics: Boolean,
    val albumArtist: Boolean,
    val shows: Boolean,
    val social: Boolean,
    val radio: Boolean,
    val custom: Boolean,
)

/**
 * Newest picks first. Older ids drop off the end so the lottery can
 * rotate back to them after a couple of talks.
 */
fun rememberRecentPromptIds(
    existing: List<String>,
    picked: List<String>,
    cap: Int = 4,
): List<String> {
    val next = LinkedHashSet<String>()
    picked.map { it.trim() }.filter { it.isNotEmpty() }.forEach { next.add(it) }
    existing.map { it.trim() }.filter { it.isNotEmpty() }.forEach { next.add(it) }
    return next.take(cap.coerceAtLeast(1))
}

internal fun pickCount1to3(rng: kotlin.random.Random, max: Int): Int {
    if (max <= 1) return max.coerceAtLeast(0)
    val n = when (rng.nextInt(10)) {
        in 0..5 -> 1
        in 6..8 -> 2
        else -> 3
    }
    return n.coerceIn(1, max)
}

internal fun pickFromEnabledPool(
    pool: List<DjPromptTemplate>,
    count: Int,
    recentIds: Collection<String>,
    rng: kotlin.random.Random,
): List<DjPromptTemplate> {
    if (pool.isEmpty() || count <= 0) return emptyList()
    val recent = recentIds.toSet()
    val unused = pool.filter { it.id !in recent }.shuffled(rng)
    val used = pool.filter { it.id in recent }.shuffled(rng)
    return (unused + used).take(count.coerceAtMost(pool.size))
}

fun enabledResearchPool(all: List<DjPromptTemplate>): List<DjPromptTemplate> {
    val research = all.filter { it.kind == DjPromptKind.Research && it.body.isNotBlank() }
    return research.filter { it.enabled }.ifEmpty { DjPromptDefaults.researchAngles() }
}

fun enabledBanterPool(all: List<DjPromptTemplate>): List<DjPromptTemplate> {
    val banter = all.filter { it.kind == DjPromptKind.Banter && it.body.isNotBlank() }
    return banter.filter { it.enabled }.ifEmpty { DjPromptDefaults.banterBits() }
}

/**
 * Build this talk's research pack.
 *
 * Every enabled angle (built-in + custom) is a peer in one lottery.
 * Draws 1–3, preferring ids not in [recentIds]. Disabled stay out.
 */
fun pickResearchTemplates(
    all: List<DjPromptTemplate>,
    rng: kotlin.random.Random = kotlin.random.Random.Default,
    recentIds: Collection<String> = emptyList(),
): List<DjPromptTemplate> {
    val pool = enabledResearchPool(all)
    val picked = pickFromEnabledPool(pool, pickCount1to3(rng, pool.size), recentIds, rng)
    return picked.ifEmpty { listOf(DjPromptDefaults.researchAngles().first()) }
}

/**
 * Build this talk's banter talking-point pack.
 *
 * Track handoff stays on when enabled. Other bits (built-in + custom)
 * rotate 1–2 from the enabled extras, preferring [recentIds] misses.
 */
fun pickBanterTemplates(
    all: List<DjPromptTemplate>,
    rng: kotlin.random.Random = kotlin.random.Random.Default,
    recentIds: Collection<String> = emptyList(),
): List<DjPromptTemplate> {
    val enabled = enabledBanterPool(all)
    val handoff = enabled.firstOrNull { it.id == DjPromptDefaults.ID_BANTER_HANDOFF }
    val extras = enabled.filter { it.id != DjPromptDefaults.ID_BANTER_HANDOFF }
    val picked = ArrayList<DjPromptTemplate>(3)
    if (handoff != null) picked.add(handoff)
    if (extras.isNotEmpty()) {
        val extraN = when (rng.nextInt(10)) {
            in 0..5 -> 1
            in 6..8 -> 2
            else -> 1
        }.coerceIn(1, extras.size)
        picked.addAll(pickFromEnabledPool(extras, extraN, recentIds, rng))
    }
    return picked.ifEmpty { listOf(DjPromptDefaults.banterBits().first()) }.distinctBy { it.id }
}

fun pickDjTalkPack(
    all: List<DjPromptTemplate>,
    recentResearchIds: Collection<String> = emptyList(),
    recentBanterIds: Collection<String> = emptyList(),
    rng: kotlin.random.Random = kotlin.random.Random.Default,
): DjTalkPack = DjTalkPack(
    research = pickResearchTemplates(all, rng, recentResearchIds),
    banterBits = pickBanterTemplates(all, rng, recentBanterIds),
)

/** Required on-air beats for this cycle: picked banter bits + picked custom research. */
fun talkingPointsForPack(pack: DjTalkPack): List<DjPromptTemplate> =
    (pack.customResearch + pack.banterBits).distinctBy { it.id }

/**
 * Talking points for a freshly drawn pack. Prefer [talkingPointsForPack] with
 * the same [DjTalkPack] the researcher used so custom is not required unless
 * it was actually drawn this cycle.
 */
fun collectBanterTalkingPoints(
    all: List<DjPromptTemplate>,
    rng: kotlin.random.Random = kotlin.random.Random.Default,
    recentResearchIds: Collection<String> = emptyList(),
    recentBanterIds: Collection<String> = emptyList(),
): List<DjPromptTemplate> =
    talkingPointsForPack(pickDjTalkPack(all, recentResearchIds, recentBanterIds, rng))

fun researchFieldMask(angles: List<DjPromptTemplate>): DjResearchFieldMask {
    fun idHit(vararg needles: String): Boolean =
        angles.any { a -> needles.any { n -> a.id.contains(n, ignoreCase = true) } }

    fun bodyHit(vararg needles: String): Boolean =
        angles.any { a -> needles.any { n -> a.body.contains(n, ignoreCase = true) } }

    val lyrics = idHit("lyric", "theme", "meaning") ||
        bodyHit("LYRICS & MEANING", "LYRICS AND MEANING")
    val albumArtist = idHit("album_song_facts", "artist_facts") ||
        bodyHit("ALBUM / SONG FACTS", "ARTIST FACTS:")
    val shows = idHit("show", "tour") || bodyHit("SHOWS & TOURS", "SHOWS AND TOURS")
    val social = idHit("x_social", "recent_x") ||
        bodyHit("RECENT X / SOCIAL", "X/social")
    val radio = idHit("radio", "host_color") || bodyHit("RADIO HOST COLOR")
    val custom = angles.any { !it.builtIn }
    return DjResearchFieldMask(
        lyrics = lyrics,
        albumArtist = albumArtist,
        shows = shows,
        social = social,
        radio = radio,
        custom = custom,
    )
}

fun formatBanterTalkingPointsBlock(
    points: List<DjPromptTemplate>,
    city: String,
): String {
    if (points.isEmpty()) return ""
    val cityVal = city.trim().ifBlank { "(not set)" }
    return buildString {
        appendLine(
            "REQUIRED ON-AIR TALKING POINTS (do not skip these for a generic " +
                "\"that was / up next\"):",
        )
        points.forEachIndexed { i, t ->
            val brief = applyPromptPlaceholders(t.body, mapOf("CITY" to cityVal))
            val tag = when {
                !t.builtIn && t.kind == DjPromptKind.Research -> "custom research"
                !t.builtIn -> "custom"
                else -> "built-in"
            }
            appendLine("${i + 1}) [${t.label} · $tag] $brief")
        }
    }.trimEnd()
}

fun appendBanterTalkingPointsToSystem(systemBody: String, talkingPointsBlock: String): String {
    val sys = systemBody.trim()
    val block = talkingPointsBlock.trim()
    if (block.isEmpty()) return sys
    return sys +
        "\n• REQUIRED TALKING POINTS this cycle — you MUST cover each one " +
        "(do not replace them with only a was/next handoff):\n" +
        block
}

data class DjBanterUserPromptInput(
    val behaviorLabel: String,
    val listenerName: String = "",
    val city: String = "",
    val genres: List<String> = emptyList(),
    val prevRawTitle: String = "",
    val prevCleanTitle: String = "",
    val prevArtists: String = "",
    val prevPrimary: String = "",
    val prevSource: String = "",
    val prevReason: String = "",
    val nextRawTitle: String = "",
    val nextCleanTitle: String = "",
    val nextArtists: String = "",
    val nextPrimary: String = "",
    val nextSource: String = "",
    val nextReason: String = "",
    val tracksUntilTalk: Int = 0,
    val upcoming: List<Pair<String, String>> = emptyList(),
    val research: List<String> = emptyList(),
    val talkingPoints: List<DjPromptTemplate> = emptyList(),
    val unhinged: Boolean = false,
    val hasPrev: Boolean = true,
    val hasNext: Boolean = true,
)

fun buildBanterUserPrompt(input: DjBanterUserPromptInput): String {
    val talkingBlock = formatBanterTalkingPointsBlock(input.talkingPoints, input.city)
    return buildString {
        appendLine("Behavior mode: ${input.behaviorLabel}")
        appendLine(
            "LISTENER NAME (person to address — NOT a place): " +
                input.listenerName.ifBlank { "(not set — say you/folks, never use city as name)" },
        )
        appendLine(
            "LISTENER CITY (location only for shows/local color — NEVER a greeting name): " +
                input.city.ifBlank { "(not set)" },
        )
        if (input.genres.isNotEmpty()) {
            appendLine("Genre board (taste signal): ${input.genres.joinToString(", ")}")
        }
        appendLine("Just played:")
        if (input.hasPrev && (input.prevCleanTitle.isNotBlank() || input.prevPrimary.isNotBlank())) {
            if (input.prevRawTitle.isNotBlank()) appendLine("  raw title: ${input.prevRawTitle}")
            appendLine("  clean title: ${input.prevCleanTitle.ifBlank { "(unknown)" }}")
            if (input.prevArtists.isNotBlank()) appendLine("  artists field: ${input.prevArtists}")
            appendLine("  primary artist: ${input.prevPrimary.ifBlank { "(unknown)" }}")
            if (input.prevSource.isNotBlank()) appendLine("  SOURCE: ${input.prevSource}")
            if (input.prevReason.isNotBlank()) appendLine("  pick detail: ${input.prevReason}")
        } else {
            appendLine("  (cold open / nothing specific)")
        }
        appendLine("Up next:")
        if (input.hasNext && (input.nextCleanTitle.isNotBlank() || input.nextPrimary.isNotBlank())) {
            if (input.nextRawTitle.isNotBlank()) appendLine("  raw title: ${input.nextRawTitle}")
            appendLine("  clean title: ${input.nextCleanTitle.ifBlank { "(unknown)" }}")
            if (input.nextArtists.isNotBlank()) appendLine("  artists field: ${input.nextArtists}")
            appendLine("  primary artist: ${input.nextPrimary.ifBlank { "(unknown)" }}")
            if (input.nextSource.isNotBlank()) appendLine("  SOURCE: ${input.nextSource}")
            if (input.nextReason.isNotBlank()) {
                appendLine(
                    "  pick detail (internal — seed/radio reason, NOT proof the listener queued it): " +
                        input.nextReason,
                )
            }
        } else {
            appendLine("  (still digging in the library)")
        }
        appendLine()
        appendLine(
            "BANTER COUNTDOWN: you are speaking at this handoff " +
                "(tracks-until-talk was ${input.tracksUntilTalk} before this line).",
        )
        if (input.upcoming.isNotEmpty()) {
            appendLine("SETLIST AHEAD (after the immediate next cut — tease 0–1 if natural):")
            input.upcoming.forEachIndexed { i, (title, artist) ->
                appendLine("  +${i + 2}: $title — $artist")
            }
            appendLine(
                "Like a real radio DJ you MAY briefly tease something later " +
                    "(\"after this we've got…\") but always introduce the IMMEDIATE next cut clearly.",
            )
        }
        appendLine()
        if (talkingBlock.isNotBlank()) {
            appendLine(talkingBlock)
            appendLine()
        }
        if (input.research.isNotEmpty()) {
            appendLine(
                "RESEARCH (angle pack this cycle — custom beats are required when present):",
            )
            input.research.forEachIndexed { i, f -> appendLine("  ${i + 1}. $f") }
            val hasCustomBeat = researchHasUsableCustomBeat(input.research)
            if (hasCustomBeat) {
                appendLine(
                    "CUSTOM ANGLE REQUIRED: weave at least one Custom:/News: beat. " +
                        "Do not replace it with a generic song fact.",
                )
            } else {
                appendLine(
                    "This cycle's research pack has no custom/news beat. " +
                        "Do not invent one — weave a beat from the listed RESEARCH only.",
                )
            }
        } else if (input.talkingPoints.isEmpty()) {
            appendLine("RESEARCH: (none solid — pure handoff, no invented news)")
        } else {
            appendLine(
                "RESEARCH: (none cached — still honor REQUIRED ON-AIR TALKING POINTS. " +
                    "Do not invent specific headlines, scores, dates, or quotes. " +
                    "Do not collapse to a generic that-was / up-next only.)",
            )
        }
        appendLine()
        append("Write the on-air line in ${input.behaviorLabel} style. ")
        if (input.talkingPoints.isNotEmpty()) {
            append(
                "You MUST cover every REQUIRED ON-AIR TALKING POINT. " +
                    "Do not collapse to only \"that was X / here's Y\" if those points ask for more. ",
            )
        } else {
            append("Close out the previous vibe, then introduce the next track clearly. ")
        }
        if (input.unhinged) append("Roast their taste. ")
        append("Filter delivery through ${input.behaviorLabel} personality. ")
        appendLine("On-air DJ line only:")
    }
}

fun encodePromptTemplates(list: List<DjPromptTemplate>): String {
    val arr = JSONArray()
    list.forEach { arr.put(it.toJson()) }
    return arr.toString()
}

fun decodePromptTemplates(raw: String?): List<DjPromptTemplate> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                DjPromptTemplate.fromJson(arr.optJSONObject(i))?.let { add(it) }
            }
        }
    }.getOrElse { emptyList() }
}
