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
        )
    }

    override suspend fun setFileWrites(enabled: Boolean) {
        context.experimentalPreferences.edit { it[FILE_WRITES] = enabled }
    }

    override suspend fun setSessionTransfer(enabled: Boolean) {
        context.experimentalPreferences.edit { it[SESSION_TRANSFER] = enabled }
    }

    private companion object {
        val FILE_WRITES = booleanPreferencesKey("fs_write")
        val SESSION_TRANSFER = booleanPreferencesKey("session_transfer")
    }
}
