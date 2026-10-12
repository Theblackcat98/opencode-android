package dev.opencode.android.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import dev.opencode.android.core.data.config.ConfigKey
import dev.opencode.android.core.data.config.ConfigRow
import dev.opencode.android.core.data.config.ConfigSource
import dev.opencode.android.core.data.config.WritePlan
import dev.opencode.android.core.designsystem.component.OpenCodeTextButton
import dev.opencode.android.core.designsystem.text.SyncedTextField
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/**
 * The config explorer (plan §6, "Config explorer").
 *
 * **One card per top-level key, in the schema's own order, and no filter on existence.** The rows come
 * from [dev.opencode.android.core.data.config.ConfigExplorer], which walks the vendored schema, so a
 * key nothing sets still has a card that says so — which is what "every top-level config key is
 * visible" means in practice, and what a user needs when a key they set is not taking effect. The
 * "configured only" chip changes what is *visible*, never what the app knows.
 *
 * **A card shows four things and no more: the key, what it is, where it came from, and what the server
 * says it is.** A value is described by shape and redacted by [showValue], because a configuration file
 * holds API keys and this screen is the one most likely to be screenshotted.
 *
 * **Stable keys, from the key's own name.** A `LazyColumn` keyed on the position would rebuild every row
 * when the server's document list changes — which it does on every `config.updated` — and a row that
 * moves under a finger while being read is a row the user cannot act on.
 */
@Composable
fun ConfigScreen(
    state: ConfigUiState,
    onReload: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenDefinitions: () -> Unit,
    onShellChange: (String) -> Unit,
    onShellRequest: () -> Unit,
    onShellConfirm: () -> Unit,
    onShellCancel: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    configuredOnly: Boolean = false,
    onConfiguredOnlyChange: (Boolean) -> Unit = {},
) {
    val spacing = OpenCodeThemeExtras.spacing
    // A pure UI filter: the rows already arrived, so the query lives here in rememberSaveable,
    // not in the ViewModel — the same pattern the P2 list blesses for catalog tab/search state.
    var search by rememberSaveable { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize()) {
        ConfigToolbar(
            state = state,
            onReload = onReload,
            onOpenEditor = onOpenEditor,
            onOpenDefinitions = onOpenDefinitions,
            configuredOnly = configuredOnly,
            onConfiguredOnlyChange = onConfiguredOnlyChange,
        )
        HorizontalDivider()
        // Thirty-six keys in schema order is a list you scan, not one you read: the field narrows
        // it by key name, case-insensitively.
        LabelledField(
            label = stringResource(R.string.admin_config_search),
            value = search,
            onValueChange = { search = it },
            tag = "config:search",
            modifier = Modifier.padding(horizontal = spacing.lg),
        )
        when {
            state.loading && !state.loaded -> Row(
                modifier = Modifier.fillMaxWidth().padding(spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(spacing.xs))
                Text(stringResource(R.string.admin_config_loading))
            }

            else -> LazyColumn(
                modifier = Modifier.weight(1f).testTag(AdminTags.CONFIG_LIST),
            ) {
                // The state's own error, or the one the read produced — a caller that filled in the
                // documents by hand has no reason to set both, and an error row that only appears when
                // two fields agree is a row that appears when it should not.
                val failure = state.error ?: state.documents.failure?.error
                failure?.let { error ->
                    item(key = FIXED_ERROR) { ErrorLine(error = error, onDismiss = onDismissError) }
                }
                item(key = FIXED_SOURCES) { SourceSummary(state = state) }
                item(key = FIXED_SHELL) {
                    ShellCard(state = state, onShellChange = onShellChange, onShellRequest = onShellRequest)
                }
                val rows = if (configuredOnly) state.configuredRows else state.rows
                val visible = if (search.isBlank()) {
                    rows
                } else {
                    val needle = search.trim()
                    rows.filter { row -> row.key.key.contains(needle, ignoreCase = true) }
                }
                items(
                    items = visible,
                    // Namespaced, and keyed on the file's own name rather than the index. The namespace
                    // is load-bearing: `shell` is both one of the schema's keys and the row of the
                    // setting above, and two items in one `LazyColumn` with one key is a crash the
                    // screenshot test found the first time the two were on the same screen.
                    key = { row -> "$KEY_PREFIX${row.key.key}" },
                ) { row ->
                    ConfigKeyCard(row = row)
                }
                if (visible.isEmpty()) {
                    item(key = FIXED_EMPTY) {
                        Text(
                            text = stringResource(R.string.admin_config_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(spacing.lg),
                        )
                    }
                }
            }
        }
    }
    state.shellPlan?.let { plan ->
        WriteConfirmationDialog(plan = plan, onConfirm = onShellConfirm, onCancel = onShellCancel)
    }
}

@Composable
private fun ConfigToolbar(
    state: ConfigUiState,
    onReload: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenDefinitions: () -> Unit,
    configuredOnly: Boolean,
    onConfiguredOnlyChange: (Boolean) -> Unit,
) {
    val spacing = OpenCodeThemeExtras.spacing
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OpenCodeTextButton(onClick = onReload, enabled = !state.loading) {
                Text(stringResource(R.string.admin_reload))
            }
            OpenCodeTextButton(
                onClick = onOpenEditor,
                enabled = state.writesUsable,
            ) { Text(stringResource(R.string.admin_config_editor)) }
            OpenCodeTextButton(onClick = onOpenDefinitions, enabled = state.writesUsable) {
                Text(stringResource(R.string.admin_definitions))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            FilterChip(
                selected = configuredOnly,
                onClick = { onConfiguredOnlyChange(!configuredOnly) },
                label = { Text(stringResource(R.string.admin_configured_only)) },
            )
            Text(
                text = stringResource(R.string.admin_key_count, state.rows.size, state.configuredRows.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The precedence chain, as the server reported it.
 *
 * **A `directory` entry is shown even though it carries no information**, because "the server looked
 * here and found nothing" is the explanation for a key that is not taking effect: the file is in the
 * wrong place, and nothing else on the screen would say so.
 */
@Composable
private fun SourceSummary(state: ConfigUiState) {
    val spacing = OpenCodeThemeExtras.spacing
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm)) {
        SectionHeading(stringResource(R.string.admin_sources))
        if (state.documentsRead.isEmpty() && state.searched.isEmpty()) {
            Text(
                text = stringResource(R.string.admin_sources_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        state.documentsRead.forEach { document ->
            Fact(
                label = stringResource(R.string.admin_source_document),
                value = shortPath(document.pathOrNull ?: stringResource(R.string.admin_source_unnamed)),
                monospace = true,
            )
        }
        state.searched.forEach { directory ->
            Fact(
                label = stringResource(R.string.admin_source_searched),
                value = shortPath(directory),
                monospace = true,
            )
        }
    }
}

/**
 * The shell setting, which is the only configuration value the server will change for us.
 *
 * **The button is disabled unless the switch and the route both say yes**, and the reason is on the row
 * rather than in a snackbar, because a greyed-out control with no explanation is the dead-control
 * defect P8's screens were written to avoid. The reason names the switch by its title, in Experimental
 * features, which is where it is: this card used to say "in settings" about a switch nothing could reach.
 */
@Composable
private fun ShellCard(
    state: ConfigUiState,
    onShellChange: (String) -> Unit,
    onShellRequest: () -> Unit,
) {
    val spacing = OpenCodeThemeExtras.spacing
    AdminCard(
        title = stringResource(R.string.admin_shell_title),
        modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Text(
            text = stringResource(R.string.admin_shell_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SyncedTextField(
            value = state.shellChoice ?: state.currentShell.orEmpty(),
            onValueChange = onShellChange,
            label = { Text(stringResource(R.string.admin_shell_field)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(AdminTags.SHELL_FIELD),
        )
        if (!state.shellUsable) {
            Text(
                // Two different reasons, and only the first is the user's to fix: a switch that is off is
                // turned on in Experimental features, a route the server does not have is not turned on
                // anywhere, and telling that user to flip a switch they already flipped is a circle.
                text = stringResource(
                    if (state.shellAllowed) R.string.admin_shell_absent else R.string.admin_shell_off,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onShellRequest,
            enabled = state.shellUsable &&
                (state.shellChoice ?: "").trim().isNotEmpty() &&
                (state.shellChoice ?: "").trim() != state.currentShell,
        ) { Text(stringResource(R.string.admin_shell_apply)) }
    }
}

/**
 * One top-level key.
 *
 * **The value is described, never printed, unless the key is not a secret.** `provider.*.options.apiKey`
 * and `enterprise` are redacted by [showValue]; everything else is shown as itself, which is the whole
 * point of an explorer.
 */
@Composable
private fun ConfigKeyCard(row: ConfigRow) {
    val spacing = OpenCodeThemeExtras.spacing
    val key = row.key
    AdminCard(
        titleContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = key.key,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.semantics { heading() },
                )
                if (key.hidden) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.admin_key_hidden)) },
                    )
                }
            }
        },
        modifier = Modifier
            .padding(horizontal = spacing.lg, vertical = spacing.xs)
            .testTag(AdminTags.configKey(key.key)),
    ) {
        Text(
            text = key.typeName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.isReadOnly) {
            Text(
                text = stringResource(R.string.admin_key_read_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        key.description?.let { description ->
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
        key.allowedValues.takeIf { it.isNotEmpty() }?.let { allowed ->
            Fact(
                label = stringResource(R.string.admin_key_one_of),
                value = allowed.joinToString(", "),
            )
        }
        EffectiveValue(row = row)
        SourceList(row = row)
    }
}

/**
 * What the server says this key is, and which document it blames.
 *
 * **The two are separated because they answer different questions.** "Set in" is about the file a user
 * edits; "effective" is about what the server computed. They agree for every key the server reports,
 * and where they do not the row says which is which rather than showing a value with no provenance.
 */
@Composable
private fun EffectiveValue(row: ConfigRow) {
    when {
        row.effective != null -> {
            Fact(
                label = stringResource(R.string.admin_key_effective),
                value = showValue(row.key.key, row.effective),
            )
            if (row.isOverridden) {
                Text(
                    text = stringResource(R.string.admin_key_overridden),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        !row.isReported -> Fact(
            label = stringResource(R.string.admin_key_not_reported),
            value = stringResource(R.string.admin_key_not_reported_help),
        )

        else -> Fact(
            label = stringResource(R.string.admin_key_effective),
            value = stringResource(R.string.admin_key_unset),
        )
    }
}

/**
 * Which documents report the key, and which set it.
 *
 * **Both columns, and they are not the same.** `config.get` gives every document a *cumulative*
 * projection, so a document that inherited a value reports it and one that wrote it also reports it. The
 * client reads the nearest file with `fs.read` to tell them apart, and the row says which of the two it
 * knows — an app that showed only "reported by" would make a single file look like three.
 */
@Composable
private fun SourceList(row: ConfigRow) {
    if (row.value.reports.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        row.value.reports.forEach { source ->
            Fact(
                label = stringResource(R.string.admin_key_reported_by),
                value = shortPath(source.path ?: stringResource(R.string.admin_source_unnamed)),
                monospace = true,
            )
        }
        if (row.value.setters.isNotEmpty()) {
            row.value.setters.forEach { source ->
                Fact(
                    label = stringResource(R.string.admin_key_set_by),
                    value = shortPath(source.path ?: stringResource(R.string.admin_source_unnamed)),
                    monospace = true,
                )
            }
        } else if (row.value.reports.size > 1) {
            Text(
                text = stringResource(R.string.admin_key_source_unknown),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A source's path, shortened for a row. */
internal fun ConfigSource.shortLabel(): String = shortPath(path ?: "")

/**
 * The fixed rows' keys, namespaced away from the configuration keys.
 *
 * **`shell` is both a schema key and a setting.** A `LazyColumn` needs its keys to be unique across
 * every item, and using the bare name for both made the screen throw the moment the shell row and the
 * `shell` key card were in the same list — which they always are. Prefixing one side is the whole fix.
 */
private const val KEY_PREFIX = "config:"
private const val FIXED_SOURCES = "config:fixed:sources"
private const val FIXED_SHELL = "config:fixed:shell"
private const val FIXED_ERROR = "config:fixed:error"
private const val FIXED_EMPTY = "config:fixed:empty"
