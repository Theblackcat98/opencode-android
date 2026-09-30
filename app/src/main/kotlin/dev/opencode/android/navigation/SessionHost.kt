package dev.opencode.android.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.opencode.android.feature.execution.SubagentStrip
import dev.opencode.android.feature.execution.SubagentsViewModel
import dev.opencode.android.feature.requests.ui.AttentionSettingsSheet
import dev.opencode.android.feature.requests.ui.RequestActions
import dev.opencode.android.feature.requests.ui.RequestDock
import dev.opencode.android.feature.requests.ui.messageRes
import dev.opencode.android.feature.requests.ui.takesArgument
import dev.opencode.android.feature.sessions.R
import dev.opencode.android.feature.sessions.ui.SessionActionsSheet
import dev.opencode.android.feature.sessions.ui.SessionManagementViewModel
import dev.opencode.android.feature.sessions.ui.SessionMenuRow
import dev.opencode.android.feature.sessions.ui.SessionScreen
import dev.opencode.android.feature.sessions.ui.SessionViewModel
import dev.opencode.android.feature.sessions.ui.UserMessageActions
import dev.opencode.android.feature.sessions.R as SessionsR

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
    onOpenChild: (String) -> Unit = {},
    onOpenSessionList: () -> Unit = {},
    onNewSession: () -> Unit = {},
    onOpenReview: (String?) -> Unit = {},
    onOpenShells: (String) -> Unit = {},
    onOpenTerminal: (String, String?) -> Unit = { _, _ -> },
    onOpenSubagents: () -> Unit = {},
    onOpenWorktrees: (String) -> Unit = {},
    onOpenSessionTerminals: () -> Unit = {},
    /** Takes the project id, because the menu row is disabled without one. */
    onOpenProjectSettings: (String) -> Unit = {},
    /**
     * The Phase 9 rows: this session's permission rules and its instruction entries.
     *
     * **They take the project id and the directory from the session's own state** rather than from the
     * graph, for the reason the location rows above are disabled without a location: a session that has
     * not loaded its projection has neither, and a panel opened with an empty one would be about the
     * server's working directory.
     */
    onOpenSessionPermissions: (String?, String) -> Unit = { _, _ -> },
    onOpenSessionInstructions: () -> Unit = {},
    onForked: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    timeline: SessionViewModel = hiltViewModel(),
    /**
     * The composer of this session, which `open(sessionId)` below binds to it.
     *
     * Everything that has to reach the session's composer from outside this screen (the review's comments
     * and attachments) is given *this* instance by the graph, so a caller that took another one would be
     * talking to a composer with no session; the undo below does not go through a callback for that reason.
     */
    composer: ComposerViewModel = hiltViewModel(),
    management: SessionManagementViewModel = hiltViewModel(),
    subagents: SubagentsViewModel = hiltViewModel(),
) {
    val state by timeline.state.collectAsStateWithLifecycle()
    val composerState by composer.state.collectAsStateWithLifecycle()
    val childCount by management.childCount.collectAsStateWithLifecycle()
    val forked by management.forked.collectAsStateWithLifecycle()
    val strip by subagents.strip.collectAsStateWithLifecycle()
    val favorites by composer.modelFavorites.collectAsStateWithLifecycle()
    val recents by composer.modelRecents.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val menu = stringResource(R.string.session_menu)
    var actionsOpen by remember { mutableStateOf(false) }
    var attentionOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var experimentalOpen by remember { mutableStateOf(false) }
    var inboxOpen by remember { mutableStateOf(false) }
    var agentPickerOpen by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }
    var skillsOpen by remember { mutableStateOf(false) }
    var stashOpen by remember { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var modelSearch by rememberSaveable { mutableStateOf("") }
    // The message an undo is aimed at, held between the menu row and the confirmation (plan §5.2:
    // reverting files is a dangerous action, so it asks).
    var undoTarget by remember { mutableStateOf<String?>(null) }

    val pickAttachment = rememberAttachmentPicker(
        onPicked = composer::attachImage,
        onCameraUnavailable = { attachOpen = false },
    )

    // A fork produced a new session id; the graph is what knows how to open one. Taking it here
    // rather than in the graph keeps `SessionHost` from knowing the navigation API, and clearing it
    // in the same call is what makes the navigation happen once.
    LaunchedEffect(forked) {
        management.consumeFork()?.let(onForked)
    }

    LaunchedEffect(sessionId) {
        timeline.open(sessionId)
        composer.open(sessionId)
        management.openSession(sessionId)
        // The strip is derived from the same session rows the timeline shows, so it is bound here
        // rather than when its own screen opens: a subagent that starts mid-turn has to appear in the
        // composer without the user going anywhere.
        subagents.open(sessionId)
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

                // `/diff` is the review screen, and the review is another feature; the composition
                // root is the only place that knows how to get there.
                ComposerEffect.OpenDiff -> onOpenReview(null)

                // Focus is the field's own business; the empty box is the visible part of the send.
                ComposerEffect.FocusComposer -> Unit

                // The palette's `/undo`: the composer picked the message, and this is where the
                // confirmation plan §5.2 asks for is asked.
                is ComposerEffect.ConfirmUndo -> undoTarget = effect.messageID
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
        onOpenChangedFile = { path -> onOpenReview(path) },
        subagentSlot = {
            SubagentStrip(
                state = strip,
                onOpen = { childId -> onOpenChild(childId) },
                onInterrupt = subagents::interrupt,
                onDismissError = subagents::dismissError,
            )
        },
        messageActions = { messageId ->
            // Only a user message is a boundary the server accepts, so only a user message gets the
            // rows; the timeline asks for them and this answers.
            val message = state.messages.firstOrNull {
                it.id == messageId
            } as? dev.opencode.android.core.model.SessionMessage.User
            if (message != null) {
                UserMessageActions(
                    messageId = message.id,
                    text = message.text,
                    onFork = { id -> management.fork(id) },
                    onRevert = { id, _ -> undoTarget = id },
                )
            }
        },
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
            onDelete = {
                management.delete()
                actionsOpen = false
                onSessionDeleted()
            },
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
            onOpenAttention = {
                actionsOpen = false
                attentionOpen = true
            },
            onOpenHistory = {
                actionsOpen = false
                historyOpen = true
            },
            onOpenExperimental = {
                actionsOpen = false
                experimentalOpen = true
            },
            executionSlot = {
                ExecutionMenuRows(
                    directory = state.directory.orEmpty(),
                    projectId = state.projectId,
                    onOpenShells = onOpenShells,
                    onOpenTerminal = onOpenTerminal,
                    onOpenSubagents = onOpenSubagents,
                    onOpenWorktrees = onOpenWorktrees,
                    onOpenSessionTerminals = onOpenSessionTerminals,
                    onOpenProjectSettings = onOpenProjectSettings,
                    onOpenSessionPermissions = onOpenSessionPermissions,
                    onOpenSessionInstructions = onOpenSessionInstructions,
                )
            },
        )
    }

    if (historyOpen) {
        HistoryHost(sessionId = sessionId, onDismiss = { historyOpen = false })
    }

    if (experimentalOpen) {
        ExperimentalHost(onDismiss = { experimentalOpen = false })
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
            onSelect = { agent ->
                composer.selectAgent(agent)
                agentPickerOpen = false
            },
            onDismiss = { agentPickerOpen = false },
        )
    }

    if (modelPickerOpen) {
        ModelPickerSheet(
            groups = ModelCatalog.group(composerState.models, favorites, recents, modelSearch),
            selected = composerState.model,
            search = modelSearch,
            onSearchChange = { modelSearch = it },
            onSelect = { ref ->
                composer.selectModel(ref)
                modelPickerOpen = false
            },
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

    if (undoTarget != null) {
        // The confirmation that makes a revert a two-step action rather than one tap.
        val revertMessage = state.messages
            .firstOrNull { it.id == undoTarget } as? dev.opencode.android.core.model.SessionMessage.User
        RevertConfirmationDialog(
            preview = revertMessage?.text.orEmpty(),
            onConfirm = {
                // This screen's composer, the one that has this session open: `stageUndo` returns without a
                // session, and a callback into the graph is how it once reached one that had none.
                composer.stageUndo(undoTarget.orEmpty())
                undoTarget = null
            },
            onDismiss = { undoTarget = null },
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

/**
 * The confirmation that precedes a revert (plan §5.2, "reverting files").
 *
 * It shows the prompt that is about to be rolled back, because "undo" without saying *which turn*
 * is a confirmation of nothing: the user cannot tell a two-turn-old prompt from the last one.
 */
@Composable
private fun RevertConfirmationDialog(
    preview: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(SessionsR.string.timeline_revert_here)) },
        text = {
            Text(
                text = preview.take(280),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 6,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(SessionsR.string.timeline_revert_here))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(SessionsR.string.action_dismiss))
            }
        },
    )
}

/**
 * Phase 7's rows in the session's overflow menu (plan §6, "Subagents, shells, terminals and
 * worktrees").
 *
 * **They are here, in the app module, because the sessions feature may not import the execution
 * feature.** The menu is the session screen's own sheet and the panels are another feature's, so the
 * composition root is the only place that can hold both — the same arrangement Phase 5 used for the
 * composer's client commands and Phase 6 for the review.
 *
 * **The location rows are disabled without a location.** Shells and terminals are location-scoped and a
 * session that has not loaded its projection yet has no directory; offering the row and answering with
 * an empty one would open a panel for the server's working directory, which is a different checkout
 * and therefore a different set of commands.
 */
@Composable
private fun ExecutionMenuRows(
    directory: String,
    projectId: String?,
    onOpenShells: (String) -> Unit,
    onOpenTerminal: (String, String?) -> Unit,
    onOpenSubagents: () -> Unit,
    onOpenWorktrees: (String) -> Unit,
    onOpenSessionTerminals: () -> Unit,
    onOpenProjectSettings: (String) -> Unit,
    onOpenSessionPermissions: (String?, String) -> Unit,
    onOpenSessionInstructions: () -> Unit,
) {
    SessionMenuRow(
        label = stringResource(SessionsR.string.session_execution_subagents),
        onClick = onOpenSubagents,
    )
    SessionMenuRow(
        label = stringResource(SessionsR.string.session_execution_shells),
        enabled = directory.isNotBlank(),
        onClick = { onOpenShells(directory) },
    )
    SessionMenuRow(
        label = stringResource(SessionsR.string.session_execution_terminal),
        enabled = directory.isNotBlank(),
        onClick = { onOpenTerminal(directory, null) },
    )
    // A worktree panel is keyed by a project and the route requires one, so the row is off until the
    // session's projection has said which project it belongs to.
    SessionMenuRow(
        label = stringResource(SessionsR.string.session_execution_worktrees),
        enabled = projectId != null,
        onClick = { projectId?.let(onOpenWorktrees) },
    )
    SessionMenuRow(
        label = stringResource(SessionsR.string.session_execution_session_terminals),
        onClick = onOpenSessionTerminals,
    )
    // Project settings are `project.update`, and the start command in them is what a terminal's quick
    // action runs — so the settings sit next to the terminal row rather than in a settings app the user
    // would have to go and find.
    SessionMenuRow(
        label = stringResource(SessionsR.string.session_execution_project_settings),
        enabled = projectId != null,
        onClick = { projectId?.let(onOpenProjectSettings) },
    )
    // The permissions panel is location-scoped for the saved approvals and session-scoped for the rules,
    // so it needs a location to list the project's standing approvals at all.
    SessionMenuRow(
        label = stringResource(SessionsR.string.session_admin_permissions),
        enabled = directory.isNotBlank(),
        onClick = { onOpenSessionPermissions(projectId, directory) },
    )
    // Instruction entries belong to the session alone, so this row needs nothing from the projection.
    SessionMenuRow(
        label = stringResource(SessionsR.string.session_admin_instructions),
        onClick = onOpenSessionInstructions,
    )
}
