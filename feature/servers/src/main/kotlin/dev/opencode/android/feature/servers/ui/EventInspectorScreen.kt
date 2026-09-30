package dev.opencode.android.feature.servers.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.designsystem.text.SyncedTextField
import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.network.ConnectionState
import dev.opencode.android.core.network.InspectedEvent
import dev.opencode.android.feature.servers.R
import kotlinx.serialization.json.JsonElement
import java.text.DateFormat
import java.util.Date

/**
 * The developer event inspector: every frame the connection receives, with the raw JSON of the
 * selected one (plan §6, Phase 1).
 *
 * Later phases debug through this screen, so the list is capped by the client, not by the UI, and the
 * search covers the raw payload as well as the type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventInspectorScreen(
    onNavigateBack: () -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EventInspectorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val events by viewModel.filteredEvents.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedText = stringResource(R.string.inspector_copied)

    LaunchedEffect(uiState.copiedMessage) {
        val message = uiState.copiedMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.setCopiedMessage(null)
        }
    }

    Scaffold(
        modifier = modifier.testTag(EventInspectorTags.SCREEN),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.inspector_title))
                        if (uiState.serverName.isNotEmpty()) {
                            Text(
                                text = uiState.serverName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.inspector_navigate_back),
                        )
                    }
                },
                actions = {
                    val paused = uiState.isPaused
                    IconButton(
                        onClick = viewModel::togglePause,
                        modifier = Modifier.testTag(EventInspectorTags.PAUSE_BUTTON),
                    ) {
                        Icon(
                            imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = stringResource(
                                if (paused) R.string.inspector_resume else R.string.inspector_pause,
                            ),
                        )
                    }
                    IconButton(
                        onClick = viewModel::clearEvents,
                        modifier = Modifier.testTag(EventInspectorTags.CLEAR_BUTTON),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.inspector_clear),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SyncedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                placeholder = { Text(stringResource(R.string.inspector_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.inspector_clear_search),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(EventInspectorTags.SEARCH_INPUT),
                singleLine = true,
            )

            if (uiState.serverId == null) {
                EmptyInspector(onAddServer = onAddServer)
                return@Column
            }

            if (uiState.connectionState !is ConnectionState.Connected || uiState.isPaused) {
                val label = stringResource(
                    if (uiState.isPaused) R.string.inspector_paused else R.string.state_connecting,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .semantics { contentDescription = label },
                ) {
                    Text(
                        text = stringResource(
                            R.string.status_info_row,
                            stringResource(R.string.state_label),
                            stringResource(uiState.connectionState.labelRes()),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            if (events.isEmpty()) {
                EmptyInspector(onAddServer = onAddServer, showAddButton = false)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(events, key = { it.index }) { event ->
                        EventRow(
                            entry = event,
                            onClick = { viewModel.selectEvent(event) },
                        )
                    }
                }
            }
        }
    }

    uiState.selectedEvent?.let { selected ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.selectEvent(null) },
            sheetState = sheetState,
            modifier = Modifier.testTag(EventInspectorTags.DETAIL_SHEET),
        ) {
            EventDetailSheet(
                entry = selected,
                onCopyJson = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Event JSON", selected.rawJson))
                    viewModel.setCopiedMessage(copiedText)
                },
            )
        }
    }
}

object EventInspectorTags {
    const val SCREEN = "event_inspector_screen"
    const val SEARCH_INPUT = "inspector_search_input"
    const val PAUSE_BUTTON = "inspector_pause_button"
    const val CLEAR_BUTTON = "inspector_clear_button"
    const val DETAIL_SHEET = "event_detail_sheet"
}

@Composable
private fun EmptyInspector(
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
    showAddButton: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                if (showAddButton) R.string.inspector_no_server else R.string.inspector_empty,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showAddButton) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAddServer) { Text(stringResource(R.string.servers_add_server)) }
        }
    }
}

@Composable
private fun EventRow(
    entry: InspectedEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFormat = ClockFormatter.timeWithMillis(entry.receivedAt)
    val formattedTime = timeFormat.format(Date(entry.receivedAt))
    val description = stringResource(R.string.inspector_event_row, entry.index, entry.type)
    val preview = entry.rawJson.take(PREVIEW_CHARS) +
        if (entry.rawJson.length > PREVIEW_CHARS) stringResource(R.string.inspector_truncated) else ""

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("event_row_${entry.index}"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "#${entry.index}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = entry.type,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun EventDetailSheet(
    entry: InspectedEvent,
    onCopyJson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.type,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
            )
            Button(onClick = onCopyJson) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.inspector_copy_json))
            }
        }

        Text(
            text = stringResource(R.string.inspector_event_meta, entry.id, entry.type),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        entry.event.location?.let { location ->
            Text(
                text = stringResource(
                    R.string.status_info_row,
                    stringResource(R.string.inspector_directory_label),
                    location.directory,
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        entry.event.durable?.let { durable ->
            Text(
                text = stringResource(
                    R.string.inspector_event_durable,
                    durable.seq,
                    durable.version,
                    durable.aggregateID,
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Text(
            text = stringResource(R.string.inspector_json),
            style = MaterialTheme.typography.labelLarge,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(12.dp),
        ) {
            Text(
                text = prettyJson(entry.rawJson),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** Indents the payload, because a single long line is unreadable in a bottom sheet. */
private fun prettyJson(raw: String): String = runCatching {
    val element: JsonElement = OpenCodeJson.parseToJsonElement(raw)
    OpenCodeJson.encodeToString(JsonElement.serializer(), element)
}.getOrDefault(raw)

private const val PREVIEW_CHARS = 160
