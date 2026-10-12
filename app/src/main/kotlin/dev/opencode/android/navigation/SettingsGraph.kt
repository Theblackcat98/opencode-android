package dev.opencode.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import dev.opencode.android.feature.integrations.R as IntegrationsR
import dev.opencode.android.feature.sessions.ui.SettingsRoute

/**
 * The settings graph: the settings tab and the per-directory manage destinations —
 * accounts, MCP, plugins, providers and web search.
 */
@Composable
fun NavGraphBuilder.settingsGraph(deps: NavGraphDeps) {
    val resourceAttachedText = stringResource(IntegrationsR.string.mcp_resource_attached)
    navigation<SettingsGraph>(startDestination = SettingsRoute) {
        composable<SettingsRoute>(
            enterTransition = { DestinationTransitions.TopLevel.enter },
            exitTransition = { DestinationTransitions.TopLevel.exit },
            popEnterTransition = { DestinationTransitions.TopLevel.popEnter },
            popExitTransition = { DestinationTransitions.TopLevel.popExit },
        ) { entry ->
            val route = entry.toRoute<SettingsRoute>()
            SettingsRoute(
                serverId = route.serverId,
                onOpen = { destination, directory ->
                    deps.navController.openSettingsDestination(route.serverId, destination, directory.orEmpty())
                },
            )
        }

        composable<ConnectRoute> { entry ->
            val route = entry.toRoute<ConnectRoute>()
            ConnectHost(
                directory = route.directory,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<McpRoute> { entry ->
            val route = entry.toRoute<McpRoute>()
            McpHost(
                directory = route.directory,
                onNavigateBack = { deps.navController.popBackStack() },
                // A `needs_auth` server names the integration that owns the flow, but the accounts
                // screen takes no deep link: this opens it at the top and the user picks the integration
                // there, rather than a second, near-identical login sheet.
                onAuthenticate = {
                    deps.navController.navigate(ConnectRoute(route.serverId, route.directory)) { launchSingleTop = true }
                },
                onAttachResource = { _, _, _ ->
                    // The resource is staged by the host (`McpHost.consumeAttach`); the screen stays
                    // put and a snackbar confirms, instead of yanking to the pending-requests inbox.
                    deps.showSnackbar(resourceAttachedText)
                },
            )
        }

        composable<PluginsRoute> { entry ->
            val route = entry.toRoute<PluginsRoute>()
            PluginsHost(
                directory = route.directory,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<ProvidersRoute> { entry ->
            val route = entry.toRoute<ProvidersRoute>()
            ProvidersHost(
                directory = route.directory,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<WebSearchRoute> { entry ->
            val route = entry.toRoute<WebSearchRoute>()
            WebSearchHost(
                directory = route.directory,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }
    }
}
