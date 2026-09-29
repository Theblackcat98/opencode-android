package dev.opencode.android.feature.servers.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.ExperimentalRoute
import dev.opencode.android.core.data.execution.PersistentPtyAvailability
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.preferences.ExperimentalPreferences
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.model.SessionTerminalHandoff
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The one dangerous action on the server status page, and the host's answer to it. */
data class HostLifecycleState(
    /**
     * Whether the switch allows the eleven experimental routes.
     *
     * **Two gates before anything is offered.** The switch is this installation's own decision and the
     * capability answer is the server's; a page that offered `shutdown` because the switch was on
     * would send a request to a server that has never heard of the route, and read the resulting `404`
     * as "the host is down".
     */
    val allowedBySetting: Boolean = false,
    val availability: RouteAvailability = RouteAvailability.Unknown,
    /** The shutdown the user asked for and has not confirmed. */
    val shutdownArmed: Boolean = false,
    val busy: Boolean = false,
    val handoff: SessionTerminalHandoff? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    /**
     * Whether the host actions may be offered at all.
     *
     * `HostDown` counts as available on purpose: "the host is not running" is a service the user can
     * start, and hiding the row would hide the only thing that tells them so.
     */
    val usable: Boolean
        get() = allowedBySetting &&
            (availability !is RouteAvailability.Absent)
}

/**
 * The two host-lifecycle routes, on the server status page and nowhere else (plan §6, "Persistent
 * PTYs": *"The host-lifecycle routes (`shutdown`, `handoff`) appear only as admin actions on the server
 * status page"*).
 *
 * **`shutdown` ends the terminals of every session on the server.** It is not a per-session action and
 * it is not a per-project one, so it belongs to the one page that is about the server rather than about
 * anything the user owns. Nothing else in the app calls it: [dev.opencode.android.core.data.execution.
 * SessionTerminalStore.shutdownHost] is deliberately unreferenced from any session screen.
 *
 * **A confirmation is required before it is sent, and it says what it stops.** A user who taps this by
 * accident loses every persistent terminal on the server at once, including other people's `vim`s, and
 * there is no way to get them back from the phone.
 */
class HostLifecycleViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val preferences: ExperimentalPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(HostLifecycleState())
    val state: StateFlow<HostLifecycleState> = _state.asStateFlow()

    private var settingsJob: Job? = null

    /** Binds the page and reads the switch; the capability answer is already on the store. */
    fun open() {
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            preferences.settings.collect { settings ->
                _state.value = _state.value.copy(allowedBySetting = settings.persistentPty)
                pushSwitch(settings.persistentPty)
            }
        }
    }

    private fun pushSwitch(enabled: Boolean) {
        val terminals = dataSets.active.value?.execution?.terminals ?: return
        terminals.allow(enabled)
        _state.value = _state.value.copy(
            availability = terminals.availability.value.toRouteAvailability(),
        )
        viewModelScope.launch {
            terminals.availability.collect { availability ->
                _state.value = _state.value.copy(availability = availability.toRouteAvailability())
            }
        }
    }

    /** Arms the confirmation. No request is sent until [confirmShutdown]. */
    fun requestShutdown() {
        if (!_state.value.usable) return
        _state.value = _state.value.copy(shutdownArmed = true, error = null, notice = null)
    }

    fun cancelShutdown() {
        _state.value = _state.value.copy(shutdownArmed = false)
    }

    /**
     * `experimental.persistent-pty.shutdown`, after the confirmation.
     *
     * The terminals are re-read afterwards so the page stops offering a host that is gone: the store
     * keeps its own list, and a `shutdown` does not remove the terminals from it — only a
     * `persistent-pty.removed` event does — so without this the next screen would show processes that
     * no longer exist.
     */
    fun confirmShutdown() {
        val terminals = dataSets.active.value?.execution?.terminals ?: return
        _state.value = _state.value.copy(shutdownArmed = false, busy = true, error = null)
        viewModelScope.launch {
            val result = terminals.shutdownHost()
            val error = result.exceptionOrNull()?.toActionError()?.message
            if (error == null) {
                _state.value = _state.value.copy(busy = false, handoff = null, notice = HOST_STOPPED)
            } else {
                _state.value = _state.value.copy(busy = false, error = error)
            }
        }
    }

    /**
     * `experimental.persistent-pty.handoff`: asks the server to hand its persistent terminals to another
     * instance and answers with the ticket that instance will accept.
     *
     * **Non-destructive, so it needs no confirmation** — it is the safe half of the pair, and a prompt
     * in front of it would teach the user to dismiss prompts. The instance id is shown because it is
     * the only way to tell which host answered.
     */
    fun handoff() {
        val terminals = dataSets.active.value?.execution?.terminals ?: return
        if (!_state.value.usable) return
        _state.value = _state.value.copy(busy = true, error = null, notice = null)
        viewModelScope.launch {
            val result = terminals.handoffHost()
            val error = result.exceptionOrNull()?.toActionError()?.message
            _state.value = _state.value.copy(
                busy = false,
                error = error,
                handoff = result.getOrNull(),
                notice = if (error == null && result.getOrNull() != null) HANDED_OFF else null,
            )
        }
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    override fun onCleared() {
        settingsJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val HOST_STOPPED = "host-stopped"
        const val HANDED_OFF = "handed-off"
    }
}

/**
 * The store's availability as a route-level one.
 *
 * The two vocabularies answer the same question at different scopes — the store's is about the
 * persistent-PTY host and this page's is about the route family — and mapping rather than re-probing
 * is what keeps a second `404` from being recorded as a second opinion.
 */
private fun PersistentPtyAvailability.toRouteAvailability(): RouteAvailability = when (this) {
    is PersistentPtyAvailability.Present -> RouteAvailability.Present

    // `HostDown` maps to `Present` because it *is* present: the routes answered and the service behind
    // them is stopped. Mapping it to `Absent` would hide the only page that can say "start the host",
    // and mapping it to `Unknown` would offer the action without ever having proved the route exists.
    is PersistentPtyAvailability.HostDown -> RouteAvailability.Present
    is PersistentPtyAvailability.Absent -> RouteAvailability.Absent(httpStatus)
    is PersistentPtyAvailability.Unknown -> RouteAvailability.Unknown
}

/** The route the two host actions belong to, named once. */
internal val HOST_LIFECYCLE_ROUTE: ExperimentalRoute = ExperimentalRoute.PERSISTENT_PTY
