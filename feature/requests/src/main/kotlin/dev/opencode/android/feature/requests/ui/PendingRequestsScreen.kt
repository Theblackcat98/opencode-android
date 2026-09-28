package dev.opencode.android.feature.requests.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.feature.requests.R

/**
 * The global pending-requests inbox: everything the agent is blocked on, across every session of a
 * server (plan §4.3, Home).
 *
 * **It is the same store the session dock reads**, so a request answered here disappears from the
 * session it came from, and one answered there disappears from here. Each row names its session and
 * opens it, because a permission is only answerable in the context of what asked for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingRequestsScreen(
    requests: List<PendingRequest>,
    sessionTitles: Map<String, String>,
    actions: RequestActions,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.requests_inbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_dismiss))
                    }
                },
            )
        },
    ) { padding ->
        if (requests.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.requests_inbox_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.requests_inbox_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(requests, key = { it.id }) { request ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = sessionTitles[request.sessionID]
                            ?: stringResource(R.string.requests_inbox_open_session),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (request) {
                        is PendingRequest.Form -> FormRequest(request, actions, busy = busy)
                        is PendingRequest.Permission -> PermissionCard(request.request, actions, busy = busy)
                    }
                }
            }
        }
    }
}
