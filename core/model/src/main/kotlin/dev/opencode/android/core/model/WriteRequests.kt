package dev.opencode.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `POST /api/session` (schema `Session.create`).
 *
 * Every field is optional: the server picks the location from `location`, the agent from the
 * configured default, and the model from `model.default`. The app always sends the location and the
 * agent because it just asked the user to choose them, and omits the model when the user did not
 * pick one so that the server's default wins.
 */
@Serializable
data class SessionCreateRequest(
    /** A client-generated `ses…` id, which makes a retried create idempotent. */
    val id: String? = null,
    val title: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val location: LocationPublicRef? = null,
    val metadata: SessionMetadata? = null,
    val permissions: List<PermissionRule>? = null,
)

/**
 * `PATCH /api/session/{id}` (schema `Session.update`).
 *
 * A field left out is not changed; a field set to `null` is cleared. A rename therefore sends only
 * the title, and never accidentally erases the metadata.
 */
@Serializable
data class SessionUpdateRequest(
    val title: String? = null,
    val metadata: SessionMetadata? = null,
    val permissions: List<PermissionRule>? = null,
)

/**
 * `POST /api/session/{id}/prompt` (schema `PromptInput`).
 *
 * [id] is a client-generated `msg_…`, which is what makes a retry on a flaky mobile network safe:
 * the server rejects a reused id with a different payload as a conflict, and accepts a repeat of the
 * same one. The composer generates it before the call and reconciles the item it produced with the
 * `session.inbox.enqueued` event the server emits.
 */
@Serializable
data class PromptRequest(
    val id: String? = null,
    val text: String,
    val files: List<PromptFileAttachment>? = null,
    val agents: List<PromptAgentAttachment>? = null,
    val skills: List<PromptSkillAttachment>? = null,
    val metadata: Map<String, JsonElement>? = null,
    /** `steer` (the default) or `queue`; the composer sends it explicitly. */
    val delivery: Delivery? = null,
    /** False admits the input without starting the agent loop. */
    val resume: Boolean? = null,
)

/** `POST /api/session/{id}/agent` (schema `Session.switchAgent`). */
@Serializable
data class SwitchAgentRequest(val agent: String)

/** `POST /api/session/{id}/model` (schema `Session.switchModel`); the variant rides in the ref. */
@Serializable
data class SwitchModelRequest(val model: ModelRef)

/** `PATCH /api/session/{id}/inbox/{inboxID}` (schema `Session.Inbox.update`): queue becomes steer. */
@Serializable
data class InboxUpdateRequest(val delivery: Delivery)

/** `POST /api/session/{id}/interrupt` with `resume`, which returns `{interrupted}`. */
@Serializable
data class InterruptResult(val interrupted: Boolean = false)

/**
 * `POST /api/session/{id}/view`: the idle transition the viewer has now seen (schema `Session.view`).
 *
 * [idle] is the `time.idle` the client observed rather than the current instant, because the server
 * records *which* transition was viewed; a later instant would mark a turn the user never read as
 * read. The client builds it in
 * [dev.opencode.android.core.data.attention.SessionViewMarker], which is what decides there is
 * anything to mark at all.
 */
@Serializable
data class SessionViewRequest(val idle: Long)
