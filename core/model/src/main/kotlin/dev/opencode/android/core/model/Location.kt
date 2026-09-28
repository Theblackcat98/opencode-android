package dev.opencode.android.core.model

import kotlinx.serialization.Serializable

/** A reference to a location (working directory), as carried by events (schema `LocationRef`). */
@Serializable
data class LocationRef(
    val directory: String,
    val workspaceID: String? = null,
)

/** A location as exposed in REST resources (schema `Location.PublicRef`). */
@Serializable
data class LocationPublicRef(val directory: String)

/** `GET /api/location`: a location and the project it belongs to (schema `Location.PublicInfo`). */
@Serializable
data class LocationInfo(
    val directory: String,
    val project: Project,
) {
    @Serializable
    data class Project(
        val id: String,
        val directory: String,
        val canonical: String,
    )
}
