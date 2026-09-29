package dev.opencode.android.feature.requests.notifications

import android.app.Notification
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.opencode.android.core.data.attention.AttentionAction
import dev.opencode.android.core.data.attention.AttentionActionCodes
import dev.opencode.android.core.data.attention.AttentionSettings as StoredAttentionSettings
import dev.opencode.android.core.data.attention.QuietHours as StoredQuietHours
import dev.opencode.android.core.data.attention.RemoteInputSpec
import dev.opencode.android.core.data.attention.encodeField
import dev.opencode.android.feature.requests.R
import dev.opencode.android.core.data.attention.AttentionChannel
import dev.opencode.android.core.data.attention.AttentionDraft
import dev.opencode.android.core.data.attention.NotificationContent
import dev.opencode.android.core.data.attention.NotificationIds
import dev.opencode.android.core.data.attention.NotificationSlot
import dev.opencode.android.core.data.presence.RunningSession
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.FormKind
import dev.opencode.android.core.model.Outcome
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.feature.requests.ui.AttentionSettingsContent
import dev.opencode.android.feature.requests.ui.AttentionSettingsUiState
import dev.opencode.android.feature.requests.ui.PendingAutoApprove
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of what Phase 4 puts in front of the user (plan §5.3, "UI"; §5.4, dynamic type).
 *
 * **A notification is drawn from the values the builder actually produced**, not from a hand-written
 * stand-in: the composable below reads the title, the text and the button labels off the built
 * `Notification`. A screenshot of a mock would prove the mock looks right, and the wording, the number
 * of buttons and the length of the subtext are exactly what this phase decides.
 *
 * Light, dark and 1.5x font, matching the thirteen baselines the earlier phases left.
 *
 * The settings shots override the window with `@Config(qualifiers = …)`: that screen is a long
 * scroll and a 2000dp capture would only ever show its first section.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class AttentionScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val codes = AttentionActionCodes()
    private val ids = NotificationIds()

    private val builder = AttentionNotificationBuilder(
        context = context,
        codes = codes,
        ids = ids,
        openSession = { serverId, sessionId ->
            NotificationIntents.openSession(context, serverId, sessionId, codes)
        },
    )

    @Test
    fun permissionNotification() = capture("notify-permission") {
        NotificationCard(builder.build(permissionDraft()))
    }

    @Test
    fun permissionNotificationDark() = capture("notify-permission-dark", dark = true) {
        NotificationCard(builder.build(permissionDraft(resources = listOf("/srv/project/src", "/srv/project/README.md", "/srv/.env"))))
    }

    @Test
    fun questionNotification() = capture("notify-question") {
        NotificationCard(builder.build(questionDraft()))
    }

    @Test
    fun turnFinishedNotification() = capture("notify-turn-finished") {
        NotificationCard(builder.build(turnDraft(Outcome.Failed)))
    }

    @Test
    fun subagentFinishedNotification() = capture("notify-subagent-finished") {
        NotificationCard(
            builder.build(
                AttentionDraft(
                    slot = NotificationSlot.SubagentFinished("srv_1", "ses_child", 1_700_000_000_000L),
                    channel = AttentionChannel.SUBAGENT_FINISHED,
                    content = NotificationContent.SubagentFinished(
                        sessionId = "ses_child",
                        sessionTitle = "Map the timeline projection",
                        parentTitle = "Fix the convergence check",
                        outcome = Outcome.Succeeded,
                    ),
                    actions = listOf(AttentionAction.OpenSession("srv_1", "ses_child")),
                ),
            ),
        )
    }

    @Test
    fun serverSummary() = capture("notify-summary") {
        NotificationCard(
            builder.build(
                AttentionDraft(
                    slot = NotificationSlot.ServerSummary("srv_1"),
                    channel = AttentionChannel.PERMISSION,
                    content = NotificationContent.ServerSummary("Workstation", total = 4, blocking = 2),
                    onlyAlertOnce = true,
                ),
            ),
        )
    }

    @Test
    fun ongoingConnection() = capture("notify-connection") {
        val ongoing = ConnectionNotificationBuilder(context, codes) { serverId, sessionId ->
            NotificationIntents.openSession(context, serverId, sessionId, codes)
        }
        val words = ongoing.wordsFor(
            running = listOf(
                RunningSession("ses_1", "Fix the convergence check", isSubagent = false),
                RunningSession("ses_2", "Map the timeline projection", isSubagent = true),
            ),
            pending = 1,
            alwaysConnected = true,
            autoApproveUntilMillis = 1L,
        )
        NotificationCard(
            ongoing.build(
                words = words,
                serverId = "srv_1",
                interruptSessionId = "ses_1",
                contentIntent = NotificationIntents.openSession(context, "srv_1", "ses_1", codes),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h3200dp-xhdpi")
    fun notificationPermissionNeeded() = capture("settings-notifications-blocked") {
        Settings(AttentionSettingsFixtures.state(), notificationsGranted = false)
    }

    @Test
    @Config(qualifiers = "w411dp-h3200dp-xhdpi")
    fun settingsScreen() = capture("settings-attention") {
        Settings(AttentionSettingsFixtures.state())
    }

    @Test
    @Config(qualifiers = "w411dp-h3200dp-xhdpi")
    fun settingsScreenDark() = capture("settings-attention-dark", dark = true) {
        Settings(AttentionSettingsFixtures.state())
    }

    @Test
    @Config(qualifiers = "w411dp-h3200dp-xhdpi")
    fun settingsScreenLargeFont() = capture("settings-attention-large-font", fontScale = 1.5f) {
        Settings(
            AttentionSettingsFixtures.state(
                autoApproveGlobalMinutes = 30,
                quiet = StoredQuietHours.of(22 * 60, 7 * 60),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h3200dp-xhdpi")
    fun autoApproveConfirmation() = capture("settings-auto-approve-confirm") {
        val awaiting = PendingAutoApprove(sessionId = null, minutes = 30)
        Settings(AttentionSettingsFixtures.state(awaiting = awaiting))
    }

    @Test
    fun serverUpdateNotification() = capture("notify-server-update") {
        NotificationCard(
            builder.build(
                AttentionDraft(
                    slot = NotificationSlot.ServerUpdate("srv_1", "2.0.19"),
                    channel = AttentionChannel.SERVER_UPDATE,
                    content = NotificationContent.ServerUpdate("2.0.19"),
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ rendering

    /** A notification as the shade would read it, from the values the builder produced. */
    @Composable
    private fun NotificationCard(notification: Notification) {
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                notification.extras.getString(Notification.EXTRA_SUB_TEXT)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    notification.extras.getString(Notification.EXTRA_TITLE).orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                )
                notification.extras.getString(Notification.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                val actions = notification.actions?.toList().orEmpty()
                if (actions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        actions.forEach { action ->
                            Text(action.title.toString(), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                // The inline reply box, which is what makes a one-question form answerable without
                // opening the app, so a screenshot without it would not show the feature.
                actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }?.let { reply ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            context.getString(R.string.notify_form_reply_label),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Settings(state: AttentionSettingsUiState, notificationsGranted: Boolean = true) {
        AttentionSettingsContent(
            state = state,
            notificationsGranted = notificationsGranted,
            onRequestNotifications = {},
            onOpenChannelSettings = {},
            onOpenNotificationSettings = {},
            onOpenBatterySettings = {},
            onAlwaysConnectedChange = {},
            onIdleGraceChange = {},
            onQuietHoursChange = {},
            onMutedChange = {},
            onRequestAutoApprove = { _, _ -> },
        )
    }

    private fun capture(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            OpenCodeTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        content()
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    // ------------------------------------------------------------------ fixtures

    private fun permissionDraft(
        resources: List<String> = listOf("/srv/project/src"),
    ) = AttentionDraft(
        slot = NotificationSlot.Permission("srv_1", "prm_1", "ses_1"),
        channel = AttentionChannel.PERMISSION,
        content = NotificationContent.PermissionRequest(
            sessionId = "ses_1",
            sessionTitle = "Fix the convergence check",
            action = "bash",
            resources = resources,
            savedPatterns = listOf("bash *"),
        ),
        actions = listOf(
            AttentionAction.ReplyPermission("srv_1", "ses_1", "prm_1", PermissionReply.Once),
            AttentionAction.ReplyPermission("srv_1", "ses_1", "prm_1", PermissionReply.Reject),
        ),
        timestampMillis = 1_700_000_000_000L,
    )

    private fun questionDraft() = AttentionDraft(
        slot = NotificationSlot.Form("srv_1", "frm_1", "ses_1"),
        channel = AttentionChannel.QUESTION,
        content = NotificationContent.Form(
            sessionId = "ses_1",
            sessionTitle = "Fix the convergence check",
            title = "Which shell should the tests use?",
            kind = FormKind.QUESTION,
            question = "Which shell should the tests use?",
        ),
        actions = listOf(
            AttentionAction.AnswerForm(
                serverId = "srv_1",
                sessionId = "ses_1",
                formId = "frm_1",
                fieldKey = "q0",
                fields = listOf(
                    encodeField(
                        FormField.StringField(key = "q0", title = "Which shell should the tests use?"),
                    ),
                ),
            ),
            AttentionAction.OpenSession("srv_1", "ses_1"),
        ),
        remoteInput = RemoteInputSpec("Your answer", "q0"),
    )

    private fun turnDraft(outcome: Outcome) = AttentionDraft(
        slot = NotificationSlot.TurnFinished("srv_1", "ses_1", 1_700_000_000_000L),
        channel = AttentionChannel.TURN_FINISHED,
        content = NotificationContent.TurnFinished("ses_1", "Fix the convergence check", outcome),
        actions = listOf(AttentionAction.OpenSession("srv_1", "ses_1")),
    )
}

/**
 * The settings state the screenshots read.
 *
 * The permission flag is a parameter of the screen rather than part of the state, because it is a
 * fact about the system that the screen reads when it is drawn, not something the app stores.
 */
private object AttentionSettingsFixtures {
    fun state(
        autoApproveGlobalMinutes: Long? = null,
        quiet: StoredQuietHours = StoredQuietHours(),
        awaiting: PendingAutoApprove? = null,
    ) = AttentionSettingsUiState(
        serverId = "srv_1",
        settings = StoredAttentionSettings(
            idleGraceMillis = 120_000L,
            quietHours = if (quiet.enabled) mapOf("srv_1" to quiet) else emptyMap(),
        ),
        alwaysConnected = true,
        quiet = quiet,
        autoApproveGlobalMinutes = autoApproveGlobalMinutes,
        awaitingConfirmation = awaiting,
    )
}
