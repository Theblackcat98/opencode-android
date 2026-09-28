package dev.opencode.android.core.data.connection

import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.network.NetworkConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerConnectionManager @Inject constructor(
    private val serverRepository: ServerRepository,
    private val okHttpClient: OkHttpClient,
    private val connectivityMonitor: NetworkConnectivityMonitor,
) {
    private val connections = ConcurrentHashMap<String, ServerConnection>()
    private val _activeConnection = MutableStateFlow<ServerConnection?>(null)
    val activeConnection: StateFlow<ServerConnection?> = _activeConnection.asStateFlow()

    fun getOrCreateConnection(profile: ServerProfile): ServerConnection {
        return connections.computeIfAbsent(profile.id) {
            ServerConnection(
                serverProfile = profile,
                serverRepository = serverRepository,
                okHttpClient = okHttpClient,
                connectivityMonitor = connectivityMonitor,
            )
        }
    }

    fun getConnection(serverId: String): ServerConnection? {
        return connections[serverId]
    }

    fun connectServer(profile: ServerProfile, scope: CoroutineScope): ServerConnection {
        val conn = getOrCreateConnection(profile)
        conn.start(scope)
        if (_activeConnection.value?.serverProfile?.id == profile.id || _activeConnection.value == null) {
            _activeConnection.value = conn
        }
        return conn
    }

    fun disconnectServer(serverId: String) {
        val conn = connections.remove(serverId)
        conn?.stop()
        if (_activeConnection.value?.serverProfile?.id == serverId) {
            _activeConnection.value = null
        }
    }

    fun setActiveServer(serverId: String, scope: CoroutineScope) {
        scope.launch {
            val profile = serverRepository.getServer(serverId) ?: return@launch
            val conn = connectServer(profile, scope)
            _activeConnection.value = conn
        }
    }
}
