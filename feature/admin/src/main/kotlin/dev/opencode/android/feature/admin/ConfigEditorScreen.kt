package dev.opencode.android.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.opencode.android.core.data.config.WritePlan

/**
 * The configuration file editor (plan §6, "Config and definition editor").
 *
 * **The text is a plain monospace field, deliberately.** A code editor with syntax highlighting and a
 * phone keyboard is a project of its own and the plan's own note — a keyboard in a code editor is
 * listed as device-only — says so; what this screen has to be is *honest about validity at every
 * moment*, and that is the diagnostics list's job, not the field's.
 *
 * **The diagnostics are below the field, not in a snackbar.** A user who mistypes a brace has to see
 * which line, and a message that disappears before they have read the line is a message that makes them
 * guess. The list is inside a bounded scroll so a document with fifty problems does not push the field
 * off the screen.
 *
 * **The save button is disabled until the document validates *and* the switch and the route say a write
 * may be made**, and its reason is on the row. The write itself is behind [WriteConfirmationDialog].
 */
@Composable
fun ConfigEditorScreen(
    state: ConfigEditorUiState,
    onPathChange: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onOpenTemplate: (ConfigTemplateChoice) -> Unit,
    onSave: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismissOutcome: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            LabelledField(
                label = stringResource(R.string.admin_config_path),
                value = state.path,
                onValueChange = onPathChange,
                supporting = stringResource(R.string.admin_config_path_help),
                tag = "config:path",
            )
            Text(
                text = if (state.isNewFile) {
                    stringResource(R.string.admin_config_new)
                } else {
                    stringResource(R.string.admin_config_existing)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.admin_templates_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigTemplateChoice.entries.forEach { choice ->
                    FilterChip(
                        selected = state.template?.choice == choice,
                        onClick = { onOpenTemplate(choice) },
                        label = { Text(stringResource(templateLabel(choice))) },
                        modifier = Modifier.testTag(AdminTags.template(choice)),
                    )
                }
            }
        }
        HorizontalDivider()
        OutlinedTextField(
            value = state.draft,
            onValueChange = onDraftChange,
            label = { Text(stringResource(R.string.admin_config_text)) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 8,
            // A large `opencode.jsonc` must not be trapped in a field that cannot show it, so the field
            // grows to a bounded height and scrolls rather than being capped at a few lines.
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 200.dp)
                .testTag(AdminTags.CONFIG_EDITOR),
        )
        Diagnostics(state = state)
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.validating) {
                CircularProgressIndicator(modifier = Modifier.heightIn(max = 20.dp))
            }
            Button(
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier.testTag("config:save"),
            ) { Text(stringResource(R.string.admin_save)) }
            if (state.writesUsable.not()) {
                Text(
                    text = stringResource(R.string.admin_writes_off),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    state.plan?.let { plan ->
        WriteConfirmationDialog(plan = plan, onConfirm = onConfirm, onCancel = onCancel)
    }
}

/**
 * The syntax error and the schema's complaints, in that order.
 *
 * **A syntax error replaces the list rather than joining it.** A document that cannot be parsed has no
 * tree, so there is nothing for the schema to say about it, and a list of both would read as though the
 * schema had opinions about a file it never saw.
 */
@Composable
private fun Diagnostics(state: ConfigEditorUiState) {
    val failure = state.parseFailure
    if (failure != null) {
        ParseFailureRow(failure = failure, modifier = Modifier.padding(horizontal = 16.dp))
        return
    }
    if (state.diagnostics.isEmpty()) {
        if (state.draft.isNotBlank() && !state.validating) {
            Text(
                text = stringResource(R.string.admin_config_valid),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.admin_diagnostics_count, state.diagnostics.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        state.diagnostics.forEach { DiagnosticRow(diagnostic = it) }
    }
}

/** What a write reported, in the server's own terms. */
@Composable
fun WriteOutcomeCard(outcome: dev.opencode.android.core.data.config.WriteOutcome, onDismiss: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = outcome.summary, style = MaterialTheme.typography.bodyMedium)
        outcome.diagnostics.forEach { DiagnosticRow(diagnostic = it) }
        TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "" }) {
            Text(stringResource(R.string.admin_dismiss))
        }
    }
}

internal fun templateLabel(choice: ConfigTemplateChoice): Int = when (choice) {
    ConfigTemplateChoice.MODEL -> R.string.admin_template_model
    ConfigTemplateChoice.PERMISSION -> R.string.admin_template_permission
    ConfigTemplateChoice.AGENT -> R.string.admin_template_agent
    ConfigTemplateChoice.MCP -> R.string.admin_template_mcp
}
