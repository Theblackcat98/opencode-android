package dev.opencode.android.core.network

import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.CommandAttempt
import dev.opencode.android.core.model.CommandAttemptStatus
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.ConfigEntry
import dev.opencode.android.core.model.ConfigPatchRequest
import dev.opencode.android.core.model.ConnectCommandRequest
import dev.opencode.android.core.model.ConnectKeyRequest
import dev.opencode.android.core.model.ConnectOAuthCompleteRequest
import dev.opencode.android.core.model.ConnectOAuthRequest
import dev.opencode.android.core.model.CreateFormRequest
import dev.opencode.android.core.model.CreatePermissionRequest
import dev.opencode.android.core.model.CreatePermissionResult
import dev.opencode.android.core.model.CredentialUpdateRequest
import dev.opencode.android.core.model.DataResponse
import dev.opencode.android.core.model.FileDiff
import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.model.FileSystemWrite
import dev.opencode.android.core.model.FormDetail
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.FormReplyPayload
import dev.opencode.android.core.model.GenerateTextRequest
import dev.opencode.android.core.model.GenerateTextResult
import dev.opencode.android.core.model.InboxUpdateRequest
import dev.opencode.android.core.model.InstructionEntry
import dev.opencode.android.core.model.InstructionEntryRequest
import dev.opencode.android.core.model.IntegrationInfo
import dev.opencode.android.core.model.InterruptResult
import dev.opencode.android.core.model.LoadedLocation
import dev.opencode.android.core.model.LocationInfo
import dev.opencode.android.core.model.LocationScoped
import dev.opencode.android.core.model.McpAddRequest
import dev.opencode.android.core.model.McpResourceCatalog
import dev.opencode.android.core.model.McpServer
import dev.opencode.android.core.model.MigrationStatus
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.OAuthAttempt
import dev.opencode.android.core.model.OAuthAttemptStatus
import dev.opencode.android.core.model.Paged
import dev.opencode.android.core.model.PairingCode
import dev.opencode.android.core.model.PairingSession
import dev.opencode.android.core.model.PermissionReplyPayload
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.core.model.PluginCheckRequest
import dev.opencode.android.core.model.PluginInfo
import dev.opencode.android.core.model.PluginUpdateRequest
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.ProjectUpdateRequest
import dev.opencode.android.core.model.PromptRequest
import dev.opencode.android.core.model.ProviderInfo
import dev.opencode.android.core.model.PtyCreateRequest
import dev.opencode.android.core.model.PtyTicketToken
import dev.opencode.android.core.model.PtyUpdateRequest
import dev.opencode.android.core.model.ReferenceInfo
import dev.opencode.android.core.model.RpcRequest
import dev.opencode.android.core.model.SavedPermission
import dev.opencode.android.core.model.ServerInfo
import dev.opencode.android.core.model.SessionCommandRequest
import dev.opencode.android.core.model.SessionCompactRequest
import dev.opencode.android.core.model.SessionCreateRequest
import dev.opencode.android.core.model.SessionEnvironmentRequest
import dev.opencode.android.core.model.SessionForkRequest
import dev.opencode.android.core.model.SessionGenerateRequest
import dev.opencode.android.core.model.SessionGenerateResponse
import dev.opencode.android.core.model.SessionImportRequest
import dev.opencode.android.core.model.SessionInboxInfo
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.SessionMoveRequest
import dev.opencode.android.core.model.SessionRevert
import dev.opencode.android.core.model.SessionRevertStageRequest
import dev.opencode.android.core.model.SessionShellRequest
import dev.opencode.android.core.model.SessionStats
import dev.opencode.android.core.model.SessionTerminalCreateRequest
import dev.opencode.android.core.model.SessionTerminalHandoff
import dev.opencode.android.core.model.SessionTerminalReadResponse
import dev.opencode.android.core.model.SessionTerminalSnapshot
import dev.opencode.android.core.model.SessionTerminalUpdateRequest
import dev.opencode.android.core.model.SessionTransfer
import dev.opencode.android.core.model.SessionUpdateRequest
import dev.opencode.android.core.model.SessionViewRequest
import dev.opencode.android.core.model.ShellCreateRequest
import dev.opencode.android.core.model.ShellInfo
import dev.opencode.android.core.model.ShellOption
import dev.opencode.android.core.model.ShellOutput
import dev.opencode.android.core.model.SkillActivationRequest
import dev.opencode.android.core.model.SkillInfo
import dev.opencode.android.core.model.SwitchAgentRequest
import dev.opencode.android.core.model.SwitchModelRequest
import dev.opencode.android.core.model.SyntheticInputRequest
import dev.opencode.android.core.model.SyntheticInputResult
import dev.opencode.android.core.model.VcsBase
import dev.opencode.android.core.model.VcsFileStatus
import dev.opencode.android.core.model.VcsInfo
import dev.opencode.android.core.model.WebSearchProviderInfo
import dev.opencode.android.core.model.WebSearchQueryRequest
import dev.opencode.android.core.model.WebSearchResponse
import dev.opencode.android.core.model.WellknownSourceRequest
import dev.opencode.android.core.model.WorktreeCreateRequest
import dev.opencode.android.core.model.WorktreeDirectory
import dev.opencode.android.core.model.WorktreeRefreshRequest
import dev.opencode.android.core.model.WorktreeRemoveRequest
import dev.opencode.android.core.model.event.PersistentPtyInfo
import dev.opencode.android.core.model.event.PtyInfo
import kotlinx.serialization.json.JsonObject
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Tag
import retrofit2.http.Url

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

    // ------------------------------------------------------- Phase 4: background and attention

    /**
     * `session.view`: marks the idle transition the viewer has now seen.
     *
     * The body is the `time.idle` the client observed, not the current time — the server records
     * *which* transition was viewed, so a later instant would mark a turn the user never read as
     * seen. `204`, and `session.viewed` follows, which is what clears the unread badge.
     */
    @POST("api/session/{sessionID}/view")
    suspend fun viewSession(
        @Path("sessionID") sessionID: String,
        @Body body: SessionViewRequest,
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

    // ---------------------------------------------------------- Phase 5: rich composer

    /**
     * `command.list`: the prompt templates a location defines, including MCP prompts named
     * `<server>:<prompt>` (features doc §11).
     */
    @GET("api/command")
    suspend fun listCommands(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<CommandInfo>>

    /** `skill.list`: the skills a location defines, with their whole body. */
    @GET("api/skill")
    suspend fun listSkills(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<SkillInfo>>

    /**
     * `reference.list`: the directories and cloned repositories the server can attach.
     *
     * A reference attaches by passing its path as a directory `file:` attachment, so the client
     * never reads the files itself (features doc §19).
     */
    @GET("api/reference")
    suspend fun listReferences(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<ReferenceInfo>>

    /**
     * `fs.find`: the ranked recursive search behind `@` completion.
     *
     * [type] is `file` or `directory` and [limit] is a string on the wire even though it is a
     * number, which the spec spells out. Verified against a live 2.0.18 server: the entries come
     * back **relative to the location**, so the client keeps the server's spelling and resolves it
     * against the location when it turns the path into a `file:` URI.
     */
    @GET("api/fs/find")
    suspend fun findFiles(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
        @Query("query") query: String,
        @Query("type") type: String? = null,
        @Query("limit") limit: String? = null,
    ): LocationScoped<List<FileSystemEntry>>

    /**
     * `session.command`: runs a command template.
     *
     * `204`; the echo is the `session.inbox.enqueued` event, which carries the text the template
     * produced, so there is nothing to read back.
     */
    @POST("api/session/{sessionID}/command")
    suspend fun runCommand(
        @Path("sessionID") sessionID: String,
        @Body body: SessionCommandRequest,
    ): Unit

    /**
     * `session.shell`: the composer's `!command` mode.
     *
     * `204`; `session.shell.started` and `session.shell.ended {output}` carry the result into the
     * timeline. A client-supplied [SessionShellRequest.id] is what makes a retry not run the command
     * a second time.
     */
    @POST("api/session/{sessionID}/shell")
    suspend fun runShell(
        @Path("sessionID") sessionID: String,
        @Body body: SessionShellRequest,
    ): Unit

    /**
     * `session.compact`: manual compaction (`/compact`).
     *
     * Answers with the inbox item it enqueued, and `409` while the session is busy. The streamed
     * summary arrives through `session.compaction.delta` and lands as a compaction message.
     */
    @POST("api/session/{sessionID}/compact")
    suspend fun compact(
        @Path("sessionID") sessionID: String,
        @Body body: SessionCompactRequest,
    ): DataResponse<SessionInboxInfo>

    /** `session.generate`: a side question about the session's context (`/btw`). `{data: {text}}`. */
    @POST("api/session/{sessionID}/generate")
    suspend fun generate(
        @Path("sessionID") sessionID: String,
        @Body body: SessionGenerateRequest,
    ): SessionGenerateResponse

    /**
     * `session.environment`: the variables this session's tools run with.
     *
     * `PUT` with the whole map, so removing a variable is sending the map without it.
     */
    @PUT("api/session/{sessionID}/environment")
    suspend fun setSessionEnvironment(
        @Path("sessionID") sessionID: String,
        @Body body: SessionEnvironmentRequest,
    ): Unit

    /**
     * `experimental.session.skill`: activates a skill in the running session.
     *
     * Experimental, so it is only ever called after a capability probe said the route exists, and
     * the composer falls back to attaching the skill on the next prompt when it does not.
     */
    @POST("api/experimental/session/{sessionID}/skill")
    suspend fun activateSkill(
        @Path("sessionID") sessionID: String,
        @Body body: SkillActivationRequest,
    ): Unit

    // ----------------------------------------------- Phase 6: review, diffs, files and history

    /**
     * `session.diff`: what one turn changed (features doc §4.2, "Per-turn file diffs").
     *
     * [from] is the user message whose turn to diff and defaults to the newest user message's turn;
     * [to] extends the range to a later user message. [context] is the number of unchanged lines
     * around each hunk and is left out to ask for full-file patches, which is what a phone wants
     * far less often than the server's default of a three-line context.
     */
    @GET("api/session/{sessionID}/diff")
    suspend fun sessionDiff(
        @Path("sessionID") sessionID: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("context") context: String? = null,
    ): DataResponse<List<FileDiff>>

    /** `vcs.get`: the provider and the current and default branches of a location. */
    @GET("api/vcs")
    suspend fun getVcs(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<VcsInfo>

    /** `vcs.base`: the ref the review is taken against, or `null` when the server has no opinion. */
    @GET("api/vcs/base")
    suspend fun getVcsBase(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<VcsBase?>

    /** `vcs.status`: the files the working copy has changed, with the server's own counts. */
    @GET("api/vcs/status")
    suspend fun getVcsStatus(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<VcsFileStatus>>

    /**
     * `vcs.branch.list`: the branch names the base picker offers.
     *
     * [limit] is a string on the wire even though it is a number, which the spec spells out and
     * which `fs.find` has the same shape for.
     */
    @GET("api/vcs/branch")
    suspend fun listVcsBranches(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
        @Query("search") search: String? = null,
        @Query("limit") limit: String? = null,
    ): LocationScoped<List<String>>

    /**
     * `vcs.diff`: the repository diff for one review scope.
     *
     * [mode] is `working` (HEAD to the working copy), `committed` (merge base to HEAD) or `branch`
     * (merge base to the working copy) — the TUI's uncommitted, committed and all. [base] overrides
     * the branch the merge base is taken against, which is what the base picker sets.
     */
    @GET("api/vcs/diff")
    suspend fun vcsDiff(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
        @Query("mode") mode: String,
        @Query("base") base: String? = null,
        @Query("context") context: String? = null,
    ): LocationScoped<List<FileDiff>>

    /**
     * `session.revert.stage`: `/undo` (features doc §21).
     *
     * [files] true asks the server to restore the working copy as well as marking the turn, which
     * is what makes an undo undo rather than just hide. The server answers `409` while the session
     * is busy, so the caller interrupts first.
     */
    @POST("api/session/{sessionID}/revert/stage")
    suspend fun stageRevert(
        @Path("sessionID") sessionID: String,
        @Body body: SessionRevertStageRequest,
    ): DataResponse<SessionRevert>

    /** `session.revert.clear`: `/redo`. `204`, and `session.revert.cleared` follows. */
    @DELETE("api/session/{sessionID}/revert")
    suspend fun clearRevert(
        @Path("sessionID") sessionID: String,
    ): Unit

    /**
     * `session.revert.commit`: accepts the rollback.
     *
     * The TUI commits before submitting the edited prompt, and so does this client, because the
     * server applies the restore at commit time. `204`, and `session.revert.committed {to}` says
     * which snapshot the working copy is at now.
     */
    @POST("api/session/{sessionID}/revert/commit")
    suspend fun commitRevert(
        @Path("sessionID") sessionID: String,
    ): Unit

    /**
     * `session.fork`: a copy of the session, optionally cut before a message.
     *
     * Omitting [before] copies the whole history. The answer is the new session, and
     * `session.forked` carries the same object, so the caller can navigate immediately.
     */
    @POST("api/session/{sessionID}/fork")
    suspend fun forkSession(
        @Path("sessionID") sessionID: String,
        @Body body: SessionForkRequest,
    ): DataResponse<SessionInfo>

    /**
     * `fs.read`: the raw bytes of a file, relative to the location (features doc §27).
     *
     * **The whole URL is built by the caller, not by Retrofit's path substitution.** The route is a
     * wildcard (`GET /api/fs/read/` followed by a star) and Retrofit has no wildcard path
     * parameter: `@Path` strips
     * one leading `/` from its value, which turns an absolute path into a relative one and makes the
     * server read a different file than the one asked for. Verified against a live 2.0.18 server: a
     * double slash is what carries the leading `/` of an absolute path, and it answers `200` with
     * the right bytes, so the caller composes `api/fs/read/` + the server's own spelling.
     *
     * [directory] stays a query parameter because that is how the location is addressed everywhere
     * else.
     *
     * **This is a streaming [Call], not a `suspend` function returning the body, and both halves
     * are what make it safe on a file the phone cannot hold.** Without `@Streaming` Retrofit copies
     * the *entire* body into memory on an OkHttp thread before the call returns, so a 572 MB file was
     * 572 MB of heap before any caller could decide to stop — and the `OutOfMemoryError` was thrown
     * on that thread, where nothing could catch it, and killed the app. Streaming hands back the live
     * body instead: the caller reads what it wants from `source()` and closes it.
     *
     * The [Call] is returned rather than awaited because **closing a body does not stop a
     * download, cancelling the call does.** OkHttp answers `close()` on an unfinished body by trying
     * to drain the rest for up to 100 ms so it can reuse the connection, which on a fast link is
     * megabytes the phone never wanted; `Call.cancel()` drops the connection at once. A `suspend`
     * signature would hide the call, and with it the only way to stop reading a file at the cap.
     * `FileReader.read` is the one caller, and it owns both rules.
     */
    @Streaming
    @GET
    fun readFile(
        @Url url: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Call<ResponseBody>

    /**
     * `experimental.fs.write`: writes a file and creates its parents.
     *
     * Writing to the machine the agent is working on is a dangerous action (plan §5.2), so the
     * route is behind capability detection *and* a setting, and the response only names the path
     * that was written — the caller re-reads the file rather than trusting its own buffer.
     */
    @POST("api/experimental/fs/write")
    suspend fun writeFile(
        @Query("path") path: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
        @Body body: RequestBody,
    ): LocationScoped<FileSystemWrite>

    /** The media type `fs.write` accepts: the body is raw bytes, and `text/plain` is a `415`. */
    val OCTET_STREAM: String get() = "application/octet-stream"

    /**
     * `experimental.session.export`: a session and its transcript.
     *
     * [sanitize] is a string on the wire even though it is a boolean, which the spec spells out.
     * Sanitizing redacts sensitive data, so it is the default and the switch to turn it off is the
     * caller's decision, not this method's.
     */
    @GET("api/experimental/session/{sessionID}/export")
    suspend fun exportSession(
        @Path("sessionID") sessionID: String,
        @Query("sanitize") sanitize: String? = null,
    ): DataResponse<SessionTransfer>

    /** `experimental.session.import`: appends a transcript. Import parents before children. */
    @POST("api/experimental/session/import")
    suspend fun importSession(
        @Body body: SessionImportRequest,
    ): DataResponse<SessionInfo>

    /** `session.context`: the messages after the last compaction, which is what the model sees. */
    @GET("api/session/{sessionID}/context")
    suspend fun getSessionContext(
        @Path("sessionID") sessionID: String,
    ): DataResponse<List<SessionMessage>>

    // ---------------------------------------- Phase 7: subagents, shells, terminals, worktrees

    // ------------------------------------------------------------------ shells (5 operations)

    /** `shell.list`: the commands running in a location, the user's and the agent's alike. */
    @GET("api/shell")
    suspend fun listShells(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<ShellInfo>>

    /**
     * `shell.create`: runs a command and captures its combined output to a file.
     *
     * Answers with the [ShellInfo] it started, and `shell.created` carries the same object, so a
     * caller can show the row without waiting for the event. A retry is *not* idempotent: there is no
     * client id on this route, so a call that failed in transit may have started a command the user
     * now has twice, and the panel's list is what tells them.
     */
    @POST("api/shell")
    suspend fun createShell(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
        @Body body: ShellCreateRequest,
    ): LocationScoped<ShellInfo>

    /** `shell.get`: one command's status and exit code. */
    @GET("api/shell/{id}")
    suspend fun getShell(
        @Path("id") id: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<ShellInfo>

    /** `shell.remove`: kills a running command and drops it. `204`, `shell.deleted` follows. */
    @DELETE("api/shell/{id}")
    suspend fun removeShell(
        @Path("id") id: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    /**
     * `shell.output`: one page of a command's combined output, by byte cursor.
     *
     * [cursor] is a **string** on the wire even though the schema calls it a number (the same shape
     * `fs.find`'s `limit` has), and [limit] likewise. The panel polls with the cursor the last page
     * ended at, which is why the client never sends a cursor it did not get back.
     */
    @GET("api/shell/{id}/output")
    suspend fun getShellOutput(
        @Path("id") id: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: String? = null,
    ): LocationScoped<ShellOutput>

    // ------------------------------------------------------------------ terminals (7)

    /** `pty.list`: every terminal of a location, exited ones included until they are removed. */
    @GET("api/pty")
    suspend fun listPtys(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<PtyInfo>>

    /** `pty.create`: starts a terminal. `command` omitted runs the location's configured shell. */
    @POST("api/pty")
    suspend fun createPty(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
        @Body body: PtyCreateRequest,
    ): LocationScoped<PtyInfo>

    /** `pty.get`: one terminal, with its exit code once it has one. */
    @GET("api/pty/{ptyID}")
    suspend fun getPty(
        @Path("ptyID") ptyID: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<PtyInfo>

    /**
     * `pty.update`: renames a terminal, resizes it, or both.
     *
     * This is how resizing is delivered (features doc §31): the WebSocket frames are terminal bytes,
     * so a resize cannot travel on it without the client having to tell a JSON frame from output.
     */
    @PUT("api/pty/{ptyID}")
    suspend fun updatePty(
        @Path("ptyID") ptyID: String,
        @Body body: PtyUpdateRequest,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<PtyInfo>

    /** `pty.remove`: kills the process and drops the terminal. `204`, `pty.deleted` follows. */
    @DELETE("api/pty/{ptyID}")
    suspend fun removePty(
        @Path("ptyID") ptyID: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    /**
     * `pty.connect.token`: a single-use ticket for a WebSocket that cannot send an `Authorization`
     * header — a page in the WebView.
     *
     * The [PtyTicketToken.HEADER] request header is required; without it the route answers `403`,
     * which says "forbidden" about a route that does exist. This client connects from OkHttp with
     * Basic auth and only calls this for the WebView fallback.
     */
    @Headers("${PtyTicketToken.HEADER}: ${PtyTicketToken.HEADER_VALUE}")
    @POST("api/pty/{ptyID}/connect-token")
    suspend fun createPtyTicket(
        @Path("ptyID") ptyID: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<PtyTicketToken>

    /**
     * `pty.connect`: the WebSocket upgrade endpoint, declared so the route is discoverable.
     *
     * It is never called over HTTP — [PtySocket] builds the same URL as a WebSocket request. The
     * method is here for the path, the query names and the documentation of the `4404` close code,
     * and it is deliberately `suspend` returning a `Boolean` (the spec's `200` body) so a mistake
     * that calls it as a plain request is visible in the type rather than in a log.
     */
    @GET("api/pty/{ptyID}/connect")
    suspend fun ptyConnect(
        @Path("ptyID") ptyID: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("ticket") ticket: String? = null,
    ): Boolean

    // ------------------------------------------------- persistent terminals (11, experimental)

    /**
     * `experimental.session.terminal.list`.
     *
     * Experimental, so it is only ever called after a capability probe said the route exists. A
     * `503` is the server's "the persistent-PTY host is not running", which is not an absence: the
     * route exists and the service does not, and the two are shown differently.
     */
    @GET("api/experimental/session/{sessionID}/terminal")
    suspend fun listSessionTerminals(
        @Path("sessionID") sessionID: String,
    ): DataResponse<List<PersistentPtyInfo>>

    @POST("api/experimental/session/{sessionID}/terminal")
    suspend fun createSessionTerminal(
        @Path("sessionID") sessionID: String,
        @Body body: SessionTerminalCreateRequest,
    ): DataResponse<PersistentPtyInfo>

    /** `experimental.session.terminal.read`: the most recently controlled terminal's screen. */
    @GET("api/experimental/session/{sessionID}/terminal/read")
    suspend fun readSessionTerminal(
        @Path("sessionID") sessionID: String,
        @Query("lines") lines: String? = null,
    ): SessionTerminalReadResponse

    @GET("api/experimental/persistent-pty/{ptyID}")
    suspend fun getSessionTerminal(
        @Path("ptyID") ptyID: String,
    ): DataResponse<PersistentPtyInfo>

    /** `experimental.persistent-pty.update`: resize, and claim the terminal with an attachment. */
    @PUT("api/experimental/persistent-pty/{ptyID}")
    suspend fun updateSessionTerminal(
        @Path("ptyID") ptyID: String,
        @Body body: SessionTerminalUpdateRequest,
    ): DataResponse<PersistentPtyInfo>

    @DELETE("api/experimental/persistent-pty/{ptyID}")
    suspend fun removeSessionTerminal(
        @Path("ptyID") ptyID: String,
    ): Unit

    @GET("api/experimental/persistent-pty/{ptyID}/snapshot")
    suspend fun getSessionTerminalSnapshot(
        @Path("ptyID") ptyID: String,
    ): DataResponse<SessionTerminalSnapshot>

    @Headers("${PtyTicketToken.HEADER}: ${PtyTicketToken.HEADER_VALUE}")
    @POST("api/experimental/persistent-pty/{ptyID}/connect-token")
    suspend fun createSessionTerminalTicket(
        @Path("ptyID") ptyID: String,
    ): DataResponse<PtyTicketToken>

    /** The WebSocket upgrade of a persistent terminal; see [ptyConnect] for why it is declared. */
    @GET("api/experimental/persistent-pty/{ptyID}/connect")
    suspend fun sessionTerminalConnect(
        @Path("ptyID") ptyID: String,
        @Query("cursor") cursor: String? = null,
        @Query("role") role: String? = null,
        @Query("attachment_id") attachmentID: String? = null,
        @Query("takeover") takeover: String? = null,
        @Query("input_protocol") inputProtocol: String? = null,
        @Query("ticket") ticket: String? = null,
    ): Boolean

    /**
     * `experimental.persistent-pty.shutdown`: stops the host that owns every persistent terminal.
     *
     * **Admin only, and an action of the last resort.** It ends the terminals of every session on
     * the server, so the only place it appears is the server status page behind an explicit
     * confirmation, and never in a session's own UI.
     */
    @POST("api/experimental/persistent-pty/shutdown")
    suspend fun shutdownPersistentPtyHost(): Unit

    /**
     * `experimental.persistent-pty.handoff`: prepares a service restart, answering the instance id
     * and ticket the new host is reattached with. Admin only, like [shutdownPersistentPtyHost].
     */
    @POST("api/experimental/persistent-pty/handoff")
    suspend fun handoffPersistentPtyHost(): SessionTerminalHandoff.Wrapper

    // ------------------------------------------------------------------ worktrees (4)

    /**
     * `worktree.list`: a project's saved worktree inventory.
     *
     * The answer is a bare array, not a `{data}` wrapper, and [projectID] is **required** — the
     * worktree table is per project, so there is no "all worktrees" call.
     */
    @GET("api/worktree")
    suspend fun listWorktrees(
        @Query("projectID") projectID: String,
    ): List<WorktreeDirectory>

    /** `worktree.create`. Runs the project's setup script, so it is confirmed before it is sent. */
    @POST("api/worktree")
    suspend fun createWorktree(
        @Body body: WorktreeCreateRequest,
    ): WorktreeDirectory

    /**
     * `worktree.remove`, on a `DELETE` with a body.
     *
     * A `400` carries a [WorktreeFailure] whose `forceRequired` is true when the worktree has
     * uncommitted work; the client asks again with `force` and a confirmation rather than sending it
     * the first time.
     */
    @HTTP(method = "DELETE", path = "api/worktree", hasBody = true)
    suspend fun removeWorktree(
        @Body body: WorktreeRemoveRequest,
    ): Unit

    /** `worktree.refresh`: rediscovers and reconciles a project's worktrees. `204`. */
    @POST("api/worktree/refresh")
    suspend fun refreshWorktrees(
        @Body body: WorktreeRefreshRequest,
    ): Unit

    // -------------------------------------------------- session move, project settings, shells

    /**
     * `session.move`: moves a session to another directory, which is how a session is moved into a
     * worktree. `204`, and `session.moved` carries the new location.
     */
    @POST("api/session/{sessionID}/move")
    suspend fun moveSession(
        @Path("sessionID") sessionID: String,
        @Body body: SessionMoveRequest,
    ): Unit

    /** `project.update`: name, icon, start command and the canonical checkout. */
    @PATCH("api/project/{projectID}")
    suspend fun updateProject(
        @Path("projectID") projectID: String,
        @Body body: ProjectUpdateRequest,
    ): Project

    /**
     * `config.shell`: the shells the server found on the machine.
     *
     * Not location-scoped and not wrapped: the answer is a bare array, and it is the same list for
     * every location, because it describes the server's `PATH` rather than a checkout.
     */
    @GET("api/config/shell")
    suspend fun listShellOptions(): List<ShellOption>

    // --------------------------------- Phase 8: providers, integrations, MCP, plugins, web search
    //
    // Twenty-seven operations in five areas. Three things about them are the same across all of them
    // and are worth stating once:
    //
    //  - **Every one that touches a location takes `location[directory]`**, because a login made in
    //    one checkout has to be visible from the other. The only exceptions are the credential
    //    writes, which are keyed by credential id alone.
    //  - **The writes are not idempotent and a retry is visible.** `integration.oauth.connect` starts
    //    a *new* attempt, and `plugin.update` re-downloads, so a caller that retries on a timeout can
    //    end up with two attempts or two installs. The stores therefore treat a transport failure as
    //    "unknown", not as "not done", and the UI re-reads rather than assuming.
    //  - **`experimental.mcp.*` are behind capability detection** (plan §4.2), and
    //    `experimental.integration.wellknown` behind the same. A `404` there is the probe's answer, not
    //    a failure, which is why [ActionErrorKind.NOT_FOUND] is what the capability policy reads.

    // ------------------------------------------------------------------ integrations (12)

    /** `integration.list`: every integration, its methods and its existing connections. */
    @GET("api/integration")
    suspend fun listIntegrations(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<IntegrationInfo>>

    /** `integration.get`: one integration, with the same shape as its entry in the list. */
    @GET("api/integration/{integrationID}")
    suspend fun getIntegration(
        @Path("integrationID") integrationID: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<IntegrationInfo>

    /**
     * `integration.connect.key`: stores an API key, plus the method's form answers and a label.
     *
     * [ConnectKeyRequest.key] is a [Secret], which serializes to the plain string the wire needs
     * while making the value impossible to print by accident on the way there.
     *
     * `204`. The new credential appears through `integration.updated` and `credential.updated`.
     */
    @POST("api/integration/{integrationID}/connect/key")
    suspend fun connectWithKey(
        @Path("integrationID") integrationID: String,
        @Body body: ConnectKeyRequest,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    /**
     * `integration.oauth.connect`: starts an OAuth attempt and answers with the URL to open.
     *
     * **The URL is server input and the app navigates to it**, so
     * [dev.opencode.android.core.model.OAuthAttempt.safeUrl] — not the raw `url` — is what a
     * Custom Tab is given. [IntegrationMethod.OAuth.id] is the `methodID`.
     */
    @POST("api/integration/{integrationID}/connect/oauth")
    suspend fun connectWithOauth(
        @Path("integrationID") integrationID: String,
        @Body body: ConnectOAuthRequest,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<OAuthAttempt>

    /**
     * `integration.oauth.status`: the attempt's state.
     *
     * **Polled, and never inferred from the redirect** (plan §4.2: the server is authoritative).
     * A Custom Tab returning to the app says nothing about whether the provider granted access; only
     * this route does, and it is the only thing that ends the `mode=auto` flow.
     */
    @GET("api/integration/{integrationID}/connect/oauth/{attemptID}")
    suspend fun getOauthAttemptStatus(
        @Path("integrationID") integrationID: String,
        @Path("attemptID") attemptID: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<OAuthAttemptStatus>

    /**
     * `integration.oauth.complete`: submits a device code for a `mode=code` attempt.
     *
     * [code] is `null` for a `mode=auto` attempt that the server itself finishes, which the schema
     * allows and which the device-code screen does not use.
     */
    @POST("api/integration/{integrationID}/connect/oauth/{attemptID}/complete")
    suspend fun completeOauthAttempt(
        @Path("integrationID") integrationID: String,
        @Path("attemptID") attemptID: String,
        @Body body: ConnectOAuthCompleteRequest,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    /** `integration.oauth.cancel`: abandons an attempt. `204`. Available throughout the flow. */
    @DELETE("api/integration/{integrationID}/connect/oauth/{attemptID}")
    suspend fun cancelOauthAttempt(
        @Path("integrationID") integrationID: String,
        @Path("attemptID") attemptID: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    /**
     * `integration.command.connect`: starts a login command on the *host*.
     *
     * The command runs on the user's machine, so the client shows it before sending this and the
     * output is polled from [getCommandAttemptStatus].
     */
    @POST("api/integration/{integrationID}/connect/command")
    suspend fun connectWithCommand(
        @Path("integrationID") integrationID: String,
        @Body body: ConnectCommandRequest,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<CommandAttempt>

    /**
     * `integration.command.status`: the attempt's state and, while pending, the output so far.
     *
     * There is no separate output route, so the message on a `pending` status *is* the stream; the
     * poller accumulates it because a device-code login prints a URL and then waits.
     */
    @GET("api/integration/{integrationID}/connect/command/{attemptID}")
    suspend fun getCommandAttemptStatus(
        @Path("integrationID") integrationID: String,
        @Path("attemptID") attemptID: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<CommandAttemptStatus>

    /** `integration.command.cancel`: stops a running login command. `204`. */
    @DELETE("api/integration/{integrationID}/connect/command/{attemptID}")
    suspend fun cancelCommandAttempt(
        @Path("integrationID") integrationID: String,
        @Path("attemptID") attemptID: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    /**
     * `experimental.integration.wellknown`: adds an integration source by URL.
     *
     * Experimental and behind a switch: it makes the *server* fetch a URL the user typed, so the app
     * validates the scheme with
     * [dev.opencode.android.core.model.WellknownSourceUrl] before sending and a `404` is the
     * capability probe's answer rather than a failure.
     */
    @POST("api/experimental/integration/wellknown")
    suspend fun addWellknownIntegration(
        @Body body: WellknownSourceRequest,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    // ------------------------------------------------------------------ credentials (3)

    /**
     * `credential.update`: renames a stored credential.
     *
     * Not location-scoped, because the credential id is global. A rename is a `PATCH` with a body
     * and no `204` in the spec, so [Unit] is what the client waits on.
     */
    @PATCH("api/credential/{credentialID}")
    suspend fun updateCredential(
        @Path("credentialID") credentialID: String,
        @Body body: CredentialUpdateRequest,
    ): Unit

    /**
     * `credential.remove`: logs out, which makes the integration unusable on this server.
     *
     * **A destructive action that needs confirmation** (plan §5.2 names "removing a credential"
     * explicitly). The server gives no way to undo it and no way to recover the key, so the UI holds
     * the target until the user answers.
     */
    @DELETE("api/credential/{credentialID}")
    suspend fun removeCredential(
        @Path("credentialID") credentialID: String,
    ): Unit

    /**
     * `credential.activate`: switches the active account for an integration.
     *
     * The newest login becomes active by default, so this is how an *older* credential is made the
     * one in use. Also a two-step action in the UI: it changes which account every request uses, and
     * a mistap silently sends the next turn to the wrong account.
     */
    @POST("api/credential/{credentialID}/activate")
    suspend fun activateCredential(
        @Path("credentialID") credentialID: String,
    ): Unit

    // ------------------------------------------------------------------ providers (2)

    /** `provider.list`. `503` while the catalog is still settling, which is not a failure. */
    @GET("api/provider")
    suspend fun listProviders(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<ProviderInfo>>

    /** `provider.get`. */
    @GET("api/provider/{providerID}")
    suspend fun getProvider(
        @Path("providerID") providerID: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<ProviderInfo>

    // ------------------------------------------------------------------ MCP (6)

    /** `mcp.list`: the configured servers and their status. */
    @GET("api/mcp")
    suspend fun listMcpServers(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<McpServer>>

    /**
     * `mcp.resource.catalog`: the resources and templates every connected server publishes.
     *
     * Invalidated by `mcp.resources.changed {server}`, and a server that is not connected has no
     * entry rather than an empty one — which is what tells the browser to say why.
     */
    @GET("api/mcp/resource")
    suspend fun getMcpResourceCatalog(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<McpResourceCatalog>

    /**
     * `experimental.mcp.add`: adds or replaces a server for this run only.
     *
     * A `PUT`, so it is idempotent by server name, and it does not survive a restart — the UI says
     * so rather than implying the server is now configured.
     */
    @PUT("api/experimental/mcp/{server}")
    suspend fun putMcpServer(
        @Path("server") server: String,
        @Body body: McpAddRequest,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    /** `experimental.mcp.remove`: drops a runtime server until restart. `204`. */
    @DELETE("api/experimental/mcp/{server}")
    suspend fun removeMcpServer(
        @Path("server") server: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    /** `experimental.mcp.connect`: connects a server now, overriding `disabled` until restart. */
    @POST("api/experimental/mcp/{server}/connect")
    suspend fun connectMcpServer(
        @Path("server") server: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    /** `experimental.mcp.disconnect`: the other half, and the reason `mcp.status.changed` exists. */
    @POST("api/experimental/mcp/{server}/disconnect")
    suspend fun disconnectMcpServer(
        @Path("server") server: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    // ------------------------------------------------------------------ plugins (3)

    /** `plugin.list`: the enabled plugins, with their source, features and state. */
    @GET("api/plugin")
    suspend fun listPlugins(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<PluginInfo>>

    /**
     * `plugin.check`: asks the registry whether a newer version exists.
     *
     * [PluginCheckRequest.target] is `null` for every plugin, and the answer is the *whole* list
     * with the `outdated` flags refreshed — so the client replaces its list rather than merging one
     * row, because a plugin that stopped being outdated has no event to say so.
     */
    @POST("api/plugin/check")
    suspend fun checkPlugins(
        @Body body: PluginCheckRequest,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<PluginInfo>>

    /**
     * `plugin.update`: installs newer versions of the named packages.
     *
     * `503` when the registry is unreachable, which the UI distinguishes from "up to date" — a
     * check that failed is not a check that found nothing. Non-idempotent: a retry re-downloads.
     */
    @POST("api/plugin/update")
    suspend fun updatePlugins(
        @Body body: PluginUpdateRequest,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    // ------------------------------------------------------------------ web search (2)

    /**
     * `websearch.providers`: the providers a search could go through.
     *
     * `503` while none is configured, which is the state the plan's "web search is off" is really in.
     */
    @GET("api/websearch/provider")
    suspend fun listWebSearchProviders(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<List<WebSearchProviderInfo>>

    /**
     * `websearch.query`: runs a search, for the "test this provider" screen.
     *
     * [WebSearchQueryRequest.providerID] is `null` for the server's configured default, and the
     * answer names the provider that actually served it in
     * [dev.opencode.android.core.model.WebSearchResponse.providerID] — so the screen shows what ran
     * rather than what was asked for.
     */
    @POST("api/websearch")
    suspend fun queryWebSearch(
        @Body body: WebSearchQueryRequest,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): LocationScoped<WebSearchResponse>

    // -------------------------------------- Phase 9: configuration, permissions and maintenance

    // ------------------------------------------------------------------ config (4 operations)

    /**
     * `config.get`: the configuration documents and discovery sources for a location, **from lowest
     * to highest priority** (features doc §33.3).
     *
     * **The order in the answer is the precedence order**, and it is the only place that order comes
     * from: the client does not sort these by path, because a project file has to beat the global one
     * and a path sort would not produce that. An entry is either a parsed `document` (with its own
     * cumulative `info`) or a `directory` the server searched and found nothing in, and the second
     * kind is kept because "your key is not taking effect because the file is in the wrong place" is
     * exactly what a `directory` entry explains.
     */
    @GET("api/config")
    suspend fun getConfig(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): List<ConfigEntry>

    /**
     * `experimental.config.update`: `{"shell": "…"}`.
     *
     * **`shell` is the only key this route accepts** (`Config.Patch` is `additionalProperties:
     * false` with `shell` required), and it writes the **global** configuration rather than the
     * location's. That asymmetry is why it is a separate method from the file editor and why its
     * confirmation names the global file: a user editing their project's file would not expect a
     * button to change the file every project on the box reads.
     */
    @PATCH("api/experimental/config")
    suspend fun updateConfig(
        @Body body: ConfigPatchRequest,
    ): Unit

    /**
     * `location.reload`: shuts down and rebuilds every loaded location.
     *
     * `204` once all replacement builds settle, and `503` while a location cannot be rebuilt. It
     * emits `location.shutdown` per location, which is the event the client uses to drop and then
     * re-read its caches — so the response arriving means the rebuild is done and the next read is
     * the new configuration rather than the old one.
     */
    @POST("api/location/reload")
    suspend fun reloadLocations(): Unit

    // ------------------------------------------------------------------ saved permissions (2)

    /**
     * `permission.saved.list`: the standing approvals, optionally for one project.
     *
     * Each row is one remembered `(projectID, action, resource)` decision, so removing one makes the
     * server ask again for that exact action — a privilege change in the other direction, which is
     * why the removal is confirmed (plan §5.2).
     */
    @GET("api/permission/saved")
    suspend fun listSavedPermissions(
        @Query("projectID") projectID: String? = null,
    ): DataResponse<List<SavedPermission>>

    /** `permission.saved.remove`: forgets one standing approval. `204`. */
    @DELETE("api/permission/saved/{id}")
    suspend fun removeSavedPermission(
        @Path("id") id: String,
    ): Unit

    // ------------------------------------------------------------------ session instructions (3)

    /**
     * `experimental.session.instructions.entry.list`: the durable instruction entries of one session.
     *
     * "Durable" is the operative word (features doc §26): an entry is not a chat message, it is a
     * key and value attached to the session and announced as updates at the next step boundary, so it
     * survives the turn it was added in.
     */
    @GET("api/experimental/session/{sessionID}/instructions/entries")
    suspend fun listInstructionEntries(
        @Path("sessionID") sessionID: String,
    ): DataResponse<List<InstructionEntry>>

    /**
     * `experimental.session.instructions.entry.put`: attaches or replaces one entry. `204`.
     *
     * The value is sent as a **JSON string** and the server parses it, which is the only way a phone
     * can enter a value of any JSON type: the spec's body is `{"value": …}` with no type, and a
     * client that could only send strings could not write the object or array entries that make an
     * entry useful. A `413` comes back as
     * [dev.opencode.android.core.model.ApiError.InstructionEntryValueTooLarge].
     */
    @PUT("api/experimental/session/{sessionID}/instructions/entries/{key}")
    suspend fun putInstructionEntry(
        @Path("sessionID") sessionID: String,
        @Path("key") key: String,
        @Body body: InstructionEntryRequest,
    ): Unit

    /** `experimental.session.instructions.entry.remove`: drops one entry. `204`. */
    @DELETE("api/experimental/session/{sessionID}/instructions/entries/{key}")
    suspend fun removeInstructionEntry(
        @Path("sessionID") sessionID: String,
        @Path("key") key: String,
    ): Unit

    // ------------------------------------------------------------------ loaded locations (2)

    /**
     * `debug.location.list`: the locations the server currently holds cached services for.
     *
     * **Not location-scoped**, which is the point: it is the one route that answers "what is this
     * server holding right now", so it cannot take a location.
     */
    @GET("api/debug/location")
    suspend fun listLoadedLocations(): List<LoadedLocation>

    /**
     * `debug.location.evict`: drops one location's cached services so its next use boots them fresh.
     *
     * **Disruptive but recoverable.** Anything cached for that location is rebuilt on next use, so
     * nothing is lost — but the location's running work is interrupted, which is why the confirmation
     * names the directory rather than the action.
     */
    @DELETE("api/debug/location")
    suspend fun evictLocation(
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): Unit

    // ------------------------------------------------------------------ migration (1)

    /**
     * `experimental.migration.v1.status`: the V1-to-V2 session-history migration.
     *
     * `running` is the only state with progress and its `numerator`/`denominator` are individually
     * nullable, so a screen must show the label alone rather than a `0/0` it would have invented.
     */
    @GET("api/experimental/migration/v1")
    suspend fun getMigrationStatus(): MigrationStatus

    // ----------------------- Phase 10: insights, extensibility, adaptive UI and release

    // ------------------------------------------------------------------ usage (1 operation)

    /**
     * `experimental.session.stats`: usage for a range, which is what `opencode stats` prints
     * (features doc §4.2).
     *
     * **Every parameter is optional and the defaults are the server's, not the client's.** With no
     * arguments the server picks its own range and every project, and sending an invented default
     * would silently show a different window than the CLI does for the same question. The caller
     * sends what the user picked and nothing else.
     *
     * The daily buckets in [SessionStats.activity] are cut in [timezone], so a dashboard that
     * re-buckets them locally would disagree with the server about where a day ends.
     */
    @GET("api/experimental/session/stats")
    suspend fun getSessionStats(
        @Query("from") from: Long? = null,
        @Query("to") to: Long? = null,
        @Query("project") project: String? = null,
        @Query("timezone") timezone: String? = null,
        @Query("tools") tools: String? = null,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
    ): DataResponse<SessionStats>

    // ------------------------------------------------------------------ extensibility (5)

    /**
     * `experimental.session.wait`: blocks until the session is idle, then answers `204`.
     *
     * **This is a long request with no body and no progress.** A server that is waiting on the
     * agent holds the socket open for as long as the turn takes, which is why the caller has to
     * treat it as cancellable and must never issue it on the main dispatcher: a timeout that is
     * not the caller's decision produces a UI that says "waiting" with no way to stop it.
     * `503` means the server itself will not wait, not that the session is broken.
     */
    @POST("api/experimental/session/{sessionID}/wait")
    suspend fun waitForSession(
        @Path("sessionID") sessionID: String,
    ): Unit

    /**
     * `session.synthetic`: injects text the user did not type, and the inbox item it becomes
     * (features doc §4.2).
     *
     * The answer is read because a caller that has just enqueued synthetic input needs to know it
     * was accepted: a `409` on a reused `id` means a previous attempt landed, which is the whole
     * point of sending a client-generated one.
     */
    @POST("api/session/{sessionID}/synthetic")
    suspend fun addSyntheticInput(
        @Path("sessionID") sessionID: String,
        @Body body: SyntheticInputRequest,
    ): DataResponse<SyntheticInputResult>

    /**
     * `session.permission.create`: raises a permission request from the client.
     *
     * **Gates the agent the same way a tool's request does.** The `effect` in the answer is what
     * decides whether there is anything to ask the user: a request the standing approvals already
     * cover comes back `allow`, and offering a choice the user cannot make is worse than saying
     * the permission is already granted.
     */
    @POST("api/session/{sessionID}/permission")
    suspend fun createPermissionRequest(
        @Path("sessionID") sessionID: String,
        @Body body: CreatePermissionRequest,
    ): DataResponse<CreatePermissionResult>

    /**
     * `session.form.create`: raises a form from the client, which renders through the same form
     * engine as a `question` tool call or an MCP elicitation.
     */
    @POST("api/session/{sessionID}/form")
    suspend fun createSessionForm(
        @Path("sessionID") sessionID: String,
        @Body body: CreateFormRequest,
    ): DataResponse<FormInfo>

    /**
     * `rpc.call`: dispatches a method to the plugin registered at [rpcID] (features doc §19).
     *
     * **The security list is empty and the body and answer are arbitrary JSON.** No plugin method
     * is enumerated here, so the console that calls this is a developer tool: the input is raw JSON
     * the user typed, and the output is raw JSON the server sent. Nothing in either direction may
     * be assumed to be a particular shape.
     */
    @POST("api/rpc/{rpcID}/{method}")
    suspend fun callRpc(
        @Path("rpcID") rpcID: String,
        @Path("method") method: String,
        @Query(LocationParam.QUERY_KEY) directory: String? = null,
        @Body body: RpcRequest,
    ): JsonObject

    // ------------------------------------------------------------------ quick ask (1)

    /**
     * `experimental.generate.text`: one stateless completion, with no session and no history
     * (features doc §8.1).
     *
     * `503` is the interesting failure: no provider is configured or reachable. That is a setup
     * problem on the server, and telling the user "try again" would be wrong.
     */
    @POST("api/experimental/generate")
    suspend fun generateText(
        @Body body: GenerateTextRequest,
    ): DataResponse<GenerateTextResult>

    // ------------------------------------------------------------------ session log (1)

    /**
     * `session.log`: the session's durable event log, as an SSE stream (features doc §4.2).
     *
     * **The durable log is what makes a gap-free resync possible.** The global stream is live-only
     * and drops whatever arrived while the phone was away, so after a reconnect the client knows
     * which events it missed but not what they were; `after` replays from a sequence number and
     * `follow=true` keeps the stream open. The stream ends with `log.synced {seq}`.
     *
     * The body is a raw stream rather than a decoded type because the log item's own shape is not
     * in the spec: `data` is declared as a JSON *string*. [SessionLogClient] parses the frames and
     * hands back the JSON without claiming a schema for it.
     */
    @Streaming
    @GET("api/experimental/session/{sessionID}/log")
    suspend fun readSessionLog(
        @Path("sessionID") sessionID: String,
        @Query("after") after: String? = null,
        @Query("follow") follow: String? = null,
    ): ResponseBody

    // ------------------------------------------------------------------ pair another device (1)

    /**
     * `POST /api/pair`: a one-time code another device redeems at `/auth/connect/{code}`.
     *
     * **The route is not in the published spec.** It exists in the server source and is absent from
     * `openapi.json`, so it may be removed in any 2.0.x release; the caller gates it on capability
     * detection and a `404` or `405` hides it rather than surfacing an error. It is outside the
     * pairing flow the app depends on, which redeems a code from the CLI.
     */
    @POST("api/pair")
    suspend fun createPairingCode(): PairingCode
}
