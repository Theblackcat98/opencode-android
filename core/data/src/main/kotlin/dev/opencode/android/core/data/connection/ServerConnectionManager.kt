package dev.opencode.android.core.data.connection

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.network.NetworkConnectivityMonitor
import dev.opencode.android.core.network.ServerCredentialCache
import dev.opencode.android.core.network.ServerTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns one [ServerConnection] per server for as long as the app runs.
 *
 * The manager, not a screen, holds the scope a connection streams in. A connection started from the
 * server list and then followed on the status page would otherwise lose its socket when the list's
 * ViewModel was cleared, and its health dot would freeze on whatever it last reported.
 *
 * The app lifecycle is observed here too: the stream runs in the foreground only in this phase, and
 * Phase 4 moves it into a foreground service behind the same switch.
 */
@Singleton
class ServerConnectionManager @Inject constructor(
    private val serverRepository: ServerRepository,
    private val credentialCache: ServerCredentialCache,
    private val serverTls: ServerTls,
    private val okHttpClient: OkHttpClient,
    private val connectivityMonitor: NetworkConnectivityMonitor,
) : DefaultLifecycleObserver {

    /** As long as the process, so a connection outlives every screen that touched it. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val connections = ConcurrentHashMap<String, ServerConnection>()

    private val _activeConnection = MutableStateFlow<ServerConnection?>(null)

    /** The server whose event stream the app is following. P2 binds its stores to this. */
    val activeConnection: StateFlow<ServerConnection?> = _activeConnection.asStateFlow()

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        connections.values.forEach { it.setForeground(true) }
    }

    override fun onStop(owner: LifecycleOwner) {
        connections.values.forEach { it.setForeground(false) }
    }

    fun getConnection(serverId: String): ServerConnection? = connections[serverId]

    /** The cached connection for [profile], replacing one built for a different address or TLS setting. */
    fun getOrCreateConnection(profile: ServerProfile): ServerConnection {
        val existing = connections[profile.id]
        if (existing != null) {
            val sameTarget = existing.serverProfile.baseUrl == profile.baseUrl &&
                existing.serverProfile.trustUserCertificates == profile.trustUserCertificates
            if (sameTarget) return existing
            connections.remove(profile.id)
            existing.stop()
        }
        return ServerConnection(
            serverProfile = profile,
            serverRepository = serverRepository,
            credentialCache = credentialCache,
            serverTls = serverTls,
            okHttpClient = okHttpClient,
            connectivityMonitor = connectivityMonitor,
            scope = appScope,
        ).also { connections[profile.id] = it }
    }

    /** Starts streaming for [profile] and makes it the active connection. */
    fun connectServer(profile: ServerProfile): ServerConnection {
        val connection = getOrCreateConnection(profile)
        connection.start()
        _activeConnection.value = connection
        return connection
    }

    /** Switches the active server, starting its stream if it is not streaming yet. */
    fun setActiveServer(serverId: String) {
        appScope.launch {
            val profile = serverRepository.getServer(serverId) ?: return@launch
            connectServer(profile)
        }
    }

    /**
     * Stops and forgets a server's connection. Used when a profile is removed, and after a
     * re-pair, where a fresh credential needs a fresh client.
     */
    fun disconnectServer(serverId: String) {
        connections.remove(serverId)?.stop()
        if (_activeConnection.value?.serverProfile?.id == serverId) {
            _activeConnection.value = null
        }
    }

    /** Forgets a server's connection so the next call rebuilds it from the current profile. */
    fun refreshServer(serverId: String) {
        connections.remove(serverId)?.stop()
    }
}
