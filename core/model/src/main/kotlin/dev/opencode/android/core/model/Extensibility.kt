package dev.opencode.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `POST /api/experimental/generate`: one stateless completion, with no session and no history
 * (features doc §8.1). This is the API behind the quick-ask widget.
 *
 * The answer is a bare `{data: {text}}`, the same wrapper the session's `/btw` route uses but a
 * different route, so the two are separate types here rather than one that claims both.
 */
@Serializable
data class GenerateTextRequest(
    val prompt: String,
    /** Omitted to let the server choose, which is not the same as the configured default. */
    val model: ModelRef? = null,
)

/** The text stateless generation produced. */
@Serializable
data class GenerateTextResult(val text: String)

/**
 * `POST /api/session/{id}/synthetic`: text the system, a plugin or the API injects into a session
 * (features doc §4.2).
 *
 * **Synthetic input is not a user prompt.** It lands in the inbox as `synthetic` rather than
 * `user`, which is what lets the agent and the transcript tell "the user typed this" from "a
 * plugin said this". A UI that offers this route has to say which one it is doing.
 */
@Serializable
data class SyntheticInputRequest(
    /** A client-generated `msg_` id, which makes a retry on a flaky network idempotent. */
    val id: String? = null,
    val text: String,
    val description: String? = null,
    val metadata: JsonObject? = null,
    val delivery: Delivery? = null,
    /** Whether a parked session resumes. */
    val resume: Boolean? = null,
)

/** The inbox item `session.synthetic` created. */
@Serializable
data class SyntheticInputResult(
    val id: String,
    val sessionID: String,
    val time: Time,
    val payload: SyntheticPayload,
    val delivery: Delivery,
) {
    @Serializable
    data class Time(val created: Long)

    @Serializable
    data class SyntheticPayload(
        val text: String,
        val description: String? = null,
        val metadata: JsonObject? = null,
    )
}

/**
 * `POST /api/session/{id}/permission`: raise a permission request from a client (features doc §14).
 *
 * It exists so a plugin or a workflow test can exercise the approval path without a tool actually
 * asking for something. **A request created here gates the agent exactly as a tool-created one
 * does**, so a UI that offers it has to say that, and it is behind a confirmation for the same
 * reason "auto-approve" is (plan §5.2).
 */
@Serializable
data class CreatePermissionRequest(
    /** A client-generated `per_` id, so a retry does not raise a second request. */
    val id: String? = null,
    val action: String,
    val resources: List<String>,
    /** The patterns an "allow always" answer would store, which the UI shows before it is sent. */
    val save: List<String> = emptyList(),
    val metadata: JsonObject? = null,
    val source: PermissionSource? = null,
    val agent: String? = null,
)

/** What the server decided the new request means. */
@Serializable
data class CreatePermissionResult(
    val id: String,
    val effect: PermissionEffect,
)

/**
 * `POST /api/session/{id}/form`: raise a form from a client (features doc §16).
 *
 * The one generic mechanism the `question` tool, MCP elicitation and integration login all use, so
 * a plugin-created form renders through the existing form engine rather than a second renderer.
 */
@Serializable
data class CreateFormRequest(
    /** A client-generated `frm_` id, so a retry does not raise a second form. */
    val id: String? = null,
    val title: String,
    val metadata: JsonObject? = null,
    val fields: List<FormField>,
)

/** `POST /api/rpc/{rpcID}/{method}` (features doc §19): call a plugin RPC method. */
@Serializable
data class RpcRequest(val input: JsonElement? = null)
