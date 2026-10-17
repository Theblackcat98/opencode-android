package dev.opencode.android.feature.sessions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.preferences.ProjectPreferences
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.model.Project
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Settings tab: which project the checkout-scoped pages act on.
 *
 * The server name is the home's ([HomeViewModel.serverName]); this owns only the project scope, which is a
 * per-server choice kept in [ProjectPreferences] so it survives leaving the tab.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val projectPreferences: ProjectPreferences,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = dataSets.active
        .flatMapLatest { set ->
            if (set == null) {
                flowOf(SettingsUiState())
            } else {
                combine(
                    set.projects.state,
                    projectPreferences.added(set.serverId),
                    projectPreferences.settingsProject(set.serverId),
                    set.insights.pairDevice,
                ) { projects, added, chosen, pairAvailability ->
                    val offered = settingsProjects(projects.value.orEmpty(), added)
                    SettingsUiState(
                        projects = offered,
                        selectedProject = offered.firstOrNull { it.id == chosen } ?: offered.firstOrNull(),
                        isPairingAvailable = pairAvailability !is RouteAvailability.Absent,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

    fun selectProject(projectId: String) {
        val serverId = dataSets.active.value?.serverId ?: return
        viewModelScope.launch { projectPreferences.setSettingsProject(serverId, projectId) }
    }
}

/**
 * The projects Settings offers as a scope: the added ones in their order, or, before any is added, every project
 * the server lists, so a fresh server's accounts and providers are still reachable.
 */
fun settingsProjects(projects: List<Project>, added: List<String>): List<Project> {
    val byId = projects.associateBy { it.id }
    val mine = added.mapNotNull { byId[it] }
    return mine.ifEmpty { projects.sortedByDescending { it.time.updated } }
}

private const val STOP_TIMEOUT_MILLIS = 5_000L
