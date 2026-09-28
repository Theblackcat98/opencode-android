package dev.opencode.android.feature.servers.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.network.PairingLink
import dev.opencode.android.core.network.ServerValidationResult
import dev.opencode.android.core.network.ServerValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddServerUiState(
    val selectedTab: Int = 0, // 0 = Scan QR, 1 = Paste Link, 2 = Manual
    val pairingLinkInput: String = "",
    val manualUrlInput: String = "http://",
    val manualNameInput: String = "",
    val manualPasswordInput: String = "",
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
    val errorDetail: String? = null,
    val isSuccess: Boolean = false,
    val createdServerId: String? = null,
)

@HiltViewModel
class AddServerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val serverValidator: ServerValidator,
    private val connectionManager: ServerConnectionManager,
) : ViewModel() {

    private val initialUrl: String? = savedStateHandle["initialUrl"]

    private val _uiState = MutableStateFlow(
        AddServerUiState(
            selectedTab = if (initialUrl.isNullOrBlank()) 0 else 1,
            pairingLinkInput = initialUrl ?: "",
        )
    )
    val uiState: StateFlow<AddServerUiState> = _uiState.asStateFlow()

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index, errorMessage = null) }
    }

    fun updatePairingLinkInput(input: String) {
        _uiState.update { it.copy(pairingLinkInput = input, errorMessage = null) }
    }

    fun updateManualUrlInput(input: String) {
        _uiState.update { it.copy(manualUrlInput = input, errorMessage = null) }
    }

    fun updateManualNameInput(input: String) {
        _uiState.update { it.copy(manualNameInput = input) }
    }

    fun updateManualPasswordInput(input: String) {
        _uiState.update { it.copy(manualPasswordInput = input) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, errorDetail = null) }
    }

    fun onQrCodeScanned(qrContent: String) {
        if (_uiState.value.isConnecting) return

        val pairingLink = PairingLink.parse(qrContent)
        if (pairingLink != null) {
            pairWithLink(pairingLink)
        } else {
            // Check if it's a raw URL
            val clean = qrContent.trim()
            if (clean.startsWith("http://") || clean.startsWith("https://")) {
                _uiState.update {
                    it.copy(
                        selectedTab = 2,
                        manualUrlInput = clean,
                        errorMessage = "Scanned server URL without pairing code. Please enter password manually.",
                    )
                }
            } else {
                _uiState.update {
                    it.copy(errorMessage = "Scanned QR code does not contain a valid OpenCode pairing link.")
                }
            }
        }
    }

    fun pairWithPastedLink() {
        val input = _uiState.value.pairingLinkInput.trim()
        val pairingLink = PairingLink.parse(input)
        if (pairingLink == null) {
            _uiState.update {
                it.copy(errorMessage = "Invalid pairing link. Format must be: http(s)://<host>:<port>/auth/connect/<code>")
            }
            return
        }
        pairWithLink(pairingLink)
    }

    private fun pairWithLink(pairingLink: PairingLink) {
        _uiState.update { it.copy(isConnecting = true, errorMessage = null, errorDetail = null) }

        viewModelScope.launch {
            val result = serverRepository.redeemAndAddPairingLink(pairingLink)
            result.fold(
                onSuccess = { profile ->
                    connectionManager.connectServer(profile, viewModelScope)
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            isSuccess = true,
                            createdServerId = profile.id,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            errorMessage = error.message ?: "Pairing redemption failed",
                            errorDetail = error.stackTraceToString(),
                        )
                    }
                },
            )
        }
    }

    fun connectManual() {
        val url = _uiState.value.manualUrlInput.trim()
        val name = _uiState.value.manualNameInput.trim()
        val pass = _uiState.value.manualPasswordInput

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _uiState.update { it.copy(errorMessage = "Server URL must start with http:// or https://") }
            return
        }

        _uiState.update { it.copy(isConnecting = true, errorMessage = null, errorDetail = null) }

        viewModelScope.launch {
            when (val validation = serverValidator.validate(url, pass.ifBlank { null })) {
                is ServerValidationResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            errorMessage = validation.userMessage,
                            errorDetail = validation.technicalDetail,
                        )
                    }
                }
                is ServerValidationResult.Success -> {
                    val info = validation.serverInfo
                    val resolvedName = name.ifBlank {
                        info.urls.firstOrNull() ?: url
                    }

                    val serverId = serverRepository.addServer(
                        name = resolvedName,
                        baseUrl = url,
                        credential = pass.ifBlank { null },
                    )
                    serverRepository.updateHealth(serverId, ServerHealth.CONNECTED)

                    val profile = serverRepository.getServer(serverId)
                    if (profile != null) {
                        connectionManager.connectServer(profile, viewModelScope)
                    }

                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            isSuccess = true,
                            createdServerId = serverId,
                        )
                    }
                }
            }
        }
    }
}
