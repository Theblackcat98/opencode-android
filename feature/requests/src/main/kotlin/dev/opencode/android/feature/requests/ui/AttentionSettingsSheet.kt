package dev.opencode.android.feature.requests.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.android.core.data.attention.AttentionChannel
import dev.opencode.android.core.data.attention.QuietHours
import dev.opencode.android.core.data.attention.userFacingChannels
import dev.opencode.android.feature.requests.R
import dev.opencode.android.feature.requests.notifications.AttentionChannelSpec
import dev.opencode.android.feature.requests.notifications.canPostNotifications

/**
 * Notifications and background behaviour, and the per-session controls when a session is in hand.
 *
 * **The three things a user needs to be told, in the order they bite.** Android 13 and later can
 * refuse notifications outright, so that is first; after it, whether the app may run in the
 * background at all, which is a vendor setting no permission can reach; and only then the choices
 * that are the app's own.
 *
 * **A channel's own switch lives in the system settings, not here.** Android 26 lets the user change
 * a channel's importance after it is created and does not let an app override it, so a switch here
 * would be a switch that silently does nothing. Each row therefore *opens* the system's channel
 * settings, which is the only place the change can be made.
 *
 * **Auto-approve is confirmed before it is stored** (plan §5.2), because it answers the agent's
 * permission requests without asking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttentionSettingsSheet(
    onDismiss: () -> Unit,
    sessionId: String? = null,
    viewModel: AttentionSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsGranted by remember { mutableStateOf(canPostNotifications(context)) }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    LaunchedEffect(sessionId) {
        viewModel.refreshClock()
        viewModel.forSession(sessionId)
    }

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AttentionSettingsContent(
            state = state,
            notificationsGranted = notificationsGranted,
            onRequestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivitySafely(notificationSettingsIntent())
                }
            },
            onOpenChannelSettings = { context.startActivitySafely(channelSettingsIntent(context, it)) },
            onOpenNotificationSettings = { context.startActivitySafely(notificationSettingsIntent()) },
            onOpenBatterySettings = { context.startActivitySafely(batterySettingsIntent()) },
            onAlwaysConnectedChange = viewModel::setAlwaysConnected,
            onIdleGraceChange = viewModel::setIdleGraceMinutes,
            onQuietHoursChange = viewModel::setQuietHours,
            onMutedChange = viewModel::setSessionMuted,
            onRequestAutoApprove = viewModel::requestAutoApprove,
        )
    }

    state.awaitingConfirmation?.let { awaiting ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(stringResource(R.string.settings_auto_approve_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_auto_approve_confirm_body,
                        stringResource(
                            R.string.settings_auto_approve_duration,
                            awaiting.minutes,
                        ),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmAutoApprove) {
                    Text(stringResource(R.string.settings_auto_approve_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConfirmation) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }
}

/**
 * The body of the sheet, without the sheet.
 *
 * Split out for the screenshot tests, the same way Phase 3 split its sheets: a `ModalBottomSheet`
 * needs a sheet host, and an empty overlay screenshot would show nothing about the screen the user
 * actually reads.
 */
@Composable
fun AttentionSettingsContent(
    state: AttentionSettingsUiState,
    notificationsGranted: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenChannelSettings: (AttentionChannel) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onAlwaysConnectedChange: (Boolean) -> Unit,
    onIdleGraceChange: (Long) -> Unit,
    onQuietHoursChange: (QuietHours) -> Unit,
    onMutedChange: (Boolean) -> Unit,
    onRequestAutoApprove: (String?, Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge) },
        )

        HorizontalDivider()

        // --- Notifications ---------------------------------------------------------------
        SectionTitle(R.string.settings_section_notifications)
        if (!notificationsGranted) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_notifications_blocked)) },
                supportingContent = { Text(stringResource(R.string.settings_notifications_blocked_body)) },
                trailingContent = {
                    TextButton(onClick = onRequestNotifications) {
                        Text(stringResource(R.string.settings_notifications_allow))
                    }
                },
            )
        } else {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_notifications_allowed)) },
                trailingContent = {
                    TextButton(onClick = onOpenNotificationSettings) {
                        Text(stringResource(R.string.settings_notifications_manage))
                    }
                },
            )
        }
        userFacingChannels.forEach { channel ->
            ListItem(
                headlineContent = { Text(stringResource(AttentionChannelSpec.of(channel).nameRes)) },
                supportingContent = { Text(stringResource(AttentionChannelSpec.of(channel).descriptionRes)) },
                modifier = Modifier.semantics {
                    contentDescription = ""
                },
                trailingContent = {
                    TextButton(onClick = { onOpenChannelSettings(channel) }) {
                        Text(stringResource(R.string.settings_channel_manage))
                    }
                },
            )
        }

        HorizontalDivider()

        // --- Background ------------------------------------------------------------------
        SectionTitle(R.string.settings_section_background)
        SwitchRow(
            title = R.string.settings_always_connected,
            summary = stringResource(R.string.settings_always_connected_body),
            checked = state.alwaysConnected,
            enabled = state.serverId != null,
            onChange = onAlwaysConnectedChange,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_idle_grace)) },
            supportingContent = {
                Text(
                    stringResource(
                        R.string.settings_idle_grace_body,
                        idleGraceLabel(state.settings.idleGraceMillis / 60_000L),
                    ),
                )
            },
        )
        // A flow rather than a row: at a large font scale a fixed row squeezes the last chip until it
        // wraps inside itself, which is what the 1.5x screenshot caught.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IDLE_GRACE_CHOICES.forEach { minutes ->
                FilterChip(
                    selected = state.settings.idleGraceMillis / 60_000L == minutes,
                    onClick = { onIdleGraceChange(minutes) },
                    label = { Text(idleGraceLabel(minutes)) },
                )
            }
        }
        SwitchRow(
            title = R.string.settings_quiet_hours,
            summary = state.quiet.label()
                ?.let { stringResource(R.string.settings_quiet_hours_body, it) }
                ?: stringResource(R.string.settings_quiet_hours_off),
            checked = state.quiet.enabled,
            enabled = state.serverId != null,
            onChange = { onQuietHoursChange(if (it) QuietHours() else QuietHours.of(22 * 60, 7 * 60)) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_battery)) },
            supportingContent = { Text(stringResource(R.string.settings_battery_body)) },
            trailingContent = {
                TextButton(onClick = onOpenBatterySettings) {
                    Text(stringResource(R.string.settings_battery_open))
                }
            },
        )

        HorizontalDivider()

        // --- Auto-approve ----------------------------------------------------------------
        SectionTitle(R.string.settings_section_auto_approve)
        AutoApproveRow(
            minutes = state.autoApproveGlobalMinutes,
            scopeLabel = stringResource(R.string.settings_auto_approve_global),
            bodyLabel = R.string.settings_auto_approve_global_body,
            onChange = { onRequestAutoApprove(null, it) },
        )
        if (state.isPerSession) {
            AutoApproveRow(
                minutes = state.autoApproveSessionMinutes,
                scopeLabel = stringResource(R.string.settings_auto_approve_session),
                bodyLabel = R.string.settings_auto_approve_session_body,
                onChange = { onRequestAutoApprove(state.sessionId, it) },
            )
            SwitchRow(
                title = R.string.settings_mute,
                summary = stringResource(R.string.settings_mute_body),
                checked = state.sessionMuted,
                onChange = onMutedChange,
            )
        }

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_auto_approve_explainer)) },
        )
    }
}

@Composable
private fun SectionTitle(res: Int) {
    ListItem(
        headlineContent = {
            Text(stringResource(res), style = MaterialTheme.typography.titleSmall)
        },
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    title: Int,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val label = stringResource(title)
    val description = summary
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics { },
            )
        },
        modifier = Modifier.semantics { contentDescription = "$label. $description" },
    )
}

@Composable
private fun AutoApproveRow(
    minutes: Long?,
    scopeLabel: String,
    bodyLabel: Int,
    onChange: (Long?) -> Unit,
) {
    ListItem(
        headlineContent = { Text(scopeLabel) },
        supportingContent = {
            Text(
                if (minutes == null) {
                    stringResource(bodyLabel)
                } else {
                    stringResource(R.string.settings_auto_approve_active, minutes)
                },
            )
        },
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AUTO_APPROVE_CHOICES.forEach { choice ->
            FilterChip(
                selected = minutes == choice,
                onClick = { onChange(if (minutes == choice) null else choice) },
                label = { Text(stringResource(R.string.settings_auto_approve_duration, choice)) },
            )
        }
    }
}

// ---------------------------------------------------------------------- platform intents

/** The system's own notification settings, which is the only place a channel can be changed. */
fun notificationSettingsIntent(): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)

/** The settings for one channel, where importance, sound and vibration live. */
fun channelSettingsIntent(context: Context, channel: AttentionChannel): Intent =
    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channel.id)

/**
 * The battery-optimisation settings.
 *
 * **The list, not this app's entry.** `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is the direct
 * route, but it needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which this app deliberately does not
 * declare: the permission is for apps whose core function breaks under doze, and this one stops its
 * service when there is nothing to do. The list needs no permission and the user picks.
 */
fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

/** Whether the user has already exempted this app, which is one of the background-start exemptions. */
fun batteryOptimisationDisabled(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

/** An intent the app cannot act on is ignored rather than a crash on the way to a settings screen. */
private fun Context.startActivitySafely(intent: Intent) {
    runCatching {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.recoverCatching {
        // A device with no such settings screen at all: fall back to the app's own detail page.
        if (intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
            startActivitySafely(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }
    }
}

@Composable
private fun idleGraceLabel(minutes: Long): String =
    pluralStringResource(R.plurals.settings_idle_grace_value, minutes.toInt(), minutes)

/** The grace-period choices, in minutes. Short enough to be noticed, long enough not to flap. */
internal val IDLE_GRACE_CHOICES: List<Long> = listOf(1, 2, 5, 10)

/** The auto-approve windows, in minutes. Always finite: an approval mode with no end cannot be taken back. */
internal val AUTO_APPROVE_CHOICES: List<Long> = listOf(5, 15, 30, 60)
