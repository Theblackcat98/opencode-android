package dev.opencode.android.core.network

import dev.opencode.android.core.model.PairingSession
import dev.opencode.android.core.model.ServerInfo
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Tag

/**
 * The Phase 1 server surface: `server.info` and the pairing redemption route.
 *
 * Instances are per server; [ServerApiFactory] builds one bound to a base URL, an optional
 * `location[directory]` and an optional credential. `event.subscribe` (`GET /api/event`) is not
 * here because the SSE reader needs the raw response stream and full control over timeouts; see
 * [EventStreamClient] and [EVENT_PATH].
 */
interface ServerApi {

    /**
     * `GET /api/info`: version, process id, the URLs the server is reachable at, and its temp path.
     */
    @GET("api/info")
    suspend fun getServerInfo(@Tag credential: ServerAuthCredential? = null): ServerInfo

    /**
     * `GET /auth/connect/{code}` with `Accept: application/json`, which exchanges a one-time
     * pairing code for a 30-day session token. Needs no authentication (features doc §2.3).
     */
    @Headers("Accept: application/json")
    @GET("auth/connect/{code}")
    suspend fun redeemPairingCode(@Path("code") code: String): PairingSession
}
