package dev.opencode.android.core.network

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/**
 * The phone's own IPv4 address on the network it is using now. An interface so the add-server
 * screen's view model can be tested without a real network.
 */
interface LocalIpv4Source {
    /** The first non-loopback IPv4 address of the active network, or null when there is none. */
    fun activeIpv4(): String?
}

/**
 * [LocalIpv4Source] read from the active network's [android.net.LinkProperties]. That needs
 * `ACCESS_NETWORK_STATE`, which the app already declares.
 *
 * A VPN that is the active network answers with the VPN's address, so the probe then covers the
 * VPN's /24 rather than the Wi-Fi one. That is the address the phone routes through, and the
 * screen does not hide it.
 */
class AndroidLocalIpv4Source(context: Context) : LocalIpv4Source {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun activeIpv4(): String? {
        val manager = connectivityManager ?: return null
        val network = manager.activeNetwork ?: return null
        val properties = manager.getLinkProperties(network) ?: return null
        return properties.linkAddresses
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}
