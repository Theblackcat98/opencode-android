package dev.opencode.android.core.network

import dev.opencode.android.core.model.ServerInfo
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** One address that answered the OpenCode signature. */
data class LanFound(val host: String, val port: Int) {
    /** The address in the form the add-server form takes. */
    val url: String get() = "http://$host:$port"
}

/**
 * Looks for OpenCode servers on the addresses it is given. The interface exists so the add-server
 * screen's view model can be tested without a network; [LanProber] is the only implementation.
 */
interface LanScanner {
    /**
     * Emits each address that answers the signature. [onProbed] runs once per address tried, found or
     * not, so a caller can show progress. Collecting the flow runs the probes; cancelling the
     * collection cancels the requests still in flight.
     */
    fun scan(
        hosts: List<String>,
        ports: List<Int>,
        concurrency: Int = LanProber.DEFAULT_CONCURRENCY,
        timeoutMs: Long = LanProber.DEFAULT_TIMEOUT_MS,
        onProbed: () -> Unit = {},
    ): Flow<LanFound>
}

/**
 * The opt-in LAN probe (plan Phase 10). It asks each address whether an OpenCode server answers
 * `GET /api/info` on a port, and reports the ones that do.
 *
 * Nothing is sent but that one unauthenticated request. The client is derived from the shared
 * [OkHttpClient] and takes no interceptors, so no credential is attached; it never follows a
 * redirect, because a redirect would send the request somewhere other than the address that was
 * asked. The requests run in parallel on a dispatcher of their own, so a scan does not queue behind
 * the app's own calls or share their per-host limits.
 */
class LanProber(private val okHttpClient: OkHttpClient) : LanScanner {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun scan(
        hosts: List<String>,
        ports: List<Int>,
        concurrency: Int,
        timeoutMs: Long,
        onProbed: () -> Unit,
    ): Flow<LanFound> {
        require(concurrency >= 1) { "A scan needs at least one request in flight" }
        require(timeoutMs > 0) { "A probe needs a positive timeout" }
        val client = probeClient(concurrency, timeoutMs)
        val targets = hosts.flatMap { host -> ports.map { port -> host to port } }
        return targets.asFlow().flatMapMerge(concurrency) { (host, port) ->
            flow {
                val found = probe(client, host, port)
                onProbed()
                if (found) emit(LanFound(host, port))
            }
        }
    }

    private fun probeClient(concurrency: Int, timeoutMs: Long): OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = concurrency
                maxRequestsPerHost = concurrency
            },
        )
        .build()

    /**
     * One probe. The coroutine is suspended on the call rather than blocking a thread, and
     * cancelling the coroutine cancels the call, so a stopped scan does not leave requests running.
     */
    private suspend fun probe(client: OkHttpClient, host: String, port: Int): Boolean {
        val url = HttpUrl.Builder()
            .scheme("http")
            .host(host)
            .port(port)
            .addPathSegment("api")
            .addPathSegment("info")
            .build()
        val pending = client.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { pending.cancel() }
            pending.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    val matched = try {
                        response.use { fingerprint(it.code, it.peekBody(MAX_BODY_BYTES).string()) }
                    } catch (e: IOException) {
                        false
                    }
                    if (continuation.isActive) continuation.resume(matched)
                }
            })
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 800L
        const val DEFAULT_CONCURRENCY = 32

        /** The two ports the plan names. A user can add more on the screen. */
        val DEFAULT_PORTS: List<Int> = listOf(49374, 4096)

        /** The only network the prober scans: the /24 around the phone's own address. */
        private const val SCAN_PREFIX = 24

        /** Enough for the largest signature body; a response longer than this is not read in full. */
        private const val MAX_BODY_BYTES = 65_536L

        private val UNAUTHORIZED_TAG = Regex("\"_tag\"\\s*:\\s*\"UnauthorizedError\"")

        /**
         * True when [ip] is an IPv4 address in one of the three private ranges of RFC 1918: 10.0.0.0/8,
         * 172.16.0.0/12 and 192.168.0.0/16. Anything else, including an IPv6 address, is false.
         */
        fun isPrivateIpv4(ip: String): Boolean {
            val octets = parseIpv4(ip) ?: return false
            return octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }

        /**
         * The hosts of the /24 around [ip], with the phone's own address left out. The network and
         * broadcast addresses (`.0` and `.255`) are not hosts, so they are left out too: 253 addresses
         * for a phone at `x.y.z.1` to `x.y.z.254`.
         *
         * The scan is always the /24, whatever netmask the phone has. A /16 network is 256 times the
         * size, and scanning it would be a sweep rather than a look around the phone.
         *
         * @throws IllegalArgumentException when [prefix] is not 24, or [ip] is not a private IPv4 address.
         */
        fun hostsOf(ip: String, prefix: Int = SCAN_PREFIX): List<String> {
            require(prefix == SCAN_PREFIX) { "The LAN prober scans a /24 only, not a /$prefix" }
            require(isPrivateIpv4(ip)) { "Only a private IPv4 address (RFC 1918) is scanned" }
            val octets = requireNotNull(parseIpv4(ip))
            val base = "${octets[0]}.${octets[1]}.${octets[2]}."
            return (1..254).filter { it != octets[3] }.map { base + it }
        }

        /**
         * Whether a response to `GET /api/info` is the OpenCode signature. Two shapes count:
         *
         * - a `401` whose body carries the `UnauthorizedError` tag, which is how the server answers a
         *   request that has no credential: `{"_tag":"UnauthorizedError",...}`;
         * - a `200` whose body decodes as [ServerInfo].
         *
         * Any other status is not the signature, and neither is a `401` that only mentions the word.
         */
        fun fingerprint(status: Int, body: String): Boolean = when (status) {
            401 -> UNAUTHORIZED_TAG.containsMatchIn(body)
            200 -> runCatching { OpenCodeJson.decodeFromString(ServerInfo.serializer(), body) }.isSuccess
            else -> false
        }

        /** The address as a 32-bit number, for putting results in network order. Zero when it is not IPv4. */
        fun ipv4Value(ip: String): Long {
            val octets = parseIpv4(ip) ?: return 0L
            return octets.fold(0L) { acc, octet -> acc * 256 + octet }
        }

        /** Four decimal octets with no sign, no spaces and no leading zeros, or null. */
        private fun parseIpv4(ip: String): IntArray? {
            val parts = ip.split('.')
            if (parts.size != 4) return null
            val octets = IntArray(4)
            for ((index, part) in parts.withIndex()) {
                val wellFormed = part.isNotEmpty() &&
                    part.length <= 3 &&
                    part.all { it in '0'..'9' } &&
                    !(part.length > 1 && part[0] == '0')
                if (!wellFormed) return null
                val value = part.toInt()
                if (value > 255) return null
                octets[index] = value
            }
            return octets
        }
    }
}
