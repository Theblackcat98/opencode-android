package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.sync.SyncedResource
import dev.opencode.android.core.data.sync.ResourceKey
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One page-stream of `session.list` for a given [SessionFilter].
 *
 * `session.list` is cursor-paged, which is a one-way door: a cursor is a position in one ordering,
 * so a live event that changes `time.updated` cannot be expressed as an insert into the middle of
 * a page. The reference client has the same constraint and answers it the same way, by keeping the
 * union of what it has seen in one place ([SessionStore.info]) and using the page only to decide
 * *what to fetch next*. So this class holds the cursor and the accumulated ids, and hands the
 * sessions it fetched to the store.
 *
 * The accumulated set also makes `loadMore` idempotent: a page that overlaps what is already held
 * adds nothing.
 */
class SessionPage(
    private val serverId: String,
    private val api: ServerApi,
    private val scope: CoroutineScope,
    private val cache: ReadCacheStore,
    val filter: SessionFilter,
    private val pageSize: Int,
    private val onSessions: suspend (List<SessionInfo>) -> Unit,
) {
    private var cursor: String? = null
    private val seen = LinkedHashSet<String>()
    private var firstPage = true

    private val _paging = MutableStateFlow(PagingState())
    val paging: StateFlow<PagingState> = _paging.asStateFlow()

    val resource: SyncedResource<List<SessionInfo>> = SyncedResource(
        key = ResourceKey(serverId, filter.directory),
        name = "session.list(${filter.name()})",
        scope = scope,
        loader = ::fetch,
        onError = { error -> _paging.value = _paging.value.copy(error = error.message) },
    )

    val state: StateFlow<dev.opencode.android.core.data.sync.SyncedState<List<SessionInfo>>> get() = resource.state

    val hasMore: Boolean get() = cursor != null

    /** Loads the first page, replacing whatever the store has for this filter. */
    suspend fun sync() = resource.sync()

    /** Reloads the first page from scratch: a resync, or a filter the client already had. */
    suspend fun refresh() {
        cursor = null
        seen.clear()
        firstPage = true
        resource.invalidate()
        resource.sync(force = true)
    }

    /** Fetches the next page. A no-op once the server has run out of pages. */
    suspend fun loadMore() {
        if (!firstPage && cursor == null) {
            _paging.value = _paging.value.copy(loading = false, hasMore = false)
            return
        }
        _paging.value = _paging.value.copy(loading = true, error = null)
        try {
            fetch()
        } finally {
            _paging.value = _paging.value.copy(loading = false, hasMore = cursor != null)
        }
    }

    private suspend fun fetch(): List<SessionInfo> {
        val page = api.listSessions(
            limit = pageSize,
            order = "desc",
            search = filter.search?.takeIf { it.isNotBlank() },
            projectID = filter.projectId,
            directory = filter.directory,
            cursor = cursor,
        )
        cursor = page.cursor.next
        val fresh = page.data.filter { seen.add(it.id) }
        firstPage = false
        if (fresh.isNotEmpty()) {
            onSessions(fresh)
            runCatching { cache.writeSessions(serverId, filter.directory, fresh) }
        }
        _paging.value = _paging.value.copy(loading = false, hasMore = cursor != null, error = null)
        return fresh
    }
}

/** A short, stable name for a filter, for resource keys and log lines. */
fun SessionFilter.name(): String = buildString {
    append(if (rootsOnly) "roots" else "all")
    search?.takeIf { it.isNotBlank() }?.let { append("/q=").append(it) }
    projectId?.let { append("/project=").append(it.take(8)) }
    directory?.let { append("/dir=").append(it.takeLast(12)) }
}
