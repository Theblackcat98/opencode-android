package dev.opencode.android.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.data.catalog.ModelCatalog
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.feature.composer.ui.AgentPickerSheet
import dev.opencode.android.feature.composer.ui.ComposerBar
import dev.opencode.android.feature.composer.ui.ComposerEffect
import dev.opencode.android.feature.composer.ui.ComposerViewModel
import dev.opencode.android.feature.composer.ui.FullScreenEditor
import dev.opencode.android.feature.composer.ui.InboxPanel
import dev.opencode.android.feature.composer.ui.ModelPickerSheet
import dev.opencode.android.feature.composer.ui.SideQuestionSheet
import dev.opencode.android.feature.composer.ui.SkillPicker
import dev.opencode.android.feature.composer.ui.StashList
import dev.opencode.android.feature.requests.ui.AttentionSettingsSheet
import dev.opencode.android.feature.requests.ui.RequestActions
import dev.opencode.android.feature.requests.ui.messageRes
import dev.opencode.android.feature.requests.ui.takesArgument
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
 *
 * **Phase 5 is why this file grew: the composer's client commands navigate.** `/new`, `/sessions`,
 * `/models` and `/agents` are the app's own actions, and the composer cannot perform them because
 * it may not know this graph. It emits a [ComposerEffect] and this function carries it out; that is
 * the same arrangement P3 used for the pickers, extended to the command palette.
 */
@Composable
fun SessionHost(
    sessionId: String,
    onNavigateBack: () -> Unit,
    onSessionDeleted: () -> Unit,
    onOpenSessionList: () -> Unit = {},
    onNewSession: () -> Unit = {},
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
    var attentionOpen by remember { mutableStateOf(false) }
    var inboxOpen by remember { mutableStateOf(false) }
    var agentPickerOpen by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }
    var skillsOpen by remember { mutableStateOf(false) }
    var stashOpen by remember { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var modelSearch by rememberSaveable { mutableStateOf("") }

    val pickAttachment = rememberAttachmentPicker(
        onPicked = composer::attachImage,
        onCameraUnavailable = { attachOpen = false },
    )

    LaunchedEffect(sessionId) {
        timeline.open(sessionId)
        composer.open(sessionId)
        management.openSession(sessionId)
    }

    // The composer's one-shot actions. A `LaunchedEffect` with the effect flow as the key collects
    // each effect exactly once, which is what a `StateFlow` of the same thing could not promise.
    LaunchedEffect(composer) {
        composer.effect.collect { effect ->
            when (effect) {
                ComposerEffect.NewSession -> onNewSession()
                ComposerEffect.SessionList -> onOpenSessionList()
                ComposerEffect.OpenAgentPicker -> agentPickerOpen = true
                ComposerEffect.OpenModelPicker -> modelPickerOpen = true
                ComposerEffect.OpenEditor -> editorOpen = true
                // Focus is the field's own business; the empty box is the visible part of the send.
                ComposerEffect.FocusComposer -> Unit
            }
        }
    }

    // "The user actually saw it" is a fact about which screen is in front, so it is published on
    // resume and withdrawn on the way out (plan §6, "Unread model"). Resuming is also when the
    // unseen idle transition is marked, through `SessionViewMarker`.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { timeline.onScreenResumed() }
    DisposableEffect(sessionId) {
        onDispose { timeline.onScreenLeft() }
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

    // The failure wording lives in the requests feature so there is one mapping for the whole app;
    // the composer takes it ready-made because a feature may not import another feature.
    val composerError = composerState.error?.let { error ->
        if (error.takesArgument) {
            stringResource(error.kind.messageRes(), error.message)
        } else {
            stringResource(error.kind.messageRes())
        }
    }

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
                onSelectCompletion = composer::applyCompletion,
                onRemoveAttachment = composer::removeAttachment,
                onAttach = { attachOpen = true },
                onOpenSkills = { skillsOpen = true },
                onOlderHistory = composer::olderHistory,
                onNewerHistory = composer::newerHistory,
                onStash = composer::stashCurrent,
                onOpenStash = { stashOpen = true },
                onOpenEditor = { editorOpen = true },
                onSendConfirmed = { composer.send(confirmed = true) },
                errorMessage = composerError,
                onDismissError = composer::dismissError,
            )
        },
        menuSlot = {
            IconButton(onClick = { actionsOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = menu)
            }
        },
    )

    if (attentionOpen) {
        // The per-session half of Phase 4's settings: mute and auto-approve for this session only.
        // Composed here for the same reason the rest of this screen is (see the class comment).
        AttentionSettingsSheet(sessionId = sessionId, onDismiss = { attentionOpen = false })
    }

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
            onOpenAttention = { actionsOpen = false; attentionOpen = true },
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
            favorites = favorites,
            recents = recents,
        )
    }

    if (skillsOpen) {
        SkillPicker(
            skills = composerState.availableSkills,
            attached = composerState.skills.map { it.id }.toSet(),
            onToggle = composer::toggleSkill,
            onActivate = composer::activateSkill,
            onDismiss = { skillsOpen = false },
        )
    }

    if (stashOpen) {
        StashList(
            stash = composerState.stash,
            onPop = composer::popStash,
            onRestore = composer::restoreStash,
            onDismiss = { stashOpen = false },
        )
    }

    composerState.sideQuestion?.let { question ->
        SideQuestionSheet(state = question, onDismiss = composer::dismissSideQuestion)
    }

    if (editorOpen) {
        FullScreenEditor(
            initialText = composerState.text,
            onDone = { text ->
                editorOpen = false
                composer.setText(text)
            },
            onDismiss = { editorOpen = false },
        )
    }

    if (attachOpen) {
        AttachSourceSheet(
            onPick = { source ->
                attachOpen = false
                pickAttachment(source)
            },
            onDismiss = { attachOpen = false },
        )
    }
}
