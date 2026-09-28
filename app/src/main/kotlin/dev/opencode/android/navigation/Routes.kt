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
 * The per-server home: projects, what is running, and the recent sessions.
 *
 * [serverId] is optional so a notification or a shared link can land here and let the connection
 * manager pick the server it is already following.
 */
@Serializable
data class HomeRoute(val serverId: String? = null)

/**
 * The session list, with [projectId] narrowing it to one project.
 *
 * The list screen is reachable from the home's "All sessions" and from a project, and in both cases
 * it is the same list with a different filter, so the filter is a route argument rather than a
 * screen.
 */
@Serializable
data class SessionListRoute(
    val serverId: String? = null,
    val projectId: String? = null,
)

/** One session's timeline. */
@Serializable
data class SessionRoute(val serverId: String? = null, val sessionId: String)
