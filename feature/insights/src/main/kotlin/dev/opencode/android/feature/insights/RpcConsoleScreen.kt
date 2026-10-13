package dev.opencode.android.feature.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras

/**
 * The plugin RPC console (matrix H6 and H7).
 *
 * The answer is shown as the JSON the server sent, in a monospace face, because nothing about its shape is
 * known to the app. A refusal from the surface (an id or method that would break the URL) arrives as the
 * error card, with the server's own words for it, and no request was made.
 */
@Composable
fun RpcConsoleScreen(
    state: RpcConsoleUiState,
    onRpcIDChange: (String) -> Unit,
    onMethodChange: (String) -> Unit,
    onInputChange: (String) -> Unit,
    onCall: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCodeThemeExtras.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(OpenCodeThemeExtras.spacing.md),
    ) {
        Text(
            stringResource(R.string.rpc_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.rpcID,
            onValueChange = onRpcIDChange,
            label = { Text(stringResource(R.string.rpc_id)) },
            enabled = !state.calling,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.method,
            onValueChange = onMethodChange,
            label = { Text(stringResource(R.string.rpc_method)) },
            enabled = !state.calling,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.input,
            onValueChange = onInputChange,
            label = { Text(stringResource(R.string.rpc_input)) },
            enabled = !state.calling,
            isError = state.inputInvalid,
            supportingText = if (state.inputInvalid) {
                { Text(stringResource(R.string.rpc_input_invalid)) }
            } else {
                null
            },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = onCall, enabled = state.canCall) {
            Text(stringResource(R.string.rpc_call))
        }

        if (state.calling) {
            CircularProgressIndicator()
            Text(stringResource(R.string.rpc_calling), style = MaterialTheme.typography.bodyMedium)
        }

        state.error?.let { message -> InsightsErrorCard(message, onDismissError) }

        state.output?.let { output ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(OpenCodeThemeExtras.spacing.lg)) {
                    Text(stringResource(R.string.rpc_output), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(output, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
