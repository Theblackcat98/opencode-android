package dev.opencode.android.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.config.DefinitionKind

/**
 * The guided templates sheet (plan §6).
 *
 * **The result is shown, not applied.** The template's merged document and the schema's verdict on it
 * appear here, and only the confirm button moves it into the editor's field. A template that applied
 * itself on selection would be an unconfirmed write to the server's own filesystem, which is the thing
 * plan §5.2 exists to prevent.
 *
 * **A remote MCP URL is checked here as well as at the store.** The server will fetch whatever is sent,
 * so a `file://` or `intent://` would make it read a local file on the user's own machine; the store
 * checks because the store is reachable without this sheet, and the field is greyed out because a user
 * who cannot send one should be told before they type it.
 */
@Composable
fun TemplateSheet(
    draft: ConfigTemplateDraft,
    outcome: dev.opencode.android.core.data.config.TemplateOutcome?,
    onChange: ((ConfigTemplateDraft) -> ConfigTemplateDraft) -> Unit,
    onApply: () -> Unit,
    onInsert: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                text = stringResource(templateLabel(draft.choice)),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (draft.choice) {
                    ConfigTemplateChoice.MODEL -> {
                        LabelledField(
                            label = stringResource(R.string.admin_template_provider),
                            value = draft.provider,
                            onValueChange = { value -> onChange { it.copy(provider = value) } },
                        )
                        LabelledField(
                            label = stringResource(R.string.admin_template_model_name),
                            value = draft.model,
                            onValueChange = { value -> onChange { it.copy(model = value) } },
                            supporting = stringResource(R.string.admin_template_model_help),
                        )
                        LabelledField(
                            label = stringResource(R.string.admin_template_variant),
                            value = draft.variant,
                            onValueChange = { value -> onChange { it.copy(variant = value) } },
                        )
                    }

                    ConfigTemplateChoice.PERMISSION -> {
                        LabelledField(
                            label = stringResource(R.string.admin_template_action),
                            value = draft.action,
                            onValueChange = { value -> onChange { it.copy(action = value) } },
                            supporting = stringResource(R.string.admin_template_action_help),
                        )
                        LabelledField(
                            label = stringResource(R.string.admin_template_resource),
                            value = draft.resource,
                            onValueChange = { value -> onChange { it.copy(resource = value) } },
                            supporting = stringResource(R.string.admin_template_resource_help),
                        )
                        Text(
                            text = stringResource(R.string.admin_template_effect),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        EffectChips(
                            selected = draft.effect,
                            onSelect = { value -> onChange { it.copy(effect = value) } },
                        )
                        Text(
                            text = stringResource(R.string.admin_template_permission_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag(AdminTags.RULES_PRECEDENCE_WARNING),
                        )
                    }

                    ConfigTemplateChoice.AGENT -> {
                        LabelledField(
                            label = stringResource(R.string.admin_template_agent_name),
                            value = draft.name,
                            onValueChange = { value -> onChange { it.copy(name = value) } },
                        )
                        LabelledField(
                            label = stringResource(R.string.admin_template_model_ref),
                            value = draft.model,
                            onValueChange = { value -> onChange { it.copy(model = value) } },
                            supporting = stringResource(R.string.admin_template_model_help),
                        )
                        Text(
                            text = stringResource(R.string.admin_template_mode),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        ModeChips(
                            selected = draft.mode,
                            onSelect = { value -> onChange { it.copy(mode = value) } },
                        )
                    }

                    ConfigTemplateChoice.MCP -> {
                        LabelledField(
                            label = stringResource(R.string.admin_template_mcp_name),
                            value = draft.name,
                            onValueChange = { value -> onChange { it.copy(name = value) } },
                        )
                        Text(text = stringResource(R.string.admin_template_mcp_kind), style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = draft.mcpKind == "remote",
                                onClick = { onChange { it.copy(mcpKind = "remote") } },
                                label = { Text(stringResource(R.string.admin_template_mcp_remote)) },
                            )
                            FilterChip(
                                selected = draft.mcpKind == "local",
                                onClick = { onChange { it.copy(mcpKind = "local") } },
                                label = { Text(stringResource(R.string.admin_template_mcp_local)) },
                            )
                        }
                        if (draft.mcpKind == "remote") {
                            LabelledField(
                                label = stringResource(R.string.admin_template_mcp_url),
                                value = draft.mcpUrl,
                                onValueChange = { value -> onChange { it.copy(mcpUrl = value) } },
                                supporting = stringResource(R.string.admin_template_mcp_url_help),
                                error = if (draft.mcpUrl.isNotBlank() && !urlAllowed(draft.mcpUrl)) {
                                    stringResource(R.string.admin_template_mcp_url_bad)
                                } else {
                                    null
                                },
                            )
                        } else {
                            LabelledField(
                                label = stringResource(R.string.admin_template_mcp_command),
                                value = draft.mcpCommand,
                                onValueChange = { value -> onChange { it.copy(mcpCommand = value) } },
                                supporting = stringResource(R.string.admin_template_mcp_command_help),
                            )
                        }
                    }
                }
                when (outcome) {
                    is dev.opencode.android.core.data.config.TemplateOutcome.Invalid -> {
                        Text(
                            text = stringResource(R.string.admin_template_invalid),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        outcome.problems.forEach { DiagnosticRow(diagnostic = it) }
                    }

                    is dev.opencode.android.core.data.config.TemplateOutcome.NotReady -> Text(
                        text = stringResource(R.string.admin_template_not_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is dev.opencode.android.core.data.config.TemplateOutcome.Ready -> Text(
                        text = stringResource(R.string.admin_template_valid),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    null -> Unit
                }
            }
        },
        confirmButton = {
            val ready = outcome is dev.opencode.android.core.data.config.TemplateOutcome.Ready
            TextButton(onClick = onApply, enabled = !ready) { Text(stringResource(R.string.admin_template_check)) }
            TextButton(onClick = onInsert, enabled = ready) { Text(stringResource(R.string.admin_template_insert)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.admin_cancel)) } },
    )
}

@Composable
private fun EffectChips(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("ask", "allow", "deny").forEach { effect ->
            FilterChip(
                selected = selected == effect,
                onClick = { onSelect(effect) },
                label = { Text(effect) },
                modifier = Modifier.testTag("template:effect:$effect"),
            )
        }
    }
}

@Composable
private fun ModeChips(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("primary", "subagent", "all").forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(mode) },
                modifier = Modifier.testTag("template:mode:$mode"),
            )
        }
    }
}

private fun urlAllowed(url: String) = dev.opencode.android.core.model.SafeNavigationUrl.parse(url) != null

/**
 * The definition editor: one Markdown file, its front matter and its body (plan §6).
 *
 * **The front matter is a set of fields, not a YAML text box.** A phone keyboard cannot type indentation
 * reliably, and a front matter block that is one space out is silently ignored by the server — so the
 * app writes the block from named fields and leaves the body, which is prose, as a text area.
 *
 * **The name is a single path segment and the screen says so when it is not.** `fs.write` is given a
 * path the *server* resolves, so `../` would put a file outside the directory the user is looking at.
 */
@Composable
fun DefinitionScreen(
    state: DefinitionUiState,
    onKindChange: (DefinitionKind) -> Unit,
    onNameChange: (String) -> Unit,
    onFrontMatterChange: (String, String) -> Unit,
    onBodyChange: (String) -> Unit,
    onSave: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismissOutcome: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefinitionKind.entries.forEach { kind ->
                    FilterChip(
                        selected = state.kind == kind,
                        onClick = { onKindChange(kind) },
                        label = { Text(stringResource(kindLabel(kind))) },
                        modifier = Modifier.testTag(AdminTags.definition(kind)),
                    )
                }
            }
            LabelledField(
                label = stringResource(R.string.admin_definition_name),
                value = state.name,
                onValueChange = onNameChange,
                error = state.nameProblem,
                supporting = state.path.takeIf { it.isNotEmpty() }?.let { shortPath(it) },
                tag = AdminTags.DEFINITION_NAME,
            )
            state.error?.let { ErrorLine(error = it, onDismiss = onDismissError) }
        }
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // `fill = false`, for the same reason the editor's header has it: a scrolling child
                // without it takes the whole column and the body field and the save row fall off the
                // bottom of the screen.
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.kind.frontMatterKeys.isNotEmpty()) {
                SectionHeading(stringResource(R.string.admin_definition_front_matter))
                state.kind.frontMatterKeys.forEach { key ->
                    LabelledField(
                        label = key,
                        value = state.frontMatter[key].orEmpty(),
                        onValueChange = { value -> onFrontMatterChange(key, value) },
                        tag = AdminTags.frontMatter(key),
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.body,
            onValueChange = onBodyChange,
            label = { Text(stringResource(R.string.admin_definition_body)) },
            textStyle = MaterialTheme.typography.bodyMedium,
            minLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .heightIn(min = 180.dp)
                .testTag(AdminTags.DEFINITION_BODY),
        )
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.saving) CircularProgressIndicator(modifier = Modifier.heightIn(max = 20.dp))
            Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.testTag("definition:save")) {
                Text(stringResource(R.string.admin_save))
            }
        }
    }
    state.plan?.let { plan ->
        WriteConfirmationDialog(plan = plan, onConfirm = onConfirm, onCancel = onCancel)
    }
    state.outcome?.let { outcome ->
        AlertDialog(
            onDismissRequest = onDismissOutcome,
            title = { Text(stringResource(R.string.admin_write_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(outcome.summary)
                    outcome.diagnostics.forEach { DiagnosticRow(diagnostic = it) }
                }
            },
            confirmButton = { TextButton(onClick = onDismissOutcome) { Text(stringResource(R.string.admin_dismiss)) } },
        )
    }
}

internal fun kindLabel(kind: DefinitionKind): Int = when (kind) {
    DefinitionKind.AGENT -> R.string.admin_definition_agent
    DefinitionKind.COMMAND -> R.string.admin_definition_command
    DefinitionKind.SKILL -> R.string.admin_definition_skill
    DefinitionKind.INSTRUCTIONS -> R.string.admin_definition_instructions
}

/** A monospace line, for a path or a value the user has to read exactly. */
@Composable
internal fun MonoLine(value: String) {
    Text(text = value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}
