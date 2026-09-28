package dev.opencode.android.core.network

import dev.opencode.android.core.model.PairingSession
import dev.opencode.android.core.model.ServerInfo
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * Retrofit interface for OpenCode server connection, pairing, and event endpoints.
 */
interface ServerApi {

    /**
     * `GET /api/info`
     * Returns server version, process ID, reachable interface URLs, and paths.
     */
    @GET("api/info")
    suspend fun getServerInfo(): ServerInfo

    /**
     * `GET /auth/connect/{code}` with `Accept: application/json`
     * Redeems a one-time pairing code for a 30-day session token.
     * Note: Needs no authentication.
     */
    @Headers("Accept: application/json")
    @GET("auth/connect/{code}")
    suspend fun redeemPairingCode(@Path("code") code: String): PairingSession

    /**
     * `GET /api/event`
     * Connects to the global Server-Sent Events stream.
     */
    @Streaming
    @GET("api/event")
    suspend fun streamEvents(): Response<ResponseBody>
}
