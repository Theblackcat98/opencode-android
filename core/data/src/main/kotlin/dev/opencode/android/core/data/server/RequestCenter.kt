package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.model.FormAnswer
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.FormReplyPayload
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionReplyPayload
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.event.FormCancelled
import dev.opencode.android.core.model.event.FormCreated
import dev.opencode.android.core.model.event.FormReplied
import dev.opencode.android.core.model.event.PermissionAsked
import dev.opencode.android.core.model.event.PermissionReplied
import dev.opencode.android.core.model.event.SessionCreated
import dev.opencode.android.core.model.event.SessionMoved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap
import dev.opencode.android.core.network.ServerApi as Api

/**
 * Everything the agent is blocked on, across every session of one server (plan §4.2, "RequestCenter").
 *
 * **The agent cannot proceed without an answer, so this is the one store that is about the user
 * rather than about a transcript.** It answers two questions: what is waiting, and what happens when
 * the user answers. Both the session's request dock and the global inbox are views of the same maps,
 * which is what stops a permission answered on one screen from still showing on another.
 *
 * **Three sources, one map.** `permission.asked` and `form.created` upsert; the `*.replied` and
 * `*.cancelled` events drop; and a resync re-reads `permission.request.list` and `form.list` for
 * every location the client knows about. Events are enough while the stream is up; the resync is
 * what makes the list correct after a reconnect, which is exactly the case plan §4.2 requires the
 * resync to cover.
 *
 * **Location-scoped, and dropped per location.** A `location.shutdown` removes only the requests
 * belonging to sessions in that location, for the same reason `ServerDataSet` drops one location's
 * caches: one location going away must not empty a screen showing another.
 *
 * Requests are never invented or edited here. A reply is a REST call whose result arrives as
 * `permission.replied` or `form.replied`, and this store only records what the server said — the one
 * exception is a failed call, which reports the error and leaves the request pending, because the
 * agent is still blocked on it.
 */
class RequestCenter(
    private val api: Api,
    private val scope: CoroutineScope,
) {
    private val _permissions = MutableStateFlow<Map<String, PermissionRequest>>(emptyMap())
    private val _forms = MutableStateFlow<Map<String, FormInfo>>(emptyMap())
    private val _directoryOfSession = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * One derived flow per session, cached.
     *
     * [forSession] is called from a state projection that recomposes on every session event, and a
     * `stateIn` per call would add a collector to the set's scope each time and never remove it. The
     * cache is bounded by the sessions the user opens and [clear] drops it.
     */
    private val perSession = ConcurrentHashMap<String, StateFlow<List<PendingRequest>>>()

    /** The pending permission requests, newest first. */
    val permissions: StateFlow<List<PermissionRequest>> = _permissions
        .map { it.values.sortedByDescending(PermissionRequest::id) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The pending forms, newest first. */
    val forms: StateFlow<List<FormInfo>> = _forms
        .map { it.values.sortedByDescending(FormInfo::id) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Everything pending, for the global inbox. Forms first: a question is the more urgent wait. */
    val pending: StateFlow<List<PendingRequest>> = combine(_permissions, _forms) { perms, forms ->
        buildList<PendingRequest> {
            forms.values.mapTo(this) { PendingRequest.Form(it) }
            perms.values.mapTo(this) { PendingRequest.Permission(it) }
        }.sortedWith(PENDING_ORDER)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The requests of one session, which is what the session's dock shows. */
    fun forSession(sessionID: String): StateFlow<List<PendingRequest>> = perSession.getOrPut(sessionID) {
        combine(_permissions, _forms) { perms, forms ->
            buildList<PendingRequest> {
                forms.values.filter { it.sessionID == sessionID }.mapTo(this) { PendingRequest.Form(it) }
                perms.values.filter { it.sessionID == sessionID }.mapTo(this) { PendingRequest.Permission(it) }
            }.sortedWith(PENDING_ORDER)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    /** The session ids with something pending, for a badge in the session list. */
    val sessionsWithPending: StateFlow<Set<String>> = pending
        .map { requests -> requests.mapTo(mutableSetOf()) { it.sessionID } }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * The directories this center has been asked about, so a resync knows where to look.
     *
     * Only directories a session actually lives in: `permission.request.list` and `form.list` are
     * location-scoped, so asking about a directory with no session would return nothing useful and
     * would cost a request.
     */
    val knownDirectories: Set<String>
        get() = _directoryOfSession.value.values.toSet()

    /**
     * Applies one event.
     *
     * Returns true when the visible requests changed, so a frame's worth of events can be published
     * once instead of once per event.
     */
    fun apply(event: Event): Boolean {
        val payload = event.payload
        rememberSessionDirectory(payload)
        return when (payload) {
            is PermissionAsked -> upsertPermission(payload.toRequest())
            is PermissionReplied -> dropPermission(payload.requestID)
            is FormCreated -> upsertForm(payload.form)
            is FormReplied -> dropForm(payload.id)
            is FormCancelled -> dropForm(payload.id)
            else -> false
        }
    }

    /**
     * Re-reads the pending requests and forms of [directory].
     *
     * The client has no record of what it missed while it was disconnected, so the answer is the
     * server's: the lists replace everything this center holds for that location rather than being
     * merged into it.
     */
    suspend fun resync(directory: String?) {
        val permissions = runCatching { api.listPermissionRequests(directory) }.getOrNull()?.data
        val forms = runCatching { api.listForms(directory) }.getOrNull()?.data
        if (permissions == null && forms == null) return
        val sessions = sessionsIn(directory)
        if (permissions != null) {
            _permissions.value = _permissions.value.filterValues { it.sessionID !in sessions } +
                permissions.associateBy(PermissionRequest::id)
        }
        if (forms != null) {
            _forms.value = _forms.value.filterValues { it.sessionID !in sessions } + forms.associateBy(FormInfo::id)
        }
    }

    /**
     * Answers a permission request.
     *
     * An `always` reply stores [PermissionRequest.savedPatterns] on the server, which is why the UI
     * shows them and asks for confirmation first (plan §5.2). The request stays pending until
     * `permission.replied` removes it, so a failed call leaves the agent blocked and visible rather
     * than silently unresolved.
     */
    suspend fun replyPermission(
        request: PermissionRequest,
        decision: PermissionReply,
        feedback: String? = null,
    ): ActionError? = callAction {
        api.replyToPermission(
            sessionID = request.sessionID,
            requestID = request.id,
            body = PermissionReplyPayload(decision = decision, message = feedback?.takeIf { it.isNotBlank() }),
        )
    }

    /** Answers a form. [answer] is what [dev.opencode.android.core.data.forms.FormEngine] built. */
    suspend fun replyForm(form: FormInfo, answer: FormAnswer): ActionError? = callAction {
        api.replyToForm(form.sessionID, form.id, FormReplyPayload(answer))
    }

    /** Dismisses a form. Dismissing a question is a cancel, which is what the TUI does too. */
    suspend fun cancelForm(form: FormInfo): ActionError? = callAction {
        api.cancelForm(form.sessionID, form.id)
    }

    /** Forgets everything, for example when the server is removed. */
    fun clear() {
        _permissions.value = emptyMap()
        _forms.value = emptyMap()
        _directoryOfSession.value = emptyMap()
        perSession.clear()
    }

    /**
     * Drops the requests of one location, which is what `location.shutdown` means for them.
     *
     * The maps are keyed by request and form id, not by session, so the session set is what has to
     * be resolved first: a request belongs to the location of the session that is waiting on it.
     */
    fun dropLocation(directory: String) {
        val sessions = sessionsIn(directory)
        _directoryOfSession.value = _directoryOfSession.value.filterKeys { it !in sessions }
        _permissions.value = _permissions.value.filterValues { it.sessionID !in sessions }
        _forms.value = _forms.value.filterValues { it.sessionID !in sessions }
    }

    private fun sessionsIn(directory: String?): Set<String> =
        _directoryOfSession.value.filterValues { it == directory }.keys

    private fun rememberSessionDirectory(payload: EventPayload) {
        val sessionID: String
        val directory: String
        when (payload) {
            is SessionCreated -> {
                sessionID = payload.sessionID
                directory = payload.location.directory
            }

            is SessionMoved -> {
                sessionID = payload.sessionID
                directory = payload.location.directory
            }

            else -> return
        }
        if (_directoryOfSession.value[sessionID] == directory) return
        _directoryOfSession.value = _directoryOfSession.value + (sessionID to directory)
    }

    private fun upsertPermission(request: PermissionRequest): Boolean {
        if (_permissions.value[request.id] == request) return false
        _permissions.value = _permissions.value + (request.id to request)
        return true
    }

    private fun dropPermission(requestID: String): Boolean {
        if (requestID !in _permissions.value) return false
        _permissions.value = _permissions.value - requestID
        return true
    }

    private fun upsertForm(form: FormInfo): Boolean {
        if (_forms.value[form.id] == form) return false
        _forms.value = _forms.value + (form.id to form)
        return true
    }

    private fun dropForm(formID: String): Boolean {
        if (formID !in _forms.value) return false
        _forms.value = _forms.value - formID
        return true
    }

    /** Runs one write and maps a failure to the class the UI renders. */
    private suspend inline fun callAction(crossinline block: suspend () -> Unit): ActionError? = try {
        block()
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        error.toActionError()
    }
}

/**
 * One thing the agent is waiting on.
 *
 * A permission and a form are the same thing to the user — something is blocked until this is
 * answered — but they are answered through different endpoints and rendered differently, so the
 * union keeps both rather than flattening them.
 */
sealed interface PendingRequest {
    val id: String
    val sessionID: String

    /** Forms sort before permissions; within a kind the newest id comes first. */
    val rank: Int

    data class Permission(val request: PermissionRequest) : PendingRequest {
        override val id: String get() = request.id
        override val sessionID: String get() = request.sessionID
        override val rank: Int get() = PERMISSION_RANK
    }

    data class Form(val form: FormInfo) : PendingRequest {
        override val id: String get() = form.id
        override val sessionID: String get() = form.sessionID
        override val rank: Int get() = FORM_RANK
    }
}

private const val FORM_RANK = 0
private const val PERMISSION_RANK = 1

private val PENDING_ORDER: Comparator<PendingRequest> =
    compareBy<PendingRequest> { it.rank }.thenByDescending { it.id }
