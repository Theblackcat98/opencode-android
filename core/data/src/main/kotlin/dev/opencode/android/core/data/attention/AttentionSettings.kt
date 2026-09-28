package dev.opencode.android.core.data.attention

/**
 * The hours in which a session does not interrupt the user.
 *
 * **Local wall-clock minutes, not instants.** Quiet hours mean "after work", which is a statement
 * about the user's day rather than about a moment, so a window is stored as two minutes-of-day and
 * evaluated against the device's own clock. A window that wraps midnight (22:00 to 07:00) is the
 * common case and is handled here rather than by asking the caller to split it into two.
 */
data class QuietHours(
    val enabled: Boolean = false,
    val startMinuteOfDay: Int = DEFAULT_START,
    val endMinuteOfDay: Int = DEFAULT_END,
) {
    /** True when [minuteOfDay], 0 to 1439, falls inside the window. The end is exclusive. */
    fun isQuietAt(minuteOfDay: Int): Boolean {
        if (!enabled) return false
        if (startMinuteOfDay == endMinuteOfDay) return false
        val minute = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
        return if (startMinuteOfDay < endMinuteOfDay) {
            minute >= startMinuteOfDay && minute < endMinuteOfDay
        } else {
            minute >= startMinuteOfDay || minute < endMinuteOfDay
        }
    }

    /** True when the device's local clock is inside the window. */
    fun isQuietAt(epochMillis: Long, utcOffsetMillis: Long): Boolean =
        isQuietAt(minuteOfDayOf(epochMillis, utcOffsetMillis))

    /** "22:00" to "07:00", or `null` while the window is off. */
    fun label(): String? {
        if (!enabled) return null
        return "${format(startMinuteOfDay)} to ${format(endMinuteOfDay)}"
    }

    private fun format(minute: Int): String {
        val hour = minute / 60
        val rest = minute % 60
        return "%02d:%02d".format(hour, rest)
    }

    companion object {
        const val MINUTES_PER_DAY: Int = 24 * 60
        private const val DEFAULT_START: Int = 22 * 60
        private const val DEFAULT_END: Int = 7 * 60

        /** A window from [start] to [end], both as minutes since midnight. */
        fun of(startMinuteOfDay: Int, endMinuteOfDay: Int): QuietHours =
            QuietHours(enabled = true, startMinuteOfDay = startMinuteOfDay, endMinuteOfDay = endMinuteOfDay)
    }
}

/** The minute of the day [epochMillis] falls on, given the device's offset from UTC. */
fun minuteOfDayOf(epochMillis: Long, utcOffsetMillis: Long): Int =
    (Math.floorDiv(epochMillis + utcOffsetMillis, 60_000L) % QuietHours.MINUTES_PER_DAY)
        .toInt()
        .let { if (it < 0) it + QuietHours.MINUTES_PER_DAY else it }

/**
 * The settings Phase 4 keeps on the device (features doc §33.4, "Attention"; plan §6).
 *
 * All of them are the client's, not the server's. The server has no idea which sessions a user wants
 * to be left alone in, and it must not be asked: a mute that travelled to the server would silence
 * the desktop too.
 *
 * **Per server where the meaning is per server, per session where it is per session.** "Always
 * connected" and quiet hours are about a connection, so they are keyed by server. Mute and
 * auto-approve are about one piece of work, so they are keyed by session. Auto-approve also has a
 * global scope because the TUI's toggle is app-wide.
 */
data class AttentionSettings(
    /** How long the connection service waits after the last active thing before it stops. */
    val idleGraceMillis: Long = DEFAULT_IDLE_GRACE_MILLIS,
    /** Server ids with "always connected" on. */
    val alwaysConnected: Set<String> = emptySet(),
    /** Per-server quiet hours; a server with no entry has none. */
    val quietHours: Map<String, QuietHours> = emptyMap(),
    /** Sessions that do not interrupt the user at all. */
    val mutedSessions: Set<String> = emptySet(),
    /** When global auto-approve stops, or `null` when it is off. */
    val autoApproveGlobalUntil: Long? = null,
    /** Per-session auto-approve, keyed by session id. */
    val autoApproveSessions: Map<String, Long> = emptyMap(),
) {
    fun alwaysConnectedFor(serverId: String?): Boolean = serverId != null && serverId in alwaysConnected

    fun quietHoursFor(serverId: String?): QuietHours =
        serverId?.let(quietHours::get) ?: QuietHours()

    fun isMuted(sessionID: String): Boolean = sessionID in mutedSessions

    /** The instant per-session auto-approve for [sessionID] stops, or `null` when it is off. */
    fun autoApproveUntilFor(sessionID: String): Long? = autoApproveSessions[sessionID]

    /** True when any scope of auto-approve is still running at [now]. */
    fun autoApproveActive(sessionID: String?, now: Long): Boolean = when {
        autoApproveGlobalUntil != null && autoApproveGlobalUntil > now -> true
        sessionID != null && (autoApproveSessions[sessionID] ?: 0L) > now -> true
        else -> false
    }

    companion object {
        /** The plan's default: two minutes of grace after the last active thing. */
        const val DEFAULT_IDLE_GRACE_MILLIS: Long = 2 * 60 * 1000L
    }
}
