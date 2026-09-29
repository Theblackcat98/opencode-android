package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.integrations.IntegrationSurface
import dev.opencode.android.core.data.timeline.TimelineDivergence
import dev.opencode.android.core.data.execution.ExecutionSurface
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.LocationInfo
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.event.InstallationUpdateAvailable
import dev.opencode.android.core.model.event.FilesystemChanged
import dev.opencode.android.core.model.event.InstallationUpdated
import dev.opencode.android.core.model.event.ProjectUpdated
import dev.opencode.android.core.model.event.PersistentPtyAdded
import dev.opencode.android.core.model.event.PersistentPtyRemoved
import dev.opencode.android.core.model.event.PtyCreated
import dev.opencode.android.core.model.event.PtyDeleted
import dev.opencode.android.core.model.event.PtyExited
import dev.opencode.android.core.model.event.PtyUpdated
import dev.opencode.android.core.model.event.CredentialSwitched
import dev.opencode.android.core.model.event.McpResourcesChanged
import dev.opencode.android.core.model.event.McpStatusChanged
import dev.opencode.android.core.model.event.ShellCreated
import dev.opencode.android.core.model.event.ShellDeleted
import dev.opencode.android.core.model.event.ShellExited
import dev.opencode.android.core.model.event.WorktreeResolved
import dev.opencode.android.core.model.event.WorktreeUpdated
import dev.opencode.android.core.model.event.SessionRevertCleared
import dev.opencode.android.core.model.event.SessionRevertCommitted
import dev.opencode.android.core.model.event.SessionRevertStaged
import dev.opencode.android.core.model.event.VcsBranchUpdated
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.data.sync.ResourceKey
import dev.opencode.android.core.data.sync.SyncedResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything one server's read path needs: the session list, the open timelines, the projects, and
 * the location catalogs (plan §4.2).
 *
 * The set owns the event application order, which is the part that has to be right:
 *
 *  - **Catalogs first, then sessions, then timelines.** A `session.created` names an agent and a
 *    model, and the row that renders them is built from a catalog that may not have loaded yet. The
 *    catalogs are keyed by `(server, directory)` and a directory-scoped event only invalidates the
 *    entry it names, so a burst of `agent.updated` costs one request per directory.
 *  - **The resync signal is a plain invalidation of everything.** `server.connected` means the
 *    client has no idea what it missed, so nothing is patched: the active sessions, the open
 *    timelines, the catalogs and the projects are all re-read (plan §4.2).
 *  - **`location.shutdown` drops that location's caches**, and nothing else: one location going
 *    away must not empty a screen showing another.
 */
class ServerDataSet(
    val serverId: String,
    private val api: ServerApi,
    private val scope: CoroutineScope,
    private val cache: ReadCacheStore,
    private val selfCheck: TimelineSelfCheck = TimelineSelfCheck.Noop,
) {    /** `project.list`. Not location-scoped: one list for the whole server. */
    val projects: SyncedResource<List<Project>> = SyncedResource(
        key = ResourceKey(serverId),
        name = "project.list",
        scope = scope,
        loader = { api.listProjects() },
    )

    val sessions = SessionStore(serverId, api, scope, cache)

    /** Everything the agent is blocked on, across every session (permissions and forms). */
    val requests: RequestCenter = RequestCenter(api, scope)

    /**
     * The write side of a session.
     *
     * Built with a lookup into this set's timelines so a prompt can show its own pending item before
     * the server confirms it; that is the only optimistic state the plan allows (plan §4.2).
     */
    val commands: SessionCommands = SessionCommands(api, timeline = { timelines[it] })

    /** `fs.list`: the directory browser behind "pick a location" for a new session. */
    val browser: DirectoryBrowser = DirectoryBrowser(api, scope)

    /**
     * The Phase 6 review surface: scopes, parsed diffs, the review position, the VCS header, the
     * file browser and the comments waiting for the next prompt.
     *
     * One review per server, not per location, because the "Last turn" scope is scoped to a
     * *session* while the other three are scoped to a *directory*, and switching between them is a
     * change of scope rather than a change of checkout. The VCS header and the file browser are the
     * two pieces that are per-location, and they get a store per directory the moment a review
     * names one.
     */
    val review: ReviewStore = ReviewStore(serverId, api)

    /**
     * The Phase 7 execution surface: shells and terminals per location, worktrees per project, and a
     * session's persistent terminals.
     *
     * The stores are created per location rather than held as one list, because the server scopes
     * `shell.list` and `pty.list` by `location[directory]` and `worktree.list` by a *required*
     * `projectID`; a store that was not keyed the same way as the route would answer a screen with
     * the wrong checkout's commands, and killing one of those kills the wrong process.
     */
    val execution: ExecutionSurface = ExecutionSurface(serverId, api, scope)

    /** `session.revert.*`, `session.fork` and `session.diff`: the undo/redo/history operations. */
    val revertCommands: RevertCommands = RevertCommands(api, timeline = { timelines[it] })

    /**
     * The Phase 8 surface: integrations and their credentials, providers, MCP servers, plugins and
     * web-search providers, plus every write the plan's tables name.
     *
     * **One object for all five areas, because they are one lifecycle.** An OAuth login is started
     * from an integration, an MCP server in `needs_auth` points back at an integration, and both
     * invalidate the same catalog on `integration.updated`. Splitting them would mean the MCP panel
     * and the connect screen each held half the answer to "is this provider logged in", and the
     * honest question — which credentials exist for this checkout — has exactly one source.
     */
    val integrations: IntegrationSurface = IntegrationSurface(serverId, api, scope)

    init {
        review.reverts = revertCommands
    }

    /** The comments, the metadata a prompt carries, and the experiment switches. */
    val reviewComments get() = review.comments

    private val vcsStores = ConcurrentHashMap<String, VcsStore>()

    /** The repository header and the base-branch picker, per directory. */
    fun vcs(directory: String): VcsStore = vcsStores.getOrPut(directory) { VcsStore(serverId, directory, api) }

    /** `fs.read`, `fs.find` and the file browser behind the file viewer. */
    val files: FileReader = FileReader(api)

    /**
     * The composer's catalogs: `command.list`, `skill.list`, `reference.list` and the `fs.find`
     * search behind `@` completion.
     *
     * They are location-scoped and read once, so they are [SyncedResource]s like the agents and
     * models, and `command.updated`, `skill.updated` and `reference.updated` invalidate them.
     */
    val composerCatalogs: ComposerCatalogs = ComposerCatalogs(serverId, api, scope)

    /**
     * The server's own version, seeded from `GET /api/info` and kept current by the
     * `installation.*` events. Phase 4 turns an announced update into a notification.
     */
    val installation: InstallationState = InstallationState(seed = {
        api.getServerInfo().version
    })

    private val locations = ConcurrentHashMap<String, SyncedResource<LocationInfo>>()
    private val agents = ConcurrentHashMap<String, SyncedResource<List<AgentInfo>>>()
    private val models = ConcurrentHashMap<String, SyncedResource<List<ModelInfo>>>()
    private val defaultModels = ConcurrentHashMap<String, SyncedResource<ModelInfo?>>()
    private val timelines = ConcurrentHashMap<String, TimelineStore>()

    private val _openTimelines = MutableStateFlow<List<TimelineStore>>(emptyList())

    /** The timelines that are open, so a resync knows which ones to re-read. */
    val openTimelines: StateFlow<List<TimelineStore>> = _openTimelines.asStateFlow()

    /** `location.get` for a directory: the location and the project it belongs to. */
    fun location(directory: String): SyncedResource<LocationInfo> = locations
        .getOrPut(directory) {
            SyncedResource(
                key = ResourceKey(serverId, directory),
                name = "location.get($directory)",
                scope = scope,
                loader = { api.getLocation(directory) },
            )
        }

    /** `agent.list` for a directory: names and colors for the chips and the picker. */
    fun agents(directory: String): SyncedResource<List<AgentInfo>> = agents
        .getOrPut(directory) {
            SyncedResource(
                key = ResourceKey(serverId, directory),
                name = "agent.list($directory)",
                scope = scope,
                loader = { api.listAgents(directory).data },
            )
        }

    /** `model.list` for a directory: names and context limits for the header's gauge. */
    fun models(directory: String): SyncedResource<List<ModelInfo>> = models
        .getOrPut(directory) {
            SyncedResource(
                key = ResourceKey(serverId, directory),
                name = "model.list($directory)",
                scope = scope,
                loader = { api.listModels(directory).data },
            )
        }

    /** `model.default` for a directory, which Phase 3 uses as a session's starting model. */
    fun defaultModel(directory: String): SyncedResource<ModelInfo?> = defaultModels
        .getOrPut(directory) {
            SyncedResource(
                key = ResourceKey(serverId, directory),
                name = "model.default($directory)",
                scope = scope,
                loader = { api.getDefaultModel(directory).data },
            )
        }

    /** Opens (or returns) the timeline of a session and starts loading it. */
    fun timeline(sessionID: String): TimelineStore = timelines.getOrPut(sessionID) {
        TimelineStore(serverId, sessionID, api, scope, cache, selfCheck)
    }.also { _openTimelines.value = timelines.values.toList() }

    /** Closes a timeline, releasing its messages. */
    fun closeTimeline(sessionID: String) {
        timelines.remove(sessionID)?.clear()
        _openTimelines.value = timelines.values.toList()
    }

    fun start() {
        scope.launch { projects.sync() }
        scope.launch { installation.start() }
        sessions.start()
    }

    /**
     * Re-reads everything after a `server.connected`.
     *
     * This is the only place that knows a reconnect happened, and it deliberately does not patch
     * anything: the client has no record of what it missed, so the answer is the server's. The
     * pending requests and forms are re-read per location, because both lists are location-scoped
     * and one location going away must not empty the others.
     */
    fun resync() {
        projects.invalidate()
        sessions.resync()
        locations.values.forEach { it.invalidate() }
        agents.values.forEach { it.invalidate() }
        models.values.forEach { it.invalidate() }
        defaultModels.values.forEach { it.invalidate() }
        timelines.values.forEach { it.resync() }
        composerCatalogs.resync()
        execution.resync()
        integrations.resync()
        val directories = (locations.keys + requests.knownDirectories).toSet()
        if (directories.isEmpty()) {
            scope.launch { requests.resync(null) }
        } else {
            directories.forEach { directory -> scope.launch { requests.resync(directory) } }
        }
    }

    /** Applies one event, in the order the stores need. */
    fun apply(event: Event) {
        val payload = event.payload
        when (payload) {
            is ProjectUpdated -> projects.invalidate()
            is EventPayload.AgentUpdated -> invalidateFor(event) { agents(it) }
            is EventPayload.ModelUpdated -> invalidateFor(event) { models(it) }
            is EventPayload.ModelsDevRefreshed -> {
                models.values.forEach { it.invalidate() }
                defaultModels.values.forEach { it.invalidate() }
            }

            // The three file-based catalogs the composer completes from. All three events carry an
            // empty payload, so the location in the envelope is the only thing to act on, and an
            // event without one means every location the client has open.
            is EventPayload.CommandUpdated, is EventPayload.SkillUpdated, is EventPayload.ReferenceUpdated ->
                composerCatalogs.invalidate(event.location?.directory)

            is EventPayload.LocationShutdown -> {
                val directory = event.location?.directory ?: return
                locations.remove(directory)?.clear()
                agents.remove(directory)?.clear()
                models.remove(directory)?.clear()
                defaultModels.remove(directory)?.clear()
                requests.dropLocation(directory)
                composerCatalogs.dropLocation(directory)
                execution.dropLocation(directory)
                integrations.dropLocation(directory)
                scope.launch { runCatching { cache.dropLocation(serverId, directory) } }
            }

            // The Phase 7 execution surface: the per-location shell and terminal lists, the per-project
            // worktrees, and a session's persistent terminals. Every one of them is applied by the
            // surface, which knows which store a location-scoped event belongs to — a shell of one
            // checkout must not appear in another checkout's panel.
            is ShellCreated, is ShellExited, is ShellDeleted,
            is PtyCreated, is PtyUpdated, is PtyExited, is PtyDeleted,
            is PersistentPtyAdded, is PersistentPtyRemoved,
            is WorktreeUpdated, is WorktreeResolved,
            -> execution.apply(event)

            is InstallationUpdated, is InstallationUpdateAvailable -> installation.apply(event)

            // The Phase 8 catalogs. All of these events are empty-payload except the two MCP ones, so
            // the location in the envelope is the only thing to act on and an event without one
            // invalidates every open directory — the same rule the agent and model catalogs use.
            is EventPayload.IntegrationUpdated, is EventPayload.CredentialUpdated,
            is EventPayload.ProviderUpdated, is EventPayload.WebsearchUpdated,
            is EventPayload.PluginUpdated, is McpStatusChanged, is McpResourcesChanged,
            is CredentialSwitched,
            -> integrations.apply(event)

            // ------------------------------------------------------------------ Phase 6: review
            //
            // `vcs.branch.updated {branch}` moves the header, and the header is the branch *and* the
            // changed files, so both are re-read: a checkout that switched branches has a different
            // working copy, and a header that kept the old file list would describe a tree that no
            // longer exists. The event names a branch, not a directory, so every open directory is
            // asked — one request each, and only for the directories a review has actually opened.
            is VcsBranchUpdated -> vcsStores.values.forEach { store ->
                store.applyBranchUpdated(payload.branch)
                scope.launch { store.refresh() }
            }

            // `filesystem.changed {file, event}` is the live refresh for the file viewer and the
            // browser. The event names one file and no location, so the file's own path decides
            // which open directory it belongs to, and only that one is re-listed.
            is FilesystemChanged -> {
                vcsStores.keys.forEach { directory ->
                    if (VcsStore.directoryFilter(directory)(payload.file)) {
                        scope.launch { files.list(directory, files.state.value.path) }
                    }
                }
            }

            is SessionRevertStaged -> revertCommands.applyStaged(payload.revert)
            is SessionRevertCleared, is SessionRevertCommitted -> revertCommands.applyStaged(null)

            else -> Unit
        }
        requests.apply(event)
        sessions.apply(event)
        val scoped = payload as? EventPayload.SessionScoped ?: return
        timelines[scoped.sessionID]?.apply(event)
    }

    /**
     * Invalidates the catalogs an event names.
     *
     * A location-scoped event invalidates only its own directory. An event without one (the server
     * only sends those for server-wide changes) invalidates every directory the client has open.
     */
    private inline fun invalidateFor(event: Event, select: (String) -> SyncedResource<*>) {
        val directory = event.location?.directory
        if (directory != null) {
            select(directory).invalidate()
        } else {
            val directories = (agents.keys + models.keys).distinct()
            directories.forEach { select(it).invalidate() }
        }
    }

    /** Compares every open timeline with the server and reports the differences. */
    suspend fun runSelfCheck(): Map<String, List<TimelineDivergence>> = buildMap {
        timelines.forEach { (id, store) -> store.verifyAgainstServer().takeIf { it.isNotEmpty() }?.let { put(id, it) } }
    }

    /** Forgets everything, for example when the server is removed. */
    fun clear() {
        sessions.clear()
        requests.clear()
        installation.clear()
        timelines.values.forEach { it.clear() }
        timelines.clear()
        _openTimelines.value = emptyList()
        locations.clear()
        agents.clear()
        models.clear()
        defaultModels.clear()
        projects.clear()
        composerCatalogs.clear()
        integrations.clear()
        vcsStores.clear()
        files.clear()
        browser.clear()
        execution.clear()
    }
}
