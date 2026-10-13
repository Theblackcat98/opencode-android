package dev.opencode.android.feature.sessions.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.designsystem.component.OpenCodeStatusDot
import dev.opencode.android.core.designsystem.component.OpenCodeStatusPill
import dev.opencode.android.core.designsystem.component.OpenCodeTopAppBar
import dev.opencode.android.core.designsystem.component.StatusKind
import dev.opencode.android.core.designsystem.format.Formatters
import dev.opencode.android.core.designsystem.theme.OpenCodeThemeExtras
import dev.opencode.android.feature.sessions.R
import dev.opencode.android.feature.sessions.ui.timeline.TimelineList
import kotlinx.coroutines.launch

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
    /** Everything the agent is blocked on, rendered by the requests feature above the timeline. */
    requestSlot: @Composable () -> Unit = {},
    /** The composer and the turn's control buttons, rendered below the timeline. */
    composerSlot: @Composable () -> Unit = {},
    /** The tools button that opens the session's tools sheet (review, shells, terminal, rename, delete…). */
    menuSlot: @Composable () -> Unit = {},
    /** The review of what this session changed, one tap from the top bar. Null hides the action. */
    onOpenReview: (() -> Unit)? = null,
    /** Opens a subagent's session from its tool row. */
    onOpenSession: ((String) -> Unit)? = null,
    /** Opens a provider's action link from a retry, such as "usage exceeded, upgrade". */
    onOpenLink: (String) -> Unit = {},
    /**
     * Opens a file a message changed in the review viewer.
     *
     * `null` hides the links. It is a callback and not a screen because the review is another
     * feature and a feature may not import another feature, so the app module — which composes both
     * — is the only place that can hand one to the other.
     */
    onOpenChangedFile: ((String) -> Unit)? = null,
    /** The per-message actions, which is where "revert to here" and "fork from here" live. */
    messageActions: (@Composable (String) -> Unit)? = null,
    /**
     * Drawn between the request dock and the transcript, and above the composer.
     *
     * A slot rather than a dependency because the strip is Phase 7's and the session screen is
     * Phase 2's: a feature may not import another feature, so the app module — which composes both —
     * supplies it, and `{}` draws nothing on a build that does not offer it.
     */
    subagentSlot: @Composable () -> Unit = {},
    /**
     * The open sessions of the active server, drawn under the top bar when there are two or more.
     * See [SessionTabStrip].
     */
    tabs: List<SessionTabUi> = emptyList(),
    onSelectTab: (String) -> Unit = {},
    onCloseTab: (String) -> Unit = {},
) {
    val spacing = OpenCodeThemeExtras.spacing
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
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !atBottom && state.following) {
            onFollowChange(false)
        }
    }

    val title = state.title.ifBlank { stringResource(R.string.session_title_untitled) }
    val gaugeLabel = if (state.contextLimit > 0) {
        stringResource(R.string.session_context_gauge, state.contextPercent, Formatters.tokens(state.contextLimit))
    } else {
        null
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            // One line of title and one of who is answering and how it is going. The context gauge is the bar's own
            // bottom edge: it was a labelled third row, which made the header taller than two messages.
            OpenCodeTopAppBar(
                title = title,
                onBack = onNavigateBack,
                backDescription = stringResource(R.string.session_close),
                subtitle = { Subtitle(state) },
                progress = gaugeLabel?.let { state.contextPercent / 100f },
                progressDescription = gaugeLabel,
                actions = {
                    if (onOpenReview != null) {
                        IconButton(onClick = onOpenReview) {
                            Icon(Icons.Outlined.Difference, contentDescription = stringResource(R.string.session_open_review))
                        }
                    }
                    menuSlot()
                },
            )
        },
        bottomBar = {
            // The keyboard overlaps the composer without this; the activity's adjustResize alone does
            // not move the bottom bar, which sits outside the inset-consuming content.
            Box(modifier = Modifier.imePadding()) { composerSlot() }
        },
        floatingActionButton = {
            AnimatedVisibility(visible = !atBottom, enter = fadeIn(), exit = fadeOut()) {
                // The M3 small-FAB spec: default shape and secondaryContainer. The old CircleShape +
                // surfaceContainerHighest read as a custom chip, not a FAB.
                SmallFloatingActionButton(
                    onClick = {
                        onFollowChange(true)
                        if (state.messages.isNotEmpty()) {
                            scope.launch { listState.scrollToItem(jumpTarget) }
                        }
                    },
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.session_jump_to_latest),
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SessionTabStrip(tabs = tabs, onSelect = onSelectTab, onClose = onCloseTab)
            state.retry?.let { retry ->
                RetryBanner(retry = retry, now = state.now, onOpenLink = onOpenLink)
            }
            requestSlot()
            subagentSlot()
            TimelineList(
                messages = state.messages,
                loadingOlder = state.paging.loading,
                hasMore = state.paging.hasMore,
                following = state.following,
                onLoadOlder = onLoadOlder,
                listState = listState,
                contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onOpenChangedFile = onOpenChangedFile,
                messageActions = messageActions,
                onOpenSession = onOpenSession,
                // A retry is still the same turn, so only a settled session announces its turn end.
                sessionRunning = state.activity != SessionActivityUi.Idle,
            )
        }
    }
}

/**
 * Who is answering and how it is going, on one line: a fork marker, the agent, the model, the turn's state and
 * the cost. Everything that does not fit is ellipsized, and the whole line is one description for a screen reader.
 */
@Composable
private fun Subtitle(state: SessionUiState) {
    val forked = state.forkedAt?.let { stringResource(R.string.session_forked_from, it) }
    val cost = state.cost.takeIf { it > 0.0 }?.let { stringResource(R.string.session_cost, Formatters.cost(it)) }
    val model = state.model?.let { model -> model.variant?.let { "${model.label}#$it" } ?: model.label }
    val running = stringResource(R.string.sessions_running_description)
    val activity = when (val activity = state.activity) {
        is SessionActivityUi.Running -> running
        is SessionActivityUi.Retrying -> stringResource(R.string.sessions_retry_attempt, activity.attempt)
        SessionActivityUi.Idle -> null
    }
    val description = listOfNotNull(forked, state.agent, model, activity, cost).joinToString(", ")
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (val activity = state.activity) {
            is SessionActivityUi.Running -> OpenCodeStatusDot(StatusKind.Running)

            is SessionActivityUi.Retrying -> OpenCodeStatusPill(
                StatusKind.Retrying,
                stringResource(R.string.sessions_retry_attempt, activity.attempt),
            )

            SessionActivityUi.Idle -> Unit
        }
        if (forked != null) {
            Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, modifier = Modifier.size(14.dp))
        }
        val line = listOfNotNull(state.agent, model, state.cost.takeIf { it > 0.0 }?.let { Formatters.cost(it) })
            .joinToString(" · ")
        Text(text = line, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
