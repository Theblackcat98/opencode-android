package dev.opencode.android.feature.sessions.ui

import dev.opencode.android.core.data.preferences.ProjectPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The home screen's projects, held in memory, so a test can read back what a created session added. */
class FakeProjectPreferences : ProjectPreferences {
    val addedIds = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val last = MutableStateFlow<Map<String, String>>(emptyMap())
    private val settings = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun added(serverId: String): Flow<List<String>> = addedIds.map { it[serverId].orEmpty() }

    override suspend fun add(serverId: String, projectId: String) {
        val current = addedIds.value[serverId].orEmpty()
        if (projectId !in current) addedIds.value = addedIds.value + (serverId to current + projectId)
    }

    override suspend fun remove(serverId: String, projectId: String) {
        addedIds.value = addedIds.value + (serverId to addedIds.value[serverId].orEmpty() - projectId)
    }

    override fun lastSession(serverId: String): Flow<String?> = last.map { it[serverId] }

    override suspend fun setLastSession(serverId: String, sessionId: String) {
        last.value = last.value + (serverId to sessionId)
    }

    override fun settingsProject(serverId: String): Flow<String?> = settings.map { it[serverId] }

    override suspend fun setSettingsProject(serverId: String, projectId: String) {
        settings.value = settings.value + (serverId to projectId)
    }

    val followDesktopState = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    override fun followDesktop(serverId: String): Flow<Boolean> = followDesktopState.map { it[serverId] ?: false }

    override suspend fun setFollowDesktop(serverId: String, enabled: Boolean) {
        followDesktopState.value = followDesktopState.value + (serverId to enabled)
    }
}
