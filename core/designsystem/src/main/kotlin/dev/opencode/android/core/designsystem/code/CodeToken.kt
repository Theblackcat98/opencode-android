package dev.opencode.android.core.designsystem.code

/**
 * A token of a highlighted line.
 *
 * **A token, not a span.** A diff viewer and a file viewer both need the *same* line broken into
 * runs, and both need to be able to say which run a tap landed in, so the unit is a run with a
 * kind rather than a list of character ranges. [start] is the offset into the line, which is what
 * makes a comment's selection and a tap position comparable without re-scanning the text.
 */
data class CodeToken(
    val kind: CodeTokenKind,
    val start: Int,
    val end: Int,
)

/**
 * The kinds of run a line can be broken into.
 *
 * Deliberately few. A diff has to be readable at arm's length on a phone, and a palette of fifteen
 * hues is less readable than six, so the classes are the ones a person actually uses to read code:
 * keywords, strings, numbers, comments, annotations, and the punctuation that gives a line its
 * shape. Types get the keyword colour, which is the one approximation — a real type is a name, and
 * a name is either a declaration, a use, or a parameter, and telling those apart needs parsing the
 * file rather than the line.
 */
enum class CodeTokenKind {
    PLAIN,
    KEYWORD,
    STRING,
    NUMBER,
    COMMENT,
    ANNOTATION,
    PUNCTUATION,
    /** A section marker, a shebang, a reStructuredText underline. Rare, and worth seeing. */
    MARKER,
    ;

    /** Whether the run is coloured differently from the surrounding text. */
    val isHighlighted: Boolean get() = this != PLAIN
}

/** One line, highlighted: the text and its runs, so a renderer never re-derives either. */
data class CodeLine(
    val number: Int,
    val text: String,
    val tokens: List<CodeToken>,
    /**
     * The state this line leaves for the next one; [LineCarry.NONE] unless a multi-line literal
     * opened here. A diff viewer always drops it and a file viewer always threads it, which is the
     * one place the two callers of the same function differ.
     */
    val carry: LineCarry = LineCarry.NONE,
) {
    /** The run that covers [offset], or `null` past the end of the line. */
    fun tokenAt(offset: Int): CodeToken? = tokens.firstOrNull { offset in it.start until it.end }
}

/** The languages this client highlights, decided by extension (see [CodeHighlighter.languageOf]). */
enum class CodeLanguage(val id: String, val extensions: Set<String>) {
    KOTLIN("kotlin", setOf("kt", "kts")),
    SWIFT("swift", setOf("swift")),
    TYPESCRIPT("typescript", setOf("ts", "tsx", "mts", "cts")),
    JAVASCRIPT("javascript", setOf("js", "jsx", "mjs", "cjs")),
    PYTHON("python", setOf("py", "pyi")),
    RUST("rust", setOf("rs")),
    GO("go", setOf("go")),
    RUBY("ruby", setOf("rb")),
    JAVA("java", setOf("java")),
    C("c", setOf("c", "h")),
    CPP("cpp", setOf("cc", "cpp", "cxx", "hpp", "hh")),
    SHELL("shell", setOf("sh", "bash", "zsh")),
    JSON("json", setOf("json", "jsonc")),
    YAML("yaml", setOf("yaml", "yml")),
    TOML("toml", setOf("toml")),
    MARKDOWN("markdown", setOf("md", "markdown", "mdx")),
    CSS("css", setOf("css", "scss")),
    SQL("sql", setOf("sql")),
    XML("xml", setOf("xml", "svg", "plist", "gradle")),
    DIFF("diff", setOf("diff", "patch")),
    PLAIN_TEXT("text", emptySet()),
    ;

    companion object {
        private val byExtension: Map<String, CodeLanguage> = buildMap {
            entries.forEach { language ->
                language.extensions.forEach { put(it, language) }
            }
        }

        /** The language of a file name, or [PLAIN_TEXT] for one this client does not highlight. */
        fun of(extension: String): CodeLanguage =
            byExtension[extension.lowercase().removePrefix(".")] ?: PLAIN_TEXT

        /**
         * The language of a path or a file name.
         *
         * A `Makefile`, a `Dockerfile` and a `.gitignore` have no extension and are ordinary files
         * in a review, so they are named rather than left unhighlighted for want of a suffix.
         */
        fun ofPath(path: String): CodeLanguage {
            val name = path.trimEnd('/').substringAfterLast('/')
            return when (name) {
                "Makefile", "makefile", "GNUmakefile" -> PLAIN_TEXT
                "Dockerfile", "Containerfile" -> PLAIN_TEXT
                else -> of(name.substringAfterLast('.', ""))
            }
        }

        /**
         * The language a fenced code block declares.
         *
         * Markdown fences name a language the way a file name would — `ts`, `kt`, `sh` — and an
         * alias table is what makes ```` ```kotlin ```` and ```` ```kt ```` the same answer.
         */
        fun ofTag(tag: String?): CodeLanguage {
            val value = tag?.trim()?.lowercase()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: return PLAIN_TEXT
            ALIASES[value]?.let { return it }
            return of(value)
        }
    }
}

private val ALIASES: Map<String, CodeLanguage> = mapOf(
    "kt" to CodeLanguage.KOTLIN,
    "kotlin" to CodeLanguage.KOTLIN,
    "kts" to CodeLanguage.KOTLIN,
    "swift" to CodeLanguage.SWIFT,
    "ts" to CodeLanguage.TYPESCRIPT,
    "tsx" to CodeLanguage.TYPESCRIPT,
    "typescript" to CodeLanguage.TYPESCRIPT,
    "js" to CodeLanguage.JAVASCRIPT,
    "jsx" to CodeLanguage.JAVASCRIPT,
    "javascript" to CodeLanguage.JAVASCRIPT,
    "py" to CodeLanguage.PYTHON,
    "python" to CodeLanguage.PYTHON,
    "rs" to CodeLanguage.RUST,
    "rust" to CodeLanguage.RUST,
    "go" to CodeLanguage.GO,
    "golang" to CodeLanguage.GO,
    "rb" to CodeLanguage.RUBY,
    "ruby" to CodeLanguage.RUBY,
    "java" to CodeLanguage.JAVA,
    "c" to CodeLanguage.C,
    "cpp" to CodeLanguage.CPP,
    "c++" to CodeLanguage.CPP,
    "sh" to CodeLanguage.SHELL,
    "shell" to CodeLanguage.SHELL,
    "bash" to CodeLanguage.SHELL,
    "zsh" to CodeLanguage.SHELL,
    "console" to CodeLanguage.SHELL,
    "json" to CodeLanguage.JSON,
    "jsonc" to CodeLanguage.JSON,
    "yaml" to CodeLanguage.YAML,
    "yml" to CodeLanguage.YAML,
    "toml" to CodeLanguage.TOML,
    "md" to CodeLanguage.MARKDOWN,
    "markdown" to CodeLanguage.MARKDOWN,
    "css" to CodeLanguage.CSS,
    "scss" to CodeLanguage.CSS,
    "sql" to CodeLanguage.SQL,
    "xml" to CodeLanguage.XML,
    "svg" to CodeLanguage.XML,
    "html" to CodeLanguage.XML,
    "gradle" to CodeLanguage.XML,
    "diff" to CodeLanguage.DIFF,
    "patch" to CodeLanguage.DIFF,
    "text" to CodeLanguage.PLAIN_TEXT,
    "txt" to CodeLanguage.PLAIN_TEXT,
    "plain" to CodeLanguage.PLAIN_TEXT,
)
