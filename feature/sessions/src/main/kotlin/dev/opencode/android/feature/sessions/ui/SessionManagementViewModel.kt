package dev.opencode.android.feature.sessions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.actionErrorOrNull
import dev.opencode.android.core.data.transcript.TranscriptFormatter
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
class SessionManagementViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val open = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<ActionError?>(null)

    /** The last failure, which the screen shows as a snackbar and then clears. */
    val error: StateFlow<ActionError?> = _error.asStateFlow()

    /** Direct children of the open session, which the delete confirmation has to name. */
    val childCount: StateFlow<Int> = dataSets.active
        .map { set -> set?.sessions?.childCounts?.value.orEmpty() }
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

    /** The text "copy message" puts on the clipboard. */
    fun copyMessage(message: SessionMessage): String = TranscriptFormatter.message(message)

    /** The text "copy the whole conversation" puts on the clipboard. */
    fun copyTranscript(sessionID: String, title: String?): String? {
        val set = dataSets.active.value ?: return null
        val messages = set.timeline(sessionID).state.value.messages
        if (messages.isEmpty()) return null
        return TranscriptFormatter.transcript(messages, title)
    }

    fun dismissError() {
        _error.value = null
    }

    private fun withSession(block: suspend (dev.opencode.android.core.data.server.ServerDataSet, String) -> Unit) {
        val id = open.value ?: return
        val set = dataSets.active.value ?: return
        viewModelScope.launch { block(set, id) }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
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
