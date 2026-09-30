package dev.opencode.android.feature.requests.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.feature.requests.R

/**
 * The global pending-requests inbox: everything the agent is blocked on, across every session of a
 * server (plan §4.3, Home).
 *
 * **It is the same store the session dock reads**, so a request answered here disappears from the
 * session it came from, and one answered there disappears from here. Each row names its session and
 * opens it, because a permission is only answerable in the context of what asked for it.
 *
 * **A failed answer is said here.** The answer is sent by a composer that never opens a session, and it records
 * a refusal (`404` gone, `409` already settled, the server unreachable) in its own state; this screen used to
 * draw only the requests, so "Allow once" that did not reach the server looked like a button that did nothing.
 * [error] is that failure, worded by [displayMessage] and kept until [onDismissError] or the next answer, and the
 * request it was about stays in [requests] because the server has not echoed an answer for it.
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
    error: ActionError? = null,
    onDismissError: () -> Unit = {},
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Above the list and not a snackbar: a snackbar goes after a few seconds, and this is about a
            // request that is still waiting below it.
            error?.let { AnswerFailedBanner(error = it, onDismiss = onDismissError) }
            if (requests.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
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
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
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
    }
}

/**
 * An answer the server did not take, above the requests that are still waiting.
 *
 * The heading says what did not happen (nothing was sent that changed the request) and the line under it says
 * why, in the words the composer uses for the same failure. A live region, so a screen reader announces it: the
 * button that was just pressed is the only other place the user's attention is.
 */
@Composable
private fun AnswerFailedBanner(error: ActionError, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    text = stringResource(R.string.requests_inbox_answer_failed),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(text = error.displayMessage(), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}
