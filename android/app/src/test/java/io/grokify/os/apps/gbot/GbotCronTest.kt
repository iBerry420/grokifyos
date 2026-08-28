package io.grokify.os.apps.gbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GbotCronTest {
    @Test
    fun composeFifteenAllDayAndWindow() {
        assertEquals("*/15 * * * *", composeCron(15, true, 8, 21))
        assertEquals("*/15 8-21 * * *", composeCron(15, false, 8, 21))
        assertEquals("*/30 9-17 * * *", composeCron(30, false, 9, 17))
        assertEquals("0 * * * *", composeCron(60, true, 8, 21))
        assertEquals("0 8-21 * * *", composeCron(60, false, 8, 21))
        assertEquals("0 */2 * * *", composeCron(120, true, 8, 21))
        assertEquals("0 8-21/2 * * *", composeCron(120, false, 8, 21))
        assertEquals("0 0 * * *", composeCron(1440, true, 8, 21))
        assertEquals("0 8 * * *", composeCron(1440, false, 8, 21))
        assertEquals("*/15 21-23,0-8 * * *", composeCron(15, false, 21, 8))
    }

    @Test
    fun parseRoundTripCommonCrons() {
        listOf(
            "*/15 * * * *",
            "*/15 8-21 * * *",
            "*/5 9-17 * * *",
            "0 * * * *",
            "0 8-21 * * *",
            "0 */2 * * *",
            "0 8-21/2 * * *",
            "0 8 * * *",
        ).forEach { cron ->
            val parsed = GbotCron.parse(cron)
            assertFalse(cron, parsed.custom)
            assertEquals(cron, GbotCron.compose(parsed))
        }
    }

    @Test
    fun parseWindowAndFrequency() {
        val windowed = GbotCron.parse("*/15 8-21 * * *")
        assertEquals(15, windowed.everyMinutes)
        assertFalse(windowed.allDay)
        assertEquals(8, windowed.startHour)
        assertEquals(21, windowed.endHour)
        assertEquals("every 15 min · 08:00–21:00", windowed.describe())

        val allDay = GbotCron.parse("*/15 * * * *")
        assertTrue(allDay.allDay)
        assertEquals("every 15 min · 24 hours", allDay.describe())
    }

    @Test
    fun looksLikeCron() {
        assertTrue(GbotCron.looksLikeCron("*/15 8-21 * * *"))
        assertFalse(GbotCron.looksLikeCron("Every 15 minutes"))
        assertFalse(GbotCron.looksLikeCron("15 8 21"))
    }

    @Test
    fun mergeReplacesCronKeepsListeners() {
        val group = JSONObject()
            .put("type", "group")
            .put(
                "listeners",
                JSONArray()
                    .put(JSONObject().put("type", "slack").put("channel", "desk"))
                    .put(JSONObject().put("type", "cron").put("schedule", "*/15 * * * *")),
            )
            .toString()
        val merged = GbotCron.mergeTrigger(group, "*/30 8-21 * * *")
        assertEquals("group", merged.getString("type"))
        val listeners = merged.getJSONArray("listeners")
        assertEquals(2, listeners.length())
        assertEquals("slack", listeners.getJSONObject(0).getString("type"))
        assertEquals("cron", listeners.getJSONObject(1).getString("type"))
        assertEquals("*/30 8-21 * * *", listeners.getJSONObject(1).getString("schedule"))
    }

    @Test
    fun mergePlainCron() {
        val merged = GbotCron.mergeTrigger(
            JSONObject().put("type", "cron").put("schedule", "*/15 * * * *").toString(),
            "*/10 * * * *",
        )
        assertEquals("cron", merged.getString("type"))
        assertEquals("*/10 * * * *", merged.getString("schedule"))
    }
}
