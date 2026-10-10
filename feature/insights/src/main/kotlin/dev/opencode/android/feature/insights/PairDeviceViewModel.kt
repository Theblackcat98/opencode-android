package dev.opencode.android.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.integrations.ActionFailure
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.model.PairingCode
import dev.opencode.android.core.network.PairingLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A code the server minted, and the link that redeems it.
 *
 * **Held in memory and nowhere else.** A code works once, so a copy that outlives the screen is a credential:
 * it is not logged, not saved to a SavedStateHandle and not written to disk. [toString] hides both fields so a
 * state dump cannot print the code.
 */
data class PairingOffer(val code: PairingCode, val link: String) {
    override fun toString(): String = "PairingOffer(redacted)"
}

data class PairDeviceUiState(
    /** The code on screen, or `null` until the user asks for one. Cleared when the server changes. */
    val offer: PairingOffer? = null,
    val generating: Boolean = false,
    val error: ActionError? = null,
    /** Whether the server answered the route at all. An absent route replaces the button, not an error. */
    val availability: RouteAvailability = RouteAvailability.Unknown,
) {
    val available: Boolean get() = availability !is RouteAvailability.Absent
    val canGenerate: Boolean get() = available && !generating
}

/**
 * Pair another device (matrix B9 and B10; issue #21): mints one code and shows it as a QR code.
 *
 * **Nothing is minted on open.** `POST /api/pair` creates a code on the server, so a screen that minted one
 * just by being looked at would leave a live code behind on every visit. Availability is therefore Unknown
 * until the first press, and an absent route is learned from that press, which is the only probe there is.
 */
@HiltViewModel
class PairDeviceViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(PairDeviceUiState())
    val state: StateFlow<PairDeviceUiState> = _state.asStateFlow()

    init {
        // A code belongs to the server that minted it, so following a different server drops the one on screen,
        // and the new server's route is Unknown until its own first press says otherwise.
        viewModelScope.launch {
            dataSets.active.collectLatest { set ->
                _state.update { it.copy(offer = null, error = null, availability = RouteAvailability.Unknown) }
                set?.insights?.pairDevice?.collect { availability ->
                    _state.update { it.copy(availability = availability) }
                }
            }
        }
    }

    /**
     * Mints a code and shows it. A press while one is in flight does nothing, so a double tap is one code.
     *
     * The link is built from the address the connection was made with, which is the address this phone uses for
     * the server. The other device has to reach that address, and the screen says so. A set and its profile
     * change in two steps, so a press in the instant between them does nothing rather than pairing the wrong server.
     */
    fun generate() {
        val set = dataSets.active.value ?: return
        val profile = dataSets.activeProfile.value?.takeIf { it.id == set.serverId } ?: return
        if (!_state.value.canGenerate) return
        viewModelScope.launch {
            _state.update { it.copy(generating = true, offer = null, error = null) }
            val outcome = set.insights.createPairingCode()
            // The route records its own answer before the call returns, so an absent route is read here and
            // reported as the screen's absent message rather than as an error.
            val absent = set.insights.pairDevice.value is RouteAvailability.Absent
            outcome
                .onSuccess { code ->
                    val link = PairingLink.linkFor(profile.baseUrl, code.code)
                    _state.update { it.copy(generating = false, offer = PairingOffer(code, link)) }
                }
                .onFailure { failure ->
                    val error = (failure as? ActionFailure)?.error ?: failure.toActionError()
                    _state.update { it.copy(generating = false, error = if (absent) null else error) }
                }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }
}
