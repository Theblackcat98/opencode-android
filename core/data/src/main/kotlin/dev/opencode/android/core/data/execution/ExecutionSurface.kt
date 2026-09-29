package dev.opencode.android.core.data.execution

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.ProjectUpdateRequest
import dev.opencode.android.core.model.SessionMoveRequest
import dev.opencode.android.core.model.ShellInfo
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.ShellExited
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * One server's whole execution surface, keyed the way the routes are.
 *
 * **Three scopes, three reasons.**
 *
 *  - Shells and terminals are keyed by **directory**, because `shell.list` and `pty.list` are
 *    location-scoped and killing a row of the wrong checkout's list would kill the wrong process.
 *  - Worktrees are keyed by **project**, because `worktree.list` takes a required `projectID` and
 *    carries no location at all.
 *  - Persistent terminals are keyed by **session**, because they belong to one session and survive
 *    the attachment.
 *
 * The three are deliberately *not* merged: one key that covered all of them would be a key that does
 * not name what it identifies, and the bug it would allow — showing another checkout's shells — is
 * exactly the kind this client has to not have.
 *
 * **It also owns the aggregate the attention layer reads.** A completion notification is about a
 * command in a location the user is not looking at, and the attention coordinator needs every such
 * command at once rather than only the ones belonging to the directory a panel happens to have open.
 * [shells] is that union, and it is a `combine` of the stores' own flows rather than a second copy.
 */
class ExecutionSurface(
    private val serverId: String,
    private val api: ServerApi,
    private val scope: CoroutineScope,
) {
    private val stores = ConcurrentHashMap<String, ExecutionStore>()

    private var aggregate: Job? = null

    /**
     * The store for a location, built and started on first use.
     *
     * The aggregate is rebuilt here rather than only in [open]: a store can be reached by anything that
     * holds the surface — the shells panel, the terminal panel, a notification action — and a location
     * whose shells were never aggregated would be a location the completion notifications are blind to.
     */
    fun at(directory: String): ExecutionStore {
        val existing = stores[directory]
        if (existing != null) return existing
        return ExecutionStore(serverId, directory, api, scope).also {
            it.start()
            stores[directory] = it
            restartShellAggregate()
        }
    }

    /** The locations this client has opened, which is what a resync and a shutdown walk. */
    val directories: Set<String> get() = stores.keys.toSet()

    /** `worktree.list` and its live events, per project. */
    val worktrees: WorktreeStore = WorktreeStore(serverId, api, scope)

    /** A session's persistent terminals, behind the experimental switch and the capability probe. */
    val terminals: SessionTerminalStore = SessionTerminalStore(api)

    /** The writes. One object, so the two screens that can kill a command agree on what that is. */
    val commands: ExecutionCommands = ExecutionCommands(api) { stores }

    /** The worktree writes, which have a two-step `force` the rest do not. */
    val worktreeCommands: WorktreeCommands = WorktreeCommands(api)

    private val _projectError = MutableStateFlow<ActionError?>(null)

    /** The last failed `project.update` or `session.move`, which a sheet shows once. */
    val projectError: StateFlow<ActionError?> = _projectError.asStateFlow()

    private val _shells = MutableStateFlow<List<ShellInfo>>(emptyList())

    /**
     * Every command this client knows about, across the locations it has opened.
     *
     * The attention layer reads this: a command that finishes while the user is somewhere else is
     * what a completion notification is for, and the alternative — the coordinator opening a location
     * to ask — would make a background notification depend on a screen.
     */
    val shells: StateFlow<List<ShellInfo>> = _shells.asStateFlow()

    private val _finishedShells = MutableStateFlow<List<FinishedShell>>(emptyList())

    /**
     * The completions this client has observed and not yet shown the user.
     *
     * **A ledger, because the server cannot answer "has this been seen".** A shell command has no
     * `time.viewed` and no unread rule; `shell.list` answers with the *running* commands, so the
     * moment a command ends is the moment it leaves the list and the only record that it ended is
     * the event this client processed. That is why the entry is kept here, bounded, and dropped when
     * the user opens the panel for its location — which is the same contract `session.view` gives
     * turns, arrived at from the other direction.
     */
    val finishedShells: StateFlow<List<FinishedShell>> = _finishedShells.asStateFlow()

    /**
     * Records a completion, from a `shell.exited` event or from a `shell.get` the panel read.
     *
     * The id is the key, so the same command reaching this twice — an event and then the refetch the
     * event triggers — is one entry. [MAX_FINISHED] bounds it, dropping the oldest, because a server
     * that runs hundreds of short commands while the app is closed would otherwise grow this without
     * limit and notify about all of them at once.
     */
    fun recordFinished(shell: FinishedShell) {
        val next = _finishedShells.value.filterNot { it.id == shell.id } + shell
        _finishedShells.value = if (next.size > MAX_FINISHED) next.takeLast(MAX_FINISHED) else next
    }

    /**
     * Drops a location's completions, which is what opening its shell panel means.
     *
     * Called by the panel when it opens, and by nothing else: a user who was not looking at a
     * command's output has not seen it, and pretending otherwise is how a notification ends up
     * describing something the user has already dealt with.
     */
    fun markFinishedSeen(directory: String) {
        val next = _finishedShells.value.filterNot { it.directory == directory }
        if (next != _finishedShells.value) _finishedShells.value = next
    }

    /** How many completions are waiting to be announced, which the settings screen can show. */
    fun pendingFinishedCount(): Int = _finishedShells.value.size

    init {
        // Re-published as one list, recomputed only when a store's own list actually changed, so a
        // busy location cannot make the coordinator redraw for every appended line of output. A store
        // that is created after this ran contributes through the collector being restarted, which is
        // why [open] restarts it rather than relying on the map being read once.
        restartShellAggregate()
    }

    private fun restartShellAggregate() {
        aggregate?.cancel()
        aggregate = scope.launch {
            val flows = stores.values.map { it.shells }
            if (flows.isEmpty()) return@launch
            combine(flows) { lists -> lists.toList().flatten() }
                .collect { all -> _shells.value = all }
        }
    }

    /**
     * Starts or refreshes the location a screen is about to show.
     *
     * Called by the panel when it opens rather than by the set's own `start()`, because a client that
     * has never opened a location has no business listing its commands: `shell.list` is cheap, but
     * it is still a request per location for a screen nobody is looking at.
     */
    fun open(directory: String) {
        at(directory)
    }

    fun resync() {
        stores.values.forEach { it.resync() }
    }

    /**
     * Drops a location's execution state, which is what `location.shutdown` means.
     *
     * The store is removed rather than cleared so a later open re-reads it: a location that came back
     * is a new service instance on the server, and its commands are not the ones that were cleared.
     */
    fun dropLocation(directory: String) {
        stores.remove(directory)?.clear()
    }

    /** Applies one event to every store, and to the ones that are not per location. */
    fun apply(event: Event) {
        val named = event.location?.directory
        val store = named?.let { stores[it] }
        if (store != null) {
            store.apply(event)
            recordExit(event, store)
        } else {
            stores.values.forEach {
                it.apply(event)
                recordExit(event, it)
            }
        }
        worktrees.apply(event)
        terminals.apply(event)
    }

    /**
     * Writes a completion into the ledger.
     *
     * **A `shell.exited` with no location is not recorded.** The server sends the scoped events with
     * one, and a completion with no checkout behind it could not be opened by the notification that
     * reports it, so guessing a directory would produce a notification that leads nowhere.
     */
    private fun recordExit(event: Event, store: ExecutionStore) {
        val exited = event.payload as? ShellExited ?: return
        recordFinished(
            FinishedShell(
                id = exited.id,
                command = store.shells.value.firstOrNull { it.id == exited.id }?.command.orEmpty(),
                status = exited.status.value,
                exitCode = exited.exit,
                directory = store.directory,
                completedAtMillis = event.created ?: 0L,
            ),
        )
    }

    fun clear() {
        stores.values.forEach { it.clear() }
        stores.clear()
        worktrees.clear()
        _shells.value = emptyList()
        _finishedShells.value = emptyList()
    }

    /**
     * `project.update`: name, icon, start command and the canonical checkout.
     *
     * The answer is the updated project, so the caller replaces what it holds rather than waiting for
     * `project.updated` — which is the same arrangement the Phase 3 writes use for a body the server
     * hands back.
     */
    suspend fun updateProject(
        projectID: String,
        name: String? = null,
        icon: Project.Icon? = null,
        commands: Project.Commands? = null,
        canonical: String? = null,
    ): Result<Project> = try {
        val updated = api.updateProject(
            projectID,
            ProjectUpdateRequest(canonical = canonical, name = name, icon = icon, commands = commands),
        )
        Result.success(updated)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        val actionError = error.toActionError()
        _projectError.value = actionError
        Result.failure(ActionFailure(actionError))
    }

    /**
     * `session.move`: the session into a worktree, or into any other directory.
     *
     * [delivery] is the same mode the composer sends, and it is passed through rather than decided
     * here: a move that silently changed how a queued prompt would be delivered would be a change
     * the user did not ask for and cannot see.
     */
    suspend fun moveSession(
        sessionID: String,
        directory: String,
        delivery: Delivery? = null,
    ): Result<Unit> = try {
        api.moveSession(sessionID, SessionMoveRequest(directory = directory, delivery = delivery))
        Result.success(Unit)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        val actionError = error.toActionError()
        _projectError.value = actionError
        Result.failure(ActionFailure(actionError))
    }

    fun dismissProjectError() {
        _projectError.value = null
    }

    /** Whether the experimental session terminals may be offered right now. */
    fun terminalsUsable(allowedBySetting: Boolean): Boolean = terminals.usable(allowedBySetting)

    companion object {
        /**
         * How many completions are remembered.
         *
         * Twenty is a screenful of notifications; beyond that the user is not going to read them, and
         * the ones that fall off the end are the oldest, which is the right thing to lose.
         */
        const val MAX_FINISHED: Int = 20
    }
}

/** A command that finished, as the ledger and the notification hold it. */
data class FinishedShell(
    val id: String,
    val command: String,
    val status: String,
    val exitCode: Int?,
    val directory: String,
    val completedAtMillis: Long,
)
