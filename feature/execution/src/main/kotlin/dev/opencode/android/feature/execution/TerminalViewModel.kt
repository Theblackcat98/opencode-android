package dev.opencode.android.feature.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.data.terminal.TerminalBridgeMessage
import dev.opencode.android.core.data.terminal.TerminalGrid
import dev.opencode.android.core.data.terminal.TerminalGridSize
import dev.opencode.android.core.model.PtySize
import dev.opencode.android.core.model.ShellOption
import dev.opencode.android.core.model.event.PtyInfo
import dev.opencode.android.core.network.AuthInterceptor
import dev.opencode.android.core.network.PtySocket
import dev.opencode.android.core.network.PtyStreamState
import dev.opencode.android.core.network.ServerCredentialCache
import dev.opencode.android.core.network.ServerTls
import kotlinx.coroutines.Job
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
     * Whether the server refused the header-authenticated upgrade and a ticket is worth trying.
     *
     * **This is what puts the ticket row on screen, and it is the only thing that does.** The normal path
     * is [AuthInterceptor] on the WebSocket handshake, the same credential every REST call uses; a `401`
     * or `403` from the upgrade is the one case a header cannot fix. Offering `pty.connect.token` on a
     * healthy terminal would spend a single-use ticket on nothing, so it appears because the upgrade
     * failed rather than in anticipation of it.
     */
    val ticketRefused: Boolean = false,
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
    /**
     * How many times the page has been told to start from an empty screen.
     *
     * **It changes whenever a new socket starts, because a new socket replays from the beginning.** The
     * screen watches it and empties the page before the replay arrives; without it a reconnect, or a
     * second terminal, is drawn on top of what the page already shows.
     */
    val epoch: Int = 0,
    val error: TerminalError? = null,
) {
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
 * Why the terminal screen is telling the user something went wrong.
 *
 * **A reason and not a sentence, so the screen can localise it.** A view model has no `Context`, and a
 * string typed here would reach the dialog as the code it is written as. The two reasons this client
 * itself has are objects the screen turns into `strings.xml` text; anything the *server* said, or the
 * page reported, is [Said] and is shown as it came, because it is more specific than anything this
 * client could write.
 */
sealed interface TerminalError {
    /** The server's own words, or the page's, shown as they arrived. */
    data class Said(val message: String) : TerminalError

    /** The terminal the user chose is no longer in the server's list: another client removed it. */
    data object Gone : TerminalError

    /** There is no server connection to open the terminal's socket over. */
    data object NoConnection : TerminalError
}

private fun ActionError.said(): TerminalError = TerminalError.Said(message)

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
 *
 * **A terminal the user just created is opened from what the server answered, not from the list.**
 * `pty.create` answers with the whole terminal, and the list learns of it from the `pty.created` event
 * that follows, which the dispatcher applies a frame later. Looking the new id up in the list — which is
 * what this used to do — found nothing whenever the answer won that race, and the screen said the terminal
 * was unknown while the server was running it. A terminal the user *taps*, by contrast, is a row of the
 * list, so it is looked up there.
 */
@HiltViewModel
class TerminalViewModel(
    private val active: StateFlow<ServerDataSet?>,
    private val sockets: TerminalSockets,
) : ViewModel() {

    /**
     * Hilt's constructor: the read model of the server being followed, and the socket that server's
     * credential and trust setting build.
     */
    @Inject
    constructor(
        dataSets: ServerDataRegistry,
        connections: ServerConnectionManager,
        credentialCache: ServerCredentialCache,
        serverTls: ServerTls,
    ) : this(dataSets.active, ActiveServerSockets(connections, credentialCache, serverTls))

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

    /**
     * Whether a page is mounted and can take output.
     *
     * **It belongs to the page and not to the socket.** A socket that is replaced leaves the page where
     * it was — mounted, ready, and never going to say `ready` again — so replacing one must not clear
     * this; only the page going away does.
     */
    private var pageReady = false

    /** The grid the page last measured, which a terminal the server has just made is told about. */
    private var grid: TerminalGridSize? = null
    private var socket: PtySocket? = null

    /** The two collectors of [socket], cancelled with it so a closed socket cannot write into its successor. */
    private var collectors: Job? = null

    /**
     * Whether this terminal has already spent its one `pty.connect.token` attempt.
     *
     * **A second attempt is a second single-use ticket, and the second one buys nothing.** The fallback
     * in [foldStream] fires on a `401` or `403` from the upgrade, which a header cannot fix; a network
     * failure or a `500` is a different problem that a ticket does not address. The button that reports
     * it is therefore *disabled* once the automatic attempt has been made rather than hidden, so the
     * state is visible and the operation is not offered as though it were still likely to help.
     */
    private var ticketTried = false

    /**
     * Binds the panel to a location and reads the terminals.
     *
     * **Binding to the location it is already bound to changes nothing.** The screen calls this from a
     * `LaunchedEffect`, which runs again whenever the activity is recreated — a rotation is enough — and
     * this view model outlives the activity. Starting over then emptied the state while the socket stayed
     * open, so the terminal the user was in became "No terminal open" over a live connection.
     */
    fun open(directory: String, startCommand: String? = null) {
        val set = active.value ?: return
        if (_state.value.directory == directory) return
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
        // The row of the terminal already on screen is not a request to reconnect it.
        if (_state.value.open?.id == ptyID && _state.value.connected) return
        // A row the user tapped is in the list, so a miss means the server removed it since it was drawn.
        val info = _state.value.terminals.firstOrNull { it.id == ptyID }
        if (info == null) {
            _state.value = _state.value.copy(error = TerminalError.Gone)
            return
        }
        attach(directory, info)
    }

    /**
     * Shows [info] as the open terminal and connects to it.
     *
     * Takes the terminal itself rather than an id, so a terminal the server has just described can be
     * opened before the list has heard of it.
     */
    private fun attach(directory: String, info: PtyInfo) {
        closeSocket()
        ticketTried = false
        _state.value = _state.value.copy(open = info, size = null, ticket = null, pickerOpen = false)
        val created = connect(directory, info.id, ticket = null)
        if (created == null) {
            _state.value = _state.value.copy(error = TerminalError.NoConnection)
            return
        }
        // The page keeps the grid it measured for the terminal before, and the one the server has just
        // made has been told nothing: the page will not measure again, because nothing about it moved.
        grid?.let { resize(it.cols, it.rows) }
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
        val commands = active.value?.execution?.commands ?: return
        viewModelScope.launch {
            val ticket = commands.ptyTicket(directory, ptyID).getOrNull()?.ticket
            if (ticket == null) return@launch
            _state.value = _state.value.copy(ticket = ticket)
            closeSocket()
            connect(directory, ptyID, ticket = ticket)
        }
    }

    private fun connect(directory: String, ptyID: String, ticket: String?): PtySocket? {
        val socket = sockets.create(directory, ptyID, ticket) ?: return null
        this.socket = socket
        // A new socket replays the retained buffer from the start, so the page starts from an empty screen.
        _state.value = _state.value.copy(pendingOutput = "", epoch = _state.value.epoch + 1)
        collectors = viewModelScope.launch {
            launch { socket.state.collect { stream -> foldStream(stream) } }
            launch {
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
                // A page says `ready` once, when it loads. A second one, with the first never withdrawn,
                // is a *different* page — the activity was recreated, and the screen the old one drew went
                // with it. The socket is still open and will only say what comes next, so it is started
                // again, and its replay is what fills the new page.
                val replacement = pageReady
                pageReady = true
                if (replacement && _state.value.open != null) reconnect() else drain()
            }

            is TerminalBridgeMessage.Input -> sendInput(message.data)

            is TerminalBridgeMessage.Resize -> {
                grid = TerminalGridSize(cols = message.cols, rows = message.rows)
                resize(message.cols, message.rows)
            }

            is TerminalBridgeMessage.Selection -> _state.value = _state.value.copy(selection = message.data)

            is TerminalBridgeMessage.Failed ->
                _state.value = _state.value.copy(error = TerminalError.Said(message.message))
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
        // Bounded like the builder above: while the screen is stopped nothing consumes this, and a busy
        // terminal would otherwise grow it for as long as the app stays in the background.
        val held = (_state.value.pendingOutput + chunk).takeLast(PENDING_LIMIT)
        _state.value = _state.value.copy(pendingOutput = held)
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
        val set = active.value ?: return
        _state.value = _state.value.copy(size = next)
        viewModelScope.launch {
            val error = set.execution.commands.resizePty(directory, id, PtySize(rows, cols))
                .exceptionOrNull()?.toActionError()
            if (error != null) _state.value = _state.value.copy(error = error.said())
        }
    }

    /**
     * `pty.create`, with the shell the user picked. A `null` [command] runs the configured shell.
     *
     * The terminal that opens is the one the server answered with. The store has recorded the same answer
     * by then, and `pty.created` will confirm it, but neither is waited for: the answer is already the
     * server's word, and waiting for a second copy of it is what made this fail.
     */
    fun createTerminal(command: String?, args: List<String>? = null, title: String? = null) {
        val directory = _state.value.directory ?: return
        val set = active.value ?: return
        _state.value = _state.value.copy(creating = true, error = null)
        viewModelScope.launch {
            val result = set.execution.commands.createPty(directory, command, args, title)
            val error = result.exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(creating = false, error = error?.said(), pickerOpen = false)
            result.getOrNull()?.let { attach(directory, it) }
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
        val commands = active.value?.execution?.commands ?: return
        viewModelScope.launch {
            val error = commands.renamePty(directory, ptyID, title).exceptionOrNull()?.toActionError()
            if (error != null) _state.value = _state.value.copy(error = error.said())
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
        val set = active.value ?: return
        val target = _state.value.killTarget ?: return
        _state.value = _state.value.copy(killTarget = null)
        viewModelScope.launch {
            val error = set.execution.commands.removePty(directory, target).exceptionOrNull()?.toActionError()
            if (error != null) {
                _state.value = _state.value.copy(error = error.said())
            } else if (_state.value.open?.id == target) {
                closeSocket()
                // Nothing is open, so the page is unmounted; the next terminal gets a new one.
                pageReady = false
                _state.value = _state.value.copy(open = null, stream = PtyStreamState.Closed("removed"))
            }
        }
    }

    /**
     * `pty.connect.token`, on demand, for the user who tapped the row.
     *
     * The automatic attempt in [foldStream] is a fallback for the case a header provably cannot fix; this
     * is the same call for the case where the automatic attempt was spent and the user wants to see it
     * happen. It publishes the ticket and reconnects with it, because a ticket the client holds and does
     * not use is not a fix for anything.
     */
    fun requestTicket() {
        val directory = _state.value.directory ?: return
        val id = _state.value.open?.id ?: return
        connectWithTicket(directory, id)
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
            _state.value = _state.value.copy(ticketRefused = true)
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
        collectors?.cancel()
        collectors = null
        socket?.close("closed")
        socket = null
        pendingOutput.setLength(0)
    }

    override fun onCleared() {
        closeSocket()
        super.onCleared()
    }

    private companion object {
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

/**
 * Where a terminal's socket comes from.
 *
 * A seam rather than a constructor argument list, because everything that builds a socket — the server's
 * address, its trust setting, the credential — belongs to the *active connection*, and a test has none.
 */
fun interface TerminalSockets {
    /** An unopened socket for [ptyID], or `null` when there is no server connection to build one over. */
    fun create(directory: String, ptyID: String, ticket: String?): PtySocket?
}

/**
 * The app's sockets: the active server's address and trust setting, with [AuthInterceptor] on the upgrade.
 *
 * The interceptor is the same one every REST call uses, which is what makes a re-pair take effect on the
 * next reconnect.
 */
private class ActiveServerSockets(
    private val connections: ServerConnectionManager,
    private val credentialCache: ServerCredentialCache,
    private val serverTls: ServerTls,
) : TerminalSockets {
    override fun create(directory: String, ptyID: String, ticket: String?): PtySocket? {
        val profile = connections.activeConnection.value?.serverProfile ?: return null
        val client = serverTls
            .clientFor(profile.trustUserCertificates)
            .newBuilder()
            .addInterceptor(AuthInterceptor(credentialProvider = credentialCache))
            .build()
        return terminalSocket(profile.baseUrl, client, directory, ptyID, ticket)
    }
}

/**
 * The socket for one terminal, addressed the way the server's `pty.connect` route is.
 *
 * Shared by the app's sockets and by the tests that stand a fake server behind them, so a test asserts
 * the URL production builds rather than one it wrote for itself.
 */
internal fun terminalSocket(
    baseUrl: String,
    client: OkHttpClient,
    directory: String,
    ptyID: String,
    ticket: String?,
): PtySocket = PtySocket(
    url = PtySocket.terminalUrl(
        baseUrl = baseUrl,
        path = "api/pty/$ptyID/connect",
        query = mapOf(LOCATION_QUERY to directory),
    ),
    okHttpClient = client,
    // The interceptor supplies the credential; a ticket is only ever set explicitly, because a
    // credential in a URL would end up in a log.
    credential = null,
    ticket = ticket,
)

/**
 * The `location[directory]` query the WebSocket URL carries, named once.
 *
 * The deepObject spelling is the one every route takes; `LocationParam.QUERY_KEY` is in `core:network` and
 * this module depends on the core modules only, so the literal is asserted against the request a test's
 * server records rather than imported.
 */
private const val LOCATION_QUERY = "location[directory]"
