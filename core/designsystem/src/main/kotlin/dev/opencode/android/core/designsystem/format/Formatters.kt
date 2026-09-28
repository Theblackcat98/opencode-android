package dev.opencode.android.core.designsystem.format

import java.util.Locale

/**
 * The numbers a session shows, formatted.
 *
 * They are pure functions of the value and a clock, so they are unit tested rather than trusted to
 * a locale-sensitive formatter: a cost that reads `$0.0000001` and a token count that reads
 * `1234567` are both correct numbers and both useless in a list row.
 */
object Formatters {

    /** Cost in US dollars. Sub-cent amounts keep four decimals, because a cheap turn is still a cost. */
    fun cost(value: Double): String = when {
        value <= 0.0 -> "$0.00"
        value < 0.01 -> "$" + String.format(Locale.US, "%.4f", value)
        value < 1000.0 -> "$" + String.format(Locale.US, "%.2f", value)
        else -> "$" + String.format(Locale.US, "%,.0f", value)
    }

    /**
     * A token count, abbreviated past a million.
     *
     * Abbreviation rather than a grouped digit string because a list row has room for `1.2M` and
     * not for `1,234,567`.
     */
    fun tokens(value: Long): String = when {
        value < 0 -> "0"
        value < 1_000 -> value.toString()
        value < 1_000_000 -> trim(value / 1_000.0) + "k"
        value < 1_000_000_000 -> trim(value / 1_000_000.0) + "M"
        else -> trim(value / 1_000_000_000.0) + "B"
    }

    /** A percentage of a limit, clamped, for the context gauge. */
    fun percent(used: Long, limit: Long): Int {
        if (limit <= 0) return 0
        return ((used.toDouble() / limit.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }

    /** A file size, for attachments and tool results. */
    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = listOf("kB", "MB", "GB", "TB")
        var size = value.toDouble() / 1024.0
        var unit = 0
        while (size >= 1024.0 && unit < units.lastIndex) {
            size /= 1024.0
            unit++
        }
        return trim(size) + " " + units[unit]
    }

    /** A duration in milliseconds, as a tool or a reasoning block takes. */
    fun duration(millis: Long?): String {
        if (millis == null || millis < 0) return ""
        if (millis < 1_000) return "${millis}ms"
        val seconds = millis / 1_000.0
        if (seconds < 60) return trim(seconds) + "s"
        val minutes = (seconds / 60).toInt()
        val rest = (seconds % 60).toInt()
        if (minutes < 60) return if (rest == 0) "${minutes}m" else "${minutes}m ${rest}s"
        val hours = minutes / 60
        return "${hours}h ${minutes % 60}m"
    }

    /**
     * How long ago, relative to [now].
     *
     * Deliberately coarse: a list row shows "3m", not "3 minutes and 12 seconds ago", and a clock
     * that re-renders every second is a clock that costs frames during a live turn.
     */
    fun relativeTime(then: Long, now: Long): String {
        if (then <= 0L) return ""
        val delta = now - then
        if (delta < 0) return "now"
        val minutes = delta / 60_000
        return when {
            delta < 60_000 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
            else -> "${minutes / (60 * 24 * 7)}w"
        }
    }

    /**
     * The last path segment of a directory, which is what a session row shows.
     *
     * A trailing separator is stripped first, because a path that ends in `/` is what a user typed
     * rather than what the server stored.
     */
    fun directoryName(directory: String): String = directory
        .trimEnd('/', '\\')
        .substringAfterLast('/', directory)
        .substringAfterLast('\\')
        .ifBlank { directory }

    /** The last two path segments, for a project chip that has to disambiguate. */
    fun directoryTail(directory: String, segments: Int = 2): String {
        val parts = directory.trimEnd('/', '\\').split('/', '\\').filter { it.isNotEmpty() }
        return parts.takeLast(segments).joinToString("/")
    }

    private fun trim(value: Double): String {
        val rounded = String.format(Locale.US, "%.1f", value)
        return if (rounded.endsWith(".0")) rounded.dropLast(2) else rounded
    }
}

/**
 * The full relative time, for a content description.
 *
 * TalkBack cannot use the abbreviation, so the screen passes this instead and a sighted user never
 * sees it.
 */
fun relativeTimeDescription(then: Long, now: Long): String {
    val delta = (now - then).coerceAtLeast(0)
    val minutes = delta / 60_000
    val value: Long = when {
        delta < 60_000 -> 0
        minutes < 60 -> minutes
        minutes < 60 * 24 -> minutes / 60
        else -> minutes / (60 * 24)
    }
    val unit = when {
        delta < 3_600_000 -> "minute"
        delta < 86_400_000 -> "hour"
        else -> "day"
    }
    val plural = if (value == 1L) "" else "s"
    return when (value) {
        0L -> "just now"
        else -> "$value $unit$plural ago"
    }
}
