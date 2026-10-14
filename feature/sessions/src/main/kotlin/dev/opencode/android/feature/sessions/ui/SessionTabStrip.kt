package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.feature.sessions.R

/** One open session, as the strip shows it. [id] is the session id, which is what the strip's callbacks name. */
data class SessionTabUi(val id: String, val title: String, val selected: Boolean)

/**
 * The open sessions, as a row of tabs under the session's top bar.
 *
 * **Drawn only with two or more tabs.** With one there is nothing to switch to, and the top bar already names that
 * session. The strip holds no state: the app module decides which tabs there are, what the selected one is, and what a
 * tap does, because each tab is a navigation destination and the features may not navigate.
 *
 * Built on M3 [ScrollableTabRow] so the strip gets proper tab-list semantics, a selected-tab indicator,
 * and automatic scrolling to the selected tab. Each tab has two targets: the tab itself selects the session
 * and the close button closes it. They are separate so that closing never selects the tab first.
 */
@Composable
fun SessionTabStrip(
    tabs: List<SessionTabUi>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.size < 2) return
    val selectedIndex = tabs.indexOfFirst { it.selected }
    ScrollableTabRow(
        selectedTabIndex = selectedIndex.coerceAtLeast(0),
        modifier = modifier,
        edgePadding = OpenCodeThemeExtras.spacing.sm,
        indicator = { tabPositions ->
            if (selectedIndex >= 0) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                )
            }
        },
    ) {
        tabs.forEachIndexed { index, tab ->
            val label = if (tab.selected) {
                stringResource(R.string.session_tab_label_selected, tab.title, index + 1, tabs.size)
            } else {
                stringResource(R.string.session_tab_label, tab.title, index + 1, tabs.size)
            }
            val closeLabel = stringResource(R.string.session_tab_close, tab.title)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tab(
                    selected = tab.selected,
                    onClick = { onSelect(tab.id) },
                    modifier = Modifier.semantics { contentDescription = label },
                    text = {
                        Text(
                            text = tab.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    },
                )
                // Keep this as a sibling of Tab: its separate semantics and 48dp target must not
                // override the tab's own selection action.
                IconButton(
                    onClick = { onClose(tab.id) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = closeLabel)
                }
            }
        }
    }
}
