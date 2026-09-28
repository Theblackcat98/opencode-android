package dev.opencode.android.feature.sessions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.attention.OpenSessionTracker
import dev.opencode.android.core.data.attention.SessionViewMarker
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.data.server.PagingState
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.SessionFilter
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.data.server.SessionStore
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.data.server.TimelinePaging
import dev.opencode.android.core.data.server.TimelineStore
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the session list draws, and nothing else. */
data class SessionListUiState(
    val rows: List<SessionRow> = emptyList(),
    val projects: List<Project> = emptyList(),
    val search: String = "",
    val filter: SessionFilter = SessionFilter.Roots,
    val paging: PagingState = PagingState(),
    /** The clock the relative timestamps are computed against, so rows do not re-render per second. */
    val now: Long = 0L,
)

/** Everything the session screen draws. */
data class SessionUiState(
    val title: String = "",
    val agent: String? = null,
    val model: SessionModelUi? = null,
    val cost: Double = 0.0,
    /** The last step's tokens against the model's context limit, for the gauge. */
    val contextUsed: Long = 0L,
    val contextLimit: Long = 0L,
    val activity: SessionActivityUi = SessionActivityUi.Idle,
    /** The provider's retry, with its countdown and its call to action, or `null`. */
    val retry: RetryUi? = null,
    /** The clock the countdown is computed against, so the banner does not hold a timer. */
    val now: Long = 0L,
    val messages: List<SessionMessage> = emptyList(),
    val paging: TimelinePaging = TimelinePaging(),
    val following: Boolean = true,
    val serverId: String? = null,
    val directory: String? = null,
    val offline: Boolean = false,
) {
    val contextPercent: Int get() = if (contextLimit > 0) ((contextUsed * 100) / contextLimit).toInt().coerceIn(0, 100) else 0
}

data class SessionModelUi(
    val label: String,
    val variant: String?,
)

sealed interface SessionActivityUi {
    data object Idle : SessionActivityUi

    data object Running : SessionActivityUi

    data class Retrying(val attempt: Int, val next: Long) : SessionActivityUi
}

/**
 * The per-server home.
 *
 * Which server is being followed is the connection manager's answer, not a parameter the navigation
 * graph has to keep in step with, so a deep link into a server that is not active yet still lands
 * on the right home.
 */
class HomeViewModel @Inject constructor(
    private val connectionManager: ServerConnectionManager,
    private val servers: ServerRepository,
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    val serverName: StateFlow<String?> = combine(
        connectionManager.activeConnection,
        servers.observeServers(),
    ) { connection, profiles ->
        connection?.serverProfile?.name
            ?: profiles.firstOrNull { it.id == connection?.serverProfile?.id }?.name
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** Follows [serverId] and loads its session list. */
    fun select(serverId: String?) {
        serverId?.let { connectionManager.setActiveServer(it) }
        dataSets.active.value?.sessions?.start()
    }
}

/**
 * The session list, derived from [SessionStore].
 *
 * The ViewModel owns no state of its own beyond the search text: everything else is a projection of
 * the store, so the list cannot disagree with the events that produced it (plan §4.2, "The server
 * echoes; the client never guesses").
 */
class SessionListViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val searchText = MutableStateFlow("")

    /**
     * A coarse clock.
     *
     * The list's relative timestamps are derived, so they need a value to derive them from. Ticking
     * once a minute is enough for a "3m" label and costs nothing during a live turn.
     */
    private val ticks: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(TICK_MILLIS)
        }
    }

    /**
     * Everything the store contributes, folded so the outer `combine` stays within its arity.
     *
     * `combine` has typed overloads up to five sources, and a list view needs more than that, so
     * the store's parts are folded first.
     */
    private val rows: Flow<List<SessionRow>> =
        dataSets.active.map { set -> set?.sessions?.visibleRows?.value ?: emptyList<SessionRow>() }

    private val projects: Flow<List<Project>> =
        dataSets.active.map { set -> set?.projects?.state?.value?.value ?: emptyList<Project>() }

    private val filter: Flow<SessionFilter> =
        dataSets.active.map { set -> set?.sessions?.filter?.value ?: SessionFilter.Roots }

    private val paging: Flow<PagingState> =
        dataSets.active.map { set -> set?.sessions?.paging?.value ?: PagingState() }

    private val content: Flow<ListSnapshot> = combine(rows, projects) { list, all ->
        ListSnapshot(rows = list, projects = all)
    }

    private val fromStore: Flow<ListSnapshot> = combine(content, filter, paging) { snapshot, active, progress ->
        snapshot.copy(filter = active, paging = progress)
    }

    val state: StateFlow<SessionListUiState> = combine(
        fromStore,
        searchText,
        ticks,
    ) { snapshot, search, now ->
        SessionListUiState(
            rows = snapshot.rows.filterBySearch(search),
            projects = snapshot.projects,
            search = search,
            filter = snapshot.filter,
            paging = snapshot.paging,
            now = now,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SessionListUiState(),
    )

    fun setSearch(text: String) {
        searchText.value = text
        val current = set?.sessions?.filter?.value ?: return
        set?.sessions?.setFilter(current.copy(search = text.takeIf(String::isNotBlank)))
    }

    fun toggleRootsOnly() {
        val current = set?.sessions?.filter?.value ?: return
        set?.sessions?.setFilter(current.copy(rootsOnly = !current.rootsOnly))
    }

    fun selectProject(projectId: String?) {
        val current = set?.sessions?.filter?.value ?: return
        set?.sessions?.setFilter(current.copy(projectId = projectId))
    }

    fun selectDirectory(directory: String?) {
        val current = set?.sessions?.filter?.value ?: return
        set?.sessions?.setFilter(current.copy(directory = directory))
    }

    fun loadMore() {
        set?.sessions?.loadMore()
    }

    private val set: ServerDataSet? get() = dataSets.active.value
}

/** The store's own part of the list, before the search text is applied. */
private data class ListSnapshot(
    val rows: List<SessionRow> = emptyList(),
    val projects: List<Project> = emptyList(),
    val filter: SessionFilter = SessionFilter.Roots,
    val paging: PagingState = PagingState(),
)

/**
 * The search text, applied to rows the store could not filter.
 *
 * The store filters a *page* server-side, but a session created while the list is open arrives
 * through an event and never went through a query, so the term is applied here too. Without this a
 * live session would appear in an unfiltered list and not in a filtered one.
 */
private fun List<SessionRow>.filterBySearch(search: String): List<SessionRow> {
    val term = search.trim()
    if (term.isEmpty()) return this
    return filter { it.title.contains(term, ignoreCase = true) }
}

/**
 * One open session: the header, the timeline, and follow mode.
 *
 * **The header's context gauge is the last step's tokens against the model's `limit.context`**
 * (plan §6). Both come from the server: the tokens from the session's own projection and the limit
 * from the location's model catalog, which is why the set keeps a models catalog per directory.
 */
class SessionViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val openSessions: OpenSessionTracker,
    clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val follow = MutableStateFlow(true)
    private val openSession = MutableStateFlow<String?>(null)

    /**
     * Whether this screen is the one in front of the user.
     *
     * **The fact `session.view` needs, and the only place it can come from.** The event stream says
     * a turn went idle; it does not say anyone read it. "Resumed on this session" is the closest the
     * app gets to "actually saw the idle transition", and the plan asks for the call at exactly that
     * moment, so the marker is set here and read by [SessionViewMarker].
     */
    private val onScreen = MutableStateFlow(false)

    val sessionId: StateFlow<String?> = openSession

    /**
     * A coarse clock for the retry countdown.
     *
     * Ticking once a second is what a countdown needs and is cheap: it is one emission a second on
     * one flow, and it only matters while a retry is actually scheduled.
     */
    private val ticks: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(TICK_MILLIS)
        }
    }

    val state: StateFlow<SessionUiState> = combine(
        openSession,
        dataSets.active,
        follow,
        ticks,
    ) { id, set, following, now ->
        if (id == null || set == null) return@combine SessionUiState(following = following, now = now)
        val store: TimelineStore = set.timeline(id)
        val info = set.sessions.info.value[id]
        val directory = info?.location?.directory
        val modelLimit = directory
            ?.let { set.models(it).value }
            ?.contextLimitFor(info.model)
        SessionUiState(
            title = info?.title?.takeIf(String::isNotBlank) ?: "",
            agent = info?.agent,
            model = info?.model?.let { SessionModelUi(it.toString(), it.variant) },
            cost = info?.cost ?: 0.0,
            contextUsed = store.lastStepTokens(),
            contextLimit = modelLimit ?: 0L,
            activity = set.activityOf(id),
            retry = set.sessions.status.value[id]?.retryOrNull,
            now = now,
            messages = store.state.value.messages,
            paging = store.paging.value,
            following = following,
            serverId = set.serverId,
            directory = directory,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SessionUiState(),
    )

    /** Opens a session: loads its timeline and its row. */
    fun open(sessionID: String) {
        val set = dataSets.active.value ?: return
        openSession.value = sessionID
        set.timeline(sessionID).start()
        viewModelScope.launch { set.sessions.loadSession(sessionID) }
    }

    /**
     * The session screen came forward.
     *
     * Publishes which session is on screen — the notification layer suppresses what the user is
     * already looking at — and lets the marker below record the idle transition as seen.
     */
    fun onScreenResumed() {
        onScreen.value = true
        openSessions.set(openSession.value)
    }

    /** The session screen went away, so nothing it shows is on screen any more. */
    fun onScreenLeft() {
        onScreen.value = false
        openSessions.set(null)
    }

    init {
        viewModelScope.launch {
            combine(
                openSession,
                dataSets.active.flatMapLatest { set -> set?.sessions?.info ?: flowOf(emptyMap()) },
                onScreen,
            ) { id, info, screen -> Triple(id, info, screen) }
                .collect { (id, info, screen) ->
                    if (!screen || id == null) return@collect
                    val set = dataSets.active.value ?: return@collect
                    val mark = SessionViewMarker.requestFor(info[id]) ?: return@collect
                    // The badge is not cleared optimistically: `session.viewed` is the server saying
                    // it has recorded the transition, which is the plan's "the server echoes" rule
                    // applied to the client's own write.
                    set.commands.markViewed(mark.sessionId, mark.idleAtMillis)
                }
        }
    }

    fun setFollowing(following: Boolean) {
        follow.value = following
    }

    fun loadOlder() {
        openSession.value?.let { dataSets.active.value?.timeline(it)?.loadMore() }
    }
}

/** The context limit a model offers, from the location's catalog. */
private fun List<ModelInfo>.contextLimitFor(model: dev.opencode.android.core.model.ModelRef?): Long {
    if (model == null) return 0L
    return firstOrNull { it.providerID == model.providerID && it.id == model.id }?.limit?.context ?: 0L
}

/** The tokens the newest completed step used, which is what the gauge divides. */
private fun TimelineStore.lastStepTokens(): Long {
    val assistant = state.value.messages
        .asReversed()
        .firstOrNull { it is dev.opencode.android.core.model.SessionMessage.Assistant }
        ?: return 0L
    return (assistant as dev.opencode.android.core.model.SessionMessage.Assistant).tokens?.total ?: 0L
}

/**
 * The header's activity chip.
 *
 * The retry state wins over the running one: a provider retry is a running session that the user
 * needs to know about, and a plain spinner would hide the countdown.
 */
private fun ServerDataSet.activityOf(sessionID: String): SessionActivityUi {
    val status = sessions.status.value[sessionID]
    if (status is SessionStatus.Retry) return SessionActivityUi.Retrying(status.attempt, status.next)
    if (status is SessionStatus.Busy) return SessionActivityUi.Running
    val live = sessions.activity.value[sessionID] ?: return SessionActivityUi.Idle
    return when (live) {
        is SessionActivity.Retrying -> SessionActivityUi.Retrying(live.attempt, live.next)
        is SessionActivity.Running -> SessionActivityUi.Running
        is SessionActivity.Idle, is SessionActivity.Unknown -> SessionActivityUi.Idle
    }
}

private const val STOP_TIMEOUT_MILLIS = 5_000L
private const val TICK_MILLIS = 1_000L
