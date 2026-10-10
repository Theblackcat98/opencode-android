package dev.opencode.android.feature.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Quick ask: one prompt, one answer, and nothing kept (matrix H5).
 *
 * An absent route is said once, at the top, and the prompt is disabled with it: a box that accepts text and
 * then fails every ask would look like a broken app rather than a server without the feature.
 */
@Composable
fun QuickAskScreen(
    state: QuickAskUiState,
    onPromptChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onAsk: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.available) {
            Text(stringResource(R.string.quick_ask_absent), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                stringResource(R.string.quick_ask_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = state.model,
            onValueChange = onModelChange,
            label = { Text(stringResource(R.string.quick_ask_model)) },
            enabled = state.available && !state.asking,
            singleLine = true,
            isError = state.modelInvalid,
            supportingText = {
                Text(
                    stringResource(
                        if (state.modelInvalid) R.string.quick_ask_model_invalid else R.string.quick_ask_model_help,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.prompt,
            onValueChange = onPromptChange,
            label = { Text(stringResource(R.string.quick_ask_prompt)) },
            enabled = state.available && !state.asking,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = onAsk, enabled = state.canAsk) {
            Text(stringResource(R.string.quick_ask_ask))
        }

        if (state.asking) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.quick_ask_asking), style = MaterialTheme.typography.bodyMedium)
            }
        }

        state.error?.let { error -> InsightsErrorCard(error.message, onDismissError) }

        state.answer?.let { answer ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.quick_ask_answer), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(answer, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
