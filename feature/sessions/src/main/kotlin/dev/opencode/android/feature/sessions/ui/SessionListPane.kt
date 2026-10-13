package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.component.OpenCodeEmptyState
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.feature.sessions.R
import kotlinx.coroutines.flow.first

/**
 * One session in the list, as the list shows it. [id] is the session id, which is what [SessionListPane]'s callback
 * names.
 */
data class RecentSessionUi(val id: String, val title: String)

/** The list's width: a fixed column at the left of a wide window, beside the session. */
private val PaneWidth = 320.dp

/** The height of a row: the smallest touch target the platform asks for. */
private val RowHeight = 48.dp

/**
 * The sides the pane keeps clear of. The pane is the left column of the window, so its start edge can sit under a
 * cutout or a side navigation bar, and its top and bottom under the status and navigation bars. The end edge is the
 * session's, and the session screen pads that one itself.
 */
private val PaneInsetSides = WindowInsetsSides.Start + WindowInsetsSides.Top + WindowInsetsSides.Bottom

/**
 * The active server's recent sessions, newest first, with the one on screen selected.
 *
 * **It holds no state of its own.** The app module decides which sessions are listed, which one is selected and what a
 * tap does, because a tap is a navigation and the features may not navigate. The scroll position is the caller's too,
 * passed as [listState], so that it outlives the list being taken out of and put back into the window: the app keeps
 * it above the navigation graph.
 *
 * A selected session that is off screen is scrolled into view when it becomes the selection, for example when it was
 * opened from a notification. A tap on a row never scrolls, because the row it selects is already on screen. The
 * scroll waits for the first layout, so a restored scroll position is not overwritten before it is measured.
 *
 * [loading] is whether the rows are still being read: an empty list while loading is a spinner, not "No sessions
 * yet." A list that finished loading and is genuinely empty keeps the empty text.
 *
 * [onNewSession] starts a new session from the empty state's CTA. It is nullable because a tap is a navigation and
 * the features may not navigate; when null the empty state shows no CTA rather than a dead one. The app module wires
 * it where a new-session destination exists.
 */
@Composable
fun SessionListPane(
    sessions: List<RecentSessionUi>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    listState: LazyListState,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
    onNewSession: (() -> Unit)? = null,
    /**
     * The system bars and cutout the pane keeps clear of. Nothing above the pane pads for them (the navigation graph
     * does not, and each screen pads itself), so the pane takes the same `safeDrawing` default the session screen's
     * `Scaffold` uses. The surface still fills the area behind them.
     */
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val currentSessions by rememberUpdatedState(sessions)
    val selectedIndex = sessions.indexOfFirst { it.id == selectedId }
    // Keyed on whether a selection is listed at all, so the effect runs again once the rows have loaded, and not on
    // each change of the list, which would scroll a reader back to the selection while they scroll the rest of it.
    LaunchedEffect(selectedId, selectedIndex >= 0) {
        val index = currentSessions.indexOfFirst { it.id == selectedId }
        if (index < 0) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.animateScrollToItem(index)
    }

    Column(
        modifier = modifier
            .width(PaneWidth)
            .background(color = MaterialTheme.colorScheme.surfaceContainerLow)
            .windowInsetsPadding(windowInsets.only(PaneInsetSides)),
    ) {
        Text(
            text = stringResource(R.string.session_pane_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OpenCodeThemeExtras.spacing.lg, vertical = OpenCodeThemeExtras.spacing.md)
                .semantics { heading() },
        )
        if (sessions.isEmpty()) {
            if (loading) {
                Row(
                    modifier = Modifier.padding(horizontal = OpenCodeThemeExtras.spacing.lg, vertical = OpenCodeThemeExtras.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OpenCodeThemeExtras.spacing.md),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.session_pane_loading),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                OpenCodeEmptyState(
                    title = stringResource(R.string.session_pane_empty),
                    subtitle = stringResource(R.string.session_pane_empty_hint),
                    icon = Icons.AutoMirrored.Filled.Chat,
                    action = onNewSession?.let { newSession ->
                        {
                            Button(onClick = newSession) {
                                Text(stringResource(R.string.session_pane_new_session))
                            }
                        }
                    },
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = OpenCodeThemeExtras.spacing.sm, vertical = OpenCodeThemeExtras.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(OpenCodeThemeExtras.spacing.xs),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionListRow(session = session, selected = session.id == selectedId, onSelect = onSelect)
                }
            }
        }
    }
}

@Composable
private fun SessionListRow(session: RecentSessionUi, selected: Boolean, onSelect: (String) -> Unit) {
    val label = if (selected) {
        stringResource(R.string.session_pane_label_selected, session.title)
    } else {
        session.title
    }
    Text(
        text = session.title,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .background(color = rowColor(selected), shape = MaterialTheme.shapes.small)
            // The pane is a single-selection list: one session on screen at a time, so the rows are
            // radio buttons, not generic buttons — TalkBack announces the checked state.
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(session.id) })
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = OpenCodeThemeExtras.spacing.md, vertical = OpenCodeThemeExtras.spacing.md),
    )
}

/** The row on screen takes the container colour the tab strip gives its selected tab; the others sit on the surface. */
@Composable
private fun rowColor(selected: Boolean): Color = if (selected) {
    MaterialTheme.colorScheme.secondaryContainer
} else {
    Color.Transparent
}
