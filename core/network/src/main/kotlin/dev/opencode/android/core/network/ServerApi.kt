package dev.opencode.android.core.network

import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.DataResponse
import dev.opencode.android.core.model.LocationInfo
import dev.opencode.android.core.model.LocationScoped
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.Paged
import dev.opencode.android.core.model.PairingSession
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.ServerInfo
import dev.opencode.android.core.model.SessionInboxInfo
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * The read surface of one server: the Phase 1 calls plus the Phase 2 projections.
 *
 * Instances are per server; [ServerApiFactory] builds one bound to a base URL and a credential.
 * `event.subscribe` (`GET /api/event`) is not here because the SSE reader needs the raw response
 * stream and full control over timeouts; see [EventStreamClient] and `EVENT_PATH`.
 *
 * **The location is a parameter, not an interceptor.** P2 calls this interface with
 * `directory = null` so that no `location[directory]` is attached to routes that do not take one
 * (`project.list`, `session.active`, `session.list`), and names the parameter explicitly with
 * [LocationParam.QUERY_KEY] on the routes that do. The P1 factory behaviour is unchanged.
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
}
