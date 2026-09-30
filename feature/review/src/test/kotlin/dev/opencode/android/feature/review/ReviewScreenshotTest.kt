package dev.opencode.android.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.opencode.android.core.data.review.CommentSelection
import dev.opencode.android.core.data.review.FileTree
import dev.opencode.android.core.data.review.ParsedFile
import dev.opencode.android.core.data.review.ReviewComments
import dev.opencode.android.core.data.review.ReviewNavigator
import dev.opencode.android.core.data.review.ReviewPosition
import dev.opencode.android.core.data.review.ReviewScope
import dev.opencode.android.core.data.review.ReviewedFiles
import dev.opencode.android.core.data.server.SessionContextInspector
import dev.opencode.android.core.data.server.VcsState
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.AssistantContent
import dev.opencode.android.core.model.FileDiffStatus
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.VcsBranch
import dev.opencode.android.core.model.VcsFileStatus
import dev.opencode.android.core.model.VcsInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for the review surface (plan §5.3, "UI"; §5.4, dynamic type).
 *
 * The states that are captured are the ones a user reads and then acts on: the four scopes, a diff
 * with context and a change, a file tree, a review in progress against a real branch, a comment
 * dialog, a staged revert's banner, an empty review, a binary file, the file browser, and the context
 * inspector. Light and dark, and once at 1.5× font because a diff is exactly the content that a
 * fixed row height or a truncated label shows up in.
 *
 * Dynamic colour is off so the baselines are stable across the machines CI runs on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class ReviewScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------------ review states

    @Test
    fun uncommittedDiff() = capture("review-diff-uncommitted") {
        ReviewScreen(
            state = reviewState().copy(scope = ReviewScope.Uncommitted),
            onSelectScope = {},
            onOpenFile = {},
            onNextFile = {},
            onPreviousFile = {},
            onNextHunk = {},
            onPreviousHunk = {},
            onToggleReviewed = {},
            onToggleWrap = {},
            onToggleSplit = {},
            onSelectLines = { _, _, _ -> },
            onOpenBasePicker = {},
            onSelectBase = {},
            onNavigateBack = {},
        )
    }

    @Test
    fun diffInDarkTheme() = capture("review-diff-dark", dark = true) {
        ReviewScreen(
            state = reviewState(),
            onSelectScope = {},
            onOpenFile = {},
            onNextFile = {},
            onPreviousFile = {},
            onNextHunk = {},
            onPreviousHunk = {},
            onToggleReviewed = {},
            onToggleWrap = {},
            onToggleSplit = {},
            onSelectLines = { _, _, _ -> },
            onOpenBasePicker = {},
            onSelectBase = {},
            onNavigateBack = {},
        )
    }

    @Test
    fun diffAtLargeFont() = capture("review-diff-large-font", fontScale = 1.5f) {
        ReviewScreen(
            state = reviewState(),
            onSelectScope = {},
            onOpenFile = {},
            onNextFile = {},
            onPreviousFile = {},
            onNextHunk = {},
            onPreviousHunk = {},
            onToggleReviewed = {},
            onToggleWrap = {},
            onToggleSplit = {},
            onSelectLines = { _, _, _ -> },
            onOpenBasePicker = {},
            onSelectBase = {},
            onNavigateBack = {},
        )
    }

    @Test
    fun splitView() = capture("review-diff-split") {
        // The split toggle only exists where the layout has room, so the fixture sets it and the
        // screenshot proves the pairing reads: a removal and the addition that replaced it, side by
        // side on one row.
        ReviewScreen(
            state = reviewState().copy(split = true, files = reviewState().files),
            onSelectScope = {},
            onOpenFile = {},
            onNextFile = {},
            onPreviousFile = {},
            onNextHunk = {},
            onPreviousHunk = {},
            onToggleReviewed = {},
            onToggleWrap = {},
            onToggleSplit = {},
            onSelectLines = { _, _, _ -> },
            onOpenBasePicker = {},
            onSelectBase = {},
            onNavigateBack = {},
        )
    }

    @Test
    fun fileTree() = capture("review-file-tree") {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            ReviewFileTree(state = reviewState(), onOpenFile = {})
        }
    }

    @Test
    fun emptyReview() = capture("review-empty") {
        ReviewScreen(
            state = reviewState().copy(
                files = emptyList(),
                tree = FileTree.build(emptyList()),
                scope = ReviewScope.Committed,
            ),
            onSelectScope = {},
            onOpenFile = {},
            onNextFile = {},
            onPreviousFile = {},
            onNextHunk = {},
            onPreviousHunk = {},
            onToggleReviewed = {},
            onToggleWrap = {},
            onToggleSplit = {},
            onSelectLines = { _, _, _ -> },
            onOpenBasePicker = {},
            onSelectBase = {},
            onNavigateBack = {},
        )
    }

    @Test
    fun binaryFileReview() = capture("review-binary") {
        ReviewScreen(
            state = reviewState().copy(
                files = listOf(binaryPatch()),
                tree = FileTree.build(listOf("assets/logo.png")),
                position = ReviewPosition("assets/logo.png"),
            ),
            onSelectScope = {},
            onOpenFile = {},
            onNextFile = {},
            onPreviousFile = {},
            onNextHunk = {},
            onPreviousHunk = {},
            onToggleReviewed = {},
            onToggleWrap = {},
            onToggleSplit = {},
            onSelectLines = { _, _, _ -> },
            onOpenBasePicker = {},
            onSelectBase = {},
            onNavigateBack = {},
        )
    }

    // ------------------------------------------------------------------ comments and revert

    @Test
    fun commentDraft() = capture("review-comment-dialog") {
        CommentDraftContent(
            draft = CommentDraft("src/main/kotlin/A.kt", 12, 18, "this range can throw"),
            onTextChange = {},
        )
    }

    @Test
    fun commentDraftEmpty() = capture("review-comment-dialog-empty") {
        CommentDraftContent(draft = CommentDraft("src/main/kotlin/A.kt", 12, 18), onTextChange = {})
    }

    @Test
    fun stagedRevertBanner() = capture("review-staged-revert") {
        StagedRevertPreviewContent()
    }

    @Test
    fun fileTreeSheet() = capture("review-file-tree-sheet") {
        FileTreeContent(state = reviewState(), onOpenFile = {})
    }

    @Test
    fun fileViewerText() = capture("review-file-viewer") {
        FileBrowserContent(
            directory = "/home/dev/project",
            path = "/home/dev/project/src/main/kotlin",
            entries = listOf(
                dev.opencode.android.core.model.FileSystemEntry(
                    path = "/home/dev/project/src/main/kotlin/A.kt",
                    type = dev.opencode.android.core.model.FileSystemEntry.EntryType.FILE,
                ),
            ),
            reading = "/home/dev/project/src/main/kotlin/A.kt",
            content = textFile(),
            loading = false,
            error = null,
            canEdit = true,
            selection = LineSelection(anchor = 2, end = 4),
            onEnter = {},
            onUp = {},
            onRead = {},
            onAttach = {},
            onAttachLines = { _, _ -> },
            onShare = {},
            onDownload = {},
        )
    }

    /**
     * A text file the reader held only the start of. The viewer says how much it shows and how much
     * there is, and offers no edit, share or download: those would treat the start as the file.
     */
    @Test
    fun fileViewerTruncated() = capture("review-file-viewer-truncated") {
        FileBrowserContent(
            directory = "/home/dev/project",
            path = "/home/dev/project",
            entries = listOf(
                dev.opencode.android.core.model.FileSystemEntry(
                    path = "/home/dev/project/huge.txt",
                    type = dev.opencode.android.core.model.FileSystemEntry.EntryType.FILE,
                ),
            ),
            reading = null,
            content = truncatedTextFile(),
            loading = false,
            error = null,
            canEdit = true,
            onEnter = {},
            onUp = {},
            onRead = {},
            onAttach = {},
            onAttachLines = { _, _ -> },
            onShare = {},
            onDownload = {},
        )
    }

    /** A picture over the reader's cap: a sentence with its size, not a decode and not a crash. */
    @Test
    fun fileViewerPictureTooLarge() = capture("review-file-viewer-image-too-large") {
        FileBrowserContent(
            directory = "/home/dev/project",
            path = "/home/dev/project",
            entries = listOf(
                dev.opencode.android.core.model.FileSystemEntry(
                    path = "/home/dev/project/photo.png",
                    type = dev.opencode.android.core.model.FileSystemEntry.EntryType.FILE,
                ),
            ),
            reading = null,
            content = dev.opencode.android.core.data.composer.FileReadResult(
                path = "/home/dev/project/photo.png",
                bytes = ByteArray(0),
                kind = dev.opencode.android.core.data.composer.FileContentKind.IMAGE,
                mime = "image/png",
                truncated = true,
                totalBytes = 48_000_000L,
            ),
            loading = false,
            error = null,
            canEdit = true,
            onEnter = {},
            onUp = {},
            onRead = {},
            onAttach = {},
            onAttachLines = { _, _ -> },
            onShare = {},
            onDownload = {},
        )
    }

    @Test
    fun fileBrowserSearching() = capture("review-file-search") {
        FileBrowserContent(
            directory = "/home/dev/project",
            path = null,
            entries = emptyList(),
            reading = null,
            content = null,
            loading = false,
            error = null,
            searchQuery = "Unified",
            searchResults = listOf(
                dev.opencode.android.core.model.FileSystemEntry(
                    path = "src/main/kotlin/UnifiedDiff.kt",
                    type = dev.opencode.android.core.model.FileSystemEntry.EntryType.FILE,
                ),
            ),
            onEnter = {},
            onUp = {},
            onRead = {},
        )
    }

    @Test
    fun historyPanel() = capture("review-history") {
        HistoryPanel(
            state = historyState(),
            onPreviousPrompt = {},
            onNextPrompt = {},
            onSearchChange = {},
            onOpenContext = {},
            onCloseContext = {},
            onSanitizeChange = {},
            onExportJson = {},
            onExportMarkdown = {},
            onImport = {},
            onDismissTransfer = {},
        )
    }

    @Test
    fun historyPanelInDarkTheme() = capture("review-history-dark", dark = true) {
        HistoryPanel(
            state = historyState().copy(contextOpen = true, context = contextEntries()),
            onPreviousPrompt = {},
            onNextPrompt = {},
            onSearchChange = {},
            onOpenContext = {},
            onCloseContext = {},
            onSanitizeChange = {},
            onExportJson = {},
            onExportMarkdown = {},
            onImport = {},
            onDismissTransfer = {},
        )
    }

    @Test
    fun historyPanelAtLargeFont() = capture("review-history-large-font", fontScale = 1.5f) {
        HistoryPanel(
            state = historyState().copy(contextOpen = true, context = contextEntries()),
            onPreviousPrompt = {},
            onNextPrompt = {},
            onSearchChange = {},
            onOpenContext = {},
            onCloseContext = {},
            onSanitizeChange = {},
            onExportJson = {},
            onExportMarkdown = {},
            onImport = {},
            onDismissTransfer = {},
        )
    }

    @Test
    fun experimentalSwitches() = capture("review-experimental") {
        ExperimentalSettingsContent(
            fileWrites = true,
            sessionTransfer = false,
            onFileWritesChange = {},
            onSessionTransferChange = {},
        )
    }

    @Test
    fun experimentalSwitchesInDarkTheme() = capture("review-experimental-dark", dark = true) {
        ExperimentalSettingsContent(
            fileWrites = false,
            sessionTransfer = true,
            onFileWritesChange = {},
            onSessionTransferChange = {},
        )
    }

    @Test
    fun baseBranchPicker() = capture("review-base-picker") {
        BaseBranchContent(
            branches = listOf("main", "develop", "phase-6"),
            selected = "main",
            onSelect = {},
        )
    }

    // ------------------------------------------------------------------ files and context

    @Test
    fun fileBrowser() = capture("review-file-browser") {
        FileBrowserContent(
            directory = "/home/dev/project",
            path = "/home/dev/project/src/main/kotlin",
            entries = listOf(
                dev.opencode.android.core.model.FileSystemEntry(
                    path = "/home/dev/project/src/main/kotlin/ui",
                    type = dev.opencode.android.core.model.FileSystemEntry.EntryType.DIRECTORY,
                ),
                dev.opencode.android.core.model.FileSystemEntry(
                    path = "/home/dev/project/src/main/kotlin/A.kt",
                    type = dev.opencode.android.core.model.FileSystemEntry.EntryType.FILE,
                ),
                dev.opencode.android.core.model.FileSystemEntry(
                    path = "/home/dev/project/src/main/kotlin/B.kt",
                    type = dev.opencode.android.core.model.FileSystemEntry.EntryType.FILE,
                ),
            ),
            reading = null,
            content = null,
            loading = false,
            error = null,
            canEdit = false,
            onEnter = {},
            onUp = {},
            onRead = {},
            onAttach = {},
            onAttachLines = { _, _ -> },
            onShare = {},
            onDownload = {},
        )
    }

    @Test
    fun fileBrowserBinary() = capture("review-file-browser-binary") {
        FileBrowserContent(
            directory = "/home/dev/project",
            path = "/home/dev/project/assets",
            entries = listOf(
                dev.opencode.android.core.model.FileSystemEntry(
                    path = "/home/dev/project/assets/logo.png",
                    type = dev.opencode.android.core.model.FileSystemEntry.EntryType.FILE,
                ),
            ),
            reading = null,
            content = dev.opencode.android.core.data.composer.FileReadResult(
                path = "/home/dev/project/assets/logo.png",
                bytes = byteArrayOf(0, 1, 2, 3),
                kind = dev.opencode.android.core.data.composer.FileContentKind.BINARY,
                mime = "image/png",
            ),
            loading = false,
            error = null,
            canEdit = true,
            onEnter = {},
            onUp = {},
            onRead = {},
            onAttach = {},
            onAttachLines = { _, _ -> },
            onShare = {},
            onDownload = {},
        )
    }

    @Test
    fun commentList() = capture("review-comment-list") {
        CommentListContent(
            comments = listOf(
                CommentSelection.onDiff(
                    "src/main/kotlin/UnifiedDiff.kt",
                    12,
                    18,
                    "a line that is only a context line still gets a number",
                    emptyList(),
                ),
                CommentSelection.onDiff(
                    "docs/ANDROID_APP_PLAN.md",
                    930,
                    930,
                    "the split threshold is named in the plan, not in a layout file",
                    emptyList(),
                ),
            ),
            onRemove = {},
        )
    }

    @Test
    fun contextInspector() = capture("review-context") {
        // The real section, not a copy of its rows: a baseline drawn from a test-local row is a
        // baseline of the row, and it keeps passing after the row it was copied from has changed.
        ContextSection(state = historyState().copy(context = contextEntries()), onClose = {})
    }

    // ------------------------------------------------------------------ fixtures

    /** The review state a user looks at: a branch, a base, three files and one change. */
    private fun reviewState(): ReviewUiState {
        val files = listOf(
            parsed(
                "src/main/kotlin/ReviewViewModel.kt",
                "@@ -10,6 +10,8 @@ class ReviewViewModel {\n-    fun load() {\n+    fun load(context: String) {\n+        // the scope decides the route\n         val files = api.load()\n",
                2,
                1,
            ),
            parsed(
                "src/main/kotlin/UnifiedDiff.kt",
                "@@ -1,2 +1,3 @@\n+/** A parsed unified patch. */\n object UnifiedDiff {\n",
                1,
                0,
            ),
            parsed(
                "docs/ANDROID_APP_PLAN.md",
                "@@ -926,3 +926,5 @@\n - **Diff engine and viewer.**\n+- Split view on tablets.\n   Parses unified patches.\n",
                1,
                0,
            ),
        )
        return ReviewUiState(
            sessionID = "ses_1",
            directory = "/home/dev/project",
            scope = ReviewScope.All,
            scopes = ReviewScope.ALL,
            files = files,
            tree = FileTree.build(files.map { it.file }),
            position = ReviewNavigator.start(files),
            reviewed = ReviewedFiles(setOf(files[1].key)),
            wrap = false,
            split = false,
            vcs = VcsState(
                directory = "/home/dev/project",
                info = VcsInfo(provider = "git", branch = VcsBranch(current = "phase-6", default = "main")),
                files = files.map {
                    VcsFileStatus(
                        file = it.file,
                        additions = it.additions,
                        deletions = it.deletions,
                        status = it.status,
                    )
                },
                base = dev.opencode.android.core.model.VcsBase(
                    name = "main",
                    ref = "refs/heads/main",
                    source = dev.opencode.android.core.model.VcsBaseSource.Default,
                ),
            ),
            branches = listOf("main", "develop", "phase-6"),
        )
    }

    private fun parsed(file: String, patch: String, additions: Int, deletions: Int): ParsedFile =
        dev.opencode.android.core.data.review.UnifiedDiff.parse(
            patch = patch,
            file = file,
            additions = additions,
            deletions = deletions,
            status = FileDiffStatus.Modified,
        )

    private fun binaryPatch(): ParsedFile = ParsedFile(
        file = "assets/logo.png",
        hunks = emptyList(),
        additions = 0,
        deletions = 0,
        status = FileDiffStatus.Added,
        binary = true,
    )

    /**
     * The staged-revert banner and the comment chips.
     *
     * Both live in the composer feature — they are part of what the composer shows — and this is the
     * arrangement Phase 3 used for the session screen: the module cannot import another feature's
     * main code, so the screenshot is taken from here with a test-only dependency, which is the only
     * way a *state* of the whole screen can be reviewed.
     */
    @Composable
    private fun StagedRevertPreviewContent() {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            dev.opencode.android.feature.composer.ui.StagedRevertBanner(
                state = dev.opencode.android.feature.composer.ui.ComposerUiState(
                    sessionID = "ses_1",
                    stagedRevert = dev.opencode.android.core.model.SessionRevert(
                        messageID = "msg_7",
                        files = reviewState().files.map {
                            dev.opencode.android.core.model.FileDiff(it.file, "", it.additions, it.deletions, it.status)
                        },
                    ),
                    restoredFiles = reviewState().files.map {
                        dev.opencode.android.core.data.review.RestoredFile(it.file, it.additions, it.deletions)
                    },
                ),
                onRedo = {},
            )
            dev.opencode.android.feature.composer.ui.ReviewCommentChips(
                comments = listOf(
                    CommentSelection.onDiff(
                        "src/main/kotlin/ReviewViewModel.kt",
                        10,
                        12,
                        "this needs a bound",
                        emptyList(),
                    ),
                ),
                onRemove = {},
            )
        }
    }

    /** The context the inspector summarizes, which is a real prompt and a real answer. */
    private fun contextEntries(): List<SessionContextInspector.Entry> = SessionContextInspector.inspect(
        listOf(
            SessionMessage.User(
                id = "msg_1",
                time = SessionMessage.CreatedTime(1),
                text = "add a wrap toggle to the diff viewer",
            ),
            SessionMessage.Assistant(
                id = "msg_2",
                time = SessionMessage.Assistant.Time(created = 2, completed = 3),
                agent = "build",
                model = ModelRef(id = "text", providerID = "fake"),
                content = listOf(
                    AssistantContent.Text("Added a wrap toggle and a split view for wide layouts."),
                ),
                snapshot = SessionMessage.Assistant.Snapshot(start = "a", end = "b", files = listOf("src/a.kt")),
            ),
        ),
    )

    /** The history panel's state: three prompts, a search, an open context and a refusal. */
    private fun historyState(): HistoryUiState = HistoryUiState(
        sessionID = "ses_1",
        open = true,
        prompts = listOf(
            HistoryPrompt("msg_1", "add a wrap toggle to the diff viewer"),
            HistoryPrompt("msg_3", "the split view has to pair a removal with its addition"),
            HistoryPrompt("msg_5", "now let a comment on a line reach the next prompt"),
        ),
        cursor = 1,
        search = "split",
        hits = listOf(
            HistoryHit("msg_3", "User", "the split view has to pair a removal with its addition"),
        ),
        context = contextEntries(),
        transferUsable = true,
        exportSanitize = true,
        transfer = TransferResult.Refused("not-found"),
    )

    /** A text file the viewer shows, with the bytes and the classification agreeing. */
    private fun textFile(): dev.opencode.android.core.data.composer.FileReadResult {
        val body = """
            package dev.opencode.android.feature.review

            /** The review screen: the scope picker and the diff. */
            class ReviewScreen(val state: ReviewUiState) {
                fun openFile(path: String) = state.copy(position = ReviewPosition(path))
            }
        """.trimIndent()
        return dev.opencode.android.core.data.composer.FileReadResult(
            path = "/home/dev/project/src/main/kotlin/ReviewScreen.kt",
            bytes = body.toByteArray(),
            kind = dev.opencode.android.core.data.composer.FileContentKind.TEXT,
            mime = "text/plain",
            text = body,
        )
    }

    /** The start of a file far larger than the phone will open: the bytes are the prefix, the total is not. */
    private fun truncatedTextFile(): dev.opencode.android.core.data.composer.FileReadResult {
        val body = (1..12).joinToString("\n") { "line $it of a log that goes on for a good while longer" } + "\n"
        return dev.opencode.android.core.data.composer.FileReadResult(
            path = "/home/dev/project/huge.txt",
            bytes = body.toByteArray(),
            kind = dev.opencode.android.core.data.composer.FileContentKind.TEXT,
            mime = "application/octet-stream",
            text = body,
            truncated = true,
            totalBytes = 600_000_000L,
        )
    }

    /** Renders [content] in the app theme and writes the image Roborazzi compares against. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        // The clock is manual because a text field's caret never stops animating, and an animated
        // caret keeps Compose from ever reporting idle — a screenshot test then fails on a timeout
        // rather than on a pixel. `captureRoboImage` still lays the tree out, so what is written is
        // the first frame, which is what a baseline wants anyway.
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
        /** Where the convention plugin puts the baselines; the same path the other modules use. */
        const val ROBO_PATH = "src/test/screenshots"
    }
}
