package dev.opencode.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.opencode.android.feature.servers.ui.AddServerScreen
import dev.opencode.android.feature.servers.ui.EditServerScreen
import dev.opencode.android.feature.servers.ui.EventInspectorScreen
import dev.opencode.android.feature.servers.ui.ServerStatusScreen
import dev.opencode.android.feature.servers.ui.ServersScreen
import dev.opencode.android.navigation.AddServerRoute
import dev.opencode.android.navigation.EditServerRoute
import dev.opencode.android.navigation.EventInspectorRoute
import dev.opencode.android.navigation.HomeRoute
import dev.opencode.android.navigation.ServerStatusRoute
import dev.opencode.android.navigation.ServersRoute
import dev.opencode.android.navigation.SessionListRoute
import dev.opencode.android.navigation.SessionRoute
import dev.opencode.android.feature.sessions.ui.HomeRoute as HomeRouteScreen
import dev.opencode.android.feature.sessions.ui.SessionListRoute as SessionListRouteScreen
import dev.opencode.android.feature.sessions.ui.SessionRoute as SessionRouteScreen

/**
 * The navigation graph.
 *
 * Phase 1's information architecture is the server registry, so it is the start destination
 * (plan §4.3). A link shared from another app, or opened from a browser, lands on the add-server
 * screen with the pairing link already in the field.
 */
@Composable
fun OpenCodeApp(
    sharedPayload: String? = null,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    LaunchedEffect(sharedPayload) {
        if (!sharedPayload.isNullOrBlank()) {
            navController.navigate(AddServerRoute(initialUrl = sharedPayload))
        }
    }

    NavHost(
        navController = navController,
        startDestination = ServersRoute,
        modifier = modifier,
    ) {
        composable<ServersRoute> {
            ServersScreen(
                onHomeClick = { serverId -> navController.navigate(HomeRoute(serverId)) },
                onAddServerClick = { navController.navigate(AddServerRoute()) },
                onServerClick = { serverId -> navController.navigate(ServerStatusRoute(serverId)) },
                onEditServerClick = { serverId -> navController.navigate(EditServerRoute(serverId)) },
                onPairAgainClick = { serverId ->
                    // A rejected credential is repaired by re-pairing, which keeps the profile.
                    navController.navigate(AddServerRoute(replaceServerId = serverId))
                },
                onInspectorClick = { navController.navigate(EventInspectorRoute()) },
            )
        }

        composable<AddServerRoute> {
            AddServerScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable<ServerStatusRoute> {
            ServerStatusScreen(
                onNavigateBack = { navController.popBackStack() },
                onEditServer = { serverId -> navController.navigate(EditServerRoute(serverId)) },
                onOpenInspector = { serverId -> navController.navigate(EventInspectorRoute(serverId)) },
                // Re-pairing keeps the profile and replaces only the credential.
                onPairAgain = { serverId -> navController.navigate(AddServerRoute(replaceServerId = serverId)) },
            )
        }

        composable<EditServerRoute> {
            EditServerScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable<EventInspectorRoute> {
            EventInspectorScreen(
                onNavigateBack = { navController.popBackStack() },
                onAddServer = {
                    navController.popBackStack()
                    navController.navigate(AddServerRoute())
                },
            )
        }

        composable<HomeRoute> {
            // Phase 2 replaces this with the session list for the given server.
            ServersScreen(
                onHomeClick = { serverId -> navController.navigate(HomeRoute(serverId)) },
                onAddServerClick = { navController.navigate(AddServerRoute()) },
                onServerClick = { serverId -> navController.navigate(ServerStatusRoute(serverId)) },
                onEditServerClick = { serverId -> navController.navigate(EditServerRoute(serverId)) },
                onPairAgainClick = { serverId ->
                    navController.navigate(AddServerRoute(replaceServerId = serverId))
                },
                onInspectorClick = { navController.navigate(EventInspectorRoute()) },
            )
        }
    }
}
