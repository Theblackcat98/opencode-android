package dev.opencode.android.feature.sessions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.attention.OpenSessionTracker
import dev.opencode.android.core.data.attention.SessionViewMarker
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.preferences.ProjectPreferences
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.data.server.PagingState
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.SessionFilter
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.data.server.SessionStore
import dev.opencode.android.core.data.server.TimelinePaging
import dev.opencode.android.core.data.server.TimelineStore
import dev.opencode.android.core.data.sync.SyncStatus
import dev.opencode.android.core.data.sync.SyncedState
import dev.opencode.android.core.model.ForkBoundary
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.SessionStatus
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the session list draws, and nothing else. */
data class SessionListUiState(
    val rows: List<SessionRow> = emptyList(),
    val projects: List<Project> = emptyList(),
    val search: String = "",
    val filter: SessionFilter = SessionFilter.Roots,
    val paging: PagingState = PagingState(),
    /** The server has not yet said what it has, so an empty list is not yet a fact. */
    val syncing: Boolean = false,
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
    /**
     * The project this session belongs to, which is what a worktree panel is keyed by.
     *
     * `worktree.list` takes a required `projectID` and carries no location at all, so the session
     * screen cannot answer it from the directory it already has — it needs the project the server
     * resolved, and that is a field of the session's own projection.
     */
    val projectId: String? = null,
    val offline: Boolean = false,
    /**
     * The message this session was forked at, or `null`.
     *
     * A fork boundary is the server's answer (`Session.fork`, schema `ForkBoundary`), and it is the
     * only record of where a copy came from. A session whose history stops at message 9 of another
     * one looks identical to a session that was always short, so the header says so.
     */
    val forkedAt: String? = null,
) {
    val contextPercent: Int get() = if (contextLimit >
        0
    ) {
        ((contextUsed * 100) / contextLimit).toInt().coerceIn(0, 100)
    } else {
        0
    }
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
@HiltViewModel
class HomeViewModel(
    private val connectionManager: ServerConnectionManager,
    private val servers: ServerRepository,
    private val dataSets: ServerDataRegistry,
    private val projectPreferences: ProjectPreferences,
    clock: () -> Long,
) : ViewModel() {
    /** Hilt's constructor: Dagger cannot supply the clock, so the production one is fixed here. */
    @Inject
    constructor(
        connectionManager: ServerConnectionManager,
        servers: ServerRepository,
        dataSets: ServerDataRegistry,
        projectPreferences: ProjectPreferences,
    ) : this(connectionManager, servers, dataSets, projectPreferences, System::currentTimeMillis)

    val serverName: StateFlow<String?> = combine(
        connectionManager.activeConnection,
        servers.observeServers(),
    ) { connection, profiles ->
        connection?.serverProfile?.name
            ?: profiles.firstOrNull { it.id == connection?.serverProfile?.id }?.name
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val ticks: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(TICK_MILLIS)
        }
    }

    /**
     * The projects on the home screen with their sessions.
     *
     * Everything follows the active data set, and the added ids are the client's own list for that server, so
     * switching server switches both at once.
     */
    val state: StateFlow<HomeUiState> = dataSets.active
        .flatMapLatest { set ->
            if (set == null) {
                flowOf(HomeUiState())
            } else {
                combine(
                    set.projects.state,
                    projectPreferences.added(set.serverId),
                    set.sessions.rows,
                ) { projects, added, rows ->
                    val known = projects.value.orEmpty()
                    HomeUiState(
                        serverId = set.serverId,
                        groups = homeGroups(known, added, rows),
                        available = known.filter { it.id !in added },
                        syncing = projects.value == null && projects.status !is SyncStatus.Failed,
                    )
                }
            }
        }
        .let { home -> combine(home, ticks) { state, now -> state.copy(now = now) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState())

    /** Follows [serverId] and loads its session list. */
    fun select(serverId: String?) {
        serverId?.let { connectionManager.setActiveServer(it) }
        dataSets.active.value?.sessions?.start()
    }

    /** Puts a project on the home screen. */
    fun addProject(projectId: String) {
        val serverId = dataSets.active.value?.serverId ?: return
        viewModelScope.launch { projectPreferences.add(serverId, projectId) }
    }

    /** Takes a project off the home screen. Its sessions stay on the server and in "All sessions". */
    fun removeProject(projectId: String) {
        val serverId = dataSets.active.value?.serverId ?: return
        viewModelScope.launch { projectPreferences.remove(serverId, projectId) }
    }
}

/**
 * The session list, derived from [SessionStore].
 *
 * The ViewModel owns no state of its own beyond the search text: everything else is a projection of
 * the store, so the list cannot disagree with the events that produced it (plan §4.2, "The server
 * echoes; the client never guesses").
 */
@HiltViewModel
class SessionListViewModel(
    private val dataSets: ServerDataRegistry,
    clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** Hilt's constructor: Dagger cannot supply the clock, so the production one is fixed here. */
    @Inject
    constructor(dataSets: ServerDataRegistry) : this(dataSets, System::currentTimeMillis)

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
    // Each of these follows the store, not the store's value at the moment the dataset became active.
    // Reading `.value` inside a `map` on `active` froze the list at whatever had loaded by then: the home
    // said "No sessions yet" on a server that was still connecting, and a new or renamed session never
    // appeared until the screen was recreated.
    private val rows: Flow<List<SessionRow>> =
        dataSets.active.flatMapLatest { set -> set?.sessions?.visibleRows ?: flowOf(emptyList()) }

    private val projects: Flow<SyncedState<List<Project>>> =
        dataSets.active.flatMapLatest { set -> set?.projects?.state ?: flowOf(SyncedState()) }

    private val filter: Flow<SessionFilter> =
        dataSets.active.flatMapLatest { set -> set?.sessions?.filter ?: flowOf(SessionFilter.Roots) }

    private val paging: Flow<PagingState> =
        dataSets.active.flatMapLatest { set -> set?.sessions?.paging ?: flowOf(PagingState()) }

    private val content: Flow<ListSnapshot> = combine(rows, projects) { list, all ->
        ListSnapshot(
            rows = list,
            projects = all.value.orEmpty(),
            // Nothing is known yet: the projects have neither arrived nor failed. An empty home is a
            // claim about the server, so it may only be made once the server has answered.
            syncing = all.value == null && all.status !is SyncStatus.Failed,
        )
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
            syncing = snapshot.syncing,
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
    val syncing: Boolean = false,
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
@HiltViewModel
class SessionViewModel(
    private val dataSets: ServerDataRegistry,
    private val openSessions: OpenSessionTracker,
    /** Where the bottom bar's Chat tab reads the last session on screen from. */
    private val projectPreferences: ProjectPreferences,
    clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** Hilt's constructor: Dagger cannot supply the clock, so the production one is fixed here. */
    @Inject
    constructor(
        dataSets: ServerDataRegistry,
        openSessions: OpenSessionTracker,
        projectPreferences: ProjectPreferences,
    ) : this(dataSets, openSessions, projectPreferences, System::currentTimeMillis)

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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<SessionUiState> = combine(
        openSession,
        dataSets.active,
        follow,
    ) { id, set, following ->
        Triple(id, set, following)
    }.flatMapLatest { (id, set, following) ->
        if (id == null || set == null) {
            flowOf(SessionUiState(following = following, now = clock()))
        } else {
            val store: TimelineStore = set.timeline(id)
            set.sessions.status.map { statuses ->
                statuses[id]?.retryOrNull
            }.flatMapLatest { retry ->
                val tickFlow = if (retry != null) {
                    flow {
                        while (true) {
                            emit(clock())
                            delay(TICK_MILLIS)
                        }
                    }
                } else {
                    flowOf(clock())
                }
                combine(
                    store.state,
                    store.paging,
                    set.sessions.info,
                    tickFlow,
                ) { timelineState, pagingState, infos, now ->
                    val info = infos[id]
                    val directory = info?.location?.directory
                    val modelLimit = directory
                        ?.let { set.models(it).value }
                        ?.contextLimitFor(info?.model)
                    SessionUiState(
                        title = info?.title?.takeIf(String::isNotBlank) ?: "",
                        agent = info?.agent,
                        model = info?.model?.let { SessionModelUi(it.toString(), it.variant) },
                        cost = info?.cost ?: 0.0,
                        contextUsed = store.lastStepTokens(),
                        contextLimit = modelLimit ?: 0L,
                        activity = set.activityOf(id),
                        retry = retry,
                        now = now,
                        messages = timelineState.messages,
                        paging = pagingState,
                        following = following,
                        serverId = set.serverId,
                        directory = directory,
                        projectId = info?.projectID,
                        forkedAt = (info?.fork as? ForkBoundary.Before)?.messageID
                            ?: (info?.fork as? ForkBoundary.Through)?.messageID,
                    )
                }
            }
        }
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
        viewModelScope.launch { projectPreferences.setLastSession(set.serverId, sessionID) }
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
