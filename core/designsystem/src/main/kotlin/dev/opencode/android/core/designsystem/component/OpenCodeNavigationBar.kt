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
 */
@Composable
fun OpenCodeNavigationBar(
    entries: List<NavigationBarEntry>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        entries.forEach { entry ->
            val isSelected = entry.key == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(entry.key) },
                icon = {
                    val icon = if (isSelected) entry.selectedIcon else entry.icon
                    if (entry.badge > 0) {
                        BadgedBox(badge = { Badge { Text(entry.badge.toString()) } }) {
                            Icon(icon, contentDescription = null)
                        }
                    } else {
                        Icon(icon, contentDescription = null)
                    }
                },
                label = { Text(entry.label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}
