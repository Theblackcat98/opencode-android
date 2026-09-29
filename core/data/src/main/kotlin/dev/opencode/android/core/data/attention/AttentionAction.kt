package dev.opencode.android.core.data.attention

import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * One thing a notification's user can do, and the whole of what the shade may do.
 *
 * **An action is data, not a call.** The ongoing notification, the permission notification and the
 * `RemoteInput` answer all become one of these values, which is then encoded into an `Intent`'s
 * extras and decoded back there. Keeping the union free of Android types is what lets a test
 * enumerate every action, round-trip it and check its request code without a device.
 *
 * **Answers carry the form, not just the text.** A `RemoteInput` answer has to go through
 * [dev.opencode.android.core.data.forms.FormEngine] like any other answer, and validating needs the
 * field list, so the action carries the encoded fields. A hand-rolled "post the text" would be the
 * one answer that skips validation, which is exactly how a required question ends up answered blank.
 *
 * **Serialisable, because `Intent` extras are a string.** A notification action travels from the
 * process that posted it through the system and back, and a flat string is the only thing that
 * survives that trip unchanged. The serial names are short because the encoded form ends up in an
 * `Intent` that a bug report can be read out of.
 */
@Serializable
sealed interface AttentionAction {

    /** Which server the action is about; a notification may outlive a server switch. */
    val serverId: String

    /** The session the action belongs to, for the reply to find its route. */
    val sessionId: String

    /** Answers a permission request once, always, or with a rejection. */
    @Serializable
    @SerialName("reply")
    data class ReplyPermission(
        override val serverId: String,
        override val sessionId: String,
        val requestId: String,
        val decision: PermissionReply,
    ) : AttentionAction

    /**
     * Answers a form from the shade.
     *
     * [fields] is the form's field list, encoded by [encodeFields]; [fieldKey] is the field the
     * inline text answers, which is only ever set when there is exactly one answerable field.
     */
    @Serializable
    @SerialName("answer")
    data class AnswerForm(
        override val serverId: String,
        override val sessionId: String,
        val formId: String,
        val fieldKey: String?,
        val fields: List<EncodedFormField> = emptyList(),
    ) : AttentionAction

    /** Dismisses a form, which cancels it. */
    @Serializable
    @SerialName("cancel")
    data class CancelForm(
        override val serverId: String,
        override val sessionId: String,
        val formId: String,
    ) : AttentionAction

    /** Opens a session, from the notification body rather than from a button. */
    @Serializable
    @SerialName("open")
    data class OpenSession(
        override val serverId: String,
        override val sessionId: String,
    ) : AttentionAction

    /**
     * Opens a location's shell panel, which is what a finished command leads to.
     *
     * A command belongs to a checkout, not to a conversation, so there is no session to open — and
     * [sessionId] is the empty string rather than `null` because the interface declares it
     * non-nullable for the permission and form actions that have to name one. The graph treats an
     * empty session id as "no session", which is the same thing said once.
     */
    @Serializable
    @SerialName("openLocation")
    data class OpenLocation(
        override val serverId: String,
        val directory: String,
    ) : AttentionAction {
        override val sessionId: String = ""
    }

    /** `session.interrupt` from the ongoing notification. */
    @Serializable
    @SerialName("interrupt")
    data class Interrupt(
        override val serverId: String,
        override val sessionId: String,
    ) : AttentionAction
}

/** One field of a form, flattened so it can ride in a notification's intent extras. */
@Serializable
data class EncodedFormField(
    val key: String,
    /** The declared `type`: `string`, `number`, `integer`, `boolean`, `multiselect`, `external`. */
    val type: String,
    val title: String? = null,
    val required: Boolean = false,
    val hidden: Boolean = false,
    val options: List<String> = emptyList(),
    val custom: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val default: JsonElement? = null,
)

/**
 * What the shade may type into.
 *
 * **Only a form that is one free-text question can be answered from the notification.** Anything
 * with a second field, an option list, a number or a validation rule has to be opened: a shade input
 * cannot show which field failed, and an answer that fails validation server-side is a round trip the
 * user did not ask for. The decision is a pure function of the fields, so it is a test rather than a
 * hope.
 */
@Serializable
data class RemoteInputSpec(
    val label: String,
    val key: String,
) {
    companion object {
        /**
         * The single field a form can be answered from the notification with, or `null`.
         *
         * Hidden fields do not count: a form whose only other field is hidden is still one question,
         * because the hidden one can never be answered anyway and the reply omits it.
         *
         * A field with a positive `minLength` is excluded as well. A `RemoteInput` can be answered
         * with one character, and a rule that one character breaks would produce a notification the
         * user can complete and still fail; such a form is better opened.
         */
        fun singleFreeTextField(fields: List<EncodedFormField>): EncodedFormField? {
            val answerable = fields.filterNot { it.hidden }
            if (answerable.size != 1) return null
            val field = answerable.single()
            if (field.type != TYPE_STRING) return null
            if (field.options.isNotEmpty() && !field.custom) return null
            if (field.required && (field.minLength ?: 0) > 0) return null
            return field
        }
    }
}

/**
 * Reading an action back.
 *
 * **A payload this build cannot read is `null`, not an exception.** It is a notification left by an
 * older build whose action this build no longer knows, and throwing would take down the receiver
 * process for a button nobody pressed.
 */
object AttentionActionCodec {
    fun encode(action: AttentionAction): String =
        OpenCodeJson.encodeToString(AttentionAction.serializer(), action)

    fun decode(encoded: String?): AttentionAction? {
        if (encoded.isNullOrBlank()) return null
        return runCatching { OpenCodeJson.decodeFromString(AttentionAction.serializer(), encoded) }.getOrNull()
    }
}

/**
 * The request code of a notification action.
 *
 * **A `PendingIntent`'s identity is its request code, so two actions that share one code share the
 * same pending intent and only the last one ever fires.** That failure is invisible — the button
 * works, it just does the wrong thing — so uniqueness is guaranteed by construction here rather
 * than hoped for from a hash: each distinct action gets a fresh counter value and the assignment is
 * remembered, so the same action always gets the same code and two different actions can never share
 * one.
 *
 * A counter rather than a digest is safe because a request code only has to outlive the process that
 * created it. A `PendingIntent` is re-created whenever its notification is re-posted, and a
 * notification is re-posted whenever the coordinator runs, so there is never a pending intent that
 * outlives the map.
 *
 * The map is unbounded by design: it only grows with the distinct actions the user could click, which
 * is bounded by the number of requests in a session.
 */
class AttentionActionCodes {
    private val codes = HashMap<String, Int>()
    private var next = 1

    /** The request code for [action], stable across calls and unique across distinct actions. */
    @Synchronized
    fun codeFor(action: AttentionAction): Int = codes.getOrPut(action.key()) { next++ }

    /** The key two actions are compared by: what makes them the same click. */
    private fun AttentionAction.key(): String = when (this) {
        is AttentionAction.ReplyPermission -> "reply:$serverId:$sessionId:$requestId:$decision"
        is AttentionAction.AnswerForm -> "answer:$serverId:$sessionId:$formId:${fieldKey.orEmpty()}"
        is AttentionAction.CancelForm -> "cancel:$serverId:$sessionId:$formId"
        is AttentionAction.OpenSession -> "open:$serverId:$sessionId"
        is AttentionAction.OpenLocation -> "openLocation:$serverId:$directory"
        is AttentionAction.Interrupt -> "interrupt:$serverId:$sessionId"
    }
}

/** A `RemoteInput` text as an answer, or `null` when there was nothing to answer with. */
fun remoteInputAnswer(key: String, text: String?): JsonElement? {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return JsonPrimitive(trimmed)
}
