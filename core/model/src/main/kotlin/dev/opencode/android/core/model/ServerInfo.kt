package dev.opencode.android.core.model

import kotlinx.serialization.Serializable

/** `GET /api/info` (schema `ServerInfo`). */
@Serializable
data class ServerInfo(
    val version: String,
    val pid: Long,
    /** Every URL the server can be reached at. Lists each non-internal interface when bound to 0.0.0.0. */
    val urls: List<String>,
    val paths: Paths,
) {
    @Serializable
    data class Paths(val tmp: String)

    /** The major version, or `null` when [version] is not `major.minor.patch`-like. */
    val majorVersion: Int? get() = version.substringBefore('.').toIntOrNull()
}

/** `POST /api/pair` (schema `PairingCode`). */
@Serializable
data class PairingCode(
    val code: String,
    @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long,
)

/** `GET /auth/connect/{code}` with `Accept: application/json` (schema `PairingSession`). */
@Serializable
data class PairingSession(val token: String)
