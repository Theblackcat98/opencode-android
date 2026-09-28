package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.ExtendedNumberSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.stringOrNull
import dev.opencode.android.core.model.json.variant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Free-form metadata attached to messages. */
typealias MessageMetadata = Map<String, JsonElement>

/**
 * One entry of a session's projected timeline (schema `Session.Message.Info`, features doc §5),
 * discriminated by `type`. Types this client does not know decode to [Unknown].
 */
@Serializable(with = SessionMessageSerializer::class)
sealed interface SessionMessage {
    val id: String
    val metadata: MessageMetadata?
    val created: Long

    @Serializable
    data class CreatedTime(val created: Long)

    @Serializable
    data class User(
        override val id: String,
        override val metadata: MessageMetadata? = null,
        val time: CreatedTime,
        val text: String,
        val files: List<PromptFileAttachment>? = null,
        val agents: List<PromptAgentAttachment>? = null,
        val skills: List<PromptSkillAttachment>? = null,
    ) : SessionMessage {
        override val created: Long get() = time.created
    }

    /** Text injected by the system, a plugin or the API, such as shell output handed back to the agent. */
    @Serializable
    data class Synthetic(
        override val id: String,
        override val metadata: MessageMetadata? = null,
        val time: CreatedTime,
        val text: String,
        val description: String? = null,
    ) : SessionMessage {
        override val created: Long get() = time.created
    }

    @Serializable
    data class System(
        override val id: String,
        override val metadata: MessageMetadata? = null,
        val time: CreatedTime,
        val text: String,
        val description: String? = null,
    ) : SessionMessage {
        override val created: Long get() = time.created
    }

    /** A loaded skill. */
    @Serializable
    data class Skill(
        override val id: String,
        override val metadata: MessageMetadata? = null,
        val time: CreatedTime,
        val skill: String,
        val name: String,
        val text: String,
    ) : SessionMessage {
        override val created: Long get() = time.created
    }

    /** A user `!` shell command. */
    @Serializable
    data class Shell(
        override val id: String,
        override val metadata: MessageMetadata? = null,
        val time: Time,
        val shellID: String,
        val command: String,
        val status: ShellStatus,
        /** Exit code; may be non-finite (sent as `"Infinity"`, `"-Infinity"` or `"NaN"`). */
        @Serializable(with = ExtendedNumberSerializer::class)
        val exit: Double? = null,
        val output: ShellOutput? = null,
    ) : SessionMessage {
        override val created: Long get() = time.created

        @Serializable
        data class Time(val created: Long, val completed: Long? = null)
    }

    /** One model step: text, reasoning and tool calls. */
    @Serializable
    data class Assistant(
        override val id: String,
        override val metadata: MessageMetadata? = null,
        val time: Time,
        val agent: String,
        val model: ModelRef,
        val content: List<AssistantContent>,
        val snapshot: Snapshot? = null,
        val finish: FinishReason? = null,
        val rawFinish: String? = null,
        val providerState: JsonObject? = null,
        val cost: Double? = null,
        val tokens: TokenUsage? = null,
        val error: StructuredError? = null,
        val retry: Retry? = null,
    ) : SessionMessage {
        override val created: Long get() = time.created

        @Serializable
        data class Time(
            val created: Long,
            val streamed: Long? = null,
            val completed: Long? = null,
        )

        /** Git-based snapshots before and after the step, and the files it changed. */
        @Serializable
        data class Snapshot(
            val start: String? = null,
            val end: String? = null,
            val files: List<String>? = null,
        )

        @Serializable
        data class Retry(
            val attempt: Int,
            val at: Long,
            val error: StructuredError,
        )
    }

    /** A compaction: its `status` is `running`, `completed` or `failed`. */
    @Serializable
    data class Compaction(
        override val id: String,
        override val metadata: MessageMetadata? = null,
        val time: CreatedTime,
        val status: String,
        val reason: CompactionReason,
        val summary: String? = null,
        val recent: String? = null,
        val model: ModelRef? = null,
        val providerState: JsonObject? = null,
        val providerContext: ProviderContext? = null,
        val error: StructuredError? = null,
        val cost: Double? = null,
        val tokens: TokenUsage? = null,
    ) : SessionMessage {
        override val created: Long get() = time.created

        companion object {
            const val RUNNING = "running"
            const val COMPLETED = "completed"
            const val FAILED = "failed"
        }
    }

    /** The end of a turn. */
    @Serializable
    data class Idle(
        override val id: String,
        override val metadata: MessageMetadata? = null,
        val time: CreatedTime,
        val outcome: Outcome,
    ) : SessionMessage {
        override val created: Long get() = time.created
    }

    @Serializable
    data class AgentSwitched(
        override val id: String,
        override val metadata: MessageMetadata? = null,
        val time: CreatedTime,
        val agent: String,
        val previous: String? = null,
    ) : SessionMessage {
        override val created: Long get() = time.created
    }

    @Serializable
    data class ModelSwitched(
        override val id: String,
        override val metadata: MessageMetadata? = null,
        val time: CreatedTime,
        val model: ModelRef,
        val previous: ModelRef? = null,
    ) : SessionMessage {
        override val created: Long get() = time.created
    }

    @Serializable
    data class LocationSwitched(
        override val id: String,
        override val metadata: MessageMetadata? = null,
        val time: CreatedTime,
        val location: LocationPublicRef,
        val projectID: String? = null,
        val subpath: String? = null,
        val previous: Previous? = null,
    ) : SessionMessage {
        override val created: Long get() = time.created

        @Serializable
        data class Previous(
            val location: LocationPublicRef,
            val projectID: String? = null,
            val subpath: String? = null,
        )
    }

    /** A message type this client does not know. Rendered as a generic fallback. */
    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        SessionMessage,
        UnknownVariant {
        override val id: String get() = raw.stringOrNull("id") ?: ""
        override val metadata: MessageMetadata? get() = raw["metadata"] as? JsonObject
        override val created: Long
            get() = ((raw["time"] as? JsonObject)?.get("created") as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toLongOrNull() ?: 0L
    }
}

/** Provenance of a compaction's provider-native context (schema `Session.ProviderContext`). */
@Serializable
data class ProviderContext(
    val version: Int,
    val provenance: Provenance,
    val messages: JsonElement,
) {
    @Serializable
    data class Provenance(
        val providerID: String,
        val provider: String,
        val modelID: String,
        val route: String,
        val protocol: String,
        val endpoint: String,
    )
}

internal object SessionMessageSerializer : DiscriminatedUnionSerializer<SessionMessage>(
    serialName = "dev.opencode.android.SessionMessage",
    unknown = SessionMessage::Unknown,
    variants = listOf(
        variant("user", SessionMessage.User.serializer()),
        variant("synthetic", SessionMessage.Synthetic.serializer()),
        variant("system", SessionMessage.System.serializer()),
        variant("skill", SessionMessage.Skill.serializer()),
        variant("shell", SessionMessage.Shell.serializer()),
        variant("assistant", SessionMessage.Assistant.serializer()),
        variant("compaction", SessionMessage.Compaction.serializer()),
        variant("idle", SessionMessage.Idle.serializer()),
        variant("agent-switched", SessionMessage.AgentSwitched.serializer()),
        variant("model-switched", SessionMessage.ModelSwitched.serializer()),
        variant("location-switched", SessionMessage.LocationSwitched.serializer()),
    ),
)
