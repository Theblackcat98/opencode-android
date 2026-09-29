package dev.opencode.android.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.opencode.android.OpenLocationTarget
import dev.opencode.android.OpenSessionTarget
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.data.sync.SyncStatus
import dev.opencode.android.core.data.sync.SyncedState
import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.ReferenceInfo
import dev.opencode.android.core.model.SkillInfo
import dev.opencode.android.feature.admin.AdminCatalog
import dev.opencode.android.feature.admin.CatalogUiState
import dev.opencode.android.feature.composer.ui.ComposerViewModel
import dev.opencode.android.feature.composer.ui.LocationChoice
import dev.opencode.android.feature.composer.ui.NewSessionSheet
import dev.opencode.android.feature.composer.ui.NewSessionViewModel
import dev.opencode.android.feature.execution.ProjectSettingsHost
import dev.opencode.android.feature.requests.ui.PendingRequestsScreen
import dev.opencode.android.feature.requests.ui.RequestActions
import dev.opencode.android.feature.servers.ui.AddServerScreen
import dev.opencode.android.feature.servers.ui.EditServerScreen
import dev.opencode.android.feature.servers.ui.EventInspectorScreen
import dev.opencode.android.feature.servers.ui.ServerStatusScreen
import dev.opencode.android.feature.servers.ui.ServersScreen
import dev.opencode.android.feature.sessions.ui.HomeRoute
import dev.opencode.android.feature.sessions.ui.ManageDestination
import dev.opencode.android.feature.sessions.ui.PendingRequestsViewModel
import dev.opencode.android.feature.sessions.ui.SessionListRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** The server registry, and the app's start destination. */
@Serializable
data object ServersRoute

/**
 * Adding a server, with an optional pairing link.
 *
 * [initialUrl] is what a shared link or the `http(s)://…/auth/connect/<code>` deep link delivers,
 * so both arrive on the paste tab already filled in. [replaceServerId] turns the screen into a
 * re-pair: the profile is kept and only its credential is replaced, which is the recovery path
 * after a password or token is rotated on the server.
 */
@Serializable
data class AddServerRoute(
    val initialUrl: String? = null,
    val replaceServerId: String? = null,
)

@Serializable
data class ServerStatusRoute(val serverId: String)

@Serializable
data class EditServerRoute(val serverId: String)

@Serializable
data class EventInspectorRoute(val serverId: String? = null)

/**
 * The per-server home: projects, what is running, the recent sessions, and the pending-requests
 * inbox (plan §4.3).
 *
 * [serverId] is optional so a notification or a shared link can land here and let the connection
 * manager pick the server it is already following.
 */
@Serializable
data class HomeRoute(
    val serverId: String? = null,
    /**
     * Open the new-session sheet as soon as the home appears.
     *
     * The composer's `/new` client command needs the new-session flow, and that flow is a sheet over
     * the home rather than a destination of its own. Asking the home to open it on arrival is what
     * keeps `/new` one tap instead of one tap plus a second one.
     */
    val openNewSession: Boolean = false,
)

/**
 * The session list, with [projectId] narrowing it to one project.
 *
 * The list screen is reachable from the home's "All sessions" and from a project, and in both cases
 * it is the same list with a different filter, so the filter is a route argument rather than a
 * screen.
 */
@Serializable
data class SessionListRoute(
    val serverId: String? = null,
    val projectId: String? = null,
)

/** One session's timeline, with its composer and its request dock. */
@Serializable
data class SessionRoute(val serverId: String? = null, val sessionId: String)

/** Everything waiting across every session of a server. */
@Serializable
data class PendingRequestsRoute(val serverId: String? = null)

/**
 * The review, which is `/diff` and the file browser.
 *
 * [sessionId] is null for a repository-level review (uncommitted, committed, all) and names a
 * session for the "last turn" scope, which is a `session.diff` between two messages. [initialPath]
 * is how a changed-files link in the transcript opens one file of the review, so the link and the
 * destination are the same screen with one field apart.
 */
@Serializable
data class ReviewRoute(
    val serverId: String? = null,
    val sessionId: String? = null,
    val initialPath: String? = null,
)

/**
 * The shell panel of one checkout (features doc §30).
 *
 * The directory is the whole identity: shells are location-scoped, so a panel without one could not
 * say which checkout's commands it is listing.
 */
@Serializable
data class ShellsRoute(val serverId: String? = null, val directory: String)

/** The terminal list and the live surface of one checkout (features doc §31). */
@Serializable
data class TerminalRoute(
    val serverId: String? = null,
    val directory: String,
    /**
     * The project's `commands.start`, offered as the screen's quick action.
     *
     * It is passed rather than read from the store so the route is the only place that knows which
     * project a session belongs to, and the terminal does not have to.
     */
    val startCommand: String? = null,
)

/** The session family tree of one session, with parent, child and sibling navigation. */
@Serializable
data class SubagentsRoute(val serverId: String? = null, val sessionId: String)

/**
 * The worktrees of one project, and the way to move a session into one (features doc §29).
 *
 * [sessionId] is the session the "move here" rows act on, or `null` when the panel was opened from a
 * project rather than from a session.
 */
@Serializable
data class WorktreesRoute(
    val serverId: String? = null,
    val projectId: String,
    val sessionId: String? = null,
)

/** A session's persistent terminals, which are experimental and capability-gated (features doc §32). */
@Serializable
data class SessionTerminalsRoute(val serverId: String? = null, val sessionId: String)

/**
 * One project's settings: name, icon, start command and canonical checkout (`project.update`).
 *
 * [fromSessionId] is the session the panel was opened from, so the settings can be a sheet's worth of
 * context and the timeline can be brought back; it is not part of what is written.
 */
@Serializable
data class ProjectSettingsRoute(
    val serverId: String? = null,
    val projectId: String,
    val fromSessionId: String? = null,
)

/**
 * The navigation graph.
 *
 * Phase 1's information architecture is the server registry, so it is the start destination
 * (plan §4.3). A link shared from another app, or opened from a browser, lands on the add-server
 * screen with the pairing link already in the field.
 *
 * **The driving screens are composed here rather than inside a feature.** A feature depends on the
 * core modules only, so the session screen, the composer and the request dock cannot import one
 * another; the app module is where they meet. See [SessionHost] for the session screen's composition
 * and [NewSessionHost] for the new-session sheet's.
 */
@Composable
fun OpenCodeApp(
    sharedPayload: String? = null,
    openSession: OpenSessionTarget? = null,
    openLocation: OpenLocationTarget? = null,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    composer: ComposerViewModel = hiltViewModel(),
    /**
     * The registry, for the catalog browsers.
     *
     * **Passed in rather than injected at the composable**, because it is not a `ViewModel` and
     * `hiltViewModel()` is the only injection `Routes.kt` does. [MainActivity] already holds it, and a
     * composable that reached for it itself would need an `EntryPoint` for no gain.
     */
    serverDataSets: ServerDataRegistry,
) {
    LaunchedEffect(sharedPayload) {
        if (!sharedPayload.isNullOrBlank()) {
            navController.navigate(AddServerRoute(initialUrl = sharedPayload))
        }
    }

    // A tapped notification lands on its session, switching to its server first so the timeline is
    // bound to the right one. `launchSingleTop` keeps a second tap from stacking two copies.
    LaunchedEffect(openSession) {
        val target = openSession ?: return@LaunchedEffect
        target.serverId?.let { navController.navigate(HomeRoute(it)) { launchSingleTop = true } }
        navController.navigate(SessionRoute(target.serverId, target.sessionId)) { launchSingleTop = true }
    }

    // A finished shell command names a checkout rather than a session, so it lands on that checkout's
    // command panel. Switching server first is the same reason as above.
    LaunchedEffect(openLocation) {
        val target = openLocation ?: return@LaunchedEffect
        target.serverId?.let { navController.navigate(HomeRoute(it)) { launchSingleTop = true } }
        navController.navigate(ShellsRoute(target.serverId, target.directory)) { launchSingleTop = true }
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

        composable<HomeRoute> { entry ->
            val route = entry.toRoute<HomeRoute>()
            var newSessionOpen by remember { mutableStateOf(route.openNewSession) }
            HomeRoute(
                serverId = route.serverId,
                onSessionClick = { sessionId ->
                    navController.navigate(SessionRoute(route.serverId, sessionId))
                },
                onAllSessionsClick = { serverId ->
                    navController.navigate(SessionListRoute(serverId = serverId))
                },
                onNewSessionClick = { newSessionOpen = true },
                onPendingRequestsClick = { serverId ->
                    navController.navigate(PendingRequestsRoute(serverId))
                },
                // The plan's "Manage" section (§4.3). The app module owns the graph, so the feature
                // hands the destination over and this is where it becomes a route.
                onManageClick = { directory, destination ->
                    when (destination) {
                        ManageDestination.ACCOUNTS ->
                            navController.navigate(ConnectRoute(route.serverId, directory))

                        ManageDestination.PROVIDERS ->
                            navController.navigate(ProvidersRoute(route.serverId, directory))

                        ManageDestination.MCP ->
                            navController.navigate(McpRoute(route.serverId, directory))

                        ManageDestination.PLUGINS ->
                            navController.navigate(PluginsRoute(route.serverId, directory))

                        ManageDestination.WEB_SEARCH ->
                            navController.navigate(WebSearchRoute(route.serverId, directory))

                        // ---------------------------------------------------------------- Phase 9
                        ManageDestination.CONFIGURATION ->
                            navController.navigate(ConfigRoute(route.serverId, directory))

                        ManageDestination.AGENTS ->
                            navController.navigate(CatalogRoute(route.serverId, directory, "agents"))

                        ManageDestination.DEFINITIONS ->
                            navController.navigate(CatalogRoute(route.serverId, directory, "commands"))

                        ManageDestination.PERMISSIONS ->
                            navController.navigate(PermissionsRoute(route.serverId, directory))

                        // Maintenance is server-wide, so it takes no directory: it lists what the server
                        // has loaded and what would be dropped, which is a property of the server.
                        ManageDestination.MAINTENANCE ->
                            navController.navigate(MaintenanceRoute(route.serverId))
                    }
                },
            )
            if (newSessionOpen) {
                NewSessionHost(
                    onCreated = { sessionId ->
                        newSessionOpen = false
                        navController.navigate(SessionRoute(route.serverId, sessionId))
                    },
                    onDismiss = { newSessionOpen = false },
                )
            }
        }

        composable<SessionListRoute> { entry ->
            val route = entry.toRoute<SessionListRoute>()
            SessionListRoute(
                serverId = route.serverId,
                projectId = route.projectId,
                onSessionClick = { sessionId ->
                    navController.navigate(SessionRoute(route.serverId, sessionId))
                },
            )
        }

        composable<SessionRoute> { entry ->
            val route = entry.toRoute<SessionRoute>()
            SessionHost(
                sessionId = route.sessionId,
                onNavigateBack = { navController.popBackStack() },
                onSessionDeleted = { navController.popBackStack() },
                // The composer's client commands: `/sessions` and `/new` navigate, and the
                // composition root is the only place that knows this graph.
                onOpenSessionList = {
                    navController.navigate(SessionListRoute(serverId = route.serverId))
                },
                onNewSession = {
                    // `/new`: the new-session flow is a sheet over the home, so this is the home with
                    // the sheet already open. `popUpTo` the session so Back returns where it was.
                    navController.navigate(HomeRoute(route.serverId, openNewSession = true)) {
                        popUpTo(SessionRoute(route.serverId, route.sessionId)) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenReview = { path ->
                    // `/diff` and a changed-files link both land here; the link adds the file.
                    navController.navigate(
                        ReviewRoute(
                            serverId = route.serverId,
                            sessionId = route.sessionId,
                            initialPath = path,
                        ),
                    )
                },
                // The subagent strip opens the child session in place, which is the only place a
                // subagent's transcript is read from.
                onOpenChild = { childId ->
                    navController.navigate(SessionRoute(route.serverId, childId)) { launchSingleTop = true }
                },
                onOpenShells = { directory ->
                    navController.navigate(ShellsRoute(route.serverId, directory))
                },
                onOpenTerminal = { directory, startCommand ->
                    navController.navigate(TerminalRoute(route.serverId, directory, startCommand))
                },
                onOpenSubagents = {
                    navController.navigate(SubagentsRoute(route.serverId, route.sessionId))
                },
                onOpenWorktrees = { projectId ->
                    navController.navigate(WorktreesRoute(route.serverId, projectId, route.sessionId))
                },
                onOpenSessionTerminals = {
                    navController.navigate(SessionTerminalsRoute(route.serverId, route.sessionId))
                },
                // Phase 9, from the session: its own permission rules and its instruction entries are
                // scoped to a session rather than to a checkout, so Manage cannot reach them.
                onOpenSessionPermissions = { projectId: String?, directory: String ->
                    navController.navigate(
                        PermissionsRoute(
                            serverId = route.serverId,
                            directory = directory,
                            projectID = projectId,
                            sessionID = route.sessionId,
                        ),
                    )
                },
                onOpenSessionInstructions = {
                    navController.navigate(InstructionsRoute(route.serverId, route.sessionId))
                },
                onOpenProjectSettings = { projectId ->
                    navController.navigate(
                        ProjectSettingsRoute(
                            serverId = route.serverId,
                            projectId = projectId,
                            fromSessionId = route.sessionId,
                        ),
                    )
                },
                onUndoConfirmed = { messageId -> composer.stageUndo(messageId) },
                // A fork is a new session id, so the graph navigates to it rather than the screen
                // re-rendering the one it is on. The view model publishes the id once.
                onForked = { sessionId -> navController.navigate(SessionRoute(route.serverId, sessionId)) },
            )
        }

        composable<ReviewRoute> { entry ->
            val route = entry.toRoute<ReviewRoute>()
            ReviewHost(
                sessionId = route.sessionId,
                initialPath = route.initialPath,
                onNavigateBack = { navController.popBackStack() },
                onAttachFile = { path, name, type ->
                    composer.attachServerFile(path, name, type)
                    navController.popBackStack()
                },
                onAttachLines = { path, name, type, range ->
                    composer.attachServerFileWithRange(path, name, type, range)
                    navController.popBackStack()
                },
            )
        }

        composable<PendingRequestsRoute> { entry ->
            val route = entry.toRoute<PendingRequestsRoute>()
            PendingRequestsHost(
                onNavigateBack = { navController.popBackStack() },
                onOpenSession = { sessionId ->
                    navController.navigate(SessionRoute(route.serverId, sessionId))
                },
            )
        }

        composable<ShellsRoute> { entry ->
            val route = entry.toRoute<ShellsRoute>()
            ShellsHost(
                directory = route.directory,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<TerminalRoute> { entry ->
            val route = entry.toRoute<TerminalRoute>()
            TerminalHost(
                directory = route.directory,
                startCommand = route.startCommand,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<SubagentsRoute> { entry ->
            val route = entry.toRoute<SubagentsRoute>()
            SubagentsHost(
                sessionId = route.sessionId,
                onOpenSession = { sessionId ->
                    navController.navigate(SessionRoute(route.serverId, sessionId))
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<WorktreesRoute> { entry ->
            val route = entry.toRoute<WorktreesRoute>()
            WorktreesHost(
                projectId = route.projectId,
                sessionId = route.sessionId,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<SessionTerminalsRoute> { entry ->
            val route = entry.toRoute<SessionTerminalsRoute>()
            SessionTerminalsHost(
                sessionId = route.sessionId,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<ProjectSettingsRoute> { entry ->
            val route = entry.toRoute<ProjectSettingsRoute>()
            ProjectSettingsHost(
                projectId = route.projectId,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // ------------------------------------------------------------------ Phase 8 destinations
        //
        // The "Manage" section of the plan (§4.3). All five are per-directory, because every catalog
        // behind them is location-scoped, and the two that cross a feature boundary — an MCP server's
        // OAuth and a resource attached to a prompt — are joined *here* rather than in the feature,
        // which is the deviation a feature may not import another feature (see `ExecutionHost`).

        composable<ConnectRoute> { entry ->
            val route = entry.toRoute<ConnectRoute>()
            ConnectHost(
                directory = route.directory,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<McpRoute> { entry ->
            val route = entry.toRoute<McpRoute>()
            McpHost(
                directory = route.directory,
                onNavigateBack = { navController.popBackStack() },
                // A `needs_auth` server names the integration that owns the flow, so this opens the
                // accounts screen pointed at it rather than a second, near-identical login sheet.
                onAuthenticate = { integrationId ->
                    navController.navigate(ConnectRoute(route.serverId, route.directory))
                },
                onAttachResource = { server, name, uri ->
                    // The composer owns prompt contents, so an attached resource is a hand-off and
                    // not a write. The pending-requests inbox is where the composer is reachable from
                    // a manage screen, and it is also the screen a form can be answered from.
                    navController.navigate(PendingRequestsRoute(route.serverId))
                },
            )
        }

        composable<PluginsRoute> { entry ->
            val route = entry.toRoute<PluginsRoute>()
            PluginsHost(
                directory = route.directory,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<ProvidersRoute> { entry ->
            val route = entry.toRoute<ProvidersRoute>()
            ProvidersHost(
                directory = route.directory,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<WebSearchRoute> { entry ->
            val route = entry.toRoute<WebSearchRoute>()
            WebSearchHost(
                directory = route.directory,
                onNavigateBack = { navController.popBackStack() },
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
                onNavigateBack = { navController.popBackStack() },
                onOpenEditor = { path ->
                    navController.navigate(ConfigEditorRoute(route.serverId, route.directory, path))
                },
                onOpenDefinitions = {
                    navController.navigate(DefinitionRoute(route.serverId, route.directory, "agent", ""))
                },
                onOpenCatalogs = { navController.navigate(CatalogRoute(route.serverId, route.directory, "agents")) },
                onOpenPermissions = {
                    navController.navigate(PermissionsRoute(route.serverId, route.directory))
                },
            )
        }

        composable<ConfigEditorRoute> { entry ->
            val route = entry.toRoute<ConfigEditorRoute>()
            ConfigEditorHost(
                directory = route.directory,
                path = route.path,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<DefinitionRoute> { entry ->
            val route = entry.toRoute<DefinitionRoute>()
            DefinitionHost(
                directory = route.directory,
                kind = definitionKindOf(route.kind),
                name = route.name,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<CatalogRoute> { entry ->
            val route = entry.toRoute<CatalogRoute>()
            val dataSet: ServerDataSet? = serverDataSets.active.collectAsStateWithLifecycle().value
            val directory = route.directory
            var tab by remember(route.tab) { mutableStateOf(catalogOf(route.tab)) }
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
            LaunchedEffect(directory) {
                dataSet?.agents(directory)?.sync()
                dataSet?.composerCatalogs?.commands(directory)?.sync()
                dataSet?.composerCatalogs?.skills(directory)?.sync()
                dataSet?.composerCatalogs?.references(directory)?.sync()
            }
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
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<PermissionsRoute> { entry ->
            val route = entry.toRoute<PermissionsRoute>()
            PermissionsHost(
                directory = route.directory,
                projectID = route.projectID,
                sessionID = route.sessionID,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<InstructionsRoute> { entry ->
            val route = entry.toRoute<InstructionsRoute>()
            InstructionsHost(
                sessionID = route.sessionID,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<MaintenanceRoute> { entry ->
            val route = entry.toRoute<MaintenanceRoute>()
            MaintenanceHost(onNavigateBack = { navController.popBackStack() })
        }
    }
}

/** The global pending-requests inbox, with the answers wired to the request center. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingRequestsHost(
    onNavigateBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    requests: PendingRequestsViewModel = hiltViewModel(),
    composer: ComposerViewModel = hiltViewModel(),
) {
    val pending by requests.pending.collectAsStateWithLifecycle()
    val titles by requests.sessionTitles.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val actions = RequestActions(
        onReplyOnce = { composer.replyPermission(it.request, PermissionReply.Once) },
        onReplyAlways = { composer.replyPermission(it.request, PermissionReply.Always) },
        onReject = { request, feedback ->
            composer.replyPermission(request.request, PermissionReply.Reject, feedback)
        },
        onSubmitForm = { form, answer -> composer.submitForm(form.form, answer) },
        onCancelForm = { composer.cancelForm(it.form) },
        onOpenLink = { uriHandler.openUri(it) },
        onOpenSession = onOpenSession,
    )
    PendingRequestsScreen(
        requests = pending,
        sessionTitles = titles,
        actions = actions,
        onNavigateBack = onNavigateBack,
    )
}

/** The new-session sheet, which navigates to the session it created. */
@Composable
private fun NewSessionHost(
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: NewSessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val created by viewModel.created.collectAsStateWithLifecycle()
    val favorites by viewModel.modelFavorites.collectAsStateWithLifecycle()
    val recents by viewModel.modelRecents.collectAsStateWithLifecycle()

    LaunchedEffect(created) {
        val sessionId = created ?: return@LaunchedEffect
        viewModel.reset()
        onCreated(sessionId)
    }

    NewSessionSheet(
        state = state,
        favorites = favorites,
        recents = recents,
        modelSearch = "",
        onTitleChange = viewModel::setTitle,
        onSelectProject = viewModel::selectLocation,
        onSelectDirectory = { directory -> viewModel.selectLocation(LocationChoice.Browsed(directory, null)) },
        onOpenBrowser = viewModel::openBrowser,
        onSelectAgent = viewModel::selectAgent,
        onSelectModel = viewModel::selectModel,
        onBrowseUp = viewModel::goUp,
        onBrowseEnter = viewModel::enterDirectory,
        onBrowseUse = viewModel::useBrowsedDirectory,
        onBrowseDismiss = viewModel::closeBrowser,
        onCreate = viewModel::create,
        onDismiss = {
            viewModel.reset()
            onDismiss()
        },
    )
}

/**
 * The flow a catalog screen reads before the app is following a server.
 *
 * **Loading rather than empty**, because an empty list renders as "this project has no agents" and the
 * true state is "there is no server yet" — two claims a user would act on differently.
 */
private fun <T> loadingFlow(): StateFlow<SyncedState<T>> =
    MutableStateFlow(SyncedState(status = SyncStatus.Loading))
