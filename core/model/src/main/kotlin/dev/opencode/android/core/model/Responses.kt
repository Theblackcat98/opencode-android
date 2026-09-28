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

/**
 * `POST /api/session/{id}/generate` (schema `SessionGenerateResponse`): the answer to a side
 * question.
 *
 * The route answers with the same `{data}` wrapper as everything else rather than with bare
 * `{text}`, so the client decodes [SessionGenerateResponse] and not [SessionGenerateResult] — a
 * mismatch here would fail on every `/btw`.
 */
@Serializable
data class SessionGenerateResponse(val data: SessionGenerateResult)

/** The text `session.generate` produced. */
@Serializable
data class SessionGenerateResult(val text: String)
