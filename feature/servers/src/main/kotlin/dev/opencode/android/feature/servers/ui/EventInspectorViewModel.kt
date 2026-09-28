package dev.opencode.android.feature.servers.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.network.ConnectionState
import dev.opencode.android.core.network.InspectedEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EventInspectorUiState(
    val serverId: String? = null,
    val serverName: String = "",
    val connectionState: ConnectionState = ConnectionState.Idle,
    val searchQuery: String = "",
    val isPaused: Boolean = false,
    val selectedEvent: InspectedEvent? = null,
    val copiedMessage: String? = null,
)

/**
 * The developer event inspector: a live list of the frames the connection receives, with the raw
 * JSON of the selected one (plan §6, Phase 1).
 *
 * Every later phase debugs through this screen, so it is a first-class feature rather than a
 * leftover. It follows one server: the one named by the route, or the default.
 */
@HiltViewModel
class EventInspectorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val connectionManager: ServerConnectionManager,
) : ViewModel() {

    private val requestedServerId: String? = savedStateHandle["serverId"]

    private val _uiState = MutableStateFlow(EventInspectorUiState())
    val uiState: StateFlow<EventInspectorUiState> = _uiState.asStateFlow()

    private val events = MutableStateFlow<List<InspectedEvent>>(emptyList())

    /**
     * The filtered list. Filtering the type and the raw payload together is what makes this useful
     * for a payload whose type is unknown.
     */
    val filteredEvents: StateFlow<List<InspectedEvent>> = combine(events, _uiState) { all, state ->
        val query = state.searchQuery.trim().lowercase()
        if (query.isEmpty()) {
            all
        } else {
            all.filter { event ->
                event.type.lowercase().contains(query) || event.rawJson.lowercase().contains(query)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList(),
    )

    init {
        followServer()
    }

    private fun followServer() {
        viewModelScope.launch {
            val serverId = requestedServerId
                ?: serverRepository.getAllServers().firstOrNull { it.isDefault }?.id
                ?: return@launch
            val profile = serverRepository.getServer(serverId) ?: return@launch
            val connection = connectionManager.connectServer(profile, viewModelScope)
            _uiState.update { it.copy(serverId = serverId, serverName = profile.name) }

            launch {
                connection.connectionState.collect { state ->
                    _uiState.update { it.copy(connectionState = state) }
                }
            }
            launch {
                connection.inspectedEvents.collect { list ->
                    // Pausing keeps the list frozen without dropping the client's own buffer, so
                    // resuming shows everything that arrived meanwhile.
                    if (!_uiState.value.isPaused) events.value = list
                }
            }
        }
    }

    fun updateSearchQuery(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun togglePause() = _uiState.update { it.copy(isPaused = !it.isPaused) }

    fun clearEvents() {
        viewModelScope.launch {
            _uiState.value.serverId?.let { connectionManager.getConnection(it)?.client?.clearInspectedEvents() }
            events.value = emptyList()
        }
    }

    fun selectEvent(event: InspectedEvent?) = _uiState.update { it.copy(selectedEvent = event) }

    fun setCopiedMessage(message: String?) = _uiState.update { it.copy(copiedMessage = message) }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
