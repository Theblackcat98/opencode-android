package dev.opencode.android.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.review.DiffHunk
import dev.opencode.android.core.data.review.DiffLine
import dev.opencode.android.core.data.review.DiffLineKind
import dev.opencode.android.core.data.review.FileNode
import dev.opencode.android.core.data.review.ParsedFile
import dev.opencode.android.core.data.review.ReviewComment
import dev.opencode.android.core.data.review.ReviewScope
import dev.opencode.android.core.designsystem.code.CodeLanguage
import dev.opencode.android.core.designsystem.component.OpenCodeChip
import dev.opencode.android.core.designsystem.component.OpenCodeTopAppBar
import dev.opencode.android.core.designsystem.diff.DiffColors
import dev.opencode.android.core.designsystem.diff.DiffPair
import dev.opencode.android.core.designsystem.diff.DiffRow
import dev.opencode.android.core.designsystem.diff.DiffRowKind
import dev.opencode.android.core.designsystem.diff.DiffRowView
import dev.opencode.android.core.designsystem.diff.DiffTable
import dev.opencode.android.core.designsystem.diff.SplitDiffTable
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/**
 * The review screen: the scope picker, the VCS header, the file tree, and the diff.
 *
 * **A `LazyColumn` of rows, with stable keys per row** (plan §5.4). The key is the hunk key plus the
 * line's own number, which is stable across a re-fetch and unique inside a file; a patch of a
 * thousand lines therefore recycles rather than re-laying-out, and a mark-reviewed recomposition does
 * not rebuild the file the user is not looking at.
 *
 * **The whole diff is not composed.** Only the visible rows are, and the header, the tree and the
 * navigation are plain columns, so opening a review of a hundred files costs a header and a list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    state: ReviewUiState,
    onSelectScope: (ReviewScope) -> Unit,
    onOpenFile: (String) -> Unit,
    onNextFile: () -> Unit,
    onPreviousFile: () -> Unit,
    onNextHunk: () -> Unit,
    onPreviousHunk: () -> Unit,
    onToggleReviewed: () -> Unit,
    onToggleWrap: (Boolean) -> Unit,
    onToggleSplit: (Boolean) -> Unit,
    onSelectLines: (path: String, start: Int, end: Int) -> Unit,
    onOpenBasePicker: () -> Unit,
    onSelectBase: (String?) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onOpenFiles: () -> Unit = {},
    onOpenTree: () -> Unit = {},
    onOpenComments: () -> Unit = {},
    onRemoveComment: (Int) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val colors = DiffColors.of()
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopAppBar(
            title = stringResource(R.string.review_title),
            onBack = onNavigateBack,
            backDescription = stringResource(R.string.action_dismiss),
            subtitle = { Text(vcsSummary(state), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                // The two lists a review is read by: the files the agent changed, and the server's own filesystem.
                // Both are actions on the bar rather than tabs, because neither replaces the diff.
                IconButton(onClick = onOpenFiles) {
                    Icon(Icons.Filled.Folder, stringResource(R.string.files_open))
                }
                // The comments are the one thing a review produces that leaves the screen, so they get a badge:
                // the count is the number of things that will go with the next prompt.
                if (state.comments.isNotEmpty()) {
                    IconButton(onClick = onOpenComments) {
                        BadgedBox(badge = { Badge { Text(state.comments.size.toString()) } }) {
                            Icon(Icons.Filled.Comment, stringResource(R.string.review_comment_count, state.comments.size))
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.review_view_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.review_toggle_wrap)) },
                            trailingIcon = { Checkbox(checked = state.wrap, onCheckedChange = null) },
                            onClick = { onToggleWrap(!state.wrap) },
                        )
                        val split = state.split
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (split != null) R.string.review_toggle_split else R.string.review_split_unavailable,
                                    ),
                                )
                            },
                            // The honest offer on a screen that cannot split is to say so rather than a toggle that
                            // would do nothing.
                            enabled = split != null,
                            trailingIcon = { if (split != null) Checkbox(checked = split, onCheckedChange = null) },
                            onClick = { if (split != null) onToggleSplit(!split) },
                        )
                    }
                }
            },
        )

        ScopeRow(state = state, onSelectScope = onSelectScope, onOpenBasePicker = onOpenBasePicker)

        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        FileSelector(state = state, onOpenTree = onOpenTree, onToggleReviewed = onToggleReviewed)

        // A failed load must not masquerade as a clean review: the two "empty for a reason" errors
        // keep their empty messages below, and anything else gets an error with a retry.
        val loadError = state.error?.takeUnless { error ->
            error.message == REVIEW_STORE_NO_SESSION || error.message == REVIEW_STORE_NO_LOCATION
        }
        if (loadError != null) {
            ReviewLoadError(error = loadError, onRetry = onRetry)
            return@Column
        }

        if (state.isEmpty) {
            Text(
                text = emptyMessage(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        DiffBody(
            state = state,
            colors = colors,
            onSelectLines = onSelectLines,
            modifier = Modifier.weight(1f),
        )

        ReviewNavigationBar(
            severalFiles = state.hasSeveralFiles,
            onPreviousFile = onPreviousFile,
            onNextFile = onNextFile,
            onPreviousHunk = onPreviousHunk,
            onNextHunk = onNextHunk,
        )
    }

    if (state.basePickerOpen) {
        BaseBranchSheet(
            branches = state.branches,
            selected = state.effectiveBase,
            onSelect = onSelectBase,
            onDismiss = onOpenBasePicker,
        )
    }

    if (state.commentsOpen) {
        ReviewCommentsSheet(
            comments = state.comments,
            onRemove = onRemoveComment,
            onDismiss = onOpenComments,
        )
    }

    if (state.treeOpen) {
        ReviewFileTreeSheet(
            state = state,
            onOpenFile = { path ->
                onOpenFile(path)
                onOpenTree()
            },
            onDismiss = onOpenTree,
        )
    }
}

/**
 * The changed-files tree as a sheet.
 *
 * Separate from the sheet-less [ReviewFileTree] for the same reason the file browser's body is: a
 * `ModalBottomSheet` animates in, so a screenshot of the composable would photograph an empty
 * rectangle. The body is the part this app owns and the part worth photographing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewFileTreeSheet(
    state: ReviewUiState,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        FileTreeContent(state = state, onOpenFile = onOpenFile)
    }
}

/**
 * The comments a review has filed, and the way to take one back.
 *
 * **They are listed, not edited.** A comment's content becomes a prompt the agent reads, so changing
 * one after the fact would leave the review and the prompt disagreeing about what was asked for. A
 * comment is either right or it is removed and written again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewCommentsSheet(
    comments: List<ReviewComment>,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        CommentListContent(comments = comments, onRemove = onRemove)
    }
}

/**
 * The comment rows, without the sheet.
 *
 * See [FileBrowserContent] for why: a `ModalBottomSheet` animates in, so a screenshot of the
 * composable would photograph an empty rectangle, and this app's baselines are its review artifact.
 */
@Composable
fun CommentListContent(
    comments: List<ReviewComment>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = OpenCodeThemeExtras.spacing.xl)) {
        Text(
            text = stringResource(R.string.review_comment_count, comments.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = OpenCodeThemeExtras.spacing.lg, vertical = OpenCodeThemeExtras.spacing.sm),
        )
        HorizontalDivider()
        comments.forEachIndexed { index, comment ->
            ListItem(
                headlineContent = { Text(comment.text) },
                supportingContent = {
                    Text(stringResource(R.string.review_comment_on, comment.path, comment.selection))
                },
                trailingContent = {
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.review_comment_remove))
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

/** The tree's own content: a heading and the rows. See [ReviewFileTreeSheet] for why it is apart. */
@Composable
fun FileTreeContent(
    state: ReviewUiState,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = OpenCodeThemeExtras.spacing.xl)) {
        Text(
            text = stringResource(R.string.review_file_tree),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = OpenCodeThemeExtras.spacing.lg, vertical = OpenCodeThemeExtras.spacing.sm),
        )
        Text(
            text = stringResource(R.string.review_progress, state.reviewed.size, state.files.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = OpenCodeThemeExtras.spacing.lg),
        )
        HorizontalDivider()
        ReviewFileTree(state = state, onOpenFile = onOpenFile)
    }
}

/** The four scopes, as a row of chips, which is the TUI's `/diff` picker (features doc §38). */
@Composable
private fun ScopeRow(state: ReviewUiState, onSelectScope: (ReviewScope) -> Unit, onOpenBasePicker: () -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = OpenCodeThemeExtras.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(state.scopes, key = { it.id }) { scope ->
            OpenCodeChip(
                label = stringResource(scope.labelRes()),
                selected = scope == state.scope,
                onClick = { onSelectScope(scope) },
            )
        }
        // The base is what "committed" and "all" are measured against, so it sits with the scopes it changes.
        item(key = "base") {
            OpenCodeChip(
                label = state.effectiveBase
                    ?.let { stringResource(R.string.review_base, it) }
                    ?: stringResource(R.string.review_base_unknown),
                icon = Icons.AutoMirrored.Filled.AltRoute,
                mono = true,
                onClick = onOpenBasePicker,
            )
        }
    }
}

/** The branch and how many files changed, which is what `vcs.get` and `vcs.status` say, as the bar's subtitle. */
@Composable
private fun vcsSummary(state: ReviewUiState): String = if (state.vcs.isRepository) {
    listOf(
        state.branch ?: stringResource(R.string.review_branch_unknown),
        stringResource(R.string.review_changed_files, state.vcs.files.size),
    ).joinToString(" · ")
} else {
    stringResource(R.string.review_not_a_repository)
}

/**
 * Which file is on screen, as one button that opens the changed-files list ("2 / 5 · ReviewViewModel.kt"), and
 * the reviewed checkbox for it with the review's progress. It replaced a row of four unlabelled arrows.
 */
@Composable
private fun FileSelector(state: ReviewUiState, onOpenTree: () -> Unit, onToggleReviewed: () -> Unit) {
    val spacing = OpenCodeThemeExtras.spacing
    val file = state.currentFile
    val index = file?.let { current -> state.files.indexOfFirst { it.file == current.file } } ?: -1
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = spacing.md, end = spacing.xs, bottom = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onOpenTree,
            shape = OpenCodeThemeExtras.shapes.button,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = spacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (index >= 0 && state.files.size > 1) {
                    Text(
                        text = stringResource(R.string.review_file_position, index + 1, state.files.size),
                        style = OpenCodeThemeExtras.code.small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text = file?.displayLabel() ?: stringResource(R.string.review_file_tree),
                    style = OpenCodeThemeExtras.code.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = stringResource(R.string.review_file_tree),
                )
            }
        }
        if (state.files.isNotEmpty()) {
            val progress = if (state.complete) {
                stringResource(R.string.review_complete)
            } else {
                stringResource(R.string.review_progress, state.files.size - state.remaining, state.files.size)
            }
            Row(
                modifier = Modifier
                    .toggleable(value = state.currentReviewed, role = Role.Checkbox, onValueChange = { onToggleReviewed() })
                    .semantics(mergeDescendants = true) { contentDescription = progress }
                    .padding(start = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = state.currentReviewed, onCheckedChange = null, modifier = Modifier.padding(spacing.md))
            }
        }
    }
}

/**
 * Moving through a review, labelled and at the bottom where the thumb is: previous and next hunk in the middle,
 * previous and next file at the ends, the way a reader steps through a diff.
 */
@Composable
private fun ReviewNavigationBar(
    severalFiles: Boolean,
    onPreviousFile: () -> Unit,
    onNextFile: () -> Unit,
    onPreviousHunk: () -> Unit,
    onNextHunk: () -> Unit,
) {
    val spacing = OpenCodeThemeExtras.spacing
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xs, vertical = spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // File steps use the same chevrons as the hunk steps: FirstPage/LastPage read as
            // "jump to the ends", not "previous/next".
            IconButton(onClick = onPreviousFile, enabled = severalFiles) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.review_previous_file))
            }
            TextButton(onClick = onPreviousHunk, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                Text(stringResource(R.string.review_previous_hunk_short))
            }
            TextButton(onClick = onNextHunk, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.review_next_hunk_short))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
            IconButton(onClick = onNextFile, enabled = severalFiles) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.review_next_file))
            }
        }
    }
}

/**
 * The diff itself.
 *
 * **Unified or split, and only one of them is composed at a time.** A split view pairs removals with
 * the additions that replaced them, and a unified view is a column; building both and hiding one
 * would be twice the work for a screen that can only show one.
 */
@Composable
private fun DiffBody(
    state: ReviewUiState,
    colors: DiffColors,
    onSelectLines: (path: String, start: Int, end: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = state.currentFile
    if (file == null) {
        Text(
            text = stringResource(R.string.review_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(OpenCodeThemeExtras.spacing.lg),
        )
        return
    }
    if (file.binary) {
        Text(
            text = stringResource(R.string.review_file_binary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(OpenCodeThemeExtras.spacing.lg),
        )
        return
    }
    val language = CodeLanguage.ofPath(file.file)
    if (state.split == true) {
        SplitDiffTable(
            pairs = file.pairs(),
            wrap = state.wrap,
            colors = colors,
            language = language,
            modifier = modifier,
        )
    } else {
        val rows = remember(file.key, state.position.hunk) { file.unifiedRows() }
        val listState = rememberLazyListState()
        val targetHunk = state.position.hunk
        LaunchedEffect(targetHunk, file.key) {
            if (targetHunk > 0) {
                val targetIndex = rows.indexOfFirst { it.key.contains(":h$targetHunk:") }
                if (targetIndex >= 0) {
                    listState.animateScrollToItem(targetIndex)
                }
            }
        }
        LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
            file.unparsed.forEach { line ->
                item(key = "unparsed:$line") {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = OpenCodeThemeExtras.spacing.sm),
                    )
                }
            }
            rows.forEach { row ->
                item(key = row.key) {
                    DiffRowView(
                        row = row.row,
                        key = row.key,
                        colors = colors,
                        wrap = state.wrap,
                        language = language,
                        modifier = Modifier.clickable {
                            val line = row.row.newNumber ?: row.row.oldNumber ?: return@clickable
                            onSelectLines(file.file, line, line)
                        },
                    )
                }
            }
        }
    }
}

/** A row of a diff with the key a `LazyColumn` uses for it. */
private data class KeyedRow(val key: String, val row: DiffRow)

/** The rows of a file in unified form, one per line of every hunk. */
private fun ParsedFile.unifiedRows(): List<KeyedRow> = buildList {
    hunks.forEachIndexed { hunkIndex, hunk ->
        if (hunkIndex > 0) {
            add(KeyedRow("$key:h$hunkIndex:header", DiffRow(null, null, '@', hunk.heading.ifEmpty { "@@" }, DiffRowKind.HEADER)))
        }
        hunk.lines.forEach { line ->
            add(KeyedRow("$key:h$hunkIndex:${line.newNumber ?: line.oldNumber}:${line.kind}", line.toRow()))
        }
    }
}

/**
 * The rows of a file in split form, pairing a removal with the addition that replaced it.
 *
 * The pairing is positional within a run of changes, which is what `git diff --word-diff` style
 * viewers do and what a reader needs: a replacement is two lines side by side or it is not a
 * replacement.
 */
private fun ParsedFile.pairs(): List<DiffPair> = buildList {
    hunks.forEach { hunk ->
        var index = 0
        val lines = hunk.lines
        while (index < lines.size) {
            when (lines[index].kind) {
                DiffLineKind.CONTEXT -> {
                    add(DiffPair(lines[index].toRow(), lines[index].toRow()))
                    index++
                }

                DiffLineKind.ADDED -> {
                    add(DiffPair(null, lines[index].toRow()))
                    index++
                }

                DiffLineKind.REMOVED -> {
                    val removed = lines[index].toRow()
                    val added = lines.getOrNull(index + 1)?.takeIf { it.kind == DiffLineKind.ADDED }?.toRow()
                    add(DiffPair(removed, added))
                    index += if (added != null) 2 else 1
                }

                DiffLineKind.UNPARSED -> {
                    add(DiffPair(lines[index].toRow(), lines[index].toRow()))
                    index++
                }
            }
        }
    }
}

/** One diff line as a row, with the marker the unified format uses. */
private fun DiffLine.toRow(): DiffRow = DiffRow(
    oldNumber = oldNumber,
    newNumber = newNumber,
    marker = when (kind) {
        DiffLineKind.ADDED -> '+'
        DiffLineKind.REMOVED -> '-'
        DiffLineKind.UNPARSED -> '?'
        DiffLineKind.CONTEXT -> ' '
    },
    text = text,
    kind = when (kind) {
        DiffLineKind.ADDED -> DiffRowKind.ADDED
        DiffLineKind.REMOVED -> DiffRowKind.REMOVED
        DiffLineKind.UNPARSED -> DiffRowKind.UNPARSED
        DiffLineKind.CONTEXT -> DiffRowKind.CONTEXT
    },
    noNewlineAtEnd = noNewlineAtEnd,
)

/** The last segment of a path, which is what a header row has room for. */
private fun ParsedFile.displayLabel(): String = file.trimEnd('/').substringAfterLast('/').ifEmpty { file }

/** The scope's own name, which `strings.xml` supplies. */
@Composable
internal fun ReviewScope.labelRes(): Int = when (this) {
    is ReviewScope.LastTurn -> R.string.review_scope_last_turn
    ReviewScope.Uncommitted -> R.string.review_scope_uncommitted
    ReviewScope.Committed -> R.string.review_scope_committed
    ReviewScope.All -> R.string.review_scope_all
}

/** The sentence for an empty review, which differs by scope because the reasons differ. */
@Composable
/**
 * A failed diff load, which must not look like a clean review. The message is the failure's own
 * words; the retry re-runs the load.
 */
@Composable
private fun ReviewLoadError(error: ActionError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = OpenCodeThemeExtras.spacing
    Surface(
        modifier = modifier.fillMaxWidth().padding(spacing.lg),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(text = stringResource(R.string.review_load_failed), style = MaterialTheme.typography.titleSmall)
            Text(text = error.message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

internal fun emptyMessage(state: ReviewUiState): String = when {
    state.error?.message == REVIEW_STORE_NO_SESSION -> stringResource(R.string.review_no_session)
    state.error?.message == REVIEW_STORE_NO_LOCATION -> stringResource(R.string.review_no_location)
    state.scope is ReviewScope.LastTurn -> stringResource(R.string.review_empty)
    state.scope == ReviewScope.Uncommitted -> stringResource(R.string.review_empty_uncommitted)
    state.scope == ReviewScope.Committed -> stringResource(R.string.review_empty_committed)
    else -> stringResource(R.string.review_empty_all)
}

/** The two reasons a scope cannot run, spelled as the store does. */
internal const val REVIEW_STORE_NO_SESSION: String = "no-session"
internal const val REVIEW_STORE_NO_LOCATION: String = "no-location"

/**
 * The file tree, which is a list of the paths a review contains.
 *
 * **It is a tree, and the tree is a value.** [ReviewUiState.tree] is built by `FileTree` from the
 * diff's own paths, so a row's key is its path and a row's label is the display path with the
 * review's common prefix removed. Recomputing the tree per frame is the one thing this screen must
 * not do, which is why it is a field of the state rather than something `DiffBody` derives.
 */
@Composable
fun ReviewFileTree(
    state: ReviewUiState,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
) {
    val nodes = if (expanded) state.tree.all() else listOf(state.tree)
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(nodes, key = { it.path.ifEmpty { it.name } }) { node ->
            FileTreeRow(
                node = node,
                current = node.path == state.currentFile?.file,
                // The reviewed set is keyed by the *change*, not the path: a scope can list one
                // path twice, and a mark belongs to the row the user marked. So the row asks the
                // diff for the key rather than composing one, because a key the tree invents is a
                // key the progress count does not have.
                reviewed = state.files.any { it.file == node.path && state.reviewed.contains(it.key) },
                onOpenFile = onOpenFile,
            )
        }
    }
}

/** One row of the tree: an icon, a name, the change counts and the reviewed mark. */
@Composable
private fun FileTreeRow(
    node: FileNode,
    current: Boolean,
    reviewed: Boolean,
    onOpenFile: (String) -> Unit,
) {
    val background = if (current) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    val directoryDesc = stringResource(R.string.review_tree_directory_desc, node.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(background)
            .clickable(enabled = !node.isDirectory, role = Role.Button) { onOpenFile(node.path) }
            .padding(horizontal = OpenCodeThemeExtras.spacing.md, vertical = OpenCodeThemeExtras.spacing.sm)
            .semantics {
                contentDescription = if (node.isDirectory) directoryDesc else node.name
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (node.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = node.name.ifEmpty { node.path },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = OpenCodeThemeExtras.spacing.sm),
        )
        if (reviewed) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.review_mark_reviewed),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
