package dev.opencode.android.core.data.attention

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.attentionPreferences: DataStore<Preferences> by preferencesDataStore(name = "attention")

/**
 * The device-side attention settings (plan §6, "Notification housekeeping" and "Auto-approve mode").
 *
 * The same shape as the model picker's [dev.opencode.android.core.data.preferences.ModelPreferences]:
 * a handful of values per server and per session, never queried and never synced, so DataStore rather
 * than a table in what is otherwise a cache of the server's own projection.
 *
 * **Durations are stored as the instant they end at, not as a length.** Auto-approve is optionally
 * time-limited, and a stored length would be re-read after a reboot as a fresh window; an instant is
 * a fact about the past and cannot be extended by reading it.
 */
interface AttentionPreferences {
    val settings: Flow<AttentionSettings>

    /** Turns "always connected" on or off for one server. */
    suspend fun setAlwaysConnected(serverId: String, enabled: Boolean)

    /** Sets the idle grace period. Values below [MINIMUM_IDLE_GRACE_MILLIS] are clamped. */
    suspend fun setIdleGraceMillis(millis: Long)

    suspend fun setQuietHours(serverId: String, hours: QuietHours)

    /** Silences one session, or unsilences it. */
    suspend fun setSessionMuted(sessionID: String, muted: Boolean)

    /**
     * Turns global auto-approve on for [minutes], or off when [minutes] is null.
     *
     * [now] is passed in rather than read from the clock so the caller, and a test, agree on when
     * "now" is.
     */
    suspend fun setAutoApproveGlobal(minutes: Long?, now: Long)

    /** Turns auto-approve on for one session for [minutes], or off when [minutes] is null. */
    suspend fun setAutoApproveSession(sessionID: String, minutes: Long?, now: Long)
}

/** The DataStore-backed store the app uses. */
@Singleton
class DataStoreAttentionPreferences @Inject constructor(
    private val context: Context,
) : AttentionPreferences {

    override val settings: Flow<AttentionSettings> = context.attentionPreferences.data.map { it.toSettings() }

    override suspend fun setAlwaysConnected(serverId: String, enabled: Boolean) {
        context.attentionPreferences.edit { preferences ->
            val key = alwaysConnectedKey(serverId)
            if (enabled) {
                preferences[key] = true
            } else {
                preferences.remove(key)
            }
        }
    }

    override suspend fun setIdleGraceMillis(millis: Long) {
        context.attentionPreferences.edit { preferences ->
            preferences[IDLE_GRACE] = millis.coerceAtLeast(MINIMUM_IDLE_GRACE_MILLIS)
        }
    }

    override suspend fun setQuietHours(serverId: String, hours: QuietHours) {
        context.attentionPreferences.edit { preferences ->
            val key = quietHoursKey(serverId)
            if (hours.enabled) {
                preferences[key] = "${hours.startMinuteOfDay},${hours.endMinuteOfDay}"
            } else {
                preferences.remove(key)
            }
        }
    }

    override suspend fun setSessionMuted(sessionID: String, muted: Boolean) {
        context.attentionPreferences.edit { preferences ->
            val key = MUTED
            val current = preferences[key] ?: emptySet()
            preferences[key] = if (muted) current + sessionID else current - sessionID
        }
    }

    override suspend fun setAutoApproveGlobal(minutes: Long?, now: Long) {
        context.attentionPreferences.edit { preferences ->
            if (minutes == null) {
                preferences.remove(AUTO_APPROVE_GLOBAL)
            } else {
                preferences[AUTO_APPROVE_GLOBAL] = now + minutes * 60_000L
            }
        }
    }

    override suspend fun setAutoApproveSession(sessionID: String, minutes: Long?, now: Long) {
        context.attentionPreferences.edit { preferences ->
            val key = autoApproveSessionKey(sessionID)
            if (minutes == null) {
                preferences.remove(key)
            } else {
                preferences[key] = now + minutes * 60_000L
            }
        }
    }

    /**
     * Reads the file back.
     *
     * A value this build cannot read is dropped rather than guessed at: the file survives an app
     * upgrade, so a format change has to be survivable, and a session id that no longer parses is
     * a mute that silently does not apply.
     */
    private fun Preferences.toSettings(): AttentionSettings = AttentionSettings(
        idleGraceMillis = this[IDLE_GRACE] ?: AttentionSettings.DEFAULT_IDLE_GRACE_MILLIS,
        alwaysConnected = this[ALWAYS_CONNECTED] ?: emptySet(),
        quietHours = this[QUIET_HOURS]
            ?.lines()
            ?.mapNotNull(::parseQuietHours)
            ?.toMap()
            .orEmpty(),
        mutedSessions = this[MUTED] ?: emptySet(),
        autoApproveGlobalUntil = this[AUTO_APPROVE_GLOBAL],
        autoApproveSessions = this[AUTO_APPROVE_SESSIONS]
            ?.lines()
            ?.mapNotNull(::parseAutoApprove)
            ?.toMap()
            .orEmpty(),
    )

    private fun parseQuietHours(line: String): Pair<String, QuietHours>? {
        val parts = line.split(',')
        if (parts.size != 3) return null
        val (server, start, end) = parts
        val startMinute = start.toIntOrNull() ?: return null
        val endMinute = end.toIntOrNull() ?: return null
        return server to QuietHours.of(startMinute, endMinute)
    }

    private fun parseAutoApprove(line: String): Pair<String, Long>? {
        val separator = line.lastIndexOf(',')
        if (separator <= 0) return null
        val session = line.substring(0, separator)
        val until = line.substring(separator + 1).toLongOrNull() ?: return null
        return session to until
    }

    private fun alwaysConnectedKey(serverId: String) = booleanPreferencesKey("always_$serverId")

    private fun quietHoursKey(serverId: String) = stringPreferencesKey("quiet_$serverId")

    private fun autoApproveSessionKey(sessionID: String) = longPreferencesKey("auto_$sessionID")

    private companion object {
        val IDLE_GRACE = longPreferencesKey("idle_grace_millis")
        val ALWAYS_CONNECTED = stringSetPreferencesKey("always_connected")
        val QUIET_HOURS = stringPreferencesKey("quiet_hours")
        val MUTED = stringSetPreferencesKey("muted_sessions")
        val AUTO_APPROVE_GLOBAL = longPreferencesKey("auto_approve_global")
        val AUTO_APPROVE_SESSIONS = stringPreferencesKey("auto_approve_sessions")

        /**
         * Shorter than this and the service stops between two events of one turn, which is the
         * flapping the phase is trying to avoid.
         */
        const val MINIMUM_IDLE_GRACE_MILLIS: Long = 15_000L
    }
}
