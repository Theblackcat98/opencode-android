package dev.opencode.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A permission request awaiting an answer (schema `Permission.Request`, features doc §14).
 *
 * The same object is what `permission.asked` carries and what `GET /api/permission/request` and
 * `GET /api/session/{id}/permission` return, so the request center holds one type and the events
 * and the REST lists agree by construction.
 */
@Serializable
data class PermissionRequest(
    val id: String,
    val sessionID: String,
    /** What is being asked for, which is the tool's name for a tool request. */
    val action: String,
    val resources: List<String> = emptyList(),
    /**
     * The patterns an `always` reply would store (features doc §14).
     *
     * The app shows these before the user commits, because "allow always" is a standing change to
     * the server's rules and plan §5.2 requires the user to see what is being stored.
     */
    val save: List<String>? = null,
    val metadata: JsonObject? = null,
    val source: PermissionSource? = null,
    val message: String? = null,
) {
    /** The patterns an `always` reply stores, or an empty list when the request names none. */
    val savedPatterns: List<String> get() = save.orEmpty()

    /**
     * Whether a tool call raised this request.
     *
     * A tool's request names the tool part that is waiting ([source]). One created through
     * `POST /api/session/{id}/permission` by a plugin, a workflow or another client has no source, and it
     * gates the agent exactly as a tool's does, so a screen that shows the request has to say who is asking:
     * "Allow external_directory?" reads the same either way and the answer is not the same decision.
     */
    val raisedByToolCall: Boolean get() = source != null

    /**
     * The title whoever raised the request gave it (`metadata.title`), or `null` when there is none.
     *
     * `metadata` is free-form (the schema says only `object`), so a title that is not a string, or is blank,
     * is treated as no title instead of being drawn as its JSON.
     */
    val title: String?
        get() = (metadata?.get("title") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}

/**
 * Where a permission request came from: the tool part that is waiting on it.
 *
 * `messageID` and `id` are what link a request to its tool card in the timeline, so the dock can
 * show what is being asked for next to what asked for it.
 */
@Serializable
data class PermissionSource(
    val type: String,
    val messageID: String,
    /** The tool part id, matching `AssistantContent.Tool.id`. */
    val id: String,
) {
    companion object {
        const val TOOL = "tool"
    }
}

/** `POST …/permission/{requestID}/reply`: the decision and optional feedback to the agent. */
@Serializable
data class PermissionReplyPayload(
    val decision: PermissionReply,
    /** Feedback handed to the agent with a rejection. */
    val message: String? = null,
)
