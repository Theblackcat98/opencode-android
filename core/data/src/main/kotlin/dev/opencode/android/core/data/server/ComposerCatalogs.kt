package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.sync.ResourceKey
import dev.opencode.android.core.data.sync.SyncedResource
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.model.ReferenceInfo
import dev.opencode.android.core.model.SkillInfo
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The three file-based catalogs the composer completes from: commands, skills and references.
 *
 * They are [SyncedResource]s for the same reason the agents and models are — they are location
 * scoped, they are invalidated by an empty-payload `*.updated` event, and a `server.connected`
 * resync has to re-read them because the client has no record of what it missed. `command.updated`,
 * `skill.updated` and `reference.updated` are exactly the three events Phase 5 acts on first
 * (plan §8).
 *
 * They live in one file because they have nothing in common with each other except that they are
 * small, read-only and complete at once: a list of names with an optional description.
 */
class ComposerCatalogs(
    private val serverId: String,
    private val api: ServerApi,
    private val scope: CoroutineScope,
) {
    private val commandLists = ConcurrentHashMap<String, SyncedResource<List<CommandInfo>>>()
    private val skillLists = ConcurrentHashMap<String, SyncedResource<List<SkillInfo>>>()
    private val referenceLists = ConcurrentHashMap<String, SyncedResource<List<ReferenceInfo>>>()

    /** `command.list`, including MCP prompts named `<server>:<prompt>`. */
    fun commands(directory: String): SyncedResource<List<CommandInfo>> = commandLists.getOrPut(directory) {
        SyncedResource(
            key = ResourceKey(serverId, directory),
            name = "command.list",
            scope = scope,
            loader = { api.listCommands(directory).data },
        )
    }

    /** `skill.list`. The body of every skill travels with the list, because the server sends it. */
    fun skills(directory: String): SyncedResource<List<SkillInfo>> = skillLists.getOrPut(directory) {
        SyncedResource(
            key = ResourceKey(serverId, directory),
            name = "skill.list",
            scope = scope,
            loader = { api.listSkills(directory).data },
        )
    }

    /** `reference.list`: local directories and cloned git repositories. */
    fun references(directory: String): SyncedResource<List<ReferenceInfo>> = referenceLists.getOrPut(directory) {
        SyncedResource(
            key = ResourceKey(serverId, directory),
            name = "reference.list",
            scope = scope,
            loader = { api.listReferences(directory).data },
        )
    }

    /** The `fs.find` search behind `@` completion. */
    val files: FileSearch = FileSearch(api, scope)

    /** Re-reads every catalog this client has opened, which is what a reconnect has to do. */
    fun resync() {
        commandLists.values.forEach { it.invalidate() }
        skillLists.values.forEach { it.invalidate() }
        referenceLists.values.forEach { it.invalidate() }
    }

    /** Invalidates the catalogs one event names, or all of them when it names no location. */
    fun invalidate(directory: String?) {
        if (directory == null) {
            resync()
        } else {
            commandLists[directory]?.invalidate()
            skillLists[directory]?.invalidate()
            referenceLists[directory]?.invalidate()
        }
    }

    /** Drops one location's catalogs, which is what `location.shutdown` does to everything else. */
    fun dropLocation(directory: String) {
        commandLists.remove(directory)?.clear()
        skillLists.remove(directory)?.clear()
        referenceLists.remove(directory)?.clear()
    }

    fun clear() {
        commandLists.values.forEach { it.clear() }
        skillLists.values.forEach { it.clear() }
        referenceLists.values.forEach { it.clear() }
        commandLists.clear()
        skillLists.clear()
        referenceLists.clear()
        files.clear()
    }
}

/** What the file search is showing. */
data class FileSearchState(
    val directory: String? = null,
    val query: String = "",
    val results: List<FileSystemEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = !loading && error == null && results.isEmpty()
}

/**
 * The ranked recursive search behind `@` completion (`fs.find`, features doc §27).
 *
 * **A store, not a picker.** The phone has no idea what is in the machine running the server, so
 * the results are the server's ranking and the client's only jobs are to debounce the keystrokes,
 * to bound the query, and to keep the previous results visible while the next load runs — a list
 * that empties itself between two keystrokes is worse than one that is briefly out of date.
 *
 * One search at a time: a new query cancels the previous request, so typing a long path costs the
 * calls for the paths that were being typed before it, not one per keystroke after it.
 */
class FileSearch(
    private val api: ServerApi,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {
    private val _state = MutableStateFlow(FileSearchState())
    val state: StateFlow<FileSearchState> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * Searches [directory] for [query], replacing anything in flight.
     *
     * A blank query clears the results rather than asking the server for everything: the
     * completion list for a bare `@` is the agents and the references, which the client already
     * has, and a recursive search of a whole repository is not what a user opening a mention wants
     * to pay for.
     */
    fun search(directory: String, query: String, type: String? = null) {
        job?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _state.value = FileSearchState(directory = directory, query = "")
            return
        }
        if (trimmed.length > MAX_QUERY_CHARS) {
            // A very long query is a paste, not a path. Searching for it would send the paste to
            // the server as a query, so the results are left empty and the list says so.
            _state.value = FileSearchState(directory = directory, query = trimmed)
            return
        }
        _state.value = _state.value.copy(directory = directory, query = trimmed, loading = true, error = null)
        job = scope.launch {
            delay(debounceMillis)
            runSearch(directory, trimmed, type)
        }
    }

    /** The same search, awaited, which is what a test needs and a picker can use directly. */
    suspend fun searchNow(directory: String, query: String, type: String? = null): List<FileSystemEntry> {
        job?.cancel()
        val trimmed = query.trim()
        _state.value = FileSearchState(directory = directory, query = trimmed, loading = trimmed.isNotEmpty())
        if (trimmed.isEmpty()) {
            _state.value = FileSearchState(directory = directory)
            return emptyList()
        }
        return runSearch(directory, trimmed, type)
    }

    /** Clears the search, which is what leaving the composer does. */
    fun clear() {
        job?.cancel()
        _state.value = FileSearchState()
    }

    private suspend fun runSearch(directory: String, query: String, type: String?): List<FileSystemEntry> = try {
        val found = api.findFiles(directory = directory, query = query, type = type, limit = LIMIT.toString()).data
        // A response for a query the user has already typed past is not the current answer.
        if (_state.value.query == query && _state.value.directory == directory) {
            _state.value = FileSearchState(directory = directory, query = query, results = found)
        }
        found
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        val failure: ActionError = error.toActionError()
        if (_state.value.query == query) {
            _state.value = _state.value.copy(loading = false, error = failure.message)
        }
        emptyList()
    }

    companion object {
        /**
         * Long enough to swallow a burst of keystrokes, short enough that a deliberate pause still
         * feels like the list is answering.
         */
        const val DEFAULT_DEBOUNCE_MILLIS = 250L

        /** The number of rows the client will show, so the server is not asked for a screenful. */
        const val LIMIT = 20

        /** Longer than any path a person types; longer still means a paste, not a path. */
        const val MAX_QUERY_CHARS = 512
    }
}
