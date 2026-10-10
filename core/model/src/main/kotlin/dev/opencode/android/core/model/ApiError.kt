package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.stringOrNull
import dev.opencode.android.core.model.json.variant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A JSON error response: `{_tag, message, ...}` (features doc §37). Unrecognized tags decode to
 * [Unrecognized], which keeps the raw body.
 */
@Serializable(with = ApiErrorSerializer::class)
sealed interface ApiError {
    val message: String

    /** 401. The password or token is wrong, expired or revoked. */
    @Serializable
    data class Unauthorized(override val message: String) : ApiError

    /** 403. */
    @Serializable
    data class Forbidden(override val message: String) : ApiError

    /** 400. */
    @Serializable
    data class InvalidRequest(
        override val message: String,
        val kind: String? = null,
        val field: String? = null,
    ) : ApiError

    @Serializable
    data class InvalidCursor(override val message: String) : ApiError

    /** 409, for example a reused message ID with a different payload. */
    @Serializable
    data class Conflict(override val message: String, val resource: String? = null) : ApiError

    /** 409. The session is running; for example a revert was requested while busy. */
    @Serializable
    data class SessionBusy(override val message: String, val sessionID: String) : ApiError

    /** 503, for example while the model catalog is still settling. */
    @Serializable
    data class ServiceUnavailable(override val message: String, val service: String? = null) : ApiError

    /** 500. The server's `UnknownError` tag. */
    @Serializable
    data class ServerUnknown(override val message: String, val ref: String? = null) : ApiError

    @Serializable
    data class SessionNotFound(override val message: String, val sessionID: String) : ApiError

    @Serializable
    data class MessageNotFound(override val message: String, val sessionID: String, val messageID: String) : ApiError

    @Serializable
    data class AgentNotFound(override val message: String, val agentID: String) : ApiError

    @Serializable
    data class CommandNotFound(override val message: String, val command: String) : ApiError

    @Serializable
    data class CommandExecution(override val message: String, val command: String) : ApiError

    @Serializable
    data class SkillNotFound(override val message: String, val skill: String) : ApiError

    @Serializable
    data class InstructionEntryValueTooLarge(
        override val message: String,
        val actualBytes: Long,
        val maxBytes: Long,
    ) : ApiError

    @Serializable
    data class FormNotFound(override val message: String, val id: String) : ApiError

    @Serializable
    data class FormInvalidAnswer(override val message: String, val id: String) : ApiError

    @Serializable
    data class FormAlreadySettled(override val message: String, val id: String) : ApiError

    @Serializable
    data class ProviderNotFound(override val message: String, val providerID: String) : ApiError

    @Serializable
    data class IntegrationNotFound(override val message: String, val integrationID: String) : ApiError

    @Serializable
    data class IntegrationAttemptNotFound(
        override val message: String,
        val integrationID: String,
        val attemptID: String,
    ) : ApiError

    @Serializable
    data class IntegrationMethodNotFound(
        override val message: String,
        val integrationID: String,
        val methodID: String,
    ) : ApiError

    @Serializable
    data class McpServerNotFound(override val message: String, val server: String) : ApiError

    @Serializable
    data class ProjectNotFound(override val message: String, val projectID: String) : ApiError

    @Serializable
    data class PermissionNotFound(override val message: String, val requestID: String) : ApiError

    @Serializable
    data class FileNotFound(override val message: String, val path: String) : ApiError

    @Serializable
    data class PtyNotFound(override val message: String, val ptyID: String) : ApiError

    @Serializable
    data class ShellNotFound(override val message: String, val id: String) : ApiError

    @Serializable
    data class Rpc(override val message: String, val type: String, val data: JsonElement? = null) : ApiError

    @Serializable
    data class RpcInternal(override val message: String, val type: String, val data: JsonElement? = null) : ApiError

    /**
     * The worktree routes' refusal, `{"name":"WorktreeError","data":{"message":…,"forceRequired":…}}`.
     *
     * **It has no `_tag`, so it reaches the client through the fallback, not a variant.** Without that path
     * it decoded as [Unrecognized], whose message is read from a top-level `message` the body does not have,
     * and the screen said "Unknown error" for a refusal that carried the server's reason. [forceRequired] is
     * the server's hint that `force` would succeed; the removal path reads the same body through
     * [WorktreeFailure], which is the typed decode the worktree commands use.
     *
     * The variant registration below exists so that a value re-encodes and decodes to itself under the
     * `_tag` every other error uses.
     */
    @Serializable
    data class WorktreeError(
        override val message: String,
        val name: String = WorktreeFailure.WORKTREE_ERROR_NAME,
        val forceRequired: Boolean? = null,
    ) : ApiError

    /** A tag this client does not know, or a body without `_tag`. */
    data class Unrecognized(override val discriminator: String?, override val raw: JsonObject) :
        ApiError,
        UnknownVariant {
        override val message: String get() = raw.stringOrNull("message") ?: discriminator ?: "Unknown error"
    }
}

internal object ApiErrorSerializer : DiscriminatedUnionSerializer<ApiError>(
    serialName = "dev.opencode.android.ApiError",
    discriminator = "_tag",
    unknown = ::unrecognizedApiError,
    variants = listOf(
        variant("UnauthorizedError", ApiError.Unauthorized.serializer()),
        variant("ForbiddenError", ApiError.Forbidden.serializer()),
        variant("InvalidRequestError", ApiError.InvalidRequest.serializer()),
        variant("InvalidCursorError", ApiError.InvalidCursor.serializer()),
        variant("ConflictError", ApiError.Conflict.serializer()),
        variant("SessionBusyError", ApiError.SessionBusy.serializer()),
        variant("ServiceUnavailableError", ApiError.ServiceUnavailable.serializer()),
        variant("UnknownError", ApiError.ServerUnknown.serializer()),
        variant("SessionNotFoundError", ApiError.SessionNotFound.serializer()),
        variant("MessageNotFoundError", ApiError.MessageNotFound.serializer()),
        variant("AgentNotFoundError", ApiError.AgentNotFound.serializer()),
        variant("CommandNotFoundError", ApiError.CommandNotFound.serializer()),
        variant("CommandExecutionError", ApiError.CommandExecution.serializer()),
        variant("SkillNotFoundError", ApiError.SkillNotFound.serializer()),
        variant("InstructionEntryValueTooLargeError", ApiError.InstructionEntryValueTooLarge.serializer()),
        variant("FormNotFoundError", ApiError.FormNotFound.serializer()),
        variant("FormInvalidAnswerError", ApiError.FormInvalidAnswer.serializer()),
        variant("FormAlreadySettledError", ApiError.FormAlreadySettled.serializer()),
        variant("ProviderNotFoundError", ApiError.ProviderNotFound.serializer()),
        variant("IntegrationNotFoundError", ApiError.IntegrationNotFound.serializer()),
        variant("IntegrationAttemptNotFoundError", ApiError.IntegrationAttemptNotFound.serializer()),
        variant("IntegrationMethodNotFoundError", ApiError.IntegrationMethodNotFound.serializer()),
        variant("McpServerNotFoundError", ApiError.McpServerNotFound.serializer()),
        variant("ProjectNotFoundError", ApiError.ProjectNotFound.serializer()),
        variant("PermissionNotFoundError", ApiError.PermissionNotFound.serializer()),
        variant("FileNotFoundError", ApiError.FileNotFound.serializer()),
        variant("PtyNotFoundError", ApiError.PtyNotFound.serializer()),
        variant("ShellNotFoundError", ApiError.ShellNotFound.serializer()),
        variant("RpcError", ApiError.Rpc.serializer()),
        variant("RpcInternalError", ApiError.RpcInternal.serializer()),
        variant("WorktreeError", ApiError.WorktreeError.serializer()),
    ),
)

/**
 * The fallback for a body with no `_tag` this client knows.
 *
 * Only the worktree envelope is given a typed answer here. It is recognised by its `name` and its `data`
 * object, and only when `data.message` is a string; anything else stays [ApiError.Unrecognized] with its raw
 * body, as before.
 */
private fun unrecognizedApiError(discriminator: String?, raw: JsonObject): ApiError {
    val data = raw["data"] as? JsonObject
    val message = data?.stringOrNull("message")
    if (discriminator == null && raw.stringOrNull("name") == WorktreeFailure.WORKTREE_ERROR_NAME && message != null) {
        return ApiError.WorktreeError(
            message = message,
            forceRequired = (data["forceRequired"] as? JsonPrimitive)?.booleanOrNull,
        )
    }
    return ApiError.Unrecognized(discriminator, raw)
}
