package io.grokify.os.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object UsageFormat {
    fun compactTokens(n: Long): String {
        if (n >= 1_000_000L) {
            val v = n / 1_000_000.0
            return trimDecimal(v) + "M"
        }
        if (n >= 1_000L) {
            val v = n / 1_000.0
            return trimDecimal(v) + "k"
        }
        return n.toString()
    }

    fun compactDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        if (s < 60L) return "${s}s"
        if (s < 3600L) return "${s / 60L}m"
        val hours = s / 3600.0
        return if (hours < 10.0) trimDecimal(hours) + "h" else "${hours.toLong()}h"
    }

    fun compactCount(n: Long): String = compactTokens(n)

    fun shortDay(isoDay: String): String {
        return try {
            val d = LocalDate.parse(isoDay.take(10))
            d.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))
        } catch (_: Exception) {
            isoDay.takeLast(5)
        }
    }

    private fun trimDecimal(v: Double): String {
        val rounded = Math.round(v * 10.0) / 10.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }
}
