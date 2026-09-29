package dev.opencode.android.core.data.server

import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.Outcome
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionRevert
import dev.opencode.android.core.model.SessionStatus
import dev.opencode.android.core.model.TokenUsage
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.event.SessionAgentSelected
import dev.opencode.android.core.model.event.SessionCreated
import dev.opencode.android.core.model.event.SessionDeleted
import dev.opencode.android.core.model.event.SessionExecutionFailed
import dev.opencode.android.core.model.event.SessionExecutionInterrupted
import dev.opencode.android.core.model.event.SessionExecutionStarted
import dev.opencode.android.core.model.event.SessionExecutionSucceeded
import dev.opencode.android.core.model.event.SessionForked
import dev.opencode.android.core.model.event.SessionIdle
import dev.opencode.android.core.model.event.SessionMetadataUpdated
import dev.opencode.android.core.model.event.SessionModelSelected
import dev.opencode.android.core.model.event.SessionMoved
import dev.opencode.android.core.model.event.SessionPermissions
import dev.opencode.android.core.model.event.SessionRenamed
import dev.opencode.android.core.model.event.SessionRevertCleared
import dev.opencode.android.core.model.event.SessionRevertCommitted
import dev.opencode.android.core.model.event.SessionRevertStaged
import dev.opencode.android.core.model.event.SessionStatusUpdated
import dev.opencode.android.core.model.event.SessionUsageUpdated
import dev.opencode.android.core.model.event.SessionViewed
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The session list: one server's sessions, their live state, and the events that change them.
 *
 * **Live insert, update and remove.** A `session.*` event carries the projection for what changed,
 * so the store applies it to [info] rather than refetching. A session appears in the list the moment
 * it is created on the desktop and disappears the moment it is deleted, with no polling.
 *
 * **Badges from three sources.** [activity] merges `session.active` (read on every resync) with
 * `session.status` and the `session.execution.*` events, and unread is the plan's rule verbatim:
 * `time.idle > time.viewed`.
 *
 * **Paging.** [visibleRows] is driven by the sessions the client has seen, and [loadMore] asks the
 * server for the next page of the active filter. Filtering is applied here rather than only in the
 * query, because [SessionFilter.rootsOnly] cannot be answered by a cursor-paged list and a session
 * can move between filters while it is open.
 */
class SessionStore(
    private val serverId: String,
    private val api: ServerApi,
    private val scope: CoroutineScope,
    private val cache: ReadCacheStore,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    private val _info = MutableStateFlow<Map<String, SessionInfo>>(emptyMap())

    /** Every session this client knows about, from pages, individual reads and events. */
    val info: StateFlow<Map<String, SessionInfo>> = _info.asStateFlow()

    private val _activity = MutableStateFlow<Map<String, SessionActivity>>(emptyMap())
    val activity: StateFlow<Map<String, SessionActivity>> = _activity.asStateFlow()

    private val _status = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    val status: StateFlow<Map<String, SessionStatus>> = _status.asStateFlow()

    private val _filter = MutableStateFlow(SessionFilter.Roots)
    val filter: StateFlow<SessionFilter> = _filter.asStateFlow()

    private val _paging = MutableStateFlow(PagingState())
    val paging: StateFlow<PagingState> = _paging.asStateFlow()

    private val pages = HashMap<SessionFilter, SessionPage>()

    /** Direct children per session id, derived from the sessions the client has seen. */
    val childCounts: StateFlow<Map<String, Int>> = _info
        .map { sessions ->
            sessions.values.mapNotNull { it.parentID }.groupingBy { it }.eachCount()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val rows: StateFlow<List<SessionRow>> = combine(
        _info,
        _activity,
        _status,
        childCounts,
    ) { sessions, activity, status, children ->
        sessions.values
            .map { session ->
                SessionRow(
                    session = session,
                    activity = activity[session.id] ?: SessionActivity.Idle,
                    status = status[session.id],
                    childCount = children[session.id] ?: 0,
                )
            }
            .sortedByDescending { it.updated }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The rows the active filter admits. */
    val visibleRows: StateFlow<List<SessionRow>> = combine(rows, _filter) { all, filter ->
        all.filter { filter.matches(it.session) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Sets the filter and loads its first page, reusing an already loaded one. */
    fun setFilter(filter: SessionFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        _paging.value = PagingState()
        scope.launch { page(filter).refresh() }
    }

    /** Loads the active filter's first page, showing the cached copy first when there is one. */
    fun start() {
        val filter = _filter.value
        scope.launch {
            if (page(filter).state.value.value == null) hydrateFromCache(filter)
            page(filter).sync()
        }
    }

    /** Asks for the next page of the active filter. A no-op while one is in flight or exhausted. */
    fun loadMore() {
        val current = _paging.value
        if (current.loading || !current.hasMore) return
        _paging.value = current.copy(loading = true)
        scope.launch {
            val active = page(_filter.value)
            active.loadMore()
            _paging.value = active.paging.value
        }
    }

    /** Refreshes the active filter and the active-session snapshot: what a resync does. */
    fun resync() {
        val filter = _filter.value
        scope.launch { page(filter).refresh() }
        _paging.value = PagingState()
        scope.launch { refreshActive() }
    }

    /** Reads `session.active`, the authoritative answer to "what is running right now". */
    suspend fun refreshActive() {
        val running = runCatching { api.listActiveSessions() }.getOrNull()?.data?.running ?: return
        val next = HashMap(_activity.value)
        next.entries.removeIf { (id, value) -> value == SessionActivity.Running && id !in running }
        running.forEach { next[it] = SessionActivity.Running }
        if (next != _activity.value) _activity.value = next
    }

    /** Reads one session over REST, for a deep link or a session the list has not paged in. */
    suspend fun loadSession(sessionID: String): SessionInfo? {
        val loaded = runCatching { api.getSession(sessionID) }.getOrNull()?.data ?: return _info.value[sessionID]
        put(loaded)
        return loaded
    }

    /**
     * Applies one event.
     *
     * Returns true when the event changed something, so a caller can fold a frame's worth of events
     * into a single state publication.
     */
    fun apply(event: Event): Boolean {
        val payload = event.payload
        val sessionID = (payload as? EventPayload.SessionScoped)?.sessionID
        val touched = event.created
        return when (payload) {
            is SessionCreated -> {
                // The create event carries the projection, so the row appears without a fetch.
                val previous = _info.value[payload.sessionID]
                put(
                    SessionInfo(
                        id = payload.sessionID,
                        parentID = payload.parentID,
                        projectID = payload.projectID,
                        agent = payload.agent,
                        model = payload.model,
                        cost = previous?.cost ?: 0.0,
                        tokens = previous?.tokens ?: TokenUsage.Zero,
                        outcome = previous?.outcome,
                        time = SessionInfo.Time(
                            created = payload.sessionID.let { previous?.time?.created ?: touched ?: 0L },
                            updated = touched ?: previous?.time?.updated ?: 0L,
                        ),
                        title = payload.title ?: previous?.title,
                        subpath = payload.subpath,
                        metadata = payload.metadata,
                        permissions = payload.permissions,
                        location = LocationPublicRef(payload.location.directory),
                    ),
                )
                true
            }

            is SessionDeleted -> {
                val id = sessionID ?: return false
                val removed = _info.value[id]
                _info.value = _info.value - id
                _activity.value = _activity.value - id
                _status.value = _status.value - id
                if (removed != null) {
                    scope.launch { runCatching { cache.deleteSession(serverId, id) } }
                }
                removed != null
            }

            is SessionRenamed -> update(sessionID, touched) { it.copy(title = payload.title) }

            is SessionAgentSelected -> update(sessionID, touched) { it.copy(agent = payload.agent) }

            is SessionModelSelected -> update(sessionID, touched) { it.copy(model = payload.model) }

            is SessionMetadataUpdated -> update(sessionID, touched) { it.copy(metadata = payload.metadata) }

            is SessionPermissions -> update(sessionID, touched) { it.copy(permissions = payload.permissions) }

            is SessionMoved -> update(sessionID, touched) {
                it.copy(
                    location = LocationPublicRef(payload.location.directory),
                    projectID = payload.projectID,
                    subpath = payload.subpath,
                )
            }

            is SessionUsageUpdated -> update(sessionID, touched) {
                it.copy(cost = payload.cost, tokens = payload.tokens)
            }

            is SessionViewed -> update(sessionID, touched) { it.copy(time = it.time.copy(viewed = payload.idle)) }

            is SessionIdle -> update(sessionID, touched) { it.copy(time = it.time.copy(idle = touched)) }

            is SessionExecutionStarted -> {
                setActivity(sessionID, SessionActivity.Running)
                true
            }

            is SessionExecutionSucceeded -> finishTurn(sessionID, touched, Outcome.Succeeded)

            is SessionExecutionFailed -> finishTurn(sessionID, touched, Outcome.Failed)

            is SessionExecutionInterrupted -> finishTurn(sessionID, touched, Outcome.Interrupted)

            is SessionStatusUpdated -> {
                val id = sessionID ?: return false
                _status.value = _status.value + (id to payload.status)
                setActivity(
                    id,
                    when (val status = payload.status) {
                        is SessionStatus.Retry -> SessionActivity.Retrying(
                            attempt = status.attempt,
                            next = status.next,
                            message = status.message,
                            action = status.action,
                        )

                        SessionStatus.Busy -> SessionActivity.Running

                        SessionStatus.Idle -> SessionActivity.Idle

                        is SessionStatus.Unknown -> _activity.value[id] ?: SessionActivity.Unknown
                    },
                )
                true
            }

            is SessionRevertStaged -> update(sessionID, null) { it.copy(revert = payload.revert) }

            is SessionRevertCleared -> update(sessionID, null) { it.copy(revert = null) }

            is SessionRevertCommitted -> update(sessionID, null) { it.copy(revert = null) }

            // A fork creates a different session, which arrives with its own session.created.
            is SessionForked -> false

            else -> false
        }
    }

    /** Forgets everything, for example when the server goes away. */
    fun clear() {
        _info.value = emptyMap()
        _activity.value = emptyMap()
        _status.value = emptyMap()
        pages.clear()
        _paging.value = PagingState()
    }

    /**
     * Applies [transform] to one session and records the instant the server touched it.
     *
     * `time.updated` is what the list is ordered by, and the server only ever moves it forward, so
     * a live event that carries a timestamp has to advance it or a session would keep the position
     * of the page it arrived in.
     */
    private fun update(sessionID: String?, touched: Long?, transform: (SessionInfo) -> SessionInfo): Boolean {
        if (sessionID == null) return false
        val current = _info.value[sessionID] ?: return false
        val transformed = transform(current)
        val next = if (touched != null && touched > current.time.updated) {
            transformed.copy(time = transformed.time.copy(updated = touched))
        } else {
            transformed
        }
        if (next == current) return false
        _info.value = _info.value + (sessionID to next)
        scope.launch { runCatching { cache.writeSession(serverId, sessionID, next) } }
        return true
    }

    private fun put(session: SessionInfo) {
        _info.value = _info.value + (session.id to session)
        scope.launch { runCatching { cache.writeSession(serverId, session.id, session) } }
    }

    private fun finishTurn(sessionID: String?, touched: Long?, outcome: Outcome): Boolean {
        if (sessionID == null) return false
        setActivity(sessionID, SessionActivity.Idle)
        return update(sessionID, touched) {
            it.copy(outcome = outcome, time = it.time.copy(idle = touched ?: it.time.idle))
        }
    }

    private fun setActivity(sessionID: String?, activity: SessionActivity) {
        if (sessionID == null) return
        if (_activity.value[sessionID] == activity) return
        _activity.value = _activity.value + (sessionID to activity)
    }

    private fun page(filter: SessionFilter): SessionPage = pages.getOrPut(filter) {
        SessionPage(serverId, api, scope, cache, filter, pageSize) { sessions ->
            _info.value = _info.value + sessions.associateBy { it.id }
        }
    }

    private suspend fun hydrateFromCache(filter: SessionFilter) {
        val cached = runCatching { cache.readSessions(serverId, filter.directory) }.getOrNull().orEmpty()
        if (cached.isEmpty()) return
        _info.value = _info.value + cached.associateBy { it.id }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}

/** Paging progress of the active filter. */
data class PagingState(
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

/** A staged revert, so the store can clear it without the ambiguity of a nullable `copy`. */
internal fun SessionInfo.withoutRevert(): SessionInfo = if (revert == null) this else copy(revert = null)

/** A staged revert, applied. */
internal fun SessionInfo.withRevert(value: SessionRevert?): SessionInfo =
    if (revert == value) this else copy(revert = value)
