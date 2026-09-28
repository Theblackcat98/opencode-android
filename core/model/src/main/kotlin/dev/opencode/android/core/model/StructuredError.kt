package dev.opencode.android.core.model

import kotlinx.serialization.Serializable

/**
 * An error inside the timeline or an event (schema `Session.StructuredError`), for example
 * `{type: "provider.auth", message, status: 403}`.
 */
@Serializable
data class StructuredError(
    val type: String,
    val message: String,
    val status: Int? = null,
)
