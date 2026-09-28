package dev.opencode.android.core.data.composer

import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.PromptAgentAttachment
import dev.opencode.android.core.model.PromptFileInput
import dev.opencode.android.core.model.PromptRequest
import dev.opencode.android.core.model.PromptSkillInput
import dev.opencode.android.core.model.SessionCommandRequest
import dev.opencode.android.core.model.SessionShellRequest

/**
 * The composer's whole input, as the value that decides what one send does.
 *
 * Everything the decision needs and nothing it does not: the text, the attachments, the delivery
 * mode, the resume flag, the location that turns a path into a URI, and the two command catalogs.
 * A value type is what makes [PromptAssembler] a function rather than a class with a database, and
 * it is what lets a hundred composer states be tested in one file.
 */
data class ComposerInput(
    val text: String = "",
    val attachments: List<AttachmentDraft> = emptyList(),
    val skills: List<PromptSkillInput> = emptyList(),
    val delivery: Delivery = Delivery.Steer,
    val resume: Boolean = false,
    /** The session's location, which is the base a relative server path resolves against. */
    val location: String? = null,
    /** The session's model, used only to decide whether a picture needs confirming. */
    val model: ModelInfo? = null,
    val serverCommands: List<CommandInfo> = emptyList(),
    val clientCommands: List<ClientCommand> = ClientCommands.ALL,
    /** The agents of this location, which is what decides whether a mention is an agent or a file. */
    val agents: List<AgentInfo> = emptyList(),
) {
    /** The delivery the request will carry: `resume` is a flag, not a delivery mode. */
    val effectiveResume: Boolean get() = resume && delivery == Delivery.Steer

    /** Every name an `@mention` can name an agent by: the id, and the display name when it differs. */
    val agentNames: Set<String>
        get() = buildSet {
            agents.forEach { agent ->
                if (agent.id.isNotBlank()) add(agent.id)
                if (agent.name.isNotBlank() && agent.name != agent.id) add(agent.name)
            }
        }
}

/**
 * What one send will do.
 *
 * A caller pattern-matches on this rather than on the text, so the composer never has to re-derive
 * "is this a command?" and the action is decided in exactly one place.
 */
sealed interface PromptIntent {
    /** A message to the agent, through `session.prompt`. */
    data class Prompt(val text: String) : PromptIntent

    /** A server command through `session.command`; [text] is its `$ARGUMENTS`. */
    data class Command(val name: String, val text: String) : PromptIntent

    /** A `!command` through `session.shell`. */
    data class Shell(val command: String) : PromptIntent

    /** A command the app performs itself, with no server call. */
    data class Client(val action: ClientAction, val text: String) : PromptIntent
}

/** Why a send did not become a request. */
enum class PromptProblem {
    /** Nothing to send: no text, and no attachment either. */
    EMPTY,

    /** An attachment the model will not be sent, so the prompt would silently lose it. */
    ATTACHMENT_BLOCKED,

    /** An attachment that is only sendable once the user has been told and agreed. */
    ATTACHMENT_NEEDS_CONFIRMATION,

    /** The picker is looking at no session, so there is nowhere to send anything. */
    NO_SESSION,
}

/** The outcome of assembling the composer's input: a request, a client action, or a refusal. */
sealed interface Assembly {
    data class Prompt(val request: PromptRequest) : Assembly

    data class Command(val request: SessionCommandRequest) : Assembly

    data class Shell(val request: SessionShellRequest) : Assembly

    data class Client(val action: ClientAction, val text: String) : Assembly

    data class Refused(val problem: PromptProblem) : Assembly

    data object Empty : Assembly
}

/**
 * Turns the composer's state into the request the API takes.
 *
 * **One decision, one place.** Whether the text is a prompt, a command, a shell line or a client
 * action; which mentions become `files[]` and which become `agents[]`; whether the attachments may
 * go at all; whether the delivery is steer or queue. Every one of those is a rule a user can be
 * surprised by, and every one of them is here rather than spread across a view model and a text
 * field.
 *
 * The rules, in the order they are applied:
 *
 *  - **A leading `!` is a shell command**, a leading `/name` is a command, and everything else is a
 *    message. A `/name` the catalogs do not know is sent as the message the user typed: a
 *    `command.list` that has not loaded yet must not swallow a sentence, and a sentence is
 *    something the API can always take.
 *  - **A server command beats a client command of the same name.** A project that defines
 *    `compact.md` meant it.
 *  - **A mention is an agent when it names one, and a file otherwise.** The agent catalog is exact,
 *    so a match is certain; a path is not, and the server reports a path it cannot read.
 *  - **An attachment is never sent twice.** A file attached by the paperclip and named by `@` is one
 *    file, and it keeps the mention range so the server can point at the text that asked for it.
 *  - **A client command that takes no arguments refuses attachments**, because there is nowhere for
 *    them to go. `/compact` has no text field; silently dropping the picture would be the one thing
 *    the composer must never do.
 */
object PromptAssembler {

    /**
     * Assembles [input] into the request to send.
     *
     * [confirmed] is the user's answer to "this model cannot see this image", and it only matters
     * when the answer is needed: passing it changes nothing otherwise, so a caller cannot get the
     * confirmation to apply to the wrong send.
     */
    fun assemble(input: ComposerInput, confirmed: Boolean = false): Assembly {
        val text = input.text.trim()
        val shell = input.text.trimStart().takeIf { it.startsWith(TriggerSpan.BANG) }
        val slash = input.text.trimStart().takeIf { it.startsWith(TriggerSpan.SLASH) }
        return when {
            shell != null -> assembleShell(shell, input)
            slash != null -> assembleCommandLine(slash, input, confirmed)
            text.isEmpty() && input.attachments.isEmpty() -> Assembly.Empty
            else -> assemblePrompt(text, input, confirmed)
        }
    }

    /** The intent on its own, for the composer's mode indicator and for tests of the text rules. */
    fun intentOf(text: String, server: List<CommandInfo> = emptyList(), client: List<ClientCommand> = ClientCommands.ALL): PromptIntent? {
        val trimmed = text.trimStart()
        val shell = trimmed.takeIf { it.startsWith(TriggerSpan.BANG) }
        if (shell != null) {
            val command = shell.drop(1).trim()
            return if (command.isEmpty()) null else PromptIntent.Shell(command)
        }
        val slash = trimmed.takeIf { it.startsWith(TriggerSpan.SLASH) } ?: return null
        val name = slash.drop(1).substringBefore(' ').substringBefore('\t')
        if (name.isEmpty() || name.any { it.isWhitespace() }) return null
        val arguments = slash.drop(1 + name.length).trim()
        server.firstOrNull { it.name == name }?.let { return PromptIntent.Command(name, arguments) }
        client.firstOrNull { it.name == name }?.let { return PromptIntent.Client(it.action, arguments) }
        return null
    }

    private fun assembleShell(line: String, input: ComposerInput): Assembly {
        val command = line.trim().drop(1).trim()
        if (command.isEmpty()) return Assembly.Empty
        if (input.attachments.isNotEmpty()) {
            // A shell command is one line of text to the server; there is nowhere to put a file.
            return Assembly.Refused(PromptProblem.ATTACHMENT_BLOCKED)
        }
        return Assembly.Shell(SessionShellRequest(command = command))
    }

    private fun assembleCommandLine(line: String, input: ComposerInput, confirmed: Boolean): Assembly {
        val name = line.drop(1).substringBefore(' ').substringBefore('\t')
        val arguments = line.drop(1 + name.length).trim()
        if (name.isEmpty()) return Assembly.Empty
        input.serverCommands.firstOrNull { it.name == name }?.let {
            return when (val blocked = checkAttachments(input, confirmed, takesText = true)) {
                null -> Assembly.Command(
                    SessionCommandRequest(
                        name = name,
                        text = arguments,
                        files = files(input),
                        agents = agents(input),
                        skills = input.skills.ifEmpty { null },
                        delivery = input.delivery,
                    ),
                )

                else -> blocked
            }
        }
        input.clientCommands.firstOrNull { it.name == name }?.let { command ->
            if (input.attachments.isNotEmpty() && !command.takesArguments) {
                return Assembly.Refused(PromptProblem.ATTACHMENT_BLOCKED)
            }
            if (command.takesArguments && arguments.isEmpty()) return Assembly.Empty
            return Assembly.Client(command.action, arguments)
        }
        // A `/name` the catalogs do not know is not a command. It is the sentence the user typed,
        // and a catalog that has not loaded must not be able to swallow it.
        return assemblePrompt(line.trim(), input, confirmed)
    }

    private fun assemblePrompt(text: String, input: ComposerInput, confirmed: Boolean): Assembly {
        if (input.attachments.isEmpty() && text.isEmpty()) return Assembly.Empty
        when (val blocked = checkAttachments(input, confirmed, takesText = true)) {
            null -> Unit
            else -> return blocked
        }
        return Assembly.Prompt(
            PromptRequest(
                text = text,
                files = files(input).ifEmpty { null },
                agents = agents(input).ifEmpty { null },
                skills = input.skills.ifEmpty { null },
                delivery = input.delivery,
                // `resume` only means anything for steering input; a queued prompt waits anyway,
                // and sending both is a contradiction the server would have to resolve.
                resume = input.effectiveResume.takeIf { it },
            ),
        )
    }

    /** The first refusal, or `null` when the attachments may go. */
    private fun checkAttachments(
        input: ComposerInput,
        confirmed: Boolean,
        takesText: Boolean,
    ): Assembly? {
        if (!takesText && input.attachments.isNotEmpty()) {
            return Assembly.Refused(PromptProblem.ATTACHMENT_BLOCKED)
        }
        input.attachments.forEach { attachment ->
            when (val verdict = AttachmentPolicy.verify(attachment, input.model)) {
                is AttachmentVerdict.Blocked -> return Assembly.Refused(PromptProblem.ATTACHMENT_BLOCKED)
                is AttachmentVerdict.NeedsConfirmation ->
                    if (!confirmed) return Assembly.Refused(PromptProblem.ATTACHMENT_NEEDS_CONFIRMATION)
                AttachmentVerdict.Ok -> Unit
            }
        }
        return null
    }

    /**
     * The `files[]` of the request: the explicit attachments, plus every mention that names a file.
     *
     * Deduplicated by URI, so attaching a file twice attaches it once. A mention that resolved to an
     * already-attached file contributes only the range, which is the one thing the paperclip cannot
     * express.
     */
    private fun files(input: ComposerInput): List<PromptFileInput> {
        val byUri = LinkedHashMap<String, PromptFileInput>()
        input.attachments.forEach { attachment ->
            byUri[attachment.uri] = PromptFileInput(
                uri = attachment.uri,
                name = attachment.label,
                mention = attachment.mention,
            )
        }
        agents(input).map { it.name }.toSet().let { agentNames ->
            mentionTokens(input.text)
                .filter { it.target !in agentNames }
                .forEach { token ->
                    val uri = ServerPath.toUri(token.target, input.location, token.range)
                    val existing = byUri[uri]
                    byUri[uri] = existing?.copy(mention = token.toMention())
                        ?: PromptFileInput(uri = uri, mention = token.toMention())
                }
        }
        return byUri.values.toList()
    }

    /** The `agents[]` of the request: every mention that names an agent of this location. */
    private fun agents(input: ComposerInput): List<PromptAgentAttachment> {
        val names = input.agentNames
        if (names.isEmpty()) return emptyList()
        return mentionTokens(input.text)
            .filter { it.target in names }
            .map { PromptAgentAttachment(name = it.target, mention = it.toMention()) }
            .distinctBy { it.name }
    }

    private fun mentionTokens(text: String): List<MentionToken> = MentionScanner.scan(text)
}
