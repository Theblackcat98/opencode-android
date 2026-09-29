package dev.opencode.android.core.data.insights

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.CapabilityPolicy
import dev.opencode.android.core.data.capability.ExperimentalRoute
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.integrations.ActionFailure
import dev.opencode.android.core.model.CreateFormRequest
import dev.opencode.android.core.model.CreatePermissionRequest
import dev.opencode.android.core.model.CreatePermissionResult
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.GenerateTextRequest
import dev.opencode.android.core.model.GenerateTextResult
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.PairingCode
import dev.opencode.android.core.model.RpcRequest
import dev.opencode.android.core.model.SessionStats
import dev.opencode.android.core.model.SyntheticInputRequest
import dev.opencode.android.core.model.SyntheticInputResult
import dev.opencode.android.core.model.ToolDetailMode
import dev.opencode.android.core.network.ServerApi
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The Phase 10 read and write surface: usage statistics, the plugin RPC console, quick ask, the
 * durable session log, pairing another device, and the advanced session tools.
 *
 * **Every route here is experimental or unpublished, and every one of them degrades.**
 *
 *  - The statistics, generate, wait and log routes live under `/api/experimental/`, and a server may
 *    not have them. Each call records what its `404` or `405` said, and the screen hides the feature
 *    rather than showing an error the user can do nothing about.
 *  - `POST /api/pair` is not in the published spec at all. It is the one route that can disappear in
 *    any 2.0.x release, so it is probed like the others and is never part of a flow the app depends
 *    on. The app's own pairing, redeeming a code the CLI printed, is a different route and is
 *    documented.
 *  - The advanced session tools write to a session the user is looking at. `session.synthetic` adds
 *    text the user did not type, and `session.permission.create` blocks the agent exactly as a tool
 *    would, so both take a confirmation before they are sent.
 *
 * **A call answers with the failure class the UI renders, or `null` for success**, like every other
 * surface in the app. Nothing here throws at a call site.
 */
class InsightsSurface(private val api: ServerApi) {

    private val statsState = MutableStateFlow<RouteAvailability>(RouteAvailability.Unknown)
    private val generateState = MutableStateFlow<RouteAvailability>(RouteAvailability.Unknown)
    private val logState = MutableStateFlow<RouteAvailability>(RouteAvailability.Unknown)
    private val pairState = MutableStateFlow<RouteAvailability>(RouteAvailability.Unknown)
    private val ids = AtomicLong(0)

    /** Whether the usage dashboard is available. */
    val stats: StateFlow<RouteAvailability> = statsState.asStateFlow()

    /** Whether the stateless routes (`generate`, `wait`) are available. */
    val generate: StateFlow<RouteAvailability> = generateState.asStateFlow()

    /** Whether the durable session log can be read. */
    val log: StateFlow<RouteAvailability> = logState.asStateFlow()

    /** Whether this server will mint a pairing code for another device. */
    val pairDevice: StateFlow<RouteAvailability> = pairState.asStateFlow()

    // ------------------------------------------------------------------ usage dashboard

    /**
     * `experimental.session.stats`: usage for a range.
     *
     * **Every argument is optional and the server's defaults are used for the ones left out.** A
     * client that invented a default range would show a different window than `opencode stats` does
     * for the same question, and the two would look like a bug in one of them. The caller sends what
     * the user chose and nothing more.
     *
     * [tools] selects how much tool detail the server computes, which is a real cost on a large
     * history, so the dashboard asks for `summary` while it is idle and `detail` when the user
     * opens the tool list.
     */
    suspend fun sessionStats(
        from: Long? = null,
        to: Long? = null,
        project: String? = null,
        timezone: String? = null,
        tools: ToolDetailMode? = null,
        directory: String? = null,
    ): Result<SessionStats> = experimental(ExperimentalRoute.SESSION_STATS) {
        api.getSessionStats(
            from = from,
            to = to,
            project = project,
            timezone = timezone,
            tools = tools?.wire,
            directory = directory,
        ).data
    }

    // ------------------------------------------------------------------ plugin RPC console

    /**
     * `rpc.call`: dispatches a method to a plugin.
     *
     * **Nothing about the input or the output is assumed.** No plugin method is enumerated in the
     * spec, the request body is `{input: …}` and the answer is `{output: …}` where both are
     * arbitrary JSON, so the console passes the user's JSON through and shows what came back. A
     * method name that is not a valid path segment is rejected here rather than being sent as a
     * broken URL.
     */
    suspend fun callRpc(
        rpcID: String,
        method: String,
        input: JsonElement? = null,
        directory: String? = null,
    ): Result<JsonObject> {
        val invalid = invalidSegment(rpcID) ?: invalidSegment(method)
        if (invalid != null) {
            return Result.failure(
                ActionFailure(
                    ActionError(
                        kind = ActionErrorKind.INVALID_REQUEST,
                        message = "'$invalid' is not a usable rpc id or method: a path segment " +
                            "cannot be empty, '.', '..' or contain a separator",
                    ),
                ),
            )
        }
        return guarded { api.callRpc(rpcID, method, directory, RpcRequest(input)) }
    }

    // ------------------------------------------------------------------ quick ask

    /**
     * `experimental.generate.text`: one stateless completion, with no session and no history.
     *
     * `503` means the server has no provider it can reach. That is a setup problem on the server and
     * not something a retry fixes, which is why the error class is kept rather than flattened.
     */
    suspend fun generateText(prompt: String, model: ModelRef? = null): Result<GenerateTextResult> =
        experimental(ExperimentalRoute.GENERATE_AND_WAIT) {
            api.generateText(GenerateTextRequest(prompt, model)).data
        }

    // ------------------------------------------------------------------ session tools

    /**
     * `experimental.session.wait`: holds the request open until the session is idle.
     *
     * **Long, and cancellable, and never on the main thread.** The server keeps the socket open for
     * as long as the turn takes, so the caller owns the timeout: a wait whose duration the user did
     * not choose is a UI that says "waiting" with no way to stop. Cancelling the caller's coroutine
     * cancels the request, which is what makes a "stop waiting" button work.
     */
    suspend fun waitUntilIdle(sessionID: String): Result<Unit> =
        experimental(ExperimentalRoute.GENERATE_AND_WAIT) { api.waitForSession(sessionID) }

    /**
     * `session.synthetic`: injects text the user did not type, through the session's inbox.
     *
     * The generated `msg_` id makes a retry idempotent, so a request that times out on a mobile
     * network can be sent again without producing a second inbox item — which is the difference
     * between "it did not go through" and "it went through twice".
     *
     * **The text is not a user prompt** and lands in the transcript as `synthetic`, so a UI that
     * offers this has to say which it is doing.
     */
    suspend fun addSyntheticInput(
        sessionID: String,
        text: String,
        description: String? = null,
        delivery: Delivery? = null,
        resume: Boolean? = null,
        metadata: JsonObject? = null,
    ): Result<SyntheticInputResult> = guarded {
        api.addSyntheticInput(
            sessionID,
            SyntheticInputRequest(
                id = "msg_syn_${ids.incrementAndGet()}",
                text = text,
                description = description,
                delivery = delivery,
                resume = resume,
                metadata = metadata,
            ),
        ).data
    }

    /**
     * `session.permission.create`: raises a permission request from the client.
     *
     * **This blocks the agent.** A request created here gates the turn exactly as one from a tool
     * does, which is why the screen confirms before sending it and why the answer's `effect` decides
     * whether there is anything to ask: a request the standing approvals already cover comes back
     * `allow`, and offering a choice the user cannot make is worse than saying it is already
     * granted.
     */
    suspend fun createPermissionRequest(
        sessionID: String,
        request: CreatePermissionRequest,
    ): Result<CreatePermissionResult> = guarded {
        api.createPermissionRequest(
            sessionID,
            request.copy(id = request.id ?: "per_cli_${ids.incrementAndGet()}"),
        ).data
    }

    /**
     * `session.form.create`: raises a form through the one form engine the `question` tool, MCP
     * elicitation and integration login already use.
     */
    suspend fun createForm(sessionID: String, request: CreateFormRequest): Result<FormInfo> = guarded {
        api.createSessionForm(
            sessionID,
            request.copy(id = request.id ?: "frm_cli_${ids.incrementAndGet()}"),
        ).data
    }

    // ------------------------------------------------------------------ pairing another device

    /**
     * `POST /api/pair`: a one-time code another device redeems at `/auth/connect/{code}`.
     *
     * **The route is unpublished and this app does not depend on it.** It exists in the server source
     * and is absent from the spec, so a `404` or `405` hides the screen instead of producing an
     * error. Redeeming a code is the documented path and is a different route entirely.
     */
    suspend fun createPairingCode(): Result<PairingCode> =
        experimental(ExperimentalRoute.PAIR_DEVICE) { api.createPairingCode() }

    // ------------------------------------------------------------------ session log

    /**
     * Opens the durable session log.
     *
     * The stream itself is read by [SessionLogClient], which needs the raw body and the credential
     * rather than this surface; this records what the route said about itself so the event-history
     * viewer can hide itself.
     */
    fun readSessionLog(sessionID: String, after: String? = null, follow: Boolean = false): Flow<SessionLogEntry> =
        SessionLogClient(api, this).read(sessionID, after, follow)

    /** Records what the session log route said about itself. */
    fun recordLogAvailability(availability: RouteAvailability) {
        logState.value = availability
    }

    /** Records what a failed log call said about the route. */
    fun recordLogFailure(error: ActionError) {
        CapabilityPolicy.from(error)?.let { logState.value = it }
    }

    // ------------------------------------------------------------------ plumbing

    private fun invalidSegment(segment: String): String? = when {
        segment.isBlank() -> segment
        segment == "." || segment == ".." -> segment
        segment.contains('/') || segment.contains('?') || segment.contains('#') -> segment
        else -> null
    }

    private suspend inline fun <T> guarded(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(ActionFailure(error.toActionError()))
    }

    /** A call that also records what it said about its experimental route. */
    private suspend inline fun <T> experimental(
        route: ExperimentalRoute,
        crossinline block: suspend () -> T,
    ): Result<T> {
        val result = guarded { block() }
        result.exceptionOrNull()?.let { failure ->
            val error = (failure as? ActionFailure)?.error ?: failure.toActionError()
            CapabilityPolicy.from(error)?.let { availability -> record(route, availability) }
        } ?: record(route, RouteAvailability.Present)
        return result
    }

    private fun record(route: ExperimentalRoute, availability: RouteAvailability) {
        when (route) {
            ExperimentalRoute.SESSION_STATS -> statsState.value = availability
            ExperimentalRoute.GENERATE_AND_WAIT -> generateState.value = availability
            ExperimentalRoute.PAIR_DEVICE -> pairState.value = availability
            ExperimentalRoute.SESSION_LOG -> logState.value = availability
            else -> Unit
        }
    }
}
