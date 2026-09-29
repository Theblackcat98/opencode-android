package dev.opencode.android.feature.execution

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.opencode.android.core.data.execution.PersistentPtyAvailability
import dev.opencode.android.core.data.execution.SessionNode
import dev.opencode.android.core.data.execution.SessionTree
import dev.opencode.android.core.data.execution.ShellOutputState
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.ShellInfo
import dev.opencode.android.core.model.ShellStatus
import dev.opencode.android.core.model.TokenUsage
import dev.opencode.android.core.model.WorktreeDirectory
import dev.opencode.android.core.model.event.PtyInfo
import dev.opencode.android.core.model.event.PtyStatus
import dev.opencode.android.core.network.PtyStreamState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for the execution surface (plan §5.3, "UI"; §5.4, dynamic type).
 *
 * **The terminal's chrome is captured, not the WebView.** A Roborazzi screenshot of a `WebView` is a
 * rectangle — P6 learned that about a sheet, and the same is true of xterm.js, which draws on a canvas
 * the compositor owns. So [TerminalChrome] is a separate composable holding everything around the live
 * surface, and that is what the baselines show; the page itself is asserted rather than photographed,
 * which is the only honest option for something that cannot render here at all.
 *
 * The states captured are the ones a user reads and then acts on: a panel with nothing running, a
 * running command with its output, a worktree refused because of uncommitted work, a persistent
 * terminal host that is stopped, and the subagent strip. Light and dark, and once at 1.5× font,
 * because a monospaced command line is exactly what a fixed row height clips.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class ExecutionScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------------ shells

    @Test
    fun shellsEmpty() = capture("shells-empty") {
        ShellsScreen(
            state = ShellsUiState(directory = "/work/app"),
            onDraftChange = {},
            onRun = {},
            onOpen = {},
            onClose = {},
            onRequestKill = {},
            onConfirmKill = {},
            onCancelKill = {},
            onDismissError = {},
        )
    }

    @Test
    fun shellsRunningWithOutput() = capture("shells-running") {
        ShellsScreen(
            state = shellsState(),
            onDraftChange = {},
            onRun = {},
            onOpen = {},
            onClose = {},
            onRequestKill = {},
            onConfirmKill = {},
            onCancelKill = {},
            onDismissError = {},
        )
    }

    @Test
    fun shellsRunningLargeFont() = capture("shells-running-large-font", fontScale = 1.5f) {
        ShellsScreen(
            state = shellsState(),
            onDraftChange = {},
            onRun = {},
            onOpen = {},
            onClose = {},
            onRequestKill = {},
            onConfirmKill = {},
            onCancelKill = {},
            onDismissError = {},
        )
    }

    // ------------------------------------------------------------------ worktrees

    @Test
    fun worktreesWithARefusal() = capture("worktrees-refused") {
        WorktreesScreen(
            state = worktreeState().copy(
                removeTarget = "/work/app/.opencode/worktree/refactor",
                removeReason = "the worktree has uncommitted changes",
                forceArmed = true,
            ),
            onFromChange = {},
            onBranchChange = {},
            onNameChange = {},
            onCreate = {},
            onRefresh = {},
            onRequestRemove = {},
            onRemove = {},
            onForceRemove = {},
            onCancelRemove = {},
            onMoveSession = {},
        )
    }

    @Test
    fun worktreesList() = capture("worktrees-list") {
        WorktreesScreen(
            state = worktreeState(),
            onFromChange = {},
            onBranchChange = {},
            onNameChange = {},
            onCreate = {},
            onRefresh = {},
            onRequestRemove = {},
            onRemove = {},
            onForceRemove = {},
            onCancelRemove = {},
            onMoveSession = {},
        )
    }

    // ------------------------------------------------------------------ the terminal's chrome

    @Test
    fun terminalChrome() = capture("terminal-chrome") {
        TerminalChrome(
            state = TerminalUiState(
                directory = "/work/app",
                open = pty(),
                stream = PtyStreamState.Live(4096),
                startCommand = "npm run dev",
                terminals = listOf(pty(), pty(id = "pty_2", title = "htop", command = "htop")),
            ),
            onNewTerminal = {},
            onRunProjectStart = {},
            onReconnect = {},
            onExtraKeys = {},
        )
    }

    @Test
    fun terminalChromeDark() = capture("terminal-chrome-dark", dark = true) {
        TerminalChrome(
            state = TerminalUiState(
                directory = "/work/app",
                open = pty(),
                stream = PtyStreamState.Reconnecting(
                    reason = "closed (1006)",
                    willRetry = true,
                    retryInMillis = 500,
                ),
                terminals = listOf(pty()),
            ),
            onNewTerminal = {},
            onRunProjectStart = {},
            onReconnect = {},
            onExtraKeys = {},
        )
    }

    @Test
    fun terminalChromeLargeFont() = capture("terminal-chrome-large-font", fontScale = 1.5f) {
        TerminalChrome(
            state = TerminalUiState(
                directory = "/work/app",
                open = pty(),
                stream = PtyStreamState.Live(4096),
                terminals = listOf(pty()),
            ),
            onNewTerminal = {},
            onRunProjectStart = {},
            onReconnect = {},
            onExtraKeys = {},
        )
    }

    // ------------------------------------------------------------------ persistent terminals

    @Test
    fun sessionTerminalsHostDown() = capture("session-terminals-host-down") {
        SessionTerminalsPane(
            state = SessionTerminalsUiState(
                sessionID = "ses_1",
                allowedBySetting = true,
                availability = PersistentPtyAvailability.HostDown,
                terminals = listOf(sessionTerminal()),
            ),
            onCreate = {},
            onRead = {},
            onRemove = {},
        )
    }

    @Test
    fun sessionTerminalsSwitchedOff() = capture("session-terminals-off") {
        SessionTerminalsPane(
            state = SessionTerminalsUiState(sessionID = "ses_1"),
            onCreate = {},
            onRead = {},
            onRemove = {},
        )
    }

    // ------------------------------------------------------------------ subagents

    @Test
    fun subagentStrip() = capture("subagents-strip") {
        SubagentStrip(
            state = SubagentStripState(
                sessionID = "ses_1",
                children = listOf(
                    chip("ses_2", "migrate the auth table", isRetrying = true),
                    chip("ses_3", "write the regression test"),
                ),
                error = "the child could not be interrupted",
            ),
            onOpen = {},
            onInterrupt = {},
        )
    }

    @Test
    fun subagentTree() = capture("subagents-tree") {
        SubagentsScreen(
            state = SubagentsUiState(
                sessionID = "ses_2",
                selected = "ses_2",
                nodes = sessionTree(),
                parentID = "ses_1",
                parentTitle = "migrate the schema",
                running = listOf(sessionRow("ses_4", "add the index", running = true)),
            ),
            onSelect = {},
            onParent = {},
            onPrevious = {},
            onNext = {},
        )
    }

    // ------------------------------------------------------------------ project settings

    @Test
    fun projectSettings() = capture("project-settings") {
        ProjectSettingsSheet(
            state = ProjectSettingsUiState(
                project = project(),
                name = "app",
                color = "#3b82f6",
                emoji = "🧪",
                startCommand = "npm run dev",
                canonical = "/work/app",
            ),
            onNameChange = {},
            onColorChange = {},
            onEmojiChange = {},
            onIconUrlChange = {},
            onStartCommandChange = {},
            onCanonicalChange = {},
            onSave = {},
            onDismiss = {},
        )
    }

    @Test
    fun projectSettingsSavedLargeFont() = capture("project-settings-saved-large-font", fontScale = 1.5f) {
        ProjectSettingsSheet(
            state = ProjectSettingsUiState(
                project = project(),
                name = "app",
                color = "#3b82f6",
                emoji = "🧪",
                startCommand = "npm run dev",
                canonical = "/work/app",
                saved = true,
            ),
            onNameChange = {},
            onColorChange = {},
            onEmojiChange = {},
            onIconUrlChange = {},
            onStartCommandChange = {},
            onCanonicalChange = {},
            onSave = {},
            onDismiss = {},
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun shellsState() = ShellsUiState(
        directory = "/work/app",
        draft = "npm test -- --watch",
        running = true,
        openID = "sh_1",
        rows = listOf(
            ShellRow(
                info = shell("sh_1", "npm test -- --watch", running = true),
                output = ShellOutputState(
                    text = "PASS src/auth\nPASS src/session\nTests: 2 passed\n",
                    cursor = 44,
                    size = 44,
                    exited = false,
                    truncated = false,
                ),
            ),
            ShellRow(info = shell("sh_2", "cargo build --release", running = true)),
        ),
    )

    private fun worktreeState() = WorktreesUiState(
        projectID = "prj_1",
        projectName = "app",
        draftName = "refactor",
        directories = listOf(
            WorktreeDirectory(directory = "/work/app/.opencode/worktree/refactor", strategy = "plugin"),
            WorktreeDirectory(directory = "/work/app/.opencode/worktree/spike"),
        ),
    )

    private fun sessionTerminal() = TerminalRow(
        id = "pty_1",
        title = "shell",
        cwd = "/work/app",
        isRunning = true,
        exitCode = null,
        foregroundProcess = "vim",
        cols = 120,
        rows = 40,
    )

    private fun chip(id: String, title: String, isRetrying: Boolean = false) = SubagentChip(
        sessionID = id,
        title = title,
        directory = "/work/app",
        isRunning = true,
        isRetrying = isRetrying,
    )

    private fun capture(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                OpenCodeTheme(darkTheme = dark) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("$ROBO_PATH/$name.png")
    }

    private companion object {
        const val ROBO_PATH = "src/test/screenshots"

        fun shell(id: String, command: String, running: Boolean, exit: Int? = null) = ShellInfo(
            id = id,
            status = if (running) ShellStatus.Running else ShellStatus.Exited,
            command = command,
            cwd = "/work/app",
            shell = "/bin/bash",
            file = "/tmp/$id",
            exit = exit,
            metadata = emptyMap(),
            time = ShellInfo.Time(started = 1),
        )

        fun pty(id: String = "pty_1", title: String = "shell", command: String = "/bin/bash") = PtyInfo(
            id = id,
            title = title,
            command = command,
            args = emptyList(),
            cwd = "/work/app",
            status = PtyStatus.Running,
            pid = 4242,
        )

        fun sessionRow(id: String, title: String, running: Boolean, parent: String? = "ses_1") = SessionRow(
            session = SessionInfo(
                id = id,
                parentID = parent,
                projectID = "prj_1",
                cost = 0.0,
                tokens = TokenUsage.Zero,
                title = title,
                time = SessionInfo.Time(created = 1, updated = 1),
                location = LocationPublicRef("/work/app"),
            ),
            activity = if (running) SessionActivity.Running else SessionActivity.Idle,
            status = null,
            childCount = 0,
        )

        /**
         * The tree, built the way the app builds it.
         *
         * **One node per session, from the flattened roots.** `SessionTree.build` returns roots, and
         * `flatten` on each gives depth-first order; flattening the roots again would emit `ses_1` twice
         * and the list's stable key would collide — which is a real constraint of the screen, and the
         * reason the fixture goes through the same path the view model does.
         */
        fun sessionTree() = SessionTree.build(
            listOf(
                sessionRow("ses_1", "migrate the schema", running = false, parent = null),
                sessionRow("ses_2", "write the migration", running = false),
                sessionRow("ses_3", "update the read model", running = false),
                sessionRow("ses_4", "add the index", running = true),
            ),
        ).flatMap(SessionNode::flatten)

        fun project() = dev.opencode.android.core.model.Project(
            id = "prj_1",
            canonical = "/work/app",
            name = "app",
            icon = dev.opencode.android.core.model.Project.Icon(color = "#3b82f6", override = "🧪"),
            commands = dev.opencode.android.core.model.Project.Commands(start = "npm run dev"),
            time = dev.opencode.android.core.model.Project.Time(1, 1, 1),
            sandboxes = emptyList(),
        )
    }
}
