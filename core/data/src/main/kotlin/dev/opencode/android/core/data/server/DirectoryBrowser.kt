package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The directory browser behind "pick a location" (`fs.list`, plan §6, New session).
 *
 * **A projection of the server's filesystem, not a local one.** The phone has no idea what is in the
 * machine running the server, and a `..` entry or a `../../..` spelling in a listing has to be
 * interpreted the way the server does. So the store keeps the path the server gave it and asks
 * again with the path it gave back, rather than joining and normalizing locally: a guess here would
 * be a path the server never offered.
 *
 * The three ways to pick a location the plan lists are all in one place: the projects and recent
 * directories come from the catalogs, and this is the browser for anything else.
 *
 * It belongs to a [ServerDataSet] rather than being a singleton, because `fs.list` is a call on one
 * server's API and a singleton would hold whichever server was first asked.
 */
class DirectoryBrowser(
    private val api: ServerApi,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    /**
     * Lists [path] inside [directory].
     *
     * A `null` path lists the location itself. A failure keeps the previous listing and records the
     * error, because a directory the user cannot read is a normal outcome and an empty browser with
     * no explanation is not a useful one.
     */
    fun list(directory: String, path: String? = null) {
        scope.launch { listAndWait(directory, path) }
    }

    /** The same call, awaited, which is what a test and a create-session flow need. */
    suspend fun listAndWait(directory: String, path: String? = null): ActionError? {
        _state.value = _state.value.copy(directory = directory, path = path, loading = true, error = null)
        return try {
            val listing = api.listDirectory(directory, path)
            _state.value = BrowserState(
                directory = directory,
                path = path,
                entries = listing.data,
                loading = false,
            )
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val failure = error.toActionError()
            _state.value = _state.value.copy(loading = false, error = failure.message)
            failure
        }
    }

    /** Closes the browser, which is what leaving the picker does. */
    fun clear() {
        _state.value = BrowserState()
    }
}

/** What the browser shows right now. */
data class BrowserState(
    /** The location the entries are relative to, which is also the session's location. */
    val directory: String? = null,
    /** The path inside that location, `null` for its root. */
    val path: String? = null,
    val entries: List<FileSystemEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** Directories first, then files, each alphabetically: what a file browser is expected to do. */
    val sorted: List<FileSystemEntry>
        get() = entries.sortedWith(
            compareByDescending<FileSystemEntry> { it.isDirectory }.thenBy { it.name.lowercase() },
        )
}
