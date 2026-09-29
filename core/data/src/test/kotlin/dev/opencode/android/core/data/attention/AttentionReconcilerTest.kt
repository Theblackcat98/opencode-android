package dev.opencode.android.core.data.attention

import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.Outcome
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which notifications exist, and what they offer (plan §6, Phase 4).
 *
 * The reconciler is a pure function of a state, so this is where the phase's housekeeping rules are
 * actually decided — the filtering by mute and quiet hours, the choice of channel, the
 * `RemoteInput` eligibility, the grouping, and above all the cancellation of a notification whose
 * request was answered somewhere else. A notification cannot be seen in a JVM test; the decision to
 * post one can, and that decision is the part that goes wrong.
 */
class AttentionReconcilerTest {

    private val server = "srv_1"
    private val now = 1_700_000_000_000L

    // ------------------------------------------------------------------ permissions

    @Test
    fun `a pending permission notifies on the high-priority channel with once and reject`() {
        val drafts = draftsFor(state(pending = listOf(permission())))
        val draft = drafts.single()
        assertEquals(AttentionChannel.PERMISSION, draft.channel)
        assertTrue(draft.channel.blocking)
        assertEquals(
            listOf(PermissionReply.Once, PermissionReply.Reject),
            draft.actions.filterIsInstance<AttentionAction.ReplyPermission>().map { it.decision },
        )
    }

    @Test
    fun `"allow always" is not a button, because plan 5-2 wants a confirmation first`() {
        val draft = draftsFor(state(pending = listOf(permission()))).single()
        val decisions = draft.actions.filterIsInstance<AttentionAction.ReplyPermission>().map { it.decision }
        assertFalse(decisions.contains(PermissionReply.Always))
    }

    @Test
    fun `a reply from another client cancels the phone's notification`() {
        // The plan's own words: the request left the pending list, so there is no slot for it. Nothing
        // about the reply has to be known here, which is the point — there is no per-event cancel to
        // forget to write.
        val before = AttentionReconciler.reconcile(state(pending = listOf(permission())), emptyList())
        assertEquals(1, before.added.size)
        val after = AttentionReconciler.reconcile(state(pending = emptyList()), before.posts)
        assertEquals(
            listOf(NotificationSlot.Permission(server, "prm_1", "ses_1")),
            after.removed,
        )
        assertTrue(after.posts.isEmpty())
    }

    @Test
    fun `a muted session produces nothing at all, a blocking request included`() {
        val drafts = draftsFor(
            state(
                pending = listOf(permission()),
                sessions = listOf(session()),
                muted = setOf("ses_1"),
            ),
        )
        assertTrue(drafts.isEmpty())
    }

    @Test
    fun `the session on screen produces nothing`() {
        val drafts = draftsFor(
            state(pending = listOf(permission()), openSessionId = "ses_1"),
        )
        assertTrue(drafts.isEmpty())
    }

    @Test
    fun `an auto-approved request produces nothing, because it is about to be answered`() {
        val drafts = draftsFor(
            state(pending = listOf(permission()), autoApproved = setOf("ses_1")),
        )
        assertTrue(drafts.isEmpty())
    }

    // ------------------------------------------------------------------ forms

    @Test
    fun `a single free-text question is answerable from the shade`() {
        val draft = draftsFor(state(pending = listOf(form()))).single()
        assertEquals(AttentionChannel.QUESTION, draft.channel)
        val remoteInput = assertNotNull(draft.remoteInput).let { draft.remoteInput!! }
        assertEquals("q0", remoteInput.key)
        assertTrue(draft.actions.any { it is AttentionAction.AnswerForm })
    }

    @Test
    fun `a form with two fields is opened rather than answered`() {
        val twoFields = form(
            FormInfo(
                id = "frm_1",
                sessionID = "ses_1",
                title = "Two questions",
                fields = listOf(
                    FormField.StringField(key = "a"),
                    FormField.StringField(key = "b"),
                ),
            ),
        )
        val draft = draftsFor(state(pending = listOf(twoFields))).single()
        assertNull(draft.remoteInput)
        assertTrue(draft.actions.none { it is AttentionAction.AnswerForm })
        assertTrue(draft.actions.any { it is AttentionAction.OpenSession })
    }

    @Test
    fun `a question with a fixed option list is opened, because a shade box cannot offer choices`() {
        val options = form(
            FormInfo(
                id = "frm_1",
                sessionID = "ses_1",
                title = "Pick one",
                fields = listOf(
                    FormField.StringField(
                        key = "q0",
                        title = "Which shell?",
                        options = listOf(
                            dev.opencode.android.core.model.FormOption("bash", "bash"),
                            dev.opencode.android.core.model.FormOption("zsh", "zsh"),
                        ),
                    ),
                ),
            ),
        )
        assertNull(draftsFor(state(pending = listOf(options))).single().remoteInput)
    }

    @Test
    fun `a required question with a minimum length is opened, because one character would fail`() {
        val bounded = form(
            FormInfo(
                id = "frm_1",
                sessionID = "ses_1",
                title = "Name it",
                fields = listOf(FormField.StringField(key = "q0", required = true, minLength = 3)),
            ),
        )
        assertNull(draftsFor(state(pending = listOf(bounded))).single().remoteInput)
    }

    // ------------------------------------------------------------------ finished turns

    @Test
    fun `an unread finished turn notifies, and says how it ended`() {
        val drafts = draftsFor(
            state(sessions = listOf(session(outcome = Outcome.Succeeded, idle = now, viewed = now - 1))),
        )
        val draft = drafts.single()
        assertEquals(AttentionChannel.TURN_FINISHED, draft.channel)
        assertEquals(Outcome.Succeeded, (draft.content as NotificationContent.TurnFinished).outcome)
    }

    @Test
    fun `a failed turn and an interrupted one each have their own channel message`() {
        fun outcomeOf(o: Outcome) = (
            draftsFor(
                state(sessions = listOf(session(outcome = o, idle = now, viewed = 0))),
            ).single().content as NotificationContent.TurnFinished
            ).outcome
        assertEquals(Outcome.Failed, outcomeOf(Outcome.Failed))
        assertEquals(Outcome.Interrupted, outcomeOf(Outcome.Interrupted))
    }

    @Test
    fun `a read turn does not notify, because the user has seen it`() {
        val drafts = draftsFor(
            state(sessions = listOf(session(outcome = Outcome.Succeeded, idle = now, viewed = now))),
        )
        assertTrue(drafts.isEmpty())
    }

    @Test
    fun `a second turn of the same session is a new notification, and the first is cancelled`() {
        val first = AttentionReconciler.reconcile(
            state(sessions = listOf(session(outcome = Outcome.Succeeded, idle = 1_000L, viewed = 0))),
            emptyList(),
        )
        val second = AttentionReconciler.reconcile(
            state(sessions = listOf(session(outcome = Outcome.Failed, idle = 2_000L, viewed = 0))),
            first.posts,
        )
        assertEquals(
            listOf(NotificationSlot.TurnFinished(server, "ses_1", 1_000L)),
            second.removed,
        )
        assertEquals(
            listOf(NotificationSlot.TurnFinished(server, "ses_1", 2_000L)),
            second.added.map { it.slot },
        )
    }

    @Test
    fun `a subagent finishing has its own channel`() {
        val drafts = draftsFor(
            state(
                sessions = listOf(
                    AttentionSession(
                        id = "ses_child",
                        title = "Explore the API",
                        parentID = "ses_1",
                        parentTitle = "Fix the tests",
                        outcome = Outcome.Succeeded,
                        idleAtMillis = now,
                    ),
                ),
            ),
        )
        assertEquals(AttentionChannel.SUBAGENT_FINISHED, drafts.single().channel)
    }

    @Test
    fun `quiet hours suppress a finished turn`() {
        val drafts = draftsFor(
            state(
                sessions = listOf(session(outcome = Outcome.Succeeded, idle = now, viewed = 0)),
                quiet = QuietHours.of(0, 24 * 60),
            ),
        )
        assertTrue(drafts.isEmpty())
    }

    @Test
    fun `quiet hours never suppress a blocking request`() {
        // The rule worth stating: the work is stopped, not merely unreported, and it will still be
        // there in the morning.
        val drafts = draftsFor(
            state(pending = listOf(permission()), quiet = QuietHours.of(0, 24 * 60)),
        )
        assertEquals(AttentionChannel.PERMISSION, drafts.single().channel)
    }

    // ------------------------------------------------------------------ retries and updates

    @Test
    fun `a scheduled retry notifies and names the attempt`() {
        val retrying = session().copy(
            activity = SessionActivity.Retrying(attempt = 2, next = now + 30_000, message = "rate limited"),
        )
        val draft = draftsFor(state(sessions = listOf(retrying))).single()
        assertEquals(AttentionChannel.RETRY, draft.channel)
        assertEquals(2, (draft.content as NotificationContent.RetryScheduled).attempt)
    }

    @Test
    fun `an announced server version notifies once and stops when the server is on it`() {
        val update = state(updateVersion = "2.0.19")
        assertEquals(AttentionChannel.SERVER_UPDATE, draftsFor(update).single().channel)
        assertTrue(draftsFor(update.copy(updateVersion = null)).isEmpty())
    }

    // ------------------------------------------------------------------ grouping and idempotence

    @Test
    fun `notifications are grouped per session and summarised per server`() {
        val result = AttentionReconciler.reconcile(
            state(
                pending = listOf(permission(), form()),
                sessions = listOf(session(outcome = Outcome.Succeeded, idle = now, viewed = 0)),
            ),
            emptyList(),
        )
        val member = result.added.first { it.slot is NotificationSlot.Permission }
        assertEquals("session|$server|ses_1", member.slot.group)
        val summary = result.summaries.single()
        assertEquals(NotificationSlot.ServerSummary(server), summary.slot)
        assertEquals("server|$server", summary.slot.group)
        assertTrue(summary.onlyAlertOnce)
        assertEquals(2, (summary.content as NotificationContent.ServerSummary).blocking)
    }

    @Test
    fun `the same state twice produces nothing the second time`() {
        val one = state(
            pending = listOf(permission(), form()),
            sessions = listOf(session(outcome = Outcome.Succeeded, idle = now, viewed = 0)),
        )
        val first = AttentionReconciler.reconcile(one, emptyList())
        val second = AttentionReconciler.reconcile(one, first.posts)
        assertTrue(second.isEmpty)
        assertEquals(first.posts.size, second.posts.size)
    }

    @Test
    fun `a resync does not re-notify about a turn the user was already told about`() {
        val one = state(sessions = listOf(session(outcome = Outcome.Succeeded, idle = now, viewed = 0)))
        val first = AttentionReconciler.reconcile(one, emptyList())
        val resync = AttentionReconciler.reconcile(one, first.posts)
        assertTrue(resync.added.isEmpty())
        assertTrue(resync.changed.isEmpty())
    }

    @Test
    fun `nothing on screen means no summary either`() {
        assertTrue(AttentionReconciler.reconcile(state(), emptyList()).summaries.isEmpty())
    }

    // ------------------------------------------------------------------ fixtures

    private fun draftsFor(state: AttentionState): List<AttentionDraft> = AttentionReconciler.draftsFor(state)

    private fun permission() = PendingRequest.Permission(
        PermissionRequest(
            id = "prm_1",
            sessionID = "ses_1",
            action = "bash",
            resources = listOf("ls -la"),
            save = listOf("bash *"),
        ),
    )

    private fun form(info: FormInfo = questionForm()) = PendingRequest.Form(info)

    private fun questionForm() = FormInfo(
        id = "frm_1",
        sessionID = "ses_1",
        title = "Which shell?",
        metadata = mapOf("kind" to dev.opencode.android.core.model.FormValues.string("question")),
        fields = listOf(FormField.StringField(key = "q0", title = "Which shell?")),
    )

    private fun session(
        outcome: Outcome? = null,
        idle: Long? = null,
        viewed: Long? = null,
    ) = AttentionSession(
        id = "ses_1",
        title = "Fix the tests",
        activity = SessionActivity.Idle,
        outcome = outcome,
        idleAtMillis = idle,
        viewedAtMillis = viewed,
    )

    private fun state(
        pending: List<PendingRequest> = emptyList(),
        sessions: List<AttentionSession> = emptyList(),
        updateVersion: String? = null,
        openSessionId: String? = null,
        muted: Set<String> = emptySet(),
        autoApproved: Set<String> = emptySet(),
        quiet: QuietHours = QuietHours(),
    ) = AttentionState(
        serverId = server,
        serverName = "Workstation",
        sessions = sessions.associateBy { it.id },
        pending = pending,
        updateVersion = updateVersion,
        openSessionId = openSessionId,
        mutedSessions = muted,
        quietHours = quiet,
        autoApprovedSessions = autoApproved,
        nowMillis = now,
        utcOffsetMillis = 0L,
    )
}
