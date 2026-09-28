package dev.opencode.android.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Network availability, so the event stream can stop hammering a dead network and resume the
 * moment it comes back instead of waiting out a backoff.
 */
interface NetworkConnectivityMonitor {
    /** Emits `true` while a usable network is available. */
    val isOnline: Flow<Boolean>

    /** The current value, without waiting for the first emission. */
    fun isCurrentlyOnline(): Boolean
}

/** [NetworkConnectivityMonitor] backed by the platform [ConnectivityManager]. */
class AndroidNetworkConnectivityMonitor(
    context: Context,
) : NetworkConnectivityMonitor {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun isCurrentlyOnline(): Boolean = isUsable(connectivityManager?.activeNetwork)

    override val isOnline: Flow<Boolean> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(false)
            close()
            return@callbackFlow
        }

        trySend(isCurrentlyOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isUsable(network))
            }

            override fun onLost(network: Network) {
                trySend(isCurrentlyOnline())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(networkCapabilities.isUsable())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        manager.registerNetworkCallback(request, callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun isUsable(network: Network?): Boolean {
        val manager = connectivityManager ?: return false
        if (network == null) return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.isUsable()
    }

    /**
     * A network counts as usable when it can reach the internet and is not restricted. Validated
     * is deliberately not required: a LAN-only server is still reachable without internet access.
     */
    private fun NetworkCapabilities.isUsable(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
}
