package dev.opencode.android.feature.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.terminal.TerminalBridgeMessage
import dev.opencode.android.core.data.terminal.TerminalGrid
import dev.opencode.android.core.data.terminal.TerminalGridSize
import dev.opencode.android.core.model.PtySize
import dev.opencode.android.core.model.PtyTicketToken
import dev.opencode.android.core.model.ShellOption
import dev.opencode.android.core.model.event.PtyInfo
import dev.opencode.android.core.network.AuthInterceptor
import dev.opencode.android.core.network.PtySocket
import dev.opencode.android.core.network.PtyStreamState
import dev.opencode.android.core.network.ServerCredentialCache
import dev.opencode.android.core.network.ServerTls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/** What the terminal screen shows (plan §6, "PTY terminals"). */
data class TerminalUiState(
    val directory: String? = null,
    val terminals: List<PtyInfo> = emptyList(),
    val open: PtyInfo? = null,
    val stream: PtyStreamState = PtyStreamState.Idle,
    /** The grid the terminal was last told the server has, so a repeat resize is not sent. */
    val size: TerminalGridSize? = null,
    /** The shells `config.shell` offers, for the "new terminal" picker. */
    val shells: List<ShellOption> = emptyList(),
    val pickerOpen: Boolean = false,
    val creating: Boolean = false,
    /** The project's own `commands.start`, offered as a quick action and run only when tapped. */
    val startCommand: String? = null,
    /** The kill the user asked for, held until they confirm it (plan §5.2). */
    val killTarget: String? = null,
    /** A ticket for a page that has to connect itself, when one was minted. */
    val ticket: String? = null,
    /**
     * The text the page has selected, which the host puts on the clipboard.
     *
     * **The page cannot copy for itself.** A hardened WebView with no file and no content access has
     * no clipboard permission to ask for, so the selection travels over the bridge and the host's own
     * clipboard does it — which is also the only way the copy lands in the app the user is looking at.
     */
    val selection: String? = null,
    /** Output the page has not taken yet, and which the screen writes and then clears. */
    val pendingOutput: String = "",
    val error: String? = null,
) {
    val canKill: Boolean get() = killTarget != null
    val isLive: Boolean get() = stream is PtyStreamState.Live
    val canStartProject: Boolean get() = !creating && !startCommand.isNullOrBlank()

    /**
     * The word the header shows, which is the stream's own state.
     *
     * It is a stable key rather than a sentence so the screen turns it into a localised string, and it
     * is `Live` only after the server's cursor frame: until then the client does not know how much of
     * the replay it has, and calling that "connected" would be a claim the protocol does not support.
     */
    val statusKey: String
        get() = when (stream) {
            is PtyStreamState.Idle -> "idle"
            is PtyStreamState.Connecting -> "connecting"
            is PtyStreamState.Live -> "live"
            is PtyStreamState.Reconnecting -> "reconnecting"
            is PtyStreamState.Closed -> "closed"
        }

    /** Whether the page should be told the stream is not live, which is what dims the terminal. */
    val connected: Boolean get() = stream is PtyStreamState.Live || stream is PtyStreamState.Connecting
}

/**
 * A live terminal: the socket, the resize, and the bridge messages (features doc §31).
 *
 * **The WebSocket belongs to this view model, not to the screen.** A terminal has to survive a
 * rotation, a sheet animation and a recomposition, and a socket held by a composable is dropped on the
 * first of those. The socket is created when a terminal is opened and closed when the screen leaves.
 *
 * **Basic auth on the upgrade, with a ticket as the fallback.** OkHttp can set an `Authorization`
 * header on a WebSocket handshake, so the normal path is [AuthInterceptor] on a client derived for
 * this server — the same interceptor every REST call uses, which is what makes a re-pair take effect
 * on the next reconnect. A ticket is only minted for the WebView's own fallback (see [requestTicket]).
 *
 * **The resize is a REST call, deduplicated by the grid.** `TerminalGrid` turns a measured area into a
 * cell count and [TerminalGrid.needsResize] says whether it moved, so a drag that produces twenty
 * layouts costs at most a couple of `pty.update` calls and never sends a `size {rows: 0}` the server
 * refuses.
 */
class TerminalViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val connections: ServerConnectionManager,
    private val credentialCache: ServerCredentialCache,
    private val serverTls: ServerTls,
    private val okHttpClient: OkHttpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    /**
     * Output the page has not taken yet.
     *
     * Held until the page says `ready`, because a WebView loads its assets asynchronously and writing
     * into a terminal that is not mounted loses the text. It is a `StringBuilder` rather than a
     * `MutableStateFlow` because it changes at message rate and nothing observes it but the bridge.
     */
    private val pendingOutput = StringBuilder()

    private var pageReady = false
    private var socket: PtySocket? = null

    /** Whether this terminal has already spent its one `pty.connect.token` attempt. */
    private var ticketTried = false

    /** Binds the panel to a location and reads the terminals. */
    fun open(directory: String, startCommand: String? = null) {
        val set = dataSets.active.value ?: return
        _state.value = TerminalUiState(directory = directory, startCommand = startCommand)
        set.execution.open(directory)
        // The project's own `commands.start` is what the quick action runs. It is resolved here rather
        // than passed through the route, because the route is opened from a session menu, from a
        // notification and from the terminal screen itself, and only this view model can tell which
        // project a checkout belongs to.
        if (startCommand == null) {
            viewModelScope.launch {
                set.projects.state.collect { projects ->
                    val project = projects.value?.firstOrNull { it.canonical == directory }
                    val command = project?.commands?.start
                    if (command != null && _state.value.startCommand == null) {
                        _state.value = _state.value.copy(startCommand = command)
                    }
                }
            }
        }
        viewModelScope.launch {
            set.execution.commands.cachedShellOptions().getOrNull()?.let { shells ->
                _state.value = _state.value.copy(shells = shells)
            }
        }
        viewModelScope.launch {
            set.execution.at(directory).ptys.collect { terminals ->
                _state.value = _state.value.copy(terminals = terminals)
            }
        }
    }

    /**
     * Opens one terminal and connects to it.
     *
     * The socket is created with no cursor, which is the server's "replay the whole retained buffer" —
     * the right thing for a terminal being opened. A *reconnect* goes through [reconnect] and carries
     * the cursor the server reported.
     */
    fun openTerminal(ptyID: String) {
        val directory = _state.value.directory ?: return
        val info = _state.value.terminals.firstOrNull { it.id == ptyID }
        if (info == null) {
            _state.value = _state.value.copy(error = "unknown-terminal")
            return
        }
        closeSocket()
        ticketTried = false
        _state.value = _state.value.copy(open = info, size = null, ticket = null, pickerOpen = false)
        val created = connect(directory, ptyID, ticket = null)
        if (created == null) _state.value = _state.value.copy(error = "socket-unavailable")
    }

    /** Re-opens the socket from the cursor the server last reported. */
    fun reconnect() {
        val directory = _state.value.directory ?: return
        val id = _state.value.open?.id ?: return
        closeSocket()
        connect(directory, id, ticket = null)
    }

    /**
     * `pty.connect.token` as the fallback for a refused Basic-auth upgrade.
     *
     * **A ticket is tried once, and only when the server refused the header.** The normal path is
     * [AuthInterceptor] on the upgrade, which every other request uses and which is what makes a
     * re-pair take effect; a `401` or `403` from the upgrade is the one case a header cannot fix — a
     * proxy in front of the server, or a deployment that only accepts the single-use ticket. Trying it
     * on every network failure would spend a ticket per backoff period for nothing, so [ticketTried]
     * makes it exactly one attempt per opened terminal.
     */
    private fun connectWithTicket(directory: String, ptyID: String) {
        if (ticketTried) return
        ticketTried = true
        val commands = dataSets.active.value?.execution?.commands ?: return
        viewModelScope.launch {
            val ticket = commands.ptyTicket(directory, ptyID).getOrNull()?.ticket
            if (ticket == null) return@launch
            _state.value = _state.value.copy(ticket = ticket)
            closeSocket()
            connect(directory, ptyID, ticket = ticket)
        }
    }

    private fun connect(directory: String, ptyID: String, ticket: String?): PtySocket? {
        val profile = connections.activeConnection.value?.serverProfile ?: return null
        val client = serverTls
            .clientFor(profile.trustUserCertificates)
            .newBuilder()
            .addInterceptor(AuthInterceptor(credentialProvider = credentialCache))
            .build()
        val socket = PtySocket(
            url = PtySocket.terminalUrl(
                baseUrl = profile.baseUrl,
                path = "api/pty/$ptyID/connect",
                query = mapOf(LOCATION_QUERY to directory),
            ),
            okHttpClient = client,
            // The interceptor supplies the credential; a ticket is only ever set explicitly, because a
            // credential in a URL would end up in a log.
            credential = null,
            ticket = ticket,
        )
        this.socket = socket
        viewModelScope.launch {
            socket.state.collect { stream -> foldStream(stream) }
        }
        viewModelScope.launch {
            socket.output.collect { chunk ->
                // A terminal writes faster than a WebView lays out a line, so the buffer drops its
                // oldest frames rather than applying backpressure to the socket (plan §4.2, "never
                // block the reader"). What is lost is a frame of output; the page redraws from its own
                // buffer, so the terminal's state is not lost.
                pendingOutput.append(chunk)
                trimPending()
                drain()
            }
        }
        socket.open(viewModelScope)
        return socket
    }

    /**
     * A message the page sent, already validated by
     * [dev.opencode.android.core.data.terminal.TerminalBridgeCodec].
     *
     * **Every kind the codec can produce is handled here, and none is dropped.** Input is the one that
     * matters most: the page's `onData` is how a hardware keyboard and a paste both reach the socket,
     * so a `when` without an `Input` branch would leave a terminal that renders perfectly and accepts
     * nothing. [Selection] is handed on rather than acted on, because copying is the host's clipboard
     * and not this view model's business.
     */
    fun onBridgeMessage(message: TerminalBridgeMessage) {
        when (message) {
            is TerminalBridgeMessage.Ready -> {
                pageReady = true
                drain()
            }

            is TerminalBridgeMessage.Input -> sendInput(message.data)
            is TerminalBridgeMessage.Resize -> resize(message.cols, message.rows)
            is TerminalBridgeMessage.Selection -> _state.value = _state.value.copy(selection = message.data)
            is TerminalBridgeMessage.Failed -> _state.value = _state.value.copy(error = message.message)
        }
    }

    /**
     * Sends bytes to the terminal, as one frame.
     *
     * **The whole run goes in one frame, not one per key.** The extra-keys row hands over the latches
     * and the key as a sequence, and `TerminalInput` has already turned it into bytes; sending them
     * separately would let the terminal's own echo interleave between a modifier and the key it
     * modifies, which is how a `Ctrl`-`C` turns into a `c`.
     */
    fun sendInput(text: String) {
        if (text.isEmpty()) return
        socket?.send(text)
    }

    private fun drain() {
        if (!pageReady || pendingOutput.isEmpty()) return
        val chunk = pendingOutput.toString()
        pendingOutput.setLength(0)
        _state.value = _state.value.copy(pendingOutput = _state.value.pendingOutput + chunk)
    }

    private fun trimPending() {
        if (pendingOutput.length <= PENDING_LIMIT) return
        pendingOutput.delete(0, pendingOutput.length - PENDING_LIMIT)
    }

    /** `pty.update` with a size, but only when the grid actually moved. */
    fun resize(cols: Int, rows: Int) {
        val next = TerminalGridSize(cols = cols, rows = rows)
        if (!TerminalGrid.needsResize(_state.value.size, next)) return
        val directory = _state.value.directory ?: return
        val id = _state.value.open?.id ?: return
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(size = next)
        viewModelScope.launch {
            val error = set.execution.commands.resizePty(directory, id, PtySize(rows, cols))
                .exceptionOrNull()?.toActionError()
            if (error != null) _state.value = _state.value.copy(error = error.message)
        }
    }

    /** `pty.create`, with the shell the user picked. A `null` [command] runs the configured shell. */
    fun createTerminal(command: String?, args: List<String>? = null, title: String? = null) {
        val directory = _state.value.directory ?: return
        val set = dataSets.active.value ?: return
        _state.value = _state.value.copy(creating = true, error = null)
        viewModelScope.launch {
            val result = set.execution.commands.createPty(directory, command, args, title)
            val error = result.exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(creating = false, error = error?.message, pickerOpen = false)
            result.getOrNull()?.let { openTerminal(it.id) }
        }
    }

    /**
     * `pty.create` with the project's own start command, the quick action the plan asks for.
     *
     * The command is a script, so it runs as `shell -c <command>`; [shellPath] is the first shell the
     * server says it can run, and a `null` there means the server's own default is used instead, which
     * is the same thing a terminal opened without a command does.
     */
    fun createFromProjectStart() {
        val command = _state.value.startCommand?.takeIf { it.isNotBlank() } ?: return
        val shell = shellPath()
        if (shell == null) {
            createTerminal(null)
            return
        }
        createTerminal(shell, listOf("-c", command))
    }

    /** `pty.update` with a title; `pty.updated` then renames every open view of the terminal. */
    fun rename(ptyID: String, title: String) {
        val directory = _state.value.directory ?: return
        val commands = dataSets.active.value?.execution?.commands ?: return
        viewModelScope.launch {
            val error = commands.renamePty(directory, ptyID, title).exceptionOrNull()?.toActionError()
            if (error != null) _state.value = _state.value.copy(error = error.message)
        }
    }

    fun openPicker() {
        _state.value = _state.value.copy(pickerOpen = true)
    }

    /** Closes the picker without creating anything; "use the server's default shell" is its own row. */
    fun closePicker() {
        _state.value = _state.value.copy(pickerOpen = false)
    }

    fun requestKill(ptyID: String) {
        _state.value = _state.value.copy(killTarget = ptyID, error = null)
    }

    fun cancelKill() {
        _state.value = _state.value.copy(killTarget = null)
    }

    /** `pty.remove`, after the confirmation plan §5.2 asks for. */
    fun confirmKill() {
        val directory = _state.value.directory ?: return
        val set = dataSets.active.value ?: return
        val target = _state.value.killTarget ?: return
        _state.value = _state.value.copy(killTarget = null)
        viewModelScope.launch {
            val error = set.execution.commands.removePty(directory, target).exceptionOrNull()?.toActionError()
            if (error != null) {
                _state.value = _state.value.copy(error = error.message)
            } else if (_state.value.open?.id == target) {
                closeSocket()
                _state.value = _state.value.copy(open = null, stream = PtyStreamState.Closed("removed"))
            }
        }
    }

    /**
     * `pty.connect.token` on demand, for the case a user hits by hand: a server that refuses the
     * header-authenticated upgrade is retried with a ticket before anything is reported as failed
     * (see [foldStream]), and this is the same call for anyone who wants to watch it happen.
     */
    fun requestTicket() {
        val directory = _state.value.directory ?: return
        val id = _state.value.open?.id ?: return
        val commands = dataSets.active.value?.execution?.commands ?: return
        ticketTried = true
        viewModelScope.launch {
            val result: Result<PtyTicketToken> = commands.ptyTicket(directory, id)
            _state.value = _state.value.copy(
                ticket = result.getOrNull()?.ticket,
                error = result.exceptionOrNull()?.toActionError()?.message,
            )
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Clears the output the page has taken, so the next write starts from an empty buffer.
     *
     * **The screen calls this, and without it the buffer only grows.** Every chunk appends to
     * `pendingOutput` and is published whole; if nothing removes what was written, the next publication
     * re-sends everything the terminal has ever said. A busy terminal would then rewrite its whole
     * history on every frame, which is both quadratic and visibly wrong.
     */
    fun outputConsumed(count: Int) {
        if (count <= 0) return
        _state.value = _state.value.copy(pendingOutput = _state.value.pendingOutput.drop(count))
    }

    private fun foldStream(stream: PtyStreamState) {
        val directory = _state.value.directory
        val open = _state.value.open
        // A refused upgrade is the one failure a credential in the header cannot fix, so it is the one
        // case a `pty.connect.token` is worth spending. `4404` is not: that terminal is gone.
        if (stream is PtyStreamState.Closed && open != null && directory != null &&
            stream.reason in TICKET_WORTH_REFUSALS
        ) {
            connectWithTicket(directory, open.id)
        }
        _state.value = _state.value.copy(
            stream = stream,
            // A closed socket is not a stopped process: the process may still be running with nobody
            // attached, so the status is left to `pty.get` and `pty.updated` to say. Inventing
            // "exited" here would show a terminal as dead that is only unattached.
            open = open,
        )
    }

    private fun shellPath(): String? = _state.value.shells.firstOrNull { it.acceptable }?.path

    private fun closeSocket() {
        socket?.close("closed")
        socket = null
        pendingOutput.setLength(0)
        pageReady = false
    }

    override fun onCleared() {
        closeSocket()
        super.onCleared()
    }

    private companion object {
        /**
         * The `location[directory]` query the WebSocket URL carries, named once.
         *
         * The deepObject spelling is the one every route takes; `LocationParam.QUERY_KEY` is in
         * `core:network` and this module depends on the core modules only, so the literal is asserted
         * against the constant by a test rather than imported.
         */
        const val LOCATION_QUERY = "location[directory]"

        /** How much output is held before the oldest is dropped: 256 KiB of characters. */
        const val PENDING_LIMIT = 256 * 1024

        /**
         * The statuses a single-use ticket can still fix.
         *
         * `PtyStreamState.Closed` carries no status of its own, so the fallback keys on the reason text
         * the socket builds. A `404` is deliberately absent: that is a terminal which is not there, and
         * no ticket brings one back.
         */
        val TICKET_WORTH_REFUSALS = setOf("refused (401)", "refused (403)")
    }
}
