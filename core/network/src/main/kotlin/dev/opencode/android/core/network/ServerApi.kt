package dev.opencode.android.core.network

import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.DataResponse
import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.model.FormDetail
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.FormReplyPayload
import dev.opencode.android.core.model.InboxUpdateRequest
import dev.opencode.android.core.model.InterruptResult
import dev.opencode.android.core.model.LocationInfo
import dev.opencode.android.core.model.LocationScoped
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.Paged
import dev.opencode.android.core.model.PairingSession
import dev.opencode.android.core.model.PermissionReplyPayload
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.PromptRequest
import dev.opencode.android.core.model.ServerInfo
import dev.opencode.android.core.model.SessionCreateRequest
import dev.opencode.android.core.model.SessionInboxInfo
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.SessionUpdateRequest
import dev.opencode.android.core.model.SwitchAgentRequest
import dev.opencode.android.core.model.SwitchModelRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * The read and write surface of one server: the Phase 1 calls, the Phase 2 projections and the
 * Phase 3 driving operations.
 *
 * Instances are per server; [ServerApiFactory] builds one bound to a base URL and a credential.
 * `event.subscribe` (`GET /api/event`) is not here because the SSE reader needs the raw response
 * stream and full control over timeouts; see [EventStreamClient] and `EVENT_PATH`.
 *
 * **The location is a parameter, not an interceptor.** P2 calls this interface with
 * `directory = null` so that no `location[directory]` is attached to routes that do not take one
 * (`project.list`, `session.active`, `session.list`), and names the parameter explicitly with
 * [LocationParam.QUERY_KEY] on the routes that do. The P1 factory behaviour is unchanged.
 *
 * **One interface, not one per tag.** [EventStreamClient] has to be built by hand because it owns
 * the raw response stream, and the P1 factory returned a single object that every caller injected.
 * Splitting the rest into a read interface and a write interface would mean either two Retrofit
 * instances over the same OkHttp client (two generated proxies, one connection pool, no benefit) or
 * changing the factory's contract for no behavioural gain, so the two sections below stay in one
 * interface with a comment marking where the split would go.
 *
 * **Writes answer with events, not with bodies.** A `204` write is confirmed by the event that
 * changes the state, which the P2 stores already apply (plan §4.2), so a write returns `Unit` and
 * Retrofit raises `HttpException` for a failure. A method that returns `DataResponse` is the
 * exception: the server answers with the projection it created, and reading it is cheaper than
 * waiting for the event to arrive.
 */
interface ServerApi {

    /**
     * `GET /api/info`: version, process id, the URLs the server is reachable at, and its temp path.
     */
    @GET("api/info")
    suspend fun getServerInfo(@Tag credential: ServerAuthCredential? = null): ServerInfo

    /**
     * `GET /auth/connect/{code}` with `Accept: application/json`, which exchanges a one-time
     * pairing code for a 30-day session token. Needs no authentication (features doc §2.3).
     */
    @Headers("Accept: application/json")
    @GET("auth/connect/{code}")
    suspend fun redeemPairingCode(@Path("code") code: String): PairingSession

    // ---------------------------------------------------------------- Phase 2: read path

    /** `location.get`: the location resolved for a directory, and the project it belongs to. */
    @GET("api/location")
    suspend fun getLocation(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationInfo

    /** `project.list`: every project on the server, most recently active first. */
    @GET("api/project")
    suspend fun listProjects(): List<Project>

    /**
     * `session.list`: one cursor page of sessions, newest first.
     *
     * [directory], [projectID] and [subpath] filter server-side; [search] matches the title;
     * [parentID] selects a session's children. Omitting [parentID] returns every session, so the
     * roots-only filter is a client-side decision over a page.
     */
    @GET("api/session")
    suspend fun listSessions(
        @Query("limit") limit: Int? = null,
        @Query("order") order: String? = null,
        @Query("search") search: String? = null,
        @Query("parentID") parentID: String? = null,
        @Query("directory") directory: String? = null,
        @Query("project") projectID: String? = null,
        @Query("subpath") subpath: String? = null,
        @Query("cursor") cursor: String? = null,
    ): Paged<SessionInfo>

    /** `session.active`: the ids of the sessions with a live execution. */
    @GET("api/session/active")
    suspend fun listActiveSessions(): DataResponse<dev.opencode.android.core.model.ActiveSessionMap>

    /** `session.get`: one session's projection. */
    @GET("api/session/{sessionID}")
    suspend fun getSession(
        @Path("sessionID") sessionID: String,
    ): DataResponse<SessionInfo>

    /**
     * `session.message.list`: one page of a session's projected timeline.
     *
     * The server returns newest first for `order = desc`, which is what the timeline reads; the
     * reducer stores the reverse.
     */
    @GET("api/session/{sessionID}/message")
    suspend fun listMessages(
        @Path("sessionID") sessionID: String,
        @Query("limit") limit: Int? = null,
        @Query("order") order: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("type") type: String? = null,
    ): Paged<SessionMessage>

    /** `session.message.get`: one message, used to fill a gap the event stream did not carry. */
    @GET("api/session/{sessionID}/message/{messageID}")
    suspend fun getMessage(
        @Path("sessionID") sessionID: String,
        @Path("messageID") messageID: String,
    ): DataResponse<SessionMessage>

    /** `session.inbox.list`: the work still queued for a session. */
    @GET("api/session/{sessionID}/inbox")
    suspend fun listInbox(
        @Path("sessionID") sessionID: String,
    ): DataResponse<List<SessionInboxInfo>>

    /** `agent.list`: the agents a location defines, with names and colors. */
    @GET("api/agent")
    suspend fun listAgents(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<AgentInfo>>

    /** `agent.get`: one agent by id. */
    @GET("api/agent/{agentID}")
    suspend fun getAgent(
        @Path("agentID") agentID: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<AgentInfo>

    /** `model.list`: the models a location offers, with their context limits. */
    @GET("api/model")
    suspend fun listModels(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<ModelInfo>>

    /** `model.default`: the model a new session starts with, or `null` when none is configured. */
    @GET("api/model/default")
    suspend fun getDefaultModel(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<ModelInfo?>

    // ---------------------------------------------------------------- Phase 3: drive sessions

    /**
     * `session.create`.
     *
     * Every field is optional, so the server fills in the location, the agent and the model it
     * would use by default. The app sends the location and the agent the user chose and omits the
     * model unless one was picked, which is what leaves `model.default` in charge.
     */
    @POST("api/session")
    suspend fun createSession(
        @Body body: SessionCreateRequest,
    ): DataResponse<SessionInfo>

    /**
     * `session.update`: rename, replace the metadata, or set session permission rules.
     *
     * A field left out is not changed. The answer is `204`, and the `session.renamed`,
     * `session.metadata.updated` and `session.permissions` events carry the result, so there is
     * nothing to read back.
     */
    @PATCH("api/session/{sessionID}")
    suspend fun updateSession(
        @Path("sessionID") sessionID: String,
        @Body body: SessionUpdateRequest,
    ): Unit

    /** `session.remove`: deletes a session and its children. `204`. */
    @DELETE("api/session/{sessionID}")
    suspend fun removeSession(
        @Path("sessionID") sessionID: String,
    ): Unit

    /** `session.switchAgent`, confirmed by `session.agent.selected`. `204`. */
    @POST("api/session/{sessionID}/agent")
    suspend fun switchAgent(
        @Path("sessionID") sessionID: String,
        @Body body: SwitchAgentRequest,
    ): Unit

    /** `session.switchModel`, confirmed by `session.model.selected`. `204`. */
    @POST("api/session/{sessionID}/model")
    suspend fun switchModel(
        @Path("sessionID") sessionID: String,
        @Body body: SwitchModelRequest,
    ): Unit

    /**
     * `session.prompt`.
     *
     * Answers with the inbox item it enqueued, which is the same object `session.inbox.enqueued`
     * carries. A `msg_…` [PromptRequest.id] that is reused with a different payload is a `409`.
     */
    @POST("api/session/{sessionID}/prompt")
    suspend fun prompt(
        @Path("sessionID") sessionID: String,
        @Body body: PromptRequest,
    ): DataResponse<SessionInboxInfo>

    /**
     * `session.interrupt`.
     *
     * With [resume] true, pending steering input resumes and queued prompts stay parked
     * (features doc §4.2), which is the difference between stopping and stopping-and-continuing.
     *
     * The answer is a bare `SessionInterruptResponse`, not the `{data: …}` wrapper most routes use;
     * the spec says so and a 2.0.18 server confirms it, so wrapping it would fail to decode.
     */
    @POST("api/session/{sessionID}/interrupt")
    suspend fun interrupt(
        @Path("sessionID") sessionID: String,
        @Query("resume") resume: Boolean? = null,
    ): InterruptResult

    /** `session.background`: moves blocking tools to the background so the turn can finish. */
    @POST("api/session/{sessionID}/background")
    suspend fun background(
        @Path("sessionID") sessionID: String,
    ): Unit

    /** `session.inbox.update`: switches a pending item between queue and steer. `204`. */
    @PATCH("api/session/{sessionID}/inbox/{inboxID}")
    suspend fun updateInboxItem(
        @Path("sessionID") sessionID: String,
        @Path("inboxID") inboxID: String,
        @Body body: InboxUpdateRequest,
    ): Unit

    /** `session.inbox.cancel`: drops a pending item. `204`, and `session.inbox.cancelled` follows. */
    @DELETE("api/session/{sessionID}/inbox/{inboxID}")
    suspend fun cancelInboxItem(
        @Path("sessionID") sessionID: String,
        @Path("inboxID") inboxID: String,
    ): Unit

    /** `permission.request.list`: the pending requests of a location, across all its sessions. */
    @GET("api/permission/request")
    suspend fun listPermissionRequests(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<PermissionRequest>>

    /** `session.permission.list`: the pending requests of one session. */
    @GET("api/session/{sessionID}/permission")
    suspend fun listSessionPermissions(
        @Path("sessionID") sessionID: String,
    ): DataResponse<List<PermissionRequest>>

    /** `session.permission.get`: one request, for the case the list is missing an id. */
    @GET("api/session/{sessionID}/permission/{requestID}")
    suspend fun getSessionPermission(
        @Path("sessionID") sessionID: String,
        @Path("requestID") requestID: String,
    ): DataResponse<PermissionRequest>

    /**
     * `session.permission.reply`.
     *
     * [PermissionReplyPayload.decision] is once, always or reject; a reject rejects every pending
     * request in the session. [PermissionReplyPayload.message] is optional feedback to the agent.
     */
    @POST("api/session/{sessionID}/permission/{requestID}/reply")
    suspend fun replyToPermission(
        @Path("sessionID") sessionID: String,
        @Path("requestID") requestID: String,
        @Body body: PermissionReplyPayload,
    ): Unit

    /** `form.list`: the pending forms of a location, across all its sessions. */
    @GET("api/form")
    suspend fun listForms(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<FormInfo>>

    /** `session.form.list`: the pending forms of one session. */
    @GET("api/session/{sessionID}/form")
    suspend fun listSessionForms(
        @Path("sessionID") sessionID: String,
    ): DataResponse<List<FormInfo>>

    /** `session.form.get`: one form with its state, for the case the list is missing an id. */
    @GET("api/session/{sessionID}/form/{formID}")
    suspend fun getSessionForm(
        @Path("sessionID") sessionID: String,
        @Path("formID") formID: String,
    ): DataResponse<FormDetail>

    /** `session.form.reply`: answers a form. `204`, and `form.replied` follows. */
    @POST("api/session/{sessionID}/form/{formID}/reply")
    suspend fun replyToForm(
        @Path("sessionID") sessionID: String,
        @Path("formID") formID: String,
        @Body body: FormReplyPayload,
    ): Unit

    /** `session.form.cancel`: dismissing a question cancels it. `204`, `form.cancelled` follows. */
    @DELETE("api/session/{sessionID}/form/{formID}")
    suspend fun cancelForm(
        @Path("sessionID") sessionID: String,
        @Path("formID") formID: String,
    ): Unit

    /**
     * `fs.list`: the entries of a directory on the server.
     *
     * [path] is absolute or relative to the location, and defaults to the location itself. The
     * entries carry the server's own path spelling, so a browser has to join them the way the
     * server does rather than normalize them itself.
     */
    @GET("api/fs/list")
    suspend fun listDirectory(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
        @Query("path") path: String? = null,
    ): LocationScoped<List<FileSystemEntry>>
}
