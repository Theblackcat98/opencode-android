package dev.opencode.android.core.model

import kotlinx.serialization.Serializable

/** The `{data}` wrapper most single-resource endpoints use. */
@Serializable
data class DataResponse<T>(val data: T)

/** The `{location, data}` wrapper of location-scoped endpoints (`/api/model`, `/api/agent`, ...). */
@Serializable
data class LocationScoped<T>(
    val location: LocationPublicRef,
    val data: T,
)

/** A cursor-paged list (`/api/session`, `/api/session/{id}/message`). */
@Serializable
data class Paged<T>(
    val data: List<T>,
    val cursor: Cursor,
) {
    @Serializable
    data class Cursor(
        val previous: String? = null,
        val next: String? = null,
    )
}
