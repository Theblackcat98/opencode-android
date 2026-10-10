package dev.opencode.android.feature.sessions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.data.server.actionErrorOrNull
import dev.opencode.android.core.data.transcript.TranscriptFormatter
import dev.opencode.android.core.data.tui.TuiControl
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.SessionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Renaming, deleting and copying a session (plan §6, Session management).
 *
 * **Deleting is the one irreversible action, and the guard is the child's count.** `session.remove`
 * takes the session's children with it, a session list row shows that as a badge rather than a
 * warning, and there is no undo. The screen therefore asks with the number in the message, and this
 * ViewModel is the only place that calls remove at all, so the confirmation cannot be bypassed by
 * another caller.
 *
 * **Copy produces text through [TranscriptFormatter]**, not through the composables, so what the
 * clipboard receives is a pure function of the message list and is unit tested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionManagementViewModel(
    private val active: StateFlow<ServerDataSet?>,
) : ViewModel() {
    /** Hilt's constructor: what this view model reads from the registry is which read model is active. */
    @Inject
    constructor(dataSets: ServerDataRegistry) : this(dataSets.active)

    private val open = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<ActionError?>(null)
    private val _forked = MutableStateFlow<String?>(null)
    private val _waiting = MutableStateFlow(false)
    private val _syntheticSending = MutableStateFlow(false)
    private val _syntheticSent = MutableStateFlow(false)
    private var waitJob: Job? = null

    /** The last failure, which the screen shows as a snackbar and then clears. */
    val error: StateFlow<ActionError?> = _error.asStateFlow()

    /** Whether a [waitUntilIdle] is in flight. The screen shows its dialog, with a way to stop, while it is. */
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    /**
     * Whether a synthetic note is being sent. The dialog locks its fields and confirm button while it is, and
     * [addSyntheticNote] ignores a second call, so a double tap sends one note.
     */
    val syntheticSending: StateFlow<Boolean> = _syntheticSending.asStateFlow()

    /**
     * Set when the server has accepted a synthetic note. The host closes its dialog on it and then calls
     * [consumeSyntheticSent].
     *
     * Whether a dialog is open is the screen's state, so the ViewModel reports the outcome and the host closes it.
     * It is consumed the way [forked] is, so one accepted note closes the dialog exactly once.
     */
    val syntheticSent: StateFlow<Boolean> = _syntheticSent.asStateFlow()

    /**
     * Whether the menu offers "wait until idle".
     *
     * Only an absent route hides it. An unknown one is offered, because the first call is what finds out,
     * and a row that hid itself until then would never be tried.
     */
    val waitAvailable: StateFlow<Boolean> = active
        .flatMapLatest { set -> set?.insights?.generate ?: flowOf(RouteAvailability.Unknown) }
        .map { it.callable }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), true)

    /**
     * Whether the app follows the desktop TUI on the active server. The sheet's row shows it as on or off.
     *
     * It is read from the server's `TuiControl`, through the active server, so a switch of server shows that server's
     * value. Nothing is stored: the setting is in memory and starts off whenever the server's data set is made.
     */
    val followDesktop: StateFlow<Boolean> = active
        .flatMapLatest { set -> set?.tui?.followDesktop ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), false)

    /** Turns following the desktop TUI on or off for the active server. It changes nothing on the server. */
    fun setFollowDesktop(enabled: Boolean) {
        active.value?.tui?.setFollowDesktop(enabled)
    }

    /**
     * The active server's TUI control, or `null` while there is no active server.
     *
     * The session screen collects the TUI's prompt text through it while following is on. It is the same object for as
     * long as the server stays active, so a collector keyed on it starts once per server and not on each recomposition.
     */
    val tui: StateFlow<TuiControl?> = active
        .map { it?.tui }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /**
     * The id of a session this app has just forked, or `null`.
     *
     * It is a one-shot rather than a sticky field because the graph navigates on it: a fork that
     * left its id here forever would re-navigate on every recomposition. The caller consumes it
     * with [consumeFork] and the value is cleared in the same breath.
     */
    val forked: StateFlow<String?> = _forked.asStateFlow()

    /**
     * Direct children of the open session, which the delete confirmation has to name.
     *
     * Followed, not read: a count read inside a `map` on `active` is the count when the server became
     * active, so a sheet that said "no subagents" would warn about nothing while `session.remove` took
     * the session's children with it.
     */
    val childCount: StateFlow<Int> = active
        .flatMapLatest { set -> set?.sessions?.childCounts ?: flowOf(emptyMap()) }
        .combine(open) { counts, id -> if (id == null) 0 else counts[id] ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    fun openSession(sessionID: String) {
        open.value = sessionID
    }

    /** `session.update` with only the title. */
    fun rename(title: String) = withSession { set, id ->
        val error = set.commands.update(sessionID = id, title = title).actionErrorOrNull
        if (error != null) _error.value = error
    }

    /** `session.remove`. The caller has already confirmed. */
    fun delete() = withSession { set, id ->
        val error = set.commands.remove(id).actionErrorOrNull
        if (error != null) _error.value = error
    }

    /**
     * `experimental.session.wait` on the open session: the request returns when its turn ends.
     *
     * The wait is held by the client, so [waiting] is what the screen shows while it is open and
     * [stopWaiting] is what ends it. A second call while one is in flight does nothing, so two taps
     * cannot start two waits on the same session.
     */
    fun waitUntilIdle() {
        val id = open.value ?: return
        val set = active.value ?: return
        if (waitJob?.isActive == true) return
        _waiting.value = true
        waitJob = viewModelScope.launch {
            val self = currentCoroutineContext()[Job]
            try {
                val result = set.insights.waitUntilIdle(id)
                // Cancelling is the user's choice, not a failure, so it does not reach the error line.
                if (isActive) result.exceptionOrNull()?.let { _error.value = it.toActionError() }
            } finally {
                // A stopped wait has already cleared the flag in stopWaiting, and a newer wait may own it by now.
                if (waitJob === self) _waiting.value = false
            }
        }
    }

    /** Ends the wait the user started. The server is not asked for anything: the request is what stops. */
    fun stopWaiting() {
        waitJob?.cancel()
        waitJob = null
        _waiting.value = false
    }

    /**
     * `session.synthetic` on the open session: text the user did not type, delivered through the session's inbox.
     *
     * The note is sent trimmed and is never a user prompt; the dialog that calls this says so before its button is
     * pressed. A failure goes to [error] and leaves [syntheticSent] false, so the dialog stays open for a retry.
     * A call made while a note is in flight, or with no text, does nothing.
     */
    fun addSyntheticNote(text: String, description: String?) {
        if (_syntheticSending.value) return
        val note = SyntheticNote.of(text, description) ?: return
        val id = open.value ?: return
        val set = active.value ?: return
        _error.value = null
        _syntheticSending.value = true
        viewModelScope.launch {
            try {
                val result = set.insights.addSyntheticInput(id, note.text, note.description)
                if (result.isSuccess) _syntheticSent.value = true
                result.exceptionOrNull()?.let { _error.value = it.toActionError() }
            } finally {
                _syntheticSending.value = false
            }
        }
    }

    /** Takes the accepted-note flag, once, so the host closes the synthetic note dialog exactly one time. */
    fun consumeSyntheticSent() {
        _syntheticSent.value = false
    }

    /** The text "copy message" puts on the clipboard. */
    fun copyMessage(message: SessionMessage): String = TranscriptFormatter.message(message)

    /** The text "copy the whole conversation" puts on the clipboard. */
    fun copyTranscript(sessionID: String, title: String?): String? {
        val set = active.value ?: return null
        val messages = set.timeline(sessionID).state.value.messages
        if (messages.isEmpty()) return null
        return TranscriptFormatter.transcript(messages, title)
    }

    /**
     * `session.fork`: a copy of the open session, cut before [messageId] when one is given.
     *
     * The copy is a *new session id*, so the caller navigates to it rather than mutating anything
     * here; [forked] is how it learns which one. A fork is not reversible and not destructive —
     * the original is untouched — so it is the one session operation that does not ask first.
     */
    fun fork(messageId: String?) = withSession { set, id ->
        val result = set.revertCommands.fork(id, messageId)
        result.onSuccess { info -> _forked.value = info.id }
        result.exceptionOrNull()?.let { _error.value = it.toActionError() }
    }

    /** Takes the forked id, once, so the graph navigates to it exactly one time. */
    fun consumeFork(): String? {
        val id = _forked.value
        _forked.value = null
        return id
    }

    fun dismissError() {
        _error.value = null
    }

    private fun withSession(block: suspend (dev.opencode.android.core.data.server.ServerDataSet, String) -> Unit) {
        val id = open.value ?: return
        val set = active.value ?: return
        viewModelScope.launch { block(set, id) }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}

/**
 * The text and description a synthetic note sends, trimmed, or `null` when there is no text to send.
 *
 * Trimming lives here so the request and the dialog's enabled state agree on what "blank" means, and a description
 * that is only spaces is sent as absent rather than as an empty string the server would store.
 */
internal data class SyntheticNote(val text: String, val description: String?) {
    companion object {
        fun of(text: String, description: String?): SyntheticNote? {
            val trimmed = text.trim().takeIf { it.isNotEmpty() } ?: return null
            return SyntheticNote(trimmed, description?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
}

/**
 * A provider's call to action, taken from a `session.status` retry (features doc §4.3).
 *
 * A usage-exceeded retry carries a title, a message and a link the user has to open to fix it — on a
 * provider's own page, not in this app. Phase 8 is where the app can log in; until then the action is
 * a link, and hiding it would leave the user with "usage exceeded" and no way forward.
 */
data class ProviderActionUi(
    val reason: String,
    val provider: String,
    val title: String,
    val message: String,
    val label: String,
    val link: String?,
)

/** The retry state a session's header shows, with the countdown to the next attempt. */
data class RetryUi(
    val attempt: Int,
    val next: Long,
    val message: String,
    val action: ProviderActionUi?,
)

/** The provider action of a status, or `null` for a status that carries none. */
val SessionStatus.retryOrNull: RetryUi?
    get() = (this as? SessionStatus.Retry)?.let { status ->
        RetryUi(
            attempt = status.attempt,
            next = status.next,
            message = status.message,
            action = status.action?.let {
                ProviderActionUi(
                    reason = it.reason,
                    provider = it.provider,
                    title = it.title,
                    message = it.message,
                    label = it.label,
                    link = it.link,
                )
            },
        )
    }
