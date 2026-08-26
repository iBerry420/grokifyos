package io.grokify.os.apps.gbot

import org.json.JSONArray
import org.json.JSONObject

internal data class GbotChromeCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val secure: Boolean? = null,
    val httpOnly: Boolean? = null,
    val sameSite: String = "",
    val expirationDate: Double? = null,
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
            .put("name", name)
            .put("value", value)
            .put("domain", domain)
            .put("path", path.ifBlank { "/" })
        if (secure != null) obj.put("secure", secure)
        if (httpOnly != null) obj.put("httpOnly", httpOnly)
        if (sameSite.isNotBlank()) obj.put("sameSite", sameSite)
        if (expirationDate != null) obj.put("expirationDate", expirationDate)
        return obj
    }
}

internal object GbotCookies {
    fun parse(text: String): List<GbotChromeCookie> {
        val raw = text.trim()
        if (raw.isBlank()) return emptyList()
        if (raw.startsWith("{") || raw.startsWith("[")) {
            val parsed = runCatching {
                if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
            }.getOrNull() ?: throw IllegalArgumentException("cookies JSON is invalid")
            val rows = when (parsed) {
                is JSONArray -> parsed
                is JSONObject -> parsed.optJSONArray("cookies") ?: JSONArray()
                else -> JSONArray()
            }
            val out = ArrayList<GbotChromeCookie>(rows.length())
            for (i in 0 until rows.length()) {
                fromJson(rows.optJSONObject(i))?.let { out.add(it) }
            }
            return out
        }
        return parseNetscape(raw)
    }

    fun toJsonArray(cookies: List<GbotChromeCookie>): JSONArray {
        val arr = JSONArray()
        cookies.forEach { arr.put(it.toJson()) }
        return arr
    }

    private fun fromJson(row: JSONObject?): GbotChromeCookie? {
        if (row == null) return null
        val name = row.optString("name").trim()
        val domain = row.optString("domain").trim()
        val value = if (row.has("value") && !row.isNull("value")) row.optString("value") else return null
        if (name.isBlank() || domain.isBlank()) return null
        val exp = when {
            row.has("expirationDate") && !row.isNull("expirationDate") -> row.optDouble("expirationDate")
            row.has("expires") && !row.isNull("expires") -> row.optDouble("expires")
            else -> Double.NaN
        }
        return GbotChromeCookie(
            name = name,
            value = value,
            domain = domain,
            path = row.optString("path").ifBlank { "/" },
            secure = if (row.has("secure")) row.optBoolean("secure") else null,
            httpOnly = when {
                row.has("httpOnly") -> row.optBoolean("httpOnly")
                row.has("httponly") -> row.optBoolean("httponly")
                else -> null
            },
            sameSite = row.optString("sameSite").ifBlank { row.optString("same_site") },
            expirationDate = if (exp.isNaN() || exp <= 0.0) null else exp,
        )
    }

    private fun parseNetscape(text: String): List<GbotChromeCookie> {
        val out = ArrayList<GbotChromeCookie>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isBlank() || t.startsWith("#")) continue
            val cols = t.split('\t')
            if (cols.size < 7) continue
            val domain = cols[0].trim()
            val name = cols[5].trim()
            if (domain.isBlank() || name.isBlank()) continue
            val expires = cols[4].toDoubleOrNull()
            out.add(
                GbotChromeCookie(
                    name = name,
                    value = cols.drop(6).joinToString("\t"),
                    domain = domain,
                    path = cols[2].ifBlank { "/" },
                    secure = cols[3].equals("TRUE", ignoreCase = true),
                    expirationDate = if (expires != null && expires > 0.0) expires else null,
                ),
            )
        }
        return out
    }
}
