package dev.opencode.android.feature.servers.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.core.data.repository.ServerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val connectionManager: ServerConnectionManager,
) : ViewModel() {

    val servers: StateFlow<List<ServerProfile>> = serverRepository.observeServers()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )

    fun setDefaultServer(serverId: String) {
        viewModelScope.launch { serverRepository.setDefaultServer(serverId) }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            // Stop the stream first, so nothing tries to use a credential that is about to go.
            connectionManager.disconnectServer(serverId)
            serverRepository.removeServer(serverId)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
