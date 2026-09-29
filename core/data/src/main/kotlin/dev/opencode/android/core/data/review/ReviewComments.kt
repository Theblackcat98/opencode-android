package dev.opencode.android.core.data.review

import dev.opencode.android.core.data.composer.LineRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A review comment in the **web app's** wire format (features doc §6, `metadata.opencodeComment`).
 *
 * The server stores prompt metadata as free-form JSON and never reads this key, so the format
 * exists for the *other* clients: a comment written on the phone is a prompt the desktop has to be
 * able to read. The five field names are therefore the web app's spelling and must not be renamed
 * to suit this client's naming — that is the whole point of using their format.
 *
 * [selection] is the web app's `start-end` string and [preview] is the text of those lines. Both
 * are strings rather than numbers because that is what the desktop writes, and a comment this
 * client reads back has to survive that spelling.
 */
@Serializable
data class OpenCodeComment(
    val path: String,
    val selection: String,
    val comment: String,
    val preview: String? = null,
    val origin: String? = null,
) {
    companion object {
        /** The metadata key the web app reads comments from. */
        const val KEY: String = "opencodeComment"

        /** What a comment written here says it came from. */
        const val ORIGIN: String = "android"
    }
}

/**
 * One review comment as the app holds it while the user is writing it.
 *
 * The difference from [OpenCodeComment] is the line range as **numbers**. The wire format carries
 * the range as a `start-end` string, and a comment row, a selection handle and a
 * `file:…?start=&end=` attachment all need the numbers, so the numbers are the type and the string
 * is what [toMetadata] produces.
 *
 * [path] is the server's own spelling of the file, which is what the metadata must carry: a
 * comment that named a file by its display path would point the desktop at nothing.
 */
data class ReviewComment(
    val path: String,
    val range: LineRange,
    val text: String,
    /** The lines the comment is about, so the prompt can carry them without re-reading the file. */
    val preview: String? = null,
) {
    /**
     * The web app's `selection` spelling.
     *
     * A selection of one line is a bare number, which is the web app's own spelling for "one line"
     * and what a person would write; a range is `start-end`.
     */
    val selection: String
        get() = if (range.end == null || range.end == range.start) "${range.start}" else "${range.start}-${range.end}"

    /** The `file:` URI a comment's line range attaches as, which is how it reaches the agent. */
    fun attachmentUri(location: String?): String =
        dev.opencode.android.core.data.composer.ServerPath.toUri(path, location, range)

    /** The wire form, and the only thing a prompt ever carries. */
    fun toMetadata(): OpenCodeComment = OpenCodeComment(
        path = path,
        selection = selection,
        comment = text,
        preview = preview,
        origin = OpenCodeComment.ORIGIN,
    )
}

/**
 * Turning comments into prompt metadata and back.
 *
 * **A single comment is an object and several are an array.** That is the web app's spelling, so it
 * is the one this client writes and reads; a reader that only understood arrays would lose every
 * comment written on a single line, which is the common case.
 *
 * **The round trip is a test, not a hope.** A comment written here is read back by [read] and must
 * come out with the same path, range, text and preview. That is what makes the format portable
 * rather than merely plausible: the assertion is over this client's own encoding, and the desktop's
 * is the same shape.
 */
object ReviewComments {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The prompt metadata for [comments], under the key the web app reads.
     *
     * Empty for no comments, so a prompt with nothing to say about a review does not carry an empty
     * array that another client would have to interpret.
     */
    fun metadataOf(comments: List<ReviewComment>): Map<String, JsonElement> = when (comments.size) {
        0 -> emptyMap()

        1 -> mapOf(
            OpenCodeComment.KEY to
                json.encodeToJsonElement(OpenCodeComment.serializer(), comments.single().toMetadata()),
        )

        else -> mapOf(
            OpenCodeComment.KEY to JsonArray(
                comments.map { json.encodeToJsonElement(OpenCodeComment.serializer(), it.toMetadata()) },
            ),
        )
    }

    /** The comments a prompt's metadata carries, in either the single or the array spelling. */
    fun read(metadata: Map<String, JsonElement>?): List<ReviewComment> {
        val value = metadata?.get(OpenCodeComment.KEY) ?: return emptyList()
        val objects: List<JsonObject> = when (value) {
            is JsonObject -> listOf(value)
            is JsonArray -> value.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
        return objects.mapNotNull { element ->
            val decoded = runCatching { json.decodeFromJsonElement(OpenCodeComment.serializer(), element) }.getOrNull()
                ?: return@mapNotNull null
            val range = parseSelection(decoded.selection) ?: return@mapNotNull null
            ReviewComment(path = decoded.path, range = range, text = decoded.comment, preview = decoded.preview)
        }
    }

    /**
     * The `start-end` string as a [LineRange].
     *
     * `null` for anything else, including an empty string, a negative number and a backwards range.
     * A comment whose range cannot be read is dropped rather than attached to the whole file: a
     * comment with no range is a different thing from a comment about the file, and the agent would
     * read it as "everything above this line".
     */
    fun parseSelection(selection: String): LineRange? {
        val trimmed = selection.trim()
        if (trimmed.isEmpty()) return null
        val start = trimmed.substringBefore('-').toIntOrNull() ?: return null
        if (start < 1) return null
        if (!trimmed.contains('-')) return LineRange(start)
        val endPart = trimmed.substringAfter('-')
        val end = if (endPart.isBlank()) null else endPart.toIntOrNull() ?: return null
        if (end != null && end < start) return null
        return LineRange(start, end)
    }

    /**
     * The readable text of [comments], which is what the prompt's own `text` says.
     *
     * A comment is metadata, and metadata is not something a model reads unless the text points at
     * it. So the text names the files and the lines and then carries the comments verbatim, and the
     * file attachments carry the ranges. Both halves are needed: the text without the attachments
     * would quote lines the model has not seen, and the attachments without the text would be a
     * file the model has no reason to look at.
     */
    fun readableText(comments: List<ReviewComment>): String = when (comments.size) {
        0 -> ""

        1 -> single(comments.first())

        else -> buildString {
            append("Review comments (")
            append(comments.size)
            append("):\n")
            comments.forEach { comment ->
                append('\n')
                append(single(comment))
            }
        }
    }

    private fun single(comment: ReviewComment): String = buildString {
        append("Comment on ").append(comment.path)
        append('#').append(comment.selection).append(" — ").append(comment.text)
        comment.preview?.takeIf { it.isNotBlank() }?.let {
            append("\n")
            append(it.trimEnd())
        }
    }
}
