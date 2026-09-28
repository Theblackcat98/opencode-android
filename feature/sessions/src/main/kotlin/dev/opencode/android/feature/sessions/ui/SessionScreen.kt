package dev.opencode.android.feature.sessions.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.format.Formatters
import kotlinx.coroutines.launch
import dev.opencode.android.feature.sessions.R
import dev.opencode.android.feature.sessions.ui.timeline.TimelineList

/**
 * One session: the header, the timeline, and follow mode.
 *
 * **Follow mode is what makes this usable while a turn runs.** The list follows the newest message
 * while it is on screen; scrolling up turns following off and reveals "jump to the latest", so a
 * user reading an earlier answer is never yanked away by a token arriving. The offset from the
 * bottom is derived state rather than a remembered flag, which is why leaving the screen and coming
 * back does not lose it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    state: SessionUiState,
    onNavigateBack: () -> Unit,
    onLoadOlder: () -> Unit,
    onFollowChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            last >= info.totalItemsCount - 1
        }
    }
    // One past the last message: the list's first item is the "load older" row.
    val jumpTarget = state.messages.size
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.following, state.messages.size) {
        if (state.following && state.messages.isNotEmpty()) {
            listState.scrollToItem(jumpTarget)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.title.ifBlank { stringResource(R.string.session_title_untitled) },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Subtitle(state)
                    }
                },
                navigationIcon = {
                    Text(
                        text = "←",
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .semantics { contentDescription = "" },
                    )
                },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = !atBottom) {
                ExtendedFloatingActionButton(
                    onClick = {
                        onFollowChange(true)
                        if (state.messages.isNotEmpty()) {
                            scope.launch { listState.scrollToItem(jumpTarget) }
                        }
                    },
                    icon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                    text = { Text(stringResource(R.string.session_jump_to_latest)) },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            TimelineList(
                messages = state.messages,
                loadingOlder = state.paging.loading,
                hasMore = state.paging.hasMore,
                following = state.following,
                onLoadOlder = onLoadOlder,
                listState = listState,
                contentPadding = PaddingValues(12.dp),
                modifier = Modifier.fillMaxSize(),
            )
            if (state.activity is SessionActivityUi.Running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

/** Title, agent, model, context gauge and cost: everything the plan's header carries. */
@Composable
private fun Subtitle(state: SessionUiState) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            state.agent?.let { agent ->
                Text(
                    text = agent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            state.model?.let { model ->
                Spacer(Modifier.width(8.dp))
                Text(
                    text = model.variant?.let { "${model.label}#$it" } ?: model.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (val activity = state.activity) {
                is SessionActivityUi.Running -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                )

                is SessionActivityUi.Retrying -> Text(
                    text = stringResource(R.string.sessions_retry_attempt, activity.attempt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )

                SessionActivityUi.Idle -> Unit
            }
        }
        if (state.contextLimit > 0) {
            val gaugeLabel = stringResource(
                R.string.session_context_gauge,
                state.contextPercent,
                Formatters.tokens(state.contextLimit),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { state.contextPercent / 100f },
                    modifier = Modifier.width(80.dp).height(4.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = gaugeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = gaugeLabel },
                )
            }
        }
        if (state.cost > 0.0) {
            val costLabel = stringResource(R.string.session_cost, Formatters.cost(state.cost))
            Text(
                text = costLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = costLabel },
            )
        }
    }
}

/** The offline notice: shown when the timeline came from the cache and no server has answered. */
@Composable
fun OfflineNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = stringResource(R.string.session_offline_notice),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(8.dp),
        )
    }
}
