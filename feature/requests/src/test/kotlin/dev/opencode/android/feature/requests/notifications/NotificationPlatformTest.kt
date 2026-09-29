package dev.opencode.android.feature.requests.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import dev.opencode.android.core.data.attention.AttentionAction
import dev.opencode.android.core.data.attention.AttentionActionCodes
import dev.opencode.android.core.data.attention.AttentionChannel
import dev.opencode.android.core.data.attention.AttentionDraft
import dev.opencode.android.core.data.attention.EncodedFormField
import dev.opencode.android.core.data.attention.NotificationContent
import dev.opencode.android.core.data.attention.NotificationIds
import dev.opencode.android.core.data.attention.NotificationSlot
import dev.opencode.android.core.data.attention.RemoteInputSpec
import dev.opencode.android.core.data.attention.encodeField
import dev.opencode.android.core.data.presence.RunningSession
import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.FormKind
import dev.opencode.android.core.model.Outcome
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.feature.requests.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The platform half of Phase 4: the channels, the notifications, and the pending intents.
 *
 * **Robolectric, not a device, and the line is drawn at what a device alone decides.** What the shade
 * actually looks like, whether a tap reaches the receiver, and what the service does in the
 * background need a phone. What a notification *is* — its channel, its group, its actions, its
 * remote input, and the flags of the intents behind those actions — is a value the platform builds
 * from what this app passes, and asserting on it catches the mistakes that matter: a channel with the
 * wrong importance, an action whose intent is mutable, a request code two buttons share.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class NotificationPlatformTest {

    private lateinit var context: Context
    private lateinit var codes: AttentionActionCodes
    private lateinit var ids: NotificationIds
    private lateinit var builder: AttentionNotificationBuilder

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        codes = AttentionActionCodes()
        ids = NotificationIds()
        builder = AttentionNotificationBuilder(
            context = context,
            codes = codes,
            ids = ids,
            openSession = { server, session ->
                NotificationIntents.openSession(context, server, session, codes)
            },
            openLocation = { server, directory ->
                NotificationIntents.openLocation(context, server, directory, codes)
            },
        )
        installAttentionChannels(context)
    }

    // ------------------------------------------------------------------ channels

    @Test
    fun `every channel the plan names is created, with the importance the plan implies`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val created = manager.notificationChannels.associateBy { it.id }
        assertEquals(
            "channels created: ${created.keys}",
            setOf(
                "attention.permission",
                "attention.question",
                "attention.turn",
                "attention.subagent",
                "attention.retry",
                "attention.update",
                "attention.connection",
                // Phase 7: a finished command is its own kind of news. It gets its own channel rather
                // than a kind within another one so a user can silence "a build finished" without
                // silencing "the agent needs an answer" — which is the whole reason channels are per
                // kind rather than one list.
                "attention.shell",
                // Phase 8: a login the user started in a browser finished, was refused or expired.
                // Its own channel, so a user who silenced their sessions' turns has not also silenced
                // the one message they are actually waiting for when they come back from a consent
                // screen.
                "attention.auth",
            ),
            created.keys,
        )
        // A permission blocks the agent, so it is the one channel that is allowed to make a sound.
        assertEquals(NotificationManager.IMPORTANCE_HIGH, created.getValue("attention.permission").importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, created.getValue("attention.question").importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, created.getValue("attention.connection").importance)
        // Only a blocking request may buzz: it is the one thing that has stopped the agent.
        assertTrue(AttentionChannelSpec.PERMISSION.vibrates)
        assertFalse(AttentionChannelSpec.TURN_FINISHED.vibrates)
        // A login outcome is news, not an interruption: nothing is blocked while the browser is in
        // front, so it must not heads-up over a permission the user is in the middle of answering.
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, created.getValue("attention.auth").importance)
        assertFalse(AttentionChannelSpec.AUTH_COMPLETED.vibrates)
    }

    @Test
    fun `a channel's id is stable, because the platform keeps the user's choice under it`() {
        val spec = AttentionChannelSpec.PERMISSION
        assertEquals("attention.permission", spec.id)
        assertEquals(spec, AttentionChannelSpec.of(AttentionChannel.PERMISSION))
    }

    @Test
    fun `every channel has a name and a description, because both are user-visible`() {
        AttentionChannelSpec.entries.forEach { spec ->
            assertTrue(context.getString(spec.nameRes).isNotBlank())
            assertTrue(context.getString(spec.descriptionRes).isNotBlank())
        }
    }

    @Test
    fun `only the blocking channels are blocking`() {
        assertTrue(AttentionChannel.PERMISSION.blocking)
        assertTrue(AttentionChannel.QUESTION.blocking)
        assertFalse(AttentionChannel.TURN_FINISHED.blocking)
        assertFalse(AttentionChannel.RETRY.blocking)
        assertFalse(AttentionChannel.SERVER_UPDATE.blocking)
    }

    // ------------------------------------------------------------------ the permission notification

    @Test
    fun `the permission notification carries the action, the resources and the patterns`() {
        val notification = builder.build(permissionDraft())
        val extras = notification.extras
        assertEquals(context.getString(R.string.notify_permission_title, "bash"), extras.getString(Notification.EXTRA_TITLE))
        assertTrue(extras.getString(Notification.EXTRA_TEXT)!!.contains("ls -la"))
        // The patterns are named up front: a standing change to the server's rules is not something a
        // notification may hide.
        assertEquals(
            context.getString(R.string.notify_permission_saves, "bash *"),
            extras.getString(Notification.EXTRA_SUB_TEXT),
        )
        assertEquals(2, notification.actionsOf().size)
        assertEquals(
            listOf(
                context.getString(R.string.action_allow_once),
                context.getString(R.string.action_reject),
            ),
            notification.actionsOf().map { it.title },
        )
    }

    @Test
    fun `the permission notification is grouped under its session and heads up`() {
        val notification = builder.build(permissionDraft())
        assertEquals("session|srv_1|ses_1", notification.group)
        assertEquals(0, notification.flags and Notification.FLAG_GROUP_SUMMARY)
        assertEquals(Notification.CATEGORY_CALL, notification.category)
    }

    @Test
    fun `every pending intent behind a notification is immutable`() {
        // `targetSdk` is 36, so the platform refuses an implicit pending intent without the flag; and
        // an immutable one is also what stops another app from filling in a reply on the user's
        // behalf. `PendingIntent.getFlags` is not public API, so this is asserted through the shadow
        // that recorded the flags the app asked for.
        val notification = builder.build(permissionDraft())
        notification.actionsOf().forEach { action ->
            val intent = requireNotNull(action.actionIntent)
            assertTrue("action ${action.title} is mutable", shadowOf(intent).isImmutable)
        }
        assertTrue(shadowOf(requireNotNull(notification.contentIntent)).isImmutable)
    }

    @Test
    fun `two notifications' buttons fire different actions, not the same one`() {
        // The user-visible consequence of two actions sharing a request code: the second button posts
        // the same intent and the first one never fires. The codes themselves are asserted in
        // `AttentionContractsTest`; this is what that means at the notification.
        val first = builder.build(permissionDraft(requestId = "prm_1"))
        val second = builder.build(permissionDraft(requestId = "prm_2"))
        fun actionsOf(notification: Notification) = notification.actionsOf()
            .map { NotificationIntents.readAction(savedIntentOf(requireNotNull(it.actionIntent))) }
        assertEquals(
            AttentionAction.ReplyPermission("srv_1", "ses_1", "prm_1", PermissionReply.Once),
            actionsOf(first).first(),
        )
        assertEquals(
            AttentionAction.ReplyPermission("srv_1", "ses_1", "prm_2", PermissionReply.Once),
            actionsOf(second).first(),
        )
        assertNotEquals(actionsOf(first).first(), actionsOf(second).first())
    }

    @Test
    fun `the action behind a button decodes back to the action the reconciler asked for`() {
        val draft = permissionDraft()
        val notification = builder.build(draft)
        val intent = savedIntentOf(requireNotNull(notification.actions.first().actionIntent))
        val decoded = NotificationIntents.readAction(intent)
        assertEquals(
            AttentionAction.ReplyPermission("srv_1", "ses_1", "prm_1", PermissionReply.Once),
            decoded,
        )
    }

    @Test
    fun `the broadcast is explicit, because the receiver exists to no other app`() {
        val notification = builder.build(permissionDraft())
        val intent = savedIntentOf(requireNotNull(notification.actions.first().actionIntent))
        assertEquals(NotificationIntents.ACTION_PERFORM, intent.action)
        assertEquals(NotificationActionReceiver::class.java.name, intent.component?.className)
    }

    // ------------------------------------------------------------------ the RemoteInput question

    @Test
    fun `a one-question form gets a reply row carrying the text the user typed`() {
        val draft = questionDraft()
        val notification = builder.build(draft)
        // The platform returns null rather than an empty array for an action with no remote input,
        // so a "does this row have a reply box" check has to tolerate that.
        val reply = notification.actionsOf().single { it.remoteInputsOrEmpty().isNotEmpty() }
        val input = reply.remoteInputsOrEmpty().single()
        assertEquals("q0", input.resultKey)

        // The system fills the intent; this side only has to be able to read it back.
        val sent = savedIntentOf(requireNotNull(reply.actionIntent))
        android.app.RemoteInput.addResultsToIntent(
            arrayOf(input),
            sent,
            Bundle().apply { putCharSequence(input.resultKey, "bash") },
        )
        assertEquals("bash", NotificationIntents.readRemoteInput(sent, "q0"))
    }

    @Test
    fun `a form with no answerable field gets no reply row`() {
        val draft = questionDraft(fields = listOf(field(key = "a"), field(key = "b")), remoteInput = null)
        val notification = builder.build(draft)
        assertTrue(notification.actionsOf().none { it.remoteInputsOrEmpty().isNotEmpty() })
    }

    // ------------------------------------------------------------------ finished turns and summaries

    @Test
    fun `a finished turn names the outcome in the title`() {
        fun titleOf(outcome: Outcome): String? = builder
            .build(turnDraft(outcome))
            .extras
            .getString(Notification.EXTRA_TITLE)
        assertEquals(context.getString(R.string.notify_turn_succeeded, "Fix the tests"), titleOf(Outcome.Succeeded))
        assertEquals(context.getString(R.string.notify_turn_failed, "Fix the tests"), titleOf(Outcome.Failed))
        assertEquals(context.getString(R.string.notify_turn_interrupted, "Fix the tests"), titleOf(Outcome.Interrupted))
    }

    @Test
    fun `the server summary is the group summary and does not alert again`() {
        val notification = builder.build(summaryDraft())
        assertEquals("server|srv_1", notification.group)
        assertTrue(notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL == 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun `a member notification is auto-canceled, because tapping it opens something`() {
        val notification = builder.build(permissionDraft())
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    // ------------------------------------------------------------------ the ongoing notification

    @Test
    fun `the ongoing notification lists running sessions and offers an interrupt`() {
        val ongoing = ConnectionNotificationBuilder(context, codes) { serverId, sessionId ->
            NotificationIntents.openSession(context, serverId, sessionId, codes)
        }
        val words = ongoing.wordsFor(
            running = listOf(RunningSession("ses_1", "Fix the tests", isSubagent = false)),
            pending = 0,
            alwaysConnected = false,
            autoApproveUntilMillis = null,
        )
        assertTrue(words.canInterrupt)
        assertEquals(listOf("Fix the tests"), words.running)
        val notification = ongoing.build(words, "srv_1", "ses_1", pendingIntentForOpen())
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, notification.actionsOf().size)
        assertEquals(
            context.getString(R.string.action_interrupt),
            notification.actionsOf().single().title,
        )
    }

    @Test
    fun `the ongoing notification has no interrupt when nothing is running`() {
        val ongoing = ConnectionNotificationBuilder(context, codes) { serverId, sessionId ->
            NotificationIntents.openSession(context, serverId, sessionId, codes)
        }
        val words = ongoing.wordsFor(emptyList(), pending = 2, alwaysConnected = false, autoApproveUntilMillis = null)
        assertFalse(words.canInterrupt)
        assertEquals(0, ongoing.build(words, "srv_1", null, pendingIntentForOpen()).actionsOf().size)
    }

    @Test
    fun `auto-approve shows in the ongoing notification, which is the plan's persistent indicator`() {
        val ongoing = ConnectionNotificationBuilder(context, codes) { serverId, sessionId ->
            NotificationIntents.openSession(context, serverId, sessionId, codes)
        }
        val words = ongoing.wordsFor(emptyList(), 0, alwaysConnected = true, autoApproveUntilMillis = 1L)
        assertEquals(
            context.getString(R.string.connection_auto_approve_subtext),
            words.subText,
        )
    }

    @Test
    fun `the ongoing notification says what it is waiting for`() {
        val ongoing = ConnectionNotificationBuilder(context, codes) { serverId, sessionId ->
            NotificationIntents.openSession(context, serverId, sessionId, codes)
        }
        val waiting = ongoing.wordsFor(emptyList(), 2, alwaysConnected = false, autoApproveUntilMillis = null)
        assertEquals(
            context.getString(R.string.connection_waiting_title, 2),
            waiting.title,
        )
        val always = ongoing.wordsFor(emptyList(), 0, alwaysConnected = true, autoApproveUntilMillis = null)
        assertEquals(
            context.getString(R.string.connection_always_title),
            always.title,
        )
    }

    // ------------------------------------------------------------------ the "allow always" confirmation

    @Test
    fun `the confirmation names the patterns, because that is the point of confirming`() {
        val request = permission().request
        val words = builder.alwaysWords(request)
        assertEquals(
            context.getString(R.string.confirm_always_title, "bash"),
            words.title,
        )
        assertTrue(words.body.contains("bash *"))
    }

    @Test
    fun `a request with no patterns still gets a confirmation that says so`() {
        val words = builder.alwaysWords(permission().request.copy(save = null))
        assertEquals(
            context.getString(R.string.confirm_always_no_patterns),
            words.body,
        )
    }

    // ------------------------------------------------------------------ notification ids

    @Test
    fun `two notifications that would replace each other get different ids`() {
        val permission = ids.idFor(NotificationSlot.Permission("srv", "x", "ses"))
        val form = ids.idFor(NotificationSlot.Form("srv", "x", "ses"))
        assertTrue(permission != form)
    }

    // ------------------------------------------------------------------ fixtures

    private fun pendingIntentForOpen(): PendingIntent = NotificationIntents.openSession(
        context,
        "srv_1",
        "ses_1",
        codes,
    )

    private fun permission(requestId: String = "prm_1") = PendingRequest.Permission(
        PermissionRequest(
            id = requestId,
            sessionID = "ses_1",
            action = "bash",
            resources = listOf("ls -la"),
            save = listOf("bash *"),
        ),
    )

    private fun permissionDraft(requestId: String = "prm_1") = AttentionDraft(
        slot = NotificationSlot.Permission("srv_1", requestId, "ses_1"),
        channel = AttentionChannel.PERMISSION,
        content = NotificationContent.PermissionRequest(
            sessionId = "ses_1",
            sessionTitle = "Fix the tests",
            action = "bash",
            resources = listOf("ls -la"),
            savedPatterns = listOf("bash *"),
        ),
        actions = listOf(
            AttentionAction.ReplyPermission("srv_1", "ses_1", requestId, PermissionReply.Once),
            AttentionAction.ReplyPermission("srv_1", "ses_1", requestId, PermissionReply.Reject),
        ),
        timestampMillis = 1_700_000_000_000L,
    )

    private fun questionDraft(
        fields: List<EncodedFormField> = listOf(field()),
        remoteInput: RemoteInputSpec? = RemoteInputSpec("Which shell?", "q0"),
    ) = AttentionDraft(
        slot = NotificationSlot.Form("srv_1", "frm_1", "ses_1"),
        channel = AttentionChannel.QUESTION,
        content = NotificationContent.Form(
            sessionId = "ses_1",
            sessionTitle = "Fix the tests",
            title = "Which shell?",
            kind = FormKind.QUESTION,
            question = "Which shell?",
        ),
        actions = listOf(
            AttentionAction.AnswerForm("srv_1", "ses_1", "frm_1", "q0", fields),
            AttentionAction.OpenSession("srv_1", "ses_1"),
        ),
        remoteInput = remoteInput,
    )

    private fun turnDraft(outcome: Outcome) = AttentionDraft(
        slot = NotificationSlot.TurnFinished("srv_1", "ses_1", 1_700_000_000_000L),
        channel = AttentionChannel.TURN_FINISHED,
        content = NotificationContent.TurnFinished("ses_1", "Fix the tests", outcome),
        actions = listOf(AttentionAction.OpenSession("srv_1", "ses_1")),
    )

    private fun summaryDraft() = AttentionDraft(
        slot = NotificationSlot.ServerSummary("srv_1"),
        channel = AttentionChannel.PERMISSION,
        content = NotificationContent.ServerSummary("Workstation", total = 3, blocking = 2),
        onlyAlertOnce = true,
    )

    private fun field(key: String = "q0") = encodeField(
        FormField.StringField(key = key, title = "Which shell?", required = true, custom = true),
    )

    /**
     * The action rows, tolerating the platform's null for "no actions".
     *
     * `Notification.getActions()` returns null rather than an empty array when a notification has no
     * buttons, which is the case the "no interrupt" and "no reply row" tests are about, so a plain
     * `.actions` would throw exactly where the assertion is most interesting.
     */
    private fun Notification.actionsOf(): List<Notification.Action> = actions?.toList().orEmpty()

    private fun Notification.Action.remoteInputsOrEmpty(): List<android.app.RemoteInput> = remoteInputs?.toList().orEmpty()

    /**
     * The intent a pending intent was created with, which is what the receiver would receive.
     */
    private fun savedIntentOf(pendingIntent: PendingIntent): Intent =
        requireNotNull(shadowOf(pendingIntent).savedIntent)
}
