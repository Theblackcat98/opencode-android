package dev.opencode.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import dev.opencode.android.core.data.config.DefinitionKind
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.feature.admin.AdminCatalog
import dev.opencode.android.feature.admin.CatalogUiState
import dev.opencode.android.feature.servers.ui.EditServerScreen
import dev.opencode.android.feature.servers.ui.EventInspectorScreen
import dev.opencode.android.feature.servers.ui.ServerStatusScreen
import kotlinx.coroutines.launch

/**
 * The admin graph: server administration and the Phase 9 destinations — the config
 * explorer and editor, definitions, catalogs, permissions, instructions, maintenance, the
 * changelog, usage, quick ask, the RPC console and device pairing.
 */
@Composable
fun NavGraphBuilder.adminGraph(deps: NavGraphDeps) {
    navigation<AdminGraph>(startDestination = ConfigRoute) {
        composable<ServerStatusRoute> {            ServerStatusScreen(
                onNavigateBack = { deps.navController.popBackStack() },
                onEditServer = { serverId -> deps.navController.navigate(EditServerRoute(serverId)) { launchSingleTop = true } },
                onOpenInspector = { serverId -> deps.navController.navigate(EventInspectorRoute(serverId)) { launchSingleTop = true } },
                // Re-pairing keeps the profile and replaces only the credential.
                onPairAgain = { serverId ->
                    deps.navController.navigate(AddServerRoute(replaceServerId = serverId)) { launchSingleTop = true }
                },
            )
        }

        composable<EditServerRoute> {
            EditServerScreen(onNavigateBack = { deps.navController.popBackStack() })
        }

        composable<EventInspectorRoute> {
            EventInspectorScreen(
                onNavigateBack = { deps.navController.popBackStack() },
                onAddServer = {
                    deps.navController.popBackStack()
                    deps.navController.navigate(AddServerRoute()) { launchSingleTop = true }
                },
            )
        }

        // -------------------------------------------------------------------- Phase 9 destinations
        //
        // "Configuration, permissions admin, maintenance" (plan §4.1, §6). Six destinations: the
        // explorer, the `opencode.jsonc` editor, the definition editor, the catalog browsers, the
        // permissions admin and maintenance. The seventh feature — session instruction entries — is
        // reached from the session rather than from Manage, because it is scoped to a session.

        composable<ConfigRoute> { entry ->
            val route = entry.toRoute<ConfigRoute>()
            ConfigHost(
                directory = route.directory,
                onNavigateBack = { deps.navController.popBackStack() },
                onOpenEditor = { path ->
                    deps.navController.navigate(ConfigEditorRoute(route.serverId, route.directory, path)) { launchSingleTop = true }
                },
                onOpenDefinitions = {
                    deps.navController.navigate(
                        DefinitionRoute(route.serverId, route.directory, DefinitionKind.AGENT, ""),
                    ) { launchSingleTop = true }
                },
                onOpenCatalogs = {
                    deps.navController.navigate(
                        CatalogRoute(route.serverId, route.directory, AdminCatalog.AGENTS),
                    ) { launchSingleTop = true }
                },
                onOpenPermissions = {
                    deps.navController.navigate(PermissionsRoute(route.serverId, route.directory)) { launchSingleTop = true }
                },
            )
        }

        composable<ConfigEditorRoute> { entry ->
            val route = entry.toRoute<ConfigEditorRoute>()
            ConfigEditorHost(
                directory = route.directory,
                path = route.path,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<DefinitionRoute> { entry ->
            val route = entry.toRoute<DefinitionRoute>()
            DefinitionHost(
                directory = route.directory,
                kind = route.kind,
                name = route.name,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<CatalogRoute> { entry ->
            val route = entry.toRoute<CatalogRoute>()
            val dataSet: ServerDataSet? = deps.serverDataSets.active.collectAsStateWithLifecycle().value
            val directory = route.directory
            var tab by remember(route.tab) { mutableStateOf(route.tab) }
            var search by remember { mutableStateOf("") }
            val agents by (dataSet?.agents(directory)?.state ?: loadingFlow())
                .collectAsStateWithLifecycle()
            val commands by (dataSet?.composerCatalogs?.commands(directory)?.state ?: loadingFlow())
                .collectAsStateWithLifecycle()
            val skills by (dataSet?.composerCatalogs?.skills(directory)?.state ?: loadingFlow())
                .collectAsStateWithLifecycle()
            val references by (dataSet?.composerCatalogs?.references(directory)?.state ?: loadingFlow())
                .collectAsStateWithLifecycle()
            // The four lists are read here because `feature/admin` may not import the modules that own
            // them: the agents come from the sessions module and the other three from the composer's
            // `ComposerCatalogs`. A null data set is the pre-connection state, and it renders as
            // loading rather than as an empty catalog — an empty list would say "this project has no
            // agents", which is a different and wrong claim.
            //
            // The four reads are one function because three call sites run them: the entry effect
            // below, the resume re-read (a catalog invalidates on agent/command/skill/reference
            // updates, per the AdminHost contract), and the screen's retry button.
            val syncCatalogs: suspend () -> Unit = {
                dataSet?.agents(directory)?.sync()
                dataSet?.composerCatalogs?.commands(directory)?.sync()
                dataSet?.composerCatalogs?.skills(directory)?.sync()
                dataSet?.composerCatalogs?.references(directory)?.sync()
            }
            LaunchedEffect(directory) { syncCatalogs() }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { syncCatalogs() }
            val reloadScope = rememberCoroutineScope()
            CatalogHost(
                state = CatalogUiState(
                    tab = tab,
                    search = search,
                    agents = agents,
                    commands = commands,
                    skills = skills,
                    references = references,
                ),
                onTabChange = { tab = it },
                onSearchChange = { search = it },
                onReload = { reloadScope.launch { syncCatalogs() } },
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<PermissionsRoute> { entry ->
            val route = entry.toRoute<PermissionsRoute>()
            PermissionsHost(
                directory = route.directory,
                projectID = route.projectID,
                sessionId = route.sessionId,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<InstructionsRoute> { entry ->
            val route = entry.toRoute<InstructionsRoute>()
            InstructionsHost(
                sessionId = route.sessionId,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<MaintenanceRoute> { entry ->
            val route = entry.toRoute<MaintenanceRoute>()
            MaintenanceHost(onNavigateBack = { deps.navController.popBackStack() })
        }

        composable<ChangelogRoute> {
            ChangelogHost(onNavigateBack = { deps.navController.popBackStack() })
        }

        composable<UsageRoute> {
            UsageHost(onNavigateBack = { deps.navController.popBackStack() })
        }

        composable<QuickAskRoute> {
            QuickAskHost(onNavigateBack = { deps.navController.popBackStack() })
        }

        composable<RpcConsoleRoute> {
            RpcConsoleHost(onNavigateBack = { deps.navController.popBackStack() })
        }

        composable<PairDeviceRoute> {
            PairDeviceHost(onNavigateBack = { deps.navController.popBackStack() })
        }
    }
}
