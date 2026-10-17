package dev.opencode.android.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.projectPreferences: DataStore<Preferences> by preferencesDataStore(name = "project_preferences")

/**
 * Which of a server's projects the user put on their home screen, and which session they had open last.
 *
 * **Both are the client's, not the server's.** The server registers a project for every directory it is
 * asked about (a scratch directory, a home directory, a path typed once), so its project list is a history,
 * not a choice. Which projects matter to this user, on this phone, is knowledge the server does not have;
 * the same holds for which session was last on screen. Everything is stored per server, because a project
 * id on one server means nothing on another.
 *
 * Project ids are stored as the server spells them, newline-separated, in the order they were added.
 */
interface ProjectPreferences {
    /** The added projects for a server, in the order they were added. */
    fun added(serverId: String): Flow<List<String>>

    /** Adds a project. Adding one that is already there keeps its place. */
    suspend fun add(serverId: String, projectId: String)

    /** Removes a project from the home screen. The server's project is untouched. */
    suspend fun remove(serverId: String, projectId: String)

    /** The session the user last had on screen on a server, or null before the first one. */
    fun lastSession(serverId: String): Flow<String?>

    suspend fun setLastSession(serverId: String, sessionId: String)

    /** The project Settings scopes its directory-bound pages to, or null for the first added one. */
    fun settingsProject(serverId: String): Flow<String?>

    suspend fun setSettingsProject(serverId: String, projectId: String)

    /** Whether follow-desktop is on for a server, or false by default. */
    fun followDesktop(serverId: String): Flow<Boolean>

    suspend fun setFollowDesktop(serverId: String, enabled: Boolean)
}

/** The DataStore-backed store the app uses. */
@Singleton
class DataStoreProjectPreferences @Inject constructor(
    private val context: Context,
) : ProjectPreferences {

    override fun added(serverId: String): Flow<List<String>> =
        context.projectPreferences.data.map { it[addedKey(serverId)].toIds() }

    override suspend fun add(serverId: String, projectId: String) {
        val key = addedKey(serverId)
        context.projectPreferences.edit { preferences ->
            val current = preferences[key].toIds()
            if (projectId !in current) preferences[key] = (current + projectId).joinToString(SEPARATOR)
        }
    }

    override suspend fun remove(serverId: String, projectId: String) {
        val key = addedKey(serverId)
        context.projectPreferences.edit { preferences ->
            preferences[key] = (preferences[key].toIds() - projectId).joinToString(SEPARATOR)
        }
    }

    override fun lastSession(serverId: String): Flow<String?> =
        context.projectPreferences.data.map { it[lastSessionKey(serverId)]?.takeIf(String::isNotBlank) }

    override suspend fun setLastSession(serverId: String, sessionId: String) {
        context.projectPreferences.edit { it[lastSessionKey(serverId)] = sessionId }
    }

    override fun settingsProject(serverId: String): Flow<String?> =
        context.projectPreferences.data.map { it[settingsProjectKey(serverId)]?.takeIf(String::isNotBlank) }

    override suspend fun setSettingsProject(serverId: String, projectId: String) {
        context.projectPreferences.edit { it[settingsProjectKey(serverId)] = projectId }
    }

    override fun followDesktop(serverId: String): Flow<Boolean> =
        context.projectPreferences.data.map { it[followDesktopKey(serverId)] ?: false }

    override suspend fun setFollowDesktop(serverId: String, enabled: Boolean) {
        context.projectPreferences.edit { it[followDesktopKey(serverId)] = enabled }
    }

    private fun String?.toIds(): List<String> = this?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    private fun addedKey(serverId: String) = stringPreferencesKey("added.$serverId")

    private fun lastSessionKey(serverId: String) = stringPreferencesKey("lastSession.$serverId")

    private fun settingsProjectKey(serverId: String) = stringPreferencesKey("settingsProject.$serverId")

    private fun followDesktopKey(serverId: String) = booleanPreferencesKey("followDesktop.$serverId")

    private companion object {
        /** Not a character a project id contains. */
        const val SEPARATOR = "\n"
    }
}
