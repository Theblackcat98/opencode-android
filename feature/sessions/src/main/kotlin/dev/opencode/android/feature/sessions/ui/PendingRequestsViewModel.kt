package dev.opencode.android.feature.sessions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.ServerDataSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PendingRequestsViewModel(
    active: StateFlow<ServerDataSet?>,
) : ViewModel() {
    /** Hilt's constructor: what this view model reads from the registry is which read model is active. */
    @Inject
    constructor(dataSets: ServerDataRegistry) : this(dataSets.active)

    // Both follow their store. Reading `.value` inside a `map` on `active` froze the answer at whatever the
    // store held when the server became active: a permission that arrived while the badge or the inbox was
    // on screen never showed, and neither did a session that was created or renamed.

    /** Everything waiting, across every session of the active server. */
    val pending: StateFlow<List<PendingRequest>> = active
        .flatMapLatest { set -> set?.requests?.pending ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /** The session titles the global inbox labels its rows with. */
    val sessionTitles: StateFlow<Map<String, String>> = active
        .flatMapLatest { set -> set?.sessions?.info ?: flowOf(emptyMap()) }
        .map { sessions -> sessions.mapValues { (_, session) -> session.title.orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyMap())

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
