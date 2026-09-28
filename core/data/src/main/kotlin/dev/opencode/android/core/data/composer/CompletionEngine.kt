package dev.opencode.android.core.data.composer

import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.model.ReferenceInfo
import dev.opencode.android.core.model.SkillInfo

/**
 * What a completion stands for, and therefore what selecting it does.
 *
 * The kind is the whole contract between the engine and the action: the engine knows *what the
 * thing is*, and the composer decides what sending it means. Keeping the two apart is what lets the
 * same list drive a row of buttons, an IME suggestion strip and a spoken menu without the engine
 * knowing about any of them.
 */
enum class CompletionKind {
    /** A file on the server, attached as a `file:` URI. */
    FILE,

    /** A directory on the server, attached so the model gets a non-recursive listing. */
    DIRECTORY,

    /** A reference directory from `reference.list`, attached the same way as a directory. */
    REFERENCE,

    /** An agent, which becomes an entry in `agents[]`. */
    AGENT,

    /** A command from `command.list`, which becomes `session.command`. */
    COMMAND,

    /** An MCP prompt, which arrives from `command.list` as `<server>:<prompt>`. */
    MCP_COMMAND,

    /** A client action the app performs itself, never a server command. */
    CLIENT_COMMAND,
}

/**
 * One completion.
 *
 * [insertText] replaces the [TriggerSpan] and nothing else, so the same completion works whether it
 * was chosen from a list, from the IME strip or by voice. [target] is the thing it resolves to —
 * a path, an agent id, a command name — and [range] the line range the text carried, so a mention
 * that already had `#20-45` keeps it.
 */
data class Completion(
    val kind: CompletionKind,
    val label: String,
    val insertText: String,
    val target: String? = null,
    val detail: String? = null,
    val range: LineRange? = null,
) {
    /** The stable key a list must use: two entries can share a label and must still be distinct. */
    val key: String get() = "${kind.name}:$label:$target"
}

/**
 * An action the app performs itself, with no server call behind it.
 *
 * A client command shadows a server command of the same name only if the server defines one, which
 * the TUI also does — `/models` is the app's picker, and a project that defines a `models.md`
 * command is a different thing the user asked for explicitly. The rule is in
 * [CompletionEngine.completeCommands]: a server command of the same name wins, because it is the
 * more specific answer.
 */
enum class ClientAction {
    NEW_SESSION,
    SESSION_LIST,
    MODEL_PICKER,
    AGENT_PICKER,
    COMPACT,
    SIDE_QUESTION,
    EDITOR,
}

/**
 * A `/`-command the app answers itself.
 *
 * [argumentHint] says what follows the name, because a command that takes free text and one that
 * does not look the same in a list. The wording is the UI's: this type carries the identity and the
 * shape, and `strings.xml` carries the words, so a translation never has to change behaviour.
 */
data class ClientCommand(
    val name: String,
    val action: ClientAction,
    val argumentHint: String? = null,
) {
    /** Whether the command wants free text after its name. */
    val takesArguments: Boolean get() = argumentHint != null
}

/**
 * The client commands this phase can actually perform.
 *
 * `/undo`, `/redo` and `/diff` are **not** here. They are real TUI commands, and they are Phase 6
 * operations (`session.revert.*` and `session.diff`), so offering them now would put a row in the
 * palette that cannot do what it says. They arrive with the operations, which is the only order in
 * which a palette is honest.
 */
object ClientCommands {
    val ALL: List<ClientCommand> = listOf(
        ClientCommand("new", ClientAction.NEW_SESSION),
        ClientCommand("sessions", ClientAction.SESSION_LIST),
        ClientCommand("models", ClientAction.MODEL_PICKER),
        ClientCommand("agents", ClientAction.AGENT_PICKER),
        ClientCommand("compact", ClientAction.COMPACT),
        ClientCommand("btw", ClientAction.SIDE_QUESTION, argumentHint = "question"),
        ClientCommand("editor", ClientAction.EDITOR),
    )
}

/**
 * Everything the composer can complete from, at one moment.
 *
 * A snapshot rather than live stores, so the engine is a pure function of text and catalog and can
 * be tested over a corpus instead of through a server. The catalogs behind it are the ones the
 * stores fetch; [files] is the result of the `fs.find` call for the current query, because a
 * ranked recursive search is the only way to complete a path and there is no local filesystem to
 * search.
 */
data class ComposerCatalog(
    val agents: List<AgentInfo> = emptyList(),
    val commands: List<CommandInfo> = emptyList(),
    val skills: List<SkillInfo> = emptyList(),
    val references: List<ReferenceInfo> = emptyList(),
    val files: List<FileSystemEntry> = emptyList(),
    val clientCommands: List<ClientCommand> = ClientCommands.ALL,
    /** The session's location, which is how an absolute server path becomes a short mention. */
    val location: String? = null,
) {
    companion object {
        val EMPTY = ComposerCatalog()
    }
}

/**
 * The completions for a piece of composer text.
 *
 * The whole engine is [complete] plus the private matchers: a cursor position, the text around it
 * and a catalog in, a ranked list of replacements out. Nothing here reads a store, a cursor or a
 * clock, which is why the rules below can be tested over a corpus instead of by typing.
 *
 * **Ranking is by how sure we are, then by kind, then alphabetically.** A prefix match beats a
 * substring match, a directory beats a file (a directory is what a path is being typed towards),
 * and two equally good entries are alphabetical so the list does not jump around between keystrokes
 * as the search results do. An unstable order in a completion list is worse than a slightly wrong
 * one, because the user cannot find the row they were reaching for.
 */
object CompletionEngine {

    /** How many rows a phone can show above the fold without a scroll. */
    const val LIMIT = 20

    /**
     * The completions that apply at [cursor] in [text], or an empty list when nothing does.
     *
     * A trigger that is not in a valid position produces nothing, which is the same answer as "this
     * is a message and there is nothing to complete".
     */
    fun complete(
        text: String,
        cursor: Int = text.length,
        catalog: ComposerCatalog = ComposerCatalog.EMPTY,
    ): List<Completion> {
        val span = detectTrigger(text, cursor) ?: return emptyList()
        return when (span.kind) {
            TriggerKind.MENTION -> completeMentions(span, catalog)
            TriggerKind.COMMAND -> completeCommands(span, catalog)
            TriggerKind.SHELL -> emptyList()
        }
    }

    /**
     * `@` completion: files, directories, references and agents.
     *
     * Files come from `fs.find` and are already ranked by the server; this only matches the typed
     * prefix against them and re-ranks for the keyboard. A hidden reference is not offered, because
     * `hidden` exists to keep it out of completion (features doc §19) — it still works when typed in
     * full, which is what the reference list is for.
     */
    private fun completeMentions(span: TriggerSpan, catalog: ComposerCatalog): List<Completion> {
        // A `#` already in the token is either a finished range or one being typed. Either way it
        // survives the completion: completing a path must not delete the lines the user asked for.
        val fragment = span.query.substringAfter('#', "").takeIf { span.query.contains('#') }
        val query = span.query.substringBefore('#')
        val suffix = fragment?.let { "#$it" }.orEmpty()
        val entries = catalog.files
            .map { entry ->
                val mention = ServerPath.toMentionText(entry.path, catalog.location)
                Completion(
                    kind = if (entry.isDirectory) CompletionKind.DIRECTORY else CompletionKind.FILE,
                    label = ServerPath.label(entry.path),
                    insertText = "@$mention$suffix",
                    target = entry.path,
                    detail = mention,
                    range = fragment?.let(MentionScanner::parseRange),
                ) to rank(mention, ServerPath.label(entry.path), query)
            }
            .filter { it.second >= 0 }
            .sortedWith(compareBy({ it.second }, { if (it.first.kind == CompletionKind.DIRECTORY) 0 else 1 }, { it.first.detail.orEmpty().lowercase() }))
            .map { it.first }
        val references = catalog.references
            .filter { it.hidden != true }
            .map { reference ->
                val mention = ServerPath.toMentionText(reference.path, catalog.location)
                Completion(
                    kind = CompletionKind.REFERENCE,
                    label = reference.name,
                    insertText = "@$mention$suffix",
                    target = reference.path,
                    detail = mention,
                    range = fragment?.let(MentionScanner::parseRange),
                ) to rank(mention, reference.name, query)
            }
            .filter { it.second >= 0 }
            .sortedWith(compareBy({ it.second }, { it.first.label.lowercase() }))
            .map { it.first }
        val agents = catalog.agents
            .map { agent ->
                Completion(
                    kind = CompletionKind.AGENT,
                    label = agent.name.ifBlank { agent.id },
                    insertText = "@${agent.id}",
                    target = agent.id,
                    detail = agent.description,
                ) to rank(agent.id, agent.name, query)
            }
            .filter { it.second >= 0 }
            .sortedWith(compareBy({ it.second }, { it.first.label.lowercase() }))
            .map { it.first }
        return (entries + references + agents).distinctBy { it.key }.take(LIMIT)
    }

    /**
     * `/` completion: server commands, MCP prompts and the app's own actions.
     *
     * A server command of the same name wins over a client one. A project that defines
     * `compact.md` meant it, and the plan's list of client commands is a list of what the app
     * offers, not a claim to every name in the namespace.
     */
    private fun completeCommands(span: TriggerSpan, catalog: ComposerCatalog): List<Completion> {
        val server = catalog.commands
            .map { info ->
                val mcp = info.name.contains(':')
                Completion(
                    kind = if (mcp) CompletionKind.MCP_COMMAND else CompletionKind.COMMAND,
                    // The row shows what was typed, and `/` was typed, so every command row starts
                    // with it. A row labelled `deploy` next to one labelled `/compact` reads as two
                    // different kinds of thing.
                    label = "/" + info.name,
                    insertText = "/" + info.name,
                    target = info.name,
                    detail = info.description,
                ) to rank(info.name, info.name, span.query)
            }
            .filter { it.second >= 0 }
        val taken = catalog.commands.map { it.name }.toSet()
        val client = catalog.clientCommands
            .filter { it.name !in taken }
            .map { command ->
                Completion(
                    kind = CompletionKind.CLIENT_COMMAND,
                    label = "/" + command.name,
                    insertText = "/" + command.name,
                    target = command.name,
                    detail = command.argumentHint,
                ) to rank(command.name, command.name, span.query)
            }
            .filter { it.second >= 0 }
        return (server + client)
            .sortedWith(compareBy({ it.second }, { it.first.label.lowercase() }))
            .map { it.first }
            .distinctBy { it.key }
            .take(LIMIT)
    }

    /**
     * How well [candidate] matches [query], higher being better, or `-1` for no match.
     *
     * Three ranks, in the order a person means them: the whole path starts with what was typed; the
     * file's own name starts with it; it appears anywhere. A prefix rank is checked against the
     * last path segment first, so `a` finds `src/a.ts` by its name even though the full path does
     * not start with `a` — that is what a person typing a filename means.
     */
    private fun rank(candidate: String, label: String, query: String): Int {
        if (query.isEmpty()) return 0
        val needle = query.lowercase()
        val path = candidate.lowercase()
        val name = label.lowercase()
        return when {
            path.startsWith(needle) -> 0
            name.startsWith(needle) -> 1
            path.contains(needle) || name.contains(needle) -> 2
            else -> -1
        }
    }
}
