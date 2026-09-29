package dev.opencode.android.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.model.InstructionEntry
import dev.opencode.android.core.model.LoadedLocation
import dev.opencode.android.core.model.MigrationStatus
import dev.opencode.android.core.model.PermissionEffect
import dev.opencode.android.core.model.SavedPermission

/**
 * The permissions screen (plan §6, "Permissions admin").
 *
 * **The precedence warning is a fixed card, not a disclosure and not a dialog.** Session rules evaluate
 * last and can therefore override an agent's `deny`, which is the reasoning Phase 4 recorded when it
 * made auto-approve client-side and only ever `once`: a server-side rule is the one mechanism that can
 * widen what runs without asking, so it is exactly what the warning is about. Putting it behind a
 * disclosure the user can skip past — or in a dialog they dismiss once and forget — would make the
 * warning a formality.
 *
 * **Removing a saved approval is confirmed with the action and the resource in the sentence.** It is a
 * privilege change in the other direction: the tool that was allowed once will ask again for exactly
 * that action, and a user who taps "remove" without reading which rule is gone has made a tool start
 * asking without knowing it.
 */
@Composable
fun PermissionsScreen(
    state: PermissionsUiState,
    onRequestRemove: (SavedPermission) -> Unit,
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onStartEditing: () -> Unit,
    onCancelEditing: () -> Unit,
    onAddRule: () -> Unit,
    onRuleChange: (Int, (PermissionRuleDraft) -> PermissionRuleDraft) -> Unit,
    onRemoveRule: (Int) -> Unit,
    onSaveRules: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (state.sessionID != null) {
            PrecedenceWarning()
            HorizontalDivider()
            RuleSection(
                state = state,
                onStartEditing = onStartEditing,
                onCancelEditing = onCancelEditing,
                onAddRule = onAddRule,
                onRuleChange = onRuleChange,
                onRemoveRule = onRemoveRule,
                onSaveRules = onSaveRules,
            )
            HorizontalDivider()
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            item(key = "saved-heading") {
                SectionHeading(
                    stringResource(
                        if (state.projectID == null) R.string.admin_saved_all else R.string.admin_saved_project,
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (state.loading && !state.savedLoaded) {
                item(key = "saved-loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.admin_saved_loading))
                    }
                }
            }
            state.error?.let { error ->
                item(key = "error") { ErrorLine(error = error, onDismiss = onDismissError) }
            }
            if (state.savedLoaded && state.saved.isEmpty()) {
                item(key = "saved-empty") {
                    Text(
                        text = stringResource(R.string.admin_saved_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            // Keyed on the id, not the position: the list is re-read after every removal.
            items(items = state.saved, key = { it.id }) { permission ->
                SavedPermissionRow(permission = permission, onRemove = { onRequestRemove(permission) })
            }
        }
    }
    state.removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = onCancelRemove,
            title = { Text(stringResource(R.string.admin_saved_remove_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.admin_saved_remove_body, target.action, target.resource))
                    Text(
                        text = stringResource(R.string.admin_saved_remove_consequence),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmRemove,
                    modifier = Modifier.testTag(AdminTags.CONFIRM_REMOVE_SAVED),
                ) { Text(stringResource(R.string.admin_saved_remove_confirm)) }
            },
            dismissButton = { TextButton(onClick = onCancelRemove) { Text(stringResource(R.string.admin_cancel)) } },
        )
    }
}

/**
 * The precedence warning, which is the one thing on this screen that is always visible.
 *
 * **It says what will happen, not that something might.** "Session rules are evaluated last and can
 * override an agent's `deny`" is the fact a user needs; "be careful with permissions" is not. The
 * second sentence is the same reasoning Phase 4's `AutoApprovePolicy` records, and it is here because
 * the alternative the plan rejected — a server-side session rule — is exactly this screen.
 */
@Composable
private fun PrecedenceWarning() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag(AdminTags.RULES_PRECEDENCE_WARNING),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.admin_rules_warning_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.admin_rules_warning_body), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RuleSection(
    state: PermissionsUiState,
    onStartEditing: () -> Unit,
    onCancelEditing: () -> Unit,
    onAddRule: () -> Unit,
    onRuleChange: (Int, (PermissionRuleDraft) -> PermissionRuleDraft) -> Unit,
    onRemoveRule: (Int) -> Unit,
    onSaveRules: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.admin_rules_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(
                onClick = if (state.editing) onCancelEditing else onStartEditing,
                modifier = Modifier.testTag("permissions:toggle-editing"),
            ) { Text(stringResource(if (state.editing) R.string.admin_cancel else R.string.admin_rules_edit)) }
        }
        if (!state.editing) {
            if (state.rules.isEmpty()) {
                Text(
                    text = stringResource(R.string.admin_rules_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.rules.forEachIndexed { index, rule ->
                RuleSummary(index = index, rule = rule)
            }
            return
        }
        state.drafts.forEachIndexed { index, draft ->
            RuleEditor(
                index = index,
                draft = draft,
                onChange = { transform -> onRuleChange(index, transform) },
                onRemove = { onRemoveRule(index) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAddRule, modifier = Modifier.testTag(AdminTags.ADD_RULE)) {
                Text(stringResource(R.string.admin_rules_add))
            }
            Button(onClick = onSaveRules, enabled = state.canSave, modifier = Modifier.testTag(AdminTags.SAVE_RULES)) {
                Text(stringResource(R.string.admin_save))
            }
        }
        Text(
            text = stringResource(R.string.admin_rules_whole_set),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RuleSummary(index: Int, rule: dev.opencode.android.core.model.PermissionRule) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(AdminTags.rule(index)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = rule.effect.value, style = MaterialTheme.typography.labelMedium, color = effectColor(rule.effect))
        Text(text = rule.action, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(text = rule.resource, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RuleEditor(
    index: Int,
    draft: PermissionRuleDraft,
    onChange: ((PermissionRuleDraft) -> PermissionRuleDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(AdminTags.rule(index)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LabelledField(
                label = stringResource(R.string.admin_template_action),
                value = draft.action,
                onValueChange = { value -> onChange { it.copy(action = value) } },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove, modifier = Modifier.testTag("permissions:remove-rule:$index")) {
                Text(text = "×", style = MaterialTheme.typography.titleMedium)
            }
        }
        LabelledField(
            label = stringResource(R.string.admin_template_resource),
            value = draft.resource,
            onValueChange = { value -> onChange { it.copy(resource = value) } },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PermissionEffect.Ask, PermissionEffect.Allow, PermissionEffect.Deny).forEach { effect ->
                FilterChip(
                    selected = draft.effect == effect,
                    onClick = { onChange { it.copy(effect = effect) } },
                    label = { Text(effect.value) },
                    modifier = Modifier.testTag("permissions:effect:$index:${effect.value}"),
                )
            }
        }
    }
}

@Composable
private fun effectColor(effect: PermissionEffect) = when (effect) {
    PermissionEffect.Deny -> MaterialTheme.colorScheme.error
    PermissionEffect.Allow -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.tertiary
}

@Composable
private fun SavedPermissionRow(permission: SavedPermission, onRemove: () -> Unit) {
    ListItem(
        headlineContent = {
            Column {
                Text(text = permission.action, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = permission.resource,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            TextButton(
                onClick = onRemove,
                modifier = Modifier
                    .testTag(AdminTags.removeSaved(permission.id))
                    .semantics {
                        contentDescription = ""
                    },
            ) { Text(stringResource(R.string.admin_saved_remove)) }
        },
        modifier = Modifier.testTag(AdminTags.savedPermission(permission.id)),
    )
}
