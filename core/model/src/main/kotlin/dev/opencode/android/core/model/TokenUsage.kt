package dev.opencode.android.core.model

import kotlinx.serialization.Serializable

/** Token counts (schema `TokenUsage.Info`). */
@Serializable
data class TokenUsage(
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cache: Cache,
) {
    @Serializable
    data class Cache(
        val read: Long,
        val write: Long,
    )

    val total: Long get() = input + output + reasoning + cache.read + cache.write

    companion object {
        val Zero = TokenUsage(0, 0, 0, Cache(0, 0))
    }
}
