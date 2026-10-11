package dev.opencode.android.feature.sessions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.insights.SessionLogEntry
import dev.opencode.android.core.data.integrations.ActionFailure
import dev.opencode.android.core.data.server.ServerDataRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the event history shows: the entries read so far, and whether the replay has finished.
 *
 * [replaying] and [synced] are separate on purpose. A replay that ends with an error is not synced, and
 * the screen must not say "caught up" over a log that stopped early.
 */
data class SessionLogUiState(
    val entries: List<SessionLogEntry> = emptyList(),
    val replaying: Boolean = false,
    val synced: Boolean = false,
    val error: ActionError? = null,
)

/**
 * The session event history (matrix H8; issue #18).
 *
 * **Entries are shown as the server wrote them.** The log's own shape is not in the spec, so the viewer keeps
 * each entry's fields and shows what is there, rather than naming keys it cannot promise exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionLogViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionLogUiState())
    val state: StateFlow<SessionLogUiState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Whether the menu offers the history. Only an absent route hides it; an unknown one is offered, because
     * the first read is what finds out.
     */
    val available: StateFlow<Boolean> = dataSets.active
        .flatMapLatest { set -> set?.insights?.log ?: flowOf(RouteAvailability.Unknown) }
        .map { it.callable }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), true)

    /** Starts a replay of [sessionID]'s log, replacing any earlier one. */
    fun open(sessionID: String) {
        val set = dataSets.active.value ?: return
        job?.cancel()
        _state.value = SessionLogUiState(replaying = true)
        job = viewModelScope.launch {
            try {
                val accumulator = mutableListOf<SessionLogEntry>()
                set.insights.readSessionLog(sessionID).collect { entry ->
                    accumulator.add(entry)
                    _state.value = _state.value.copy(entries = accumulator.toList())
                }
                // The client ends the flow when it reaches log.synced and does not emit that frame, so a
                // flow that completes without an error is a replay that caught up.
                _state.value = _state.value.copy(synced = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    error = (error as? ActionFailure)?.error ?: error.toActionError(),
                )
            } finally {
                _state.value = _state.value.copy(replaying = false)
            }
        }
    }

    /** Closes the history and ends the replay, if it is still running. */
    fun close() {
        job?.cancel()
        job = null
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
