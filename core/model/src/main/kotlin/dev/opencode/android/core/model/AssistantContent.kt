package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.variant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A part of an assistant message: text, reasoning or a tool call. */
@Serializable(with = AssistantContentSerializer::class)
sealed interface AssistantContent {
    @Serializable
    data class Text(
        val text: String,
        val state: JsonObject? = null,
    ) : AssistantContent

    @Serializable
    data class Reasoning(
        val text: String,
        val state: JsonObject? = null,
        val time: Time? = null,
    ) : AssistantContent {
        @Serializable
        data class Time(val created: Long, val completed: Long? = null)
    }

    /** A tool call (features doc §5, "Tool parts"). */
    @Serializable
    data class Tool(
        val id: String,
        val name: String,
        val executed: Boolean? = null,
        val providerState: JsonObject? = null,
        val providerResultState: JsonObject? = null,
        val state: ToolState,
        val time: Time,
    ) : AssistantContent {
        @Serializable
        data class Time(
            val created: Long,
            val ran: Long? = null,
            val completed: Long? = null,
        )
    }

    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        AssistantContent,
        UnknownVariant
}

internal object AssistantContentSerializer : DiscriminatedUnionSerializer<AssistantContent>(
    serialName = "dev.opencode.android.AssistantContent",
    unknown = AssistantContent::Unknown,
    variants = listOf(
        variant("text", AssistantContent.Text.serializer()),
        variant("reasoning", AssistantContent.Reasoning.serializer()),
        variant("tool", AssistantContent.Tool.serializer()),
    ),
)

/** Lifecycle of a tool call, discriminated by `status`. */
@Serializable(with = ToolStateSerializer::class)
sealed interface ToolState {
    /** The model is still writing the arguments; [input] is the partial JSON text. */
    @Serializable
    data class Streaming(val input: String) : ToolState

    @Serializable
    data class Running(
        val input: Map<String, JsonElement>,
        val metadata: Map<String, JsonElement>,
    ) : ToolState

    @Serializable
    data class Completed(
        val input: Map<String, JsonElement>,
        val content: List<ToolContent>,
        val metadata: Map<String, JsonElement>? = null,
    ) : ToolState

    @Serializable
    data class Error(
        val input: Map<String, JsonElement>,
        val error: StructuredError,
        val content: List<ToolContent>? = null,
        val metadata: Map<String, JsonElement>? = null,
    ) : ToolState

    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        ToolState,
        UnknownVariant
}

internal object ToolStateSerializer : DiscriminatedUnionSerializer<ToolState>(
    serialName = "dev.opencode.android.ToolState",
    discriminator = "status",
    unknown = ToolState::Unknown,
    variants = listOf(
        variant("streaming", ToolState.Streaming.serializer()),
        variant("running", ToolState.Running.serializer()),
        variant("completed", ToolState.Completed.serializer()),
        variant("error", ToolState.Error.serializer()),
    ),
)

/** Output of a tool: text, or a file (schema `Tool.Content`). */
@Serializable(with = ToolContentSerializer::class)
sealed interface ToolContent {
    @Serializable
    data class Text(val text: String) : ToolContent

    @Serializable
    data class File(
        val uri: String,
        val mime: String,
        val name: String? = null,
    ) : ToolContent

    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        ToolContent,
        UnknownVariant
}

internal object ToolContentSerializer : DiscriminatedUnionSerializer<ToolContent>(
    serialName = "dev.opencode.android.ToolContent",
    unknown = ToolContent::Unknown,
    variants = listOf(
        variant("text", ToolContent.Text.serializer()),
        variant("file", ToolContent.File.serializer()),
    ),
)
