package dev.opencode.android.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.data.composer.FileReadResult
import dev.opencode.android.core.data.composer.LineRange
import dev.opencode.android.feature.composer.ui.ComposerViewModel
import dev.opencode.android.feature.review.FileBrowserScreen
import dev.opencode.android.feature.review.ReviewCommentDialog
import dev.opencode.android.feature.review.ReviewScreen
import dev.opencode.android.feature.review.ReviewViewModel
import dev.opencode.android.feature.review.WriteOutcome
import java.io.File
import dev.opencode.android.feature.composer.R as ComposerR
import dev.opencode.android.feature.review.R as ReviewR

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
 *
 * **The split/unified choice is measured here, from the real width.** Plan §6 asks for a unified
 * view on a phone and a split view on a tablet and in landscape. The view model keeps the answer as
 * `Boolean?` with a third state for "this screen cannot tell", because a screen that guesses a
 * layout from a default is wrong on exactly the devices the rule is about. `BoxWithConstraints` is
 * the one measurement that is the window rather than a guess about it, and the threshold is the one
 * Material's own width classes use for the same distinction.
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
    val context = LocalContext.current

    LaunchedEffect(sessionId) { review.open(sessionId) }
    LaunchedEffect(initialPath) { initialPath?.let(review::openFile) }

    // The review's comments travel to the composer as soon as they exist, because the composer is the
    // only thing that can turn them into a prompt and a second holder would be a second thing to
    // keep in step.
    LaunchedEffect(state.comments) { composer.setReviewComments(state.comments) }

    // Measured once per width, not per composition: the review loads a patch, and re-deciding the
    // layout on every recomposition of that patch would be work nobody asked for.
    BoxWithConstraints(modifier = modifier) {
        val wide = maxWidth >= SPLIT_MIN_WIDTH
        LaunchedEffect(wide) { review.setSplit(wide) }

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
            onOpenFiles = review::toggleFiles,
            onOpenTree = review::toggleTree,
            onOpenComments = review::toggleComments,
            onRemoveComment = review::removeComment,
        )
    }

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
            writing = state.writing,
            writeNotice = state.writeNotice?.let { outcome ->
                when (outcome) {
                    WriteOutcome.SwitchedOff -> stringResource(ReviewR.string.files_edit_off)
                    is WriteOutcome.Failed -> stringResource(ReviewR.string.files_edit_failed, outcome.message)
                }
            },
            searchQuery = state.searchQuery,
            searchResults = state.searchResults,
            onSearchChange = review::searchFiles,
            onEnter = { entry -> review.listFiles(entry.path) },
            onUp = review::goUp,
            onRead = review::readFile,
            onAttach = { file ->
                onAttachFile(file.path, file.label, file.mime.orEmpty())
                review.toggleFiles()
            },
            onAttachLines = { file, range ->
                onAttachLines(file.path, file.label, file.mime.orEmpty(), range)
                review.toggleFiles()
            },
            // A binary cannot be previewed, so the two offers are different: one hands the bytes to
            // another app, the other puts them somewhere the user chose. Both need a real file, and
            // the only URI this app may hand out is one its own `FileProvider` owns — the bytes
            // arrive in memory and never touch storage, so they are written to the cache here.
            onShare = { file ->
                shareFile(context, file, download = false)
                onShareFile(file)
            },
            onDownload = { file -> shareFile(context, file, download = true) },
            onWrite = review::writeFile,
            onNavigateBack = review::toggleFiles,
        )
    }
}

/**
 * The width at which a diff is shown side by side.
 *
 * 840 dp is Material 3's own expanded width class, which is the boundary a tablet and a landscape
 * phone both cross and a portrait phone does not. It is one number in one place so the rule is
 * testable rather than a threshold typed into a layout.
 */
internal val SPLIT_MIN_WIDTH = 840.dp

/**
 * Hands a file the server holds to another app, or to the user's downloads.
 *
 * **The bytes go to the cache, and the cache is the only place they go.** `fs.read` answered with a
 * body, so there is no file on this device to share; writing one is a side effect of *looking* at a
 * file, and a viewer that leaves a copy of every file it opened behind is a leak. The file is named
 * after the server's own path, in the `shares` subdirectory the `FileProvider` already declares, so
 * the grant is scoped to it and expires with the cache.
 */
private fun shareFile(context: Context, file: FileReadResult, download: Boolean) {
    val target = File(context.cacheDir, "shares").apply { mkdirs() }.resolve(file.label)
    runCatching { target.writeBytes(file.bytes) }.onFailure { return }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.captures", target)
    val intent = Intent(if (download) Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
        if (download) {
            setDataAndType(uri, file.mime ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = file.mime ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    val chooser = Intent.createChooser(intent, context.getString(ReviewR.string.files_share))
    if (context !is android.app.Activity) {
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}
