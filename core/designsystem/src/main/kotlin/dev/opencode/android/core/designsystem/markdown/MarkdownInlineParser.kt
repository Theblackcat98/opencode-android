package dev.opencode.android.core.designsystem.markdown

/**
 * The inline half of the grammar: emphasis, inline code and links inside a run of text.
 *
 * Scanning is left to right and a marker only opens a span when a matching closer follows on the
 * same line, so a lone `*` or an unmatched backtick stays a literal character instead of eating the
 * rest of the paragraph.
 */
internal fun parseInline(text: String): List<MarkdownInline> {
    val out = mutableListOf<MarkdownInline>()
    val plain = StringBuilder()
    var index = 0

    fun flush() {
        if (plain.isNotEmpty()) {
            out += MarkdownInline.Text(plain.toString())
            plain.setLength(0)
        }
    }

    while (index < text.length) {
        val char = text[index]
        when {
            char == '`' -> {
                val close = text.indexOf('`', index + 1)
                if (close > index) {
                    flush()
                    out += MarkdownInline.Code(text.substring(index + 1, close))
                    index = close + 1
                } else {
                    plain.append(char)
                    index++
                }
            }

            char == '*' || char == '_' -> {
                val strong = text.startsWith("$char$char", index)
                val marker = if (strong) "$char$char" else "$char"
                val from = index + marker.length
                val close = text.indexOf(marker, from)
                val body = if (close > from) text.substring(from, close) else null
                if (body != null && body.isNotBlank() && !body.contains('\n')) {
                    flush()
                    out += MarkdownInline.Emphasis(body, strong)
                    index = close + marker.length
                } else {
                    plain.append(char)
                    index++
                }
            }

            char == '[' -> {
                val labelEnd = text.indexOf(']', index + 1)
                if (labelEnd > index && labelEnd + 1 < text.length && text[labelEnd + 1] == '(') {
                    val urlEnd = text.indexOf(')', labelEnd + 2)
                    if (urlEnd > labelEnd) {
                        flush()
                        out += MarkdownInline.Link(
                            text = text.substring(index + 1, labelEnd),
                            url = text.substring(labelEnd + 2, urlEnd),
                        )
                        index = urlEnd + 1
                        continue
                    }
                }
                plain.append(char)
                index++
            }

            else -> {
                plain.append(char)
                index++
            }
        }
    }
    flush()
    return out
}
