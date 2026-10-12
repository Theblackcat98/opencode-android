package dev.opencode.android.core.designsystem.component

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.opencode.android.core.designsystem.R

/** One destination of an [OpenCodeNavigationBar]. [badge] is a count drawn on the icon when positive. */
data class NavigationBarEntry(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val badge: Int = 0,
)

/**
 * The app's bottom bar. Stateless: the caller owns which entry is selected and what selecting one does,
 * and supplies the labels, so no navigation and no strings live in the design system.
 *
 * Badges cap at "99+"; the full count is what TalkBack announces, so a screen reader hears the
 * number the sighted badge abbreviates.
 */
@Composable
fun OpenCodeNavigationBar(
    entries: List<NavigationBarEntry>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    alwaysShowLabel: Boolean = true,
) {
    NavigationBar(modifier = modifier, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        entries.forEach { entry ->
            val isSelected = entry.key == selected
            val badgeText = if (entry.badge > 99) "99+" else entry.badge.toString()
            // The badge count is announced as state: TalkBack reads it after the tab label.
            val badgeDescription = if (entry.badge > 0) {
                stringResource(R.string.navigation_bar_badge_description, entry.label, entry.badge)
            } else {
                null
            }
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(entry.key) },
                icon = {
                    val icon = if (isSelected) entry.selectedIcon else entry.icon
                    if (entry.badge > 0) {
                        BadgedBox(badge = { Badge { Text(badgeText) } }) {
                            Icon(icon, contentDescription = null)
                        }
                    } else {
                        Icon(icon, contentDescription = null)
                    }
                },
                label = { Text(entry.label) },
                alwaysShowLabel = alwaysShowLabel,
                modifier = if (badgeDescription != null) {
                    Modifier.semantics { stateDescription = badgeDescription }
                } else {
                    Modifier
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}
