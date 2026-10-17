package dev.opencode.android.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.opencode.android.OpenLocationTarget
import dev.opencode.android.OpenSessionTarget
import dev.opencode.android.OpenShortcutTarget
import dev.opencode.android.R
import dev.opencode.android.ShortcutDestination
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.sync.SyncStatus
import dev.opencode.android.core.data.sync.SyncedState
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.feature.admin.AdminCatalog
import dev.opencode.android.feature.composer.ui.ComposerViewModel
import dev.opencode.android.feature.composer.ui.LocationChoice
import dev.opencode.android.feature.composer.ui.NewSessionSheet
import dev.opencode.android.feature.composer.ui.NewSessionViewModel
import dev.opencode.android.feature.requests.ui.PendingRequestsScreen
import dev.opencode.android.feature.requests.ui.RequestActions
import dev.opencode.android.feature.sessions.ui.HomeRoute
import dev.opencode.android.feature.sessions.ui.ManageDestination
import dev.opencode.android.feature.sessions.ui.PendingRequestsViewModel
import dev.opencode.android.feature.sessions.ui.SessionListRoute
import dev.opencode.android.feature.sessions.ui.SettingsRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import dev.opencode.android.feature.sessions.R as SessionsR

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

/**
 * The Settings tab of the bottom bar: the server's accounts, providers, configuration, insights and maintenance,
 * which used to be the home's overflow menu.
 */
@Serializable
data class SettingsRoute(val serverId: String? = null)

/**
 * The Chat tab of the bottom bar. It resolves to the session last on screen (or the newest one) and replaces
 * itself with that [SessionRoute], so it is only ever seen while resolving or when there is nothing to open.
 */
@Serializable
data class ChatRoute(val serverId: String? = null)

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
 *
 * **Only a review with a session is offered today**, because the review hands its comments and attached
 * files to that session's composer and a review without one would have nowhere to put them. The graph
 * closes a review whose session is not on the back stack rather than showing one that drops them.
 */
@Serializable
data class ReviewRoute(
    val serverId: String? = null,
    val sessionId: String? = null,
    val initialPath: String? = null,
)

/**
 * The session's file browser, full screen. Like [ReviewRoute] it hands what it produces (attached files) to the
 * session's composer, so it is only offered while that session is on the back stack under it.
 */
@Serializable
data class FilesRoute(val serverId: String? = null, val sessionId: String)

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
 *
 * **This function takes no `ViewModel`, and must not.** A default such as `= hiltViewModel()` is evaluated
 * here, outside every `composable<…>`, so it resolves in the activity's `ViewModelStoreOwner` and not in
 * the route's back-stack entry: the instance is real, injected and never opened. A `ComposerViewModel`
 * declared here received `/undo`'s confirmation and the file browser's attachments for a session it had
 * never been told about, and each returned without a word. A ViewModel a route needs is taken *inside*
 * that route's lambda, with the entry as its owner; `tools/audit-viewmodels.mjs` fails on the other shape.
 *
 * **Every `navigate()` here sets `launchSingleTop`.** A repeated intent — a second tap on a notification,
 * a palette command run twice, a deep link re-delivered — never stacks a second copy of the screen it
 * names; navigating to the destination already on top is a no-op, and navigating to a different instance
 * of the same route still pushes.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalLayoutApi::class)
@Composable
fun OpenCodeApp(
    sharedPayload: String? = null,
    openSession: OpenSessionTarget? = null,
    openLocation: OpenLocationTarget? = null,
    openShortcut: OpenShortcutTarget? = null,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
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
            navController.navigate(AddServerRoute(initialUrl = sharedPayload)) { launchSingleTop = true }
        }
    }

    // A tapped notification lands on its session, switching to its server first so the timeline is
    // bound to the right one. The stack is reset to the servers list first, so the notification lands
    // on a clean Home -> Session rather than stacking onto whatever was open. `launchSingleTop`
    // keeps a second tap from stacking two copies.
    LaunchedEffect(openSession) {
        val target = openSession ?: return@LaunchedEffect
        target.serverId?.let {
            navController.navigate(HomeRoute(it)) {
                popUpTo(ServersRoute) { inclusive = false }
                launchSingleTop = true
            }
        }
        navController.navigate(SessionRoute(target.serverId, target.sessionId)) { launchSingleTop = true }
    }

    // A finished shell command names a checkout rather than a session, so it lands on that checkout's
    // command panel. Switching server first is the same reason as above, and the stack reset is the
    // same: a clean Home -> Shells rather than a stack onto whatever was open.
    LaunchedEffect(openLocation) {
        val target = openLocation ?: return@LaunchedEffect
        target.serverId?.let {
            navController.navigate(HomeRoute(it)) {
                popUpTo(ServersRoute) { inclusive = false }
                launchSingleTop = true
            }
        }
        navController.navigate(ShellsRoute(target.serverId, target.directory)) { launchSingleTop = true }
    }

    // A launcher shortcut lands on a root screen. The navigation itself is shared with the command palette, so
    // "New session" means the same from either (see openShortcutDestination).
    LaunchedEffect(openShortcut) {
        val target = openShortcut ?: return@LaunchedEffect
        navController.openShortcutDestination(target.destination)
    }

    // The command palette (navigation/CommandPalette.kt). Its open state is screen state, so it is a remembered
    // flag here and not a ViewModel, which this function may not take. The commands are the ones the active
    // server allows, so the server-scoped ones are listed only while there is one.
    var paletteOpen by rememberSaveable { mutableStateOf(false) }
    val activeServerId = serverDataSets.active.collectAsStateWithLifecycle().value?.serverId
    // The session tabs of this app run (SessionTabs.kt). They are kept across a rotation. Their titles come from the
    // active server's session store, which the inbox reads too, so a tab never fetches a session of its own.
    var sessionTabs by rememberSaveable(stateSaver = sessionTabsSaver) { mutableStateOf(SessionTabs()) }
    // The session list drawn beside a session on a wide window (SessionListPane.kt). Its scroll is held here, above
    // the NavHost, because a tap replaces the session's back-stack entry and an entry-level state would start again
    // at the top.
    val sessionListState = rememberLazyListState()
    // Built once, not per composition: a flow operator run in composition restarts its collection each time.
    val sessionInfoFlow = remember(serverDataSets) {
        serverDataSets.active.flatMapLatest { set -> set?.sessions?.info ?: emptyFlow() }
    }
    val sessionInfo by sessionInfoFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    val untitledSession = stringResource(SessionsR.string.session_title_untitled)
    // The dialog and the leader keys read this one list, so a leader key cannot run a command the palette hides.
    val paletteOffered = paletteCommands(hasActiveServer = activeServerId != null)
    val leader = remember { LeaderKeyMachine() }
    val leaderArmed by leader.armed
    // The machine only disarms on the next key, so a Ctrl+X followed by silence would leave the armed
    // indicator up: the wait times out here, through the same cancel() the key path uses.
    LaunchedEffect(leaderArmed) {
        if (leaderArmed) {
            delay(LEADER_TIMEOUT_MILLIS)
            leader.cancel()
        }
    }
    val onRunPaletteCommand: (PaletteCommand) -> Unit = { command ->
        when (command.id) {
            PaletteCommandIds.SERVERS -> navController.openShortcutDestination(ShortcutDestination.SERVERS)
            PaletteCommandIds.NEW_SESSION -> navController.openShortcutDestination(ShortcutDestination.NEW_SESSION)
            PaletteCommandIds.USAGE -> navController.navigate(UsageRoute(activeServerId)) { launchSingleTop = true }
            PaletteCommandIds.QUICK_ASK -> navController.navigate(QuickAskRoute(activeServerId)) { launchSingleTop = true }
            PaletteCommandIds.RPC_CONSOLE -> navController.navigate(RpcConsoleRoute(activeServerId)) { launchSingleTop = true }
            PaletteCommandIds.PAIR_DEVICE -> navController.navigate(PairDeviceRoute(activeServerId)) { launchSingleTop = true }
            PaletteCommandIds.CHANGELOG -> navController.navigate(ChangelogRoute) { launchSingleTop = true }
            PaletteCommandIds.EVENT_INSPECTOR -> navController.navigate(EventInspectorRoute()) { launchSingleTop = true }
            else -> Unit // Every id paletteCommands() returns is named above, so a new command is added to both.
        }
    }

    // A key event reaches a handler only when something under the handler has focus, so the root takes focus and
    // Ctrl+P is seen whichever screen is up. It is focused again on closing the palette, because the dialog's
    // window takes focus while it is open. Ctrl+P is read before the focused control (onPreviewKeyEvent below), so
    // it opens the palette whichever control has focus. The leader keys (navigation/LeaderKeys.kt) are also read before
    // the focused control, and stand aside while a text editor or a native view has focus (leaderKeys below), so a
    // sequence cannot fire in a field whether or not the field would have taken the key.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(paletteOpen) {
        if (!paletteOpen) rootFocus.requestFocus()
    }

    // A TUI toast (`tui.toast.show`) is shown as a snackbar. The collector follows the active server, so
    // switching servers stops the old one's toasts. The flow has no replay: a toast that arrives while the
    // app is closed is not shown when it next opens, and nothing keeps a record of it.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(serverDataSets) {
        serverDataSets.active
            .flatMapLatest { set -> set?.tui?.toasts ?: emptyFlow() }
            .collect { toast ->
                val text = toastText(toast)
                if (text.isNotBlank()) snackbarHostState.showSnackbar(text, duration = toastDuration(toast.level))
            }
    }

    // A TUI command (`tui.command.execute`) is shown as a message and never performed; the decision and its
    // sentence are in `commandOffer`. Performing a safe command is not built, so nothing here runs one.
    val unknownCommandText = stringResource(R.string.tui_command_unknown)
    val offeredCommandText = stringResource(R.string.tui_command_offered)
    LaunchedEffect(serverDataSets) {
        serverDataSets.active
            .flatMapLatest { set -> set?.tui?.commands ?: emptyFlow() }
            .collect { command ->
                val offer = commandOffer(command, unknownCommandText, offeredCommandText)
                snackbarHostState.showSnackbar(offer.text, duration = SnackbarDuration.Long)
            }
    }

    // A TUI session selection (`tui.session.select`) opens that session here, but only while the app follows the
    // desktop. The gate is `followedSessionSelections`, which stops at once when following is turned off.
    // The stack is reset to the servers list first, like the notification path: `openTab` assumes every tab
    // sits directly on a HomeRoute, and pushing SessionRoute with no Home beneath broke that invariant.
    LaunchedEffect(serverDataSets) {
        serverDataSets.active
            .flatMapLatest { set ->
                val serverId = set?.serverId
                set?.tui?.let { tui ->
                    followedSessionSelections(tui).map { selection -> serverId to selection }
                } ?: emptyFlow()
            }
            .collect { (serverId, selection) ->
                navController.navigate(HomeRoute(serverId)) {
                    popUpTo(ServersRoute) { inclusive = false }
                    launchSingleTop = true
                }
                navController.navigate(SessionRoute(serverId, selection.sessionID)) { launchSingleTop = true }
            }
    }

    // The extracted graphs (navigation/Graphs.kt) close over this function's state through one holder,
    // so the graph reads as the plan's phases rather than as 36 inline destinations. The holder is built
    // here, where the snackbar host and the palette flag live, and the graphs never reach past it.
    val appScope = rememberCoroutineScope()
    val deps = NavGraphDeps(
        navController = navController,
        serverDataSets = serverDataSets,
        activeServerId = activeServerId,
        onOpenCommandPalette = { paletteOpen = true },
        sessionTabs = sessionTabs,
        onSessionTabsChange = { sessionTabs = it },
        sessionListState = sessionListState,
        sessionInfo = sessionInfo,
        untitledSession = untitledSession,
        showSnackbar = { message -> appScope.launch { snackbarHostState.showSnackbar(message) } },
    )

    // The host sits over the whole app rather than in a Scaffold, which would pad every screen that already
    // has its own top and bottom bars a second time. It is inset for the navigation bar only.
    Box(
        modifier = modifier
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!isPaletteShortcut(event.key, event.isCtrlPressed, event.type)) return@onPreviewKeyEvent false
                leader.cancel()
                paletteOpen = true
                true
            }
            .leaderKeys(leader, paletteOffered, onRunPaletteCommand),
    ) {
        // The bottom bar shows on a server's three top-level screens and hides on everything pushed above them, and
        // while the keyboard is up so the composer keeps its height. While it shows it owns the navigation-bar inset,
        // so the screen above it does not pad for the system bar a second time.
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentTab = backStackEntry?.destination?.topLevelTab()
        val barShown = currentTab != null && activeServerId != null && !WindowInsets.isImeVisible
        Column(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = HomeGraph,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (barShown) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
                // The transition language (navigation/Transitions.kt). Forward slide-and-fade is the default;
                // the top-level cross-fade and the session container transitions are per-destination overrides
                // declared on the destinations themselves.
                enterTransition = { DestinationTransitions.Forward.enter },
                exitTransition = { DestinationTransitions.Forward.exit },
                popEnterTransition = { DestinationTransitions.Forward.popEnter },
                popExitTransition = { DestinationTransitions.Forward.popExit },
            ) {
                homeGraph(deps)
                sessionGraph(deps)
                settingsGraph(deps)
                adminGraph(deps)
            }

            if (barShown) {
                TopLevelBar(
                    selected = currentTab,
                    onSelect = { tab -> navController.openTab(tab, activeServerId, currentTab) },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(OpenCodeThemeExtras.spacing.lg),
        )

        // The leader-key armed state, made visible: after Ctrl+X the app waits for a letter, and without
        // this pill there is no sign it heard the first key. It sits at the top so it never fights the
        // snackbar below, and the timeout above clears it.
        if (leaderArmed) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = OpenCodeThemeExtras.spacing.sm),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.leader_armed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(
                        horizontal = OpenCodeThemeExtras.spacing.lg,
                        vertical = OpenCodeThemeExtras.spacing.sm,
                    ),
                )
            }
        }

        if (paletteOpen) {
            CommandPaletteDialog(
                commands = paletteOffered,
                onRun = onRunPaletteCommand,
                onDismiss = { paletteOpen = false },
            )
        }
    }
}

/**
 * Goes to a launcher shortcut's root screen: the servers list, or the home with the new-session sheet open.
 *
 * The launcher shortcuts and the command palette both come here, so "New session" is the same from either.
 * Everything above the servers list is popped first, so a root screen never stacks a copy of one the user had
 * open. "New session" is the home's own sheet, which follows the active server, so it takes no server id.
 */
private fun NavHostController.openShortcutDestination(destination: ShortcutDestination) {
    val route: Any = when (destination) {
        ShortcutDestination.SERVERS -> ServersRoute
        ShortcutDestination.NEW_SESSION -> HomeRoute(openNewSession = true)
        ShortcutDestination.INBOX -> PendingRequestsRoute()
    }
    navigate(route) {
        popUpTo(ServersRoute) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * Opens a Settings row. The checkout-scoped screens take the [directory] of the project chosen on the Settings tab;
 * the server-wide ones ignore it.
 */
internal fun NavHostController.openSettingsDestination(serverId: String?, destination: ManageDestination, directory: String) {
    // Every manage-menu destination is launchSingleTop: re-selecting the open destination is a no-op
    // rather than a second copy, per the back-stack rule in OpenCodeApp's KDoc.
    fun go(route: Any) = navigate(route) { launchSingleTop = true }
    when (destination) {
        ManageDestination.SERVER_STATUS -> serverId?.let { go(ServerStatusRoute(it)) }

        ManageDestination.EDIT_SERVER -> serverId?.let { go(EditServerRoute(it)) }

        ManageDestination.PAIR_DEVICE -> go(PairDeviceRoute(serverId))

        ManageDestination.EVENT_INSPECTOR -> go(EventInspectorRoute(serverId))

        ManageDestination.SWITCH_SERVER -> popBackStack(ServersRoute, inclusive = false)

        ManageDestination.ACCOUNTS -> go(ConnectRoute(serverId, directory))

        ManageDestination.PROVIDERS -> go(ProvidersRoute(serverId, directory))

        ManageDestination.MCP -> go(McpRoute(serverId, directory))

        ManageDestination.PLUGINS -> go(PluginsRoute(serverId, directory))

        ManageDestination.WEB_SEARCH -> go(WebSearchRoute(serverId, directory))

        ManageDestination.CONFIGURATION -> go(ConfigRoute(serverId, directory))

        ManageDestination.AGENTS -> go(CatalogRoute(serverId, directory, AdminCatalog.AGENTS))

        ManageDestination.DEFINITIONS -> go(CatalogRoute(serverId, directory, AdminCatalog.COMMANDS))

        ManageDestination.PERMISSIONS -> go(PermissionsRoute(serverId, directory))

        // Maintenance is server-wide, so it takes no directory: it lists what the server has loaded and what would
        // be dropped, which is a property of the server.
        ManageDestination.MAINTENANCE -> go(MaintenanceRoute(serverId))

        ManageDestination.USAGE -> go(UsageRoute(serverId))

        ManageDestination.QUICK_ASK -> go(QuickAskRoute(serverId))

        ManageDestination.RPC_CONSOLE -> go(RpcConsoleRoute(serverId))

        ManageDestination.CHANGELOG -> go(ChangelogRoute)
    }
}

/** The global pending-requests inbox, with the answers wired to the request center. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PendingRequestsHost(
    onNavigateBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    requests: PendingRequestsViewModel = hiltViewModel(),
    // audit-viewmodels: own-instance
    // The inbox has no session open and needs none: a request names the session that asked, and the composer
    // only answers it (`replyPermission`, `submitForm`, `cancelForm`), so this instance is never `open`ed.
    composer: ComposerViewModel = hiltViewModel(),
) {
    val pending by requests.pending.collectAsStateWithLifecycle()
    val titles by requests.sessionTitles.collectAsStateWithLifecycle()
    val composerState by composer.state.collectAsStateWithLifecycle()
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
        // The composer records a refused answer in its own state, and nothing else on this screen reads it.
        error = composerState.error,
        onDismissError = composer::dismissError,
    )
}

/** The new-session sheet, which navigates to the session it created. */
@Composable
internal fun NewSessionHost(
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

    var modelSearch by rememberSaveable { mutableStateOf("") }
    NewSessionSheet(
        state = state,
        favorites = favorites,
        recents = recents,
        modelSearch = modelSearch,
        onModelSearchChange = { modelSearch = it },
        onTitleChange = viewModel::setTitle,
        onSelectProject = viewModel::selectLocation,
        onSelectDirectory = { directory -> viewModel.selectLocation(LocationChoice.Browsed(directory, null)) },
        onOpenBrowser = viewModel::openBrowser,
        onPathDraftChange = viewModel::setPathDraft,
        onPathCommit = viewModel::commitPath,
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
 * The back-stack entry of the session [route]'s review was opened from, or `null` when there is none.
 *
 * `getBackStackEntry` throws for a route that is not on the stack, and here that is an answer: a review
 * with no session (`sessionId` is null) or whose session is gone has no composer to give its comments and
 * attachments to.
 */
internal fun NavHostController.sessionEntryOf(route: ReviewRoute): NavBackStackEntry? =
    route.sessionId?.let { sessionEntryOf(route.serverId, it) }

/** The back-stack entry of session [sessionId], or `null` when it is not on the stack. */
internal fun NavHostController.sessionEntryOf(serverId: String?, sessionId: String): NavBackStackEntry? =
    runCatching { getBackStackEntry(SessionRoute(serverId, sessionId)) }.getOrNull()

/**
 * The flow a catalog screen reads before the app is following a server.
 *
 * **Loading rather than empty**, because an empty list renders as "this project has no agents" and the
 * true state is "there is no server yet" — two claims a user would act on differently.
 */
internal fun <T> loadingFlow(): StateFlow<SyncedState<T>> =
    MutableStateFlow(SyncedState(status = SyncStatus.Loading))
