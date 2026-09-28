package dev.opencode.android.feature.servers.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.repository.AddServerErrorType
import dev.opencode.android.core.data.repository.AddServerOutcome
import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.core.data.repository.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditServerUiState(
    val profile: ServerProfile? = null,
    val name: String = "",
    val url: String = "",
    /** Never pre-filled: the stored credential is not read back into the field. */
    val password: String = "",
    val isDefault: Boolean = false,
    val trustUserCertificates: Boolean = false,
    val isWorking: Boolean = false,
    val error: AddServerErrorType? = null,
    val errorTechnicalDetail: String? = null,
    val saved: Boolean = false,
) {
    val showUrlHint: Boolean get() = url.isEmpty()
}

/**
 * Edits one saved server: name, address, credential, default flag and user-CA trust.
 *
 * The new values are validated before anything is written, so a typo cannot leave a profile that
 * points nowhere. An empty password field means "keep what is stored", which is why the field is
 * never pre-filled.
 */
@HiltViewModel
class EditServerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val connectionManager: ServerConnectionManager,
) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"]) {
        "The edit screen needs a serverId route argument"
    }

    private val _uiState = MutableStateFlow(EditServerUiState())
    val uiState: StateFlow<EditServerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = serverRepository.getServer(serverId) ?: return@launch
            _uiState.update {
                it.copy(
                    profile = profile,
                    name = profile.name,
                    url = profile.baseUrl,
                    isDefault = profile.isDefault,
                    trustUserCertificates = profile.trustUserCertificates,
                )
            }
        }
    }

    fun updateName(value: String) = _uiState.update { it.copy(name = value) }
    fun updateUrl(value: String) = _uiState.update {
        it.copy(url = value, error = null, errorTechnicalDetail = null)
    }

    fun updatePassword(value: String) = _uiState.update { it.copy(password = value) }
    fun setDefault(value: Boolean) = _uiState.update { it.copy(isDefault = value) }
    fun setTrustUserCertificates(value: Boolean) = _uiState.update { it.copy(trustUserCertificates = value) }
    fun clearError() = _uiState.update { it.copy(error = null, errorTechnicalDetail = null) }

    fun save() {
        val state = _uiState.value
        val url = state.url.trim()
        if (url.toServerBaseUrlOrNull() == null) {
            _uiState.update { it.copy(error = AddServerErrorType.UNREACHABLE) }
            return
        }
        if (state.isWorking) return

        _uiState.update { it.copy(isWorking = true, error = null, errorTechnicalDetail = null) }
        viewModelScope.launch {
            val outcome = serverRepository.editAndValidateServer(
                id = serverId,
                name = state.name,
                baseUrl = url,
                password = state.password.ifBlank { null },
                isDefault = state.isDefault,
                trustUserCertificates = state.trustUserCertificates,
            )
            when (outcome) {
                is AddServerOutcome.Success -> {
                    _uiState.update { it.copy(isWorking = false, saved = true) }
                    // The address or the TLS setting may have changed, so the cached client, which
                    // was built for the old ones, has to go.
                    connectionManager.refreshServer(serverId)
                    connectionManager.connectServer(outcome.profile, viewModelScope)
                }

                is AddServerOutcome.Failure -> _uiState.update {
                    it.copy(
                        isWorking = false,
                        error = outcome.errorType,
                        errorTechnicalDetail = outcome.technicalDetail,
                    )
                }
            }
        }
    }
}
