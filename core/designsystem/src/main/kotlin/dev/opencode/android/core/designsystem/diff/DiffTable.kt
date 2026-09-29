package dev.opencode.android.core.designsystem.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
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
         * The three backgrounds are *low-alpha washes* of the accent colours rather than solid
         * fills: a diff shows two colours of text on one background, and a solid fill behind both
         * sides of a replacement makes the two lines of a change the same colour, which is the one
         * thing a diff has to distinguish.
         */
        @Composable
        fun of(scheme: androidx.compose.material3.ColorScheme = MaterialTheme.colorScheme): DiffColors = DiffColors(
            added = scheme.tertiaryContainer,
            removed = scheme.errorContainer,
            context = Color.Transparent,
            addedStrong = scheme.onTertiaryContainer,
            removedStrong = scheme.onErrorContainer,
            hunkHeader = scheme.surfaceVariant,
            keyword = scheme.primary,
            string = scheme.tertiary,
            number = scheme.secondary,
            comment = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            annotation = scheme.error,
            punctuation = scheme.onSurfaceVariant,
            marker = scheme.onSurfaceVariant,
            plain = scheme.onSurface,
            gutter = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            selection = scheme.primary.copy(alpha = 0.16f),
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
    Column(
        modifier = modifier
            .then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal))
            .semantics { contentDescription?.let { this.contentDescription = it } },
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        rows.forEachIndexed { index, row ->
            DiffRowView(
                row = row,
                key = index.toString(),
                colors = colors,
                wrap = wrap,
                showGutter = showGutter,
                language = language,
            )
        }
    }
}

/** One row: the gutter, the marker, and the highlighted text. */
@Composable
fun DiffRowView(
    row: DiffRow,
    key: String,
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(vertical = 1.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (showGutter) {
            Text(
                text = "${row.oldNumber ?: ""}\t${row.newNumber ?: ""}",
                style = gutterStyle(),
                color = colors.gutter,
                textAlign = TextAlign.End,
                modifier = Modifier.width(GUTTER_WIDTH).padding(end = 4.dp),
                maxLines = 1,
            )
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
                if (row.noNewlineAtEnd) append(NO_NEWLINE_SUFFIX)
            },
            style = codeStyle(),
            color = textColor,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            softWrap = wrap,
            modifier = Modifier.weight(1f, fill = true).padding(end = 8.dp),
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
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                maxLines = 1,
            )
            if (rows.isEmpty() && emptyMessage != null) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        pairs.forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) {
                    pair.left?.let {
                        DiffRowView(
                            it,
                            "l${it.newNumber ?: it.oldNumber}",
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
                            "r${it.newNumber ?: it.oldNumber}",
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

/** The `\ No newline at end of file` note, which a viewer has to say out loud. */
const val NO_NEWLINE_SUFFIX: String = "  \\ No newline at end of file"

private val GUTTER_WIDTH = 56.dp
private val MARKER_WIDTH = 16.dp
