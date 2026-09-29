package dev.opencode.android.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.experimentalPreferences: DataStore<Preferences> by preferencesDataStore(name = "experimental")

/** Which experimental routes this installation has agreed to use, as one value. */
data class ExperimentalSettings(
    /** Whether this app may write to the server's filesystem. Off by default (plan §5.2). */
    val fileWrites: Boolean = false,
    /** Whether this app may export and import transcripts. Off by default. */
    val sessionTransfer: Boolean = false,
    /**
     * Whether this app may use the session's persistent terminals.
     *
     * Off by default for the same reason as the other two and one more: eleven routes, two of which
     * (`shutdown`, `handoff`) end the terminals of every session on the server. The switch is what
     * says the app may call them; the first `404` says whether it can.
     */
    val persistentPty: Boolean = false,
    /**
     * Whether this app may change the server's running MCP servers (connect, disconnect, add,
     * remove). Off by default.
     *
     * **A separate switch from the well-known sources below, and the reason is what the routes do.**
     * These four write to a table that exists only until the server restarts, so the blast radius is
     * a restart. The well-known route instead makes the *server* fetch a URL, which can add an
     * integration that was never configured and which then offers its own login methods. One switch
     * for both would ask the user to consent to something they have not been told about.
     */
    val mcpRuntime: Boolean = false,
    /**
     * Whether this app may add an integration source by URL. Off by default, for the reason above.
     *
     * The URL is checked before it is sent — only http and https with a host, the same rule the
     * OAuth Custom Tab uses — but a valid URL is still a URL the server will fetch, so the switch is
     * the user's part of that decision.
     */
    val wellknownIntegrations: Boolean = false,
    /**
     * Whether this app may set the server's global `shell`. Off by default.
     *
     * **Its own switch rather than a fifth use of [fileWrites].** `shell` decides which program runs
     * the `bash` tool and every terminal on the server, so it is a behaviour change, but it is a
     * single documented key on a route that accepts nothing else (`Config.Patch` is
     * `additionalProperties: false`). A user who will not let the app edit their configuration files
     * has not said no to picking a shell from the list the server itself reported, and a user who
     * says yes to the shell picker has not said yes to arbitrary text being written to disk.
     */
    val configUpdate: Boolean = false,
    /**
     * Whether this app may read and write a session's durable instruction entries. Off by default.
     *
     * **A separate switch because these entries change what the agent is told, in a session that is
     * already running.** A file write is a change the user makes deliberately and reviews; an
     * instruction entry announces itself at the next step boundary of a session mid-turn, so the
     * user has less of a chance to notice it. That is a different risk and gets its own consent.
     */
    val sessionInstructions: Boolean = false,
)

/**
 * Which experimental routes this installation has agreed to use (plan §4.2, §5.2; features doc §38).
 *
 * **Off is the default, and that is the point.** Plan §2 finding 13 records that 29 operations live
 * under `/api/experimental/`: a server may add, change or remove any of them between releases, and a
 * client that calls one unconditionally breaks on the release that changes it. So each switch is per
 * *installation* rather than per server — the user decided once that this app may write to a server,
 * and asking per server would only teach them to leave it off everywhere — and the value is the
 * client's own, because the server has no such setting to read.
 *
 * **A switch is necessary but not sufficient.** Granting it says the route *may* be called; whether
 * it *can* is `CapabilityPolicy`'s answer from the first `404`, and both have to say yes before a
 * write happens (see `ReviewUiState.editingUsable`).
 */
interface ExperimentalPreferences {
    val settings: Flow<ExperimentalSettings>

    suspend fun setFileWrites(enabled: Boolean)

    suspend fun setSessionTransfer(enabled: Boolean)

    suspend fun setPersistentPty(enabled: Boolean)

    suspend fun setMcpRuntime(enabled: Boolean)

    suspend fun setWellknownIntegrations(enabled: Boolean)

    suspend fun setConfigUpdate(enabled: Boolean)

    suspend fun setSessionInstructions(enabled: Boolean)
}

/** The DataStore-backed store the app uses. */
@Singleton
class DataStoreExperimentalPreferences @Inject constructor(
    private val context: Context,
) : ExperimentalPreferences {

    override val settings: Flow<ExperimentalSettings> = context.experimentalPreferences.data.map { preferences ->
        ExperimentalSettings(
            fileWrites = preferences[FILE_WRITES] ?: false,
            sessionTransfer = preferences[SESSION_TRANSFER] ?: false,
            persistentPty = preferences[PERSISTENT_PTY] ?: false,
            mcpRuntime = preferences[MCP_RUNTIME] ?: false,
            wellknownIntegrations = preferences[WELLKNOWN_INTEGRATIONS] ?: false,
            configUpdate = preferences[CONFIG_UPDATE] ?: false,
            sessionInstructions = preferences[SESSION_INSTRUCTIONS] ?: false,
        )
    }

    override suspend fun setFileWrites(enabled: Boolean) {
        context.experimentalPreferences.edit { it[FILE_WRITES] = enabled }
    }

    override suspend fun setSessionTransfer(enabled: Boolean) {
        context.experimentalPreferences.edit { it[SESSION_TRANSFER] = enabled }
    }

    override suspend fun setPersistentPty(enabled: Boolean) {
        context.experimentalPreferences.edit { it[PERSISTENT_PTY] = enabled }
    }

    override suspend fun setMcpRuntime(enabled: Boolean) {
        context.experimentalPreferences.edit { it[MCP_RUNTIME] = enabled }
    }

    override suspend fun setWellknownIntegrations(enabled: Boolean) {
        context.experimentalPreferences.edit { it[WELLKNOWN_INTEGRATIONS] = enabled }
    }

    override suspend fun setConfigUpdate(enabled: Boolean) {
        context.experimentalPreferences.edit { it[CONFIG_UPDATE] = enabled }
    }

    override suspend fun setSessionInstructions(enabled: Boolean) {
        context.experimentalPreferences.edit { it[SESSION_INSTRUCTIONS] = enabled }
    }

    private companion object {
        val FILE_WRITES = booleanPreferencesKey("fs_write")
        val SESSION_TRANSFER = booleanPreferencesKey("session_transfer")
        val PERSISTENT_PTY = booleanPreferencesKey("persistent_pty")
        val MCP_RUNTIME = booleanPreferencesKey("mcp_runtime")
        val WELLKNOWN_INTEGRATIONS = booleanPreferencesKey("wellknown_integrations")
        val CONFIG_UPDATE = booleanPreferencesKey("config_update")
        val SESSION_INSTRUCTIONS = booleanPreferencesKey("session_instructions")
    }
}
