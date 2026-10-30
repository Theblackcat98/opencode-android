package dev.opencode.android.feature.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.tui.TuiControl
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.json.JsonElement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The plugin RPC console (matrix H6 and H7) and event viewer.
 *
 * The answer is shown as the JSON the server sent, in a monospace face, because nothing about its shape is
 * known to the app. A refusal from the surface (an id or method that would break the URL) arrives as the
 * error card, with the server's own words for it, and no request was made.
 */
@Composable
fun RpcConsoleScreen(
    state: RpcConsoleUiState,
    events: List<TuiControl.RpcEvent>,
    onTabChange: (RpcConsoleTab) -> Unit,
    onRpcIDChange: (String) -> Unit,
    onMethodChange: (String) -> Unit,
    onInputChange: (String) -> Unit,
    onCall: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = state.tab.ordinal) {
            Tab(
                selected = state.tab == RpcConsoleTab.CALL,
                onClick = { onTabChange(RpcConsoleTab.CALL) },
                text = { Text(stringResource(R.string.rpc_tab_call)) },
            )
            Tab(
                selected = state.tab == RpcConsoleTab.EVENTS,
                onClick = { onTabChange(RpcConsoleTab.EVENTS) },
                text = { Text(stringResource(R.string.rpc_tab_events)) },
            )
        }

        when (state.tab) {
            RpcConsoleTab.CALL -> {
                CallConsole(
                    state = state,
                    onRpcIDChange = onRpcIDChange,
                    onMethodChange = onMethodChange,
                    onInputChange = onInputChange,
                    onCall = onCall,
                    onDismissError = onDismissError,
                )
            }

            RpcConsoleTab.EVENTS -> {
                EventsViewer(events = events)
            }
        }
    }
}

@Composable
private fun CallConsole(
    state: RpcConsoleUiState,
    onRpcIDChange: (String) -> Unit,
    onMethodChange: (String) -> Unit,
    onInputChange: (String) -> Unit,
    onCall: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(OpenCodeThemeExtras.spacing.lg),
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

@Composable
private fun EventsViewer(events: List<TuiControl.RpcEvent>) {
    if (events.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(OpenCodeThemeExtras.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.rpc_events_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        // Reverse so the newest event is at the top.
        val reversed = events.reversed()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(OpenCodeThemeExtras.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(OpenCodeThemeExtras.spacing.md),
        ) {
            items(items = reversed, key = { "${it.rpcID}-${it.event}-${it.at}-${System.identityHashCode(it)}" }) { event ->
                EventCard(event)
            }
        }
    }
}

@Composable
private fun EventCard(event: TuiControl.RpcEvent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OpenCodeThemeExtras.spacing.md),
            verticalArrangement = Arrangement.spacedBy(OpenCodeThemeExtras.spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = event.type,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.US
                Text(
                    text = SimpleDateFormat("HH:mm:ss.SSS", locale).format(Date(event.at)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.rpc_event_source, event.rpcID),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )

            if (event.data.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.rpc_event_payload),
                    style = MaterialTheme.typography.labelMedium,
                )
                val payloadString = runCatching {
                    // Try to pretty print the payload
                    OpenCodeJson.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonObject(event.data))
                }.getOrElse { event.data.toString() }

                val bounded = if (payloadString.length <= 5000) {
                    payloadString
                } else {
                    payloadString.take(5000) + "\n… [output truncated]"
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = MaterialTheme.shapes.small,
                        )
                        .padding(OpenCodeThemeExtras.spacing.sm),
                ) {
                    SelectionContainer {
                        Text(
                            text = bounded,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
