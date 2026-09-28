package dev.opencode.android.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation routes for OpenCode Android. */

/** The server registry, and the app's start destination in Phase 1. */
@Serializable
data object ServersRoute

/**
 * Adding a server, with an optional pairing link.
 *
 * [initialUrl] is what a shared link or the `http(s)://…/auth/connect/<code>` deep link delivers,
 * so both arrive on the paste tab already filled in. [replaceServerId] turns the screen into a
 * re-pair: the profile is kept and only its credential is replaced, which is the recovery path
 * after a password or token is rotated on the server.
 */
@Serializable
data class AddServerRoute(
    val initialUrl: String? = null,
    val replaceServerId: String? = null,
)

@Serializable
data class ServerStatusRoute(val serverId: String)

@Serializable
data class EditServerRoute(val serverId: String)

@Serializable
data class EventInspectorRoute(val serverId: String? = null)

/**
 * The per-server home.
 *
 * Phase 2 gives this a session list; in Phase 1 it is registered so a link into it already
 * resolves, and it shows the registry.
 */
@Serializable
data class HomeRoute(val serverId: String? = null)
