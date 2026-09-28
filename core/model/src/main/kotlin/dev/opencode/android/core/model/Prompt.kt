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

/**
 * The mime type and payload of a `data:` URL, or `null` for any other scheme.
 *
 * `data:[<mediatype>][;base64],<data>`: the type ends at the first `;` or `,` and is empty when the
 * URL omits it, which the API allows and which this reports as `""` rather than guessing `text/plain`.
 */
fun dataUrlParts(uri: String): Pair<String, String>? {
    if (!uri.startsWith("data:")) return null
    val comma = uri.indexOf(',')
    if (comma < 0) return null
    val header = uri.substring("data:".length, comma)
    val payload = uri.substring(comma + 1)
    val mime = header.substringBefore(';')
    return mime to payload
}

/**
 * A request-shaped attachment, rendered as the stored shape a user message carries.
 *
 * **For the optimistic item only.** The composer shows a prompt on screen before the server has
 * confirmed it (plan §4.2's one permitted optimism), and the message that is shown has to have the
 * same shape as the one that replaces it — the same chips, in the same place — or the transcript
 * visibly rearranges itself when the event arrives.
 *
 * **The bytes are deliberately not copied.** A five-megabyte picture is already in memory once as the
 * draft the user picked; a second copy inside a pending item, which lives until the event arrives,
 * is the kind of doubling that makes an OutOfMemoryError on a mid-range phone. What the copy keeps is
 * the honest part: the source (`inline` or the `file:` URI) and the name, which is all the chip
 * renders.
 */
fun PromptFileInput.toPreviewAttachment(): PromptFileAttachment {
    val data = dataUrlParts(uri)
    return when {
        data != null -> PromptFileAttachment(
            data = "",
            mime = data.first,
            source = PromptFileSource.Inline,
            name = name,
            mention = mention,
        )

        else -> PromptFileAttachment(
            data = "",
            // A `file:` URI says nothing about the type, and the server's echo is what knows. The
            // chip falls back to the name, which the composer always sets.
            mime = "",
            source = PromptFileSource.Uri(uri.substringBefore('?')),
            name = name,
            mention = mention,
        )
    }
}
