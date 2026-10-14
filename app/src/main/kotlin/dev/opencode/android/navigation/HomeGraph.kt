package dev.opencode.android.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import dev.opencode.android.feature.servers.ui.AddServerScreen
import dev.opencode.android.feature.servers.ui.ServersScreen
import dev.opencode.android.feature.sessions.ui.ChatTabRoute
import dev.opencode.android.feature.sessions.ui.HomeRoute
import dev.opencode.android.feature.sessions.ui.SessionListRoute

/**
 * The home graph: the server picker, the add-server flow, and a server's three top-level
 * screens — home, chat and the session list.
 */
fun NavGraphBuilder.homeGraph(deps: NavGraphDeps) {
    navigation<HomeGraph>(startDestination = ServersRoute) {
        composable<ServersRoute>(
            enterTransition = { DestinationTransitions.TopLevel.enter },
            exitTransition = { DestinationTransitions.TopLevel.exit },
            popEnterTransition = { DestinationTransitions.TopLevel.popEnter },
            popExitTransition = { DestinationTransitions.TopLevel.popExit },
        ) {
            ServersScreen(
                onHomeClick = { serverId -> deps.navController.navigate(HomeRoute(serverId)) { launchSingleTop = true } },
                onAddServerClick = { deps.navController.navigate(AddServerRoute()) { launchSingleTop = true } },
                onServerClick = { serverId -> deps.navController.navigate(ServerStatusRoute(serverId)) { launchSingleTop = true } },
                onEditServerClick = { serverId -> deps.navController.navigate(EditServerRoute(serverId)) { launchSingleTop = true } },
                onPairAgainClick = { serverId ->
                    // A rejected credential is repaired by re-pairing, which keeps the profile.
                    deps.navController.navigate(AddServerRoute(replaceServerId = serverId)) { launchSingleTop = true }
                },
                onInspectorClick = { deps.navController.navigate(EventInspectorRoute()) { launchSingleTop = true } },
                onOpenChangelog = { deps.navController.navigate(ChangelogRoute) { launchSingleTop = true } },
                onOpenCommandPalette = deps.onOpenCommandPalette,
            )
        }

        composable<AddServerRoute> {
            AddServerScreen(onNavigateBack = { deps.navController.popBackStack() })
        }

        composable<HomeRoute>(
            enterTransition = { DestinationTransitions.TopLevel.enter },
            exitTransition = { DestinationTransitions.TopLevel.exit },
            popEnterTransition = { DestinationTransitions.TopLevel.popEnter },
            popExitTransition = { DestinationTransitions.TopLevel.popExit },
        ) { entry ->
            val route = entry.toRoute<HomeRoute>()
            var newSessionOpen by remember { mutableStateOf(route.openNewSession) }
            HomeRoute(
                serverId = route.serverId,
                onSessionClick = { sessionId ->
                    deps.navController.navigate(SessionRoute(route.serverId, sessionId)) { launchSingleTop = true }
                },
                onProjectSessionsClick = { projectId ->
                    deps.navController.navigate(
                        SessionListRoute(serverId = route.serverId, projectId = projectId),
                    ) { launchSingleTop = true }
                },
                onNewSessionClick = { newSessionOpen = true },
                onPendingRequestsClick = { serverId ->
                    deps.navController.navigate(PendingRequestsRoute(serverId)) { launchSingleTop = true }
                },
                onSwitchServer = { deps.navController.popBackStack(ServersRoute, inclusive = false) },
                onOpenCommandPalette = deps.onOpenCommandPalette,
            )
            if (newSessionOpen) {
                NewSessionHost(
                    onCreated = { sessionId ->
                        newSessionOpen = false
                        deps.navController.navigate(SessionRoute(route.serverId, sessionId)) { launchSingleTop = true }
                    },
                    onDismiss = { newSessionOpen = false },
                )
            }
        }

        composable<ChatRoute>(
            enterTransition = { DestinationTransitions.TopLevel.enter },
            exitTransition = { DestinationTransitions.TopLevel.exit },
            popEnterTransition = { DestinationTransitions.TopLevel.popEnter },
            popExitTransition = { DestinationTransitions.TopLevel.popExit },
        ) { entry ->
            val route = entry.toRoute<ChatRoute>()
            ChatTabRoute(
                onOpenSession = { sessionId ->
                    deps.navController.navigate(SessionRoute(route.serverId, sessionId)) {
                        popUpTo(ChatRoute(route.serverId)) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNewSession = {
                    // Pop up to any existing home (inclusive) first: pushing another home on top of
                    // one would stack two of them.
                    deps.navController.navigate(HomeRoute(route.serverId, openNewSession = true)) {
                        popUpTo(HomeRoute(route.serverId)) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable<SessionListRoute> { entry ->
            val route = entry.toRoute<SessionListRoute>()
            SessionListRoute(
                serverId = route.serverId,
                projectId = route.projectId,
                onSessionClick = { sessionId ->
                    deps.navController.navigate(SessionRoute(route.serverId, sessionId)) { launchSingleTop = true }
                },
            )
        }
    }
}
