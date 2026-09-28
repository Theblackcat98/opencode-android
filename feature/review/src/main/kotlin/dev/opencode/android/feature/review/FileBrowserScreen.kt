package dev.opencode.android.feature.review

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.composer.FileContentKind
import dev.opencode.android.core.data.composer.FileReadResult
import dev.opencode.android.core.data.composer.LineRange
import dev.opencode.android.core.designsystem.code.CodeHighlighter
import dev.opencode.android.core.designsystem.code.CodeLanguage
import dev.opencode.android.core.model.FileSystemEntry

/**
 * The file browser: `fs.list` navigation and `fs.read` viewing (plan §6, "File browser").
 *
 * **The server's spelling, all the way through.** A row's path is what `fs.list` gave back, "up" is
 * derived from the same spelling, and a `..` entry is a row like any other. Nothing here joins or
 * normalizes a path, because a joined path is a path the server never offered and the P2 rule is
 * that a path it gave is the path we send.
 *
 * **The viewer is a first-class part of this sheet, not a row's side effect.** A reviewer who opens
 * a file is reading it; a sheet that lists names and then offers three buttons has made the user
 * open a file and then not shown it. So the content, with line numbers and highlighting, is the
 * bottom half of the sheet, and the actions sit under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    directory: String?,
    path: String?,
    entries: List<FileSystemEntry>,
    reading: String?,
    content: FileReadResult?,
    loading: Boolean,
    error: String?,
    onEnter: (FileSystemEntry) -> Unit,
    onUp: () -> Unit,
    onRead: (FileSystemEntry) -> Unit,
    onAttach: (FileReadResult) -> Unit,
    onAttachLines: (FileReadResult, LineRange) -> Unit,
    onShare: (FileReadResult) -> Unit,
    onDownload: (FileReadResult) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    canEdit: Boolean = false,
    onWrite: (FileReadResult, String) -> Unit = { _, _ -> },
    writing: Boolean = false,
    writeNotice: String? = null,
    searchQuery: String = "",
    searchResults: List<FileSystemEntry> = emptyList(),
    onSearchChange: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onNavigateBack, sheetState = sheetState, modifier = modifier) {
        FileBrowserContent(
            directory = directory,
            path = path,
            entries = entries,
            reading = reading,
            content = content,
            loading = loading,
            error = error,
            canEdit = canEdit,
            writing = writing,
            writeNotice = writeNotice,
            searchQuery = searchQuery,
            searchResults = searchResults,
            onSearchChange = onSearchChange,
            onEnter = onEnter,
            onUp = onUp,
            onRead = onRead,
            onAttach = onAttach,
            onAttachLines = onAttachLines,
            onShare = onShare,
            onDownload = onDownload,
            onWrite = onWrite,
        )
    }
}

/**
 * The browser's own content, without the sheet.
 *
 * Separate from the sheet for the same reason the comment dialog's body is separate: a
 * `ModalBottomSheet` animates in, so at the first frame a screenshot would be an empty rectangle —
 * and this app's baselines are its review artifact.
 */
@Composable
fun FileBrowserContent(
    directory: String?,
    path: String?,
    entries: List<FileSystemEntry>,
    reading: String?,
    content: FileReadResult?,
    loading: Boolean,
    error: String?,
    canEdit: Boolean = false,
    writing: Boolean = false,
    writeNotice: String? = null,
    searchQuery: String = "",
    searchResults: List<FileSystemEntry> = emptyList(),
    onSearchChange: (String) -> Unit = {},
    onEnter: (FileSystemEntry) -> Unit = {},
    onUp: () -> Unit = {},
    onRead: (FileSystemEntry) -> Unit = {},
    onAttach: (FileReadResult) -> Unit = {},
    onAttachLines: (FileReadResult, LineRange) -> Unit = { _, _ -> },
    onShare: (FileReadResult) -> Unit = {},
    onDownload: (FileReadResult) -> Unit = {},
    onWrite: (FileReadResult, String) -> Unit = { _, _ -> },
    selection: LineSelection? = null,
    onSelectionChange: (LineSelection) -> Unit = {},
) {
    // The range the user is selecting is a value the caller can hold, not a `remember` buried in
    // this composable: a selection a test cannot set is a selection no baseline can photograph, and
    // "attach lines" is what turns it into a request. [selection] is null for a caller that has no
    // opinion, and the composable keeps its own in that case.
    var own by remember(content?.path) { mutableStateOf(LineSelection()) }
    val current = selection ?: own
    val select: (Int) -> Unit = { line ->
        val next = current.tapped(line)
        own = next
        onSelectionChange(next)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = path?.takeIf { it.isNotBlank() } ?: directory.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider()

        // `fs.find` quick open. It is a field rather than a second list because the server's search
        // is scoped to the location and returns paths relative to it, so a hit cannot be joined
        // onto the current directory: it is shown with the server's own spelling and read from there.
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            label = { Text(stringResource(R.string.files_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // While a query is live the search results *are* the list. Showing the directory underneath
        // them as well would offer two answers to "which files" and put "this directory is empty"
        // under a search that found something.
        val searching = searchQuery.isNotBlank()
        if (searching) {
            if (searchResults.isEmpty()) {
                Text(
                    text = stringResource(R.string.files_no_results),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                searchResults.forEach { hit ->
                    FileRow(entry = hit, reading = reading) { onRead(hit) }
                }
            }
            HorizontalDivider()
        }

        if (path != null && path.isNotBlank()) {
            Text(
                text = stringResource(R.string.files_up),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUp() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.files_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else if (error != null) {
            Text(
                text = stringResource(R.string.files_unreadable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        } else if (entries.isEmpty() && !searching) {
            Text(
                text = stringResource(R.string.files_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        // Two scrollable halves of one sheet, so only the one without content fills the sheet.
        // Both `remember` their position: a `fs.read` replaces the content, and a list that jumped
        // back to the top on every read would make browsing and then opening a file unusable.
        val listState = rememberLazyListState()
        if (!searching) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = content == null),
            state = listState,
        ) {
            items(entries, key = { it.path }) { entry ->
                FileRow(entry = entry, reading = reading) {
                    if (entry.isDirectory) onEnter(entry) else onRead(entry)
                }
            }
        }
        }

        if (content != null) {
            HorizontalDivider()
            FileContent(
                file = content,
                selection = current,
                onSelectLine = select,
                modifier = Modifier.weight(1f, fill = false),
            )
            FileActions(
                file = content,
                canEdit = canEdit,
                writing = writing,
                writeNotice = writeNotice,
                onAttach = { onAttach(content) },
                onAttachLines = { current.range?.let { onAttachLines(content, it) } },
                onShare = { onShare(content) },
                onDownload = { onDownload(content) },
                onWrite = onWrite,
            )
        }
    }
}

/**
 * The line range a file viewer has selected, as a value with a rule for what a tap does.
 *
 * **Two taps, not a drag.** A drag across a `LazyColumn` of code lines is imprecise with a thumb,
 * and "tap the first line, tap the last" is a selection anyone can do. The rule is the one a text
 * editor uses, and it is a pure function so a test can assert it without a screen:
 *
 *  - the first tap sets the anchor, and the range is that one line;
 *  - a tap outside the range extends it to cover the new line;
 *  - a tap *inside* the range starts over from the new line, which is what "I meant that one"
 *     means;
 *  - a tap on the anchor collapses to that single line, which is the only way back from a range.
 */
data class LineSelection(val anchor: Int? = null, val end: Int? = null) {

    /** The range as an attachment, or `null` when nothing is selected. */
    val range: LineRange?
        get() = anchor?.let { start -> LineRange(minOf(start, end ?: start), maxOf(start, end ?: start)) }

    /** Whether [line] is inside the selection, which is what highlights it. */
    fun contains(line: Int): Boolean {
        val first = anchor ?: return false
        val last = end ?: first
        return line in minOf(first, last)..maxOf(first, last)
    }

    /** The selection after a tap on [line]. */
    fun tapped(line: Int): LineSelection {
        val first = anchor ?: return LineSelection(line, null)
        val last = end ?: first
        return when {
            line == first -> LineSelection(line, null)
            line in minOf(first, last)..maxOf(first, last) -> LineSelection(line, null)
            else -> LineSelection(first, line)
        }
    }
}

/**
 * The bytes a `fs.read` returned, rendered.
 *
 * **Text, an image, or a sentence saying neither can be shown.** Those are the three answers the
 * classifier in `FileReader` can give, and each one has a different affordance: a text file can be
 * commented on line by line, an image can be looked at, and a binary can only be shared. A viewer
 * that drew all three the same way would be lying about two of them.
 */
@Composable
private fun FileContent(
    file: FileReadResult,
    selection: LineSelection,
    onSelectLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (file.kind) {
        FileContentKind.TEXT -> TextFileView(
            file = file,
            selection = selection,
            onSelectLine = onSelectLine,
            modifier = modifier,
        )

        FileContentKind.IMAGE -> ImageFileView(file = file, modifier = modifier)

        FileContentKind.BINARY -> Text(
            text = stringResource(R.string.files_binary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp),
        )
    }
}

/**
 * A text file: line numbers, highlighting, and a tap per line.
 *
 * **Highlighting is computed once per file, off the main thread** (plan §5.4). [FileReadResult.lines]
 * and [dev.opencode.android.core.designsystem.code.CodeHighlighter.highlightAll] are pure, so the
 * result is `remember`ed on the content and a re-composition of the browser does not re-lex it.
 */
@Composable
private fun TextFileView(
    file: FileReadResult,
    selection: LineSelection,
    onSelectLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = remember(file.path) { CodeLanguage.ofPath(file.path) }
    // Highlighting the whole file and then dropping the tail would be two passes over a million
    // lines to show two thousand, so the cap is applied before the lexer runs. The number left out is
    // the file's own line count, which is a fact rather than an estimate.
    val all = remember(file.path, file.sizeBytes) { file.lines }
    val shown = all.take(VIEWER_MAX_LINES)
    val lines = remember(file.path, all.size) {
        CodeHighlighter.highlightAll(shown.joinToString("\n"), language)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = VIEWER_MAX_HEIGHT)
            .verticalScroll(rememberScrollState()),
    ) {
        lines.forEach { line ->
            val selected = selection.contains(line.number)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onSelectLine(line.number) }
                    .semantics {
                        contentDescription = "${line.number}, ${line.text.take(80)}"
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = line.number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(GUTTER_WIDTH).padding(end = 8.dp),
                )
                Text(
                    text = line.text.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (all.size > shown.size) {
            // Say what was left out. A viewer that simply stops is a viewer whose end looks like the
            // end of the file, and a reviewer who believes they read all of it is worse off than one
            // who knows to open the rest.
            Text(
                text = stringResource(R.string.files_too_large, (all.size - shown.size).toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        selection.range?.let { range ->
            Text(
                text = stringResource(R.string.files_line_range, range.toSuffix()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * An image the server holds, decoded from the bytes `fs.read` returned.
 *
 * **It is a byte array, not a URI.** The bytes arrived in memory and never touched the disk, which
 * is the point: a viewer that wrote a preview to storage would be creating files on the server's
 * behalf — on the phone, but for a file the user only looked at. `coil-compose` is not used here
 * because there is nothing to fetch; a `BitmapFactory.decodeByteArray` off the main thread is the
 * whole of it, and it fails softly to the same sentence a binary gets.
 */
@Composable
private fun ImageFileView(
    file: FileReadResult,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(file.path, file.sizeBytes) {
        runCatching { BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size) }.getOrNull()
    }
    if (bitmap == null) {
        Text(
            text = stringResource(R.string.files_binary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp),
        )
        return
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = VIEWER_MAX_HEIGHT)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = file.label,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One entry: a directory opens, a file is read, and the row says which it is. */
@Composable
private fun FileRow(entry: FileSystemEntry, reading: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (reading == entry.path) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
}

/** What the viewer offers for a file it has read. */
@Composable
private fun FileActions(
    file: FileReadResult,
    canEdit: Boolean,
    writing: Boolean,
    writeNotice: String?,
    onAttach: () -> Unit,
    onAttachLines: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onWrite: (FileReadResult, String) -> Unit,
) {
        var editing by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = file.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (file.kind == FileContentKind.BINARY) {
            // A binary has no lines to select, so the range action is not offered at all rather than
            // offered and disabled: a control that cannot work is a control that confuses.
            Text(
                text = stringResource(R.string.files_binary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAttach) { Text(stringResource(R.string.files_attach)) }
            if (file.kind == FileContentKind.TEXT) {
                TextButton(onClick = onAttachLines) { Text(stringResource(R.string.files_attach_lines)) }
            }
            TextButton(onClick = onShare) { Text(stringResource(R.string.files_share)) }
            TextButton(onClick = onDownload) { Text(stringResource(R.string.files_download)) }
        }
        if (canEdit) {
            TextButton(onClick = { editing = !editing }, enabled = !writing) {
                Text(stringResource(R.string.files_edit))
            }
        } else {
            Text(
                text = stringResource(R.string.files_edit_off),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        writeNotice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (editing) {
        FileEditDialog(
            file = file,
            writing = writing,
            onConfirm = { text ->
                editing = false
                onWrite(file, text)
            },
            onDismiss = { editing = false },
        )
    }
}

/**
 * The confirmation a remote write requires (plan §5.2: "experimental file writes" ask).
 *
 * **It asks again, at the point of the write, not at the point of the switch.** The switch says
 * "this app may write"; this dialog says "this write, to this file, will replace what is there".
 * Only a text file can be edited — a write replaces the whole file, so a binary would be corrupted
 * by a text editor and the dialog is not offered for one.
 */
@Composable
private fun FileEditDialog(
    file: FileReadResult,
    writing: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(file.path) { mutableStateOf(file.text.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_edit)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.files_edit_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = !writing && text != file.text.orEmpty()) {
                Text(stringResource(R.string.files_edit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_comment_cancel)) }
        },
    )
}

/**
 * The dialog that turns a selection in a file into a comment (plan §6, "Review comments").
 *
 * The body is a separate composable so a screenshot can photograph the content without the dialog's
 * window: `AlertDialog` never reports idle under Robolectric — its window animation is what does not
 * settle — and a screenshot of a window that never settles is a test that fails on a timeout rather
 * than on a pixel. The chrome is Material's and the content is the part this app owns.
 */
@Composable
fun ReviewCommentDialog(
    draft: CommentDraft,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_comment_add, draft.selectionText())) },
        text = { CommentDraftContent(draft = draft, onTextChange = onTextChange) },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = draft.canSubmit) {
                Text(stringResource(R.string.review_comment_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.review_comment_cancel))
            }
        },
    )
}

/** The body of the comment dialog: the anchor, the field and the empty-state sentence. */
@Composable
fun CommentDraftContent(
    draft: CommentDraft,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = draft.path,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.review_comment_add, draft.selectionText()),
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedTextField(
            value = draft.text,
            onValueChange = onTextChange,
            label = { Text(stringResource(R.string.review_comment_hint)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.text.isEmpty()) {
            Text(
                text = stringResource(R.string.review_comment_empty),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The `#12-18` spelling of a draft's range, which the dialog's title and the chip both use. */
internal fun CommentDraft.selectionText(): String =
    dev.opencode.android.core.data.composer.LineRange(startLine, endLine).toSuffix().removePrefix("#")

/** The base-branch picker, which is `vcs.branch.list` (features doc §28). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseBranchSheet(
    branches: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        BaseBranchContent(branches = branches, selected = selected, onSelect = onSelect)
    }
}

/** The base picker's rows, without the sheet. See [FileBrowserContent] for why it is separate. */
@Composable
fun BaseBranchContent(
    branches: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = stringResource(R.string.review_choose_base),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            text = stringResource(R.string.review_choose_base_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        TextButton(onClick = { onSelect(null) }) {
            Text(stringResource(R.string.review_base_default))
        }
        branches.forEach { branch ->
            TextButton(
                onClick = { onSelect(branch) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (branch == selected) "✓ $branch" else branch,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The height cap on the viewer, so a million-line file does not push the actions off the sheet. */
private val VIEWER_MAX_HEIGHT = 320.dp

/** The line-number gutter, which is wide enough for four digits and monospaced. */
private val GUTTER_WIDTH = 40.dp
