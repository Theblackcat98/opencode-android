package dev.opencode.android.feature.sessions.ui.timeline

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.component.OpenCodeAccordionRow
import dev.opencode.android.core.designsystem.diff.DiffRowKind
import dev.opencode.android.core.designsystem.diff.DiffTable
import dev.opencode.android.core.designsystem.format.Formatters
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.feature.sessions.R

/**
 * A tool call, as one compact row that opens on tap.
 *
 * The row says what was done to what (the kind's icon, the path or command, the kind and its duration, the lines an
 * edit added and removed) and keeps the rest (the diff, the output, the answers) one tap away, because a turn can
 * run dozens of tools and a full card for each buried the answer. **A failed call starts open**, so its reason is on
 * screen without a tap, and a call still running starts open so its progress is visible. A to-do list is drawn as a
 * checklist rather than folded, because the list is the point of the call.
 *
 * The plan's per-tool details are all still here: a path for the file tools, a pattern for `glob` and `grep`, the
 * command for `shell` and `execute`, the URL for `webfetch`, the query for `websearch`, the answers for `question`,
 * the child session for `subagent`, the changed files for `edit`, `write` and `patch`, and the raw input for anything
 * this client has no dedicated card for.
 */
@Composable
fun ToolCardView(
    card: ToolCard,
    modifier: Modifier = Modifier,
    onOpenChangedFile: ((String) -> Unit)? = null,
    onOpenSession: ((String) -> Unit)? = null,
    /** Inside a [ToolGroupView], which draws the container; alone, the row draws its own. */
    grouped: Boolean = false,
) {
    val description = toolDescription(card)
    val failed = card.status is ToolStatus.Failed
    var expanded by remember(card.id) { mutableStateOf(failed || card.inFlight) }
    val scheme = MaterialTheme.colorScheme
    val container = if (grouped) {
        Modifier
    } else {
        Modifier
            .clip(OpenCodeThemeExtras.shapes.card)
            .background(if (failed) OpenCodeThemeExtras.status.failure.copy(alpha = 0.35f) else scheme.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = if (failed) OpenCodeThemeExtras.status.onFailure.copy(alpha = 0.4f) else scheme.outlineVariant.copy(alpha = 0.5f),
                shape = OpenCodeThemeExtras.shapes.card,
            )
    }
    Column(modifier = modifier.fillMaxWidth().then(container).semantics { contentDescription = description }) {
        if (card.kind == ToolCardKind.TODO && card.todos != null) {
            TodoList(card, card.todos)
            return@Column
        }
        OpenCodeAccordionRow(
            title = card.headline(),
            titleMono = card.subject != null,
            meta = card.meta(),
            icon = card.kind.icon(),
            iconTint = if (failed) OpenCodeThemeExtras.status.onFailure else scheme.onSurfaceVariant,
            expanded = expanded,
            onToggle = { expanded = it },
            trailing = { ToolStatusMark(card.status) },
            content = if (card.hasBody(onOpenChangedFile, onOpenSession)) {
                { ToolBody(card, onOpenChangedFile, onOpenSession) }
            } else {
                null
            },
        )
    }
}

/**
 * Several quiet tool calls in a row, folded into one line: "Ran 4 tools · Read file, Search text · 1.2s".
 *
 * Only calls that finished cleanly are folded (see [assistantBlocks]); opening the group shows each one as its own row.
 */
@Composable
fun ToolGroupView(
    cards: List<ToolCard>,
    modifier: Modifier = Modifier,
    onOpenChangedFile: ((String) -> Unit)? = null,
    onOpenSession: ((String) -> Unit)? = null,
) {
    var expanded by remember(cards.first().id) { mutableStateOf(false) }
    val labels = cards.map { stringResource(it.kind.labelRes, it.name) }.distinct().joinToString(", ")
    val total = cards.mapNotNull { it.durationMillis }.takeIf { it.isNotEmpty() }?.sum()
    val meta = listOfNotNull(labels, total?.let { Formatters.duration(it) }).joinToString(" · ")
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(OpenCodeThemeExtras.shapes.card)
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), OpenCodeThemeExtras.shapes.card),
    ) {
        OpenCodeAccordionRow(
            title = pluralStringResource(R.plurals.tool_group_title, cards.size, cards.size),
            meta = meta,
            icon = Icons.Outlined.Construction,
            iconTint = scheme.onSurfaceVariant,
            expanded = expanded,
            onToggle = { expanded = it },
            trailing = { ToolStatusMark(ToolStatus.Completed) },
        ) {
            Column {
                cards.forEachIndexed { index, card ->
                    if (index > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                    ToolCardView(card, onOpenChangedFile = onOpenChangedFile, onOpenSession = onOpenSession, grouped = true)
                }
            }
        }
    }
}

/** The status mark at the end of a row: a spinner while it runs, a cross when it failed, a tick when it is done. */
@Composable
private fun ToolStatusMark(status: ToolStatus) {
    when (status) {
        ToolStatus.Streaming, ToolStatus.Running -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )

        is ToolStatus.Failed -> Icon(
            Icons.Filled.Close,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = OpenCodeThemeExtras.status.onFailure,
        )

        ToolStatus.Completed -> Icon(
            Icons.Filled.Check,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = OpenCodeThemeExtras.status.onSuccess,
        )
    }
}

/** Everything behind the row: the whole subject, the diff, the reason it failed, the output, the links. */
@Composable
private fun ToolBody(
    card: ToolCard,
    onOpenChangedFile: ((String) -> Unit)?,
    onOpenSession: ((String) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The row ellipsizes the subject; a long command or a patch's header is shown whole here.
        card.subject?.takeIf { it.length > SUBJECT_INLINE_LIMIT || it.contains('\n') }?.let { subject ->
            CodeSurface {
                Text(
                    text = if (card.kind == ToolCardKind.SHELL || card.kind == ToolCardKind.EXECUTE) {
                        stringResource(R.string.tool_command, subject)
                    } else {
                        subject
                    },
                    style = OpenCodeThemeExtras.code.small,
                    modifier = Modifier.padding(OpenCodeThemeExtras.spacing.sm).horizontalScroll(rememberScrollState()),
                )
            }
        }
        card.diff?.let { diff ->
            CodeSurface {
                DiffTable(
                    rows = diff.rows,
                    // Wrapped, because a row's text is weighted and an unwrapped table sits in a horizontal
                    // scroll with unbounded width, where a weighted child is given none.
                    wrap = true,
                    showGutter = false,
                    language = diff.language,
                    modifier = Modifier.padding(vertical = OpenCodeThemeExtras.spacing.xs),
                )
            }
            if (diff.hiddenRows > 0) {
                Text(
                    text = stringResource(R.string.tool_diff_more, diff.hiddenRows),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // After the diff and before the output: the diff is what was attempted, this is why it was not done.
        card.failure?.let { reason -> FailureReason(reason) }
        // The diff replaces the raw `files` text an edit used to show: the same information, drawn.
        card.detail?.takeIf { it.isNotBlank() && card.diff == null && card.childSession == null }?.let { detail ->
            ToolOutput(detail)
        }
        card.childSession?.let { child ->
            if (onOpenSession != null) {
                TextButton(onClick = { onOpenSession(child) }) {
                    Text(stringResource(R.string.tool_open_child_session))
                }
            } else {
                Text(
                    text = stringResource(R.string.tool_child_session, child),
                    style = OpenCodeThemeExtras.code.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onOpenChangedFile != null && card.changedFiles.isNotEmpty()) {
            ChangedFilesRow(files = card.changedFiles, onOpenFile = onOpenChangedFile)
        }
    }
}

/** A tool's output: the first lines, and the rest behind "Show all" so one long read does not fill the screen. */
@Composable
private fun ToolOutput(text: String) {
    var all by remember(text) { mutableStateOf(false) }
    val lines = remember(text) { text.trimEnd().lines() }
    val shown = if (all) lines else lines.take(MAX_OUTPUT_LINES)
    CodeSurface {
        Text(
            text = shown.joinToString("\n"),
            style = OpenCodeThemeExtras.code.small,
            modifier = Modifier.padding(OpenCodeThemeExtras.spacing.sm).horizontalScroll(rememberScrollState()),
        )
    }
    if (lines.size > MAX_OUTPUT_LINES) {
        TextButton(onClick = { all = !all }) {
            Text(
                if (all) {
                    stringResource(R.string.tool_output_show_less)
                } else {
                    stringResource(R.string.tool_output_show_all, lines.size)
                },
            )
        }
    }
}

@Composable
private fun CodeSurface(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = OpenCodeThemeExtras.shapes.codeBlock,
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

/** A to-do list, as a checklist with the item in progress marked. */
@Composable
private fun TodoList(card: ToolCard, todos: List<TodoItem>) {
    val done = todos.count { it.done }
    Column(modifier = Modifier.padding(horizontal = OpenCodeThemeExtras.spacing.md, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Checklist,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.tool_todo_progress, done, todos.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            ToolStatusMark(card.status)
        }
        todos.forEach { item ->
            Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = when {
                        item.done -> Icons.Filled.CheckBox
                        item.active -> Icons.Filled.IndeterminateCheckBox
                        else -> Icons.Filled.CheckBoxOutlineBlank
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (item.done) OpenCodeThemeExtras.status.onSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.active) FontWeight.SemiBold else FontWeight.Normal,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** What the row's first line says: the subject when there is one, which is what tells two reads apart. */
@Composable
private fun ToolCard.headline(): String {
    val first = subject?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
    return when {
        first == null -> label()
        kind == ToolCardKind.READ || kind == ToolCardKind.EDIT || kind == ToolCardKind.WRITE -> first.substringAfterLast('/')
        else -> first
    }
}

/** The row's second line: the kind, what an edit added and removed, the duration, and a failure's state. */
@Composable
private fun ToolCard.meta(): String {
    val parts = mutableListOf<String>()
    if (subject != null) parts += label()
    diff?.let { diff ->
        val added = diff.rows.count { it.kind == DiffRowKind.ADDED }
        val removed = diff.rows.count { it.kind == DiffRowKind.REMOVED }
        if (added + removed > 0) parts += stringResource(R.string.tool_diff_stat, added, removed)
    }
    if (subject != null && (kind == ToolCardKind.READ || kind == ToolCardKind.EDIT || kind == ToolCardKind.WRITE)) {
        subject.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { parts += Formatters.directoryTail(it) }
    }
    if (status is ToolStatus.Failed) parts += stringResource(R.string.tool_failed)
    durationMillis?.let { parts += Formatters.duration(it) }
    return parts.joinToString(" · ")
}

@Composable
private fun ToolCard.label(): String = if (kind == ToolCardKind.GENERIC) {
    stringResource(R.string.tool_unknown, name)
} else {
    stringResource(kind.labelRes)
}

private fun ToolCard.hasBody(onOpenChangedFile: ((String) -> Unit)?, onOpenSession: ((String) -> Unit)?): Boolean =
    diff != null ||
        failure != null ||
        !detail.isNullOrBlank() ||
        (subject?.let { it.length > SUBJECT_INLINE_LIMIT || it.contains('\n') } == true) ||
        (onOpenChangedFile != null && changedFiles.isNotEmpty()) ||
        (childSession != null && onOpenSession != null)

/** What TalkBack hears for a row: the kind, its state and duration, and why it failed. */
@Composable
private fun toolDescription(card: ToolCard): String {
    val statusLabel = stringResource(card.status.labelRes())
    val title = card.label()
    val duration = card.durationMillis?.let { Formatters.duration(it) }
    val statusDescription = if (duration == null) {
        stringResource(R.string.tool_status_description, title, statusLabel)
    } else {
        stringResource(
            R.string.tool_status_description,
            title,
            stringResource(R.string.tool_status_description, statusLabel, duration),
        )
    }
    // "Failed" alone tells a screen-reader user nothing they can act on, so the reason is part of the description.
    return card.failure?.let { reason ->
        stringResource(R.string.tool_status_description, statusDescription, spokenFailure(reason))
    } ?: statusDescription
}

private fun ToolCardKind.icon(): ImageVector = when (this) {
    ToolCardKind.READ -> Icons.Outlined.Description
    ToolCardKind.GLOB -> Icons.Outlined.FolderOpen
    ToolCardKind.GREP -> Icons.AutoMirrored.Outlined.ManageSearch
    ToolCardKind.EDIT, ToolCardKind.PATCH -> Icons.Outlined.EditNote
    ToolCardKind.WRITE -> Icons.AutoMirrored.Outlined.NoteAdd
    ToolCardKind.SHELL, ToolCardKind.EXECUTE -> Icons.Outlined.Terminal
    ToolCardKind.WEBFETCH -> Icons.Outlined.Language
    ToolCardKind.WEBSEARCH -> Icons.Outlined.TravelExplore
    ToolCardKind.SKILL -> Icons.Outlined.AutoAwesome
    ToolCardKind.SUBAGENT -> Icons.Outlined.AccountTree
    ToolCardKind.QUESTION -> Icons.AutoMirrored.Outlined.HelpOutline
    ToolCardKind.TODO -> Icons.Outlined.Checklist
    ToolCardKind.GENERIC -> Icons.Outlined.Extension
}

/** A subject longer than this is ellipsized on the row and shown whole when it is opened. */
private const val SUBJECT_INLINE_LIMIT = 48

/** How many lines of output a row shows before "Show all". */
internal const val MAX_OUTPUT_LINES = 12

/** How many lines of a failure reason a card shows before it folds the rest behind a tap. */
internal const val MAX_FAILURE_LINES = 6

/**
 * Why a tool call failed, in the words the server recorded.
 *
 * A message can run to many lines (a rejected call quotes the arguments it was given), and a card in a
 * transcript is read in passing, so it is capped at [MAX_FAILURE_LINES] and the rest is one tap away rather than
 * pushing the transcript around. The tap target exists only once the text has actually been cut, so a short
 * reason is not dressed up as something that can be expanded. It wraps instead of scrolling sideways: this is
 * prose with the odd JSON fragment, and a reason that has to be panned to be read is not visible at all.
 */
@Composable
private fun FailureReason(reason: String, modifier: Modifier = Modifier) {
    var expanded by remember(reason) { mutableStateOf(false) }
    // Sticky: once expanded nothing overflows, but the control has to stay to collapse it again.
    var foldable by remember(reason) { mutableStateOf(false) }
    val toggle = stringResource(if (expanded) R.string.tool_failure_show_less else R.string.tool_failure_show_all)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .then(
                if (foldable) {
                    Modifier.clickable(role = Role.Button, onClickLabel = toggle) { expanded = !expanded }
                } else {
                    Modifier
                },
            ),
    ) {
        Text(
            text = stringResource(R.string.tool_failure_reason),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = reason,
            style = OpenCodeThemeExtras.code.small,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = if (expanded) Int.MAX_VALUE else MAX_FAILURE_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout -> if (layout.hasVisualOverflow) foldable = true },
            modifier = Modifier.padding(top = OpenCodeThemeExtras.spacing.xxs),
        )
        if (foldable) {
            Text(
                text = toggle,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = OpenCodeThemeExtras.spacing.xs),
            )
        }
    }
}

@androidx.annotation.StringRes
private fun ToolStatus.labelRes(): Int = when (this) {
    ToolStatus.Streaming -> R.string.tool_streaming
    ToolStatus.Running -> R.string.tool_running
    ToolStatus.Completed -> R.string.tool_completed
    is ToolStatus.Failed -> R.string.tool_failed
}
