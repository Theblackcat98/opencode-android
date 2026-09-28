package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.variant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A range of the prompt text tied to an attachment, such as `@src/a.ts` (schema `Prompt.Mention`). */
@Serializable
data class PromptMention(
    val start: Int,
    val end: Int,
    val text: String,
)

/** A file attached to a user message, as stored (schema `Prompt.FileAttachment`). */
@Serializable
data class PromptFileAttachment(
    /** Base64 content. */
    val data: String,
    val mime: String,
    val source: PromptFileSource,
    val name: String? = null,
    val description: String? = null,
    val mention: PromptMention? = null,
)

/** Where an attachment came from (schema `Prompt.FileSource`). */
@Serializable(with = PromptFileSourceSerializer::class)
sealed interface PromptFileSource {
    /** Sent inline as a `data:` URL, for example a photo from the phone. */
    @Serializable
    data object Inline : PromptFileSource

    /** A `file:` URL on the server. */
    @Serializable
    data class Uri(val uri: String) : PromptFileSource

    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        PromptFileSource,
        UnknownVariant
}

internal object PromptFileSourceSerializer : DiscriminatedUnionSerializer<PromptFileSource>(
    serialName = "dev.opencode.android.PromptFileSource",
    unknown = PromptFileSource::Unknown,
    variants = listOf(
        variant("inline", PromptFileSource.Inline.serializer()),
        variant("uri", PromptFileSource.Uri.serializer()),
    ),
)

/** An agent mentioned in a prompt (schema `Prompt.AgentAttachment`). */
@Serializable
data class PromptAgentAttachment(
    val name: String,
    val mention: PromptMention? = null,
)

/** A skill attached to a prompt (schema `Prompt.SkillAttachment`). */
@Serializable
data class PromptSkillAttachment(
    val id: String,
    val name: String,
    val text: String? = null,
    val mention: PromptMention? = null,
)

/** Payload of a user inbox item and of a user message. */
@Serializable
data class UserPromptPayload(
    val text: String,
    val files: List<PromptFileAttachment>? = null,
    val agents: List<PromptAgentAttachment>? = null,
    val skills: List<PromptSkillAttachment>? = null,
    val metadata: Map<String, JsonElement>? = null,
)
