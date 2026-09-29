package dev.opencode.android.feature.sessions.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * The per-server home, wired to the stores.
 *
 * The screen itself is stateless ([HomeScreen]) and takes its state as a parameter, so the whole
 * thing is testable and screenshot-able without a server, and this wrapper is the only place that
 * knows about Hilt and the connection registry.
 *
 * **The server is followed here, not by the navigation graph.** Which server is active is the
 * connection manager's answer, so a deep link into a server that is not active yet still lands on the
 * right home, and [serverId] is the hint rather than the instruction.
 */
@Composable
fun HomeRoute(
    serverId: String?,
    onSessionClick: (String) -> Unit,
    onAllSessionsClick: (String) -> Unit,
    onNewSessionClick: (String) -> Unit,
    onPendingRequestsClick: (String) -> Unit,
    /**
     * The plan's "Manage" destinations (plan §4.3, Phase 8).
     *
     * **Passed in rather than navigated to here**, because a feature may not import another one and
     * the app module is what owns the graph. The directory is the home's own project directory,
     * which is what every one of those screens is scoped by.
     */
    onManageClick: ((String, ManageDestination) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    listViewModel: SessionListViewModel = hiltViewModel(),
    requests: PendingRequestsViewModel = hiltViewModel(),
) {
    val state by listViewModel.state.collectAsStateWithLifecycle()
    val manageDirectory = state.projects.firstOrNull()?.canonical
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()
    val pending by requests.pending.collectAsStateWithLifecycle()

    LaunchedEffect(serverId) { viewModel.select(serverId) }

    HomeScreen(
        projects = state.projects,
        rows = state.rows,
        serverName = serverName,
        loading = state.paging.loading,
        onProjectClick = { onAllSessionsClick(it) },
        onSessionClick = onSessionClick,
        onAllSessionsClick = { serverId?.let(onAllSessionsClick) },
        modifier = modifier,
        pendingRequests = pending.size,
        onNewSessionClick = { serverId?.let(onNewSessionClick) },
        onPendingRequestsClick = { serverId?.let(onPendingRequestsClick) },
        onManageClick = onManageClick?.let { handler ->
            // A checkout is the scope of every Manage screen, so the first project's canonical
            // directory is the one the server is working in. With no project there is no checkout to
            // manage, and the row is simply not composed.
            { destination: ManageDestination -> handler(manageDirectory.orEmpty(), destination) }
        },
        manageDirectory = manageDirectory,
    )
}

/**
 * The session list, derived from [dev.opencode.android.core.data.server.SessionStore].
 *
 * The ViewModel owns no state of its own beyond the search text: everything else is a projection of
 * the store, so the list cannot disagree with the events that produced it (plan §4.2, "The server
 * echoes; the client never guesses").
 */
@Composable
fun SessionListRoute(
    serverId: String? = null,
    projectId: String? = null,
    onSessionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionListViewModel = hiltViewModel(),
    home: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Both the server and the project have to be applied, and the server first: a filter needs the
    // store it filters. Phase 2's wrapper only passed the project, so a deep link into a server that
    // was not the active one silently showed the wrong server's sessions.
    LaunchedEffect(serverId) { home.select(serverId) }
    LaunchedEffect(projectId) { if (projectId != null) viewModel.selectProject(projectId) }

    SessionListScreen(
        state = state,
        onSearchChange = viewModel::setSearch,
        onToggleRootsOnly = viewModel::toggleRootsOnly,
        onProjectSelected = viewModel::selectProject,
        onDirectorySelected = viewModel::selectDirectory,
        onLoadMore = viewModel::loadMore,
        onSessionClick = onSessionClick,
        modifier = modifier,
    )
}
