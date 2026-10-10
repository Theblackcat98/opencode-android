package dev.opencode.android.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.SessionStats
import dev.opencode.android.core.model.SessionStatsTools
import dev.opencode.android.core.model.ToolDetailMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/** The range the user picked, in the only units that survive a timezone change: whole days. */
data class StatsRange(
    val days: Int,
) {
    val from: Long get() = System.currentTimeMillis() - days * MILLIS_PER_DAY
    val to: Long get() = System.currentTimeMillis()

    /** The label the picker shows, which is the user's word for it rather than a date arithmetic. */
    val labelRes: Int
        get() = when (days) {
            7 -> R.string.insights_range_7_days
            30 -> R.string.insights_range_30_days
            90 -> R.string.insights_range_90_days
            else -> R.string.insights_range_365_days
        }

    companion object {
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /** The ranges the picker offers, shortest first. */
        val offered = listOf(StatsRange(7), StatsRange(30), StatsRange(90), StatsRange(365))

        val Default = offered[1]
    }
}

data class InsightsUiState(
    val loading: Boolean = false,
    val range: StatsRange = StatsRange.Default,
    /** The projects the active server knows, for the project row. Empty until the server's list has loaded. */
    val projects: List<Project> = emptyList(),
    /** The id of the project the figures are filtered to, or `null` for every project. */
    val project: String? = null,
    val timezone: String = ZoneId.systemDefault().id,
    val tools: ToolDetailMode = ToolDetailMode.Summary,
    val stats: SessionStats? = null,
    val error: ActionError? = null,
    /**
     * Whether the server answered the route at all.
     *
     * **Three states, not two.** `Unknown` means nothing has been asked, `Present` means it answered,
     * and `Absent` means it has no such route. The state records that, and `available` is false on the
     * third. [InsightsScreen] reads `available` and, for a server without `/api/experimental/session/stats`,
     * shows a plain message in place of the cards rather than the error card.
     */
    val availability: RouteAvailability = RouteAvailability.Unknown,
) {
    val available: Boolean get() = availability !is RouteAvailability.Absent
    val isDetail: Boolean get() = stats?.tools is SessionStatsTools.Detail
}

/**
 * The usage dashboard's state (plan §6, "Usage dashboard").
 *
 * **Every parameter is optional and the server's defaults are used for the ones left out.** Sending
 * an invented default range would show a different window than `opencode stats` does for the same
 * question, and the two would look like a bug in one of them. So the state holds only what the user
 * chose, and the view model passes exactly that.
 *
 * **Tool detail is asked for lazily.** Computing per-tool reliability over a long history costs the
 * server something, so the column is `summary` while the screen is idle and only becomes `detail`
 * when the user opens it.
 */
@HiltViewModel
class InsightsViewModel(
    private val active: StateFlow<ServerDataSet?>,
) : ViewModel() {

    /** Hilt's constructor: the read model of the server being followed. */
    @Inject
    constructor(dataSets: ServerDataRegistry) : this(dataSets.active)

    private val _state = MutableStateFlow(InsightsUiState())
    val state: StateFlow<InsightsUiState> = _state.asStateFlow()

    private var inFlight: Job? = null

    init {
        // The list is the active server's own `project.list`, read through its set and not fetched a second
        // time here. When the server changes, the list follows the new set.
        val projects: Flow<List<Project>> = active.flatMapLatest { set ->
            set?.projects?.state?.map { it.value.orEmpty() } ?: flowOf(emptyList())
        }
        // The selection belongs to the server it was made on: a project id means nothing to another server, and
        // the figures on screen are the old server's. Range and tool detail are the user's, so they stay.
        // Only a change from the server already followed resets; the first emission, which may arrive after
        // open() has asked, is the server the screen started on.
        var followed = active.value?.serverId
        viewModelScope.launch {
            active.collect { set ->
                if (set?.serverId == followed) return@collect
                followed = set?.serverId
                inFlight?.cancel()
                _state.value = _state.value.copy(
                    loading = false,
                    project = null,
                    stats = null,
                    error = null,
                    availability = RouteAvailability.Unknown,
                )
            }
        }
        viewModelScope.launch {
            projects.collect { list -> _state.value = _state.value.copy(projects = list) }
        }
    }

    fun open() = refresh()

    /**
     * Re-queries with the current selection.
     *
     * **The previous request is cancelled rather than left to finish.** Two statistics answers
     * arriving out of order would leave the screen showing a range the user has already changed, and
     * a cancel is the only thing that stops a slow query from winning a race it lost.
     */
    fun refresh() {
        val set = active.value ?: return
        inFlight?.cancel()
        inFlight = viewModelScope.launch {
            val current = _state.value
            _state.value = current.copy(loading = true, error = null)
            val result = set.insights.sessionStats(
                from = current.range.from,
                to = current.range.to,
                project = current.project,
                timezone = current.timezone,
                tools = current.tools,
            )
            result.onSuccess { stats ->
                _state.value = _state.value.copy(loading = false, stats = stats, error = null)
            }.onFailure { failure ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = (failure as? dev.opencode.android.core.data.integrations.ActionFailure)?.error,
                )
            }
            _state.value = _state.value.copy(availability = set.insights.stats.value)
        }
    }

    fun setRange(range: StatsRange) {
        if (_state.value.range == range) return
        _state.value = _state.value.copy(range = range)
        refresh()
    }

    /**
     * Filters the figures to one project by its id, or to every project with `null`, and re-queries.
     *
     * The id is what the request carries, so the chip the user picked and the query the server answers
     * cannot disagree. Choosing the project already selected sends nothing.
     */
    fun setProject(project: String?) {
        if (_state.value.project == project) return
        _state.value = _state.value.copy(project = project)
        refresh()
    }

    /**
     * Turns the tool column on and off.
     *
     * `summary` keeps the rolled-up counters without the per-tool rows, which is what the server
     * computes cheaply. The switch is the same one the query parameter is, so the screen and the
     * request can never disagree about what is being shown.
     */
    fun setToolDetail(on: Boolean) {
        val next = if (on) ToolDetailMode.Detail else ToolDetailMode.Summary
        if (_state.value.tools == next) return
        _state.value = _state.value.copy(tools = next)
        refresh()
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}
