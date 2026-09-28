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
import dev.opencode.android.OpenSessionTarget
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.feature.composer.ui.ComposerViewModel
import dev.opencode.android.feature.composer.ui.LocationChoice
import dev.opencode.android.feature.composer.ui.NewSessionSheet
import dev.opencode.android.feature.composer.ui.NewSessionViewModel
import dev.opencode.android.feature.requests.ui.PendingRequestsScreen
import dev.opencode.android.feature.requests.ui.RequestActions
import dev.opencode.android.feature.servers.ui.AddServerScreen
import dev.opencode.android.feature.servers.ui.EditServerScreen
import dev.opencode.android.feature.servers.ui.EventInspectorScreen
import dev.opencode.android.feature.servers.ui.ServerStatusScreen
import dev.opencode.android.feature.servers.ui.ServersScreen
import dev.opencode.android.feature.sessions.ui.HomeRoute
import dev.opencode.android.feature.sessions.ui.PendingRequestsViewModel
import dev.opencode.android.feature.sessions.ui.SessionListRoute
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
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    composer: ComposerViewModel = hiltViewModel(),
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
