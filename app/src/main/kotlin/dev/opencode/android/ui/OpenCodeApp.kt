package dev.opencode.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.opencode.android.feature.servers.ui.AddServerScreen
import dev.opencode.android.feature.servers.ui.EventInspectorScreen
import dev.opencode.android.feature.servers.ui.ServerStatusScreen
import dev.opencode.android.feature.servers.ui.ServersScreen
import dev.opencode.android.navigation.AddServerRoute
import dev.opencode.android.navigation.EventInspectorRoute
import dev.opencode.android.navigation.HomeRoute
import dev.opencode.android.navigation.ServerStatusRoute
import dev.opencode.android.navigation.ServersRoute

@Composable
fun OpenCodeApp(
    initialUrl: String? = null,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) {
            navController.navigate(AddServerRoute(initialUrl = initialUrl))
        }
    }

    NavHost(
        navController = navController,
        startDestination = ServersRoute,
        modifier = modifier,
    ) {
        composable<ServersRoute> {
            ServersScreen(
                onAddServerClick = { navController.navigate(AddServerRoute()) },
                onServerClick = { serverId -> navController.navigate(ServerStatusRoute(serverId)) },
                onInspectorClick = { navController.navigate(EventInspectorRoute()) },
            )
        }
        composable<AddServerRoute> {
            AddServerScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable<ServerStatusRoute> {
            ServerStatusScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenInspector = { serverId -> navController.navigate(EventInspectorRoute(serverId)) },
            )
        }
        composable<EventInspectorRoute> {
            EventInspectorScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable<HomeRoute> {
            ServersScreen(
                onAddServerClick = { navController.navigate(AddServerRoute()) },
                onServerClick = { serverId -> navController.navigate(ServerStatusRoute(serverId)) },
                onInspectorClick = { navController.navigate(EventInspectorRoute()) },
            )
        }
    }
}
