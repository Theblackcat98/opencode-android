package dev.opencode.android.core.data.transcript

import dev.opencode.android.core.data.timeline.textOutput
import dev.opencode.android.core.model.AssistantContent
import dev.opencode.android.core.model.Outcome
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.ToolState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * A transcript as plain text, for "copy message" and "copy the whole conversation"
 * (plan §6, Phase 3, Session management).
 *
 * **Text, not Markdown, not HTML.** The destination is a chat app, an issue tracker or a terminal
 * paste, so the output has to survive being pasted anywhere. A tool call becomes its most
 * identifying argument plus its output, indented, because that is the part a reader needs and the
 * part a model wrote as JSON.
 *
 * **Pure, and therefore testable.** The alternative is to build the string in a composable, which
 * makes "what exactly does copy produce" unanswerable without a screenshot.
 *
 * Every message type has a case, including the ones this client does not recognise: an unknown type
 * is labelled by its discriminator rather than dropped, because a transcript that silently loses
 * messages is worse than one that says what it could not read.
 */
object TranscriptFormatter {

    /** The whole conversation, oldest first, one blank line between messages. */
    fun transcript(messages: List<SessionMessage>, title: String? = null): String = buildString {
        if (!title.isNullOrBlank()) {
            appendLine("# $title")
            appendLine()
        }
        messages.forEach { message ->
            val text = message(message)
            if (text.isNotBlank()) appendLine(text).appendLine()
        }
    }.trimEnd() + "\n"

    /** One message as text, headed by who produced it. */
    fun message(message: SessionMessage): String = when (message) {
        is SessionMessage.User -> buildString {
            appendLine("## You")
            message.files?.takeIf { it.isNotEmpty() }?.let { files ->
                appendLine("Attachments: ${files.joinToString(", ") { it.name ?: "file" }}")
            }
            message.agents?.takeIf { it.isNotEmpty() }
                ?.let { appendLine("Agents: ${it.joinToString(", ") { agent -> agent.name }}") }
            message.skills?.takeIf { it.isNotEmpty() }
                ?.let { appendLine("Skills: ${it.joinToString(", ") { skill -> skill.name }}") }
            append(message.text)
        }

        is SessionMessage.Assistant -> buildString {
            appendLine("## ${message.agent} (${message.model})")
            message.content.forEach { part ->
                when (part) {
                    is AssistantContent.Text -> appendLine(part.text)
                    is AssistantContent.Reasoning -> appendLine(part.text.lineSequence().joinToString("\n") { "> $it" })
                    is AssistantContent.Tool -> appendLine(toolText(part))
                    is AssistantContent.Unknown -> appendLine("[unrecognised part ${part.discriminator ?: "?"}]")
                }
            }
            message.error?.let { appendLine("_failed: ${it.message}_") }
        }

        is SessionMessage.Synthetic -> buildString {
            appendLine("## Injected")
            append(message.description ?: message.text)
        }

        is SessionMessage.System -> buildString {
            appendLine("## System")
            append(message.description ?: message.text)
        }

        is SessionMessage.Skill -> buildString {
            appendLine("## Skill ${message.name}")
            append(message.text)
        }

        is SessionMessage.Shell -> buildString {
            appendLine("## Shell")
            appendLine("\$ ${message.command}")
            message.output?.output?.takeIf { it.isNotBlank() }?.let { appendLine(indent(it)) }
        }

        is SessionMessage.Compaction -> buildString {
            appendLine("## Compaction (${message.status})")
            appendLine("Reason: ${message.reason.value}")
            message.summary?.takeIf { it.isNotBlank() }?.let { appendLine(indent(it)) }
            message.error?.let { appendLine("_failed: ${it.message}_") }
        }

        is SessionMessage.Idle -> "--- turn ${message.outcome.describe()} ---"

        is SessionMessage.AgentSwitched -> "--- agent switched to ${message.agent} ---"

        is SessionMessage.ModelSwitched -> "--- model switched to ${message.model} ---"

        is SessionMessage.LocationSwitched -> "--- moved to ${message.location.directory} ---"

        is SessionMessage.Unknown -> "--- [unrecognised message ${message.discriminator ?: "?"}] ---"
    }.trimEnd()

    /** A tool call: its name, its most identifying argument, and its output when it has one. */
    private fun toolText(tool: AssistantContent.Tool): String = buildString {
        append("- **${tool.name}**")
        subjectOf(tool.state)?.let { append(" `$it`") }
        outputOf(tool.state)?.let {
            appendLine()
            appendLine()
            appendLine(indent(it))
        }
    }.trimEnd()

    private fun subjectOf(state: ToolState): String? {
        val input: Map<String, JsonElement> = when (state) {
            is ToolState.Running -> state.input
            is ToolState.Completed -> state.input
            is ToolState.Error -> state.input
            else -> emptyMap()
        }
        return input.entries.firstNotNullOfOrNull { (key, value) ->
            if (key !in SUBJECT_KEYS) return@firstNotNullOfOrNull null
            (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
        }
    }

    private fun outputOf(state: ToolState): String? = when (state) {
        is ToolState.Completed -> state.textOutput.takeIf { it.isNotBlank() }
        is ToolState.Error -> state.error.message.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun indent(text: String): String = text.lineSequence().joinToString("\n") { "    $it" }

    private fun Outcome.describe(): String = when (this) {
        Outcome.Succeeded -> "succeeded"
        Outcome.Failed -> "failed"
        Outcome.Interrupted -> "interrupted"
        else -> value
    }

    /** The arguments a reader most needs to recognize a tool call by. */
    private val SUBJECT_KEYS = setOf("filePath", "path", "file", "command", "pattern", "query", "url", "name")
}
