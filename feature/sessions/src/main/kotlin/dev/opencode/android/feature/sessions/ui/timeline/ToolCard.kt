package dev.opencode.android.feature.sessions.ui.timeline

import androidx.annotation.StringRes
import dev.opencode.android.core.data.timeline.textOutput
import dev.opencode.android.core.model.AssistantContent
import dev.opencode.android.core.model.Outcome
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.StructuredError
import dev.opencode.android.core.model.ToolState
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.feature.sessions.R

/**
 * How a tool call is labelled.
 *
 * A tool card is the most repeated thing in a transcript, so the label is a function of the tool
 * name rather than a string per card, and an unrecognised name gets a generic card instead of
 * nothing: a server may add tools this client has never heard of, and a transcript with a hole in
 * it is not acceptable.
 */
enum class ToolCardKind(@StringRes val labelRes: Int) {
    READ(R.string.tool_read),
    GLOB(R.string.tool_glob),
    GREP(R.string.tool_grep),
    EDIT(R.string.tool_edit),
    WRITE(R.string.tool_write),
    PATCH(R.string.tool_patch),
    SHELL(R.string.tool_shell),
    WEBFETCH(R.string.tool_webfetch),
    WEBSEARCH(R.string.tool_websearch),
    SKILL(R.string.tool_skill),
    SUBAGENT(R.string.tool_subagent),
    QUESTION(R.string.tool_question),
    EXECUTE(R.string.tool_execute),
    GENERIC(R.string.tool_generic),
    ;

    companion object {
        /** The compact renderer for a tool, or [GENERIC] for one this client does not know. */
        fun of(name: String): ToolCardKind = when (name.lowercase()) {
            "read", "fs.read" -> READ
            "glob", "fs.glob" -> GLOB
            "grep", "fs.grep" -> GREP
            "edit", "fs.edit", "patch_edit" -> EDIT
            "write", "fs.write" -> WRITE
            "patch", "apply_patch" -> PATCH
            "bash", "shell" -> SHELL
            "webfetch", "fetch" -> WEBFETCH
            "websearch" -> WEBSEARCH
            "skill" -> SKILL
            "task", "subagent", "agent" -> SUBAGENT
            "question", "ask" -> QUESTION
            "execute", "run" -> EXECUTE
            else -> GENERIC
        }

        /** True when the client has a dedicated card for the name. */
        fun isKnown(name: String): Boolean = of(name) != GENERIC
    }
}

/** A tool call as the timeline draws it, with everything the card needs already extracted. */
data class ToolCard(
    val id: String,
    val name: String,
    val kind: ToolCardKind,
    val status: ToolStatus,
    /** The one line under the title: a path, a pattern, a command. */
    val subject: String?,
    /** Everything else worth showing: output, the changed files, the answers. */
    val detail: String?,
    val durationMillis: Long?,
    /** True when this client has no dedicated card and the raw input has to be shown. */
    val unknown: Boolean = false,
    /**
     * The files this tool changed, from `metadata.files` (features doc §5, "Tool metadata worth
     * rendering").
     *
     * Kept as paths rather than folded into [detail], because they are links: the transcript shows
     * one row per file and the review viewer opens the file, and a card whose detail is a blob of
     * JSON cannot do either.
     */
    val changedFiles: List<String> = emptyList(),
)

/** Where a tool call is in its lifecycle, as a card shows it. */
sealed interface ToolStatus {
    /** The model is still writing the arguments. */
    data object Streaming : ToolStatus

    /** The tool is executing. */
    data object Running : ToolStatus

    data object Completed : ToolStatus

    data class Failed(val error: StructuredError) : ToolStatus
}

/** Reduces an assistant content part to the card the timeline shows. */
fun AssistantContent.Tool.toCard(): ToolCard {
    val kind = ToolCardKind.of(name)
    val status = when (val current = state) {
        is ToolState.Streaming -> ToolStatus.Streaming
        is ToolState.Running -> ToolStatus.Running
        is ToolState.Completed -> ToolStatus.Completed
        is ToolState.Error -> ToolStatus.Failed(current.error)
        is ToolState.Unknown -> ToolStatus.Completed
    }
    val input: Map<String, String> = when (val current = state) {
        is ToolState.Streaming -> mapOf("input" to current.input)
        is ToolState.Running -> current.input.stringValues()
        is ToolState.Completed -> current.input.stringValues()
        is ToolState.Error -> current.input.stringValues()
        is ToolState.Unknown -> emptyMap()
    }
    val completed: ToolState.Completed? = state as? ToolState.Completed
    val error: ToolState.Error? = state as? ToolState.Error

    return ToolCard(
        id = id,
        name = name,
        kind = kind,
        status = status,
        subject = subjectOf(kind, input),
        detail = detailOf(kind, input, completed, error),
        durationMillis = (time.completed ?: 0L).takeIf { it > 0L }?.let { it - (time.ran ?: time.created) },
        unknown = !ToolCardKind.isKnown(name),
        changedFiles = dev.opencode.android.core.data.review.ChangedFiles
            .fromToolMetadata(completed?.metadata ?: error?.metadata),
    )
}

/** The single most identifying argument of a tool, which is what the card's title line shows. */
private fun subjectOf(kind: ToolCardKind, input: Map<String, String>): String? = when (kind) {
    ToolCardKind.READ, ToolCardKind.EDIT, ToolCardKind.WRITE ->
        input["filePath"] ?: input["path"] ?: input["file"]

    ToolCardKind.GLOB -> input["pattern"]

    ToolCardKind.GREP -> input["pattern"] ?: input["query"]

    ToolCardKind.PATCH -> input["patch"]?.lineSequence()?.firstOrNull()

    ToolCardKind.SHELL, ToolCardKind.EXECUTE -> input["command"]

    ToolCardKind.WEBFETCH -> input["url"]

    ToolCardKind.WEBSEARCH -> input["query"]

    ToolCardKind.SKILL -> input["name"] ?: input["skill"]

    ToolCardKind.SUBAGENT -> input["description"] ?: input["prompt"]

    ToolCardKind.QUESTION -> input["question"]

    ToolCardKind.GENERIC -> input["input"]
}

/** The rest of what a card shows, per tool. */
private fun detailOf(
    kind: ToolCardKind,
    input: Map<String, String>,
    completed: ToolState.Completed?,
    error: ToolState.Error?,
): String? = when (kind) {
    ToolCardKind.SHELL, ToolCardKind.EXECUTE -> completed?.textOutput ?: error?.content?.textOrNull()

    ToolCardKind.WEBFETCH, ToolCardKind.WEBSEARCH -> completed?.textOutput

    ToolCardKind.QUESTION -> input["answer"] ?: completed?.metadata?.stringOrNull("answers")

    ToolCardKind.SUBAGENT -> completed?.metadata?.stringOrNull("sessionID")
        ?: error?.metadata?.stringOrNull("sessionID")

    ToolCardKind.EDIT, ToolCardKind.WRITE, ToolCardKind.PATCH ->
        completed?.metadata?.stringOrNull("files") ?: error?.metadata?.stringOrNull("files")

    ToolCardKind.READ, ToolCardKind.GLOB, ToolCardKind.GREP -> completed?.textOutput

    ToolCardKind.SKILL -> null

    ToolCardKind.GENERIC -> completed?.textOutput ?: error?.error?.message
}

private fun List<dev.opencode.android.core.model.ToolContent>?.textOrNull(): String? = this
    ?.filterIsInstance<dev.opencode.android.core.model.ToolContent.Text>()
    ?.joinToString("\n") { it.text }
    ?.takeIf { it.isNotBlank() }

private fun Map<String, kotlinx.serialization.json.JsonElement>.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe()

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

private fun Map<String, kotlinx.serialization.json.JsonElement>.stringValues(): Map<String, String> =
    mapNotNull { (key, value) ->
        val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return@mapNotNull null
        if (primitive is kotlinx.serialization.json.JsonNull) null else key to primitive.content
    }.toMap()

/** The outcome a turn's idle divider shows. */
@StringRes
fun Outcome.labelRes(): Int = when (this) {
    Outcome.Succeeded -> R.string.divider_turn_succeeded
    Outcome.Failed -> R.string.divider_turn_failed
    Outcome.Interrupted -> R.string.divider_turn_interrupted
    else -> R.string.divider_turn_succeeded
}

/** A short, stable key for a `LazyColumn` row (plan §5.4). */
fun SessionMessage.stableKey(): String = "$id:$created"
