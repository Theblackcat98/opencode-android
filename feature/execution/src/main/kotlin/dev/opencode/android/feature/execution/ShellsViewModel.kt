package dev.opencode.android.feature.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.attention.OpenLocationTracker
import dev.opencode.android.core.data.execution.ShellExit
import dev.opencode.android.core.data.execution.ShellOutputPoller
import dev.opencode.android.core.data.execution.ShellOutputState
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.model.ShellInfo
import dev.opencode.android.core.model.ShellOutput
import dev.opencode.android.core.model.ShellStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row of the shell panel. */
data class ShellRow(
    val info: ShellInfo,
    /** The output read so far, which is only non-empty for the command that is open. */
    val output: ShellOutputState? = null,
) {
    val id: String get() = info.id
    val isRunning: Boolean get() = info.status.value == "running"
    val isFailed: Boolean get() = info.status.value == "exited" && (info.exit ?: 0) != 0
}

/** The shell panel's state (plan §6, "Shell commands"). */
data class ShellsUiState(
    val directory: String? = null,
    val rows: List<ShellRow> = emptyList(),
    val openID: String? = null,
    val draft: String = "",
    val running: Boolean = false,
    /** The kill the user asked for, held until they confirm it (plan §5.2). */
    val killTarget: String? = null,
    val error: String? = null,
) {
    val open: ShellRow? get() = rows.firstOrNull { it.id == openID }
    val canRun: Boolean get() = draft.isNotBlank() && !running
}

/**
 * The shell panel: the commands running in a location, their output, and killing one
 * (features doc §30).
 *
 * **The output is polled, because that is the only way it exists.** `shell.output` is cursor-paged and
 * the features doc has no event that carries output, so [ShellOutputPoller] asks for the part after the
 * last page it saw until the command has ended and the last page was read. The cursor is never advanced
 * locally: that would skip output on the next poll and produce a panel with a hole in it.
 *
 * **The output belongs to the poller, and the rows are decorated from it.** The open command's output is
 * not stored in a row, because a row can be missing when a page arrives — the list is filled by an event
 * and a read, and a page can beat both — and a page folded into a row that is not there is a page thrown
 * away. Every publication of the rows attaches the poller's current state to the open command's row, so
 * the pane shows the text whenever the row exists, whichever came first.
 *
 * **A command's end reaches the poller from the list.** `shell.exited` updates the row in the store, and
 * this view model tells the poller when the open command's row says it ended; the poller reads the last
 * page after that and stops. It also asks `shell.get` itself when a page brings nothing, so an event that
 * was missed does not leave it polling.
 *
 * **A command the server has removed is not an error.** The row the user was watching stays on screen
 * with the output it had. Throwing away the last page on a `404` would be a worse answer than showing a
 * command whose final status this client did not get to read.
 *
 * **Killing is two steps** (plan §5.2: a dangerous action asks). [killTarget] is the row awaiting the
 * confirmation, and no request is sent until the user answers.
 */
@HiltViewModel
class ShellsViewModel(
    private val active: StateFlow<ServerDataSet?>,
    private val openLocations: OpenLocationTracker,
) : ViewModel() {

    /** Hilt's constructor: the read model of the server being followed. */
    @Inject
    constructor(
        dataSets: ServerDataRegistry,
        openLocations: OpenLocationTracker,
    ) : this(dataSets.active, openLocations)

    private val _state = MutableStateFlow(ShellsUiState())
    val state: StateFlow<ShellsUiState> = _state.asStateFlow()

    private val poller = ShellOutputPoller()
    private var collector: Job? = null

    /** Binds the panel to a location and publishes it as the one being watched. */
    fun openPanel(directory: String) {
        val set = active.value ?: return
        // The open command survives a resume: `openPanel` also runs on `ON_RESUME`, and resetting the
        // pane there would close the output a user backgrounded a build to read. The draft is not kept,
        // because a half-typed command that outlives the screen is worse than one that has to be typed
        // again.
        val openID = _state.value.openID
        _state.value = _state.value.copy(directory = directory, openID = openID)
        openLocations.set(directory)
        set.execution.open(directory)
        // Opening the panel is what "the user has seen these commands' output" means, so the
        // completions waiting to be announced are dropped. Not doing this is how a notification ends
        // up describing something the user has already dealt with.
        set.execution.markFinishedSeen(directory)
        collector?.cancel()
        collector = viewModelScope.launch {
            set.execution.at(directory).shells.collect { shells ->
                _state.value = _state.value.copy(rows = shells.map { decorate(ShellRow(it)) })
                shells.firstOrNull { it.id == _state.value.openID }?.let(::noteEnd)
            }
        }
        openID?.let { follow(it) }
    }

    /**
     * Re-reads the panel after the screen comes back, and follows the open command again.
     *
     * **The list is re-read, and the open command's status with it.** A `shell.list` answers the commands
     * that are *running*, so anything that finished while the phone was in a pocket is in neither the list
     * nor the events that were missed, and its exit code is only reachable through `shell.get`.
     *
     * **Polling starts again from the cursor it stopped at.** [close] stops it when the screen goes to the
     * background, so without this a command that kept printing while the phone was in a pocket would show
     * the output it had at the moment the screen left. It is not [openPanel]'s reset: the output the user
     * has not read is still the output the next page continues from.
     */
    fun resumePanel() {
        val set = active.value ?: return
        val directory = _state.value.directory ?: return
        openLocations.set(directory)
        set.execution.open(directory)
        val openID = _state.value.openID ?: return
        val output = poller.state()
        if (!poller.isPolling && !(output.exited && output.caughtUp)) startPolling(openID)
        refreshStatus(openID)
    }

    /** Called when the panel leaves the screen, so a completion is announced again. */
    fun close() {
        openLocations.set(null)
        stopFollowing()
    }

    fun setDraft(command: String) {
        _state.value = _state.value.copy(draft = command)
    }

    /** `shell.create`, and the row appears from the answer or the event, whichever lands first. */
    fun run() {
        val state = _state.value
        val directory = state.directory ?: return
        val set = active.value ?: return
        if (!state.canRun) return
        _state.value = state.copy(running = true, error = null)
        viewModelScope.launch {
            val result = set.execution.commands.runShell(directory, state.draft.trim())
            val error = result.exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(
                running = false,
                error = error?.message,
                draft = if (error == null) "" else _state.value.draft,
            )
            result.getOrNull()?.let { openCommand(it.id) }
        }
    }

    /**
     * Opens one command and starts following its output.
     *
     * Following rather than reading once is the point: a command that is still running has more
     * output, and the only way to see it is to ask for the part after the last page.
     */
    fun openCommand(shellID: String) {
        val set = active.value ?: return
        val directory = _state.value.directory ?: return
        _state.value = _state.value.copy(openID = shellID)
        follow(shellID)
        set.execution.open(directory)
    }

    /**
     * Starts the polling loop for the open command from the beginning, and reads its status once.
     *
     * **The status read is on every open, not only the first.** `shell.list` answers with the commands
     * that are *running*, so a command that finished while the panel was away is in neither the list nor
     * the events, and `shell.get` is the only place its exit code exists.
     */
    private fun follow(shellID: String) {
        if (_state.value.directory == null) return
        stopFollowing()
        poller.reset()
        // A command the list already says has ended needs one read, not a wait to find that out.
        _state.value.rows.firstOrNull { it.id == shellID }?.info?.let(::noteEnd)
        startPolling(shellID)
        refreshStatus(shellID)
    }

    /** Runs the poller from wherever it is, which is the beginning after [follow] and later on a resume. */
    private fun startPolling(shellID: String) {
        val directory = _state.value.directory ?: return
        poller.start(
            scope = viewModelScope,
            page = { cursor -> readPage(directory, shellID, cursor) },
            status = { readExit(directory, shellID) },
            onState = { publishRows() },
        )
    }

    /** Stops following and closes the pane, which is what the pane's own button means. */
    fun closeCommand() {
        stopFollowing()
        _state.value = _state.value.copy(openID = null)
        publishRows()
    }

    /**
     * Reads the command's status once, so the row shows its exit code even if the event was missed.
     *
     * A resync is not enough here: `shell.list` answers with the *running* commands, so a command that
     * finished while the event stream was down is in neither the list nor the events, and its exit code
     * is only reachable through `shell.get`.
     *
     * **Public because the host calls it, not only [follow].** The panel is re-read on `ON_RESUME`, and a
     * command that ran to completion while the phone was in a pocket is exactly the one a user comes back
     * to read — so the open row's status is refreshed on resume as well as on open.
     */
    fun refreshStatus(shellID: String) {
        val set = active.value ?: return
        val directory = _state.value.directory ?: return
        viewModelScope.launch {
            val info = set.execution.commands.shell(directory, shellID).getOrNull() ?: return@launch
            if (_state.value.openID == shellID) noteEnd(info)
        }
    }

    /**
     * Tells the poller the open command has ended, when [info] says so.
     *
     * The poller reads the last page after this and stops. If its loop had already ended — it gave up on
     * a failing route, or the panel was resumed after it finished — the loop is started once more, because
     * the bytes written just before the end may not have been read.
     */
    private fun noteEnd(info: ShellInfo) {
        if (info.id != _state.value.openID || info.status.value == "running" || poller.state().exited) return
        poller.exit(info.status.value, info.exit)
        publishRows()
        if (!poller.isPolling && !poller.state().caughtUp) startPolling(info.id)
    }

    /**
     * One page of output, or `null` when the command is gone.
     *
     * The route answers `{location, data}` and a `404` is the end of the stream, not a failure, so
     * [dev.opencode.android.core.data.execution.ExecutionCommands.shellOutput] flattens it and the
     * poller decides what that means. Any other failure is thrown, and the poller retries it.
     */
    private suspend fun readPage(directory: String, shellID: String, cursor: Long): ShellOutput? {
        val set = active.value ?: return null
        return set.execution.commands.shellOutput(directory, shellID, cursor.toString())
    }

    /** How the command ended, or `null` while it is running or `shell.get` could not be read. */
    private suspend fun readExit(directory: String, shellID: String): ShellExit? {
        val set = active.value ?: return null
        val info = set.execution.commands.shell(directory, shellID).getOrNull() ?: return null
        return if (info.status.value == "running") null else ShellExit(info.status.value, info.exit)
    }

    /** Arms the confirmation for a kill, and sends nothing. */
    fun requestKill(shellID: String) {
        _state.value = _state.value.copy(killTarget = shellID, error = null)
    }

    fun cancelKill() {
        _state.value = _state.value.copy(killTarget = null)
    }

    /**
     * `shell.remove`, after the confirmation.
     *
     * A `204` is the whole answer and `shell.deleted` removes the row; the command is not read back
     * first, because a killed process's status is not something the user can act on.
     */
    fun confirmKill() {
        val state = _state.value
        val directory = state.directory ?: return
        val set = active.value ?: return
        val target = state.killTarget ?: return
        _state.value = state.copy(killTarget = null, running = true)
        viewModelScope.launch {
            val error = set.execution.commands.killShell(directory, target).exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(running = false, error = error?.message)
            if (error == null && _state.value.openID == target) {
                poller.stop()
                _state.value = _state.value.copy(openID = null)
                publishRows()
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Attaches the poller's state to the open command's row, and takes it off every other row.
     *
     * **Read from the poller, not passed in**, so there is one copy of the output and a row built later —
     * from the list, from an event, from the answer to `shell.create` — carries it too. The status and exit
     * code the poller learned are laid over the row's own, which is how a command whose event was missed
     * still shows how it ended.
     */
    private fun decorate(row: ShellRow): ShellRow {
        if (row.id != _state.value.openID) return row.copy(output = null)
        val output = poller.state()
        return row.copy(
            output = output,
            info = row.info.copy(
                status = output.status?.let(::ShellStatus) ?: row.info.status,
                exit = output.exitCode ?: row.info.exit,
            ),
        )
    }

    private fun publishRows() {
        _state.value = _state.value.copy(rows = _state.value.rows.map(::decorate))
    }

    private fun stopFollowing() {
        poller.stop()
    }

    override fun onCleared() {
        stopFollowing()
        openLocations.set(null)
        super.onCleared()
    }
}
