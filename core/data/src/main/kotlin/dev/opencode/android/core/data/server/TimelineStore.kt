package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.timeline.PendingInboxItem
import dev.opencode.android.core.data.timeline.TimelineConvergence
import dev.opencode.android.core.data.timeline.TimelineDivergence
import dev.opencode.android.core.data.timeline.TimelineReducer
import dev.opencode.android.core.data.timeline.TimelineState
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.SessionExecutionFailed
import dev.opencode.android.core.model.event.SessionExecutionInterrupted
import dev.opencode.android.core.model.event.SessionExecutionSucceeded
import dev.opencode.android.core.model.event.SessionStatusUpdated
import dev.opencode.android.core.model.SessionStatus
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One session's timeline: the REST projection, the cached copy of it, and the event stream on top.
 *
 * **The load order is cache, then server, then events.** The cache is shown first so a session
 * opens instantly and reads offline; the server's page replaces it, because the server is
 * authoritative and the cache may be days old. Events are folded in either way, and the reducer is
 * idempotent for the frames a projection already contains, so an event that arrives while the REST
 * read is in flight is not lost or double-counted.
 *
 * **Load older** is a cursor walk over `session.message.list`, which the server returns newest
 * first. The store keeps the messages oldest first, so a page is reversed and prepended; a page
 * that overlaps what is already held is dropped, which is what makes a resync during paging safe.
 */
class TimelineStore(
    private val serverId: String,
    val sessionID: String,
    private val api: ServerApi,
    private val scope: CoroutineScope,
    private val cache: ReadCacheStore,
    private val selfCheck: TimelineSelfCheck = TimelineSelfCheck.Noop,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    private val _state = MutableStateFlow(TimelineState.Empty)
    val state: StateFlow<TimelineState> = _state.asStateFlow()

    private val _paging = MutableStateFlow(TimelinePaging())
    val paging: StateFlow<TimelinePaging> = _paging.asStateFlow()

    private var cursor: String? = null
    private var loading = false
    private var loadedFromServer = false
    private var cacheWrite: Job? = null

    /** Loads the cached window and then the server's newest page. */
    fun start() {
        scope.launch {
            if (!loadedFromServer) {
                hydrateFromCache()
                loadFirstPage()
            }
        }
    }

    /** Reloads the newest page over REST: the resync path, and the path after a reconnect. */
    fun resync() {
        scope.launch { loadFirstPage() }
    }

    /** Fetches the next older page. A no-op while one is in flight or the server is exhausted. */
    fun loadMore() {
        if (loading || cursor == null) return
        loading = true
        _paging.value = _paging.value.copy(loading = true, error = null)
        scope.launch {
            val next = cursor
            try {
                val page = api.listMessages(sessionID, limit = pageSize, order = "desc", cursor = next)
                cursor = page.cursor.next
                val held = _state.value.index
                val older = page.data.asReversed().filter { held[it.id] == null }
                if (older.isNotEmpty()) {
                    val messages = older + _state.value.messages
                    _state.value = TimelineState.of(messages, _state.value.pending)
                    scheduleCacheWrite(messages)
                }
                _paging.value = _paging.value.copy(loading = false, hasMore = cursor != null)
            } catch (error: Throwable) {
                _paging.value = _paging.value.copy(loading = false, error = error.message)
            } finally {
                loading = false
            }
        }
    }

    /**
     * Folds one event into the timeline.
     *
     * Returns true when the state changed, so a frame's worth of events can be published at once.
     */
    fun apply(event: Event): Boolean {
        val before = _state.value
        val after = TimelineReducer.reduce(before, event, sessionID)
        if (after === before) return false
        _state.value = after
        scheduleCacheWrite(after.messages)
        onTurnEnded(event)
        return true
    }

    /** Forgets the timeline, for example when the session is deleted. */
    fun clear() {
        cacheWrite?.cancel()
        cacheWrite = null
        _state.value = TimelineState.Empty
        cursor = null
        loadedFromServer = false
        _paging.value = TimelinePaging()
    }

    /**
     * Shows a pending inbox item before the server has confirmed it.
     *
     * This is the plan's one permitted piece of optimism (plan §4.2), and it is safe for the same
     * reason it is useful: the item carries the `msg_…` id the prompt was sent with, so the
     * `session.inbox.enqueued` event replaces this entry instead of adding a second one. The entry
     * is *not* written to the cache, because it is not the server's projection.
     */
    fun showPending(item: PendingInboxItem) {
        val current = _state.value
        if (current.pending.any { it.id == item.id }) return
        _state.value = current.copy(pending = current.pending + item)
    }

    /**
     * Removes a pending item this client invented.
     *
     * Used when the call that created it failed: the server emitted nothing, so nothing would
     * otherwise ever remove it, and a prompt that will never be sent must not sit in the
     * transcript looking like work in progress.
     */
    fun dropPending(id: String) {
        val current = _state.value
        if (current.pending.none { it.id == id }) return
        _state.value = current.copy(pending = current.pending.filterNot { it.id == id })
    }

    /**
     * Compares the reducer's state with the server's own projection and reports the difference.
     *
     * Run when a turn goes idle, which is the first moment the projection is final for that turn.
     * It is a debug aid, never a repair: the server is authoritative, so a difference is logged
     * and the timeline is left alone rather than being overwritten behind the user's back.
     */
    suspend fun verifyAgainstServer(): List<TimelineDivergence> {
        val reduced = _state.value.messages
        if (reduced.isEmpty()) return emptyList()
        val projected = runCatching {
            api.listMessages(sessionID, limit = VERIFY_PAGE_SIZE, order = "desc").data
        }.getOrNull() ?: return emptyList()
        val divergences = TimelineConvergence.compare(reduced, projected.asReversed())
        if (divergences.isNotEmpty()) selfCheck.report(sessionID, divergences)
        return divergences
    }

    private suspend fun loadFirstPage() {
        loading = true
        _paging.value = _paging.value.copy(loading = true, error = null)
        try {
            val page = api.listMessages(sessionID, limit = pageSize, order = "desc")
            val fetched = page.data.asReversed()
            cursor = page.cursor.next
            loadedFromServer = true
            // Replace rather than merge: the server's page is the projection, and merging would
            // be the guess the plan forbids.
            _state.value = TimelineState.of(fetched, _state.value.pending)
            scheduleCacheWrite(fetched)
            _paging.value = TimelinePaging(hasMore = cursor != null)
        } catch (error: Throwable) {
            _paging.value = _paging.value.copy(loading = false, error = error.message)
        } finally {
            loading = false
        }
    }

    private suspend fun hydrateFromCache() {
        val cached = runCatching { cache.readMessages(serverId, sessionID) }.getOrNull().orEmpty()
        if (cached.isEmpty()) return
        _state.value = TimelineState.of(cached, _state.value.pending)
        _paging.value = _paging.value.copy(hasMore = true)
    }

    /**
     * Writes the cache once the events stop arriving.
     *
     * A live turn produces a write per frame, and each one replaces the whole window; writing on
     * every frame would put a database transaction behind every token. Cancelling and restarting
     * the job coalesces a burst into a single write and still writes promptly, because a token
     * stream always has a gap before the turn ends.
     */
    private fun scheduleCacheWrite(messages: List<SessionMessage>) {
        cacheWrite?.cancel()
        cacheWrite = scope.launch {
            delay(CACHE_WRITE_DEBOUNCE_MILLIS)
            runCatching { cache.writeMessages(serverId, sessionID, messages) }
        }
    }

    /**
     * A finished turn is the moment to check the projection.
     *
     * Only when the timeline was actually loaded from the server: comparing a cached window with a
     * full projection would report every message the cache does not hold as missing.
     */
    private fun onTurnEnded(event: Event) {
        if (!event.endsATurn || !loadedFromServer || selfCheck is TimelineSelfCheck.Noop) return
        scope.launch { runCatching { verifyAgainstServer() } }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20

        /** How much of the projection the self-check compares. */
        const val VERIFY_PAGE_SIZE = 100

        /** How long a burst of events is allowed to delay the cache write. */
        const val CACHE_WRITE_DEBOUNCE_MILLIS = 750L
    }
}

/** Load-older progress. */
data class TimelinePaging(
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
)

/**
 * Where the self-check's findings go.
 *
 * An interface rather than a logger so the check can be a no-op in tests, and so a release build
 * can compile it out without touching the store.
 */
interface TimelineSelfCheck {
    fun report(sessionID: String, divergences: List<TimelineDivergence>)

    /** Used when the check is off: the release behaviour, and what a unit test asserts against. */
    data object Noop : TimelineSelfCheck {
        override fun report(sessionID: String, divergences: List<TimelineDivergence>) = Unit
    }
}

/**
 * True when the event announces that a turn has finished.
 *
 * A finished turn is the first moment its projection is final, so it is when the self-check has
 * something worth comparing.
 */
val Event.endsATurn: Boolean
    get() = when (val payload = this.payload) {
        is SessionExecutionSucceeded, is SessionExecutionFailed, is SessionExecutionInterrupted -> true
        is SessionStatusUpdated -> payload.status is SessionStatus.Idle
        else -> false
    }
