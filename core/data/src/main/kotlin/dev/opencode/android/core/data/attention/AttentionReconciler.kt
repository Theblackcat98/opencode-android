package dev.opencode.android.core.data.attention

import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.model.PermissionReply

/**
 * What one pass over the state produced.
 *
 * [posts] is everything that should be on screen, [added], [changed] and [removed] are the diff
 * against the previous pass. A notification manager needs the diff; a test needs the whole set, so
 * both are here rather than making one of them re-derive the other.
 */
data class AttentionReconcile(
    val posts: List<AttentionDraft> = emptyList(),
    val added: List<AttentionDraft> = emptyList(),
    val changed: List<AttentionDraft> = emptyList(),
    val removed: List<NotificationSlot> = emptyList(),
    val summaries: List<AttentionDraft> = emptyList(),
) {
    val isEmpty: Boolean get() = added.isEmpty() && changed.isEmpty() && removed.isEmpty()
}

/**
 * A draft as a diff key: everything except the timestamp.
 *
 * The timestamp is the wall clock, so it differs on every pass and comparing whole drafts would
 * report every notification as changed on every pass, re-alerting for all of them. A draft's
 * identity is what it says and which channel it is on.
 */
private fun AttentionDraft.sameNotificationAs(other: AttentionDraft): Boolean =
    slot == other.slot &&
        channel == other.channel &&
        content == other.content &&
        actions == other.actions &&
        remoteInput == other.remoteInput &&
        onlyAlertOnce == other.onlyAlertOnce

/**
 * Turns the attention state into the notifications that should be on screen (plan §6, Phase 4).
 *
 * **A reconciler, not an event handler.** The alternative — turning each event into a post and each
 * drop into a cancel — needs a cancel for every way a request can go away (answered here, answered
 * on the desktop, cancelled, the session deleted, the server forgotten) and forgets whichever one is
 * not listed. Deriving the desired set from the state makes "a reply from another client cancels the
 * phone's notification" fall out of the same rule as everything else: the request is no longer
 * pending, so there is no slot for it. That is the property the plan asks for, and it is the one
 * worth testing.
 *
 * **Idempotent by construction.** The same state produces the same drafts, so a second pass reports
 * nothing new and a resync — which re-reads everything on every `server.connected` — does not
 * re-notify about a turn the user was already told about.
 *
 * **Five filters, in this order.**
 *
 *  1. **A muted session produces nothing.** A mute is the user saying "not this one", and it covers
 *     the permission too: a muted session that blocks stays visible in the app and silent in the
 *     shade.
 *  2. **The session on screen produces nothing.** It is the one session whose state the user is
 *     already looking at.
 *  3. **An auto-approved request produces nothing.** It is about to be answered, and a notification
 *     the user cannot outrace the client on is worse than none.
 *  4. **Quiet hours suppress information, never a request.** See [AttentionChannel.blocking].
 *  5. **Only an unread, finished turn notifies.** `time.idle > time.viewed` is the plan's unread
 *     rule verbatim, and reading the session — which is what calls `session.view` — clears it.
 */
object AttentionReconciler {

    /**
     * The diff of [state] against what is on screen now.
     *
     * [previous] is what the last pass produced, which is the only record of what is posted. It is
     * passed in rather than kept here so the function stays pure and so two independent servers can
     * be reconciled without sharing a ledger.
     */
    fun reconcile(state: AttentionState, previous: List<AttentionDraft>): AttentionReconcile {
        val posts = draftsFor(state)
        val summaries = summariesFor(state, posts)
        val before = previous.associateBy { it.slot }
        val now = posts.associateBy { it.slot }
        val added = posts.filter { it.slot !in before }
        val changed = posts.filter { draft ->
            val seen = before[draft.slot]
            seen != null && !draft.sameNotificationAs(seen)
        }
        val removed = before.keys.filterNot { it in now }
        return AttentionReconcile(posts, added, changed, removed, summaries)
    }

    /** The complete desired set, in the order the shade lists them. */
    fun draftsFor(state: AttentionState): List<AttentionDraft> = buildList {
        state.pending.forEach { request ->
            when (request) {
                is PendingRequest.Permission -> permissionDraft(state, request)?.let(::add)
                is PendingRequest.Form -> formDraft(state, request)?.let(::add)
            }
        }
        state.sessions.values.sortedBy { it.id }.forEach { session ->
            turnDraft(state, session)?.let(::add)
            retryDraft(state, session)?.let(::add)
        }
        state.finishedShells.forEach { shell -> shellDraft(state, shell)?.let(::add) }
        updateDraft(state)?.let(::add)
    }

    // ------------------------------------------------------------------ one of each kind

    private fun permissionDraft(
        state: AttentionState,
        request: PendingRequest.Permission,
    ): AttentionDraft? {
        val model = request.request
        if (state.suppresses(model.sessionID, AttentionChannel.PERMISSION)) return null
        return AttentionDraft(
            slot = NotificationSlot.Permission(state.serverId, model.id, model.sessionID),
            channel = AttentionChannel.PERMISSION,
            content = NotificationContent.PermissionRequest(
                sessionId = model.sessionID,
                sessionTitle = state.sessions[model.sessionID]?.title.orEmpty(),
                action = model.action,
                resources = model.resources,
                savedPatterns = model.savedPatterns,
            ),
            // "Allow always" is deliberately absent: it is the one answer plan §5.2 requires a
            // confirmation for, and a notification action has no dialog to confirm in. The receiver
            // turns this request into a confirmation notification instead of answering it.
            actions = listOf(
                AttentionAction.ReplyPermission(state.serverId, model.sessionID, model.id, PermissionReply.Once),
                AttentionAction.ReplyPermission(state.serverId, model.sessionID, model.id, PermissionReply.Reject),
            ),
            timestampMillis = state.nowMillis,
        )
    }

    private fun formDraft(state: AttentionState, request: PendingRequest.Form): AttentionDraft? {
        val form = request.form
        if (state.suppresses(form.sessionID, AttentionChannel.QUESTION)) return null
        val session = state.sessions[form.sessionID]
        val fields = form.fields.map(::encodeField)
        val single = RemoteInputSpec.singleFreeTextField(fields)
        val actions = buildList {
            // Offered only when the form really is one free-text question: a shade input has one
            // box, and a form with two fields would silently lose the other one.
            if (single != null) {
                add(
                    AttentionAction.AnswerForm(
                        serverId = state.serverId,
                        sessionId = form.sessionID,
                        formId = form.id,
                        fieldKey = single.key,
                        fields = fields,
                    ),
                )
            }
            add(AttentionAction.OpenSession(state.serverId, form.sessionID))
        }
        return AttentionDraft(
            slot = NotificationSlot.Form(state.serverId, form.id, form.sessionID),
            channel = AttentionChannel.QUESTION,
            content = NotificationContent.Form(
                sessionId = form.sessionID,
                sessionTitle = session?.title.orEmpty(),
                title = form.title,
                kind = form.kind,
                question = single?.title?.takeIf(String::isNotBlank) ?: single?.key,
            ),
            actions = actions,
            remoteInput = single?.let { RemoteInputSpec(label = it.title.orEmpty(), key = it.key) },
            timestampMillis = state.nowMillis,
        )
    }

    private fun turnDraft(state: AttentionState, session: AttentionSession): AttentionDraft? {
        if (state.suppresses(session.id, AttentionChannel.TURN_FINISHED)) return null
        val idle = session.idleAtMillis ?: return null
        val outcome = session.outcome ?: return null
        if (!session.isUnread) return null
        return if (session.isSubagent) {
            AttentionDraft(
                slot = NotificationSlot.SubagentFinished(state.serverId, session.id, idle),
                channel = AttentionChannel.SUBAGENT_FINISHED,
                content = NotificationContent.SubagentFinished(
                    sessionId = session.id,
                    sessionTitle = session.title,
                    parentTitle = session.parentTitle,
                    outcome = outcome,
                ),
                actions = listOf(AttentionAction.OpenSession(state.serverId, session.id)),
                timestampMillis = state.nowMillis,
            )
        } else {
            AttentionDraft(
                slot = NotificationSlot.TurnFinished(state.serverId, session.id, idle),
                channel = AttentionChannel.TURN_FINISHED,
                content = NotificationContent.TurnFinished(session.id, session.title, outcome),
                actions = listOf(AttentionAction.OpenSession(state.serverId, session.id)),
                timestampMillis = state.nowMillis,
            )
        }
    }

    /**
     * A command the user is not watching finished.
     *
     * **Three filters, and the third is the whole reason this is here.** Quiet hours apply (a shell
     * notification is information, not a request the agent is blocked on), the channel can be muted
     * like any other, and a completion in the location whose shell panel is on screen produces
     * nothing — because the user is looking at the output arriving.
     *
     * The action opens the shell panel rather than a session, since a command belongs to a location
     * and not to a conversation.
     */
    private fun shellDraft(state: AttentionState, shell: AttentionShell): AttentionDraft? {
        if (state.suppresses(shell.id, AttentionChannel.SHELL_FINISHED)) return null
        if (state.openDirectory != null && state.openDirectory == shell.directory) return null
        return AttentionDraft(
            slot = NotificationSlot.ShellFinished(
                serverId = state.serverId,
                shellId = shell.id,
                directory = shell.directory,
                completedAtMillis = shell.completedAtMillis,
            ),
            channel = AttentionChannel.SHELL_FINISHED,
            content = NotificationContent.ShellFinished(
                sessionId = "",
                command = shell.command,
                status = shell.status,
                exitCode = shell.exitCode,
                directory = shell.directory,
            ),
            actions = listOf(AttentionAction.OpenLocation(state.serverId, shell.directory)),
            timestampMillis = state.nowMillis,
        )
    }

    private fun retryDraft(state: AttentionState, session: AttentionSession): AttentionDraft? {
        val activity = session.activity
        if (activity !is SessionActivity.Retrying) return null
        if (state.suppresses(session.id, AttentionChannel.RETRY)) return null
        return AttentionDraft(
            slot = NotificationSlot.Retry(state.serverId, session.id, activity.attempt, activity.next),
            channel = AttentionChannel.RETRY,
            content = NotificationContent.RetryScheduled(
                sessionId = session.id,
                sessionTitle = session.title,
                attempt = activity.attempt,
                nextAtMillis = activity.next,
                message = activity.message.takeIf(String::isNotBlank),
                actionUrl = activity.action?.link,
            ),
            actions = listOf(AttentionAction.OpenSession(state.serverId, session.id)),
            timestampMillis = state.nowMillis,
        )
    }

    private fun updateDraft(state: AttentionState): AttentionDraft? {
        val version = state.updateVersion ?: return null
        return AttentionDraft(
            slot = NotificationSlot.ServerUpdate(state.serverId, version),
            channel = AttentionChannel.SERVER_UPDATE,
            content = NotificationContent.ServerUpdate(version),
            actions = emptyList(),
            timestampMillis = state.nowMillis,
        )
    }

    /**
     * One summary per server, holding that server's sessions together in the shade.
     *
     * Android groups one level deep, so the session group is the native one and this is the level
     * above it. The summary lives on the channel of the most insistent member, because a group
     * summary has to be on a channel and being quieter than its members would be a lie. It never
     * alerts: it is a count, and a count that alerts is noise.
     */
    private fun summariesFor(state: AttentionState, posts: List<AttentionDraft>): List<AttentionDraft> {
        if (posts.isEmpty()) return emptyList()
        val channel = posts.maxBy { it.channel.priority.ordinal }.channel
        return listOf(
            AttentionDraft(
                slot = NotificationSlot.ServerSummary(state.serverId),
                channel = channel,
                content = NotificationContent.ServerSummary(
                    serverName = state.serverName,
                    total = posts.size,
                    blocking = posts.count { it.channel.blocking },
                ),
                actions = emptyList(),
                timestampMillis = state.nowMillis,
                onlyAlertOnce = true,
            ),
        )
    }

    /**
     * Whether this session produces no notification at all, for a notification on [channel].
     *
     * Mute and the session on screen suppress everything, including a blocking request: both are
     * statements about *this* session. Quiet hours suppress only the informational kinds, because a
     * request is not a message, it is work that has stopped until someone answers it, and it will
     * still be there in the morning. The channel is a parameter rather than a second call site
     * because that distinction is the whole rule.
     */
    private fun AttentionState.suppresses(sessionId: String?, channel: AttentionChannel): Boolean {
        if (sessionId == null) return false
        if (isMuted(sessionId)) return true
        if (openSessionId == sessionId) return true
        if (channel.blocking && sessionId in autoApprovedSessions) return true
        return quiet && !channel.blocking
    }
}
