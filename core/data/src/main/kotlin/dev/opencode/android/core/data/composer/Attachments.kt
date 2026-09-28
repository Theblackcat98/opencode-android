package dev.opencode.android.core.data.composer

import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.PromptMention

/**
 * What an attachment is, for the chip that shows it and the rules that decide whether it may be
 * sent.
 *
 * The classification is the server's, not the client's: what a model can read is a property of the
 * format (features doc §6, "Model-visible formats"), and the phone is only the thing that has to
 * know it.
 */
enum class AttachmentKind {
    /** PNG, JPEG, GIF or WebP: the model sees the picture. */
    IMAGE,

    /** UTF-8 text, and formats that are text to a model: Markdown, JSON, source, and SVG. */
    TEXT,

    /** A format the model is not sent: PDF, audio, video, and anything else binary. */
    BINARY,

    /** A directory on the server, which the model receives as a non-recursive listing. */
    DIRECTORY,
}

/**
 * One attachment the composer will send, before it has been sent.
 *
 * **The URI is built when the attachment is created, not when it is sent**, so the chip the user
 * removes and the request the server receives are the same value; a chip cannot claim a path the
 * request does not carry. [id] is stable for the life of the draft and is what a list keys on,
 * because two photos from the same picker can share a name.
 *
 * [sizeBytes] is the **decoded** size, which is what the 20 MiB per-item limit counts (features doc
 * §20), and is `0` for a file the server already holds, where the client does not know the size and
 * must not pretend to.
 */
data class AttachmentDraft(
    val id: String,
    val label: String,
    val uri: String,
    val kind: AttachmentKind,
    val sizeBytes: Long = 0,
    val mime: String? = null,
    val mention: PromptMention? = null,
    val range: LineRange? = null,
) {
    /** The text that says what will be attached, for a chip's description and for TalkBack. */
    fun describe(formatBytes: (Long) -> String): String {
        val size = if (sizeBytes > 0) formatBytes(sizeBytes) else null
        val rangeText = range?.toSuffix()
        return listOfNotNull(label, rangeText, size).joinToString(" · ")
    }
}

/** Why an attachment cannot be sent, or can only be sent after the user says so. */
enum class AttachmentProblem {
    /** Over the 20 MiB per-item limit. */
    TOO_LARGE,

    /** A format the model is not sent at all, whatever the model is. */
    NOT_MODEL_READABLE,

    /** An `http(s)` URI, which the API does not accept (features doc §6). */
    UNSUPPORTED_SCHEME,

    /** The selected model declares no image input. */
    MODEL_TAKES_NO_IMAGES,

    /** The URI is not one the API can read. */
    MALFORMED_URI,
}

/**
 * The verdict on one attachment.
 *
 * [AttachmentVerdict.NeedsConfirmation] exists because plan §5.2 asks for explicit confirmation
 * before a meaningful action, and "this model cannot see this image" is one: the server is the
 * authority on what happens next, and the phone's only honest contribution is to make sure the user
 * chose it rather than discovering it afterwards.
 */
sealed interface AttachmentVerdict {
    data object Ok : AttachmentVerdict

    data class Blocked(val problem: AttachmentProblem, val detail: String? = null) : AttachmentVerdict

    data class NeedsConfirmation(val problem: AttachmentProblem) : AttachmentVerdict
}

/**
 * Whether an attachment may be sent, and whether the user has to agree to it first.
 *
 * The rules are the server's, read from features doc §6 and §20, and they are applied here rather
 * than after a rejection because each of them costs the user a round trip and, for the model-capability
 * one, a turn.
 *
 * **A format the model is not sent is blocked, not warned about.** There is no model for which a PDF
 * becomes visible, so confirming it would only be confirming a silent drop. **A model that declares
 * no image input is a confirmation**, because the attachment is legitimate and the model may still
 * handle it; the phone says what it knows and lets the user choose.
 */
object AttachmentPolicy {

    /** The per-item limit, decoded (features doc §20, "20 MiB decoded per item"). */
    const val MAX_DECODED_BYTES: Long = 20L * 1024 * 1024

    /** The formats a model is sent as pictures. */
    val IMAGE_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    /** SVG is a picture to a browser and text to a model, and the model is what matters here. */
    const val SVG_TYPE: String = "image/svg+xml"

    /**
     * The verdict for [attachment] with [model] selected.
     *
     * A [model] of `null` means the client does not know which model the session will use, either
     * because the catalog has not loaded or because the session names one the catalog does not
     * list. Nothing is blocked on a guess in that case, because blocking a valid attachment on the
     * strength of missing information is the worse failure; only formats the model is never sent are
     * blocked regardless.
     */
    fun verify(attachment: AttachmentDraft, model: ModelInfo?): AttachmentVerdict {
        if (attachment.sizeBytes > MAX_DECODED_BYTES) {
            return AttachmentVerdict.Blocked(AttachmentProblem.TOO_LARGE, "over the 20 MiB limit")
        }
        when (uriScheme(attachment.uri)) {
            "http", "https" -> return AttachmentVerdict.Blocked(AttachmentProblem.UNSUPPORTED_SCHEME)
            "data", "file" -> Unit
            else -> return AttachmentVerdict.Blocked(AttachmentProblem.MALFORMED_URI)
        }
        val kind = attachment.kind
        if (kind == AttachmentKind.DIRECTORY) return AttachmentVerdict.Ok
        if (kind == AttachmentKind.BINARY) {
            return AttachmentVerdict.Blocked(AttachmentProblem.NOT_MODEL_READABLE, attachment.mime)
        }
        if (kind == AttachmentKind.IMAGE && model != null && !takesImages(model)) {
            return AttachmentVerdict.NeedsConfirmation(AttachmentProblem.MODEL_TAKES_NO_IMAGES)
        }
        return AttachmentVerdict.Ok
    }

    /** The verdict for a whole prompt: the first blocking problem, or every confirmation needed. */
    fun verify(
        attachments: List<AttachmentDraft>,
        model: ModelInfo?,
    ): List<AttachmentVerdict> = attachments.map { verify(it, model) }

    /** Whether [model] declares image input, from the catalog badges the picker already shows. */
    fun takesImages(model: ModelInfo): Boolean =
        model.capabilities?.input?.contains("image") == true

    /** Classifies a mime type into what the model would receive. */
    fun classify(mime: String?): AttachmentKind {
        val type = mime?.substringBefore(';')?.trim()?.lowercase() ?: return AttachmentKind.BINARY
        if (type in IMAGE_TYPES) return AttachmentKind.IMAGE
        if (type == SVG_TYPE) return AttachmentKind.TEXT
        if (type.startsWith("text/")) return AttachmentKind.TEXT
        // Formats a model reads as text even though their type does not say so.
        if (type in TEXTUAL_TYPES) return AttachmentKind.TEXT
        return AttachmentKind.BINARY
    }

    private fun uriScheme(uri: String): String? {
        val colon = uri.indexOf(':')
        if (colon <= 0) return null
        val scheme = uri.substring(0, colon)
        return scheme.takeIf { it.all { c -> c.isLetterOrDigit() || c == '+' || c == '-' || c == '.' } }
            ?.lowercase()
    }

    /** Non-`text/` types that are still source or configuration a model reads as text. */
    private val TEXTUAL_TYPES: Set<String> = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-sh",
        "application/x-yaml",
        "application/yaml",
        "application/toml",
        "application/x-httpd-php",
        "application/sql",
    )
}
