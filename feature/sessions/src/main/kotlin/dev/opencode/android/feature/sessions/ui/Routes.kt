package dev.opencode.android.feature.sessions.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * The per-server home, wired to the stores.
 *
 * The screen itself is stateless ([HomeScreen]) and takes its state as a parameter, so the whole
 * thing is testable and screenshot-able without a server, and this wrapper is the only place that
 * knows about Hilt and the connection registry.
 */
@Composable
fun HomeRoute(
    serverId: String?,
    onSessionClick: (String) -> Unit,
    onAllSessionsClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    listViewModel: SessionListViewModel = hiltViewModel(),
) {
    val state by listViewModel.state.collectAsStateWithLifecycle()
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()

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
    )
}

/** The session list, wired to the stores. */
@Composable
fun SessionListRoute(
    projectId: String? = null,
    onSessionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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

/** One session, wired to the stores. */
@Composable
fun SessionRoute(
    sessionId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) { viewModel.open(sessionId) }

    SessionScreen(
        state = state,
        onNavigateBack = onNavigateBack,
        onLoadOlder = viewModel::loadOlder,
        onFollowChange = viewModel::setFollowing,
        modifier = modifier,
    )
}
