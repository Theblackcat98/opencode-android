package dev.opencode.android.core.data.connection

import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.network.NetworkConnectivityMonitor
import dev.opencode.android.core.network.ServerCredentialCache
import dev.opencode.android.core.network.ServerTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns one [ServerConnection] per server and remembers which one is in the foreground.
 *
 * Connections are cached, so moving between the registry and a server's status page reuses the
 * same socket instead of reconnecting. A cached connection is dropped when the profile's address
 * or its user-CA setting changes, because both change the client that has to be built.
 */
@Singleton
class ServerConnectionManager @Inject constructor(
    private val serverRepository: ServerRepository,
    private val credentialCache: ServerCredentialCache,
    private val serverTls: ServerTls,
    private val okHttpClient: OkHttpClient,
    private val connectivityMonitor: NetworkConnectivityMonitor,
) {
    private val connections = ConcurrentHashMap<String, ServerConnection>()

    private val _activeConnection = MutableStateFlow<ServerConnection?>(null)

    /** The server whose event stream the app is currently following. P2 binds its stores to this. */
    val activeConnection: StateFlow<ServerConnection?> = _activeConnection.asStateFlow()

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
        ).also { connections[profile.id] = it }
    }

    /** Starts streaming for [profile] and makes it the active connection. */
    fun connectServer(profile: ServerProfile, scope: CoroutineScope): ServerConnection {
        val connection = getOrCreateConnection(profile)
        connection.start(scope)
        _activeConnection.value = connection
        return connection
    }

    /** Switches the active server, starting its stream if it is not streaming yet. */
    fun setActiveServer(serverId: String, scope: CoroutineScope) {
        scope.launch {
            val profile = serverRepository.getServer(serverId) ?: return@launch
            connectServer(profile, scope)
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
