package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.timeline.TimelineDivergence
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.LocationInfo
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.event.ProjectUpdated
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
) {
    /** `project.list`. Not location-scoped: one list for the whole server. */
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

            is EventPayload.LocationShutdown -> {
                val directory = event.location?.directory ?: return
                locations.remove(directory)?.clear()
                agents.remove(directory)?.clear()
                models.remove(directory)?.clear()
                defaultModels.remove(directory)?.clear()
                requests.dropLocation(directory)
                scope.launch { runCatching { cache.dropLocation(serverId, directory) } }
            }

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
        timelines.values.forEach { it.clear() }
        timelines.clear()
        _openTimelines.value = emptyList()
        locations.clear()
        agents.clear()
        models.clear()
        defaultModels.clear()
        projects.clear()
    }
}
