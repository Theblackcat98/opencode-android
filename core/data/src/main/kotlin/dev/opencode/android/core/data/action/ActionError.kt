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
    /**
     * The HTTP status the server answered with, when there was one.
     *
     * **Kept because [kind] cannot express every status that matters.** Capability detection reads a
     * `404` *and* a `405` as "this route is not available" (plan §4.2), and a `405` arrives with an
     * empty body and no `_tag`, so there is nothing in [kind] to tell it apart from a `500`. Before
     * this field the answer came out as `SERVER` and the feature stayed switched on against a server
     * that has the route but refuses the method — which is exactly the state the probe is supposed
     * to detect. `null` for a failure with no HTTP answer at all, such as a dropped connection.
     */
    val httpStatus: Int? = null,
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
        // A body this build does not recognise still has a status, and the status is the more reliable of
        // the two. `ApiError.Unrecognized` is the catch-all for a body with no `_tag` this client knows,
        // and routing it to [kindOf]'s `else` branch made every such answer look like a `500`: a `404`
        // from a server that omits or renames the error tag became `SERVER`, which is not a failure —
        // it is what capability detection reads as "the route is missing". So an unrecognised body falls
        // through to the status, and only a *recognised* body is allowed to override it.
        val kind = when {
            decoded == null -> kindOfStatus(code())
            decoded is ApiError.Unrecognized -> kindOfStatus(code())
            else -> kindOf(decoded)
        }
        return ActionError(
            kind = kind,
            message = decoded?.message ?: "HTTP ${code()}",
            apiError = decoded,
            field = (decoded as? ApiError.InvalidRequest)?.field,
            httpStatus = code(),
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
    // A form the server has already answered or cancelled is a conflict, not a server fault: it is
    // the ordinary result of answering from two clients, and the UI says "already answered".
    is ApiError.FormAlreadySettled -> ActionErrorKind.CONFLICT
    is ApiError.FormInvalidAnswer -> ActionErrorKind.INVALID_REQUEST
    is ApiError.InstructionEntryValueTooLarge -> ActionErrorKind.INVALID_REQUEST
    is ApiError.ServiceUnavailable -> ActionErrorKind.SERVER
    // Every "the thing you named is not here" tag is [ActionErrorKind.NOT_FOUND], not a server fault.
    //
    // **These were the ones capability detection depends on.** Phase 8 probes
    // `experimental.mcp.*` and `experimental.integration.wellknown`, and the only evidence it has
    // that a route is missing is a `404` — so a `McpServerNotFound` folded into [ActionErrorKind.SERVER]
    // would leave the feature switched *on* against a server that has never heard of the route, and
    // the user would get a failure on every tap with no way to tell the feature apart. The Phase 8
    // tags are listed explicitly rather than being caught by a rule, because a rule that classified
    // every tag ending in `NotFound` would also catch a *resource* that is gone (a deleted session,
    // a removed credential) and report it as a missing route.
    is ApiError.SessionNotFound, is ApiError.MessageNotFound, is ApiError.PermissionNotFound,
    is ApiError.FormNotFound, is ApiError.AgentNotFound, is ApiError.FileNotFound,
    is ApiError.ProviderNotFound, is ApiError.IntegrationNotFound,
    is ApiError.IntegrationAttemptNotFound, is ApiError.IntegrationMethodNotFound,
    is ApiError.McpServerNotFound,
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

/**
 * A write that failed, carrying the classification every screen reports.
 *
 * **One class for the whole data layer, and that is the point.** Phase 6, Phase 7 and Phase 8 each
 * declared their own `ActionFailure` in their own package, which meant a failure raised by one
 * subsystem and reported by another lost its classification: an `fs.write` rejection came back as
 * [ActionErrorKind.UNKNOWN] because the caller's `as? ActionFailure` did not match, and the screen said
 * "the server refused that (unknown)" for what was in fact a `400` naming a path outside the location.
 * The three old names are now typealiases onto this one, so every existing `is`, `as` and import keeps
 * working and no two callers can disagree about which class a failure is.
 *
 * **The message is the server's, and `null` is suppressed for credentials.** [ActionError.message] is
 * the server's own text, which is more specific than anything the app could invent; the exception
 * carries no stack trace, because these are expected outcomes of a write and a stack of `okhttp`
 * frames in a log line says nothing a user can act on.
 */
class ActionFailure(val error: ActionError) : Exception(error.message, null, false, false)
