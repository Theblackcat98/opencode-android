package dev.opencode.android.core.data.execution

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.CapabilityPolicy
import dev.opencode.android.core.data.capability.ExperimentalRoute
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.model.PtySize
import dev.opencode.android.core.model.PersistentPtyScreen
import dev.opencode.android.core.model.PtyTicketToken
import dev.opencode.android.core.model.SessionTerminalCreateRequest
import dev.opencode.android.core.model.SessionTerminalHandoff
import dev.opencode.android.core.model.SessionTerminalRead
import dev.opencode.android.core.model.SessionTerminalSnapshot
import dev.opencode.android.core.model.SessionTerminalUpdateRequest
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.PersistentPtyAdded
import dev.opencode.android.core.model.event.PersistentPtyInfo
import dev.opencode.android.core.model.event.PersistentPtyRemoved
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the client believes about the experimental persistent-PTY host.
 *
 * **Three answers, because a `503` is neither "present" nor "absent".** The eleven
 * `api/experimental/…/persistent-pty/…` routes answer `503` when the host is not running, which is a
 * different situation from a server that has never heard of them: the feature is there and its
 * service is down. Collapsing that into "present" would offer a terminal picker that always fails;
 * collapsing it into "absent" would hide a feature that a `service start` would bring back. So the
 * three states are kept, and the screen says which one it is.
 */
sealed interface PersistentPtyAvailability {
    /** No call has been made from this process yet. */
    data object Unknown : PersistentPtyAvailability

    /** The routes answered: the host is running. */
    data object Present : PersistentPtyAvailability

    /** A call came back `404` or `405`: this server has no such routes. */
    data class Absent(val httpStatus: Int) : PersistentPtyAvailability

    /** The routes are there and the host is not running. */
    data object HostDown : PersistentPtyAvailability

    /** Whether the terminals may be offered at all. */
    val usable: Boolean get() = this is Present || this is HostDown
}

/**
 * A session's persistent terminals, and the eleven experimental routes behind them (features doc
 * §32; plan §6, "Persistent PTYs").
 *
 * **Nothing here is called until both the switch and the probe agree**, exactly as the review's file
 * writes are: the switch says the app may call the routes, and the first `404` says whether it can.
 * [allow] carries the switch's value and [list] records what the answer proved, so a caller never
 * has to know about either.
 *
 * **A terminal survives this client.** A persistent terminal belongs to a session and outlives the
 * attachment, so the store is keyed by session and re-read on entry rather than assumed empty; a
 * store that forgot its contents on the way out would lose a `vim` the user left open on the desktop.
 */
class SessionTerminalStore(
    private val api: ServerApi,
) {
    private val _state = MutableStateFlow(SessionTerminalState())
    val state: StateFlow<SessionTerminalState> = _state.asStateFlow()

    private val _availability = MutableStateFlow<PersistentPtyAvailability>(PersistentPtyAvailability.Unknown)

    /** What the client believes about the persistent-PTY host. */
    val availability: StateFlow<PersistentPtyAvailability> = _availability.asStateFlow()

    private val _error = MutableStateFlow<ActionError?>(null)
    val error: StateFlow<ActionError?> = _error.asStateFlow()

    /** The switch's value, which the store needs for every call. */
    @Volatile
    private var allowedBySetting: Boolean = false

    fun allow(enabled: Boolean) {
        allowedBySetting = enabled
    }

    /**
     * Whether the terminals may be offered: the switch is on and no call has found them absent.
     *
     * The switch is pushed in here rather than left to a caller, because the store is the one that
     * must refuse the calls and it is the one that has to know the value: two screens asking
     * separately is how one of them ends up offering a feature the other has already found missing.
     */
    fun usable(allowedBySetting: Boolean): Boolean {
        allow(allowedBySetting)
        return allowedBySetting && _availability.value.usable
    }

    /**
     * `experimental.session.terminal.list`, which is also the capability probe.
     *
     * The list is the cheapest call that proves the eleven routes exist, so it is the one the probe
     * makes; a `404` marks the whole feature absent for this process, and the panel stops offering
     * it rather than calling again on every open.
     */
    suspend fun list(sessionID: String): Result<List<PersistentPtyInfo>> {
        if (!allowedBySetting) return refused()
        val result = call { api.listSessionTerminals(sessionID).data }
        record(sessionID, result)
        result.getOrNull()?.let { terminals ->
            _state.value = _state.value.copy(sessionID = sessionID, terminals = terminals)
        }
        return result
    }

    /** `experimental.session.terminal.create`. */
    suspend fun create(
        sessionID: String,
        command: String?,
        args: List<String>,
        title: String,
        size: PtySize?,
    ): Result<PersistentPtyInfo> {
        if (!allowedBySetting) return refused()
        val result = call {
            api.createSessionTerminal(
                sessionID,
                SessionTerminalCreateRequest(
                    command = command,
                    args = args,
                    title = title,
                    env = emptyMap(),
                    size = size,
                ),
            ).data
        }
        record(sessionID, result)
        result.getOrNull()?.let { terminal ->
            _state.value = _state.value.copy(terminals = _state.value.terminals + terminal)
        }
        return result
    }

    /** `experimental.persistent-pty.update`: the resize, and the claim with an attachment id. */
    suspend fun resize(id: String, size: PtySize, attachmentID: String? = null): Result<PersistentPtyInfo> {
        if (!allowedBySetting) return refused()
        return call { api.updateSessionTerminal(id, SessionTerminalUpdateRequest(attachmentID, size)).data }
    }

    /** `experimental.persistent-pty.remove`. */
    suspend fun remove(id: String): Result<Unit> {
        if (!allowedBySetting) return refused()
        val result = call { api.removeSessionTerminal(id) }
        result.getOrNull()?.let { _state.value = _state.value.copy(terminals = _state.value.terminals.filterNot { it.id == id }) }
        return result
    }

    /** `experimental.session.terminal.read`: the most recently controlled terminal's screen. */
    suspend fun read(sessionID: String, lines: Int? = null): Result<SessionTerminalRead> {
        if (!allowedBySetting) return refused()
        val result = call {
            api.readSessionTerminal(sessionID, lines?.toString()).data?.toRead() ?: SessionTerminalRead.None
        }
        record(sessionID, result)
        return result
    }

    /** `experimental.persistent-pty.snapshot`: the screen, the text and the resume checkpoint. */
    suspend fun snapshot(id: String): Result<SessionTerminalSnapshot> {
        if (!allowedBySetting) return refused()
        val result = call { api.getSessionTerminalSnapshot(id).data }
        record(_state.value.sessionID, result)
        return result
    }

    /** `experimental.persistent-pty.connect-token`, for a WebView that cannot send Basic auth. */
    suspend fun ticket(id: String): Result<PtyTicketToken> {
        if (!allowedBySetting) return refused()
        return call { api.createSessionTerminalTicket(id).data }
    }

    /**
     * `experimental.persistent-pty.shutdown` and `…/handoff`.
     *
     * **Not reachable from a session.** These end the terminals of every session on the server, so
     * they are admin actions and live on the server status page (plan §6). They are here because the
     * API is one interface, and the fact that a method exists is not the same as a screen offering
     * it.
     */
    suspend fun shutdownHost(): Result<Unit> {
        if (!allowedBySetting) return refused()
        return call { api.shutdownPersistentPtyHost() }
    }

    suspend fun handoffHost(): Result<SessionTerminalHandoff?> {
        if (!allowedBySetting) return refused()
        return call { api.handoffPersistentPtyHost().handoff }
    }

    /**
     * Applies one event.
     *
     * `persistent-pty.added` and `persistent-pty.removed` name a session, so a session's pane updates
     * without a round trip and a pane for another session is untouched.
     */
    fun apply(event: Event): Boolean {
        val payload = event.payload
        return when (payload) {
            is PersistentPtyAdded -> {
                if (payload.sessionID != _state.value.sessionID) return false
                _state.value = _state.value.copy(
                    terminals = _state.value.terminals.filterNot { it.id == payload.terminal.id } +
                        payload.terminal,
                )
                true
            }

            is PersistentPtyRemoved -> {
                if (payload.sessionID != _state.value.sessionID) return false
                _state.value = _state.value.copy(
                    terminals = _state.value.terminals.filterNot { it.id == payload.ptyID },
                )
                true
            }

            else -> false
        }
    }

    fun forget(sessionID: String) {
        if (_state.value.sessionID == sessionID) _state.value = SessionTerminalState()
    }

    fun dismissError() {
        _error.value = null
    }

    /**
     * Records what a call proved.
     *
     * The route answers `503` when the host is not running, which `CapabilityPolicy` does not treat
     * as an absence — and it is right not to: a `503` proves the route is there. This is where the
     * distinction is kept, so a server whose host is down shows "start the host" rather than hiding
     * the feature or claiming it works.
     */
    private fun record(sessionID: String?, result: Result<*>) {
        if (sessionID != _state.value.sessionID) {
            _state.value = _state.value.copy(sessionID = sessionID)
        }
        val error = result.exceptionOrNull()
        if (error == null) {
            _availability.value = PersistentPtyAvailability.Present
            return
        }
        // [call] wraps the classified error, so this reads the classification rather than classifying
        // the wrapper: a `404` inside an `ActionFailure` is NOT_FOUND, and classifying the wrapper would
        // make every failure look like an unknown one and the feature look "present" after a `404`.
        val actionError = (error as? ActionFailure)?.error ?: error.toActionError()
        val fromPolicy = CapabilityPolicy.from(actionError.kind)
        _availability.value = when {
            fromPolicy is RouteAvailability.Absent ->
                PersistentPtyAvailability.Absent(fromPolicy.httpStatus)

            // A `503` is the host not running, and the features doc spells it as
            // `ServiceUnavailableError`. A `500` is not that, so it falls back to "present": the routes
            // answered, and a server fault does not prove the feature is gone.
            actionError.kind == ActionErrorKind.SERVER -> PersistentPtyAvailability.HostDown
            else -> PersistentPtyAvailability.Present
        }
    }

    private suspend inline fun <T> call(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        val actionError = error.toActionError()
        _error.value = actionError
        Result.failure(ActionFailure(actionError))
    }

    /**
     * The refusal for a call that was not made because the switch is off.
     *
     * It is [ActionErrorKind.FORBIDDEN] and not a synthetic kind, so the screen's existing error
     * wording applies, and it is *not* recorded in [record]: a route the app was told not to call has
     * told us nothing about whether it exists.
     */
    private fun <T> refused(): Result<T> =
        Result.failure(ActionFailure(ActionError(ActionErrorKind.FORBIDDEN, SWITCH_OFF)))

    private companion object {
        /** The message the switch-off refusal carries, the same one the review's writes use. */
        const val SWITCH_OFF = "experimental-routes-off"
    }
}

/**
 * The route's screen as the client's own union, so a screen and "no terminal" cannot be confused.
 *
 * The `null` case is the route's: `data` is nullable and `null` means the session has no terminal yet.
 */
private fun PersistentPtyScreen.toRead(): SessionTerminalRead = SessionTerminalRead.Screen(
    ptyID = ptyID,
    title = title,
    cwd = cwd,
    foregroundProcess = foregroundProcess,
    text = screen.text,
    cols = screen.cols,
    rows = screen.rows,
    cursor = screen.cursor,
)

/** What a session's terminal pane shows. */
data class SessionTerminalState(
    val sessionID: String? = null,
    val terminals: List<PersistentPtyInfo> = emptyList(),
    val screen: SessionTerminalRead? = null,
) {
    /** The most recently created terminal, which is the one a picker opens. */
    val latest: PersistentPtyInfo? get() = terminals.lastOrNull()
}
