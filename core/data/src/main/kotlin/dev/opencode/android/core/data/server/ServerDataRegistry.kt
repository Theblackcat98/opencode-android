package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.connection.ServerConnection
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binds the read model to whichever server the app is following (plan §4.2).
 *
 * There is one [ServerDataSet] per server, built when that server becomes active and kept for as
 * long as the process lives, so switching servers and coming back does not throw away the session
 * list, the open timelines or the catalogs. Each set gets its own coroutine scope, which is
 * cancelled with the set; a stopped server therefore stops applying events rather than leaking a
 * collector per switch.
 *
 * The set is fed by a single [EventDispatcher] per server. The SSE reader only enqueues, and one
 * dispatcher applies a frame's worth of events at a time, so the cost of a busy turn is bounded by
 * the frame rate and not by the number of frames the server sent.
 */
@Singleton
class ServerDataRegistry @Inject constructor(
    private val connectionManager: ServerConnectionManager,
    private val serverRepository: ServerRepository,
    private val apiFactory: ServerApiFactory,
    private val cache: ReadCacheStore,
    private val selfCheck: TimelineSelfCheck,
    private val schema: ConfigSchema,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sets = LinkedHashMap<String, ServerDataSet>()
    private val bindings = HashMap<String, Binding>()
    private val scopes = HashMap<String, CoroutineScope>()

    private val _active = MutableStateFlow<ServerDataSet?>(null)

    /** The read model of the server being followed, or `null` before one is chosen. */
    val active: StateFlow<ServerDataSet?> = _active.asStateFlow()

    init {
        appScope.launch {
            connectionManager.activeConnection.collect { connection ->
                _active.value = connection?.let { dataSetFor(it) }
                _active.value?.start()
            }
        }
    }

    /** The set of one server, if it has been built. */
    fun dataSetFor(serverId: String): ServerDataSet? = sets[serverId]

    /**
     * The set of one server, built if it is not there yet.
     *
     * A notification action can name a server the app is not following: the user answers from the
     * shade while the phone is on another server's home. The write only needs an API bound to that
     * address — the confirmation of the write arrives on the stream of whichever server *is* active,
     * and the notification is cancelled by the action's own success — so the set is built unbound and
     * binds to events later, when that server becomes active.
     */
    suspend fun ensureDataSet(serverId: String): ServerDataSet? {
        sets[serverId]?.let { return it }
        val profile = serverRepository.getServer(serverId) ?: return null
        val scope = newScope(profile.id)
        return ServerDataSet(
            serverId = profile.id,
            api = apiFactory.createForReads(profile.baseUrl, profile.trustUserCertificates),
            scope = scope,
            cache = cache,
            selfCheck = selfCheck,
            schema = schema,
        ).also { sets[profile.id] = it }
    }

    private suspend fun dataSetFor(connection: ServerConnection): ServerDataSet {
        sets[connection.serverProfile.id]?.let { existing ->
            bind(existing, connection)
            return existing
        }
        val profile = serverRepository.getServer(connection.serverProfile.id) ?: connection.serverProfile
        val scope = newScope(profile.id)
        val set = ServerDataSet(
            serverId = profile.id,
            api = apiFactory.createForReads(profile.baseUrl, profile.trustUserCertificates),
            scope = scope,
            cache = cache,
            selfCheck = selfCheck,
            schema = schema,
        )
        sets[profile.id] = set
        bind(set, connection)
        return set
    }

    /**
     * Attaches a data set to a connection.
     *
     * Rebinding cancels the previous collectors. That matters after a re-pair, where a new
     * [ServerConnection] replaces the old one: without the cancel, the old connection's stream
     * would keep feeding a dispatcher nothing reads, and its queue would grow for as long as the
     * app runs.
     */
    private fun bind(set: ServerDataSet, connection: ServerConnection) {
        if (bindings[set.serverId]?.connection === connection) return
        bindings.remove(set.serverId)?.jobs?.forEach { it.cancel() }
        val dispatcher = EventDispatcher(appScope)
        val jobs = listOf(
            appScope.launch { dispatcher.batches.collect { batch -> batch.forEach(set::apply) } },
            appScope.launch { connection.events.collect { event -> dispatcher.submit(event) } },
            appScope.launch { connection.resyncSignal.collect { set.resync() } },
        )
        bindings[set.serverId] = Binding(connection, jobs)
    }

    /** Forgets a removed server's read model, its scope and its stream. */
    fun forget(serverId: String) {
        sets.remove(serverId)?.clear()
        bindings.remove(serverId)?.jobs?.forEach { it.cancel() }
        // The set's collectors live in its own scope, so this is what actually stops them. Without
        // it a removed server would keep a `stateIn` and its event application alive for the life
        // of the process.
        scopes.remove(serverId)?.cancel()
        if (_active.value?.serverId == serverId) _active.value = null
    }

    private fun newScope(serverId: String): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes[serverId] = it }

    private data class Binding(
        val connection: ServerConnection,
        val jobs: List<kotlinx.coroutines.Job>,
    )
}
