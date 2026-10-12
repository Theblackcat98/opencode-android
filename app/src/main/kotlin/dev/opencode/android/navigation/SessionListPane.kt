package dev.opencode.android.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.feature.sessions.ui.RecentSessionUi
import dev.opencode.android.feature.sessions.ui.SessionListPane
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * The narrowest window that shows the session list beside the session. A window narrower than this shows the session
 * alone, as it did before the list existed.
 */
const val SESSION_LIST_MIN_WIDTH_DP = 840

/** How many sessions the list keeps: the newest, by when each was last updated. */
const val SESSION_LIST_MAX = 30

/**
 * Whether the session route draws the session list beside the session.
 *
 * It does on a window at least [SESSION_LIST_MIN_WIDTH_DP] wide, and only for a session on the active server. The list
 * is read from the active server's data, so a session on another server would otherwise be set against the wrong
 * sessions. This is a plain function so the breakpoint is decided in one place and tested without a window.
 */
fun sessionListShown(screenWidthDp: Int, sessionOnActiveServer: Boolean): Boolean =
    sessionOnActiveServer && screenWidthDp >= SESSION_LIST_MIN_WIDTH_DP

/**
 * The active server's recent sessions, drawn by [SessionListPane].
 *
 * **Composed only on a wide window**, so a narrow window never collects the session rows for it. The rows are the
 * server's session store's rows, which are already newest first. Child sessions (subagents and background commands) are
 * left out, as the home list leaves them out. Titles are the session's own, or [untitledTitle] when it has none.
 *
 * **Selecting a row is a navigation the caller makes.** [onSelect] receives the session id and the route replaces the
 * session on screen with that one, exactly as the tab strip does (see `SessionRoute` in `Routes.kt`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun SessionListPaneHost(
    serverDataSets: ServerDataRegistry,
    untitledTitle: String,
    selectedId: String,
    listState: LazyListState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the new-session flow; wires the pane's empty-state CTA when provided. */
    onNewSession: (() -> Unit)? = null,
) {
    // Built once, not per composition: a flow operator run in composition restarts its collection each time.
    val rowsFlow = remember(serverDataSets) {
        serverDataSets.active.flatMapLatest { set -> set?.sessions?.rows ?: emptyFlow() }
    }
    // Null until the first emission, which is what the pane's loading state is: an emptyList() initial
    // value would be indistinguishable from a server that genuinely has no sessions.
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = null)
    val sessions = remember(rows, untitledTitle) {
        (rows ?: emptyList()).asSequence()
            .filterNot { it.isChild }
            .take(SESSION_LIST_MAX)
            .map { row ->
                RecentSessionUi(id = row.id, title = row.session.title?.takeIf { it.isNotBlank() } ?: untitledTitle)
            }
            .toList()
    }
    SessionListPane(
        sessions = sessions,
        selectedId = selectedId,
        onSelect = onSelect,
        listState = listState,
        loading = rows == null,
        modifier = modifier,
        // The empty-state CTA renders only when wired (the sessions P2 added the param); without it
        // the pane keeps its previous empty treatment.
        onNewSession = onNewSession,
    )
}
