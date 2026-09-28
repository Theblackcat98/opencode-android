package dev.opencode.android.core.model

import kotlinx.serialization.Serializable

/** A model selection (schema `Model.Ref`). */
@Serializable
data class ModelRef(
    val id: String,
    val providerID: String,
    /** A model variant, such as a reasoning effort. */
    val variant: String? = null,
) {
    /** `provider/model` or `provider/model#variant`, the format config files use. */
    override fun toString(): String = buildString {
        append(providerID).append('/').append(id)
        variant?.let { append('#').append(it) }
    }
}
