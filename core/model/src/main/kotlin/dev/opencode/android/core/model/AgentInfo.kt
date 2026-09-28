package dev.opencode.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * An agent as `GET /api/agent` returns it (schema `Agent.Info`, features doc §7).
 *
 * Only the fields the client reads are typed: an agent is shown by its name, its color and
 * whether it is primary, subagent or hidden. `request`, `system`, `permissions` and `steps` are
 * carried as raw JSON so that a newer server adding a field there cannot break decoding and no
 * information is lost when a message round-trips through the cache.
 */
@Serializable
data class AgentInfo(
    val id: String,
    val name: String,
    val model: ModelRef? = null,
    val description: String? = null,
    /** `primary`, `subagent` or `all`; compared against [AgentMode] constants. */
    val mode: String = AgentMode.ALL,
    val hidden: Boolean = false,
    val color: String? = null,
    val steps: Int? = null,
    val request: JsonElement? = null,
    val system: String? = null,
    val permissions: JsonElement? = null,
) {
    /** The mode constants, as value classes so a value a newer server adds still decodes. */
    object AgentMode {
        const val PRIMARY = "primary"
        const val SUBAGENT = "subagent"
        const val ALL = "all"
    }

    val isPrimary: Boolean get() = mode == AgentMode.PRIMARY
    val isSubagent: Boolean get() = mode == AgentMode.SUBAGENT
}

/**
 * A model as `GET /api/model` returns it (schema `Model.Info`, features doc §8).
 *
 * P2 needs the name for the model chip and [limit].[Limit.context] for the header's context
 * gauge; the cost tiers and variants are kept because Phase 3's model picker reads them, and they
 * cost nothing to carry through the cache.
 */
@Serializable
data class ModelInfo(
    val id: String,
    val modelID: String,
    val providerID: String,
    val name: String,
    val family: String? = null,
    val canonical: String? = null,
    val capabilities: Capabilities? = null,
    val variants: List<Variant> = emptyList(),
    val cost: List<Cost> = emptyList(),
    /** `alpha`, `beta`, `deprecated` or `active`. */
    val status: String? = null,
    val enabled: Boolean = true,
    val limit: Limit,
    val time: Time? = null,
) {
    @Serializable
    data class Capabilities(
        val tools: Boolean = false,
        val input: List<String> = emptyList(),
        val output: List<String> = emptyList(),
    )

    @Serializable
    data class Variant(val id: String)

    /** A cost tier, in US dollars per million tokens (schema `Model.Cost`). */
    @Serializable
    data class Cost(
        val input: Double = 0.0,
        val output: Double = 0.0,
        val cache: Cache = Cache(),
    ) {
        @Serializable
        data class Cache(val read: Double = 0.0, val write: Double = 0.0)
    }

    /** Context and output limits in tokens. [context] is what the header gauge divides by. */
    @Serializable
    data class Limit(
        val context: Long,
        val input: Long? = null,
        val output: Long = 0,
    )

    @Serializable
    data class Time(val released: Double = 0.0)

    /** `provider/model`, the form the config files and `Model.Ref` use. */
    val ref: String get() = "$providerID/$id"
}
