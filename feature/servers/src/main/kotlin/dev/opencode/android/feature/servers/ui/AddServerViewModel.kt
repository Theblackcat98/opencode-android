package dev.opencode.android.feature.servers.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.repository.AddServerErrorType
import dev.opencode.android.core.data.repository.AddServerOutcome
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.network.PairingLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The three ways to add a server (plan §6, Phase 1). */
enum class AddServerTab { SCAN, PASTE, MANUAL }

data class AddServerUiState(
    val selectedTab: AddServerTab = AddServerTab.SCAN,
    val pairingLinkInput: String = "",
    val manualUrlInput: String = "",
    val manualNameInput: String = "",
    val manualPasswordInput: String = "",
    val trustUserCertificates: Boolean = false,
    val isWorking: Boolean = false,
    val error: AddServerErrorType? = null,
    val errorTechnicalDetail: String? = null,
    val addedServerId: String? = null,
) {
    /** The URL field only shows its hint while the user has not typed anything. */
    val showUrlHint: Boolean get() = manualUrlInput.isEmpty()
}

/**
 * Drives the add-server screen for all three paths.
 *
 * The screen owns no network logic: it hands a parsed [PairingLink] or a typed address to
 * [ServerRepository], which returns an [AddServerOutcome] whose error class already selects the
 * help text. That keeps "nothing is listening" and "wrong password" in one place, and keeps
 * `feature:servers` free of request handling.
 */
@HiltViewModel
class AddServerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val connectionManager: ServerConnectionManager,
) : ViewModel() {

    private val sharedLink: String? = savedStateHandle["initialUrl"]

    /**
     * When set, the screen repairs this server's credential instead of adding a new profile,
     * which is what a rotated password or a revoked token needs.
     */
    private val replaceServerId: String? = savedStateHandle["replaceServerId"]

    val isRePairing: Boolean get() = replaceServerId != null

    private val _uiState = MutableStateFlow(
        AddServerUiState(
            // A shared or deep-linked link is text, so it lands on the paste tab with the payload
            // already filled in rather than asking the user to retype what they just shared. A
            // re-pair is a credential repair, and the paste tab is the only path that can do it.
            selectedTab = if (replaceServerId != null || !sharedLink.isNullOrBlank()) {
                AddServerTab.PASTE
            } else {
                AddServerTab.SCAN
            },
            pairingLinkInput = sharedLink.orEmpty(),
        ),
    )
    val uiState: StateFlow<AddServerUiState> = _uiState.asStateFlow()

    fun selectTab(tab: AddServerTab) = _uiState.update {
        it.copy(selectedTab = tab, error = null, errorTechnicalDetail = null)
    }

    fun updatePairingLinkInput(input: String) = _uiState.update {
        it.copy(pairingLinkInput = input, error = null, errorTechnicalDetail = null)
    }

    fun updateManualUrlInput(input: String) = _uiState.update {
        it.copy(manualUrlInput = input, error = null, errorTechnicalDetail = null)
    }

    fun updateManualNameInput(input: String) = _uiState.update { it.copy(manualNameInput = input) }

    fun updateManualPasswordInput(input: String) = _uiState.update { it.copy(manualPasswordInput = input) }

    fun setTrustUserCertificates(trust: Boolean) = _uiState.update { it.copy(trustUserCertificates = trust) }

    fun clearError() = _uiState.update { it.copy(error = null, errorTechnicalDetail = null) }

    /** The camera could not start, so pasting the link is the way forward. */
    fun onCameraUnavailable() = selectTab(AddServerTab.PASTE)

    /**
     * A scanned, pasted or shared payload. A full pairing link is redeemed; a bare server address
     * cannot be, so it moves to the manual tab with the address filled in and the password left to
     * the user.
     */
    fun onPayloadReceived(payload: String) {
        if (_uiState.value.isWorking) return
        PairingLink.parse(payload)?.let { link ->
            _uiState.update { it.copy(pairingLinkInput = payload) }
            pairWithLink(link)
            return
        }
        when {
            // A bare address cannot be redeemed, and in re-pair mode it must not create a second
            // profile for the same server either.
            payload.toServerBaseUrlOrNull() != null && !isRePairing -> _uiState.update {
                it.copy(
                    selectedTab = AddServerTab.MANUAL,
                    manualUrlInput = payload.trim(),
                    error = AddServerErrorType.PAIRING_CODE_REJECTED,
                )
            }

            else -> _uiState.update { it.copy(error = AddServerErrorType.PAIRING_CODE_REJECTED) }
        }
    }

    fun pairWithPastedLink() {
        val link = PairingLink.parse(_uiState.value.pairingLinkInput)
        if (link == null) {
            _uiState.update { it.copy(error = AddServerErrorType.PAIRING_CODE_REJECTED) }
            return
        }
        pairWithLink(link)
    }

    fun connectManually() {
        val state = _uiState.value
        val url = state.manualUrlInput.trim()
        if (url.toServerBaseUrlOrNull() == null) {
            _uiState.update { it.copy(error = AddServerErrorType.UNREACHABLE) }
            return
        }
        add {
            serverRepository.addManualServer(
                name = state.manualNameInput,
                baseUrl = url,
                password = state.manualPasswordInput.ifBlank { null },
                trustUserCertificates = state.trustUserCertificates,
            )
        }
    }

    private fun pairWithLink(link: PairingLink) = add {
        val existing = replaceServerId
        if (existing != null) {
            serverRepository.rePairServer(id = existing, pairingLink = link)
        } else {
            serverRepository.addPairedServer(
                pairingLink = link,
                trustUserCertificates = _uiState.value.trustUserCertificates,
            )
        }
    }

    /**
     * Runs one add attempt, then connects the new profile. On success the id is published so the
     * screen can leave; on failure the class is published so the dialog can show the matching help.
     */
    private fun add(block: suspend () -> AddServerOutcome) {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true, error = null, errorTechnicalDetail = null) }
        viewModelScope.launch {
            when (val outcome = block()) {
                is AddServerOutcome.Success -> {
                    _uiState.update { it.copy(isWorking = false, addedServerId = outcome.profile.id) }
                    // A re-pair replaces the credential, so the cached client, which was built
                    // around the rejected one, is dropped before reconnecting.
                    if (replaceServerId != null) connectionManager.refreshServer(outcome.profile.id)
                    connectionManager.connectServer(outcome.profile)
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
