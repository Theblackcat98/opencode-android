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
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    fun setDefaultServer(serverId: String) {
        viewModelScope.launch {
            serverRepository.setDefaultServer(serverId)
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            connectionManager.disconnectServer(serverId)
            serverRepository.removeServer(serverId)
        }
    }

    fun connectServer(profile: ServerProfile) {
        connectionManager.connectServer(profile, viewModelScope)
    }
}
