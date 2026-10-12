package dev.opencode.android.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import dev.opencode.android.feature.composer.ui.ComposerViewModel
import dev.opencode.android.feature.execution.ProjectSettingsHost
import dev.opencode.android.feature.sessions.ui.HomeRoute
import dev.opencode.android.feature.sessions.ui.SessionListRoute
import dev.opencode.android.feature.sessions.ui.SessionTabUi

/**
 * The session graph: a session and everything pushed above it — the review, the file
 * browser, the pending-requests inbox, shells, terminals, subagents, worktrees and project settings.
 */
@Composable
fun NavGraphBuilder.sessionGraph(deps: NavGraphDeps) {
    navigation<SessionGraph>(startDestination = SessionRoute) {
        composable<SessionRoute> { entry ->
            val route = entry.toRoute<SessionRoute>()
            // This entry's composer, taken with the entry as its owner. It is the instance `SessionHost`
            // opens the session in, and the one the review below this session hands its comments and
            // attachments to (see `ReviewRoute`), so nothing in the graph holds a second, unopened one.
            val composer = hiltViewModel<ComposerViewModel>(entry)
            // The server this session is on: the route's, or the active one, which is the one the screen reads.
            val server = route.serverId ?: deps.activeServerId
            val current = server?.let { OpenSessionTab(it, route.sessionId) }
            // Showing a session lists it as a tab. A listed one stays where it is, so a rotation does not reorder it.
            LaunchedEffect(current) {
                current?.let { deps.onSessionTabsChange(deps.sessionTabs.opened(it)) }
            }
            val tabsOnServer = server?.let { deps.sessionTabs.onServer(it) }.orEmpty()
            // A tap on a session in the tab strip or in the list replaces this entry with the tapped session,
            // which is the same navigation for both. The session on screen is not navigated to again: that would
            // pop it and build it afresh.
            val showInPlace: (String) -> Unit = { sessionId ->
                if (server != null && sessionId != route.sessionId) {
                    deps.navController.navigate(SessionRoute(server, sessionId)) {
                        popUpTo(SessionRoute(route.serverId, route.sessionId)) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            // A wide window draws the session list beside the session; a narrow one draws the session alone,
            // as before.
            val listShown = sessionListShown(
                screenWidthDp = LocalConfiguration.current.screenWidthDp,
                sessionOnActiveServer = server != null && server == deps.activeServerId,
            )
            val sessionScreen: @Composable (Modifier) -> Unit = { modifier ->
                SessionHost(
                    sessionId = route.sessionId,
                    composer = composer,
                    onNavigateBack = { deps.navController.popBackStack() },
                    onSessionDeleted = {
                        if (current != null) deps.onSessionTabsChange(deps.sessionTabs.forgotten(current))
                        deps.navController.popBackStack()
                    },
                    // The composer's client commands: `/sessions` and `/new` navigate, and the
                    // composition root is the only place that knows this graph.
                    onOpenSessionList = {
                        deps.navController.navigate(SessionListRoute(serverId = route.serverId)) { launchSingleTop = true }
                    },
                    onOpenCommandPalette = deps.onOpenCommandPalette,
                    onNewSession = {
                        // `/new`: the new-session flow is a sheet over the home, so this is the home with
                        // the sheet already open. `popUpTo` the session so Back returns where it was.
                        deps.navController.navigate(HomeRoute(route.serverId, openNewSession = true)) {
                            popUpTo(SessionRoute(route.serverId, route.sessionId)) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onOpenReview = { path ->
                        // `/diff` and a changed-files link both land here; the link adds the file.
                        deps.navController.navigate(
                            ReviewRoute(
                                serverId = route.serverId,
                                sessionId = route.sessionId,
                                initialPath = path,
                            ),
                        ) { launchSingleTop = true }
                    },
                    // The subagent strip opens the child session in place, which is the only place a
                    // subagent's transcript is read from.
                    onOpenChild = { childId ->
                        deps.navController.navigate(SessionRoute(route.serverId, childId)) { launchSingleTop = true }
                    },
                    onOpenShells = { directory ->
                        deps.navController.navigate(ShellsRoute(route.serverId, directory)) { launchSingleTop = true }
                    },
                    onOpenTerminal = { directory, startCommand ->
                        deps.navController.navigate(TerminalRoute(route.serverId, directory, startCommand)) { launchSingleTop = true }
                    },
                    onOpenSubagents = {
                        deps.navController.navigate(SubagentsRoute(route.serverId, route.sessionId)) { launchSingleTop = true }
                    },
                    onOpenWorktrees = { projectId ->
                        deps.navController.navigate(WorktreesRoute(route.serverId, projectId, route.sessionId)) { launchSingleTop = true }
                    },
                    onOpenSessionTerminals = {
                        deps.navController.navigate(SessionTerminalsRoute(route.serverId, route.sessionId)) { launchSingleTop = true }
                    },
                    // The tools sheet's Files tile: the file browser for the session's checkout.
                    onOpenFiles = {
                        deps.navController.navigate(FilesRoute(route.serverId, route.sessionId)) { launchSingleTop = true }
                    },
                    // Phase 9, from the session: its own permission rules and its instruction entries are
                    // scoped to a session rather than to a checkout, so Manage cannot reach them.
                    onOpenSessionPermissions = { projectId: String?, directory: String ->
                        deps.navController.navigate(
                            PermissionsRoute(
                                serverId = route.serverId,
                                directory = directory,
                                projectID = projectId,
                                sessionID = route.sessionId,
                            ),
                        ) { launchSingleTop = true }
                    },
                    onOpenSessionInstructions = {
                        deps.navController.navigate(InstructionsRoute(route.serverId, route.sessionId)) { launchSingleTop = true }
                    },
                    onOpenProjectSettings = { projectId ->
                        deps.navController.navigate(
                            ProjectSettingsRoute(
                                serverId = route.serverId,
                                projectId = projectId,
                                fromSessionId = route.sessionId,
                            ),
                        ) { launchSingleTop = true }
                    },
                    // A fork is a new session id, so the graph navigates to it rather than the screen
                    // re-rendering the one it is on. The view model publishes the id once.
                    onForked = { sessionId ->
                        deps.navController.navigate(SessionRoute(route.serverId, sessionId)) { launchSingleTop = true }
                    },
                    tabs = tabsOnServer.map { tab ->
                        SessionTabUi(
                            id = tab.sessionId,
                            title = deps.sessionInfo[tab.sessionId]?.title?.takeIf { it.isNotBlank() }
                                ?: deps.untitledSession,
                            selected = tab == current,
                        )
                    },
                    onSelectTab = showInPlace,
                    onCloseTab = { sessionId ->
                        if (server != null) {
                            val closing = deps.sessionTabs.closed(OpenSessionTab(server, sessionId), current)
                            deps.onSessionTabsChange(closing.tabs)
                            // Closing a tab that is not on screen only removes it. Closing the one on screen moves
                            // on.
                            when (closing) {
                                is TabClose.Stay -> Unit

                                is TabClose.Open -> deps.navController.navigate(
                                    SessionRoute(closing.next.serverId, closing.next.sessionId),
                                ) {
                                    popUpTo(SessionRoute(route.serverId, route.sessionId)) { inclusive = true }
                                    launchSingleTop = true
                                }

                                is TabClose.Home -> deps.navController.navigate(HomeRoute(closing.serverId)) {
                                    popUpTo(SessionRoute(route.serverId, route.sessionId)) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    },
                    modifier = modifier,
                )
            }
            // The session screen takes its modifier from the layout. Narrow windows give it none, as before the
            // list existed.
            if (listShown) {
                Row(Modifier.fillMaxSize()) {
                    SessionListPaneHost(
                        deps.serverDataSets = deps.serverDataSets,
                        untitledTitle = deps.untitledSession,
                        selectedId = route.sessionId,
                        listState = deps.sessionListState,
                        onSelect = showInPlace,
                        modifier = Modifier.fillMaxHeight(),
                    )
                    VerticalDivider(modifier = Modifier.fillMaxHeight())
                    sessionScreen(Modifier.weight(1f))
                }
            } else {
                sessionScreen(Modifier)
            }
        }

        composable<FilesRoute>(
            enterTransition = { DestinationTransitions.Container.enter },
            exitTransition = { DestinationTransitions.Container.exit },
            popEnterTransition = { DestinationTransitions.Container.popEnter },
            popExitTransition = { DestinationTransitions.Container.popExit },
        ) { entry ->
            val route = entry.toRoute<FilesRoute>()
            // The session's composer, from the session's entry under this one, for the reason `ReviewRoute` takes it.
            val sessionEntry = remember(entry) { deps.navController.sessionEntryOf(route.serverId, route.sessionId) }
            if (sessionEntry == null) {
                LaunchedEffect(entry) { deps.navController.popBackStack() }
            } else {
                FilesHost(
                    sessionId = route.sessionId,
                    composer = hiltViewModel<ComposerViewModel>(sessionEntry),
                    onNavigateBack = { deps.navController.popBackStack() },
                )
            }
        }

        composable<ReviewRoute>(
            enterTransition = { DestinationTransitions.Container.enter },
            exitTransition = { DestinationTransitions.Container.exit },
            popEnterTransition = { DestinationTransitions.Container.popEnter },
            popExitTransition = { DestinationTransitions.Container.popExit },
        ) { entry ->
            val route = entry.toRoute<ReviewRoute>()
            // The review is opened from a session, and what it produces (comments, attached files) is that
            // session's composer's to hold: so the composer is the one in the session's back-stack entry,
            // which is still on the stack under this one. Asking for a composer here would build a third,
            // whose session nobody opened.
            val sessionEntry = remember(entry) { deps.navController.sessionEntryOf(route) }
            if (sessionEntry == null) {
                // Nothing to hand a comment or a file to, so there is nothing to show: a review that
                // accepted them into a composer nobody sees would be the silent failure this avoids.
                LaunchedEffect(entry) { deps.navController.popBackStack() }
            } else {
                ReviewHost(
                    sessionId = route.sessionId,
                    composer = hiltViewModel<ComposerViewModel>(sessionEntry),
                    initialPath = route.initialPath,
                    onNavigateBack = { deps.navController.popBackStack() },
                    onAttached = { deps.navController.popBackStack() },
                )
            }
        }

        composable<PendingRequestsRoute> { entry ->
            val route = entry.toRoute<PendingRequestsRoute>()
            PendingRequestsHost(
                onNavigateBack = { deps.navController.popBackStack() },
                onOpenSession = { sessionId ->
                    deps.navController.navigate(SessionRoute(route.serverId, sessionId)) { launchSingleTop = true }
                },
            )
        }

        composable<ShellsRoute> { entry ->
            val route = entry.toRoute<ShellsRoute>()
            ShellsHost(
                directory = route.directory,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<TerminalRoute> { entry ->
            val route = entry.toRoute<TerminalRoute>()
            TerminalHost(
                directory = route.directory,
                startCommand = route.startCommand,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<SubagentsRoute> { entry ->
            val route = entry.toRoute<SubagentsRoute>()
            SubagentsHost(
                sessionId = route.sessionId,
                onOpenSession = { sessionId ->
                    deps.navController.navigate(SessionRoute(route.serverId, sessionId)) { launchSingleTop = true }
                },
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<WorktreesRoute> { entry ->
            val route = entry.toRoute<WorktreesRoute>()
            WorktreesHost(
                projectId = route.projectId,
                sessionId = route.sessionId,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<SessionTerminalsRoute> { entry ->
            val route = entry.toRoute<SessionTerminalsRoute>()
            SessionTerminalsHost(
                sessionId = route.sessionId,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }

        composable<ProjectSettingsRoute> { entry ->
            val route = entry.toRoute<ProjectSettingsRoute>()
            ProjectSettingsHost(
                projectId = route.projectId,
                onNavigateBack = { deps.navController.popBackStack() },
            )
        }
    }
}
