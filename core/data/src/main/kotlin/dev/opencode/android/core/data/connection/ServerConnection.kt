package dev.opencode.android.core.data.connection

import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.network.ConnectionLogEntry
import dev.opencode.android.core.network.ConnectionState
import dev.opencode.android.core.network.EventStreamClient
import dev.opencode.android.core.network.InspectedEvent
import dev.opencode.android.core.network.NetworkConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Encapsulates an active live connection to an OpenCode server (plan §4.2).
 */
class ServerConnection(
    val serverProfile: ServerProfile,
    private val serverRepository: ServerRepository,
    okHttpClient: OkHttpClient,
    connectivityMonitor: NetworkConnectivityMonitor? = null,
) {
    val client: EventStreamClient = EventStreamClient(
        baseUrl = serverProfile.baseUrl,
        credentialProvider = { serverRepository.getCredential(serverProfile.id) },
        okHttpClient = okHttpClient,
        connectivityMonitor = connectivityMonitor,
    )

    val connectionState: StateFlow<ConnectionState> = client.connectionState
    val resyncSignal: SharedFlow<Unit> = client.resyncSignal
    val events: SharedFlow<Event> = client.events
    val inspectedEvents: StateFlow<List<InspectedEvent>> = client.inspectedEvents
    val connectionLogs: StateFlow<List<ConnectionLogEntry>> = client.connectionLogs

    fun start(scope: CoroutineScope) {
        client.start(scope)
        // Keep repository health in sync with client connection state
        scope.launch {
            connectionState.collect { state ->
                val health = when (state) {
                    is ConnectionState.Connected -> {
                        serverRepository.updateLastSeen(serverProfile.id, System.currentTimeMillis())
                        ServerHealth.CONNECTED
                    }
                    is ConnectionState.Connecting -> ServerHealth.CONNECTING
                    is ConnectionState.Disconnected -> {
                        if (state.reason != null && state.reason != "Client stopped") {
                            ServerHealth.ERROR
                        } else {
                            ServerHealth.DISCONNECTED
                        }
                    }
                }
                serverRepository.updateHealth(serverProfile.id, health)
            }
        }
    }

    fun stop() {
        client.stop()
    }
}
