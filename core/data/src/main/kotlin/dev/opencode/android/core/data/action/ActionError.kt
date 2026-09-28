package dev.opencode.android.core.data.action

import dev.opencode.android.core.model.ApiError
import dev.opencode.android.core.model.json.OpenCodeJson
import retrofit2.HttpException
import java.io.IOException

/**
 * The failure classes a driving action can fail with, and what the UI says about each.
 *
 * Plan §4.2 forbids guessing at state, so a failed write changes nothing on screen except an error:
 * the class here exists to let the composer, the request dock and the pickers all explain a failure
 * the same way, and to keep a `409 Conflict` (a reused message id with a different payload) from
 * being reported as a generic failure.
 */
enum class ActionErrorKind {
    /** The credential is wrong, expired or revoked. Re-pairing is the fix (plan §6, Phase 1). */
    UNAUTHORIZED,

    /** The agent or the server refused the action outright. */
    FORBIDDEN,

    /** The session, request or form is gone. Another client may have answered it first. */
    NOT_FOUND,

    /** The action conflicts with the server's state, for example a reused id with a new payload. */
    CONFLICT,

    /** The session is running and cannot accept the action yet. */
    SESSION_BUSY,

    /** The payload was rejected; [ActionError.field] names the offending part when the server says. */
    INVALID_REQUEST,

    /** The server failed, or was unavailable. */
    SERVER,

    /** The phone could not reach the server at all. */
    OFFLINE,

    /** A failure this client does not recognize, which is logged in debug builds. */
    UNKNOWN,
}

/**
 * A failed driving action.
 *
 * [message] is the server's own text when it gave one, because it is more specific than anything
 * the app could invent; [apiError] keeps the decoded error so a caller can branch on the exact tag.
 */
data class ActionError(
    val kind: ActionErrorKind,
    val message: String,
    val apiError: ApiError? = null,
    /** The field an `InvalidRequestError` names, when it names one. */
    val field: String? = null,
) {
    /** True when re-pairing is the fix, which is the one case a retry cannot solve. */
    val needsRepair: Boolean get() = kind == ActionErrorKind.UNAUTHORIZED
}

/**
 * Classifies a thrown call.
 *
 * Retrofit raises [HttpException] for a non-2xx answer, and the body is the same `_tag` union the
 * model decodes everywhere else, so the message the user reads is the server's. A body that does not
 * decode — a proxy's HTML error page, say — still yields the right class from the status code.
 */
fun Throwable.toActionError(): ActionError {
    if (this is HttpException) {
        val decoded = runCatching {
            response()?.errorBody()?.string()?.takeIf { it.isNotBlank() }?.let {
                OpenCodeJson.decodeFromString<ApiError>(it)
            }
        }.getOrNull()
        val kind = decoded?.let(::kindOf) ?: kindOfStatus(code())
        return ActionError(
            kind = kind,
            message = decoded?.message ?: "HTTP ${code()}",
            apiError = decoded,
            field = (decoded as? ApiError.InvalidRequest)?.field,
        )
    }
    if (this is IOException) {
        return ActionError(ActionErrorKind.OFFLINE, message ?: "The server could not be reached")
    }
    return ActionError(ActionErrorKind.UNKNOWN, message ?: this::class.java.simpleName)
}

private fun kindOf(error: ApiError): ActionErrorKind = when (error) {
    is ApiError.Unauthorized -> ActionErrorKind.UNAUTHORIZED
    is ApiError.Forbidden -> ActionErrorKind.FORBIDDEN
    is ApiError.Conflict -> ActionErrorKind.CONFLICT
    is ApiError.SessionBusy -> ActionErrorKind.SESSION_BUSY
    is ApiError.InvalidRequest -> ActionErrorKind.INVALID_REQUEST
    is ApiError.ServiceUnavailable -> ActionErrorKind.SERVER
    is ApiError.SessionNotFound, is ApiError.MessageNotFound, is ApiError.PermissionNotFound,
    is ApiError.FormNotFound, is ApiError.AgentNotFound, is ApiError.FileNotFound,
    -> ActionErrorKind.NOT_FOUND

    else -> ActionErrorKind.SERVER
}

private fun kindOfStatus(code: Int): ActionErrorKind = when (code) {
    401 -> ActionErrorKind.UNAUTHORIZED
    403 -> ActionErrorKind.FORBIDDEN
    404 -> ActionErrorKind.NOT_FOUND
    409 -> ActionErrorKind.CONFLICT
    400, 422 -> ActionErrorKind.INVALID_REQUEST
    in 500..599 -> ActionErrorKind.SERVER
    else -> ActionErrorKind.UNKNOWN
}
