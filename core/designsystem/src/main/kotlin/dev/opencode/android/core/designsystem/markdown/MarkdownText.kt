package dev.opencode.android.core.designsystem.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.R
import dev.opencode.android.core.designsystem.code.CodeHighlighter
import dev.opencode.android.core.designsystem.code.CodeLanguage
import dev.opencode.android.core.designsystem.diff.DiffColors
import dev.opencode.android.core.designsystem.diff.highlighted
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/**
 * Renders an assistant answer (plan §5.4).
 *
 * **The parse is remembered, not recomputed on every frame.** Parsing a long answer into blocks and
 * spans is the expensive part of showing a transcript; doing it inside a `remember` keyed on the
 * text means a recomposition (a token arriving, the theme changing) does not re-parse the whole
 * answer. The caller's own dispatcher decides which thread does the work, and
 * `rememberParsedMarkdown` is the hook for moving it off the main thread.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val blocks = rememberParsedMarkdown(markdown)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> Text(
                    text = annotate(parseInline(block.text), style),
                    style = style,
                )

                is MarkdownBlock.Heading -> Text(
                    text = annotate(parseInline(block.text), style),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                )

                is MarkdownBlock.BulletList -> block.items.forEach { item ->
                    Row {
                        Text(stringResource(R.string.md_bullet_marker), style = style)
                        Text(text = annotate(listOf(item), style), style = style)
                    }
                }

                is MarkdownBlock.NumberedList -> block.items.forEachIndexed { position, item ->
                    Row {
                        Text("${block.start + position}.  ", style = style)
                        Text(text = annotate(listOf(item), style), style = style)
                    }
                }

                is MarkdownBlock.Code -> CodeBlock(
                    code = block.code,
                    language = block.language,
                    modifier = Modifier.fillMaxWidth(),
                )

                is MarkdownBlock.Quote -> Column(modifier = Modifier.padding(start = 12.dp)) {
                    HorizontalDivider()
                    block.lines.forEach { line ->
                        when (line) {
                            is MarkdownBlock.Paragraph -> Text(
                                text = annotate(parseInline(line.text), style),
                                style = style.copy(color = LocalContentColor.current.copy(alpha = 0.7f)),
                            )

                            else -> MarkdownBlockView(line, style)
                        }
                    }
                    HorizontalDivider()
                }

                is MarkdownBlock.Table -> Table(block, style)

                is MarkdownBlock.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = if (block.style == MarkdownBlock.DividerStyle.HORIZONTAL_RULE) {
                        MaterialTheme.colorScheme.outlineVariant
                    } else {
                        Color.Transparent
                    },
                )

                is MarkdownBlock.Verbatim -> Text(text = block.text, style = style)
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, style: androidx.compose.ui.text.TextStyle) {
    when (block) {
        is MarkdownBlock.Paragraph -> Text(text = annotate(parseInline(block.text), style), style = style)

        is MarkdownBlock.Heading -> Text(text = annotate(parseInline(block.text), style), style = style)

        is MarkdownBlock.BulletList -> block.items.forEach {
            Text(stringResource(R.string.md_bullet_marker) + it.plainText(), style = style)
        }

        is MarkdownBlock.NumberedList -> block.items.forEach {
            Text(stringResource(R.string.md_number_marker) + it.plainText(), style = style)
        }

        is MarkdownBlock.Code -> CodeBlock(block.code, block.language, Modifier.fillMaxWidth())

        is MarkdownBlock.Table -> Table(block, style)

        else -> Text(block.textOrEmpty(), style = style)
    }
}

private fun MarkdownBlock.textOrEmpty(): String = when (this) {
    is MarkdownBlock.Verbatim -> text
    is MarkdownBlock.Quote -> lines.joinToString("\n") { it.textOrEmpty() }
    else -> ""
}

@Composable
private fun Table(block: MarkdownBlock.Table, style: androidx.compose.ui.text.TextStyle) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
    ) {
        block.header.forEachIndexed { column, cell ->
            val alignment = block.alignment.getOrNull(column) ?: ColumnAlignment.LEFT
            Text(
                text = annotate(parseInline(cell.plainText()), style),
                style = style.copy(
                    fontWeight = FontWeight.SemiBold,
                    textAlign = alignment.toTextAlign(),
                ),
                modifier = Modifier
                    .width(160.dp)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    HorizontalDivider()
    block.rows.forEach { row ->
        Row(modifier = Modifier.fillMaxWidth()) {
            row.forEachIndexed { column, cell ->
                val alignment = block.alignment.getOrNull(column) ?: ColumnAlignment.LEFT
                Text(
                    text = annotate(parseInline(cell.plainText()), style),
                    style = style.copy(textAlign = alignment.toTextAlign()),
                    modifier = Modifier
                        .width(160.dp)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun ColumnAlignment.toTextAlign(): TextAlign = when (this) {
    ColumnAlignment.LEFT -> TextAlign.Start
    ColumnAlignment.CENTER -> TextAlign.Center
    ColumnAlignment.RIGHT -> TextAlign.End
}

/**
 * A code block: monospace, its own surface, and no wrapping, so a long line scrolls sideways.
 *
 * **Highlighted since Phase 6, and by this project's own lexer.** The `multiplatform-markdown-renderer`
 * highlighting module that plan §3 named is not adopted, and the reason is recorded on
 * [dev.opencode.android.core.designsystem.code.CodeHighlighter]: the diff viewer and the file viewer
 * need *runs of a line*, not a composed document, and a third highlighter for the same file would
 * be three answers to "what colour is this line". The block resolves its language from the fence tag
 * and highlights the body with the same function the diff viewer uses, so a snippet in an answer and
 * the same lines in a diff are drawn the same way.
 */
@Composable
fun CodeBlock(
    code: String,
    language: String? = null,
    modifier: Modifier = Modifier,
) {
    val codeTypography = OpenCodeThemeExtras.code
    val resolved = CodeLanguage.ofTag(language)
    val colors = DiffColors.of()
    val scroll = rememberScrollState()
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (language != null) {
                Text(
                    text = language,
                    style = codeTypography.small.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
            Text(
                text = highlighted(code.trimEnd('\n'), resolved, colors),
                style = codeTypography.body,
                modifier = Modifier.horizontalScroll(scroll),
            )
        }
    }
}

/**
 * The parse, remembered on the text.
 *
 * `produceState` is deliberately not used: the parse has to be visible to the first composition or
 * the answer would flash as raw Markdown. Callers that need it off the main thread use
 * `rememberParsedMarkdownOffThread`.
 */
@Composable
fun rememberParsedMarkdown(markdown: String): List<MarkdownBlock> = remember(markdown) { parseMarkdown(markdown) }

/** Turns inline markup into a styled string. */
@Composable
private fun annotate(
    inlines: List<MarkdownInline>,
    style: androidx.compose.ui.text.TextStyle,
): AnnotatedString = buildAnnotatedString {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.Text -> append(inline.text)

            is MarkdownInline.Emphasis -> withStyle(
                if (inline.strong) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(fontStyle = FontStyle.Italic),
            ) { append(inline.text) }

            is MarkdownInline.Code -> withStyle(SpanStyle(fontFamily = OpenCodeThemeExtras.code.body.fontFamily)) {
                append(inline.text)
            }

            is MarkdownInline.Link -> withStyle(
                SpanStyle(color = Color(0xFF1A73E8)),
            ) { append(inline.text) }
        }
    }
}
