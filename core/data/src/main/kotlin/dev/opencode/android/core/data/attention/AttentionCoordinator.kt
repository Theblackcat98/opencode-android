package dev.opencode.android.core.data.attention

import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.execution.FinishedShell
import dev.opencode.android.core.data.server.InstallationState
import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.PermissionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which session the user is actually looking at.
 *
 * **A tiny piece of shared state, and the reason it exists.** "Did the user see this?" is not
 * something a store can answer — it is a fact about which screen is in front — and the notification
 * layer needs it. Publishing it once is what keeps the answer from being re-derived several slightly
 * different ways.
 */
@Singleton
class OpenSessionTracker @Inject constructor() {
    private val _open = MutableStateFlow<String?>(null)

    /** The session whose screen is resumed, or `null` on every other screen. */
    val open: StateFlow<String?> = _open.asStateFlow()

    /** Called by the session screen when it resumes, and again when it is left. */
    fun set(sessionId: String?) {
        _open.value = sessionId
    }
}

/**
 * Which location's shell panel is in front (Phase 7).
 *
 * **The same idea as [OpenSessionTracker], and for the same reason.** A finished command belongs to a
 * checkout, so "the user is watching this one" is a statement about a directory rather than a
 * session, and it cannot be derived from the session the app happens to have open: a shell panel is
 * reached from the home, not from a conversation. Publishing it once is what stops the notification
 * from arriving for output the user is reading.
 */
@Singleton
class OpenLocationTracker @Inject constructor() {
    private val _open = MutableStateFlow<String?>(null)

    /** The directory whose shell panel is resumed, or `null` on every other screen. */
    val open: StateFlow<String?> = _open.asStateFlow()

    /** Called by the shell panel when it resumes, and again when it is left. */
    fun set(directory: String?) {
        _open.value = directory
    }
}

/**
 * Drives the attention layer from the stores (plan §6, Phase 4).
 *
 * **All the decisions are elsewhere.** What should be on screen is [AttentionReconciler]'s, which
 * request is auto-approved is [AutoApprovePolicy]'s, and what the connection service is doing is
 * [PresencePolicy]'s. This only collects the inputs, keeps the ledger of what is posted, and hands
 * the diff to the [AttentionSink]. That split is the reason the phase can be tested at all: a
 * `Service`, a `BroadcastReceiver` and a `NotificationManager` cannot be exercised without a device,
 * so nothing that matters is written in terms of them.
 *
 * **The ledger is per server.** Switching servers replaces the whole set rather than merging,
 * because a notification about one server says nothing about another, and the previous server's
 * notifications have to be un-posted when the user leaves it.
 *
 * **Auto-approve runs here rather than in the service.** A permission arrives as an event, and if
 * the user turned auto-approve on the answer follows in the same pass. Waiting for the service to
 * start first would leave a window in which the request is both pending and unapproved, and a
 * notification the user cannot outrace the client on.
 */
@Singleton
class AttentionCoordinator @Inject constructor(
    private val dataSets: ServerDataRegistry,
    private val connections: ServerConnectionManager,
    private val preferences: AttentionPreferences,
    private val openSessions: OpenSessionTracker,
    private val openLocations: OpenLocationTracker,
    private val sink: AttentionSink,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settings: StateFlow<AttentionSettings> = preferences.settings
        .stateIn(scope, SharingStarted.Eagerly, AttentionSettings())

    /** What the last pass posted, which is the only record of what is on screen. */
    private val ledger = MutableStateFlow<List<AttentionDraft>>(emptyList())

    /** Requests already sent an auto-approval for, so one is never sent twice. */
    private val autoApproved = mutableSetOf<String>()

    private val _running = MutableStateFlow(false)

    /** Whether the coordinator is collecting. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    /**
     * Starts collecting. Idempotent, because both the activity and the service call it: the
     * coordinator is useful for as long as the process is, and starting it twice would double every
     * notification.
     */
    @Synchronized
    fun start() {
        if (job != null) return
        _running.value = true
        job = scope.launch {
            dataSets.active.collect { set ->
                // A different server, or none: nothing that was posted for the previous one survives.
                // Publishing the empty diff is what cancels it, so there is one code path for
                // "this should not be on screen any more" rather than two that can disagree.
                clearLedger()
                if (set != null) collectServer(set)
            }
        }
    }

    /** Stops collecting and silences the shade. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        clearLedger()
        _running.value = false
    }

    private fun clearLedger() {
        val previous = ledger.value
        if (previous.isNotEmpty()) {
            sink.publish(
                AttentionReconcile(removed = previous.map { it.slot }),
            )
        }
        ledger.value = emptyList()
        autoApproved.clear()
        sink.setLauncherBadge(0)
    }

    private suspend fun collectServer(set: ServerDataSet) {
        // The two un-derived maps rather than [dev.opencode.android.core.data.server.RequestCenter.pending]:
        // that derivation runs on another coroutine, so a permission raised in the same dispatch as
        // this pass could still be missing from it — which is exactly the window the "within about
        // 2 s" exit criterion is about.
        val requests = combine(
            set.requests.permissionsById,
            set.requests.formsById,
        ) { permissions, forms -> pendingRequestsOf(permissions, forms) }
        // Seven inputs, so the combination is nested. `combine` has typed overloads up to five and an
        // `Array` overload beyond that, and the array one would hand the transform an `Array<Any?>` —
        // which is how a `pending` parameter quietly becomes `Any?` and a `null` reaches a state
        // field that is not nullable. Two typed combines keep every parameter a real type.
        val focus = combine(
            settings,
            openSessions.open,
            openLocations.open,
            set.execution.finishedShells,
        ) { all, open, openDirectory, finished ->
            Focus(all, open, openDirectory, finished)
        }
        combine(
            set.sessions.rows,
            requests,
            set.installation.state,
            focus,
        ) { rows, pending, installation, f ->
            buildState(set, rows, pending, installation, f)
        }.collect { state ->
            reconcile(state)
            autoApprove(set, state.nowMillis)
        }
    }

    private fun buildState(
        set: ServerDataSet,
        rows: List<SessionRow>,
        pending: List<PendingRequest>,
        installation: InstallationState.Installation,
        focus: Focus,
    ): AttentionState {
        val clock = now()
        return AttentionState(
            serverId = set.serverId,
            serverName = connections.activeConnection.value?.serverProfile?.name.orEmpty(),
            sessions = rows.associate { row -> row.id to row.toAttentionSession(rows) },
            pending = pending,
            finishedShells = focus.finished.map(FinishedShell::toAttentionShell),
            updateVersion = installation.updateAvailable,
            openSessionId = focus.openSession,
            openDirectory = focus.openDirectory,
            mutedSessions = focus.settings.mutedSessions,
            quietHours = focus.settings.quietHoursFor(set.serverId),
            autoApprovedSessions = AutoApprovePolicy.autoApprovedSessions(
                settings = focus.settings,
                sessionIds = pending.map(PendingRequest::sessionID),
                now = clock,
            ),
            nowMillis = clock,
            utcOffsetMillis = utcOffsetMillis(clock),
        )
    }

    /**
     * The four inputs that answer "what is the user looking at, and what are they allowed to be told".
     *
     * A private value type rather than seven parameters, so the nested `combine` has one typed
     * transform to return and the compiler checks the whole bundle.
     */
    private data class Focus(
        val settings: AttentionSettings,
        val openSession: String?,
        val openDirectory: String?,
        val finished: List<FinishedShell>,
    )

    private fun reconcile(state: AttentionState) {
        // The badge comes from the same pass as the notifications, so the two cannot disagree about
        // what "unread" means.
        sink.setLauncherBadge(state.sessions.values.count { it.isUnread })
        val reconcile = AttentionReconciler.reconcile(state, ledger.value)
        if (reconcile.isEmpty) return
        ledger.value = reconcile.posts
        sink.publish(reconcile)
    }

    /**
     * Answers the requests auto-approve covers.
     *
     * The answer goes through the same [dev.opencode.android.core.data.server.RequestCenter] the
     * screens use, so a retried approve is the same request and cannot approve twice, and a failure
     * leaves the request pending and visible rather than silently dropped.
     */
    private suspend fun autoApprove(set: ServerDataSet, clock: Long) {
        val all = settings.value
        val pending = set.requests.currentPermissions()
        if (pending.isEmpty() || !anyAutoApproveRunning(all, pending, clock)) return
        set.requests.currentPermissions().forEach { request ->
            if (request.id in autoApproved) return@forEach
            val decision = AutoApprovePolicy.decide(request, all, clock, autoApproved)
            if (decision !is AutoApproveDecision.Approve) return@forEach
            autoApproved += request.id
            val error = set.requests.replyPermission(decision.request, AutoApprovePolicy.decision)
            if (error == null) return@forEach
            // The answer did not land. The request stays pending, so the agent is still blocked, and
            // the user is told rather than left with a mode that silently does nothing.
            autoApproved -= request.id
            sink.announce(
                AttentionNotice.ActionFailed(
                    sessionId = decision.request.sessionID,
                    about = decision.request.action,
                    error = error,
                ),
            )
        }
    }

    private fun anyAutoApproveRunning(
        settings: AttentionSettings,
        pending: List<PermissionRequest>,
        clock: Long,
    ): Boolean = settings.autoApproveGlobalUntil != null ||
        pending.any { settings.autoApproveUntilFor(it.sessionID)?.let { until -> until > clock } == true }
}

/** Forms first, then permissions, each newest first: the same order the request centre publishes. */
private fun pendingRequestsOf(
    permissions: Map<String, PermissionRequest>,
    forms: Map<String, FormInfo>,
): List<PendingRequest> = buildList {
    forms.values.sortedByDescending { it.id }.mapTo(this) { PendingRequest.Form(it) }
    permissions.values.sortedByDescending { it.id }.mapTo(this) { PendingRequest.Permission(it) }
}

/** The parts of a row the attention layer reads. */
private fun SessionRow.toAttentionSession(rows: List<SessionRow>): AttentionSession = AttentionSession(
    id = session.id,
    title = title,
    parentID = session.parentID,
    parentTitle = session.parentID?.let { parent -> rows.firstOrNull { it.id == parent }?.title },
    activity = activity,
    outcome = session.outcome,
    idleAtMillis = session.time.idle,
    viewedAtMillis = session.time.viewed,
)

/** The device's own offset from UTC, which is the frame a quiet-hours window is measured in. */
private fun utcOffsetMillis(clock: Long): Long = TimeZone.getDefault().getOffset(clock).toLong()

/** A ledger entry as the reconciler reads it; the two types are the same facts in two layers. */
private fun FinishedShell.toAttentionShell(): AttentionShell = AttentionShell(
    id = id,
    command = command,
    status = status,
    exitCode = exitCode,
    directory = directory,
    completedAtMillis = completedAtMillis,
)

/**
 * The clock.
 *
 * A function rather than a constructor parameter: this class is built by Hilt, and a `() -> Long` in
 * the constructor is a binding nobody can provide. The decisions that depend on the clock — the
 * quiet-hours window and the auto-approve window — are pure functions tested with an explicit one.
 */
private fun now(): Long = System.currentTimeMillis()
