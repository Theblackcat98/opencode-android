package dev.opencode.android.core.data.catalog

import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.ModelRef

/**
 * The model catalog as the picker shows it (plan §6, Model picker).
 *
 * **Grouped by provider, searched, and annotated.** The grouping is what makes a catalog of a few
 * hundred entries usable on a phone, and the search matches the several things a user might know a
 * model by: its display name, its id, its family and its provider.
 *
 * **Recents and favorites come from the client, not the server** (features doc §8), so they arrive
 * as parameters and are attached here. A favorite the catalog no longer lists is not shown, because
 * a picker row the user cannot select is a lie; the stored value is left alone so that a model which
 * comes back is still pinned.
 *
 * Pure, so the picker's behaviour is unit tested rather than checked by scrolling it.
 */
object ModelCatalog {

    /** One model as a row, with the client-side annotations folded in. */
    data class Entry(
        val info: ModelInfo,
        val isFavorite: Boolean = false,
        val isRecent: Boolean = false,
    ) {
        val ref: ModelRef get() = ModelRef(id = info.id, providerID = info.providerID)
        val supportsTools: Boolean get() = info.capabilities?.tools == true
        val supportsImageInput: Boolean get() = info.capabilities?.input?.contains("image") == true
        val variants: List<ModelInfo.Variant> get() = info.variants

        /** The cheapest tier's price per million tokens, which is what a row can afford to show. */
        val inputCost: Double get() = info.cost.minOfOrNull { it.input } ?: 0.0
        val outputCost: Double get() = info.cost.minOfOrNull { it.output } ?: 0.0
    }

    /** One provider's models, in the order the catalog lists them. */
    data class ProviderGroup(
        val providerID: String,
        val entries: List<Entry>,
    )

    /**
     * The catalog, grouped and filtered.
     *
     * [includeDisabled] is off by default: a disabled model cannot be selected, and offering it would
     * let the user pick something that then fails on the first turn.
     */
    fun group(
        models: List<ModelInfo>,
        favorites: List<ModelRef> = emptyList(),
        recents: List<ModelRef> = emptyList(),
        search: String = "",
        includeDisabled: Boolean = false,
    ): List<ProviderGroup> {
        val pinned = favorites.toSet()
        val term = search.trim()
        return models
            .asSequence()
            .filter { includeDisabled || it.enabled }
            .filter { term.isEmpty() || matches(it, term) }
            .groupBy { it.providerID }
            // Alphabetical by provider, so the group order does not move as the catalog grows.
            .toSortedMap()
            .map { (provider, group) ->
                ProviderGroup(
                    providerID = provider,
                    entries = group.map { model ->
                        val ref = ModelRef(id = model.id, providerID = model.providerID)
                        Entry(
                            info = model,
                            isFavorite = ref in pinned,
                            isRecent = recents.any { it.id == model.id && it.providerID == model.providerID },
                        )
                    },
                )
            }
    }

    /**
     * Whether [model] matches a search term.
     *
     * Four fields, because a user arrives with whichever of them they know: the name from a menu
     * elsewhere, the id from a config file, the family from a model card, or the provider. Matching
     * is case-insensitive and on substrings, so a partial id finds the model.
     */
    fun matches(model: ModelInfo, term: String): Boolean {
        val needle = term.lowercase()
        return model.name.lowercase().contains(needle) ||
            model.id.lowercase().contains(needle) ||
            model.modelID.lowercase().contains(needle) ||
            model.providerID.lowercase().contains(needle) ||
            model.family?.lowercase()?.contains(needle) == true
    }

    /**
     * The variants of a model, with a synthetic `default` when the server declares none.
     *
     * A model whose configuration lists no variants still has one usable value, and offering it as
     * `default` makes the variant control uniform instead of special-cased in the UI.
     */
    fun variantsOf(model: ModelInfo): List<ModelInfo.Variant> =
        model.variants.ifEmpty { listOf(ModelInfo.Variant(DEFAULT_VARIANT)) }

    /**
     * The next variant after [current], for the variant cycle button.
     *
     * Cycling wraps, and a `null` current variant starts at the first declared one, which is the
     * server's own ordering.
     */
    fun nextVariant(model: ModelInfo, current: String?): String? {
        val variants = variantsOf(model).map { it.id }
        if (variants.size <= 1) return variants.firstOrNull()
        val index = variants.indexOf(current)
        return variants[(index + 1) % variants.size]
    }

    /** The next primary agent after [current], for the composer's cycle button. */
    fun nextAgent(agents: List<AgentInfo>, current: String?): AgentInfo? {
        val primary = AgentCatalog.primary(agents)
        if (primary.isEmpty()) return null
        val index = primary.indexOfFirst { it.id == current }
        return primary[(index + 1) % primary.size]
    }

    const val DEFAULT_VARIANT = "default"
}
