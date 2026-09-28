package dev.opencode.android.core.data.presence

import dev.opencode.android.core.data.attention.AttentionPreferences
import dev.opencode.android.core.data.attention.AttentionSettings
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.model.SessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One session with a live execution, as the ongoing notification lists it. */
data class RunningSession(
    val id: String,
    val title: String,
    /** True for a subagent, which is a child session; the notification names the difference. */
    val isSubagent: Boolean,
)

/**
 * Everything the connection service needs to decide whether it should be running.
 *
 * A snapshot rather than a live read of four stores, so the service collects one flow instead of
 * four and so a test can build one in a single expression. The counts are what
 * [PresencePolicy] needs; the session list is what the ongoing notification shows.
 */
data class PresenceSignals(
    val serverId: String? = null,
    val serverName: String = "",
    val running: List<RunningSession> = emptyList(),
    val pendingPermissions: Int = 0,
    val pendingForms: Int = 0,
    val alwaysConnected: Boolean = false,
    val idleGraceMillis: Long = PresencePolicy.DEFAULT_IDLE_GRACE_MILLIS,
) {
    val hasServer: Boolean get() = serverId != null
    val pendingRequests: Int get() = pendingPermissions + pendingForms
    val hasWork: Boolean get() = running.isNotEmpty() || pendingRequests > 0

    /** The subset [PresencePolicy] reads, with the facts only the service knows filled in. */
    fun toInputs(
        serviceRunning: Boolean,
        idleSinceMillis: Long?,
        nowMillis: Long,
        startExemption: StartExemption,
    ): PresenceInputs = PresenceInputs(
        hasServer = hasServer,
        runningSessions = running.size,
        pendingRequests = pendingRequests,
        alwaysConnected = alwaysConnected,
        serviceRunning = serviceRunning,
        idleGraceMillis = idleGraceMillis,
        idleSinceMillis = idleSinceMillis,
        nowMillis = nowMillis,
        startExemption = startExemption,
    )
}

/**
 * The one thing [dev.opencode.android.feature.requests.notifications.ConnectionServiceLauncher] reads.
 *
 * An interface rather than a bare [PresenceController] because the controller is built from the data
 * sets, the connection manager and the preferences, none of which the launcher's decision depends on:
 * the launcher only ever asks "is there work right now". Naming the dependency as the one signal keeps
 * the decision testable without a server, a socket or a preference file.
 */
interface PresenceSignalsSource {
    val signals: StateFlow<PresenceSignals>
}

/**
 * Reads the stores the service's decision depends on, and nothing else (plan §6, Phase 4).
 *
 * **It does not decide.** The decision is [PresencePolicy]'s and belongs to a test; this only
 * projects the inputs (running sessions, pending requests, the per-server preferences) into one
 * flow, so the Android component that cannot be unit tested has nothing left to decide.
 *
 * Only the *active* server is watched. The service holds one stream and the connection manager owns
 * which server that is, so a second server's activity is not this service's business.
 */
@Singleton
class PresenceController @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val connections: ServerConnectionManager,
    private val preferences: AttentionPreferences,
) : PresenceSignalsSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class Activity(
        val running: List<RunningSession> = emptyList(),
        val pendingPermissions: Int = 0,
        val pendingForms: Int = 0,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val fromActiveSet = dataSets.active.flatMapLatest { set ->
        if (set == null) {
            flowOf(Activity())
        } else {
            combine(
                set.sessions.activity,
                set.sessions.info,
                set.requests.permissions,
                set.requests.forms,
            ) { activity, info, permissions, forms ->
                Activity(
                    running = runningSessions(activity, info),
                    pendingPermissions = permissions.size,
                    pendingForms = forms.size,
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val serverId = dataSets.active
        .map { it?.serverId }
        .distinctUntilChanged()

    private val settings: StateFlow<AttentionSettings> = preferences.settings
        .stateIn(scope, SharingStarted.Eagerly, AttentionSettings())

    private val _signals = MutableStateFlow(PresenceSignals())
    override val signals: StateFlow<PresenceSignals> = _signals.asStateFlow()

    init {
        scope.launch {
            combine(
                fromActiveSet,
                settings,
                connections.activeConnection,
                serverId,
            ) { activity, all, connection, id ->
                PresenceSignals(
                    serverId = id,
                    serverName = connection?.serverProfile?.name.orEmpty(),
                    running = activity.running,
                    pendingPermissions = activity.pendingPermissions,
                    pendingForms = activity.pendingForms,
                    alwaysConnected = id != null && all.alwaysConnected.contains(id),
                    idleGraceMillis = all.idleGraceMillis,
                )
            }.collect { _signals.value = it }
        }
    }

    /**
     * The sessions with a live execution, in a stable order.
     *
     * A subagent is a child session and the ongoing notification says so, because "a subagent
     * finished" and "the session you were reading finished" are different things to the user. A
     * session the stream has not paged in has no title to show, so it is left out of the list
     * rather than shown as an empty row; it still counts towards the decision, because
     * `SessionStore.activity` is what carries the truth.
     */
    private fun runningSessions(
        activity: Map<String, SessionActivity>,
        info: Map<String, SessionInfo>,
    ): List<RunningSession> = activity
        .filterValues { it is SessionActivity.Running }
        .keys
        .sorted()
        .mapNotNull { id ->
            val session = info[id] ?: return@mapNotNull null
            RunningSession(
                id = id,
                title = session.title?.takeIf(String::isNotBlank) ?: SessionRow.DEFAULT_TITLE,
                isSubagent = session.parentID != null,
            )
        }
}
