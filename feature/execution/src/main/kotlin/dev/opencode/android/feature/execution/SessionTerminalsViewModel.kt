package dev.opencode.android.feature.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.execution.PersistentPtyAvailability
import dev.opencode.android.core.data.execution.SessionTerminalState
import dev.opencode.android.core.data.preferences.ExperimentalPreferences
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.model.PtySize
import dev.opencode.android.core.model.SessionTerminalRead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The session's terminal pane (features doc §32; plan §6, "Persistent PTYs"). */
data class SessionTerminalsUiState(
    val sessionID: String? = null,
    val terminals: List<TerminalRow> = emptyList(),
    val screen: SessionTerminalRead.Screen? = null,
    /** The switch's own value, which the pane reads rather than takes. */
    val allowedBySetting: Boolean = false,
    /** What the client believes about the persistent-PTY host. */
    val availability: PersistentPtyAvailability = PersistentPtyAvailability.Unknown,
    val probing: Boolean = false,
    val error: String? = null,
) {
    /**
     * Whether the pane is shown at all.
     *
     * Two gates and both must say yes: the switch says the app may call the eleven routes, and the
     * probe says the server has them. Showing the pane with the switch off would offer a feature this
     * installation was told not to use; showing it after a `404` would offer one that does not exist.
     */
    val usable: Boolean get() = allowedBySetting && availability.usable

    /** The word the header shows about the host, which is a fact about the server. */
    val availabilityKey: String
        get() = when (availability) {
            is PersistentPtyAvailability.Unknown -> "unknown"
            is PersistentPtyAvailability.Present -> "present"
            is PersistentPtyAvailability.HostDown -> "host-down"
            is PersistentPtyAvailability.Absent -> "absent"
        }

    val latest: TerminalRow? get() = terminals.lastOrNull()
}

/** One persistent terminal, as the picker shows it. */
data class TerminalRow(
    val id: String,
    val title: String,
    val cwd: String,
    val isRunning: Boolean,
    val exitCode: Int?,
    val foregroundProcess: String?,
    val cols: Int,
    val rows: Int,
)

/**
 * A session's persistent terminals, behind the experimental switch and capability detection.
 *
 * **Nothing is called until both gates agree**, and the gates are the store's business rather than the
 * screen's: [SessionTerminalStore.usable] folds the switch and the probe into one answer so two
 * screens asking cannot disagree.
 *
 * **The three availability states are kept apart on purpose.** `Unknown` (no call yet), `Absent` (a
 * `404`: this server has never heard of the routes), `HostDown` (a `503`: the routes are there and the
 * host is not running) and `Present`. Collapsing the last two would either offer a picker whose every
 * call fails, or hide a feature that an `opencode service start` would bring back — and the user
 * cannot tell which of those happened from a silent pane.
 *
 * **The screen text is a fallback, not the terminal.** `terminal/read` returns the most recently
 * controlled terminal's *rendered* screen, which is a picture of what it looked like; the live stream
 * is the WebSocket, and this pane is what there is when the route exists and the socket does not.
 */
class SessionTerminalsViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val preferences: ExperimentalPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionTerminalsUiState())
    val state: StateFlow<SessionTerminalsUiState> = _state.asStateFlow()

    /** Binds the pane to a session and, when the gates allow it, reads the terminals. */
    fun open(sessionID: String) {
        _state.value = _state.value.copy(sessionID = sessionID)
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                _state.value = _state.value.copy(allowedBySetting = settings.persistentPty)
                if (settings.persistentPty) probe(sessionID)
            }
        }
    }

    /** The capability probe, which is the list route: the cheapest call that proves all eleven. */
    private fun probe(sessionID: String) {
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(probing = true)
        viewModelScope.launch {
            set.execution.terminals.list(sessionID)
            set.execution.terminals.state.collect { next -> fold(next) }
        }
    }

    private fun fold(next: SessionTerminalState) {
        _state.value = _state.value.copy(
            terminals = next.terminals.map { terminal ->
                TerminalRow(
                    id = terminal.id,
                    title = terminal.title,
                    cwd = terminal.cwd,
                    isRunning = terminal.status.value == "running",
                    exitCode = terminal.exitCode,
                    foregroundProcess = terminal.foregroundProcess,
                    cols = terminal.size.cols,
                    rows = terminal.size.rows,
                )
            },
            availability = dataSets.active.value?.execution?.terminals?.availability?.value
                ?: PersistentPtyAvailability.Unknown,
            probing = false,
        )
        dataSets.active.value?.let { set ->
            viewModelScope.launch {
                set.execution.terminals.availability.collect { availability ->
                    _state.value = _state.value.copy(availability = availability)
                }
            }
        }
    }

    /** `experimental.session.terminal.create`. */
    fun create(title: String, args: List<String> = listOf("-l"), size: PtySize? = null) {
        val set = dataSets.active.value ?: return
        val sessionID = _state.value.sessionID ?: return
        if (!_state.value.usable) {
            _state.value = _state.value.copy(error = "experimental-routes-off")
            return
        }
        viewModelScope.launch {
            val result = set.execution.terminals.create(
                sessionID = sessionID,
                command = null,
                args = args,
                title = title,
                size = size,
            )
            val error = result.exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(error = error?.message)
            result.getOrNull()?.let { read() }
        }
    }

    /** `experimental.persistent-pty.update`: the resize. */
    fun resize(id: String, cols: Int, rows: Int) {
        val set = dataSets.active.value ?: return
        viewModelScope.launch {
            val error = set.execution.terminals.resize(id, PtySize(rows, cols)).exceptionOrNull()?.toActionError()
            if (error != null) _state.value = _state.value.copy(error = error.message)
        }
    }

    /** `experimental.persistent-pty.remove`. */
    fun remove(id: String) {
        val set = dataSets.active.value ?: return
        viewModelScope.launch {
            val error = set.execution.terminals.remove(id).exceptionOrNull()?.toActionError()
            if (error != null) _state.value = _state.value.copy(error = error.message)
        }
    }

    /** `experimental.session.terminal.read`: the rendered screen of the terminal being watched. */
    fun read() {
        val set = dataSets.active.value ?: return
        val sessionID = _state.value.sessionID ?: return
        viewModelScope.launch {
            val result = set.execution.terminals.read(sessionID)
            val error = result.exceptionOrNull()?.toActionError()
            val screen = (result.getOrNull() as? SessionTerminalRead.Screen)
            _state.value = _state.value.copy(screen = screen, error = error?.message)
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}
