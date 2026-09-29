package dev.opencode.android.feature.integrations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.integrations.ConnectAttemptState
import dev.opencode.android.core.data.integrations.CredentialAction
import dev.opencode.android.core.data.integrations.IntegrationFlow
import dev.opencode.android.core.data.sync.SyncStatus
import dev.opencode.android.core.model.ConnectionInfo
import dev.opencode.android.core.model.FormValues
import dev.opencode.android.core.model.IntegrationMethod

/**
 * The connect screen, at `/connect` parity (features doc §10; plan §6).
 *
 * **One row per integration, one sheet for whichever method is being started.** The plan's four flows
 * are one flow with a `when`, and the decision of *which* is
 * [dev.opencode.android.core.data.integrations.IntegrationFlows.of] in the data layer — so a method
 * of the wrong type cannot open the wrong sheet even though the composable is one function.
 *
 * **The key field is a secret, and the sheet is built so a screenshot of it says nothing.** The field
 * is masked with a show/hide toggle, the sheet never holds the key in a `Text` node, and the
 * `FLAG_SECURE` window flag is set for the sheet's lifetime by [SecureSheet] — see
 * [dev.opencode.android.feature.integrations.SecureWindowEffect]. Roborazzi baselines use a
 * placeholder, and no real credential is ever typed into this screen's fixtures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    state: ConnectUiState,
    onMethodClick: (IntegrationMethod) -> Unit,
    onCredentialAction: (ConnectionInfo.Credential, CredentialAction) -> Unit,
    onConfirmCredential: () -> Unit,
    onCancelCredential: () -> Unit,
    onConfirmLabelChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onAnswer: (String, kotlinx.serialization.json.JsonElement?) -> Unit,
    onSubmit: () -> Unit,
    onSubmitCode: () -> Unit,
    onCancelAttempt: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    onDismissConnect: () -> Unit,
    onWellknownUrlChange: (String) -> Unit,
    onAddWellknown: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                when (val status = state.integrations.status) {
                    is SyncStatus.Failed -> ErrorLine(
                        text = status.error.message ?: stringResource(R.string.connect_error),
                        onDismiss = onDismissError,
                    )

                    SyncStatus.Loading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.connect_loading))
                    }

                    else -> if (state.rows.isEmpty()) {
                        EmptyNote(stringResource(R.string.connect_empty))
                    }
                }
            }
            items(state.rows, key = { it.id }) { row ->
                IntegrationCard(row = row, onMethodClick = onMethodClick, onCredentialAction = onCredentialAction)
                HorizontalDivider()
            }
            if (state.wellknownUsable) {
                item {
                    WellknownSourceCard(
                        url = state.wellknownUrl,
                        canAdd = state.canAddWellknown,
                        busy = state.busy,
                        onUrlChange = onWellknownUrlChange,
                        onAdd = onAddWellknown,
                    )
                }
            }
        }
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.connect_error)) },
            text = { Text(error.message) },
            confirmButton = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.connect_dismiss)) } },
        )
    }

    state.confirm?.let { pending ->
        CredentialConfirmDialog(
            pending = pending,
            onLabelChange = onConfirmLabelChange,
            onConfirm = onConfirmCredential,
            onCancel = onCancelCredential,
        )
    }

    val active = state.active
    if (active != null) {
        ModalBottomSheet(onDismissRequest = onDismissConnect) {
            ConnectSheetContent(
                state = state,
                onLabelChange = onLabelChange,
                onKeyChange = onKeyChange,
                onCodeChange = onCodeChange,
                onAnswer = onAnswer,
                onSubmit = onSubmit,
                onSubmitCode = onSubmitCode,
                onCancelAttempt = onCancelAttempt,
                onOpenUrl = onOpenUrl,
                onCopyCode = onCopyCode,
                onDismiss = onDismissConnect,
            )
        }
    }
}

/** One integration: its name, the ways in, and the logins that already exist. */
@Composable
private fun IntegrationCard(
    row: IntegrationRow,
    onMethodClick: (IntegrationMethod) -> Unit,
    onCredentialAction: (ConnectionInfo.Credential, CredentialAction) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = row.info.name, style = MaterialTheme.typography.titleMedium)
        if (row.info.methods.isEmpty()) {
            Text(
                text = stringResource(R.string.connect_no_methods),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        row.info.methods.forEach { method ->
            MethodRow(method = method, onClick = { onMethodClick(method) })
        }
        if (row.info.credentials.isNotEmpty()) {
            Text(
                text = stringResource(R.string.connect_accounts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        row.info.credentials.forEach { credential ->
            CredentialRow(credential = credential, onAction = onCredentialAction)
        }
        row.info.environment.forEach { env ->
            ListItem(
                headlineContent = { Text(env.name) },
                supportingContent = { Text(stringResource(R.string.connect_env_readonly)) },
                trailingContent = { Text(stringResource(R.string.connect_env_badge)) },
            )
        }
    }
}

/**
 * One method, and what tapping it does.
 *
 * **An `env` method has no button, and that is the design rather than an omission.** The variables
 * can only be set on the host, so the row says where and a button that did nothing would be the
 * dead control a previous phase shipped.
 */
@Composable
private fun MethodRow(method: IntegrationMethod, onClick: () -> Unit) {
    val enabled = when (method) {
        is IntegrationMethod.Key, is IntegrationMethod.OAuth, is IntegrationMethod.Command -> true
        is IntegrationMethod.Env, is IntegrationMethod.Unknown -> false
    }
    ListItem(
        headlineContent = { Text(method.label) },
        supportingContent = {
            Text(
                text = when (method) {
                    is IntegrationMethod.Key -> stringResource(R.string.connect_method_key)

                    is IntegrationMethod.OAuth -> stringResource(R.string.connect_method_oauth)

                    is IntegrationMethod.Command -> stringResource(
                        R.string.connect_method_command,
                        method.command.joinToString(" "),
                    )

                    is IntegrationMethod.Env -> stringResource(R.string.connect_method_env)

                    is IntegrationMethod.Unknown -> stringResource(R.string.connect_method_unknown)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            if (enabled) {
                TextButton(onClick = onClick) { Text(stringResource(R.string.connect_connect)) }
            } else {
                Text(
                    text = stringResource(R.string.connect_read_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.testTag(IntegrationsTags.method(method)),
    )
}

/** One stored credential, and the three actions that are confirmed before they are sent. */
@Composable
private fun CredentialRow(
    credential: ConnectionInfo.Credential,
    onAction: (ConnectionInfo.Credential, CredentialAction) -> Unit,
) {
    ListItem(
        headlineContent = { Text(credential.label) },
        supportingContent = {
            Text(stringResource(if (credential.isKey) R.string.connect_via_key else R.string.connect_via_oauth))
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAction(credential, CredentialAction.ACTIVATE) }) {
                    Text(stringResource(R.string.connect_activate))
                }
                TextButton(onClick = { onAction(credential, CredentialAction.REMOVE) }) {
                    Text(stringResource(R.string.connect_remove))
                }
            }
        },
        modifier = Modifier.testTag(IntegrationsTags.credential(credential.id)),
    )
}

/**
 * The connect sheet, whichever of the four flows is running.
 *
 * **The four are one composable with a `when`, and the branch is chosen by the flow value the state
 * carries** — never by the method's `type` re-inspected here, which is the mistake that would let a
 * command method render a key field.
 *
 * **Public, and the sheet's chrome is the caller's, because a `ModalBottomSheet` cannot be
 * screenshotted.** Roborazzi captures the composition's own nodes, and a `ModalBottomSheet` puts its
 * content in a separate window that the capture does not see — the first recording of this screen
 * produced a PNG of the *background*, with no sheet in it, which is a baseline that asserts nothing.
 * P6 learned the same about a sheet and P7 about a `WebView`; the arrangement is the one P7 used for
 * its terminal: the content is a plain composable the host wraps, and the screenshot photographs the
 * content.
 */
@Composable
fun ConnectSheetContent(
    state: ConnectUiState,
    onLabelChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onAnswer: (String, kotlinx.serialization.json.JsonElement?) -> Unit,
    onSubmit: () -> Unit,
    onSubmitCode: () -> Unit,
    onCancelAttempt: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val active = state.active ?: return
    // The window is made secure for the sheet's lifetime: a key is on screen and the task switcher
    // takes a snapshot of whatever is in front of it.
    SecureWindowEffect()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = active.integrationName, style = MaterialTheme.typography.titleLarge)
        Text(
            text = active.method.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (active.flow) {
            IntegrationFlow.KEY -> SecretKeyField(value = state.keyDraft, onValueChange = onKeyChange)

            IntegrationFlow.OAUTH -> Unit

            IntegrationFlow.COMMAND -> {
                (active.method as? IntegrationMethod.Command)?.let { method ->
                    Text(
                        text = stringResource(R.string.connect_command_runs_here),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = method.command.joinToString(" "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            IntegrationFlow.ENVIRONMENT, IntegrationFlow.UNSUPPORTED -> Unit
        }

        if (state.hasForm) {
            IntegrationFormFields(state = state, onAnswer = onAnswer)
        }

        OutlinedTextField(
            value = state.labelDraft,
            onValueChange = onLabelChange,
            label = { Text(stringResource(R.string.connect_label)) },
            supportingText = { Text(stringResource(R.string.connect_label_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.active?.flow == IntegrationFlow.OAUTH && state.progress.attemptID != null) {
            OAuthProgress(
                state = state,
                onOpenUrl = onOpenUrl,
                onCopyCode = onCopyCode,
                onCodeChange = onCodeChange,
                onSubmitCode = onSubmitCode,
                onCancel = onCancelAttempt,
            )
        }

        if (state.progress.attemptID != null && state.active?.flow == IntegrationFlow.COMMAND) {
            CommandProgress(state = state, onCopyCode = onCopyCode, onCancel = onCancelAttempt)
        }

        state.error?.let { ErrorLine(text = it.message, onDismiss = onDismiss) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSubmit, enabled = state.canSubmit && !state.busy) {
                Text(stringResource(R.string.connect_connect))
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.connect_cancel)) }
        }
    }
}

/**
 * The key field, masked with a show/hide toggle.
 *
 * **The same pattern the pairing screen's password uses**, and for the same reason: a 40-character
 * token cannot be checked by eye without a way to reveal it, and revealing it is a deliberate tap
 * rather than the default state.
 */
@Composable
private fun SecretKeyField(value: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.connect_api_key)) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.connect_key_hide else R.string.connect_key_show,
                    ),
                )
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(IntegrationsTags.KEY_FIELD),
    )
}

/**
 * The method's own form, rendered by the shared forms engine.
 *
 * **The same fields a `question` tool renders, through the same engine.** An integration login's
 * `form` is the same `Form.Field` list, so a second renderer would be a second set of rules — which
 * is how an Azure login ends up sending a field the server calls missing while the app showed a
 * green button.
 */
@Composable
private fun IntegrationFormFields(
    state: ConnectUiState,
    onAnswer: (String, kotlinx.serialization.json.JsonElement?) -> Unit,
) {
    val active = state.active ?: return
    val fields = dev.opencode.android.core.data.integrations.IntegrationForm.fieldsOf(active.method)
    val problems = dev.opencode.android.core.data.integrations.IntegrationForm.problemsOf(
        active.method,
        state.formAnswers,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields
            .filter { dev.opencode.android.core.data.forms.FormEngine.isVisible(it, state.formAnswers) }
            .forEach { field ->
                val problem = problems[field.key]
                OutlinedTextField(
                    value = jsonText(state.formAnswers[field.key]),
                    onValueChange = { text ->
                        // The engine owns how an answer is represented; the field only reports the
                        // text and the key, so a second renderer cannot disagree with it.
                        onAnswer(field.key, text.takeIf { it.isNotEmpty() }?.let { FormValues.string(it) })
                    },
                    label = { Text(field.labelOrKey()) },
                    isError = problem != null,
                    supportingText = problem?.let { p -> { Text(stringResource(problemRes(p))) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(IntegrationsTags.formField(field.key)),
                )
            }
    }
}

private fun jsonText(value: kotlinx.serialization.json.JsonElement?): String =
    (value as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

/**
 * The OAuth half of the sheet: instructions, the button that opens the provider, and the progress.
 *
 * **The "open" button is only rendered when [ConnectUiState.canOpenOauthUrl].** That value is the
 * *checked* URL, not the server's, so there is no arrangement of this composable in which a
 * `javascript:` or `intent://` value reaches a Custom Tab — the unvalidated one is not on the state
 * at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OAuthProgress(
    state: ConnectUiState,
    onOpenUrl: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The server's own words. Untrusted text, shown verbatim and never interpreted as markup.
        if (state.oauthInstructions.isNotBlank()) {
            Text(text = state.oauthInstructions, style = MaterialTheme.typography.bodyMedium)
        }
        val url = state.oauthAttemptUrl
        if (state.canOpenOauthUrl && url != null) {
            Button(
                onClick = { onOpenUrl(url) },
                modifier = Modifier.testTag(IntegrationsTags.OPEN_OAUTH),
            ) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null)
                Text(
                    text = stringResource(R.string.connect_open_provider),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (state.needsCode) {
            OutlinedTextField(
                value = state.codeDraft,
                onValueChange = onCodeChange,
                label = { Text(stringResource(R.string.connect_device_code)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(IntegrationsTags.CODE_FIELD),
            )
            Button(onClick = onSubmitCode, enabled = state.canSubmitCode) {
                Text(stringResource(R.string.connect_submit_code))
            }
        }
        AttemptProgress(state = state, onCancel = onCancel)
    }
}

/** The command half: what the host printed, and a copy button for a device code in it. */
@Composable
private fun CommandProgress(state: ConnectUiState, onCopyCode: (String) -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            SelectionContainer {
                Text(
                    text = state.progress.output.ifBlank { stringResource(R.string.connect_command_waiting) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 240.dp)
                        .padding(8.dp),
                )
            }
        }
        state.deviceCode?.let { code ->
            AssistChip(
                onClick = { onCopyCode(code) },
                label = { Text(code) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                modifier = Modifier.testTag(IntegrationsTags.COPY_CODE),
            )
        }
        AttemptProgress(state = state, onCancel = onCancel)
    }
}

/** The spinner, the cancel button and the reason, which are the same for both flows. */
@Composable
private fun AttemptProgress(state: ConnectUiState, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.isPolling) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics {
                    contentDescription = "waiting for the sign-in"
                },
            ) {
                CircularProgressIndicator(modifier = Modifier.heightIn(max = 20.dp))
                Text(
                    text = stringResource(R.string.connect_waiting),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        val reason = when (val attempt = state.progress.state) {
            is ConnectAttemptState.Failed -> attempt.message
            ConnectAttemptState.Expired -> stringResource(R.string.connect_attempt_expired)
            ConnectAttemptState.Cancelled -> stringResource(R.string.connect_attempt_cancelled)
            ConnectAttemptState.Complete -> stringResource(R.string.connect_attempt_complete)
            else -> null
        }
        if (reason != null) {
            Text(text = reason, style = MaterialTheme.typography.bodySmall)
        }
        if (state.canCancel) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(IntegrationsTags.CANCEL_ATTEMPT)) {
                Text(stringResource(R.string.connect_cancel_attempt))
            }
        }
    }
}

/** Adding a well-known integration source, which is behind its own switch. */
@Composable
private fun WellknownSourceCard(
    url: String,
    canAdd: Boolean,
    busy: Boolean,
    onUrlChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = stringResource(R.string.connect_wellknown_title), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(R.string.connect_wellknown_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.connect_wellknown_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onAdd, enabled = canAdd && !busy) {
            Text(stringResource(R.string.connect_wellknown_add))
        }
    }
}

/**
 * The confirmation a destructive or account-changing action needs (plan §5.2).
 *
 * **Both actions go through it.** Removing a credential has no undo and no way to recover the key;
 * activating one silently changes which account every later request uses. Neither is a tap the app
 * should take on the user's behalf, and the dialog names the credential so a mistap is visible.
 */
@Composable
private fun CredentialConfirmDialog(
    pending: PendingCredentialAction,
    onLabelChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(titleFor(pending.action))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(bodyFor(pending.action), pending.connection.label))
                if (pending.action == CredentialAction.RENAME) {
                    OutlinedTextField(
                        value = pending.label,
                        onValueChange = onLabelChange,
                        label = { Text(stringResource(R.string.connect_label)) },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.connect_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.connect_cancel)) }
        },
    )
}

private fun titleFor(action: CredentialAction) = when (action) {
    CredentialAction.RENAME -> R.string.connect_rename_title
    CredentialAction.ACTIVATE -> R.string.connect_activate_title
    CredentialAction.REMOVE -> R.string.connect_remove_title
}

private fun bodyFor(action: CredentialAction) = when (action) {
    CredentialAction.RENAME -> R.string.connect_rename_body
    CredentialAction.ACTIVATE -> R.string.connect_activate_body
    CredentialAction.REMOVE -> R.string.connect_remove_body
}

@Composable
internal fun ErrorLine(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.connect_dismiss)) }
    }
}

@Composable
internal fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** The string a form problem is shown with. */
private fun problemRes(problem: dev.opencode.android.core.data.forms.FieldProblem) = when (problem) {
    dev.opencode.android.core.data.forms.FieldProblem.REQUIRED -> R.string.form_problem_required
    dev.opencode.android.core.data.forms.FieldProblem.PATTERN -> R.string.form_problem_pattern
    dev.opencode.android.core.data.forms.FieldProblem.TOO_SHORT -> R.string.form_problem_too_short
    dev.opencode.android.core.data.forms.FieldProblem.TOO_LONG -> R.string.form_problem_too_long
    dev.opencode.android.core.data.forms.FieldProblem.BELOW_MINIMUM -> R.string.form_problem_below_minimum
    dev.opencode.android.core.data.forms.FieldProblem.ABOVE_MAXIMUM -> R.string.form_problem_above_maximum
    dev.opencode.android.core.data.forms.FieldProblem.NOT_AN_INTEGER -> R.string.form_problem_not_integer
    dev.opencode.android.core.data.forms.FieldProblem.NOT_AN_OPTION -> R.string.form_problem_not_option
    dev.opencode.android.core.data.forms.FieldProblem.TOO_FEW -> R.string.form_problem_too_few
    dev.opencode.android.core.data.forms.FieldProblem.TOO_MANY -> R.string.form_problem_too_many
}
