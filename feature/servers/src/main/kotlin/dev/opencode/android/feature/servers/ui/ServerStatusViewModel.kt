package dev.opencode.android.feature.servers.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.model.ServerInfo
import dev.opencode.android.core.network.ConnectionLogEntry
import dev.opencode.android.core.network.ServerValidationResult
import dev.opencode.android.core.network.ServerValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerStatusUiState(
    val profile: ServerProfile? = null,
    val serverInfo: ServerInfo? = null,
    val connectionLogs: List<ConnectionLogEntry> = emptyList(),
    val isTesting: Boolean = false,
    val testMessage: String? = null,
)

@HiltViewModel
class ServerStatusViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val serverValidator: ServerValidator,
    private val connectionManager: ServerConnectionManager,
) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])

    private val _uiState = MutableStateFlow(ServerStatusUiState())
    val uiState: StateFlow<ServerStatusUiState> = _uiState.asStateFlow()

    init {
        loadServer()
    }

    fun loadServer() {
        viewModelScope.launch {
            val profile = serverRepository.getServer(serverId)
            _uiState.update { it.copy(profile = profile) }

            if (profile != null) {
                val conn = connectionManager.getOrCreateConnection(profile)
                conn.start(viewModelScope)

                // Observe connection logs
                launch {
                    conn.connectionLogs.collect { logs ->
                        _uiState.update { it.copy(connectionLogs = logs) }
                    }
                }

                // Initial validation check
                testConnection()
            }
        }
    }

    fun testConnection() {
        val currentProfile = _uiState.value.profile ?: return
        _uiState.update { it.copy(isTesting = true, testMessage = null) }

        viewModelScope.launch {
            val credential = serverRepository.getCredential(serverId)
            when (val result = serverValidator.validate(currentProfile.baseUrl, credential)) {
                is ServerValidationResult.Success -> {
                    serverRepository.updateHealth(serverId, ServerHealth.CONNECTED)
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            serverInfo = result.serverInfo,
                            testMessage = "Connected successfully (OpenCode ${result.serverInfo.version})",
                        )
                    }
                }
                is ServerValidationResult.Failure -> {
                    serverRepository.updateHealth(serverId, ServerHealth.ERROR)
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testMessage = result.userMessage,
                        )
                    }
                }
            }
        }
    }

    fun setDefault() {
        viewModelScope.launch {
            serverRepository.setDefaultServer(serverId)
            loadServer()
        }
    }

    fun clearLogs() {
        val currentProfile = _uiState.value.profile ?: return
        val conn = connectionManager.getConnection(currentProfile.id)
        conn?.client?.clearLogs()
        _uiState.update { it.copy(connectionLogs = emptyList()) }
    }
}
