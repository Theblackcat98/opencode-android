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
    val serverName: String = "",
    val connectionState: ConnectionState = ConnectionState.Disconnected(),
    val searchQuery: String = "",
    val isPaused: Boolean = false,
    val selectedEvent: InspectedEvent? = null,
    val copiedMessage: String? = null,
)

@HiltViewModel
class EventInspectorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val connectionManager: ServerConnectionManager,
) : ViewModel() {

    private val serverIdArg: String? = savedStateHandle["serverId"]

    private val _uiState = MutableStateFlow(EventInspectorUiState())
    val uiState: StateFlow<EventInspectorUiState> = _uiState.asStateFlow()

    private val rawEvents = MutableStateFlow<List<InspectedEvent>>(emptyList())

    val filteredEvents: StateFlow<List<InspectedEvent>> = combine(
        rawEvents,
        _uiState,
    ) { events, state ->
        val query = state.searchQuery.trim().lowercase()
        if (query.isBlank()) {
            events
        } else {
            events.filter { event ->
                event.type.lowercase().contains(query) ||
                    event.rawJson.lowercase().contains(query)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
    )

    init {
        initializeInspector()
    }

    private fun initializeInspector() {
        viewModelScope.launch {
            val serverId = serverIdArg ?: serverRepository.getAllServers().firstOrNull { it.isDefault }?.id
            if (serverId != null) {
                val profile = serverRepository.getServer(serverId)
                _uiState.update { it.copy(serverName = profile?.name ?: "Event Inspector") }

                if (profile != null) {
                    val conn = connectionManager.connectServer(profile, viewModelScope)

                    launch {
                        conn.connectionState.collect { state ->
                            _uiState.update { it.copy(connectionState = state) }
                        }
                    }

                    launch {
                        conn.inspectedEvents.collect { list ->
                            if (!_uiState.value.isPaused) {
                                rawEvents.value = list
                            }
                        }
                    }
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun togglePause() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    fun clearEvents() {
        viewModelScope.launch {
            val serverId = serverIdArg ?: serverRepository.getAllServers().firstOrNull { it.isDefault }?.id
            if (serverId != null) {
                connectionManager.getConnection(serverId)?.client?.clearInspectedEvents()
            }
            rawEvents.value = emptyList()
        }
    }

    fun selectEvent(event: InspectedEvent?) {
        _uiState.update { it.copy(selectedEvent = event) }
    }

    fun setCopiedMessage(message: String?) {
        _uiState.update { it.copy(copiedMessage = message) }
    }
}
