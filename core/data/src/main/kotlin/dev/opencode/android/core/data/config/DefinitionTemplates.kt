package dev.opencode.android.core.data.config

/**
 * Where a definition file lives relative to a location, and what kind of file it is.
 *
 * **The paths are the ones features doc §33.5 names, and they are relative to the location's
 * directory** — which is what `fs.read` resolves against and what `fs.write` will be given as an
 * absolute path. Keeping the relative half here means a screen never builds a path by joining
 * strings, and the one place that does it is the one place with a test.
 *
 * **The three kinds are Markdown with YAML front matter, and the front matter keys differ.** An
 * agent's front matter carries the same fields as `AgentConfig` (`mode`, `model`, `permission`,
 * `temperature`, `steps`, `color`), a command's carries `description`, `agent` and `model`, and a
 * skill's carries only `name` and `description` — everything else in a skill is its body. Getting
 * this wrong produces a file the server reads and ignores, which is why the templates are built
 * per kind rather than from one shape.
 */
enum class DefinitionKind(val id: String) {

    /** `.opencode/agents/<name>.md` — the agent's front matter plus its system prompt. */
    AGENT("agent"),

    /** `.opencode/commands/<name>.md` — a slash command, whose body is the template (features doc §11). */
    COMMAND("command"),

    /** `.opencode/skills/<name>/SKILL.md` — a skill (features doc §12). */
    SKILL("skill"),

    /** `AGENTS.md` at the location root — the project's instructions (features doc §26). */
    INSTRUCTIONS("instructions"),
    ;

    /** The directory this kind lives in, relative to the location. */
    val directory: String
        get() = when (this) {
            AGENT -> ".opencode/agents"
            COMMAND -> ".opencode/commands"
            SKILL -> ".opencode/skills"
            INSTRUCTIONS -> ""
        }

    /** The file name for [name], which is the only part of the path a screen collects. */
    fun fileName(name: String): String = when (this) {
        AGENT, COMMAND -> "$name.md"
        SKILL -> "$name/SKILL.md"
        INSTRUCTIONS -> "AGENTS.md"
    }

    /** The path for [name] relative to the location directory. */
    fun relativePath(name: String): String = when (this) {
        INSTRUCTIONS -> fileName(name)
        else -> "${directory}/${fileName(name)}"
    }

    /** The front-matter keys this kind understands, which is what the template writes. */
    val frontMatterKeys: List<String>
        get() = when (this) {
            AGENT -> listOf("description", "mode", "model", "variant", "temperature", "steps", "color", "permission")
            COMMAND -> listOf("description", "agent", "model", "subtask")
            SKILL -> listOf("name", "description")
            INSTRUCTIONS -> emptyList()
        }
}

/**
 * The Markdown definition files, and the front matter they need.
 *
 * **Front matter is YAML, and this writes the two kinds that need no escaping.** Every value that
 * can be written is a quoted string, a number or an inline flow map — never a block, never a bare
 * string with a colon in it. A `description: Review the diff: carefully` would parse as a mapping
 * and silently lose the text after the colon; quoting every string means a phone keyboard cannot
 * produce a file the server reads wrongly.
 *
 * **The permission block is written as flow style on purpose.** `AgentConfig.permission` is the same
 * `PermissionConfig` map the top-level `permission` key is, and `{"bash": "ask"}` is legal YAML flow
 * as well as legal JSON, so one line does it and there is no nesting to get wrong from a keyboard.
 *
 * **The body is the user's, untouched.** A system prompt or a command template is prose a person
 * writes; a template that reflowed it would be worse than one that leaves it alone.
 */
object DefinitionTemplates {

    /** The file a new definition of [kind] called [name] starts with. */
    fun newFile(kind: DefinitionKind, name: String): String {
        val title = "# ${name.replace('-', ' ').replace('_', ' ')}"
        return when (kind) {
            DefinitionKind.AGENT -> buildString {
                appendLine("---")
                appendLine("description: ${quote("When to use ${name}")}")
                appendLine("mode: primary")
                appendLine("---")
                appendLine()
                appendLine(title)
                appendLine()
                appendLine("Describe what this agent should do.")
            }

            DefinitionKind.COMMAND -> buildString {
                appendLine("---")
                appendLine("description: ${quote("What $name does")}")
                appendLine("---")
                appendLine()
                appendLine(title)
                appendLine()
                appendLine("Describe the task. The arguments are appended after this template.")
            }

            DefinitionKind.SKILL -> buildString {
                appendLine("---")
                appendLine("name: ${quote(name)}")
                appendLine("description: ${quote("When to use $name")}")
                appendLine("---")
                appendLine()
                appendLine(title)
                appendLine()
                appendLine("Describe the skill and when it should be used.")
            }

            // No front matter: `AGENTS.md` is prose the project reads, and anything else in it would
            // be a line the agent treats as an instruction.
            DefinitionKind.INSTRUCTIONS -> buildString {
                appendLine(title)
                appendLine()
                appendLine("Describe how this project should be worked on.")
            }
        }
    }

    /**
     * Rewrites [text]'s front matter with [fields], keeping the body byte for byte.
     *
     * **A file with no front matter gets one, and a file with one gets it replaced.** Both are
     * ordinary cases — a user writes the body first and adds the front matter later — and returning
     * the text unchanged because there was no front matter would make the form do nothing.
     */
    fun withFrontMatter(text: String, fields: Map<String, String>): String {
        val body = stripFrontMatter(text)
        val front: Map<String, String> = fields.filterValues { it.isNotBlank() }
        if (front.isEmpty()) return body
        return buildString {
            appendLine("---")
            front.forEach { (key, value) -> appendLine("$key: $value") }
            appendLine("---")
            if (body.isNotEmpty()) {
                appendLine()
                append(body)
            }
        }
    }

    /** The front matter [text] has, as the raw lines, for a form to pre-fill. */
    fun frontMatter(text: String): Map<String, String> {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("---")) return emptyMap()
        val body = trimmed.removePrefix("---")
        val end = body.indexOf("\n---")
        if (end < 0) return emptyMap()
        return body.substring(0, end).lineSequence()
            .mapNotNull { line ->
                val key = line.substringBefore(':', missingDelimiterValue = "").trim()
                val value = line.substringAfter(':', missingDelimiterValue = "").trim()
                if (key.isEmpty() || value.isEmpty()) null else key to unquote(value)
            }
            .toMap()
    }

    /** The body of [text], with any front matter removed. */
    fun stripFrontMatter(text: String): String {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("---")) return text
        val body = trimmed.removePrefix("---")
        val end = body.indexOf("\n---")
        if (end < 0) return text
        val after = body.substring(end + 1).removePrefix("---").removePrefix("\n")
        return after.removePrefix("\n")
    }

    /**
     * Renders a permission block as a YAML flow map.
     *
     * **Effect order is the server's, not the caller's.** The map is written in the order the input
     * gave it, because `PermissionObjectConfig` is a map and its evaluation order for overlapping
     * resources is the server's; a template that reordered them would be claiming a precedence it
     * does not have.
     */
    fun permissionFlow(rules: Map<String, String>): String =
        rules.entries.joinToString(", ", prefix = "{", postfix = "}") { (key, value) ->
            "${quote(key)}: ${quote(value)}"
        }

    private fun quote(value: String): String =
        if (value.startsWith("{") || value.startsWith("[")) value else "\"${value.replace("\"", "\\\"")}\""

    /** The reverse of [quote], for pre-filling a form from a file's own front matter. */
    private fun unquote(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
            return trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"")
        }
        return trimmed
    }
}

/**
 * What a definition file needs before it may be written.
 *
 * **A name must be a path segment, not a path.** `../AGENTS.md` or `a/b` would make the editor write
 * outside the directory the user was looking at, and `fs.write` is given a path the *server* resolves.
 * The check is here rather than in a text field's `onValueChange` because the form, the template and
 * the store all build the path, and only the store is reachable without the form.
 */
object DefinitionName {

    private val SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /** Whether [name] may be used as a file name segment. */
    fun isValid(name: String): Boolean =
        name.isNotBlank() && name.length <= 64 && SEGMENT.matches(name) && name != "." && name != ".."

    /** Why [name] cannot be used, in words. */
    fun problem(name: String): String? = when {
        name.isBlank() -> "A name is required"
        name.length > 64 -> "A name may be at most 64 characters"
        !SEGMENT.matches(name) -> "A name may use letters, digits, dots, dashes and underscores only"
        else -> null
    }
}
