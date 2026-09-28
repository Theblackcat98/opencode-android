package dev.opencode.android.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.review.DiffHunk
import dev.opencode.android.core.data.review.DiffLine
import dev.opencode.android.core.data.review.DiffLineKind
import dev.opencode.android.core.data.review.FileNode
import dev.opencode.android.core.data.review.ParsedFile
import dev.opencode.android.core.data.review.ReviewComment
import dev.opencode.android.core.data.review.ReviewScope
import dev.opencode.android.core.designsystem.code.CodeLanguage
import dev.opencode.android.core.designsystem.diff.DiffPair
import dev.opencode.android.core.designsystem.diff.DiffRow
import dev.opencode.android.core.designsystem.diff.DiffRowKind
import dev.opencode.android.core.designsystem.diff.DiffRowView
import dev.opencode.android.core.designsystem.diff.DiffTable
import dev.opencode.android.core.designsystem.diff.DiffColors
import dev.opencode.android.core.designsystem.diff.SplitDiffTable

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
) {
    val colors = DiffColors.of()
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.review_title)) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.action_dismiss),
                    )
                }
            },
            actions = {
                // The two lists a review is read by: the files the agent changed, and the server's
                // own filesystem. Both are actions on the bar rather than tabs, because neither
                // replaces the diff — they sit over it, and the diff is what is behind them.
                IconButton(onClick = onOpenTree) {
                    Icon(Icons.Filled.AccountTree, stringResource(R.string.review_file_tree))
                }
                IconButton(onClick = onOpenFiles) {
                    Icon(Icons.Filled.Folder, stringResource(R.string.files_open))
                }
                // The comments are the one thing a review produces that leaves the screen, so they get
                // a badge rather than a row: the count is the number of things that will go with the
                // next prompt, and a reviewer needs it without opening anything.
                if (state.comments.isNotEmpty()) {
                    BadgedBox(badge = { Badge { Text(state.comments.size.toString()) } }) {
                        IconButton(onClick = onOpenComments) {
                            Icon(
                                Icons.Filled.Comment,
                                stringResource(R.string.review_comment_count, state.comments.size),
                            )
                        }
                    }
                }
            },
        )

        ScopeRow(state = state, onSelectScope = onSelectScope)

        VcsHeader(state = state, onOpenBasePicker = onOpenBasePicker)

        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = state.currentFile?.displayLabel() ?: stringResource(R.string.review_file_tree),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.hasSeveralFiles) {
                IconButton(onClick = onPreviousFile) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.review_previous_file))
                }
                IconButton(onClick = onNextFile) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.review_next_file))
                }
            }
            IconButton(onClick = onPreviousHunk) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.review_previous_hunk))
            }
            IconButton(onClick = onNextHunk) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.review_next_hunk))
            }
        }

        ReviewedProgress(state = state, onToggleReviewed = onToggleReviewed)

        ViewToggles(
            state = state,
            onToggleWrap = onToggleWrap,
            onToggleSplit = onToggleSplit,
        )

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
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = stringResource(R.string.review_comment_count, comments.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = stringResource(R.string.review_file_tree),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            text = stringResource(R.string.review_progress, state.reviewed.size, state.files.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider()
        ReviewFileTree(state = state, onOpenFile = onOpenFile)
    }
}

/** The four scopes, as a row of chips, which is the TUI's `/diff` picker (features doc §38). */
@Composable
private fun ScopeRow(state: ReviewUiState, onSelectScope: (ReviewScope) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.scopes, key = { it.id }) { scope ->
            FilterChip(
                selected = scope == state.scope,
                onClick = { onSelectScope(scope) },
                label = { Text(stringResource(scope.labelRes())) },
            )
        }
    }
}

/** The branch, the changed-file count and the review base, which is what `vcs.get` and `vcs.status` say. */
@Composable
fun VcsHeader(state: ReviewUiState, onOpenBasePicker: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            text = if (state.vcs.isRepository) {
                stringResource(R.string.review_branch, state.branch ?: stringResource(R.string.review_branch_unknown))
            } else {
                stringResource(R.string.review_not_a_repository)
            },
            style = MaterialTheme.typography.labelLarge,
        )
        if (state.vcs.isRepository) {
            Text(
                text = stringResource(R.string.review_changed_files, state.vcs.files.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AssistChip(
            onClick = onOpenBasePicker,
            label = {
                Text(
                    state.effectiveBase
                        ?.let { stringResource(R.string.review_base, it) }
                        ?: stringResource(R.string.review_base_unknown),
                )
            },
        )
    }
}

/** The review's own progress, which is device state and never sent to the server. */
@Composable
private fun ReviewedProgress(state: ReviewUiState, onToggleReviewed: () -> Unit) {
    if (state.files.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.currentReviewed, onCheckedChange = { onToggleReviewed() })
        Text(
            text = if (state.complete) {
                stringResource(R.string.review_complete)
            } else {
                stringResource(R.string.review_progress, state.files.size - state.remaining, state.files.size)
            },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The wrap and split toggles, with split offered only where the layout can honour it. */
@Composable
private fun ViewToggles(
    state: ReviewUiState,
    onToggleWrap: (Boolean) -> Unit,
    onToggleSplit: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = state.wrap,
            onClick = { onToggleWrap(!state.wrap) },
            label = { Text(stringResource(R.string.review_toggle_wrap)) },
        )
        val split = state.split
        if (split != null) {
            FilterChip(
                selected = split,
                onClick = { onToggleSplit(!split) },
                label = { Text(stringResource(R.string.review_toggle_split)) },
            )
        } else {
            // The honest offer on a screen that cannot split is to say so rather than show a toggle
            // that would do nothing.
            Text(
                text = stringResource(R.string.review_split_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            modifier = modifier.padding(16.dp),
        )
        return
    }
    if (file.binary) {
        Text(
            text = stringResource(R.string.review_file_binary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp),
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
        LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
            file.unparsed.forEach { line ->
                item(key = "unparsed:$line") {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp),
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
internal fun emptyMessage(state: ReviewUiState): String = when {
    state.error?.message == ReviewStoreNoSession -> stringResource(R.string.review_no_session)
    state.error?.message == ReviewStoreNoLocation -> stringResource(R.string.review_no_location)
    state.scope is ReviewScope.LastTurn -> stringResource(R.string.review_empty)
    state.scope == ReviewScope.Uncommitted -> stringResource(R.string.review_empty_uncommitted)
    state.scope == ReviewScope.Committed -> stringResource(R.string.review_empty_committed)
    else -> stringResource(R.string.review_empty_all)
}

/** The two reasons a scope cannot run, spelled as the store does. */
internal const val ReviewStoreNoSession: String = "no-session"
internal const val ReviewStoreNoLocation: String = "no-location"

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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(enabled = !node.isDirectory) { onOpenFile(node.path) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics {
                contentDescription = "${node.name}${if (node.isDirectory) ", directory" else ""}"
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
            modifier = Modifier.weight(1f).padding(start = 8.dp),
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
