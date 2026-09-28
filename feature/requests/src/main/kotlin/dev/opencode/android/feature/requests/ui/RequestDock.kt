package dev.opencode.android.feature.requests.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.forms.FormEngine
import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.model.FormAnswer
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.FormKind
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.core.model.mcpMessage
import dev.opencode.android.core.model.mcpServer
import dev.opencode.android.core.model.questionTool
import dev.opencode.android.feature.requests.R
import kotlinx.serialization.json.JsonElement

/**
 * What a screen does with a request.
 *
 * A plain holder rather than a ViewModel reference, so the dock can be rendered and screenshotted
 * with a lambda, and so the same dock works in a session, in the global inbox and — in Phase 4 — in
 * a notification action.
 */
data class RequestActions(
    val onReplyOnce: (PendingRequest.Permission) -> Unit = {},
    val onReplyAlways: (PendingRequest.Permission) -> Unit = {},
    val onReject: (PendingRequest.Permission, String?) -> Unit = { _, _ -> },
    val onSubmitForm: (PendingRequest.Form, FormAnswer) -> Unit = { _, _ -> },
    val onCancelForm: (PendingRequest.Form) -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
    val onOpenSession: (String) -> Unit = {},
)

/**
 * The dock: what the agent is blocked on, right where the user is looking.
 *
 * **A form is answered where it is raised.** A `question` renders inline beside its tool card,
 * because that is the turn it belongs to; a web-search consent renders as a dialog and an MCP
 * elicitation as a sheet naming the server, because those interrupt something else. `metadata.kind`
 * picks the presentation (features doc §16) and the fields are the same in all three.
 *
 * **"Always allow" asks first and shows the patterns** (plan §5.2). The reply stores rules on the
 * server that outlive the session, and a user who cannot see them cannot meaningfully consent to
 * them; the confirmation lists exactly what will be stored. It is disabled when the request names no
 * patterns, because there is nothing to store and the server would reject it.
 */
@Composable
fun RequestDock(
    requests: List<PendingRequest>,
    actions: RequestActions,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    initiallyExpanded: Boolean = true,
) {
    if (requests.isEmpty()) return
    var expanded by rememberSaveable(requests.size) { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.requests_dock_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            val toggle = if (expanded) {
                stringResource(R.string.requests_dock_collapse)
            } else {
                stringResource(R.string.requests_dock_expand, requests.size)
            }
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = toggle },
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                requests.forEach { request ->
                    when (request) {
                        is PendingRequest.Form -> FormRequest(request, actions, busy = busy)
                        is PendingRequest.Permission -> PermissionCard(request.request, actions, busy = busy)
                    }
                }
            }
        }
    }
}

/**
 * One permission request.
 *
 * The action, the resources it would touch, and optional feedback are all here, because a user
 * cannot answer "may I?" from the action name alone. The tool the request came from is linked in the
 * timeline through [PermissionRequest.source], which is what lets the dock sit next to the tool card
 * that asked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionCard(
    request: PermissionRequest,
    actions: RequestActions,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    var feedback by rememberSaveable(request.id) { mutableStateOf("") }
    var confirmingAlways by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.permission_title, request.action),
            style = MaterialTheme.typography.titleSmall,
        )
        request.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        if (request.resources.isNotEmpty()) {
            Text(
                text = stringResource(R.string.permission_resources),
                style = MaterialTheme.typography.labelMedium,
            )
            request.resources.forEach { resource ->
                Text(
                    text = resource,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        if (busy) {
            Text(
                text = stringResource(R.string.permission_sending),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // A wrapping row, not a fixed one: at a large font scale three buttons do not fit on a
        // phone's width, and clipping "Reject" is the one button a user must always be able to reach.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { actions.onReplyOnce(PendingRequest.Permission(request)) },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.permission_once))
            }
            OutlinedButton(
                onClick = { confirmingAlways = true },
                enabled = !busy && request.savedPatterns.isNotEmpty(),
            ) {
                Text(stringResource(R.string.permission_always))
            }
            TextButton(
                onClick = { actions.onReject(PendingRequest.Permission(request), feedback.ifBlank { null }) },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.permission_reject))
            }
        }
        OutlinedTextField(
            value = feedback,
            onValueChange = { feedback = it },
            label = { Text(stringResource(R.string.permission_feedback_label)) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }

    if (confirmingAlways) {
        AlertDialog(
            onDismissRequest = { confirmingAlways = false },
            title = { Text(stringResource(R.string.permission_always_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.permission_always_confirm_body))
                    request.savedPatterns.forEach { pattern ->
                        Text(
                            text = pattern,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmingAlways = false
                        actions.onReplyAlways(PendingRequest.Permission(request))
                    },
                ) {
                    Text(stringResource(R.string.permission_always))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingAlways = false }) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }
}

/**
 * One form, presented according to `metadata.kind`.
 *
 * The answers live in the presentation and start from the field defaults, so the same form can be
 * answered from the dock, from the global inbox and — in Phase 4 — from a notification without a
 * shared ViewModel and without the answers surviving a presentation the user abandoned.
 */
@Composable
fun FormRequest(
    request: PendingRequest.Form,
    actions: RequestActions,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    when (request.form.kind) {
        FormKind.MCP_ELICITATION -> ElicitationSheet(request, actions, busy, modifier)
        FormKind.WEBSEARCH_CONSENT -> ConsentRequest(request, actions, busy, modifier)
        FormKind.QUESTION, FormKind.GENERIC -> InlineForm(request, actions, busy, modifier)
    }
}

/**
 * The `question` presentation: inline, beside the tool call that asked.
 *
 * The first press on an incomplete form reveals its problems instead of sending, because a user who
 * has not typed anything should not be met with a red field they did not know was required.
 */
@Composable
private fun InlineForm(request: PendingRequest.Form, actions: RequestActions, busy: Boolean, modifier: Modifier) {
    val form = request.form
    val answers = formAnswers(form)
    var attempted by rememberSaveable(form.id) { mutableStateOf(false) }
    val problems = FormEngine.validate(form.fields, answers.value)

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(form.title, style = MaterialTheme.typography.titleSmall)
        // A question is asked by a specific tool call, and a transcript can hold several; naming the
        // one being answered is what tells the user which card this belongs to.
        form.questionTool?.id?.let {
            Text(
                text = stringResource(R.string.form_answers_tool, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FormFields(
            fields = form.fields,
            answers = answers.value,
            onAnswerChange = answers.set,
            onOpenLink = actions.onOpenLink,
            enabled = !busy,
            showProblems = attempted,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    if (problems.isEmpty()) {
                        actions.onSubmitForm(request, FormEngine.toAnswer(form.fields, answers.value))
                    } else {
                        attempted = true
                    }
                },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.form_reply))
            }
            FormCancelButton(onClick = { actions.onCancelForm(request) }, enabled = !busy)
        }
    }
}

/** `websearch.provider`: consent, as a dialog, because it interrupts a different decision. */
@Composable
private fun ConsentRequest(
    request: PendingRequest.Form,
    actions: RequestActions,
    busy: Boolean,
    modifier: Modifier,
) {
    val answers = formAnswers(request.form)
    var confirming by rememberSaveable(request.form.id) { mutableStateOf(false) }
    var attempted by rememberSaveable(request.form.id) { mutableStateOf(false) }
    val title = stringResource(R.string.form_websearch_title)

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(title) },
            text = {
                Column {
                    Text(stringResource(R.string.form_websearch_body))
                    FormFields(
                        fields = request.form.fields,
                        answers = answers.value,
                        onAnswerChange = answers.set,
                        onOpenLink = actions.onOpenLink,
                        enabled = !busy,
                        showProblems = attempted,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!FormEngine.canSubmit(request.form.fields, answers.value)) {
                            attempted = true
                            return@Button
                        }
                        confirming = false
                        actions.onSubmitForm(request, FormEngine.toAnswer(request.form.fields, answers.value))
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.form_reply))
                }
            },
            dismissButton = {
                TextButton(onClick = { actions.onCancelForm(request) }, enabled = !busy) {
                    Text(stringResource(R.string.form_cancel))
                }
            },
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { confirming = true }, enabled = !busy) {
                    Text(stringResource(R.string.form_reply))
                }
                TextButton(onClick = { actions.onCancelForm(request) }, enabled = !busy) {
                    Text(stringResource(R.string.form_cancel))
                }
            }
        }
    }
}

/** `mcp-elicitation`: a sheet that names the server, because that is who is asking. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ElicitationSheet(
    request: PendingRequest.Form,
    actions: RequestActions,
    busy: Boolean,
    modifier: Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val form = request.form
    val answers = formAnswers(form)
    var attempted by rememberSaveable(form.id) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { actions.onCancelForm(request) },
        sheetState = sheetState,
        modifier = modifier,
    ) {
        ElicitationContent(form, answers.value, answers.set, actions, busy, attempted, onAttempt = { attempted = true })
    }
}

/**
 * An elicitation's body, without the sheet chrome.
 *
 * A `ModalBottomSheet` needs a host to lay out, so the body is a composable of its own: the sheet
 * wraps it and a screenshot renders it directly.
 */
@Composable
fun ElicitationContent(
    form: FormInfo,
    answers: FormAnswer,
    onAnswerChange: (String, JsonElement?) -> Unit,
    actions: RequestActions,
    busy: Boolean,
    showProblems: Boolean = false,
    onAttempt: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.form_mcp_title, form.mcpServer ?: form.title)
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        form.mcpMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        HorizontalDivider()
        FormFields(
            fields = form.fields,
            answers = answers,
            onAnswerChange = onAnswerChange,
            onOpenLink = actions.onOpenLink,
            enabled = !busy,
            showProblems = showProblems,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    if (!FormEngine.canSubmit(form.fields, answers)) {
                        onAttempt()
                        return@Button
                    }
                    actions.onSubmitForm(PendingRequest.Form(form), FormEngine.toAnswer(form.fields, answers))
                },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.form_reply))
            }
            FormCancelButton(onClick = { actions.onCancelForm(PendingRequest.Form(form)) }, enabled = !busy)
        }
    }
}

/** A form's answers, seeded from the field defaults and kept across a configuration change. */
@Composable
private fun formAnswers(form: FormInfo): FormAnswerHolder {
    val state: MutableState<FormAnswer> = rememberSaveable(form.id) {
        mutableStateOf(FormEngine.initialState(form))
    }
    return FormAnswerHolder(state) { key, value ->
        state.value = if (value == null) state.value - key else state.value + (key to value)
    }
}

private class FormAnswerHolder(
    val state: MutableState<FormAnswer>,
    val set: (String, JsonElement?) -> Unit,
) {
    val value: FormAnswer get() = state.value
}

/** A cancel affordance, which for a question is the same as dismissing it. */
@Composable
fun FormCancelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(stringResource(R.string.form_cancel))
    }
}
