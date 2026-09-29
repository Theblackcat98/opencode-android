package dev.opencode.android.feature.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.execution.SessionNode
import dev.opencode.android.core.data.execution.SessionTree
import dev.opencode.android.core.data.execution.SubagentLink
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.SessionRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

/**
 * The session family tree, and the subagent strip the composer shows (plan §6, "Subagents").
 *
 * **Three questions, three pieces of state, one store.** Where am I in the tree, which of my children
 * are working, and what can I jump to. All three are answered from the session rows the client already
 * holds — the tree is a projection of `session.list`, not a second list — so opening this screen costs
 * no request and a `session.created` for a subagent appears in it the moment the event lands.
 */
data class SubagentsUiState(
    val sessionID: String? = null,
    /** The flattened tree, root first, each node carrying the depth it is drawn at. */
    val nodes: List<SessionNode> = emptyList(),
    val selected: String? = null,
    val parentID: String? = null,
    val parentTitle: String? = null,
    val previousSibling: String? = null,
    val nextSibling: String? = null,
    /** The children that are working right now, which is the composer's strip. */
    val running: List<SessionRow> = emptyList(),
    /**
     * Children the tree knows of that the flat list does not.
     *
     * A paged `session.list` does not hold every child, so a family can be incomplete without anything
     * being wrong. Saying so is better than drawing a tree that looks whole.
     */
    val missingChildren: Int = 0,
) {
    val canGoToParent: Boolean get() = parentID != null
    val canGoToPrevious: Boolean get() = previousSibling != null
    val canGoToNext: Boolean get() = nextSibling != null
    val hasRunning: Boolean get() = running.isNotEmpty()
}

/** The composer's strip of running subagents, and the interrupt it offers (plan §6). */
data class SubagentStripState(
    val sessionID: String? = null,
    val children: List<SubagentChip> = emptyList(),
    /** The child whose interrupt is in flight, so its button shows progress rather than nothing. */
    val interrupting: String? = null,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = children.isEmpty()
}

/** One child in the strip: the minimum a chip needs, and nothing this client has to guess. */
data class SubagentChip(
    val sessionID: String,
    val title: String,
    val directory: String,
    val isRunning: Boolean,
    val isRetrying: Boolean,
)

/**
 * Subagent navigation and the running-children strip.
 *
 * **The tree is derived, never stored.** [SessionTree] builds it from the rows on every emission, so a
 * `session.created` for a subagent the server started while the user was elsewhere is in the tree
 * before they get here, and nothing has to be invalidated on the way in.
 *
 * **Interrupting a child goes through the session store's own command**, so the interrupt is the same
 * request the ongoing notification's "Interrupt" button sends and the answer arrives as the same
 * `session.execution.interrupted` event. A second code path would be a second thing that could disagree
 * about whether the child stopped.
 */
class SubagentsViewModel @Inject constructor(
    private val dataSets: ServerDataRegistry,
) : ViewModel() {

    private val selected = MutableStateFlow<String?>(null)
    private val rows = MutableStateFlow<List<SessionRow>>(emptyList())
    private var rowsJob: Job? = null

    private val _strip = MutableStateFlow(SubagentStripState())
    val strip: StateFlow<SubagentStripState> = _strip.asStateFlow()

    /** The tree, flattened, for a screen that draws one list with indentation. */
    val state: StateFlow<SubagentsUiState> = combine(selected, rows) { focus, all ->
        buildState(all, focus)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SubagentsUiState())

    /** Binds to a session: the tree selection, and the strip of its running children. */
    fun open(sessionID: String?) {
        selected.value = sessionID
        _strip.value = _strip.value.copy(sessionID = sessionID)
        val set = dataSets.active.value ?: return
        viewModelScope.launch { set.sessions.loadSession(sessionID.orEmpty()) }
        rowsJob?.cancel()
        rowsJob = viewModelScope.launch {
            set.sessions.rows.collect { all ->
                rows.value = all
                _strip.value = _strip.value.copy(children = chipsOf(all, _strip.value.sessionID))
            }
        }
    }

    /** Jumps to the parent, one row up the tree. */
    fun selectParent() {
        val id = selected.value ?: return
        selected.value = SessionTree.ancestorsOf(rows.value, id).firstOrNull()?.id
    }

    /** Jumps to the previous or next sibling of the selection. */
    fun selectSibling(delta: Int) {
        val id = selected.value ?: return
        selected.value = SessionTree.sibling(rows.value, id, delta)?.id
    }

    /** Selects a node the tree screen was tapped on. */
    fun select(sessionID: String) {
        selected.value = sessionID
    }

    /**
     * `session.interrupt` for one child, and only that child.
     *
     * [resume] is false: the user pressed stop on this subagent, not "stop and let the rest of the turn
     * carry on", and a session nobody is looking at is not the place to decide that for them.
     */
    fun interrupt(sessionID: String) {
        val set = dataSets.active.value ?: return
        _strip.value = _strip.value.copy(interrupting = sessionID, error = null)
        viewModelScope.launch {
            val result = set.commands.interrupt(sessionID, resume = false)
            _strip.value = _strip.value.copy(
                interrupting = null,
                error = result.exceptionOrNull()?.toActionError()?.message,
            )
        }
    }

    fun dismissError() {
        _strip.value = _strip.value.copy(error = null)
    }

    private fun buildState(all: List<SessionRow>, focus: String?): SubagentsUiState {
        val tree = SessionTree.build(all)
        val flat = tree.flatMap { it.flatten() }
        val node = flat.firstOrNull { it.id == focus }
        val parentID = all.firstOrNull { it.id == focus }?.session?.parentID
        return SubagentsUiState(
            sessionID = focus,
            nodes = flat,
            selected = focus,
            parentID = parentID,
            parentTitle = all.firstOrNull { it.id == parentID }?.title,
            previousSibling = SessionTree.sibling(all, focus.orEmpty(), -1)?.id,
            nextSibling = SessionTree.sibling(all, focus.orEmpty(), 1)?.id,
            running = SessionTree.runningChildrenOf(all, focus.orEmpty()),
            missingChildren = (node?.children?.size ?: 0) - all.count { it.session.parentID == focus },
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        fun chipsOf(all: List<SessionRow>, parent: String?): List<SubagentChip> {
            if (parent.isNullOrEmpty()) return emptyList()
            return SessionTree.runningChildrenOf(all, parent).map { row ->
                SubagentChip(
                    sessionID = row.id,
                    title = row.title,
                    directory = row.directory,
                    isRunning = row.activity is SessionActivity.Running,
                    isRetrying = row.activity is SessionActivity.Retrying,
                )
            }
        }
    }
}

/** The child session a subagent tool card names, or `null` when this client cannot open it. */
fun subagentTarget(rows: List<SessionRow>, metadata: Map<String, JsonElement>?): SessionRow? =
    SubagentLink.resolve(rows, metadata)
