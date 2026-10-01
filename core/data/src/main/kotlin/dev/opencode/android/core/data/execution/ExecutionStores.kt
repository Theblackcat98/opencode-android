package dev.opencode.android.core.data.execution

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.CapabilityPolicy
import dev.opencode.android.core.data.capability.ExperimentalRoute
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.sync.ResourceKey
import dev.opencode.android.core.data.sync.SyncedResource
import dev.opencode.android.core.model.PtyCreateRequest
import dev.opencode.android.core.model.PtySize
import dev.opencode.android.core.model.PtyTicketToken
import dev.opencode.android.core.model.PtyUpdateRequest
import dev.opencode.android.core.model.ShellCreateRequest
import dev.opencode.android.core.model.ShellInfo
import dev.opencode.android.core.model.ShellOption
import dev.opencode.android.core.model.ShellOutput
import dev.opencode.android.core.model.WorktreeCreateRequest
import dev.opencode.android.core.model.WorktreeDirectory
import dev.opencode.android.core.model.WorktreeFailure
import dev.opencode.android.core.model.WorktreeRefreshRequest
import dev.opencode.android.core.model.WorktreeRemoveRequest
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.PtyCreated
import dev.opencode.android.core.model.event.PtyDeleted
import dev.opencode.android.core.model.event.PtyExited
import dev.opencode.android.core.model.event.PtyInfo
import dev.opencode.android.core.model.event.PtyStatus
import dev.opencode.android.core.model.event.PtyUpdated
import dev.opencode.android.core.model.event.ShellCreated
import dev.opencode.android.core.model.event.ShellDeleted
import dev.opencode.android.core.model.event.ShellExited
import dev.opencode.android.core.model.event.WorktreeResolved
import dev.opencode.android.core.model.event.WorktreeUpdated
import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap

/**
 * One location's shells and terminals (features doc §30, §31).
 *
 * **Both are per location, because the server scopes both by location.** `shell.list` and `pty.list`
 * answer for the directory in `location[directory]`, so a store that was not per directory would
 * show the commands of the checkout the user last looked at on a screen belonging to another one —
 * and killing a row there would kill the wrong command. One store per directory, created on demand,
 * is the only arrangement that cannot do that.
 *
 * **The events are live inserts, not invalidations, for the things the events carry.**
 * `shell.created` and `pty.created` bring the whole object, so a command started on the desktop
 * appears in an open panel without a round trip, and `shell.exited` and `pty.exited` carry the status
 * the row shows. A resync — which is what `server.connected` triggers — re-reads both lists, because
 * a reconnect means the client has no idea what it missed.
 */
class ExecutionStore(
    private val serverId: String,
    val directory: String,
    private val api: ServerApi,
    private val scope: CoroutineScope,
) {
    private val _shells = MutableStateFlow<List<ShellInfo>>(emptyList())

    /** The commands running in this location, newest first. The agent's own commands are here too. */
    val shells: StateFlow<List<ShellInfo>> = _shells.asStateFlow()

    private val _ptys = MutableStateFlow<List<PtyInfo>>(emptyList())

    /** The terminals of this location, exited ones included until they are removed. */
    val ptys: StateFlow<List<PtyInfo>> = _ptys.asStateFlow()

    private val _capabilities = MutableStateFlow<Map<ExperimentalRoute, RouteAvailability>>(emptyMap())
    val capabilities: StateFlow<Map<ExperimentalRoute, RouteAvailability>> = _capabilities.asStateFlow()

    private val _error = MutableStateFlow<ActionError?>(null)
    val error: StateFlow<ActionError?> = _error.asStateFlow()

    /**
     * The ids events have spoken about since a list load began, so that load can neither lose nor revive a row.
     *
     * **A load and an event write the same field, and the load used to win outright — in both directions.**
     * `shell.created` inserted the row immediately, and the `shell.list` already in flight published over the
     * top of it when it landed, carrying a list the server had answered *before* that command existed: the
     * command the user had just started left the list, silently, on any server slow enough for the two to
     * overlap, and came back only if something else refetched. The same shape was worse for terminals, where
     * it ran the other way: a `pty.deleted` that raced an in-flight `pty.list` left a terminal the server had
     * already killed sitting in the list, because the load put back what the event had removed.
     *
     * **Both directions are needed, because both are wrong.** A row an event created is not in a list that was
     * requested before it existed, so it is kept; a row an event deleted is still in one, so it is dropped. A
     * load is only allowed to overwrite a row no event has mentioned, and both sets are emptied once it has,
     * because from that point the server's own answer is at least as new as the events this store has seen.
     *
     * One ledger per list, and guarded because a load publishes on an OkHttp thread while events arrive on the
     * collector's. `lock` is the store's, shared by both ledgers, because both are written by `apply`.
     */
    private val shellLedger = Ledger()
    private val ptyLedger = Ledger()
    private val lock = Any()

    /** `shell.list`, published into [shells] as well so an event and a refetch cannot disagree. */
    val shellList: SyncedResource<List<ShellInfo>> = SyncedResource(
        key = ResourceKey(serverId, directory),
        name = "shell.list($directory)",
        scope = scope,
        loader = { api.listShells(directory).data },
        onValue = { value -> publish(_shells, value, shellLedger) { it.id } },
    )

    /** `pty.list`. */
    val ptyList: SyncedResource<List<PtyInfo>> = SyncedResource(
        key = ResourceKey(serverId, directory),
        name = "pty.list($directory)",
        scope = scope,
        loader = { api.listPtys(directory).data },
        onValue = { value -> publish(_ptys, value, ptyLedger) { it.id } },
    )

    /**
     * Publishes a loaded list, folded in with what the events have said.
     *
     * **The publish and the merge are one locked step, and must be.** An event writes the row and then the
     * ledger; a load reads the ledger and then writes the row. If the two were not atomic, a load could
     * merge against an empty ledger in the window between an event's write and its ledger entry and then
     * overwrite the row — which is the very bug this ledger exists to stop, reintroduced one step later.
     */
    private fun <T> publish(
        into: MutableStateFlow<List<T>>,
        listed: List<T>,
        ledger: Ledger,
        idOf: (T) -> String,
    ) = synchronized(lock) {
        into.value = ledger.merge(listed, into.value, idOf)
    }

    /**
     * Runs an event's change to a list and its ledger entry as one locked step.
     *
     * The pairing is the whole point: a row the event has just written must be visible to the merge that
     * runs next, and the id it has just named must be visible too, or one of the two is missed. Which of the
     * two ledger entries an event makes is its own business, so this takes the change rather than an id.
     */
    private fun atomically(change: () -> Unit) = synchronized(lock) { change() }

    /**
     * What events have said about one list since its last load, and the merge of a load with that.
     *
     * @param merge the loaded list, the rows on screen now, and how a row names itself — one implementation
     *   for shells and terminals, which differ in nothing else here.
     */
    private class Ledger {

        private val held = mutableSetOf<String>()
        private val dropped = mutableSetOf<String>()

        /** An event named this row, so a load that predates it must not overwrite it. */
        fun named(id: String) = synchronized(this) {
            held += id
            dropped -= id
        }

        /** An event removed this row, so a load that predates the removal must not bring it back. */
        fun removed(id: String) = synchronized(this) {
            dropped += id
            held -= id
        }

        /** Everything this location has forgotten, which is what `location.shutdown` means. */
        fun forget() = synchronized(this) {
            held.clear()
            dropped.clear()
        }

        /**
         * The loaded list with the events folded in: rows a create event added are kept, rows a delete event
         * removed are taken out, and everything else is the server's own answer.
         *
         * The event's own version wins over the list's, because the list is older by construction — it was
         * requested before the event and cannot reflect it. The sets are emptied here, because this load is
         * now as new as the events that led to it, and a later one may be believed outright.
         */
        fun <T> merge(listed: List<T>, current: List<T>, idOf: (T) -> String): List<T> = synchronized(this) {
            val keep = held.toSet()
            val remove = dropped.toSet()
            held.clear()
            dropped.clear()
            val listedIDs = listed.mapTo(mutableSetOf(), idOf)
            val kept = keep.filterNot { it in listedIDs }.mapNotNull { id ->
                current.firstOrNull { idOf(it) == id }
            }
            listed.filterNot { idOf(it) in remove } + kept
        }
    }

    fun start() {
        scope.launch { shellList.sync() }
        scope.launch { ptyList.sync() }
    }

    fun resync() {
        shellList.invalidate()
        ptyList.invalidate()
    }

    /** Forgets this location's shells and terminals, which is what `location.shutdown` means. */
    fun clear() {
        shellList.clear()
        ptyList.clear()
        _shells.value = emptyList()
        _ptys.value = emptyList()
        // The ledgers go with the rows: this location has nothing left, and a load that lands after a
        // shutdown must not bring back a command the user was told had gone.
        atomically {
            shellLedger.forget()
            ptyLedger.forget()
        }
    }

    /**
     * Applies one event.
     *
     * The answer is `true` when the event changed something, so a dispatcher can fold a frame's worth
     * of events into one publication. An event that names another location is not this store's: the
     * features doc sends the scoped ones with a location, and a panel showing a command from a
     * different checkout would be a bug rather than a feature.
     */
    fun apply(event: Event): Boolean {
        val payload = event.payload
        val named = event.location?.directory
        if (named != null && named != directory) return false
        return when (payload) {
            is ShellCreated -> atomically {
                _shells.value = _shells.value.filterNot { it.id == payload.info.id } + payload.info
                shellLedger.named(payload.info.id)
            }.let { true }

            is ShellExited -> {
                _shells.value = _shells.value.map { shell ->
                    if (shell.id == payload.id) {
                        shell.copy(
                            status = payload.status,
                            exit = payload.exit ?: shell.exit,
                            time = shell.time.copy(completed = event.created),
                        )
                    } else {
                        shell
                    }
                }
                true
            }

            is ShellDeleted -> atomically {
                shellLedger.removed(payload.id)
                _shells.value = _shells.value.filterNot { it.id == payload.id }
            }.let { true }

            is PtyCreated -> atomically {
                record(payload.info)
                ptyLedger.named(payload.info.id)
            }.let { true }

            is PtyUpdated -> {
                _ptys.value = _ptys.value.map { pty -> if (pty.id == payload.info.id) payload.info else pty }
                true
            }

            is PtyExited -> {
                _ptys.value = _ptys.value.map { pty ->
                    if (pty.id == payload.id) pty.copy(status = PtyStatus.Exited, exitCode = payload.exitCode) else pty
                }
                true
            }

            is PtyDeleted -> atomically {
                // As for a shell: without this a load already in flight puts a killed terminal back.
                ptyLedger.removed(payload.id)
                _ptys.value = _ptys.value.filterNot { it.id == payload.id }
            }.let { true }

            else -> false
        }
    }

    /**
     * Puts a command the server has described into [shells], unless the list already has it.
     *
     * Idempotent, and never a replacement: see [ExecutionCommands.runShell] for why an existing row wins.
     */
    fun recordIfAbsent(info: ShellInfo) {
        if (_shells.value.none { it.id == info.id }) _shells.value = _shells.value + info
    }

    /**
     * Puts a terminal the server has described into [ptys], in place of any row with the same id.
     *
     * **Idempotent on purpose, because the server says the same thing twice.** `pty.create` answers with the
     * whole terminal and `pty.created` then carries it again, and the two reach the client in either order.
     * Whichever comes second finds the row and replaces it with the same object, so the list neither misses
     * the terminal nor shows it twice. What is recorded is always the server's own description; nothing here
     * is a guess about a terminal the server has not confirmed.
     */
    fun record(info: PtyInfo) {
        _ptys.value = _ptys.value.filterNot { it.id == info.id } + info
    }

    /** Records what a failed call to an experimental route proved, or proved nothing. */
    fun recordCapability(route: ExperimentalRoute, error: ActionError?) {
        val availability = CapabilityPolicy.from(error?.kind) ?: return
        _capabilities.value = _capabilities.value + (route to availability)
    }

    /** Whether [route] may be called: the switch is on and no call has found it absent. */
    fun isUsable(route: ExperimentalRoute, allowedBySetting: Boolean): Boolean {
        if (!allowedBySetting) return false
        return CapabilityPolicy.isUsable(_capabilities.value[route] ?: RouteAvailability.Unknown)
    }

    fun dismissError() {
        _error.value = null
    }
}

/**
 * The writes of the execution surface, and the capability ledger the experimental routes need.
 *
 * **The writes live here rather than in the screens** so the two places that can kill a command — the
 * shell panel and a notification action — cannot disagree about what killing means, and so a failure
 * is recorded in one place. Reads are [ExecutionStore]s; this is the seam.
 */
class ExecutionCommands(
    private val api: ServerApi,
    private val stores: () -> Map<String, ExecutionStore>,
) {
    private val _error = MutableStateFlow<ActionError?>(null)

    /** The last failed call, which a screen shows once and then dismisses. */
    val error: StateFlow<ActionError?> = _error.asStateFlow()

    /**
     * `shell.create`: runs a command in a location. Not idempotent, so the panel re-reads.
     *
     * **The answer puts the command in the list if the event has not.** `shell.create` answers with the
     * whole [ShellInfo] and `shell.created` then carries it again on the event stream, in either order
     * with the answer. A caller that opens the command as soon as it has the answer would otherwise look
     * for a row that is not there yet. The row is only added when it is *absent*: an event that won the
     * race may already have moved the command on, and replacing that row with the answer's older
     * "running" would put a finished command back to work.
     */
    suspend fun runShell(directory: String, command: String, cwd: String? = null): Result<ShellInfo> =
        call { api.createShell(directory, ShellCreateRequest(command = command, cwd = cwd)).data }
            .onSuccess { info -> stores()[directory]?.recordIfAbsent(info) }

    /** `shell.remove`: kills a running command and drops it. */
    suspend fun killShell(directory: String, id: String): Result<Unit> =
        call { api.removeShell(id, directory) }

    /**
     * `shell.output`, the page after [cursor].
     *
     * **`null` means the command is gone**, which is the ordinary end of a stream rather than a
     * failure: the server answers `404` for a command it no longer has, and a poller treats "no more"
     * and "there was never any" identically, which is what they are. Any other failure is thrown as an
     * [ActionFailure] and is *not* recorded in [error]: a poll runs every second or so, and a dialog
     * for each one that a phone dropped would say the same thing again before it could be dismissed.
     * The poller retries, and says so in its own state if it gives up.
     *
     * [cursor] is a string on the wire even though the schema calls it a number, and it is only ever a
     * cursor a previous page returned.
     */
    suspend fun shellOutput(
        directory: String,
        id: String,
        cursor: String,
        limit: String? = null,
    ): ShellOutput? = try {
        api.getShellOutput(id, directory, cursor, limit).data
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        val classified = error.toActionError()
        // The status decides, not only the kind: a real answer carries `_tag: ShellNotFoundError`, which is a
        // tag [ActionErrorKind] does not call "not found", so the body alone classifies it as a server fault.
        if (classified.kind == ActionErrorKind.NOT_FOUND || classified.httpStatus == HTTP_NOT_FOUND) {
            null
        } else {
            throw ActionFailure(classified)
        }
    }

    /** `shell.get`: one command's status and exit code, for a row whose event was missed. */
    suspend fun shell(directory: String, id: String): Result<ShellInfo> =
        call { api.getShell(id, directory).data }

    /**
     * `pty.create`: starts a terminal. A `null` [command] runs the location's configured shell.
     *
     * **The answer is the terminal, and it is recorded before the caller sees it.** The server's own
     * `pty.created` follows on the event stream, where the dispatcher buffers it until the next frame, so a
     * caller that went and looked the new id up in [ExecutionStore.ptys] would usually look before the event
     * had been applied. The response carries the whole [PtyInfo], so the list is brought up to date from it
     * here; the event then replaces the same row with the same object.
     */
    suspend fun createPty(
        directory: String,
        command: String? = null,
        args: List<String>? = null,
        title: String? = null,
    ): Result<PtyInfo> = call {
        api.createPty(directory, PtyCreateRequest(command = command, args = args, title = title)).data
    }.onSuccess { info -> stores()[directory]?.record(info) }

    /**
     * `pty.update` with a size, which is how a layout change reaches the terminal.
     *
     * Resizing goes over REST because the WebSocket frames are terminal output (features doc §31),
     * and a JSON frame in that stream would be output the client has to tell apart from a program's.
     */
    suspend fun resizePty(directory: String, id: String, size: PtySize): Result<PtyInfo> =
        call { api.updatePty(id, PtyUpdateRequest(size = size), directory).data }

    /** `pty.update` with a title; `pty.updated` then renames every open view of the terminal. */
    suspend fun renamePty(directory: String, id: String, title: String): Result<PtyInfo> =
        call { api.updatePty(id, PtyUpdateRequest(title = title), directory).data }

    /** `pty.remove`: kills the process and drops the terminal. */
    suspend fun removePty(directory: String, id: String): Result<Unit> =
        call { api.removePty(id, directory) }

    /**
     * `pty.connect.token`: a single-use ticket for a WebView that cannot send an `Authorization`
     * header. Never called for the OkHttp path, which uses Basic auth on the upgrade.
     */
    suspend fun ptyTicket(directory: String, id: String): Result<PtyTicketToken> =
        call { api.createPtyTicket(id, directory).data }

    /**
     * `pty.get`: one terminal's own state.
     *
     * **Re-read when a terminal is opened, not while it is open.** The list gives a row enough to
     * show a title, and the socket reports the output; the fields neither carries are the ones that
     * decide what the screen does on resume — whether the process is still running, its exit code,
     * and its size. A terminal the user comes back to after the app was killed has to be asked
     * about, because the events that said it exited went while the client was gone.
     */
    suspend fun pty(directory: String, id: String): Result<PtyInfo> = call { api.getPty(id, directory).data }

    /** `config.shell`: the shells the server found, for the terminal's shell picker. */
    suspend fun shellOptions(): Result<List<ShellOption>> = call { api.listShellOptions() }

    /** `config.shell`, cached per server: it describes the server's `PATH`, not a checkout. */
    private var shellOptionsCache: List<ShellOption>? = null

    suspend fun cachedShellOptions(): Result<List<ShellOption>> {
        shellOptionsCache?.let { return Result.success(it) }
        val result = shellOptions()
        result.getOrNull()?.let { shellOptionsCache = it }
        return result
    }

    /** The capability a store holds for a route, or `Unknown` when it has never called it. */
    fun capability(directory: String, route: ExperimentalRoute): RouteAvailability =
        stores()[directory]?.capabilities?.value?.get(route) ?: RouteAvailability.Unknown

    fun dismissError() {
        _error.value = null
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
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
}

/**
 * A failed call, carrying the classified [ActionError] so a caller need not classify it again.
 * The one [ActionFailure], in the action package; see its note for why.
 */
typealias ActionFailure = dev.opencode.android.core.data.action.ActionFailure

/**
 * One project's worktree inventory and its live state (features doc §29).
 *
 * **One store per project, because the route is per project.** `worktree.list` takes a *required*
 * `projectID` and carries no location parameter at all, so a per-directory store would be a lie
 * about where the answer came from. The live events name a `projectID` too, so they route the same
 * way.
 *
 * **Both events mean "re-read".** `worktree.updated`'s payload is only a `projectID`, and
 * `worktree.resolved` names a directory but not the list it changed. One request per project cannot
 * be wrong, which is the trade the plan asks for on events that do not carry the answer.
 */
class WorktreeStore(
    private val serverId: String,
    private val api: ServerApi,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(WorktreeState())
    val state: StateFlow<WorktreeState> = _state.asStateFlow()

    private val projects =
        ConcurrentHashMap<String, SyncedResource<List<WorktreeDirectory>>>()

    /** The worktrees of one project, cached per project so a second screen does not re-read. */
    fun list(projectID: String): SyncedResource<List<WorktreeDirectory>> = projects.getOrPut(projectID) {
        SyncedResource(
            key = ResourceKey(serverId, projectID),
            name = "worktree.list($projectID)",
            scope = scope,
            loader = { api.listWorktrees(projectID) },
            onValue = { value -> _state.value = _state.value.copy(directories = value, projectID = projectID) },
        )
    }

    fun start(projectID: String) {
        scope.launch { list(projectID).sync() }
    }

    fun resync(projectID: String) {
        list(projectID).invalidate()
    }

    fun apply(event: Event): Boolean = when (val payload = event.payload) {
        is WorktreeUpdated -> {
            resync(payload.projectID)
            true
        }

        is WorktreeResolved -> {
            _state.value = _state.value.copy(
                resolved = _state.value.resolved + ResolvedWorktree(
                    projectID = payload.projectID,
                    directory = payload.directory,
                    previous = payload.previous,
                    adopted = payload.adopted.orEmpty(),
                ),
            )
            resync(payload.projectID)
            true
        }

        else -> false
    }

    fun clear() {
        projects.values.forEach { it.clear() }
        projects.clear()
        _state.value = WorktreeState()
    }
}

/** What the worktree panel shows. */
data class WorktreeState(
    val projectID: String? = null,
    val directories: List<WorktreeDirectory> = emptyList(),
    /** The adoptions the server reported, oldest first, which is what "resolved" means. */
    val resolved: List<ResolvedWorktree> = emptyList(),
) {
    /** The directories as a picker lists them, with the project id dropped. */
    val labels: List<String> get() = directories.map { it.directory }
}

/** One `worktree.resolved` adoption: a directory the server found and took ownership of. */
data class ResolvedWorktree(
    val projectID: String,
    val directory: String,
    val previous: String,
    val adopted: List<String>,
)

/**
 * The worktree writes (features doc §29).
 *
 * **The force flag is the caller's decision, and this class is what makes it possible to ask.** A
 * worktree with uncommitted work answers `400` with a `WorktreeError` whose `forceRequired` is true;
 * the first call sends `force = false`, and only a user who has been told what forcing does gets a
 * second one. [remove] is therefore the only method that has a two-step shape, and
 * [WorktreeRemoval.Refused] is what the panel shows in between.
 */
class WorktreeCommands(
    private val api: ServerApi,
) {
    /** `worktree.create`. Runs the project's setup script, which the confirmation says out loud. */
    suspend fun create(
        projectID: String,
        from: String? = null,
        branch: String? = null,
        name: String? = null,
        directory: String? = null,
    ): Result<WorktreeDirectory> = call {
        api.createWorktree(WorktreeCreateRequest(projectID, from, branch, directory, name))
    }

    /** `worktree.refresh`: rediscovers and reconciles. */
    suspend fun refresh(projectID: String): Result<Unit> = call {
        api.refreshWorktrees(WorktreeRefreshRequest(projectID))
    }

    /**
     * `worktree.remove`.
     *
     * A `400` whose body is a `WorktreeError` becomes [WorktreeRemoval.Refused] with the server's own
     * message and its `forceRequired` hint, which is what lets the panel ask the second question
     * instead of guessing. Any other `400` is a plain failure.
     */
    suspend fun remove(
        projectID: String,
        directory: String,
        force: Boolean,
    ): Result<WorktreeRemoval> = try {
        api.removeWorktree(WorktreeRemoveRequest(projectID, directory, force))
        Result.success(WorktreeRemoval.Removed)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        val http = error as? HttpException
        val parsed = http?.response()?.errorBody()?.string()?.let { body ->
            runCatching { OpenCodeJson.decodeFromString(WorktreeFailure.serializer(), body) }.getOrNull()
        }?.takeIf { it.isWorktreeError() }
        if (parsed != null) {
            Result.success(
                WorktreeRemoval.Refused(
                    message = parsed.data.message,
                    forceRequired = parsed.data.forceRequired ?: true,
                ),
            )
        } else {
            Result.failure(ActionFailure(error.toActionError()))
        }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }

    private suspend inline fun <T> call(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(ActionFailure(error.toActionError()))
    }
}

/** The outcome of removing a worktree, which is three things and not two. */
sealed interface WorktreeRemoval {
    /** The worktree is gone. */
    data object Removed : WorktreeRemoval

    /**
     * The server refused, and [forceRequired] says whether asking again with `force` would work.
     *
     * The message is the server's own, which is the only description of *what* is in the way that
     * this client could not invent.
     */
    data class Refused(val message: String, val forceRequired: Boolean) : WorktreeRemoval
}
