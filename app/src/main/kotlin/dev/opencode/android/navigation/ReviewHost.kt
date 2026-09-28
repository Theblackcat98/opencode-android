package dev.opencode.android.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.data.composer.FileReadResult
import dev.opencode.android.core.data.composer.LineRange
import dev.opencode.android.feature.composer.R as ComposerR
import dev.opencode.android.feature.composer.ui.ComposerViewModel
import dev.opencode.android.feature.review.R as ReviewR
import dev.opencode.android.feature.review.FileBrowserScreen
import dev.opencode.android.feature.review.ReviewCommentDialog
import dev.opencode.android.feature.review.ReviewScreen
import dev.opencode.android.feature.review.ReviewViewModel

/**
 * The review destination, and the composition root for everything Phase 6 adds to a session.
 *
 * **The review is a screen of its own, not a tab of the timeline.** A review of a hundred files is a
 * different amount of content from a transcript and it has its own navigation — file, hunk, base
 * branch — so putting it in the same `LazyColumn` would mean one list deciding both the scroll
 * position and the row shape for two unrelated things.
 *
 * **The wiring lives here because a feature may not import another one.** The composer's client
 * commands are the review's entry point, the review's comments are the composer's next prompt, and
 * the timeline's changed files are the review's file list. All three cross a module boundary, so the
 * app module is the only place that can hold the three of them — and it holds no logic: every
 * operation is a call on a view model and every decision is a pure type in `core:data`.
 */
@Composable
fun ReviewHost(
    sessionId: String?,
    initialPath: String? = null,
    onNavigateBack: () -> Unit,
    onAttachFile: (String, String, String) -> Unit = { _, _, _ -> },
    onAttachLines: (String, String, String, LineRange) -> Unit = { _, _, _, _ -> },
    onShareFile: (FileReadResult) -> Unit = {},
    modifier: Modifier = Modifier,
    review: ReviewViewModel = hiltViewModel(),
    composer: ComposerViewModel = hiltViewModel(),
) {
    val state by review.state.collectAsStateWithLifecycle()
    val files by review.files.collectAsStateWithLifecycle()
    var pendingRange by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(sessionId) { review.open(sessionId) }
    LaunchedEffect(initialPath) { initialPath?.let(review::openFile) }

    // The review's comments travel to the composer as soon as they exist, because the composer is the
    // only thing that can turn them into a prompt and a second holder would be a second thing to
    // keep in step.
    LaunchedEffect(state.comments) { composer.setReviewComments(state.comments) }

    ReviewScreen(
        state = state,
        onSelectScope = review::selectScope,
        onOpenFile = review::openFile,
        onNextFile = review::nextFile,
        onPreviousFile = review::previousFile,
        onNextHunk = review::nextHunk,
        onPreviousHunk = review::previousHunk,
        onToggleReviewed = review::toggleReviewed,
        onToggleWrap = review::setWrap,
        onToggleSplit = { review.setSplit(it) },
        onSelectLines = review::beginComment,
        onOpenBasePicker = review::openBasePicker,
        onSelectBase = review::selectBase,
        onNavigateBack = onNavigateBack,
        modifier = modifier,
    )

    state.commentDraft?.let { draft ->
        ReviewCommentDialog(
            draft = draft,
            onTextChange = review::editComment,
            onSubmit = {
                review.submitComment()
                // The composer already holds the list through `setReviewComments`; nothing is taken
                // here, because a comment that is filed and then taken would vanish from both.
            },
            onDismiss = review::cancelComment,
        )
    }

    if (state.filesOpen) {
        FileBrowserScreen(
            directory = state.directory,
            path = files.path,
            entries = files.sorted,
            reading = files.reading,
            content = files.content,
            loading = files.loading,
            error = files.error,
            canEdit = state.editingUsable,
            onEnter = { entry -> review.listFiles(entry.path) },
            onUp = review::goUp,
            onRead = review::readFile,
            onAttach = { file ->
                onAttachFile(file.path, file.label, file.mime.orEmpty())
                review.toggleFiles()
            },
            onAttachLines = { file ->
                pendingRange = file.path to file.label
            },
            onShare = { file ->
                onShareFile(file)
                review.toggleFiles()
            },
            onNavigateBack = review::toggleFiles,
        )
    }

    pendingRange?.let { (path, name) ->
        AlertDialog(
            onDismissRequest = { pendingRange = null },
            title = { Text(stringResource(ReviewR.string.files_attach_lines)) },
            text = {
                Text(
                    stringResource(
                        ReviewR.string.files_attach_range,
                        LineRange(1).toSuffix(),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAttachLines(path, name, "text/plain", LineRange(1))
                    pendingRange = null
                    review.toggleFiles()
                }) { Text(stringResource(ComposerR.string.composer_send)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRange = null }) {
                    Text(stringResource(ReviewR.string.action_dismiss))
                }
            },
        )
    }
}
