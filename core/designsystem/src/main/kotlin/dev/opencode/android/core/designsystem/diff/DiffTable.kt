package dev.opencode.android.core.designsystem.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.R
import dev.opencode.android.core.designsystem.code.CodeHighlighter
import dev.opencode.android.core.designsystem.code.CodeLanguage
import dev.opencode.android.core.designsystem.code.CodeTokenKind
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/**
 * The colours a diff and a highlighter are drawn with, as one value.
 *
 * **A token kind is not a colour.** The kinds are a property of the code, the colours are a
 * property of the theme, and a caller that hard-coded either would make the light and dark
 * screenshots and the user's own theme disagree. So a renderer asks this for a colour and the
 * theme answers.
 */
data class DiffColors(
    val added: Color,
    val removed: Color,
    val context: Color,
    val addedStrong: Color,
    val removedStrong: Color,
    val hunkHeader: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val punctuation: Color,
    val marker: Color,
    val plain: Color,
    val gutter: Color,
    val selection: Color,
) {
    companion object {
        /**
         * The palette from the theme.
         *
         * Added and removed rows use a subtle theme-accent wash, not a full tonal container: the
         * brighter syntax colours and gutter must still contrast against the row. A fixed green
         * would clash with a custom or dynamic theme whose accent may itself be green. Annotations
         * use `tertiary` — colouring `@Composable` in error red reads as "this is broken".
         */
        @Composable
        fun of(scheme: androidx.compose.material3.ColorScheme = MaterialTheme.colorScheme): DiffColors = DiffColors(
            added = scheme.primary.copy(alpha = 0.12f),
            removed = scheme.error.copy(alpha = 0.12f),
            context = Color.Transparent,
            addedStrong = scheme.onSurface,
            removedStrong = scheme.onSurface,
            hunkHeader = scheme.surfaceContainerLow,
            keyword = scheme.primary,
            string = scheme.tertiary,
            number = scheme.secondary,
            comment = scheme.onSurfaceVariant,
            annotation = scheme.tertiary,
            punctuation = scheme.onSurfaceVariant,
            marker = scheme.onSurfaceVariant,
            plain = scheme.onSurface,
            gutter = scheme.onSurfaceVariant,
            selection = scheme.primary.copy(alpha = 0.1f),
        )
    }
}

/** One line of a diff, as the renderer receives it. */
data class DiffRow(
    val oldNumber: Int?,
    val newNumber: Int?,
    val marker: Char,
    val text: String,
    val kind: DiffRowKind,
    /** True when the file has no newline at the end of this line. */
    val noNewlineAtEnd: Boolean = false,
    /** True while the line is inside the user's selection. */
    val selected: Boolean = false,
)

/** What a diff line is, which is all the renderer needs to colour it. */
enum class DiffRowKind {
    CONTEXT,
    ADDED,
    REMOVED,
    HEADER,
    UNPARSED,
}

/**
 * A diff, as a table of monospaced rows with a gutter and syntax highlighting.
 *
 * **One row per line, one `Text` per row.** A thousand-line patch is a thousand rows rather than
 * one enormous annotated string, which is what makes a `LazyColumn` able to recycle it, what makes
 * a row's line number addressable for a comment, and what keeps a recomposition of one row from
 * re-laying-out the other nine hundred and ninety-nine.
 *
 * **Highlighting is remembered per row and computed off the main thread by the caller.**
 * [CodeHighlighter.highlight] is a pure function, so the caller parses a file once with
 * `produceState` on a background dispatcher and passes runs in; this composable only turns runs into
 * spans. That is the "diff tokenization off the main thread" of plan §5.4, and the reason
 * [highlighted] is a separate composable from the row.
 */
@Composable
fun DiffTable(
    rows: List<DiffRow>,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
    showGutter: Boolean = true,
    colors: DiffColors = DiffColors.of(),
    language: CodeLanguage = CodeLanguage.PLAIN_TEXT,
    contentDescription: String? = null,
) {
    val horizontal = rememberScrollState()
    LazyColumn(
        modifier = modifier
            .then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal))
            .semantics { contentDescription?.let { this.contentDescription = it } },
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> diffRowKey(row) }) { _, row ->
            DiffRowView(
                row = row,
                colors = colors,
                wrap = wrap,
                showGutter = showGutter,
                language = language,
            )
        }
    }
}

/**
 * A row's stable identity: its line numbers on each side of the diff. An insertion or deletion
 * above shifts list indices but never a row's own numbers, so a row keeps its key — and its
 * remembered highlight — while the list around it changes.
 */
private fun diffRowKey(row: DiffRow): String =
    "${row.kind}:${row.oldNumber}:${row.newNumber}:${row.text.hashCode()}"

/** A split pair's identity from its two halves; either half may be absent. */
private fun diffPairKey(pair: DiffPair): String {
    val left = pair.left?.let(::diffRowKey) ?: "-"
    val right = pair.right?.let(::diffRowKey) ?: "-"
    return "L$left|R$right"
}

/** One row: the gutter, the marker, and the highlighted text. */
@Composable
fun DiffRowView(
    row: DiffRow,
    colors: DiffColors,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
    showGutter: Boolean = true,
    language: CodeLanguage = CodeLanguage.PLAIN_TEXT,
) {
    val background = when (row.kind) {
        DiffRowKind.ADDED -> colors.added
        DiffRowKind.REMOVED -> colors.removed
        DiffRowKind.HEADER -> colors.hunkHeader
        else -> if (row.selected) colors.selection else colors.context
    }
    val textColor = when (row.kind) {
        DiffRowKind.ADDED -> colors.addedStrong
        DiffRowKind.REMOVED -> colors.removedStrong
        DiffRowKind.HEADER -> colors.gutter
        else -> colors.plain
    }
    val text = remember(row.text, language, colors, textColor) {
        highlightedPlain(row.text, language, colors, textColor)
    }
    // Resolved up front: the annotated builder below is not a @Composable context.
    val noNewlineSuffix = stringResource(R.string.diff_no_newline_suffix)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(vertical = 1.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (showGutter) {
            Row(
                modifier = Modifier.width(GUTTER_WIDTH).padding(end = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = row.oldNumber?.toString() ?: "",
                    style = gutterStyle(),
                    color = colors.gutter,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = row.newNumber?.toString() ?: "",
                    style = gutterStyle(),
                    color = colors.gutter,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
        Text(
            text = row.marker.toString(),
            style = gutterStyle(),
            color = colors.marker,
            modifier = Modifier.width(MARKER_WIDTH),
            maxLines = 1,
        )
        Text(
            text = buildAnnotatedString {
                append(text)
                if (row.noNewlineAtEnd) append(noNewlineSuffix)
            },
            style = codeStyle(),
            color = textColor,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            softWrap = wrap,
            modifier = Modifier.weight(1f, fill = true).padding(end = OpenCodeThemeExtras.spacing.sm),
        )
    }
}

/**
 * One line of highlighted text, as an [AnnotatedString].
 *
 * Public because the transcript's code blocks and the file viewer use the same conversion, and
 * because a screenshot test asserts on the spans rather than on pixels.
 */
@Composable
fun highlighted(
    text: String,
    language: CodeLanguage,
    colors: DiffColors = DiffColors.of(),
    plainColor: Color = colors.plain,
): AnnotatedString = highlightedPlain(text, language, colors, plainColor)

/** The same conversion without the composable scope, so a `remember` inside a row can call it. */
private fun highlightedPlain(
    text: String,
    language: CodeLanguage,
    colors: DiffColors,
    plainColor: Color,
): AnnotatedString {
    if (language == CodeLanguage.PLAIN_TEXT) return AnnotatedString(text)
    if (text.contains('\n')) {
        val lines = CodeHighlighter.highlightAll(text, language)
        return buildAnnotatedString {
            lines.forEachIndexed { i, line ->
                if (i > 0) append("\n")
                var at = 0
                line.tokens.forEach { token ->
                    val value = line.text.substring(
                        token.start.coerceAtMost(line.text.length),
                        token.end.coerceAtMost(line.text.length),
                    )
                    if (token.start > at) {
                        append(line.text.substring(at, token.start.coerceAtMost(line.text.length)))
                    }
                    withStyle(SpanStyle(color = colorOf(token.kind, colors, plainColor))) { append(value) }
                    at = token.end
                }
                if (at < line.text.length) append(line.text.substring(at))
            }
        }
    }
    val line = CodeHighlighter.highlight(text, language)
    return buildAnnotatedString {
        var at = 0
        line.tokens.forEach { token ->
            val value = text.substring(token.start.coerceAtMost(text.length), token.end.coerceAtMost(text.length))
            if (token.start > at) append(text.substring(at, token.start.coerceAtMost(text.length)))
            withStyle(SpanStyle(color = colorOf(token.kind, colors, plainColor))) { append(value) }
            at = token.end
        }
        if (at < text.length) append(text.substring(at))
    }
}

private fun colorOf(kind: CodeTokenKind, colors: DiffColors, plain: Color): Color = when (kind) {
    CodeTokenKind.PLAIN -> plain
    CodeTokenKind.KEYWORD -> colors.keyword
    CodeTokenKind.STRING -> colors.string
    CodeTokenKind.NUMBER -> colors.number
    CodeTokenKind.COMMENT -> colors.comment
    CodeTokenKind.ANNOTATION -> colors.annotation
    CodeTokenKind.PUNCTUATION -> colors.punctuation
    CodeTokenKind.MARKER -> colors.marker
}

@Composable
private fun codeStyle() = OpenCodeThemeExtras.code.body

@Composable
private fun gutterStyle() = OpenCodeThemeExtras.code.small.copy(fontFamily = FontFamily.Monospace)

/** A file's diff on its own surface, which is what the viewer puts on screen. */
@Composable
fun DiffFileCard(
    fileName: String,
    rows: List<DiffRow>,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
    showGutter: Boolean = true,
    language: CodeLanguage = CodeLanguage.ofPath(fileName),
    emptyMessage: String? = null,
    colors: DiffColors = DiffColors.of(),
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = OpenCodeThemeExtras.spacing.xs)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = OpenCodeThemeExtras.spacing.sm, vertical = OpenCodeThemeExtras.spacing.xs),
                maxLines = 1,
            )
            if (rows.isEmpty() && emptyMessage != null) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(OpenCodeThemeExtras.spacing.sm),
                )
            } else {
                DiffTable(
                    rows = rows,
                    wrap = wrap,
                    showGutter = showGutter,
                    colors = colors,
                    language = language,
                )
            }
        }
    }
}

/**
 * A split (side-by-side) diff, which is what a tablet or a landscape phone shows (plan §6, "Diff
 * engine and viewer").
 *
 * **The two columns are the same rows, paired.** A removal and the addition that replaced it have to
 * be on the same row or the reader has to hold one in their head while reading the other, so the
 * pairing is by index within a run of changes and a run with no counterpart shows a blank.
 */
@Composable
fun SplitDiffTable(
    pairs: List<DiffPair>,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
    showGutter: Boolean = true,
    colors: DiffColors = DiffColors.of(),
    language: CodeLanguage = CodeLanguage.PLAIN_TEXT,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // Keyed by each pair's own rows, not by list index: an insertion above must not hand a
        // row's remembered highlight to the row that slid into its slot.
        itemsIndexed(pairs, key = { _, pair -> diffPairKey(pair) }) { _, pair ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) {
                    pair.left?.let {
                        DiffRowView(
                            it,
                            colors,
                            wrap = wrap,
                            showGutter = showGutter,
                            language = language,
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    pair.right?.let {
                        DiffRowView(
                            it,
                            colors,
                            wrap = wrap,
                            showGutter = showGutter,
                            language = language,
                        )
                    }
                }
            }
        }
    }
}

/** One row of a split diff: the two halves, either of which may be absent. */
data class DiffPair(val left: DiffRow?, val right: DiffRow?)

private val GUTTER_WIDTH = 56.dp
private val MARKER_WIDTH = 16.dp
