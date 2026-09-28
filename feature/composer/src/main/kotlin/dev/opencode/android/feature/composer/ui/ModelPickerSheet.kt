package dev.opencode.android.feature.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.catalog.ModelCatalog
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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.model_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                label = { Text(stringResource(R.string.model_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            )
            if (groups.isEmpty()) {
                ModelEmptyState()
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

/** A standalone variant chooser, for the model picker when a model has many variants. */
@Composable
fun VariantRow(
    variants: List<String>,
    active: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        variants.forEach { variant ->
            FilterChip(
                selected = variant == active,
                onClick = { onSelect(variant) },
                label = { Text(stringResource(R.string.model_variant, variant)) },
            )
        }
    }
}
