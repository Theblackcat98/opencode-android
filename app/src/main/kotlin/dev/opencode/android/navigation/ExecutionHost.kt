package dev.opencode.android.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.data.terminal.TerminalInput
import dev.opencode.android.feature.execution.R
import dev.opencode.android.feature.execution.SessionTerminalsPane
import dev.opencode.android.feature.execution.SessionTerminalsViewModel
import dev.opencode.android.feature.execution.ShellsScreen
import dev.opencode.android.feature.execution.ShellsViewModel
import dev.opencode.android.feature.execution.SubagentsScreen
import dev.opencode.android.feature.execution.SubagentsViewModel
import dev.opencode.android.feature.execution.TerminalChannel
import dev.opencode.android.feature.execution.TerminalScreen
import dev.opencode.android.feature.execution.TerminalViewModel
import dev.opencode.android.feature.execution.WorktreesScreen
import dev.opencode.android.feature.execution.WorktreesViewModel

/**
 * The execution destinations, and the composition root for Phase 7 (plan §6).
 *
 * **Four screens, one per location or project, and none of them composable state.** The shells panel
 * and the terminal list are scoped to a checkout, the worktree panel to a project, and the subagent
 * tree to a session; the graph knows which is which because the route says so, and the view models read
 * the rest from the stores.
 *
 * **The terminal's channel is remembered here, not in the view model.** The channel holds a `WebView`
 * reference and a buffer, both of which are tied to the composition, and a view model that outlives the
 * composition would keep writing into a detached WebView. The *socket* is the view model's, because a
 * terminal has to survive a rotation; the page is the composition's, because it must not.
 */
@Composable
fun ShellsHost(
    directory: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShellsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(directory) { viewModel.openPanel(directory) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.openPanel(directory) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.close() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { ExecutionTopBar(title = state.directory ?: directory, onNavigateBack = onNavigateBack) },
    ) { padding ->
        ShellsScreen(
            state = state,
            onDraftChange = viewModel::setDraft,
            onRun = viewModel::run,
            onOpen = viewModel::openCommand,
            onRequestKill = viewModel::requestKill,
            onConfirmKill = viewModel::confirmKill,
            onCancelKill = viewModel::cancelKill,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The terminal destination, with the extra-keys row and the page wired. */
@Composable
fun TerminalHost(
    directory: String,
    startCommand: String? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channel = remember { TerminalChannel() }

    LaunchedEffect(directory, startCommand) { viewModel.open(directory, startCommand) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { ExecutionTopBar(title = directory, onNavigateBack = onNavigateBack) },
    ) { padding ->
        TerminalScreen(
            state = state,
            channel = channel,
            onOpenTerminal = viewModel::openTerminal,
            onNewTerminal = viewModel::openPicker,
            onCreate = { command, args -> viewModel.createTerminal(command, args) },
            onRunProjectStart = viewModel::createFromProjectStart,
            onResize = viewModel::resize,
            // The extra-keys row produces a key *sequence*; the bytes are a pure fold, and they are
            // sent as one frame so a Ctrl held over three keys is three control codes rather than
            // three round trips the terminal would have to interleave with its own echo.
            onKeys = { keys -> viewModel.sendInput(TerminalInput.encode(keys)) },
            onReconnect = viewModel::reconnect,
            onRequestKill = viewModel::requestKill,
            onConfirmKill = viewModel::confirmKill,
            onCancelKill = viewModel::cancelKill,
            onDismissError = viewModel::dismissError,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The subagent tree for one session, with the navigation the plan asks for. */
@Composable
fun SubagentsHost(
    sessionId: String,
    onOpenSession: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubagentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) { viewModel.open(sessionId) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ExecutionTopBar(
                title = state.parentTitle ?: sessionId,
                onNavigateBack = onNavigateBack,
            )
        },
    ) { padding ->
        SubagentsScreen(
            state = state,
            onSelect = { id -> viewModel.select(id); onOpenSession(id) },
            onParent = {
                viewModel.selectParent()
                viewModel.state.value.parentID?.let(onOpenSession)
            },
            onPrevious = {
                viewModel.selectSibling(-1)
                viewModel.state.value.previousSibling?.let(onOpenSession)
            },
            onNext = {
                viewModel.selectSibling(1)
                viewModel.state.value.nextSibling?.let(onOpenSession)
            },
            modifier = Modifier.padding(padding),
        )
    }
}

/** The worktree panel for one project, with "move a session into it" wired to the graph. */
@Composable
fun WorktreesHost(
    projectId: String,
    sessionId: String? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorktreesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(projectId) { viewModel.open(projectId) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ExecutionTopBar(
                title = state.projectName ?: projectId,
                onNavigateBack = onNavigateBack,
            )
        },
    ) { padding ->
        WorktreesScreen(
            state = state,
            onFromChange = viewModel::setFrom,
            onBranchChange = viewModel::setBranch,
            onNameChange = viewModel::setName,
            onCreate = viewModel::create,
            onRefresh = viewModel::refresh,
            onRequestRemove = viewModel::requestRemove,
            onRemove = viewModel::remove,
            onForceRemove = viewModel::forceRemove,
            onCancelRemove = viewModel::cancelRemove,
            onMoveSession = sessionId?.let { id -> { directory -> viewModel.moveSession(id, directory) } },
            modifier = Modifier.padding(padding),
        )
    }
}

/** A session's persistent terminals, behind the experimental switch and the capability probe. */
@Composable
fun SessionTerminalsHost(
    sessionId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionTerminalsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) { viewModel.open(sessionId) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ExecutionTopBar(
                title = stringResource(R.string.session_terminals_title),
                onNavigateBack = onNavigateBack,
            )
        },
    ) { padding ->
        SessionTerminalsPane(
            state = state,
            onCreate = { viewModel.create(title = "shell") },
            onRead = viewModel::read,
            onRemove = viewModel::remove,
            modifier = Modifier.padding(padding),
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ExecutionTopBar(title: String, onNavigateBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                )
            }
        },
    )
}
