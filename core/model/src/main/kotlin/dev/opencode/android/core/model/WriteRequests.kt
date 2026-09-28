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
 * A file, directory or inline blob attached to a prompt **as the API accepts it**
 * (schema `PromptInput.FileAttachment`).
 *
 * This is a different shape from [PromptFileAttachment], which is how a *stored* user message
 * carries its files. The request names a [uri] — an absolute `file:` URL for something on the
 * server, a `data:` URL for content from the phone — while the stored message carries the decoded
 * base64, the mime type and where it came from. Sending the stored shape would be a payload the
 * server cannot read, which is why the two are separate types rather than one with nullable fields.
 *
 * [uri] restrictions (features doc §6): absolute `file:` for the server's own filesystem, `data:`
 * for inline content, line ranges as `?start=&end=`, `http(s)` **not** supported, 20 MiB decoded per
 * item.
 */
@Serializable
data class PromptFileInput(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
    val mention: PromptMention? = null,
)

/** A skill attached to a prompt **as the API accepts it** (schema `PromptInput.SkillAttachment`). */
@Serializable
data class PromptSkillInput(
    val id: String,
    val mention: PromptMention? = null,
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
    val files: List<PromptFileInput>? = null,
    val agents: List<PromptAgentAttachment>? = null,
    val skills: List<PromptSkillInput>? = null,
    val metadata: Map<String, JsonElement>? = null,
    /** `steer` (the default) or `queue`; the composer sends it explicitly. */
    val delivery: Delivery? = null,
    /** False admits the input without starting the agent loop. */
    val resume: Boolean? = null,
)

/**
 * `POST /api/session/{id}/command` (schema `Command.execute`).
 *
 * The same attachment shape as a prompt, because a command is a prompt template: [text] is the
 * `$ARGUMENTS` the template interpolates, and files, agents and skills ride along exactly as they do
 * on [PromptRequest]. A command has no `id` of its own and no `resume`, and it answers `204` — the
 * echo is the inbox event the server emits.
 */
@Serializable
data class SessionCommandRequest(
    val name: String,
    val text: String,
    val files: List<PromptFileInput>? = null,
    val agents: List<PromptAgentAttachment>? = null,
    val skills: List<PromptSkillInput>? = null,
    val metadata: Map<String, JsonElement>? = null,
    val delivery: Delivery? = null,
)

/**
 * `POST /api/session/{id}/shell` (schema `Session.shell`): the composer's `!command` mode.
 *
 * [id] is a client-generated `msg_…` used the same way a prompt's is, so a retry does not run the
 * command twice. Unlike a prompt it has no delivery mode: the server runs the command and puts the
 * output in the timeline as a shell message (features doc §6, `!` shell mode).
 */
@Serializable
data class SessionShellRequest(
    val id: String? = null,
    val command: String,
)

/** `POST /api/session/{id}/compact` (schema `Session.compact`), the `/compact` client command. */
@Serializable
data class SessionCompactRequest(
    val id: String? = null,
    val delivery: Delivery? = null,
)

/** `POST /api/session/{id}/generate` (schema `Session.generate`): the `/btw` side question. */
@Serializable
data class SessionGenerateRequest(val prompt: String)

/** `PUT /api/session/{id}/environment` (schema `Session.environment`). */
@Serializable
data class SessionEnvironmentRequest(val variables: Map<String, String>)

/** `POST /api/experimental/session/{id}/skill` (schema `Skill.activate`). */
@Serializable
data class SkillActivationRequest(
    val id: String,
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
