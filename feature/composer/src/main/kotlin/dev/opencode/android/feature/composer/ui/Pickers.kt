package dev.opencode.android.feature.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.catalog.AgentCatalog
import dev.opencode.android.core.designsystem.format.Formatters
import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.feature.composer.R

/**
 * The agent picker: the agents a user may select, with their colors and descriptions
 * (features doc §7).
 *
 * The dot is the agent's own color, parsed once here. A color the device cannot parse falls back to
 * the theme's primary rather than to nothing: a row with no dot would read as "no color set" when in
 * fact the server set one this phone cannot read.
 */
@Composable
fun AgentPicker(
    agents: List<AgentInfo>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (agents.isEmpty()) {
        Text(
            text = stringResource(R.string.agent_no_selectable),
            modifier = modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(agents, key = { it.id }) { agent ->
            val label = AgentCatalog.label(agent)
            val description = agent.description ?: stringResource(R.string.agent_no_description)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = agent.id == selected, onClick = { onSelect(agent.id) })
                    .padding(vertical = 8.dp, horizontal = 12.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$label. $description"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "●",
                    color = agentColor(agent),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 12.dp),
                )
                RadioButton(selected = agent.id == selected, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The color an agent declares, or the theme's primary when it declares none this client can read. */
@Composable
fun agentColor(agent: AgentInfo): Color = agent.color
    ?.let { hex -> runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull() }
    ?: MaterialTheme.colorScheme.primary

/** One model row: its name, its capability badges, its context size, its price and its variants. */
@Composable
fun ModelRow(
    name: String,
    ref: ModelRef,
    supportsTools: Boolean,
    supportsImages: Boolean,
    contextTokens: Long,
    inputCost: Double,
    outputCost: Double,
    selected: Boolean,
    isFavorite: Boolean,
    variants: List<String>,
    activeVariant: String?,
    onSelect: () -> Unit,
    onSelectVariant: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                modifier = Modifier.semantics(mergeDescendants = true) {},
            )
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    text = ref.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val pin = stringResource(R.string.model_favorite_toggle)
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.semantics { contentDescription = pin },
            ) {
                Checkbox(checked = isFavorite, onCheckedChange = null)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (supportsTools) Badge(stringResource(R.string.model_capability_tools))
            if (supportsImages) Badge(stringResource(R.string.model_capability_image))
            Badge(stringResource(R.string.model_context, Formatters.tokens(contextTokens)))
            Badge(
                stringResource(
                    R.string.model_cost,
                    Formatters.cost(inputCost),
                    Formatters.cost(outputCost),
                ),
            )
        }
        if (variants.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                variants.forEach { variant ->
                    FilterChip(
                        selected = variant == activeVariant,
                        onClick = { onSelectVariant(variant) },
                        label = { Text(stringResource(R.string.model_variant, variant)) },
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

/** A capability or price label. Its own text is the content description TalkBack reads. */
@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = text },
    )
}

/** A section header, used for Favorites, Recents and each provider. */
@Composable
fun ModelSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The picker's empty state, which is also how a server with no provider is explained. */
@Composable
fun ModelEmptyState(modifier: Modifier = Modifier, query: String = "") {
    Text(
        // With a search typed, the list is empty because of the filter, not because the server has no
        // provider, and "connect a provider" sends the user to fix something that is not broken.
        text = if (query.isBlank()) {
            stringResource(R.string.model_empty)
        } else {
            stringResource(R.string.model_no_match, query.trim())
        },
        modifier = modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
