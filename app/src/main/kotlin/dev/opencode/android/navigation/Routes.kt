package dev.opencode.android.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation routes for OpenCode Android. */
@Serializable
data object HomeRoute

@Serializable
data object ServersRoute

@Serializable
data class AddServerRoute(val initialUrl: String? = null)

@Serializable
data class ServerStatusRoute(val serverId: String)

@Serializable
data class EventInspectorRoute(val serverId: String? = null)
