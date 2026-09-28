package dev.opencode.android.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.data.catalog.ModelCatalog
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.feature.composer.ui.AgentPickerSheet
import dev.opencode.android.feature.composer.ui.ComposerBar
import dev.opencode.android.feature.composer.ui.ComposerViewModel
import dev.opencode.android.feature.composer.ui.InboxPanel
import dev.opencode.android.feature.composer.ui.ModelPickerSheet
import dev.opencode.android.feature.requests.ui.RequestActions
import dev.opencode.android.feature.requests.ui.RequestDock
import dev.opencode.android.feature.sessions.R
import dev.opencode.android.feature.sessions.ui.SessionActionsSheet
import dev.opencode.android.feature.sessions.ui.SessionManagementViewModel
import dev.opencode.android.feature.sessions.ui.SessionScreen
import dev.opencode.android.feature.sessions.ui.SessionViewModel

/**
 * One session, assembled from the three features that own parts of it.
 *
 * The composition root is here, in the app module, and not in a feature: a feature depends on the
 * core modules only (see the feature convention plugin), so the session screen, the composer and the
 * request dock cannot import one another. This is the one place that knows the session screen is the
 * dock above the timeline and the composer below it, and it holds no logic of its own — the state is
 * the server's projection and the actions are the operations.
 *
 * **Deleting leaves the screen.** Once `session.remove` succeeds the transcript is gone from the
 * server's point of view, so staying on it would show a conversation that no longer exists.
 */
@Composable
fun SessionHost(
    sessionId: String,
    onNavigateBack: () -> Unit,
    onSessionDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    timeline: SessionViewModel = hiltViewModel(),
    composer: ComposerViewModel = hiltViewModel(),
    management: SessionManagementViewModel = hiltViewModel(),
) {
    val state by timeline.state.collectAsStateWithLifecycle()
    val composerState by composer.state.collectAsStateWithLifecycle()
    val childCount by management.childCount.collectAsStateWithLifecycle()
    val favorites by composer.modelFavorites.collectAsStateWithLifecycle()
    val recents by composer.modelRecents.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val menu = stringResource(R.string.session_menu)
    var actionsOpen by remember { mutableStateOf(false) }
    var inboxOpen by remember { mutableStateOf(false) }
    var agentPickerOpen by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }
    var modelSearch by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(sessionId) {
        timeline.open(sessionId)
        composer.open(sessionId)
        management.openSession(sessionId)
    }

    val requestActions = RequestActions(
        onReplyOnce = { composer.replyPermission(it.request, PermissionReply.Once) },
        onReplyAlways = { composer.replyPermission(it.request, PermissionReply.Always) },
        onReject = { request, feedback ->
            composer.replyPermission(request.request, PermissionReply.Reject, feedback)
        },
        onSubmitForm = { form, answer -> composer.submitForm(form.form, answer) },
        onCancelForm = { composer.cancelForm(it.form) },
        onOpenLink = { uriHandler.openUri(it) },
    )

    SessionScreen(
        state = state,
        onNavigateBack = onNavigateBack,
        onLoadOlder = timeline::loadOlder,
        onFollowChange = timeline::setFollowing,
        onOpenLink = { uriHandler.openUri(it) },
        modifier = modifier,
        requestSlot = { RequestDock(requests = composerState.requests, actions = requestActions) },
        composerSlot = {
            ComposerBar(
                state = composerState,
                onTextChange = composer::setText,
                onSend = { composer.send() },
                onSendQueued = composer::sendQueued,
                onDeliveryChange = composer::setDelivery,
                onResumeChange = composer::setResume,
                onInterrupt = { resume -> composer.interrupt(resume) },
                onBackground = composer::background,
                onOpenInbox = { inboxOpen = true },
                onOpenAgentPicker = { agentPickerOpen = true },
                onOpenModelPicker = { modelPickerOpen = true },
                onCycleAgent = composer::cycleAgent,
                onCycleVariant = composer::cycleVariant,
            )
        },
        menuSlot = {
            IconButton(onClick = { actionsOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = menu)
            }
        },
    )

    if (actionsOpen) {
        SessionActionsSheet(
            title = state.title,
            childCount = childCount,
            onRename = { title -> management.rename(title) },
            onDelete = { management.delete(); actionsOpen = false; onSessionDeleted() },
            onCopyMessage = {
                state.messages.lastOrNull()?.let { message ->
                    clipboard.setText(AnnotatedString(management.copyMessage(message)))
                }
            },
            onCopyTranscript = {
                management.copyTranscript(sessionId, state.title)?.let {
                    clipboard.setText(AnnotatedString(it))
                }
            },
            onDismiss = { actionsOpen = false },
        )
    }

    if (inboxOpen) {
        InboxPanel(
            items = composerState.pending,
            onCancel = composer::cancelInboxItem,
            onDeliveryChange = composer::setInboxDelivery,
            onDismiss = { inboxOpen = false },
        )
    }

    if (agentPickerOpen) {
        AgentPickerSheet(
            agents = composerState.primaryAgents,
            selected = composerState.agent,
            onSelect = { agent -> composer.selectAgent(agent); agentPickerOpen = false },
            onDismiss = { agentPickerOpen = false },
        )
    }

    if (modelPickerOpen) {
        ModelPickerSheet(
            groups = ModelCatalog.group(composerState.models, favorites, recents, modelSearch),
            selected = composerState.model,
            search = modelSearch,
            onSearchChange = { modelSearch = it },
            onSelect = { ref -> composer.selectModel(ref); modelPickerOpen = false },
            onSelectVariant = { ref, variant -> composer.selectModel(ref.copy(variant = variant)) },
            onToggleFavorite = composer::toggleFavorite,
            onDismiss = { modelPickerOpen = false },
        )
    }
}
