package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.feature.sessions.R

/** One open session, as the strip shows it. [id] is the session id, which is what the strip's callbacks name. */
data class SessionTabUi(val id: String, val title: String, val selected: Boolean)

/** The height of a tab and of the strip: the smallest touch target the platform asks for. */
private val TabHeight = 48.dp

/**
 * The open sessions, as a row of tabs under the session's top bar.
 *
 * **Drawn only with two or more tabs.** With one there is nothing to switch to, and the top bar already names that
 * session. The strip holds no state: the app module decides which tabs there are, what the selected one is, and what a
 * tap does, because each tab is a navigation destination and the features may not navigate.
 *
 * Each tab has two targets, both at least 48dp high: the title selects the session and the close button closes it.
 * They are separate so that closing never selects the tab first.
 */
@Composable
fun SessionTabStrip(
    tabs: List<SessionTabUi>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.size >= 2) {
        TabRow(tabs = tabs, onSelect = onSelect, onClose = onClose, modifier = modifier)
    }
}

@Composable
private fun TabRow(
    tabs: List<SessionTabUi>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val selectedIndex = tabs.indexOfFirst { it.selected }
    // The selected tab is brought into view, so a tab that was opened last is not cut off at the end of the row.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }
    // A fixed height, so every tab is the same 48dp and the row does not size itself to its tallest child.
    LazyRow(
        modifier = modifier.fillMaxWidth().height(TabHeight),
        state = listState,
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
            SessionTab(
                tab = tab,
                position = index + 1,
                count = tabs.size,
                onSelect = onSelect,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun SessionTab(
    tab: SessionTabUi,
    position: Int,
    count: Int,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    // The spoken label names the position and the state, so TalkBack reads the tab as one line.
    val label = if (tab.selected) {
        stringResource(R.string.session_tab_label_selected, tab.title, position, count)
    } else {
        stringResource(R.string.session_tab_label, tab.title, position, count)
    }
    val closeLabel = stringResource(R.string.session_tab_close, tab.title)
    Row(
        modifier = Modifier
            .height(TabHeight)
            .background(color = tabColor(tab.selected), shape = MaterialTheme.shapes.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 200.dp)
                .height(TabHeight)
                .selectable(selected = tab.selected, role = Role.Tab, onClick = { onSelect(tab.id) })
                .semantics(mergeDescendants = true) { contentDescription = label }
                .padding(start = 12.dp, end = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = tab.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (tab.selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Material's icon button lays out at 40dp, so it is sized to the tab's 48dp explicitly.
        IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(TabHeight)) {
            Icon(Icons.Filled.Close, contentDescription = closeLabel)
        }
    }
}

/** The selected tab is the one a user looks for, so it takes the container colour and the rest sit on the surface. */
@Composable
private fun tabColor(selected: Boolean) = if (selected) {
    MaterialTheme.colorScheme.secondaryContainer
} else {
    MaterialTheme.colorScheme.surface
}
