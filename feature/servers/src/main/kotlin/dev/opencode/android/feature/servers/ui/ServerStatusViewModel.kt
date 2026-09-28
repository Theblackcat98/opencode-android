package dev.opencode.android.feature.servers.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.repository.AddServerErrorType
import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.data.repository.toAddServerErrorType
import dev.opencode.android.core.model.ServerInfo
import dev.opencode.android.core.network.ConnectionLogEntry
import dev.opencode.android.core.network.ConnectionState
import dev.opencode.android.core.network.ServerValidator
import dev.opencode.android.core.network.ServerValidationResult
import dev.opencode.android.core.network.VersionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerStatusUiState(
    val profile: ServerProfile? = null,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val connectionLogs: List<ConnectionLogEntry> = emptyList(),
    val resyncCount: Long = 0L,
    val serverInfo: ServerInfo? = null,
    val versionStatus: VersionStatus? = null,
    val isTesting: Boolean = false,
    /** Null when the last check passed; the check is also what fills [serverInfo]. */
    val checkError: AddServerErrorType? = null,
    val checkTechnicalDetail: String? = null,
)

/**
 * The per-server status page: identity and reachable URLs from `GET /api/info`, the live connection
 * state, the connection history, and the resync counter (plan §6, Phase 1).
 *
 * "Test connection" is a `GET /api/info` through [ServerValidator], which is also the version gate:
 * a newer release is connected anyway and only labelled as untested.
 */
@HiltViewModel
class ServerStatusViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val serverValidator: ServerValidator,
    private val connectionManager: ServerConnectionManager,
) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"]) {
        "The server status screen needs a serverId route argument"
    }

    private val _uiState = MutableStateFlow(ServerStatusUiState())
    val uiState: StateFlow<ServerStatusUiState> = _uiState.asStateFlow()

    init {
        observeServer()
    }

    private fun observeServer() {
        viewModelScope.launch {
            val profile = serverRepository.getServer(serverId) ?: return@launch
            val connection = connectionManager.getOrCreateConnection(profile)
            connection.start()
            _uiState.update { it.copy(profile = profile) }

            launch {
                connection.connectionState.collect { state ->
                    val refreshed = serverRepository.getServer(serverId)
                    _uiState.update { it.copy(profile = refreshed ?: it.profile, connectionState = state) }
                }
            }
            launch {
                connection.connectionLogs.collect { logs -> _uiState.update { it.copy(connectionLogs = logs) } }
            }
            launch {
                connection.resyncCount.collect { count -> _uiState.update { it.copy(resyncCount = count) } }
            }
            testConnection()
        }
    }

    fun testConnection() {
        val profile = _uiState.value.profile ?: return
        if (_uiState.value.isTesting) return
        _uiState.update { it.copy(isTesting = true, checkError = null, checkTechnicalDetail = null) }

        viewModelScope.launch {
            val credential = serverRepository.getCredential(serverId)
            when (
                val result = serverValidator.validate(
                    baseUrl = profile.baseUrl,
                    credential = credential,
                    trustUserCertificates = profile.trustUserCertificates,
                )
            ) {
                is ServerValidationResult.Success -> _uiState.update {
                    it.copy(
                        isTesting = false,
                        serverInfo = result.serverInfo,
                        versionStatus = result.versionStatus,
                    )
                }

                is ServerValidationResult.Failure -> _uiState.update {
                    it.copy(
                        isTesting = false,
                        checkError = result.errorType.toAddServerErrorType(),
                        checkTechnicalDetail = result.technicalDetail,
                    )
                }
            }
        }
    }

    /** Drops the socket and connects again, which is what a user wants after moving networks. */
    fun reconnectNow() {
        connectionManager.getConnection(serverId)?.reconnectNow()
    }

    fun setDefault() {
        viewModelScope.launch { serverRepository.setDefaultServer(serverId) }
    }

    fun clearLogs() {
        connectionManager.getConnection(serverId)?.client?.clearLogs()
        _uiState.update { it.copy(connectionLogs = emptyList()) }
    }

    fun clearCheckError() = _uiState.update { it.copy(checkError = null, checkTechnicalDetail = null) }
}
