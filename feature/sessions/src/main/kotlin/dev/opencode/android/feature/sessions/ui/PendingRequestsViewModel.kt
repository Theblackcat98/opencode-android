package dev.opencode.android.feature.sessions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.data.server.ServerDataRegistry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The count of everything the agent is blocked on across a server's sessions (plan §4.3, Home).
 *
 * **It is the same store the session dock and the global inbox read**, so a request answered anywhere
 * disappears from the badge. The home shows it rather than relying on a notification because a
 * permission blocks the agent until it is answered, and a badge is the one signal that survives the
 * app being backgrounded (Phase 4 turns the same number into a notification).
 */
class PendingRequestsViewModel @Inject constructor(
    dataSets: ServerDataRegistry,
) : ViewModel() {

    /** Everything waiting, across every session of the active server. */
    val pending: StateFlow<List<PendingRequest>> = dataSets.active
        .map { set -> set?.requests?.pending?.value ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /** The session titles the global inbox labels its rows with. */
    val sessionTitles: StateFlow<Map<String, String>> = dataSets.active
        .map { set ->
            set?.sessions?.info?.value.orEmpty()
                .mapValues { (_, session) -> session.title.orEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyMap())

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
