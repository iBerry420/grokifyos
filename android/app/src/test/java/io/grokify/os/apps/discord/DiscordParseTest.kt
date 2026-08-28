package io.grokify.os.apps.discord

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordParseTest {
    @Test
    fun botsFromWrappedArray() {
        val raw = JSONObject(
            """
            {"ok":true,"data":[
              {"id":4,"name":"AvalynnAI","clientType":"discord.js","isActive":1,"isRunning":true,"respondMentions":false,"lastOnline":"2026-08-20T15:31:01.127Z"},
              {"id":2,"name":"LYNX","clientType":"selfbot","isActive":true,"isRunning":false}
            ]}
            """.trimIndent(),
        )
        val bots = DiscordParse.bots(raw)
        assertEquals(2, bots.size)
        assertEquals("AvalynnAI", bots[0].name)
        assertTrue(bots[0].isRunning)
        assertEquals("selfbot", bots[1].clientType)
        assertTrue(bots[0].lastOnlineMs > 0L)
    }

    @Test
    fun messagesPage() {
        val raw = JSONObject(
            """
            {"ok":true,"data":{"messages":[
              {"id":1,"messageId":"m1","guildId":"g1","channelId":"c1","content":"hello","tags":"alpha,beta",
               "createdAt":"2026-08-20T12:00:00.000Z","guildName":"BASI","channelName":"bot-chat",
               "user":{"id":9,"discordId":"d1","username":"iberry420","displayName":"iBerry","avatar":"https://cdn.example/a.png","level":12},
               "attachments":[{"id":3,"filename":"x.png","contentType":"image/png","discordUrl":"https://cdn.example/x.png"}]}
            ],"total":537,"page":1,"pageSize":40,"totalPages":14}}
            """.trimIndent(),
        )
        val page = DiscordParse.messages(raw)
        assertEquals(537, page.total)
        assertTrue(page.hasMore)
        assertEquals("hello", page.items[0].content)
        assertEquals(listOf("alpha", "beta"), page.items[0].tags)
        assertEquals("iBerry", page.items[0].displayName)
        assertEquals(1, page.items[0].attachments.size)
        assertEquals("", page.items[0].attachments[0].url)
        assertTrue(page.hasMore)
    }

    @Test
    fun messagesHasMoreFlag() {
        val raw = JSONObject(
            """{"ok":true,"data":{"messages":[{"id":1,"messageId":"m1","content":"hi","user":{}}],"hasMore":true,"page":1,"totalPages":1,"total":1}}""",
        )
        assertTrue(DiscordParse.messages(raw).hasMore)
    }

    @Test
    fun discordMarkdownMentions() {
        val md = discordToMarkdown("hi <@123> in <#9> ||secret|| <:wave:1>")
        assertTrue(md.contains("`@user`"))
        assertTrue(md.contains("`#channel`"))
        assertTrue(md.contains(":wave:"))
        assertTrue(md.contains("*secret*"))
    }

    @Test
    fun guildsAndAudits() {
        val guilds = DiscordParse.guilds(
            JSONObject("""{"ok":true,"data":[{"id":1,"guildId":"111","guildName":"OpenAI","isWatched":true,"guildIcon":"https://cdn/i.png"}]}"""),
        )
        assertEquals(1, guilds.size)
        assertEquals("OpenAI", guilds[0].name)
        assertTrue(guilds[0].isWatched)

        val audits = DiscordParse.audits(
            JSONObject(
                """{"ok":true,"data":{"events":[
                  {"id":9,"action":"message_delete","guildId":"111","guildName":"OpenAI","actorUsername":"mod","targetUsername":"user","createdAt":"2026-08-20T01:00:00Z"}
                ],"hasMore":true}}""",
            ),
        )
        assertEquals("message_delete", audits.items[0].action)
        assertEquals("mod", audits.items[0].actor)
        assertTrue(audits.hasMore)
    }

    @Test
    fun auditsCarryHistoricalContent() {
        val audits = DiscordParse.audits(
            JSONObject(
                """
                {"ok":true,"data":{"events":[
                  {"id":1,"action":"message_delete","guildId":"g1","guildName":"GREYCORD",
                   "actorDisplayName":"Mod","targetUsername":"user","channelName":"general",
                   "beforeText":"bye world","afterText":"",
                   "beforeAttachments":[{"id":7,"filename":"clip.gif","contentType":"image/gif",
                     "url":"https://host/api/discord.php?action=file&id=7","kind":"gif"}],
                   "createdAt":"2026-08-20T17:40:13.770Z"},
                  {"id":2,"action":"message_edit","guildId":"g1","guildName":"GREYCORD",
                   "actorUsername":"nynabtw","channelName":"merch-chat",
                   "beforeText":"ni","afterText":"no","createdAt":"2026-08-20T17:39:18.594Z"},
                  {"id":3,"action":"avatar_change","guildId":"g1","guildName":"GREYCORD",
                   "targetDisplayName":"mari","targetAvatar":"https://host/api/discord.php?action=avatar&user=7&hash=now",
                   "beforeAvatar":"https://host/api/discord.php?action=avatar&user=7&hash=old1",
                   "afterAvatar":"https://host/api/discord.php?action=avatar&user=7&hash=new1",
                   "createdAt":"2026-08-20T18:16:16.625Z"},
                  {"id":4,"action":"avatar_change","guildId":"g1","guildName":"GREYCORD",
                   "targetDisplayName":"mari",
                   "beforeAvatar":"https://host/api/discord.php?action=avatar&user=7&hash=old2",
                   "afterAvatar":"https://host/api/discord.php?action=avatar&user=7&hash=new2",
                   "createdAt":"2026-08-20T18:17:17.792Z"},
                  {"id":5,"action":"avatar_change","guildId":"g1","guildName":"GREYCORD",
                   "targetDisplayName":"mari",
                   "beforeAvatar":"https://host/api/discord.php?action=avatar&user=7&hash=old3",
                   "afterAvatar":"https://host/api/discord.php?action=avatar&user=7&hash=new3",
                   "createdAt":"2026-08-20T18:18:18.000Z"},
                  {"id":6,"action":"username_change","guildId":"g1","guildName":"GREYCORD",
                   "targetUsername":"ydkyvngcutthroat","beforeText":"ydkyvngchrist","afterText":"ydkyvngcutthroat",
                   "createdAt":"2026-08-20T18:07:28.837Z"},
                  {"id":7,"action":"displayname_change","guildId":"g1","guildName":"GREYCORD",
                   "targetDisplayName":"vice","beforeText":"abu","afterText":"vice",
                   "createdAt":"2026-08-20T17:52:55.449Z"},
                  {"id":8,"action":"role_assign","guildId":"g1","guildName":"GREYCORD",
                   "targetUsername":"wxtto5o9","afterText":"Member","createdAt":"2026-08-20T18:33:47.758Z"}
                ],"hasMore":true}}
                """.trimIndent(),
            ),
        )
        assertEquals(8, audits.items.size)
        val del = audits.items[0]
        assertEquals("bye world", del.beforeText)
        assertEquals("general", del.channelName)
        assertEquals(1, del.beforeAttachments.size)
        assertTrue(del.beforeAttachments[0].url.contains("action=file"))
        assertEquals("ni", audits.items[1].beforeText)
        assertEquals("no", audits.items[1].afterText)
        val avatars = audits.items.filter { it.action == "avatar_change" }
        assertEquals(3, avatars.size)
        assertTrue(avatars[0].beforeAvatar.contains("hash=old1"))
        assertTrue(avatars[2].afterAvatar.contains("hash=new3"))
        assertEquals("ydkyvngchrist", audits.items[5].beforeText)
        assertEquals("ydkyvngcutthroat", audits.items[5].afterText)
        assertEquals("abu", audits.items[6].beforeText)
        assertEquals("vice", audits.items[6].afterText)
        assertEquals("Member", audits.items[7].afterText)
    }

    @Test
    fun mediaOnlyDeleteShowsCachedImage() {
        val audits = DiscordParse.audits(
            JSONObject(
                """
                {"ok":true,"data":{"events":[
                  {"id":9,"action":"message_delete","guildId":"g1","guildName":"OpenAI",
                   "actorUsername":"excessive_fours","channelName":"nsfw",
                   "beforeText":"","afterText":"",
                   "beforeAttachments":[{"id":575004,"filename":"shot.jpg","contentType":"image/jpeg",
                     "url":"https://host/api/discord.php?action=file&id=575004","kind":"image"}],
                   "createdAt":"2026-08-20T18:22:24.997Z"}
                ],"hasMore":false}}
                """.trimIndent(),
            ),
        )
        val del = audits.items[0]
        assertEquals("", del.beforeText)
        assertEquals(1, del.beforeAttachments.size)
        assertEquals("image", del.beforeAttachments[0].kind)
        assertTrue(del.beforeAttachments[0].url.contains("action=file"))
        assertEquals("", discordAuditMessageText(del.beforeText, del.beforeAttachments))
        assertEquals("(no text)", discordAuditMessageText("", emptyList()))
        assertEquals("hello", discordAuditMessageText("hello", del.beforeAttachments))
    }

    @Test
    fun emojiFilesPaginated() {
        val page = DiscordParse.emojiFiles(
            JSONObject("""{"ok":true,"data":{"files":["grin_123.png","wave_9.gif"],"total":34837,"limit":48,"offset":0}}"""),
        )
        assertEquals(34837, page.total)
        assertEquals("grin", page.items[0].name)
        assertTrue(page.hasMore)
    }

    @Test
    fun errorUnwrap() {
        val err = DiscordJson.err(JSONObject("""{"ok":false,"error":"discord_unreachable"}"""))
        assertEquals("discord_unreachable", err)
        assertTrue(DiscordParse.bots(JSONObject("""{"ok":false,"error":"nope"}""")).isEmpty())
        assertFalse(DiscordJson.err(JSONObject("""{"ok":true,"data":[]}""")) != null)
    }

    @Test
    fun channelsKeyedPerGuildWithBots() {
        val raw = JSONObject(
            """
            {"ok":true,"data":{"guildId":"111","channels":[
              {"channelId":"c1","guildId":"111","channelName":"general","channelType":0,
               "bots":[
                 {"botId":2,"name":"LYNX","isEnabled":true,"isMuted":false},
                 {"botId":4,"name":"AvalynnAI","isEnabled":false,"isMuted":false}
               ]}
            ]}}
            """.trimIndent(),
        )
        val ch = DiscordParse.channels(raw)
        assertEquals(1, ch.size)
        assertEquals("111", ch[0].guildId)
        assertEquals(2, ch[0].bots.size)
        assertEquals("LYNX", ch[0].bots[0].name)
        assertFalse(ch[0].bots[1].isEnabled)

        val page = DiscordParse.channelPage(
            JSONObject(
                """
                {"ok":true,"data":{"guildId":"111","kind":"threads","total":1644,"hasMore":true,
                  "channelCount":88,"threadCount":1644,
                  "channels":[
                    {"channelId":"t1","guildId":"111","channelName":"I love Colorado","channelType":0,"kind":"thread"}
                  ]}}
                """.trimIndent(),
            ),
        )
        assertEquals("threads", page.kind)
        assertEquals(1644, page.total)
        assertTrue(page.hasMore)
        assertEquals(88, page.channelCount)
        assertEquals(1644, page.threadCount)
        assertEquals("thread", page.items[0].kind)
        assertEquals("I love Colorado", discordChannelLabel(page.items[0], "threads"))
        assertEquals("# general", discordChannelLabel(ch[0], "channels"))
    }

    @Test
    fun usersHasMoreAndAvatar() {
        val raw = JSONObject(
            """{"ok":true,"data":{"users":[
              {"id":9,"discordId":"d1","username":"iberry420","displayName":"iBerry",
               "avatar":"https://grokifyos.grokpot.io/api/discord.php?action=avatar&user=d1&hash=abc","level":12,"messageCount":3,"lastActive":"2026-08-20T12:00:00Z"}
            ],"total":9,"limit":1,"offset":0,"hasMore":true}}""",
        )
        val page = DiscordParse.users(raw)
        assertTrue(page.hasMore)
        assertTrue(page.items[0].avatar.contains("action=avatar"))
        assertEquals(12, page.items[0].level)

        val memberOnly = DiscordParse.users(
            JSONObject(
                """{"ok":true,"data":{"users":[
                  {"id":0,"discordId":"99","username":"joiner","displayName":null,"level":0,"messageCount":0}
                ],"total":1,"limit":40,"offset":0,"hasMore":false}}""",
            ),
        )
        assertEquals(1, memberOnly.items.size)
        assertEquals("99", memberOnly.items[0].discordId)
        assertEquals("joiner", memberOnly.items[0].username)
    }

    @Test
    fun attachmentKinds() {
        assertEquals("gif", discordMediaKind("image/gif", "x.gif"))
        assertEquals("video", discordMediaKind("video/mp4", "clip.mp4"))
        assertEquals("audio", discordMediaKind("audio/mpeg", "song.mp3"))
        assertEquals("image", discordMediaKind("image/png", "pic.png"))
        val raw = JSONObject(
            """{"ok":true,"data":{"attachments":[
              {"id":7,"filename":"dance.gif","contentType":"image/gif","url":"https://host/api/discord.php?action=file&id=7","kind":"gif"}
            ],"total":1,"offset":0}}""",
        )
        val page = DiscordParse.attachments(raw)
        assertEquals("gif", page.items[0].kind)
        assertTrue(page.items[0].url.contains("action=file"))
    }

    @Test
    fun guildsDoNotInventExtraBots() {
        val raw = JSONObject(
            """
            {"ok":true,"data":[
              {"id":1,"guildId":"g1","guildName":"GREYCORD","isWatched":true,
               "bots":[{"botId":2,"name":"LYNX","isWatched":true}]}
            ]}
            """.trimIndent(),
        )
        val guilds = DiscordParse.guilds(raw)
        assertEquals(1, guilds[0].bots.size)
        assertEquals(2, guilds[0].bots[0].botId)
        assertEquals("LYNX", guilds[0].bots[0].name)
    }

    @Test
    fun channelBotsFilteredToGuildMembers() {
        val chBots = listOf(
            DiscordChannelBot(2, "LYNX", true, false, false),
            DiscordChannelBot(3, "AvalynnAI NG", true, false, false),
            DiscordChannelBot(4, "AvalynnAI", false, false, false),
        )
        val shown = discordMemberChannelBots(chBots, setOf(2, 4))
        assertEquals(2, shown.size)
        assertEquals("LYNX", shown[0].name)
        assertEquals("AvalynnAI", shown[1].name)
        assertTrue(discordMemberChannelBots(chBots, emptySet()).isEmpty())
    }

    @Test
    fun attachmentsHasMoreAndGuildName() {
        val raw = JSONObject(
            """{"ok":true,"data":{"attachments":[
              {"id":7,"filename":"dance.gif","contentType":"image/gif","url":"https://host/api/discord.php?action=file&id=7","kind":"gif","guildId":"g1","guildName":"BASI"}
            ],"total":9,"offset":0,"hasMore":true}}""",
        )
        val page = DiscordParse.attachments(raw)
        assertTrue(page.hasMore)
        assertEquals("BASI", page.items[0].guildName)
        assertEquals("g1", page.items[0].guildId)
    }

    @Test
    fun attachmentIgnoresDiscordCdnFallback() {
        val cdnOnly = DiscordParse.attachment(
            JSONObject("""{"id":3,"filename":"x.png","contentType":"image/png","discordUrl":"https://cdn.discordapp.com/attachments/1/2/x.png"}"""),
        )
        assertEquals("", cdnOnly.url)
        assertFalse(cdnOnly.local)
        val local = DiscordParse.attachment(
            JSONObject("""{"id":7,"filename":"dance.gif","contentType":"image/gif","url":"https://host/api/discord.php?action=file&id=7","discordUrl":"https://cdn.discordapp.com/attachments/1/2/dance.gif","local":true}"""),
        )
        assertEquals("https://host/api/discord.php?action=file&id=7", local.url)
        assertTrue(local.local)
        assertEquals("gif", local.kind)
    }

    @Test
    fun attachmentCarriesOriginMeta() {
        val att = DiscordParse.attachment(
            JSONObject(
                """
                {"id":576705,"filename":"clip.mp4","contentType":"video/mp4","size":4722727,
                 "url":"https://host/api/discord.php?action=file&id=576705","local":true,"kind":"video",
                 "guildId":"1114305545478877377","guildName":"GREYCORD","channelId":"1131685490647642112",
                 "channelName":"clips","messageId":"1540373626258194432",
                 "discordAttachmentId":"1540373626258194432",
                 "createdAt":"2026-08-21T20:23:43.000Z",
                 "user":{"id":7964,"discordId":"123456789012345678","username":"iberry420",
                         "displayName":"iBerry","avatar":"https://host/api/discord.php?action=avatar&user=123"}}
                """.trimIndent(),
            ),
        )
        assertEquals("video", att.kind)
        assertEquals("GREYCORD", att.guildName)
        assertEquals("clips", att.channelName)
        assertEquals("1114305545478877377", att.guildId)
        assertEquals("1131685490647642112", att.channelId)
        assertEquals("1540373626258194432", att.messageId)
        assertEquals("1540373626258194432", att.discordAttachmentId)
        assertEquals("iberry420", att.username)
        assertEquals("iBerry", att.displayName)
        assertEquals("123456789012345678", att.discordId)
        assertEquals(7964, att.userId)
        assertEquals(4722727L, att.size)
        assertTrue(att.createdAtMs > 0L)
        assertTrue(att.avatar.contains("action=avatar"))
    }

    @Test
    fun attachmentFillsFromMessageContext() {
        val thin = DiscordParse.attachment(
            JSONObject("""{"id":9,"filename":"clip.mp4","contentType":"video/mp4","url":"https://host/api/discord.php?action=file&id=9"}"""),
        )
        val msg = DiscordParse.message(
            JSONObject(
                """
                {"id":1,"messageId":"m9","guildId":"g1","channelId":"c1","content":"vid",
                 "createdAt":"2026-08-21T12:00:00.000Z","guildName":"BASI","channelName":"bot-chat",
                 "user":{"id":4,"discordId":"99","username":"nyna","displayName":"Nyna","avatar":"https://host/a.png"},
                 "attachments":[]}
                """.trimIndent(),
            ),
        )
        val filled = thin.withMessageContext(msg)
        assertEquals("BASI", filled.guildName)
        assertEquals("bot-chat", filled.channelName)
        assertEquals("g1", filled.guildId)
        assertEquals("c1", filled.channelId)
        assertEquals("nyna", filled.username)
        assertEquals("Nyna", filled.displayName)
        assertEquals("99", filled.discordId)
        assertEquals(4, filled.userId)
        assertEquals("m9", filled.messageId)
        assertTrue(filled.createdAtMs > 0L)
        assertEquals("https://host/api/discord.php?action=file&id=9", filled.url)
    }

    @Test
    fun discordFormatBytesLabels() {
        assertEquals("0 B", discordFormatBytes(0))
        assertEquals("512 B", discordFormatBytes(512))
        assertEquals("1.0 KB", discordFormatBytes(1024))
        assertEquals("4.5 MB", discordFormatBytes(4_718_592))
    }

    @Test
    fun pickersAndCaptchas() {
        val pickers = DiscordParse.pickers(
            JSONObject("""{"ok":true,"data":[{"id":2,"botId":4,"guildId":"g","channelId":"c","messageId":"m","embedTitle":"React"}]}"""),
        )
        assertTrue(pickers[0].deployed)
        assertEquals("React", pickers[0].title)
        val caps = DiscordParse.captchas(
            JSONObject("""{"ok":true,"data":[{"id":1,"botId":4,"guildId":"g","channelId":"c","postRoleName":"member"}]}"""),
        )
        assertFalse(caps[0].deployed)
        assertEquals("member", caps[0].postRoleName)
    }

    @Test
    fun messagesExposeUserIds() {
        val page = DiscordParse.messages(
            JSONObject(
                """{"ok":true,"data":{"messages":[
                  {"id":1,"messageId":"m1","content":"**hi**","createdAt":"2026-08-20T12:00:00Z",
                   "guildName":"GROK COMMUNITY","channelName":"bot-chat",
                   "user":{"id":9,"discordId":"111","username":"iberry420","displayName":"iBerry","avatar":"https://host/a","level":16}}
                ],"hasMore":false}}""",
            ),
        )
        assertEquals(9, page.items[0].userId)
        assertEquals("111", page.items[0].discordId)
    }

    @Test
    fun auditsExposeActorAndTargetIds() {
        val page = DiscordParse.audits(
            JSONObject(
                """{"ok":true,"data":{"events":[
                  {"id":3,"action":"avatar_change","guildId":"g","guildName":"OpenAI",
                   "actorId":"111","targetId":"222","targetType":"user",
                   "actorUsername":"mod","targetUsername":"iberry420","createdAt":"2026-08-20T01:00:00Z"}
                ],"hasMore":false}}""",
            ),
        )
        assertEquals("111", page.items[0].actorId)
        assertEquals("222", page.items[0].targetId)
        assertEquals("user", page.items[0].targetType)
    }

    @Test
    fun profilePayload() {
        val profile = DiscordParse.profile(
            JSONObject(
                """{"ok":true,"data":{
                  "id":9,"discordId":"111","username":"iberry420","displayName":"iBerry",
                  "avatar":"https://host/api/discord.php?action=avatar&user=111&hash=abc",
                  "level":16,"xp":75,"totalXp":92989,"activityScore":100.0,
                  "messageCount":2519,"xpToNextLevel":15000,"levelProgress":12.5,
                  "lastActive":"2026-08-20T12:00:00Z",
                  "activeGuilds":[{"id":"g1","name":"GROK COMMUNITY","messageCount":12}],
                  "activeChannels":[{"id":"c1","name":"bot-chat","guildId":"g1","guildName":"GROK COMMUNITY","messageCount":8}],
                  "usernameChanges":[{"oldValue":"old","newValue":"iberry420","changedAt":"2026-03-01T12:00:00Z"}],
                  "displayNameChanges":[{"oldValue":"x","newValue":"iBerry","changedAt":"2026-03-02T12:00:00Z"}],
                  "avatarChanges":[{"oldValue":"https://host/a","newValue":"https://host/b","changedAt":"2026-03-03T12:00:00Z"}],
                  "topTags":[{"tag":"grok community","count":40},{"tag":"wow","count":12}],
                  "totalTagCount":52,"uniqueTagCount":2,"hasMoreTags":false
                }}""",
            ),
        )
        assertEquals(9, profile.id)
        assertEquals("111", profile.discordId)
        assertEquals("iBerry", profile.displayName)
        assertEquals(2519, profile.messageCount)
        assertEquals("GROK COMMUNITY", profile.guilds[0].name)
        assertEquals("bot-chat", profile.channels[0].name)
        assertEquals("iberry420", profile.usernameChanges[0].newValue)
        assertEquals("https://host/b", profile.avatarChanges[0].newValue)
        assertEquals("grok community", profile.topTags[0].tag)
        assertEquals(40, profile.topTags[0].count)
        assertEquals(2, profile.uniqueTagCount)
        assertTrue(profile.avatar.contains("action=avatar"))
    }

    @Test
    fun profileOpenKeyPrefersDiscordId() {
        val fromUser = discordProfileKey(id = 9, discordId = "111")
        assertEquals("111", fromUser.id)
        assertTrue(fromUser.byDiscordId)
        val internalOnly = discordProfileKey(id = 9, discordId = "")
        assertEquals("9", internalOnly.id)
        assertFalse(internalOnly.byDiscordId)
        val auditUser = discordProfileKey(id = 0, discordId = "222", targetType = "user")
        assertEquals("222", auditUser.id)
        assertTrue(auditUser.byDiscordId)
    }

    @Test
    fun messageTagsParseJsonArray() {
        val row = JSONObject(
            """{"id":1,"messageId":"m1","content":"hi","tags":["colorado","love","weather"],
               "createdAt":"2026-08-20T12:00:00Z","user":{"id":9,"username":"iberry420"}}""",
        )
        val msg = DiscordParse.message(row)
        assertEquals(listOf("colorado", "love", "weather"), msg.tags)
    }

    @Test
    fun aiJobAndActivityParse() {
        val jobRaw = JSONObject(
            """{"ok":true,"data":{"job":{
              "id":7,"botId":4,"kind":"analyze","scope":"channel","guildId":"g1","channelId":"c1",
              "userId":9,"discordUserId":"111","timeframe":"1d","fromDate":"","toDate":"",
              "messageLimit":50,"skipTagged":false,"status":"running","total":50,"processed":3,
              "tagged":3,"skipped":0,"failed":0,"lastError":"","label":"Analyze · #bot-chat",
              "prompt":"Focus on arguments and who started them.",
              "summary":"People talked about Colorado weather.",
              "createdAt":"2026-08-21T12:00:00Z","updatedAt":"2026-08-21T12:01:00Z"
            }}}""",
        )
        val job = DiscordParse.aiJobWrap(jobRaw)
        assertEquals(7, job.id)
        assertEquals("analyze", job.kind)
        assertEquals("channel", job.scope)
        assertEquals(50, job.messageLimit)
        assertEquals(3, job.tagged)
        assertFalse(job.skipTagged)
        assertEquals("Focus on arguments and who started them.", job.prompt)
        assertTrue(job.summary.contains("Colorado"))

        val act = DiscordParse.aiActivity(
            JSONObject(
                """{"ok":true,"data":{"items":[{
                  "id":11,"jobId":7,"messageId":99,"guildId":"g1","guildName":"GROK COMMUNITY",
                  "channelId":"c1","channelName":"bot-chat","content":"i love the weather in Colorado",
                  "tags":["colorado","love","weather"],"status":"ok","error":"",
                  "createdAt":"2026-08-21T12:01:00Z",
                  "user":{"id":9,"discordId":"111","username":"iberry420","displayName":"iBerry","avatar":"https://host/a","roles":["Developer","Gamer"]}
                }],"hasMore":true}}""",
            ),
        )
        assertEquals(1, act.items.size)
        assertTrue(act.hasMore)
        assertEquals("colorado", act.items[0].tags[0])
        assertEquals("iBerry", act.items[0].displayName)
        assertEquals(listOf("Developer", "Gamer"), act.items[0].roles)

        val summaryDup = DiscordParse.aiResult(
            JSONObject(
                """{"id":12,"jobId":7,"messageId":0,"guildId":"g1","guildName":"GROK COMMUNITY",
                  "channelId":"c1","channelName":"bot-chat",
                  "content":"People talked about Colorado weather.","tags":[],"status":"ok","error":"",
                  "createdAt":"2026-08-21T12:02:00Z",
                  "user":{"id":9,"discordId":"111","username":"iberry420","displayName":"iBerry","avatar":""}}""",
            ),
        )
        assertTrue(discordAiResultIsJobSummary(summaryDup))
        assertFalse(discordAiResultIsJobSummary(act.items[0]))
    }

    @Test
    fun userTagsPageParse() {
        val page = DiscordParse.tagPage(
            JSONObject(
                """{"ok":true,"data":{
                  "topTags":[{"tag":"colorado","count":40},{"tag":"weather","count":12}],
                  "totalTagCount":900,"uniqueTagCount":1200,"hasMoreTags":true,"tagOffset":24,"tagLimit":80
                }}""",
            ),
        )
        assertEquals(2, page.tags.size)
        assertEquals(1200, page.uniqueTagCount)
        assertTrue(page.hasMore)
        assertEquals(24, page.offset)
    }

    @Test
    fun aiSettingsAndJobModel() {
        val settings = DiscordParse.aiSettings(
            JSONObject(
                """{"ok":true,"data":{
                  "provider":"spacexai","listingProvider":"spacexai","model":"grok-4.6",
                  "reasoningEffort":"high","keySet":true,"keyHint":"…wxyz","keySource":"settings",
                  "bridgeHealthy":true,"bridgeError":"","defaultModel":"grok-4.6","defaultEffort":"high",
                  "models":[
                    {"id":"grok-4.6","name":"grok-4.6","provider":"spacexai",
                     "reasoning_efforts":["low","medium","high","xhigh"],"default_reasoning_effort":"xhigh"},
                    {"id":"grok-4.5","name":"grok-4.5","provider":"spacexai",
                     "reasoning_efforts":["low","medium","high"],"default_reasoning_effort":"high"}
                  ]
                }}""",
            ),
        )
        assertEquals("spacexai", settings.provider)
        assertEquals("grok-4.6", settings.model)
        assertEquals(2, settings.models.size)
        assertEquals(listOf("low", "medium", "high", "xhigh"), settings.models[0].reasoningEfforts)
        assertTrue(settings.keySet)
        assertEquals("…wxyz", settings.keyHint)

        val job = DiscordParse.aiJob(
            JSONObject(
                """{"id":3,"kind":"analyze","status":"queued","provider":"bridge","model":"grok-4.6",
                   "reasoningEffort":"xhigh","label":"Analyze · all","prompt":"focus"}""",
            ),
        )
        assertEquals("bridge", job.provider)
        assertEquals("grok-4.6", job.model)
        assertEquals("xhigh", job.reasoningEffort)
        assertEquals("focus", job.prompt)
    }

    @Test
    fun ttsChunksStayUnderCap() {
        val short = discordAiTtsChunks("hello summary")
        assertEquals(listOf("hello summary"), short)
        assertTrue(discordAiTtsChunks("").isEmpty())

        val para = "First sentence. Second sentence? Third sentence!\n\nNext paragraph here."
        val parts = discordAiTtsChunks(para, maxChars = 40)
        assertTrue(parts.size >= 2)
        assertTrue(parts.all { it.length <= 40 })
        assertTrue(parts.first().startsWith("First"))
        assertTrue(parts.last().contains("paragraph"))
        assertTrue(parts.joinToString("").replace(" ", "").contains("Secondsentence"))
    }

    @Test
    fun guildSemanticTaggingAndBotPills() {
        val guilds = DiscordParse.guilds(
            JSONObject(
                """{"ok":true,"data":[
                  {"id":1,"guildId":"g1","guildName":"GREYCORD","isWatched":true,"semanticTagging":false,
                   "bots":[
                     {"botId":2,"name":"LYNX","isWatched":true,"semanticTagging":true},
                     {"botId":4,"name":"AvalynnAI","isWatched":false,"semanticTagging":false}
                   ]}
                ]}""",
            ),
        )
        assertTrue(guilds[0].semanticTagging)
        assertTrue(guilds[0].isWatched)
        assertTrue(guilds[0].bots[0].semanticTagging)
        val taggingOnly = DiscordParse.guilds(
            JSONObject(
                """{"ok":true,"data":[
                  {"id":1,"guildId":"g1","guildName":"GREYCORD","isWatched":true,"semanticTagging":true,
                   "bots":[{"botId":2,"name":"LYNX","isWatched":true,"semanticTagging":true}]}
                ]}""",
            ),
        )
        assertTrue(taggingOnly[0].isWatched)
        assertTrue(taggingOnly[0].semanticTagging)
        val taggingOff = DiscordParse.guilds(
            JSONObject(
                """{"ok":true,"data":[
                  {"id":1,"guildId":"g1","guildName":"GREYCORD","isWatched":true,"semanticTagging":false,
                   "bots":[
                     {"botId":2,"name":"LYNX","isWatched":true,"semanticTagging":false},
                     {"botId":4,"name":"AvalynnAI","isWatched":true,"semanticTagging":false}
                   ]}
                ]}""",
            ),
        )
        assertFalse(taggingOff[0].semanticTagging)
        assertEquals(listOf(null, 2, 4), discordAutoTagPatchBotIds(listOf(2, 4, 2)))
        assertEquals(listOf(null), discordAutoTagPatchBotIds(emptyList()))
        assertEquals("-", discordFormatBotIdSet(emptySet()))
        assertEquals("2,4", discordFormatBotIdSet(setOf(4, 2)))
        assertEquals(setOf(2, 4), discordParseBotIdSet("2,4"))
        assertEquals(emptySet<Int>(), discordParseBotIdSet("-"))
        assertEquals(null, discordParseBotIdSet(""))
    }

    @Test
    fun channelWatchedForSelectedBots() {
        val ch = DiscordChannel(
            channelId = "c1",
            guildId = "g1",
            name = "general",
            type = 0,
            isEnabled = true,
            isMuted = false,
            respondToAll = false,
            bots = listOf(
                DiscordChannelBot(2, "LYNX", true, false, false),
                DiscordChannelBot(4, "AvalynnAI", false, false, false),
            ),
        )
        assertTrue(ch.watchedFor(setOf(2)))
        assertFalse(ch.watchedFor(setOf(4)))
        assertTrue(ch.watchedFor(setOf(2, 4)))
    }

    @Test
    fun discogramExitNeedsSecondBackInsideWindow() {
        assertFalse(discordDiscogramConfirmExit(0L, 1_000L))
        assertTrue(discordDiscogramConfirmExit(1_000L, 2_500L))
        assertFalse(discordDiscogramConfirmExit(1_000L, 1_000L))
        assertFalse(discordDiscogramConfirmExit(1_000L, 3_000L))
        assertFalse(discordDiscogramConfirmExit(1_000L, 4_000L))
        assertTrue(discordDiscogramConfirmExit(1_000L, 2_999L, 2_000L))
    }

    @Test
    fun discogramImageLeavesSingleFingerSwipeToPager() {
        assertFalse(discordImageOwnsPointer(1, 1f))
        assertFalse(discordImageOwnsPointer(1, DISCORD_IMAGE_ZOOM_EPS))
        assertTrue(discordImageOwnsPointer(2, 1f))
        assertTrue(discordImageOwnsPointer(1, 2.5f))
        assertTrue(discordImageOwnsPointer(3, 1.2f))
    }

    @Test
    fun mergePageDropsDuplicateKeys() {
        val a = DiscordParse.message(JSONObject("""{"id":1,"messageId":"m1","content":"a","user":{}}"""))
        val b = DiscordParse.message(JSONObject("""{"id":2,"messageId":"m2","content":"b","user":{}}"""))
        val dup = DiscordParse.message(JSONObject("""{"id":1,"messageId":"m1","content":"a2","user":{}}"""))
        val merged = listOf(a).discordMergePage(listOf(dup, b), reset = false) { it.lazyKey() }
        assertEquals(2, merged.size)
        assertEquals("m2", merged[1].messageId)
        val reset = listOf(a, b).discordMergePage(listOf(b, b), reset = true) { it.lazyKey() }
        assertEquals(1, reset.size)
        assertEquals("m2", reset[0].messageId)
    }

    @Test
    fun mergeLiveUpdatesTagsAndPrependsNewerOnly() {
        fun msg(
            id: Int,
            messageId: String,
            tags: String = "",
            content: String = "x",
            createdAt: String = "2026-08-22T12:00:00.000Z",
        ): DiscordMessage = DiscordParse.message(
            JSONObject(
                """{"id":$id,"messageId":"$messageId","content":"$content","tags":"$tags","createdAt":"$createdAt","user":{}}""",
            ),
        )
        val shown = listOf(msg(10, "m10"))
        val incoming = listOf(
            msg(12, "m12", content = "new", createdAt = "2026-08-22T12:00:02.000Z"),
            msg(10, "m10", tags = "alpha,beta"),
            msg(8, "m8", content = "old", createdAt = "2026-08-22T11:59:00.000Z"),
        )
        val merged = shown.discordMergeLive(incoming)
        assertEquals(2, merged.size)
        assertEquals("m12", merged[0].messageId)
        assertEquals("new", merged[0].content)
        assertEquals("m10", merged[1].messageId)
        assertEquals(listOf("alpha", "beta"), merged[1].tags)
        val empty = emptyList<DiscordMessage>().discordMergeLive(incoming)
        assertEquals(3, empty.size)
        val unchanged = shown.discordMergeLive(emptyList())
        assertEquals(shown, unchanged)
    }

    @Test
    fun stableMediaKeyIgnoresExpiringSignature() {
        val a = discordStableMediaKey(
            "https://host/api/discord.php?action=avatar&user=1&hash=abc&exp=100&sig=aaa",
        )
        val b = discordStableMediaKey(
            "https://host/api/discord.php?action=avatar&user=1&hash=abc&exp=999&sig=bbb",
        )
        assertEquals(a, b)
        assertTrue(a.contains("hash=abc"))
        assertFalse(a.contains("exp="))
        assertFalse(a.contains("sig="))
        assertEquals(
            "https://host/api/discord.php?action=file&id=7",
            discordStableMediaKey("https://host/api/discord.php?action=file&id=7&exp=1&sig=z"),
        )
    }

    @Test
    fun mergeLiveKeepsAvatarWhenOnlySignatureChanges() {
        fun msg(id: Int, avatar: String, tags: String = ""): DiscordMessage = DiscordParse.message(
            JSONObject(
                """{"id":$id,"messageId":"m$id","content":"hi","tags":"$tags",
                   "createdAt":"2026-08-22T12:00:00.000Z",
                   "user":{"id":9,"discordId":"d1","username":"nyna","avatar":"$avatar"}}""",
            ),
        )
        val first = "https://host/api/discord.php?action=avatar&user=d1&hash=abc&exp=100&sig=aaa"
        val later = "https://host/api/discord.php?action=avatar&user=d1&hash=abc&exp=200&sig=bbb"
        val shown = listOf(msg(10, first))
        val merged = shown.discordMergeLive(listOf(msg(10, later, tags = "alpha")))
        assertEquals(first, merged[0].avatar)
        assertEquals(listOf("alpha"), merged[0].tags)
        val same = shown.discordMergeLive(listOf(msg(10, later)))
        assertEquals(shown, same)
    }

    @Test
    fun attachmentParsesLikedAndFollowing() {
        val att = DiscordParse.attachment(
            JSONObject(
                """{"id":7,"filename":"dance.gif","contentType":"image/gif",
                   "url":"https://host/api/discord.php?action=file&id=7","kind":"gif",
                   "liked":true,"following":true,
                   "user":{"id":1,"discordId":"99","username":"nyna","displayName":"Nyna"}}""",
            ),
        )
        assertTrue(att.liked)
        assertTrue(att.following)
        assertEquals("99", att.discordId)
        assertEquals("", att.thumbUrl)
        val plain = DiscordParse.attachment(
            JSONObject("""{"id":8,"filename":"x.png","contentType":"image/png","url":"https://host/api/discord.php?action=file&id=8"}"""),
        )
        assertFalse(plain.liked)
        assertFalse(plain.following)
        assertTrue(plain.playable)
        val stale = DiscordParse.attachment(
            JSONObject("""{"id":9,"filename":"old.ogg","contentType":"audio/ogg","url":"","local":false,"playable":false}"""),
        )
        assertFalse(stale.playable)
        assertEquals("", stale.url)
        val inferred = DiscordParse.attachment(
            JSONObject("""{"id":10,"filename":"x.png","contentType":"image/png"}"""),
        )
        assertFalse(inferred.playable)
        val video = DiscordParse.attachment(
            JSONObject(
                """{"id":11,"filename":"clip.mp4","contentType":"video/mp4",
                   "url":"https://host/api/discord.php?action=file&id=11",
                   "thumbUrl":"https://host/api/discord.php?action=file&id=11&thumb=1",
                   "kind":"video"}""",
            ),
        )
        assertEquals("video", video.kind)
        assertTrue(video.thumbUrl.contains("thumb=1"))
        val page = DiscordParse.attachments(
            JSONObject(
                """{"ok":true,"data":{"attachments":[{"id":11,"filename":"clip.mp4","contentType":"video/mp4",
                   "url":"https://host/api/discord.php?action=file&id=11","kind":"video"}],
                   "hasMore":true,"cursor":7,"total":12}}""",
            ),
        )
        assertEquals(7, page.cursor)
        assertTrue(page.hasMore)
    }

    @Test
    fun oggAndOpusUseOggContainerMime() {
        assertEquals("audio/ogg", discordPlayerMime("audio/ogg", "clip.ogg"))
        assertEquals("audio/ogg", discordPlayerMime("audio/ogg; codecs=opus", "voice-message.ogg"))
        assertEquals("audio/ogg", discordPlayerMime("audio/opus", "voice-message.ogg"))
        assertEquals("audio/ogg", discordPlayerMime("application/ogg", "song.ogg"))
        assertEquals("audio/ogg", discordPlayerMime("", "clip.ogg"))
        assertEquals("audio/ogg", discordPlayerMime("", "note.opus"))
        assertEquals("audio/mpeg", discordPlayerMime("audio/mpeg", "a.mp3"))
        assertEquals("audio", discordMediaKind("application/ogg", "clip.ogg"))
        assertEquals("audio", discordMediaKind("audio/opus", "voice-message.ogg"))
    }

    @Test
    fun downloadNameStaysInOneSafeSegment() {
        assertEquals("dance.gif", discordSafeDownloadName("dance.gif", "image/gif"))
        assertEquals("clip.mp4", discordSafeDownloadName("../clip.mp4", "video/mp4"))
        assertEquals("photo.jpg", discordSafeDownloadName("a/b/photo.jpg", "image/jpeg"))
        assertEquals("audio.m4a", discordSafeDownloadName("", "audio/mp4"))
        assertFalse(discordSafeDownloadName("hi.png\u0000x", "image/png").contains("\u0000"))
    }
}
