package dev.opencode.android.feature.requests.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.forms.FormEngine
import dev.opencode.android.core.data.forms.FormFieldState
import dev.opencode.android.core.model.FormAnswer
import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.FormOption
import dev.opencode.android.core.model.FormValues
import dev.opencode.android.feature.requests.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A form's fields, in the order the server declared them, filtered to the ones currently visible.
 *
 * **One renderer for every field type**, which is the whole point of forms being one mechanism
 * (features doc §16): the `question` tool, web-search consent and an MCP elicitation all arrive
 * through here, and a field type this client has never seen gets a labelled row rather than being
 * skipped, so the user can still read what it was asked.
 *
 * **The state is the answers, not a per-field object graph.** Visibility is derived from the answers
 * by [FormEngine] on every recomposition, which is what makes a `when` condition work without the
 * renderer knowing the schema. The answers are held by the caller, so the same map is what
 * `FormEngine.toAnswer` turns into the reply.
 *
 * Everything here is stateless on purpose: the caller owns the draft so that a rotation, a
 * backgrounding and a notification action (Phase 4) all read and write the same answers.
 */
@Composable
fun FormFields(
    fields: List<FormField>,
    answers: FormAnswer,
    onAnswerChange: (String, JsonElement?) -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val states = FormEngine.fieldStates(fields, answers)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        states.filter { it.visible }.forEach { state ->
            FormFieldRow(
                state = state,
                enabled = enabled,
                onAnswerChange = onAnswerChange,
                onOpenLink = onOpenLink,
            )
        }
    }
}

/** One field, whatever its type. */
@Composable
private fun FormFieldRow(
    state: FormFieldState,
    enabled: Boolean,
    onAnswerChange: (String, JsonElement?) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    when (val field = state.field) {
        is FormField.StringField -> StringFieldRow(
            state = state,
            field = field,
            enabled = enabled,
            onAnswerChange = onAnswerChange,
        )

        is FormField.NumberField -> NumberFieldRow(
            state = state,
            field = field,
            enabled = enabled,
            onAnswerChange = onAnswerChange,
        )

        is FormField.BooleanField -> BooleanFieldRow(
            state = state,
            field = field,
            enabled = enabled,
            onAnswerChange = onAnswerChange,
        )

        is FormField.MultiselectField -> MultiselectFieldRow(
            state = state,
            field = field,
            enabled = enabled,
            onAnswerChange = onAnswerChange,
        )

        is FormField.ExternalField -> ExternalFieldRow(field = field, onOpenLink = onOpenLink)

        is FormField.Unknown -> {
            // A field type a newer server added. Saying what it is beats dropping it: the form would
            // otherwise be answerable while silently skipping a question.
            val label = field.declaredType ?: stringResource(R.string.form_unknown_field_type, "unknown")
            Text(
                text = stringResource(R.string.form_unknown_field_type, label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A string field: the options as radios, plus a free-text box when `custom` allows it.
 *
 * The options come first because a `question` field is nearly always a choice, and the custom box
 * after them because it is the escape hatch. With no options at all the box is the whole field.
 */
@Composable
private fun StringFieldRow(
    state: FormFieldState,
    field: FormField.StringField,
    enabled: Boolean,
    onAnswerChange: (String, JsonElement?) -> Unit,
) {
    val text = (state.value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    val customOnly = field.options.isEmpty() || !typedIntoOptions(text, field.options)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(state)
        if (field.options.isNotEmpty()) {
            Column(Modifier.selectableGroup()) {
                field.options.forEach { option ->
                    OptionRow(
                        option = option,
                        selected = text == option.value,
                        enabled = enabled,
                        onSelect = { onAnswerChange(field.key, FormValues.string(option.value)) },
                    )
                }
            }
        }
        if (field.custom || field.options.isEmpty()) {
            OutlinedTextField(
                value = if (customOnly) text else "",
                onValueChange = { onAnswerChange(field.key, FormValues.string(it)) },
                label = if (field.options.isEmpty()) null else {
                    { Text(stringResource(R.string.form_custom_option)) }
                },
                placeholder = field.placeholder?.let { { Text(it) } },
                singleLine = true,
                enabled = enabled,
                isError = state.problem != null,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardTypeFor(field.format)),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ProblemText(state)
    }
}

/** A `number` or `integer` field. */
@Composable
private fun NumberFieldRow(
    state: FormFieldState,
    field: FormField.NumberField,
    enabled: Boolean,
    onAnswerChange: (String, JsonElement?) -> Unit,
) {
    val text = (state.value as? JsonPrimitive)?.content.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(state)
        OutlinedTextField(
            value = text,
            // Kept as text, not as a number: a partially typed "1e" or "-" is a legitimate state to
            // be in, and parsing it on every keystroke would delete the user's typing.
            onValueChange = { typed ->
                val number = typed.trim().toDoubleOrNull()
                onAnswerChange(field.key, if (typed.isBlank() || number == null) null else FormValues.number(number))
            },
            label = if (field.integer) {
                { Text(stringResource(R.string.form_integer_hint)) }
            } else {
                { Text(stringResource(R.string.form_number_hint)) }
            },
            singleLine = true,
            enabled = enabled,
            isError = state.problem != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (field.integer) KeyboardType.Number else KeyboardType.Decimal,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        ProblemText(state)
    }
}

/** A boolean field, as a switch with its label on the left. */
@Composable
private fun BooleanFieldRow(
    state: FormFieldState,
    field: FormField.BooleanField,
    enabled: Boolean,
    onAnswerChange: (String, JsonElement?) -> Unit,
) {
    val checked = (state.value as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: field.default ?: false
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            FieldLabel(state)
            ProblemText(state)
        }
        Switch(
            checked = checked,
            onCheckedChange = { onAnswerChange(field.key, FormValues.boolean(it)) },
            enabled = enabled,
        )
    }
}

/** A multiselect field: chips, which is what "pick any of these" looks like on a phone. */
@Composable
private fun MultiselectFieldRow(
    state: FormFieldState,
    field: FormField.MultiselectField,
    enabled: Boolean,
    onAnswerChange: (String, JsonElement?) -> Unit,
) {
    val selected = (state.value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(state)
        val selectedCount = stringResource(R.string.form_selected_count, selected.size)
        Column(Modifier.selectableGroup().semantics { contentDescription = selectedCount }) {
            field.options.forEach { option ->
                val isSelected = option.value in selected
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        val next = if (isSelected) selected - option.value else selected + option.value
                        onAnswerChange(field.key, if (next.isEmpty()) null else FormValues.strings(next))
                    },
                    enabled = enabled,
                    label = { Text(option.label) },
                    modifier = Modifier.padding(vertical = 2.dp).fillMaxWidth(),
                )
            }
        }
        ProblemText(state)
    }
}

/** An external field: a link the user has to open, and nothing to answer. */
@Composable
private fun ExternalFieldRow(field: FormField.ExternalField, onOpenLink: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = field.labelOrKey(),
            style = MaterialTheme.typography.labelLarge,
        )
        field.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { onOpenLink(field.url) }) {
            Text(stringResource(R.string.form_external))
        }
    }
}

/** A title, its description, and whether it is required — the label every field shares. */
@Composable
private fun FieldLabel(state: FormFieldState) {
    val requirement = if (state.field.required) {
        stringResource(R.string.form_required)
    } else {
        stringResource(R.string.form_optional)
    }
    val label = stringResource(R.string.form_field_label, state.field.labelOrKey(), requirement)
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.semantics { contentDescription = label },
    )
    state.field.description?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The field's problem, or nothing. A missing answer is not an error until Reply is pressed. */
@Composable
private fun ProblemText(state: FormFieldState) {
    val problem = state.problem ?: return
    val text = if (problem.takesCount) {
        val limit = (state.field as? FormField.MultiselectField)?.let {
            if (problem == dev.opencode.android.core.data.forms.FieldProblem.TOO_FEW) it.minItems else it.maxItems
        } ?: 0
        stringResource(problem.messageRes(), limit)
    } else {
        stringResource(problem.messageRes())
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** One option as a radio row, with its description under the label when it has one. */
@Composable
private fun OptionRow(
    option: FormOption,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = null, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(option.label, style = MaterialTheme.typography.bodyMedium)
            option.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A cancel affordance, which for a question is the same as dismissing it. */
@Composable
fun FormCancelButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.clearAndSetSemantics { }) {
        Text(stringResource(R.string.form_cancel))
    }
}

/** A checkbox row, used by the consent dialog rather than by a form field. */
@Composable
fun ConsentRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().selectable(selected = checked, enabled = enabled, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

private fun typedIntoOptions(text: String, options: List<FormOption>): Boolean =
    text.isEmpty() || options.any { it.value == text }

private fun keyboardTypeFor(format: String?): KeyboardType = when (format) {
    "email" -> KeyboardType.Email
    "uri" -> KeyboardType.Uri
    "date" -> KeyboardType.Number
    "date-time" -> KeyboardType.Text
    else -> KeyboardType.Text
}
