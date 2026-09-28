package dev.opencode.android.core.designsystem.code

/**
 * The keyword sets the lexer matches against.
 *
 * They are `internal` rather than private because [CodeHighlighter] builds the per-language rules
 * from them, and a rule that could not see its own keywords would be a rule nobody could test.
 *
 * **They are sets of words, not grammars, and they are small on purpose.** What a reader needs from
 * highlighting is to see the shape of a line: where a value ends and a name begins, where a comment
 * starts. A language's full keyword list would add words a reviewer never sees in a diff and would
 * grow with every release of the language; these are the words that carry the structure, plus the
 * few literals whose colour genuinely helps.
 *
 * Case is normalised on lookup, so a set holds lower case only.
 */
internal val KOTLIN_KEYWORDS = words(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
    "true", "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch",
    "constructor", "delegate", "dynamic", "field", "file", "finally", "get", "import", "init",
    "param", "property", "receiver", "set", "setparam", "where", "abstract", "actual", "annotation",
    "companion", "const", "crossinline", "data", "enum", "expect", "external", "final", "infix",
    "inline", "inner", "internal", "lateinit", "noinline", "open", "operator", "out", "override",
    "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg",
)

internal val JS_KEYWORDS = words(
    "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
    "delete", "do", "else", "export", "extends", "false", "finally", "for", "function", "if",
    "import", "in", "instanceof", "let", "new", "null", "return", "super", "switch", "this",
    "throw", "true", "try", "typeof", "undefined", "var", "void", "while", "with", "yield",
    "async", "get", "of", "set", "static", "from", "as", "interface", "type", "implements",
    "public", "private", "protected", "readonly", "declare", "namespace", "enum", "abstract",
)

internal val PYTHON_KEYWORDS = words(
    "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
    "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is",
    "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while",
    "with", "yield", "match", "case",
)

internal val RUBY_KEYWORDS = words(
    "alias", "and", "begin", "break", "case", "class", "def", "defined?", "do", "else", "elsif",
    "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not", "or", "redo",
    "rescue", "retry", "return", "self", "super", "then", "true", "undef", "unless", "until",
    "when", "while", "yield", "require", "attr_accessor", "attr_reader", "attr_writer",
)

internal val SHELL_KEYWORDS = words(
    "if", "then", "else", "elif", "fi", "case", "esac", "for", "select", "while", "until", "do",
    "done", "function", "return", "break", "continue", "local", "export", "readonly", "declare",
    "source", "alias", "set", "unset", "shift", "trap", "exit", "eval", "exec", "true", "false",
)

internal val RUST_KEYWORDS = words(
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern",
    "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub",
    "ref", "return", "self", "Self", "static", "struct", "super", "trait", "true", "type",
    "unsafe", "use", "where", "while", "union", "box",
)

internal val GO_KEYWORDS = words(
    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for",
    "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return",
    "select", "struct", "switch", "type", "var", "nil", "true", "false", "error", "string",
    "int", "int64", "float64", "bool", "byte", "rune", "make", "new", "len", "cap", "append",
)

internal val C_KEYWORDS = words(
    "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else",
    "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long", "register", "return",
    "short", "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned",
    "void", "volatile", "while", "class", "namespace", "template", "public", "private", "protected",
    "virtual", "new", "delete", "true", "false", "nullptr", "override", "constexpr", "auto",
)

internal val JSON_KEYWORDS = words("true", "false", "null")

internal val YAML_KEYWORDS = words(
    "true", "false", "null", "yes", "no", "on", "off", "y", "n", "~",
)

internal val SQL_KEYWORDS = words(
    "add", "all", "alter", "and", "as", "asc", "begin", "between", "by", "case", "cast", "check",
    "collate", "column", "commit", "constraint", "create", "cross", "default", "delete", "desc",
    "distinct", "drop", "else", "end", "exists", "foreign", "from", "full", "group", "having",
    "if", "in", "index", "inner", "insert", "into", "is", "join", "key", "left", "like", "limit",
    "not", "null", "offset", "on", "or", "order", "outer", "primary", "references", "right",
    "rollback", "select", "set", "table", "then", "transaction", "union", "unique", "update",
    "using", "values", "view", "when", "where", "with",
)

internal val CSS_KEYWORDS = words(
    "important", "inherit", "initial", "unset", "auto", "none", "var", "calc",
)

/**
 * The set as written, with its case kept.
 *
 * Keywords are matched **exactly**, because that is how the languages themselves work: Kotlin's
 * `val` is a keyword and `VAL` is an identifier, and colouring an identifier as a keyword is the
 * kind of quiet lie a highlighter should not tell. The one language that does not distinguish is
 * SQL, and it says so through [LexerRules.keywordsIgnoreCase].
 */
internal fun words(vararg list: String): Set<String> = list.toSet()
