package dev.opencode.android.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.CapabilityPolicy
import dev.opencode.android.core.data.capability.ExperimentalRoute
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.SessionContextInspector
import dev.opencode.android.core.data.transcript.TranscriptFormatter
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.SessionTransfer
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The format an export is written in. Both are the server's; the client only chooses. */
enum class TransferFormat {
    /** The `SessionTransfer.Data` the route returned, verbatim. */
    JSON,

    /**
     * The same transcript as text.
     *
     * Markdown is produced on the device from the projection the app already has, not by asking the
     * server for it: the route returns one shape, and a second format the server does not offer is
     * a format this client has to get right rather than one it can be handed.
     */
    MARKDOWN,
}

/**
 * The history panel's state (plan §6, "History tools").
 *
 * **Everything here is derived from the timeline the session screen already holds**, except the
 * context inspector, the export and the import, which are the server's own calls. The prompts, the
 * search hits and the counts are a projection of `SessionMessage`s, not a second copy of the
 * timeline: a history panel with its own transcript would disagree with the one on screen the
 * moment a message arrived.
 */
data class HistoryUiState(
    val sessionID: String? = null,
    val open: Boolean = false,
    /** The user messages, in order, which is what "jump" walks. */
    val prompts: List<HistoryPrompt> = emptyList(),
    /** Where "jump" currently is, as an index into [prompts], or `null` when it has not moved. */
    val cursor: Int? = null,
    val search: String = "",
    val hits: List<HistoryHit> = emptyList(),
    val context: List<SessionContextInspector.Entry> = emptyList(),
    val contextOpen: Boolean = false,
    val contextLoading: Boolean = false,
    val transferring: Boolean = false,
    /** The last transfer's outcome, which is either a count of messages or the server's refusal. */
    val transfer: TransferResult? = null,
    /**
     * The transcript this device exported, in the format it was exported in.
     *
     * It is held so an import has something to send and so the app can share it. It is the client's
     * memory of a file the user also has, and it is dropped when the panel closes rather than
     * persisted: a transcript on the device that the server no longer holds is a copy of a
     * conversation the user did not ask to be kept.
     */
    val exported: String? = null,
    val exportSanitize: Boolean = false,
    val error: ActionError? = null,
    /** Whether the export and import routes are permitted: the switch and the probe, both. */
    val transferUsable: Boolean = false,
    /** The switch's own state, which the settings screen writes and this one only reads. */
    val transferAllowed: Boolean = false,
) {
    /** Where the jump arrows go, and whether they can go anywhere at all. */
    val canGoBack: Boolean get() = cursor != null && cursor > 0
    val canGoForward: Boolean get() = cursor != null && cursor < prompts.lastIndex
}

/** One user message, as the jump list shows it. */
data class HistoryPrompt(val id: String, val preview: String)

/** One search result: a message and the line of it that matched. */
data class HistoryHit(val id: String, val type: String, val preview: String)

/** What a transfer did, as a value the screen turns into a sentence. */
sealed interface TransferResult {
    /** The server answered; the number is the transcript's own length. */
    data class Exported(val messages: Int) : TransferResult

    /** The server answered; this is the new session's id. */
    data class Imported(val sessionID: String) : TransferResult

    /** The route is not there, or is not permitted; the message is the server's own. */
    data class Refused(val message: String) : TransferResult
}

/**
 * Jumping, searching, exporting, importing and inspecting the context (plan §6, "History tools").
 *
 * **It is a separate view model from the review's** because it answers a different question: the
 * review asks "what changed", this asks "what happened and what does the model still see". Sharing a
 * view model would put a hundred-file diff and a search field in one state value, and every
 * recomposition of one would carry the other.
 *
 * **The context inspector runs off the main thread** (plan §5.4). `session.context` is a list of
 * messages, each of which is summarized; doing that on the UI thread for a long session is the one
 * place this screen could drop frames, and it is a pure function so moving it costs nothing.
 */
class HistoryViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private var messages: List<SessionMessage> = emptyList()
    private var transferAllowed: Boolean = false

    /** Binds to a session and folds in the prompts its timeline holds. */
    fun open(sessionID: String?) {
        _state.value = _state.value.copy(sessionID = sessionID, cursor = null, hits = emptyList())
        val set = dataSets.active.value ?: return
        viewModelScope.launch {
            set.sessions.loadSession(sessionID.orEmpty())
            val timeline = sessionID?.let { set.timeline(it) }
            timeline?.state?.collect { store ->
                val next = store.messages
                if (next == messages) return@collect
                messages = next
                _state.value = _state.value.copy(prompts = promptsOf(next))
            }
        }
    }

    fun toggle() {
        val open = !_state.value.open
        _state.value = _state.value.copy(open = open)
        if (open) loadContext()
    }

    /** `/diff`-style movement: the previous and next user message. */
    fun previousPrompt() = move(-1)

    fun nextPrompt() = move(1)

    private fun move(delta: Int) {
        val prompts = _state.value.prompts
        if (prompts.isEmpty()) return
        val from = _state.value.cursor ?: (prompts.lastIndex + delta).coerceIn(0, prompts.lastIndex)
        _state.value = _state.value.copy(cursor = (from + delta).coerceIn(0, prompts.lastIndex))
    }

    /** The message the cursor names, or `null` when it has not moved. */
    fun focused(): String? = _state.value.cursor?.let { _state.value.prompts.getOrNull(it)?.id }

    /**
     * Search within the session.
     *
     * It walks the server's own projection rather than asking the server, because there is no route
     * for it: the transcript is already in memory, and a search that needed a round trip per
     * keystroke would be a search that lags. The match is a case-insensitive substring over the
     * formatted message, which is the same text "copy message" produces — so what a hit previews is
     * what the user would copy.
     */
    fun search(query: String) {
        _state.value = _state.value.copy(search = query)
        if (query.isBlank()) {
            _state.value = _state.value.copy(hits = emptyList())
            return
        }
        viewModelScope.launch {
            val hits = withContext(Dispatchers.Default) { hitsOf(messages, query) }
            _state.value = _state.value.copy(hits = hits)
        }
    }

    /** `session.context`: the messages after the last compaction, summarized. */
    fun loadContext() {
        val id = _state.value.sessionID ?: return
        val store = dataSets.active.value?.review ?: return
        _state.value = _state.value.copy(contextOpen = true, contextLoading = true)
        viewModelScope.launch {
            val result = store.context(id)
            val error = result.exceptionOrNull()?.toActionError()
            val entries = result.getOrNull().orEmpty()
            _state.value = _state.value.copy(
                context = withContext(Dispatchers.Default) { SessionContextInspector.inspect(entries) },
                contextLoading = false,
                error = error,
            )
        }
    }

    fun closeContext() {
        _state.value = _state.value.copy(contextOpen = false)
    }

    fun setSanitize(sanitize: Boolean) {
        _state.value = _state.value.copy(exportSanitize = sanitize)
    }

    /** Records the switch's value, so the export button knows whether it may be pressed. */
    fun setTransferAllowed(allowed: Boolean) {
        transferAllowed = allowed
        fold()
    }

    /**
     * `experimental.session.export`, with the sanitize flag the route takes.
     *
     * Sanitizing is the safer default and it is on here: a transcript can contain a credential the
     * user pasted, and a file they then share from the downloads folder has no further idea of where
     * it goes. Both routes are behind the switch and the probe, and a `404` is recorded so the
     * button disappears rather than failing again.
     */
    fun export(format: TransferFormat) {
        val id = _state.value.sessionID ?: return
        val store = dataSets.active.value?.review ?: return
        if (!_state.value.transferUsable) {
            _state.value = _state.value.copy(transfer = TransferResult.Refused(REFUSED_OFF))
            return
        }
        _state.value = _state.value.copy(transferring = true, transfer = null)
        viewModelScope.launch {
            val result = store.export(id, _state.value.exportSanitize)
            val error = result.exceptionOrNull()?.toActionError()
            store.recordCapability(ExperimentalRoute.SESSION_EXPORT, error)
            val transfer = result.getOrNull()
            val outcome = when {
                error != null || transfer == null ->
                    TransferResult.Refused(error?.message.orEmpty().ifEmpty { REFUSED_OFF })

                // JSON is the server's own bytes and this client does not re-serialize them: a file
                // that has been through a second encoder is a file whose imports may not round-trip.
                format == TransferFormat.JSON -> {
                    val encoded = withContext(Dispatchers.Default) {
                        runCatching { OpenCodeJson.encodeToString(SessionTransfer.serializer(), transfer) }.getOrNull()
                    }
                    if (encoded == null) {
                        TransferResult.Refused(REFUSED_OFF)
                    } else {
                        _state.value = _state.value.copy(exported = encoded)
                        TransferResult.Exported(transfer.messages.size)
                    }
                }

                // Markdown is the one format the server does not offer, so it is built here from the
                // same transcript through the same formatter "copy transcript" uses.
                else -> {
                    val text = withContext(Dispatchers.Default) {
                        TranscriptFormatter.transcript(transfer.messages, transfer.info.title)
                    }
                    _state.value = _state.value.copy(exported = text)
                    TransferResult.Exported(transfer.messages.size)
                }
            }
            _state.value = _state.value.copy(transferring = false, transfer = outcome)
            fold()
        }
    }

    /**
     * `experimental.session.import`, from a transcript the user picked.
     *
     * Importing appends to the server's history rather than merging into it, and a session the
     * server already has is a conflict it reports rather than retries. The text is the user's file
     * and the decode is this client's, so a file it cannot read is refused here rather than sent as
     * a request the server would answer `400`.
     */
    fun import(text: String) {
        val store = dataSets.active.value?.review ?: return
        if (!_state.value.transferUsable) {
            _state.value = _state.value.copy(transfer = TransferResult.Refused(REFUSED_OFF))
            return
        }
        _state.value = _state.value.copy(transferring = true, transfer = null)
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.Default) { decodeTransfer(text) }
            val result = decoded?.let { store.import(it) }
            val error = result?.exceptionOrNull()?.toActionError()
            store.recordCapability(ExperimentalRoute.SESSION_IMPORT, error)
            _state.value = _state.value.copy(
                transferring = false,
                transfer = when {
                    decoded == null -> TransferResult.Refused(REFUSED_OFF)
                    error != null -> TransferResult.Refused(error.message.orEmpty())
                    else -> TransferResult.Imported(result?.getOrNull()?.id.orEmpty())
                },
            )
            fold()
        }
    }

    /**
     * Decodes a `SessionTransfer` a user chose.
     *
     * It is a `Result` rather than a throw, and it is `null` for anything that is not one: a
     * Markdown transcript, a truncated file, a transcript from a newer release. The refusal says
     * nothing about the server because nothing was sent — the file never left the device.
     */
    private fun decodeTransfer(text: String): SessionTransfer? = runCatching {
        OpenCodeJson.decodeFromString(SessionTransfer.serializer(), text)
    }.getOrNull()

    fun dismissTransfer() {
        _state.value = _state.value.copy(transfer = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun fold() {
        val store = dataSets.active.value?.review
        val availability = store?.capabilities?.value?.get(ExperimentalRoute.SESSION_EXPORT)
        _state.value = _state.value.copy(
            transferAllowed = transferAllowed,
            transferUsable = transferAllowed &&
                CapabilityPolicy.isUsable(availability ?: RouteAvailability.Unknown),
        )
    }

    private companion object {
        /** What the UI says when the button was pressed and the switch was off. */
        const val REFUSED_OFF = "experimental-routes-off"

        fun promptsOf(messages: List<SessionMessage>): List<HistoryPrompt> = messages
            .filterIsInstance<SessionMessage.User>()
            .map { message ->
                HistoryPrompt(
                    id = message.id,
                    preview = message.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(160).orEmpty(),
                )
            }

        fun hitsOf(messages: List<SessionMessage>, query: String): List<HistoryHit> {
            val needle = query.lowercase()
            return messages.mapNotNull { message ->
                val text = TranscriptFormatter.message(message)
                val line = text.lineSequence().firstOrNull { it.lowercase().contains(needle) } ?: return@mapNotNull null
                HistoryHit(id = message.id, type = message::class.simpleName.orEmpty(), preview = line.take(160))
            }
        }
    }
}
