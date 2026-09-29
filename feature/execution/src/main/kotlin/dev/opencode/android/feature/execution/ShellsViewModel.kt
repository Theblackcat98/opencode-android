package dev.opencode.android.feature.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.attention.OpenLocationTracker
import dev.opencode.android.core.data.execution.ShellOutputPoller
import dev.opencode.android.core.data.execution.ShellOutputState
import dev.opencode.android.core.data.server.ServerDataRegistry
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
 * last page it saw until the server says there is none. The cursor is never advanced locally: that
 * would skip output on the next poll and produce a panel with a hole in it.
 *
 * **A command the server has removed is not an error.** The list is "commands running in this
 * location", so an exited command leaves it, and the row the user was watching stays on screen with
 * the output it had. Throwing away the last page on a `404` would be a worse answer than showing a
 * command whose final status this client did not get to read.
 *
 * **Killing is two steps** (plan §5.2: a dangerous action asks). [killTarget] is the row awaiting the
 * confirmation, and no request is sent until the user answers.
 */
class ShellsViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val openLocations: OpenLocationTracker,
) : ViewModel() {

    private val _state = MutableStateFlow(ShellsUiState())
    val state: StateFlow<ShellsUiState> = _state.asStateFlow()

    private val poller = ShellOutputPoller()
    private var collector: Job? = null
    private var followJob: Job? = null

    /** Binds the panel to a location and publishes it as the one being watched. */
    fun openPanel(directory: String) {
        val set = dataSets.active.value ?: return
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
                _state.value = _state.value.copy(rows = shells.map(::rowOf))
            }
        }
        openID?.let { follow(it) }
    }

    /**
     * Re-reads the panel after the screen comes back.
     *
     * **The list is re-read, and the open command's status with it.** A `shell.list` answers the commands
     * that are *running*, so anything that finished while the phone was in a pocket is in neither the list
     * nor the events that were missed, and its exit code is only reachable through `shell.get`. This is
     * the resume path rather than [openPanel], because resume must not restart the poller's cursor: the
     * output the user has not read is still the output the next page continues from.
     */
    fun resumePanel() {
        val set = dataSets.active.value ?: return
        val directory = _state.value.directory ?: return
        openLocations.set(directory)
        set.execution.open(directory)
        _state.value.openID?.let(::refreshStatus)
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
        val set = dataSets.active.value ?: return
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
        val set = dataSets.active.value ?: return
        val directory = _state.value.directory ?: return
        _state.value = _state.value.copy(openID = shellID)
        follow(shellID)
        set.execution.open(directory)
    }

    /**
     * (Re)starts the polling loop for the open command and reads its status once.
     *
     * **The status read is on every open, not only the first.** `shell.list` answers with the commands
     * that are *running*, so a command that finished while the panel was away is in neither the list nor
     * the events, and `shell.get` is the only place its exit code exists.
     */
    private fun follow(shellID: String) {
        val directory = _state.value.directory ?: return
        stopFollowing()
        poller.reset()
        followJob = viewModelScope.launch {
            poller.start(
                scope = this,
                page = { cursor -> readPage(directory, shellID, cursor) },
                onState = { output -> foldOutput(shellID, output) },
            )
        }
        refreshStatus(shellID)
    }

    /** Stops following and closes the pane, which is what the pane's own button means. */
    fun closeCommand() {
        stopFollowing()
        _state.value = _state.value.copy(openID = null)
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
        val set = dataSets.active.value ?: return
        val directory = _state.value.directory ?: return
        viewModelScope.launch {
            val info = set.execution.commands.shell(directory, shellID).getOrNull() ?: return@launch
            if (info.status.value != "running") poller.exit(info.status.value, info.exit)
            foldOutput(shellID, poller.state())
        }
    }

    /**
     * One page of output, or `null` when the command is gone.
     *
     * The route answers `{location, data}` and a `404` is the end of the stream, not a failure, so
     * [dev.opencode.android.core.data.execution.ExecutionCommands.shellOutput] flattens it and the
     * poller decides what that means.
     */
    private suspend fun readPage(directory: String, shellID: String, cursor: Long): ShellOutput? {
        val set = dataSets.active.value ?: return null
        return set.execution.commands.shellOutput(directory, shellID, cursor.toString())
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
        val set = dataSets.active.value ?: return
        val target = state.killTarget ?: return
        _state.value = state.copy(killTarget = null, running = true)
        viewModelScope.launch {
            val error = set.execution.commands.killShell(directory, target).exceptionOrNull()?.toActionError()
            _state.value = _state.value.copy(running = false, error = error?.message)
            if (error == null && _state.value.openID == target) {
                poller.stop()
                _state.value = _state.value.copy(openID = null)
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun foldOutput(shellID: String, output: ShellOutputState) {
        _state.value = _state.value.copy(
            rows = _state.value.rows.map { row ->
                if (row.id == shellID) {
                    row.copy(
                        output = output,
                        info = row.info.copy(
                            status = output.status?.let(::ShellStatus) ?: row.info.status,
                            exit = output.exitCode ?: row.info.exit,
                        ),
                    )
                } else {
                    row
                }
            },
        )
    }

    private fun rowOf(info: ShellInfo): ShellRow {
        val current = _state.value.rows.firstOrNull { it.id == info.id }
        return if (current?.output != null) {
            current.copy(info = info)
        } else {
            ShellRow(info = info)
        }
    }

    private fun stopFollowing() {
        followJob?.cancel()
        followJob = null
        poller.stop()
    }

    override fun onCleared() {
        stopFollowing()
        openLocations.set(null)
        super.onCleared()
    }
}
