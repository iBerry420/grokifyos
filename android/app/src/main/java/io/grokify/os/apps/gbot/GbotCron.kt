package io.grokify.os.apps.gbot

import org.json.JSONArray
import org.json.JSONObject

data class GbotCronEdit(
    val everyMinutes: Int = 15,
    val allDay: Boolean = true,
    val startHour: Int = 8,
    val endHour: Int = 21,
    val cronText: String = "*/15 * * * *",
    val custom: Boolean = false,
) {
    fun normalized(): GbotCronEdit {
        val start = startHour.coerceIn(0, 23)
        val end = endHour.coerceIn(0, 23)
        val minutes = everyMinutes.coerceIn(1, 1440)
        val cron = if (custom) {
            normalizeCron(cronText).ifBlank { composeCron(minutes, allDay, start, end) }
        } else {
            composeCron(minutes, allDay, start, end)
        }
        return copy(everyMinutes = minutes, startHour = start, endHour = end, cronText = cron)
    }

    fun describe(): String {
        val freq = GbotCron.frequencyLabel(everyMinutes)
        val hours = if (allDay) {
            if (everyMinutes >= 1440) "at ${hourLabel(0)}" else "24 hours"
        } else if (everyMinutes >= 1440) {
            "at ${hourLabel(startHour)}"
        } else {
            "${hourLabel(startHour)}–${hourLabel(endHour)}"
        }
        return "$freq · $hours"
    }
}

object GbotCron {
    val frequencies = listOf(5, 10, 15, 30, 60, 120, 240, 360, 1440)

    fun looksLikeCron(raw: String): Boolean {
        val parts = normalizeCron(raw).split(' ')
        return parts.size == 5 && parts.all { it.isNotBlank() && CRON_FIELD.matches(it) }
    }

    fun parse(raw: String): GbotCronEdit {
        val cron = normalizeCron(raw)
        val fallback = GbotCronEdit(cronText = cron.ifBlank { "*/15 * * * *" }, custom = cron.isNotBlank())
        val parts = cron.split(' ')
        if (parts.size != 5) return fallback
        val minute = parts[0]
        val hour = parts[1]
        val day = parts[2]
        val month = parts[3]
        val dow = parts[4]
        if (day != "*" || month != "*" || dow != "*") {
            return fallback.copy(custom = true)
        }
        val hours = parseHourField(hour) ?: return fallback.copy(custom = true)
        val parsed = when {
            minute.startsWith("*/") -> {
                val n = minute.removePrefix("*/").toIntOrNull() ?: return fallback.copy(custom = true)
                if (n !in 1..59) return fallback.copy(custom = true)
                GbotCronEdit(
                    everyMinutes = n,
                    allDay = hours.allDay,
                    startHour = hours.start,
                    endHour = hours.end,
                    cronText = cron,
                    custom = false,
                )
            }
            minute == "0" -> {
                val stepHours = hours.step
                val every = when {
                    hours.allDay && stepHours == 1 && hour == "*" -> 60
                    hours.allDay && hour.startsWith("*/") -> stepHours * 60
                    !hours.allDay && stepHours > 1 -> stepHours * 60
                    hour == "*" || (hours.allDay && stepHours == 1) -> 60
                    hours.start == hours.end && stepHours == 1 -> 1440
                    else -> 60
                }
                GbotCronEdit(
                    everyMinutes = every.coerceIn(1, 1440),
                    allDay = hours.allDay,
                    startHour = hours.start,
                    endHour = hours.end,
                    cronText = cron,
                    custom = false,
                )
            }
            else -> fallback.copy(custom = true)
        }
        return parsed
    }

    fun compose(edit: GbotCronEdit): String = edit.normalized().cronText

    fun mergeTrigger(existingJson: String, cron: String): JSONObject {
        val cronTrigger = JSONObject().put("type", "cron").put("schedule", normalizeCron(cron))
        val raw = existingJson.trim()
        if (raw.isBlank()) return cronTrigger
        return try {
            if (raw.startsWith("[")) {
                groupFromMembers(JSONArray(raw), cronTrigger)
            } else {
                val obj = JSONObject(raw)
                when (obj.optString("type")) {
                    "cron" -> cronTrigger
                    "group" -> groupFromMembers(obj.optJSONArray("listeners") ?: JSONArray(), cronTrigger)
                    else -> {
                        if (obj.optString("type").isBlank()) cronTrigger
                        else JSONObject()
                            .put("type", "group")
                            .put("listeners", JSONArray().put(obj).put(cronTrigger))
                    }
                }
            }
        } catch (_: Exception) {
            cronTrigger
        }
    }

    fun frequencyLabel(minutes: Int): String = when {
        minutes >= 1440 -> "daily"
        minutes % 60 == 0 -> {
            val hours = minutes / 60
            if (hours == 1) "every hour" else "every ${hours}h"
        }
        else -> "every $minutes min"
    }
}

internal fun composeCron(everyMinutes: Int, allDay: Boolean, startHour: Int, endHour: Int): String {
    val minutes = everyMinutes.coerceIn(1, 1440)
    val hours = hourField(allDay, startHour, endHour)
    return when {
        minutes >= 1440 -> {
            val hour = if (allDay) 0 else startHour.coerceIn(0, 23)
            "0 $hour * * *"
        }
        minutes % 60 == 0 -> {
            val step = (minutes / 60).coerceAtLeast(1)
            when {
                allDay && step == 1 -> "0 * * * *"
                allDay -> "0 */$step * * *"
                step == 1 -> "0 $hours * * *"
                else -> "0 $hours/$step * * *"
            }
        }
        else -> "*/$minutes $hours * * *"
    }
}

internal fun normalizeCron(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

internal fun hourLabel(hour: Int): String = "%02d:00".format(hour.coerceIn(0, 23))

private val CRON_FIELD = Regex("^[0-9*,/\\-]+$")

private data class HourParse(
    val allDay: Boolean,
    val start: Int,
    val end: Int,
    val step: Int = 1,
)

private fun parseHourField(raw: String): HourParse? {
    if (raw == "*") return HourParse(allDay = true, start = 8, end = 21, step = 1)
    if (raw.startsWith("*/")) {
        val step = raw.removePrefix("*/").toIntOrNull() ?: return null
        if (step !in 1..23) return null
        return HourParse(allDay = true, start = 8, end = 21, step = step)
    }
    val overnight = Regex("^(\\d{1,2})-23,0-(\\d{1,2})$").matchEntire(raw)
    if (overnight != null) {
        val start = overnight.groupValues[1].toInt()
        val end = overnight.groupValues[2].toInt()
        if (start !in 0..23 || end !in 0..23) return null
        return HourParse(allDay = false, start = start, end = end, step = 1)
    }
    val stepped = raw.split('/', limit = 2)
    val range = stepped[0]
    val step = stepped.getOrNull(1)?.toIntOrNull() ?: 1
    if (step !in 1..23) return null
    if (range.matches(Regex("^\\d{1,2}$"))) {
        val hour = range.toInt()
        if (hour !in 0..23) return null
        return HourParse(allDay = false, start = hour, end = hour, step = step)
    }
    val dash = Regex("^(\\d{1,2})-(\\d{1,2})$").matchEntire(range) ?: return null
    val start = dash.groupValues[1].toInt()
    val end = dash.groupValues[2].toInt()
    if (start !in 0..23 || end !in 0..23) return null
    return HourParse(allDay = false, start = start, end = end, step = step)
}

private fun hourField(allDay: Boolean, startHour: Int, endHour: Int): String {
    if (allDay) return "*"
    val start = startHour.coerceIn(0, 23)
    val end = endHour.coerceIn(0, 23)
    if (start == end) return start.toString()
    if (start < end) return "$start-$end"
    return "$start-23,0-$end"
}

private fun groupFromMembers(members: JSONArray, cronTrigger: JSONObject): JSONObject {
    val out = JSONArray()
    var replaced = false
    for (i in 0 until members.length()) {
        val item = members.optJSONObject(i) ?: continue
        if (item.optString("type") == "cron") {
            out.put(cronTrigger)
            replaced = true
        } else {
            out.put(item)
        }
    }
    if (!replaced) out.put(cronTrigger)
    if (out.length() == 1) return out.getJSONObject(0)
    return JSONObject().put("type", "group").put("listeners", out)
}
