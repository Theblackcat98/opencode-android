package dev.opencode.android.feature.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.config.TemplateOutcome
import dev.opencode.android.core.data.config.WritePlan

/**
 * The screenshot fixtures, as plain composables.
 *
 * **The dialogs are captured through their own content, not through a host window.** P8 found that a
 * `ModalBottomSheet` renders into a window a root capture cannot see, and the fix was a plain
 * composable the host wraps. The same applies here: an `AlertDialog` is a window, so a capture of the
 * tree under it would photograph an empty frame and call it a baseline. These fixtures therefore render
 * the same title, body and buttons the dialog does, in a `Column` inside the same surface — which is
 * what makes the baseline actually show the confirmation rather than prove that a window exists.
 */
@Composable
fun AdminWriteDialogFixture(plan: WritePlan) {
    // **The content, not the dialog.** An `AlertDialog` is a window, and a root capture photographs the
    // tree under it — which is the same defect P8 found with a `ModalBottomSheet`, in the same shape.
    // Capturing the dialog produced two byte-identical baselines for a privilege change and a plain
    // one, which is the only way to notice: the pictures were of an empty background. This renders what
    // the dialog renders, so the baseline shows the words the user reads.
    AdminScaffoldless {
        Text(
            text = if (plan.isPrivilegeChange) {
                stringResource(R.string.admin_write_privilege_title)
            } else {
                stringResource(R.string.admin_write_title)
            },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .padding(16.dp)
                .testTag(AdminTags.CONFIRM_WRITE),
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(text = plan.consequence)
            Text(
                text = plan.target,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            if (plan.isPrivilegeChange) {
                Text(
                    text = stringResource(R.string.admin_write_privilege_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = if (plan.previousBytes == null) {
                    stringResource(R.string.admin_write_size_new, plan.bytes)
                } else {
                    stringResource(R.string.admin_write_size, plan.previousBytes!!, plan.bytes)
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = {}, modifier = Modifier.testTag(AdminTags.CONFIRM_WRITE)) {
                Text(stringResource(R.string.admin_confirm))
            }
            TextButton(onClick = {}, modifier = Modifier.testTag(AdminTags.CANCEL_WRITE)) {
                Text(stringResource(R.string.admin_cancel))
            }
        }
    }
}

/**
 * The template sheet's content, captured the same way.
 *
 * The real sheet is a dialog, so this renders the same fields and the same verdict without one; the
 * point of a baseline is what the user reads, and a photograph of a dialog's *contents* is that.
 */
@Composable
fun TemplateSheetFixture(
    draft: ConfigTemplateDraft,
    outcome: TemplateOutcome?,
) {
    AdminScaffoldless {
        Text(
            text = stringResource(templateLabel(draft.choice)),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when (draft.choice) {
            ConfigTemplateChoice.PERMISSION -> {
                LabelledField(
                    label = stringResource(R.string.admin_template_action),
                    value = draft.action,
                    onValueChange = {},
                    supporting = stringResource(R.string.admin_template_action_help),
                    tag = "template:action",
                )
                LabelledField(
                    label = stringResource(R.string.admin_template_resource),
                    value = draft.resource,
                    onValueChange = {},
                    supporting = stringResource(R.string.admin_template_resource_help),
                    tag = "template:resource",
                )
                Text(
                    text = stringResource(R.string.admin_template_effect),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = draft.effect,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = stringResource(R.string.admin_template_permission_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(16.dp)
                        .testTag(AdminTags.RULES_PRECEDENCE_WARNING),
                )
            }

            ConfigTemplateChoice.MODEL -> {
                LabelledField(stringResource(R.string.admin_template_provider), draft.provider, {}, tag = "template:provider")
                LabelledField(
                    label = stringResource(R.string.admin_template_model_name),
                    value = draft.model,
                    onValueChange = {},
                    supporting = stringResource(R.string.admin_template_model_help),
                )
                LabelledField(stringResource(R.string.admin_template_variant), draft.variant, {})
            }

            ConfigTemplateChoice.MCP -> {
                LabelledField(stringResource(R.string.admin_template_mcp_name), draft.name, {})
                Text(
                    text = stringResource(R.string.admin_template_mcp_kind),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(text = draft.mcpKind, modifier = Modifier.padding(horizontal = 16.dp))
                LabelledField(
                    label = stringResource(R.string.admin_template_mcp_url),
                    value = draft.mcpUrl,
                    onValueChange = {},
                    supporting = stringResource(R.string.admin_template_mcp_url_help),
                    error = if (draft.mcpUrl.isNotBlank() &&
                        dev.opencode.android.core.model.SafeNavigationUrl.parse(draft.mcpUrl) == null
                    ) {
                        stringResource(R.string.admin_template_mcp_url_bad)
                    } else {
                        null
                    },
                )
            }

            ConfigTemplateChoice.AGENT -> {
                LabelledField(stringResource(R.string.admin_template_agent_name), draft.name, {})
                LabelledField(
                    label = stringResource(R.string.admin_template_model_ref),
                    value = draft.model,
                    onValueChange = {},
                )
                Text(
                    text = stringResource(R.string.admin_template_mode),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(text = draft.mode, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        when (outcome) {
            is TemplateOutcome.Ready -> Text(
                text = stringResource(R.string.admin_template_valid),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp),
            )

            is TemplateOutcome.Invalid -> Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.admin_template_invalid),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                outcome.problems.forEach { DiagnosticRow(diagnostic = it) }
            }

            is TemplateOutcome.NotReady -> Text(
                text = stringResource(R.string.admin_template_not_ready),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )

            null -> Unit
        }
        Text(
            text = stringResource(if (outcome is TemplateOutcome.Ready) R.string.admin_template_insert else R.string.admin_template_check),
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** A screen in a column with no scaffold, which is what a fixture needs and a screen does not. */
@Composable
internal fun AdminScaffoldless(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) { content() }
}

/** The config explorer, captured as a plain composable. */
@Composable
fun ConfigScreenFixture(state: ConfigUiState, configuredOnly: Boolean = false) {
    AdminScaffoldless {
        ConfigScreen(
            state = state,
            configuredOnly = configuredOnly,
            onReload = {},
            onOpenEditor = {},
            onOpenDefinitions = {},
            onShellChange = {},
            onShellRequest = {},
            onShellConfirm = {},
            onShellCancel = {},
            onDismissError = {},
        )
    }
}

/** The `opencode.jsonc` editor, captured as a plain composable. */
@Composable
fun ConfigEditorScreenFixture(state: ConfigEditorUiState) {
    AdminScaffoldless {
        ConfigEditorScreen(
            state = state,
            onPathChange = {},
            onDraftChange = {},
            onOpenTemplate = {},
            onSave = {},
            onConfirm = {},
            onCancel = {},
            onDismissOutcome = {},
            onDismissError = {},
        )
    }
}

/** The definition editor, captured as a plain composable. */
@Composable
fun DefinitionScreenFixture(state: DefinitionUiState) {
    AdminScaffoldless {
        DefinitionScreen(
            state = state,
            onKindChange = {},
            onNameChange = {},
            onFrontMatterChange = { _, _ -> },
            onBodyChange = {},
            onSave = {},
            onConfirm = {},
            onCancel = {},
            onDismissOutcome = {},
            onDismissError = {},
        )
    }
}

/** The permissions screen, captured as a plain composable. */
@Composable
fun PermissionsScreenFixture(state: PermissionsUiState) {
    AdminScaffoldless {
        PermissionsScreen(
            state = state,
            onRequestRemove = {},
            onConfirmRemove = {},
            onCancelRemove = {},
            onStartEditing = {},
            onCancelEditing = {},
            onAddRule = {},
            onRuleChange = { _, _ -> },
            onRemoveRule = {},
            onSaveRules = {},
            onDismissError = {},
        )
    }
}

/** The maintenance screen, captured as a plain composable. */
@Composable
fun MaintenanceScreenFixture(state: MaintenanceUiState) {
    AdminScaffoldless {
        MaintenanceScreen(
            state = state,
            onReload = {},
            onRequestEvict = {},
            onConfirmEvict = {},
            onCancelEvict = {},
            onDismissOutcome = {},
            onDismissError = {},
        )
    }
}

/** The instruction entries screen, captured as a plain composable. */
@Composable
fun InstructionsScreenFixture(state: InstructionsUiState) {
    AdminScaffoldless {
        InstructionsScreen(
            state = state,
            onKeyChange = {},
            onValueChange = {},
            onPut = {},
            onRequestRemove = {},
            onConfirmRemove = {},
            onCancelRemove = {},
            onDismissError = {},
        )
    }
}

/** One of the four catalog browsers, captured as a plain composable. */
@Composable
fun CatalogScreenFixture(state: CatalogUiState) {
    AdminScaffoldless {
        CatalogScreen(state = state, onTabChange = {}, onSearchChange = {})
    }
}
