package dev.opencode.android.feature.execution

import androidx.compose.foundation.layout.fillMaxSize
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
 * Screen-flow screenshots for `docs/app_screen_flow.html`.
 *
 * Each test captures one node of the flow canvas with the `docs-screen-<node-id>` naming the
 * `update-screen-flow-shots.mjs` tool expects. Fake data mirrors the existing
 * [ExecutionScreenshotTest] fixtures; see that file's KDoc for why the terminal's chrome — not
 * the WebView — is what gets photographed.
 *
 * Dynamic colour is off so the baselines are stable across the machines CI runs on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-xhdpi")
class ScreenFlowExecutionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun docsScreenShells() = capture("docs-screen-shells") {
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
    fun docsScreenTerminal() = capture("docs-screen-terminal") {
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
            onRequestTicket = {},
            onReconnect = {},
            onExtraKeys = {},
        )
    }

    @Test
    fun docsScreenSubagents() = capture("docs-screen-subagents") {
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

    @Test
    fun docsScreenWorktrees() = capture("docs-screen-worktrees") {
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
            onDismissError = {},
        )
    }

    @Test
    fun docsScreenSessionTerminals() = capture("docs-screen-session_terminals") {
        SessionTerminalsPane(
            state = SessionTerminalsUiState(sessionID = "ses_1"),
            onCreate = {},
            onRead = {},
            onInspect = {},
            onResize = { _, _, _ -> },
            onRemove = {},
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1f),
            ) {
                OpenCodeTheme(darkTheme = false) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun shell(id: String, command: String, running: Boolean) = ShellInfo(
        id = id,
        status = if (running) ShellStatus.Running else ShellStatus.Exited,
        command = command,
        cwd = "/work/app",
        shell = "/bin/bash",
        file = "/tmp/$id",
        exit = null,
        metadata = emptyMap(),
        time = ShellInfo.Time(started = 1),
    )

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

    private fun pty(id: String = "pty_1", title: String = "shell", command: String = "/bin/bash") = PtyInfo(
        id = id,
        title = title,
        command = command,
        args = emptyList(),
        cwd = "/work/app",
        status = PtyStatus.Running,
        pid = 4242,
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

    private fun sessionRow(id: String, title: String, running: Boolean, parent: String? = "ses_1") = SessionRow(
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

    private fun sessionTree() = SessionTree.build(
        listOf(
            sessionRow("ses_1", "migrate the schema", running = false, parent = null),
            sessionRow("ses_2", "write the migration", running = false),
            sessionRow("ses_3", "update the read model", running = false),
            sessionRow("ses_4", "add the index", running = true),
        ),
    ).flatMap(SessionNode::flatten)
}
