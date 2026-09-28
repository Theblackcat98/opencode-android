package dev.opencode.android.core.data.connection

import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.network.ConnectionLogEntry
import dev.opencode.android.core.network.ConnectionState
import dev.opencode.android.core.network.DisconnectCause
import dev.opencode.android.core.network.EventStreamClient
import dev.opencode.android.core.network.InspectedEvent
import dev.opencode.android.core.network.NetworkConnectivityMonitor
import dev.opencode.android.core.network.ServerCredentialCache
import dev.opencode.android.core.network.ServerTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One live connection to one OpenCode server (plan §4.2).
 *
 * It owns the [EventStreamClient] and mirrors its [ConnectionState] into the registry's health dot,
 * so the two can never disagree about whether a server is connected. The credential is read from
 * the Keystore-backed store on every attempt, so a re-pair takes effect on the next reconnect
 * without recreating the connection.
 *
 * The resync signal, the event flow and the inspected-event buffer are exposed unchanged, because
 * that is the whole contract P2 stores build on.
 */
class ServerConnection(
    val serverProfile: ServerProfile,
    private val serverRepository: ServerRepository,
    private val credentialCache: ServerCredentialCache,
    private val serverTls: ServerTls,
    okHttpClient: OkHttpClient,
    /** The scope the stream and the health mirror run in. Owned by [ServerConnectionManager]. */
    private val scope: CoroutineScope,
    connectivityMonitor: NetworkConnectivityMonitor? = null,
) {
    val client: EventStreamClient = EventStreamClient(
        baseUrl = serverProfile.baseUrl,
        credentialProvider = { serverRepository.getCredential(serverProfile.id) },
        okHttpClient = serverTls.clientFor(serverProfile.trustUserCertificates),
        connectivityMonitor = connectivityMonitor,
    )

    val connectionState: StateFlow<ConnectionState> = client.state
    val connectionLogs: StateFlow<List<ConnectionLogEntry>> = client.logs
    val inspectedEvents: StateFlow<List<InspectedEvent>> = client.inspectedEvents
    val events: SharedFlow<Event> = client.events

    /** Fires once per `server.connected`; P2 stores re-read their state over REST on it. */
    val resyncSignal: SharedFlow<Unit> = client.resyncSignals

    /** How many resyncs have fired, so a late subscriber can tell it missed one. */
    val resyncCount: StateFlow<Long> = client.resyncCount

    private val started = AtomicBoolean(false)

    /**
     * Starts the event stream. Calling it again while running only asks for an immediate retry, so
     * every screen that opens a server can call it without coordinating with the others.
     */
    fun start() {
        if (started.compareAndSet(false, true)) {
            scope.launch { primeCredentialThenMirrorHealth() }
        }
        client.start(scope)
    }

    fun stop() {
        client.stop()
    }

    /**
     * Follows the app lifecycle: the stream runs in the foreground only in this phase (plan §6).
     * The app's own lifecycle is observed by [ServerConnectionManager]; a screen calls this only
     * when it wants the connection paused for a reason of its own.
     */
    fun setForeground(visible: Boolean) = client.setForeground(visible)

    /** Drops the socket and reconnects now, for example after a re-pair. */
    fun reconnectNow() = client.reconnectNow()

    /**
     * Puts the stored credential into the shared cache so the REST calls of later phases
     * authenticate the same way the event stream does, then mirrors the connection state.
     */
    private suspend fun primeCredentialThenMirrorHealth() {
        credentialCache.put(serverProfile.baseUrl, serverRepository.getCredential(serverProfile.id))
        connectionState.collect { state ->
            if (state is ConnectionState.Disconnected && state.cause == DisconnectCause.AUTHORIZATION_REQUIRED) {
                // A rejected token must not keep authenticating other requests for this server.
                credentialCache.forget(serverProfile.baseUrl)
            }
            serverRepository.updateHealth(serverProfile.id, state.toServerHealth())
            if (state is ConnectionState.Connected) {
                serverRepository.updateLastSeen(serverProfile.id, System.currentTimeMillis())
            }
        }
    }

    private fun ConnectionState.toServerHealth(): ServerHealth = when (this) {
        is ConnectionState.Connected -> ServerHealth.CONNECTED
        is ConnectionState.Connecting -> ServerHealth.CONNECTING
        is ConnectionState.Suspended -> ServerHealth.DISCONNECTED
        is ConnectionState.Idle -> ServerHealth.DISCONNECTED
        // A rejected credential is the one failure a retry cannot fix, so it is its own state: the
        // registry turns it into a "pair again" action.
        is ConnectionState.Disconnected -> when {
            cause == DisconnectCause.AUTHORIZATION_REQUIRED -> ServerHealth.REAUTH_REQUIRED
            willRetry -> ServerHealth.OFFLINE
            else -> ServerHealth.DISCONNECTED
        }
    }
}
