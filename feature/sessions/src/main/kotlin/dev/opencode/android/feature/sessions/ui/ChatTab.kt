package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.android.core.data.preferences.ProjectPreferences
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.data.sync.SyncStatus
import dev.opencode.android.core.designsystem.component.OpenCodeEmptyState
import dev.opencode.android.feature.sessions.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the bottom bar's Chat tab opens. */
sealed interface ChatTarget {
    /** The server has not answered yet, so "nothing to open" is not yet a fact. */
    data object Loading : ChatTarget

    data class Open(val sessionId: String) : ChatTarget

    /** There is no session on this server at all. */
    data object Empty : ChatTarget
}

/**
 * The Chat tab's session: the one last on screen, then the most recently updated root session, then nothing.
 *
 * The last one is trusted until the server has answered, so the tab opens at once on a cold start; once the list
 * has loaded, a last session the server no longer has (deleted on the desktop) falls through to the newest one
 * rather than opening a timeline that cannot load.
 */
fun chatTarget(last: String?, rows: List<SessionRow>, loaded: Boolean): ChatTarget {
    if (last != null && (!loaded || rows.any { it.id == last })) return ChatTarget.Open(last)
    val newest = rows.filter { !it.isChild }.maxByOrNull { it.updated }
    return when {
        newest != null -> ChatTarget.Open(newest.id)
        loaded -> ChatTarget.Empty
        else -> ChatTarget.Loading
    }
}

/** Resolves the Chat tab against the active server. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatTabViewModel @Inject constructor(
    dataSets: ServerDataRegistry,
    projectPreferences: ProjectPreferences,
) : ViewModel() {
    val target: StateFlow<ChatTarget> = dataSets.active
        .flatMapLatest { set ->
            if (set == null) {
                flowOf(ChatTarget.Loading)
            } else {
                set.sessions.start()
                combine(
                    projectPreferences.lastSession(set.serverId),
                    set.sessions.rows,
                    set.sessions.paging,
                    set.projects.state,
                ) { last, rows, paging, projects ->
                    val loaded = !paging.loading && (projects.value != null || projects.status is SyncStatus.Failed)
                    chatTarget(last, rows, loaded)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ChatTarget.Loading)
}

/**
 * The Chat tab. It is a redirect, not a screen: it hands the resolved session to [onOpenSession], which replaces
 * this entry with the session, and only draws something while it is resolving or when there is nothing to open.
 */
@Composable
fun ChatTabRoute(
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatTabViewModel = hiltViewModel(),
) {
    val target by viewModel.target.collectAsStateWithLifecycle()
    LaunchedEffect(target) {
        (target as? ChatTarget.Open)?.let { onOpenSession(it.sessionId) }
    }
    when (target) {
        ChatTarget.Empty -> OpenCodeEmptyState(
            title = stringResource(R.string.chat_empty_title),
            subtitle = stringResource(R.string.chat_empty_subtitle),
            icon = Icons.AutoMirrored.Outlined.Chat,
            modifier = modifier.fillMaxSize(),
            action = { Button(onClick = onNewSession) { Text(stringResource(R.string.home_new_session)) } },
        )

        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

private const val STOP_TIMEOUT_MILLIS = 5_000L
