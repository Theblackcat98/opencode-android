package dev.opencode.android.feature.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.catalog.ModelCatalog
import dev.opencode.android.core.designsystem.text.SyncedTextField
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.feature.composer.R

/**
 * The model picker: grouped by provider, searchable, with recents and favorites on top
 * (plan §6, Model picker).
 *
 * **The grouping is what makes a catalog of a few hundred entries usable on a phone**, and the
 * capability badges, the context size and the price are on every row because the two questions a user
 * has about a model are "can it do what I need" and "what will it cost". Variants are chips on the row
 * rather than a second level, because a variant is a reasoning effort, not a different model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    groups: List<ModelCatalog.ProviderGroup>,
    selected: ModelRef?,
    search: String,
    onSearchChange: (String) -> Unit,
    onSelect: (ModelRef) -> Unit,
    onSelectVariant: (ModelRef, String) -> Unit,
    onToggleFavorite: (ModelRef) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The models this device pinned and used recently, which the server does not know about
     * (features doc §8). They come first, because they are what the user reached for last time.
     */
    favorites: List<ModelRef> = emptyList(),
    recents: List<ModelRef> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.model_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            SyncedTextField(
                value = search,
                onValueChange = onSearchChange,
                label = { Text(stringResource(R.string.model_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            )
            // Favorites and recents first, and only while a search is not narrowing the catalog:
            // pinning a model is a standing choice, so it should not be a section a search hides.
            if (search.isBlank()) {
                PinnedSection(
                    title = stringResource(R.string.model_favorites),
                    refs = favorites,
                    groups = groups,
                    selected = selected,
                    onSelect = onSelect,
                    onSelectVariant = onSelectVariant,
                    onToggleFavorite = onToggleFavorite,
                )
                PinnedSection(
                    title = stringResource(R.string.model_recents),
                    refs = recents,
                    groups = groups,
                    selected = selected,
                    onSelect = onSelect,
                    onSelectVariant = onSelectVariant,
                    onToggleFavorite = onToggleFavorite,
                )
            }
            if (groups.isEmpty()) {
                ModelEmptyState(query = search)
                return@Column
            }
            groups.forEach { group ->
                ModelSectionHeader(group.providerID)
                group.entries.forEach { entry ->
                    val ref = entry.ref
                    val variants = ModelCatalog.variantsOf(entry.info).map { it.id }
                    ModelRow(
                        name = entry.info.name,
                        ref = ref,
                        supportsTools = entry.supportsTools,
                        supportsImages = entry.supportsImageInput,
                        contextTokens = entry.info.limit.context,
                        inputCost = entry.inputCost,
                        outputCost = entry.outputCost,
                        selected = selected?.id == ref.id && selected.providerID == ref.providerID,
                        isFavorite = entry.isFavorite,
                        variants = variants,
                        activeVariant = selected?.variant,
                        onSelect = { onSelect(ref) },
                        onSelectVariant = { variant -> onSelectVariant(ref, variant) },
                        onToggleFavorite = { onToggleFavorite(ref) },
                    )
                }
            }
        }
    }
}

/**
 * One of the client's own sections: the models it pinned or used recently.
 *
 * A pinned or recent model the current catalog does not list is skipped rather than shown as a
 * broken row, because a picker entry the user cannot select is worse than its absence.
 */
@Composable
private fun PinnedSection(
    title: String,
    refs: List<ModelRef>,
    groups: List<ModelCatalog.ProviderGroup>,
    selected: ModelRef?,
    onSelect: (ModelRef) -> Unit,
    onSelectVariant: (ModelRef, String) -> Unit,
    onToggleFavorite: (ModelRef) -> Unit,
) {
    val entries = groups.asSequence()
        .flatMap { it.entries }
        .filter { entry -> refs.any { it.id == entry.info.id && it.providerID == entry.info.providerID } }
        .toList()
    if (entries.isEmpty()) return
    ModelSectionHeader(title)
    entries.forEach { entry ->
        val ref = entry.ref
        ModelRow(
            name = entry.info.name,
            ref = ref,
            supportsTools = entry.supportsTools,
            supportsImages = entry.supportsImageInput,
            contextTokens = entry.info.limit.context,
            inputCost = entry.inputCost,
            outputCost = entry.outputCost,
            selected = selected?.id == ref.id && selected.providerID == ref.providerID,
            isFavorite = entry.isFavorite,
            variants = ModelCatalog.variantsOf(entry.info).map { it.id },
            activeVariant = selected?.variant,
            onSelect = { onSelect(ref) },
            onSelectVariant = { variant -> onSelectVariant(ref, variant) },
            onToggleFavorite = { onToggleFavorite(ref) },
        )
    }
}

/** The agent picker, as a sheet, which is the only presentation a phone has room for. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPickerSheet(
    agents: List<dev.opencode.android.core.model.AgentInfo>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.agent_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AgentPicker(agents = agents, selected = selected, onSelect = onSelect)
        }
    }
}
