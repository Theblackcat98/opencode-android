package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.connection.ServerConnection
import dev.opencode.android.core.data.connection.ServerConnectionManager
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sets = LinkedHashMap<String, ServerDataSet>()
    private val bound = HashMap<String, ServerConnection>()

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

    private suspend fun dataSetFor(connection: ServerConnection): ServerDataSet {
        sets[connection.serverProfile.id]?.let { existing ->
            bind(existing, connection)
            return existing
        }
        val profile = serverRepository.getServer(connection.serverProfile.id) ?: connection.serverProfile
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val set = ServerDataSet(
            serverId = profile.id,
            api = apiFactory.createForReads(profile.baseUrl, profile.trustUserCertificates),
            scope = scope,
            cache = cache,
            selfCheck = selfCheck,
        )
        sets[profile.id] = set
        bind(set, connection)
        return set
    }

    private fun bind(set: ServerDataSet, connection: ServerConnection) {
        if (bound[set.serverId] === connection) return
        bound[set.serverId] = connection
        val dispatcher = EventDispatcher(appScope)
        appScope.launch {
            dispatcher.batches.collect { batch ->
                batch.forEach(set::apply)
            }
        }
        appScope.launch {
            connection.events.collect { event -> dispatcher.submit(event) }
        }
        appScope.launch {
            connection.resyncSignal.collect { set.resync() }
        }
    }

    /** Forgets a removed server's read model. */
    fun forget(serverId: String) {
        sets.remove(serverId)?.clear()
        bound.remove(serverId)
        if (_active.value?.serverId == serverId) _active.value = null
    }
}
