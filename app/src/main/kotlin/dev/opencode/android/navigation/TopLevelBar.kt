package dev.opencode.android.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import dev.opencode.android.R
import dev.opencode.android.core.designsystem.component.NavigationBarEntry
import dev.opencode.android.core.designsystem.component.OpenCodeNavigationBar

/** The three top-level destinations of a server: its projects, the conversation, and its settings. */
enum class TopLevelTab { HOME, CHAT, SETTINGS }

/**
 * Which tab [this] destination belongs to, or null for a pushed screen (review, shells, a Settings page…), which
 * hides the bar so it gets the whole height. Every session is the Chat tab, however it was reached.
 */
fun NavDestination.topLevelTab(): TopLevelTab? = when {
    hasRoute<HomeRoute>() -> TopLevelTab.HOME
    hasRoute<SettingsRoute>() -> TopLevelTab.SETTINGS
    hasRoute<ChatRoute>() || hasRoute<SessionRoute>() -> TopLevelTab.CHAT
    else -> null
}

/** The bottom bar. Stateless: the graph says which tab is selected and what choosing one does. */
@Composable
fun TopLevelBar(selected: TopLevelTab?, onSelect: (TopLevelTab) -> Unit, modifier: Modifier = Modifier) {
    val entries = listOf(
        NavigationBarEntry(TopLevelTab.HOME.name, stringResource(R.string.tab_home), Icons.Outlined.Home, Icons.Filled.Home),
        NavigationBarEntry(
            TopLevelTab.CHAT.name,
            stringResource(R.string.tab_chat),
            Icons.AutoMirrored.Outlined.Chat,
            Icons.AutoMirrored.Filled.Chat,
        ),
        NavigationBarEntry(
            TopLevelTab.SETTINGS.name,
            stringResource(R.string.tab_settings),
            Icons.Outlined.Settings,
            Icons.Filled.Settings,
        ),
    )
    OpenCodeNavigationBar(
        entries = entries,
        selected = selected?.name,
        onSelect = { key -> onSelect(TopLevelTab.valueOf(key)) },
        modifier = modifier,
    )
}

/**
 * Switches tab on [serverId]. The home is the root of a server, so every tab sits directly on it: Back from Chat or
 * Settings returns to the home, and Back from the home returns to the server list.
 */
fun NavHostController.openTab(tab: TopLevelTab, serverId: String?, current: TopLevelTab?) {
    if (tab == current) return
    when (tab) {
        TopLevelTab.HOME -> if (!popBackStack<HomeRoute>(inclusive = false)) {
            navigate(HomeRoute(serverId)) { launchSingleTop = true }
        }

        TopLevelTab.CHAT -> navigate(ChatRoute(serverId)) {
            popUpTo<HomeRoute> { inclusive = false }
            launchSingleTop = true
        }

        TopLevelTab.SETTINGS -> navigate(SettingsRoute(serverId)) {
            popUpTo<HomeRoute> { inclusive = false }
            launchSingleTop = true
        }
    }
}
