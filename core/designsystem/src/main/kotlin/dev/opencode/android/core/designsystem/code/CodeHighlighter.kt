package dev.opencode.android.core.designsystem.code

/**
 * Syntax highlighting, as a pure function from a line of text to a line of runs.
 *
 * **Why this is hand-written and not the `multiplatform-markdown-renderer` highlighting module.**
 * Plan §3 named that module for code blocks; Phase 2 recorded that the Markdown parser was written
 * out instead, and this is the rest of that decision. The reasons, in the order they mattered:
 *
 *  1. **The unit this phase needs is a line, not a document.** A diff viewer renders a thousand
 *     lines of a thousand-line patch, each one independently, in a `LazyColumn`, and the file
 *     viewer renders a file the user may scroll a million lines into. A document renderer composes
 *     per block and owns its own layout; it cannot hand back "these runs of this line" for a viewer
 *     that draws its own gutter, its own line numbers, its own selection and its own comment
 *     anchors. Adopting it for the transcript and writing the diff viewer's own tokenizer would mean
 *     two highlighters, and the two would disagree on the same file.
 *  2. **It has to be total, and this is.** A line that is half a string literal, a line inside a
 *     block comment the previous line opened, a file with a shebang, an unterminated fence — every
 *     one of them produces runs and no exception. That is not an accident of the implementation; it
 *     is why the rules are per-line, per-language and total by construction, and it is testable over
 *     a corpus rather than hoped for.
 *  3. **State across lines is the one thing a line-wise tokenizer must not have, and this does not
 *     have it.** Each line is highlighted in isolation, which is exactly right for a diff (a hunk's
 *     first line has no context above it) and wrong for a file (a triple-quoted string spanning ten
 *     lines). Rather than carry that state — which would make the function a class and the parse
 *     un-`remember`-able per line — [highlight] takes a *line* and the app threads a per-file
 *     carry only where a language needs one. See [LineCarry] for the languages that do and the
 *     deliberate cost.
 *  4. **No new dependency.** The library is Kotlin-Multiplatform-first and brings a Compose
 *     renderer this app would use for one of its three surfaces. The value this phase gets from it
 *     is a function, and the function is four hundred lines.
 *
 * **What is given up, honestly.** No per-language grammar, so a `SELECT` in SQL is not a keyword
 * here, and there is no incremental re-lexing for a file that changes. For a diff — where a few
 * hundred changed lines are shown at a time and the reader is looking at structure, not at
 * grammar — the six classes above are what the eye uses. If the transcript's code blocks ever grow
 * long enough that grammar matters, `highlight` is the seam to replace: it is the only function
 * either the Markdown renderer or the diff viewer depends on.
 */
object CodeHighlighter {

    /**
     * Highlights one line.
     *
     * [carry] is the state the *previous* line left behind, for the two languages whose literals
     * span lines, and the returned line's [CodeLine] carries the state forward. A caller that does
     * not want state passes [LineCarry.NONE] on every line, which is the diff viewer's case and is
     * correct there because a hunk's context is a window, not a file.
     */
    fun highlight(
        text: String,
        language: CodeLanguage,
        number: Int = 0,
        carry: LineCarry = LineCarry.NONE,
    ): CodeLine {
        if (language == CodeLanguage.PLAIN_TEXT) {
            return CodeLine(number, text, listOf(CodeToken(CodeTokenKind.PLAIN, 0, text.length)))
        }
        val rule = rulesFor(language)
        return Lexer(text, rule).run(carry, number)
    }

    /**
     * Highlights a whole file, threading the carry that needs threading.
     *
     * Off the main thread: this is the "diff tokenization off the main thread" of plan §5.4, and
     * it is a pure function over a list, so the caller can move it to whatever dispatcher it has
     * and `remember` the result on the file.
     */
    fun highlightAll(text: String, language: CodeLanguage, firstLineNumber: Int = 1): List<CodeLine> {
        var carry = LineCarry.NONE
        return text.split('\n').mapIndexed { index, line ->
            val line2 = line.removeSuffix("\r")
            val highlighted = highlight(line2, language, firstLineNumber + index, carry)
            carry = highlighted.carry
            highlighted.copy(carry = LineCarry.NONE)
        }
    }

    private fun rulesFor(language: CodeLanguage): LexerRules = when (language) {
        CodeLanguage.KOTLIN, CodeLanguage.SWIFT, CodeLanguage.JAVA -> hashLike(
            lineComment = listOf("//"),
            blockComment = listOf("/*" to "*/"),
            stringDelimiters = listOf('"', '\''),
            keywords = KOTLIN_KEYWORDS,
            tripleQuote = true,
        )

        CodeLanguage.TYPESCRIPT, CodeLanguage.JAVASCRIPT -> hashLike(
            lineComment = listOf("//"),
            blockComment = listOf("/*" to "*/"),
            stringDelimiters = listOf('"', '\'', '`'),
            keywords = JS_KEYWORDS,
            backtickTemplate = true,
        )

        CodeLanguage.PYTHON, CodeLanguage.RUBY, CodeLanguage.SHELL -> hashLike(
            lineComment = listOf("#"),
            blockComment = emptyList(),
            stringDelimiters = listOf('"', '\''),
            keywords = when (language) {
                CodeLanguage.PYTHON -> PYTHON_KEYWORDS
                CodeLanguage.RUBY -> RUBY_KEYWORDS
                else -> SHELL_KEYWORDS
            },
        )

        CodeLanguage.RUST, CodeLanguage.GO, CodeLanguage.C, CodeLanguage.CPP -> hashLike(
            lineComment = listOf("//"),
            blockComment = listOf("/*" to "*/"),
            stringDelimiters = listOf('"', '\''),
            keywords = when (language) {
                CodeLanguage.RUST -> RUST_KEYWORDS
                CodeLanguage.GO -> GO_KEYWORDS
                else -> C_KEYWORDS
            },
            charLiteral = language != CodeLanguage.RUST,
        )

        CodeLanguage.JSON, CodeLanguage.TOML -> dataLike(
            keywords = JSON_KEYWORDS,
            stringDelimiters = listOf('"'),
            lineComment = if (language == CodeLanguage.TOML) listOf("#") else emptyList(),
            blockComment = emptyList(),
        )

        CodeLanguage.YAML -> dataLike(
            keywords = YAML_KEYWORDS,
            stringDelimiters = listOf('"', '\''),
            lineComment = listOf("#"),
            blockComment = emptyList(),
        )

        CodeLanguage.XML, CodeLanguage.MARKDOWN -> markupLike(language)
        CodeLanguage.SQL -> dataLike(
            keywords = SQL_KEYWORDS,
            stringDelimiters = listOf('\'', '"'),
            lineComment = listOf("--"),
            blockComment = listOf("/*" to "*/"),
        )

        CodeLanguage.CSS -> hashLike(
            lineComment = listOf("//"),
            blockComment = listOf("/*" to "*/"),
            stringDelimiters = listOf('"', '\''),
            keywords = CSS_KEYWORDS,
        )

        CodeLanguage.DIFF -> diffLike()
        CodeLanguage.PLAIN_TEXT -> hashLike(emptyList(), emptyList(), emptyList(), emptySet())
    }
}

/** A C-family language: `//` and `/* */` comments, quote-delimited strings, an `@` annotation. */
private fun hashLike(
    lineComment: List<String>,
    blockComment: List<Pair<String, String>>,
    stringDelimiters: List<Char>,
    keywords: Set<String>,
    tripleQuote: Boolean = false,
    backtickTemplate: Boolean = false,
    charLiteral: Boolean = false,
    annotation: Boolean = true,
): LexerRules = LexerRules(
    lineComment = lineComment,
    blockComment = blockComment,
    stringDelimiters = stringDelimiters,
    keywords = keywords,
    tripleQuote = tripleQuote,
    backtickTemplate = backtickTemplate,
    charLiteral = charLiteral,
    annotation = annotation,
)

/** A configuration language: quoted keys and values, and a comment marker. */
private fun dataLike(
    keywords: Set<String>,
    stringDelimiters: List<Char>,
    lineComment: List<String>,
    blockComment: List<Pair<String, String>>,
): LexerRules = LexerRules(
    lineComment = lineComment,
    blockComment = blockComment,
    stringDelimiters = stringDelimiters,
    keywords = keywords,
    annotation = false,
)

/** XML, HTML and Markdown: a tag is a run, and the rest of the line is prose. */
private fun markupLike(language: CodeLanguage): LexerRules = LexerRules(
    lineComment = emptyList(),
    blockComment = emptyList(),
    stringDelimiters = listOf('"'),
    keywords = emptySet(),
    annotation = false,
    markup = true,
    tablePipe = language == CodeLanguage.MARKDOWN,
    quotePrefix = language == CodeLanguage.MARKDOWN,
)

/** A patch body: the marker is the first character and it is the one thing worth colouring. */
private fun diffLike(): LexerRules = LexerRules(
    lineComment = emptyList(),
    blockComment = emptyList(),
    stringDelimiters = emptyList(),
    keywords = emptySet(),
    annotation = false,
    diffBody = true,
)

/**
 * The state a line leaves behind for the next one.
 *
 * Only two languages need it, and both for the same reason: a literal that spans lines. A Kotlin
 * or Swift raw string, a Python triple-quoted string, a JavaScript template literal. Everything else
 * — a block comment that does not close, for instance — is deliberately *not* carried, because a
 * diff shows a window rather than a file and a comment state threaded through a hunk would colour
 * every line of it as comment.
 */
data class LineCarry(
    /** The delimiter that opened a multi-line literal, or `null` when no literal is open. */
    val openStringDelimiter: String? = null,
    /** Whether an open literal is a triple-quoted one, which closes on its own closing marker. */
    val triple: Boolean = false,
) {
    val open: Boolean get() = openStringDelimiter != null

    companion object {
        val NONE = LineCarry()
    }
}

/** What one language's lexer is, as data rather than as a subclass. */
internal data class LexerRules(
    val lineComment: List<String>,
    val blockComment: List<Pair<String, String>>,
    val stringDelimiters: List<Char>,
    val keywords: Set<String>,
    /** The language uses `"""` / `'''` / ```` ``` ```` for a literal that spans lines. */
    val tripleQuote: Boolean = false,
    /** A backtick opens a template literal, which in JavaScript-family languages nests `${}`. */
    val backtickTemplate: Boolean = false,
    /** Single-quote is a character literal rather than a string, in the C family. */
    val charLiteral: Boolean = false,
    /** A `@` prefix marks an annotation or a decorator. */
    val annotation: Boolean = false,
    /** A `-` prefix marks a YAML key, a Markdown list item, or a diff removal. */
    val leadingDashKey: Boolean = false,
    /** A `<` starts a tag, which is what marks XML and HTML content. */
    val markup: Boolean = false,
    /** A `+`/`-` prefix marks a diff change, which is what marks a patch body. */
    val diffBody: Boolean = false,
    /** A `|` starts a table cell in Markdown. */
    val tablePipe: Boolean = false,
    /** A `>` starts a block quote or a YAML folded scalar. */
    val quotePrefix: Boolean = false,
)
