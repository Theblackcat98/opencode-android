package dev.opencode.android.feature.servers.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.LocationRef
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.event.SessionCreated
import dev.opencode.android.core.network.ConnectionState
import dev.opencode.android.core.network.InspectedEvent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots for the `docs/app_screen_flow.html` nodes that had no isolatable composable: the
 * screens whose bodies called `hiltViewModel()` directly now go through the extracted `*Content`
 * functions, so the baselines render the real UI with fake data and no view model.
 *
 * Every value is a placeholder. Server credentials are never held in [ServerProfile] (only whether
 * one exists), so there is nothing secret to leak into a baseline.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenFlowServersTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------------ servers

    @Test
    fun docsScreenServers() = capture("docs-screen-servers") {
        ServersContent(
            servers = listOf(
                ServerProfile(
                    id = "server-1",
                    name = "MacBook Pro",
                    baseUrl = "http://192.168.1.10:4096",
                    isDefault = true,
                    health = ServerHealth.CONNECTED,
                    hasCredential = true,
                ),
                ServerProfile(
                    id = "server-2",
                    name = "Home Server",
                    baseUrl = "http://192.168.1.20:4096",
                    health = ServerHealth.DISCONNECTED,
                    hasCredential = true,
                ),
                ServerProfile(
                    id = "server-3",
                    name = "Old Laptop",
                    baseUrl = "http://192.168.1.30:4096",
                    health = ServerHealth.REAUTH_REQUIRED,
                    hasCredential = false,
                ),
            ),
            onAddServerClick = {},
            onServerClick = {},
            onHomeClick = {},
            onEditServerClick = {},
            onPairAgainClick = {},
            onInspectorClick = {},
            onOpenChangelog = {},
            onOpenCommandPalette = {},
            onSetDefault = {},
            onDelete = {},
        )
    }

    // ------------------------------------------------------------------ add_server

    @Test
    fun docsScreenAddServer() = capture("docs-screen-add_server") {
        AddServerContent(
            uiState = AddServerUiState(
                selectedTab = AddServerTab.MANUAL,
                manualUrlInput = "http://192.168.1.10:4096",
                manualNameInput = "MacBook Pro",
            ),
            lanState = LanDiscoveryUiState(),
            isRePairing = false,
            onNavigateBack = {},
            onSelectTab = {},
            onPayloadReceived = {},
            onCameraUnavailable = {},
            onPairingLinkChange = {},
            onPairWithLink = {},
            onManualUrlChange = {},
            onManualNameChange = {},
            onManualPasswordChange = {},
            onTrustUserCertificatesChange = {},
            onConnectManually = {},
            onClearError = {},
            onLanEnabledChange = {},
            onLanPortsChange = {},
            onLanScan = {},
            onLanStop = {},
        )
    }

    // ------------------------------------------------------------------ server_status

    @Test
    fun docsScreenServerStatus() = capture("docs-screen-server_status") {
        ServerStatusContent(
            uiState = ServerStatusUiState(
                profile = ServerProfile(
                    id = "server-1",
                    name = "MacBook Pro",
                    baseUrl = "http://192.168.1.10:4096",
                    isDefault = true,
                    health = ServerHealth.CONNECTED,
                    hasCredential = true,
                    lastSeenAt = 1_729_000_000_000L,
                ),
                connectionState = ConnectionState.Connected(
                    connectedAt = 1_729_000_000_000L,
                    lastActivityAt = 1_729_000_060_000L,
                ),
                resyncCount = 3L,
                connectionLogs = listOf(
                    dev.opencode.android.core.network.ConnectionLogEntry(
                        id = "log-1",
                        timestamp = 1_729_000_000_000L,
                        type = dev.opencode.android.core.network.ConnectionEventType.CONNECTED,
                        message = "Stream opened",
                    ),
                    dev.opencode.android.core.network.ConnectionLogEntry(
                        id = "log-2",
                        timestamp = 1_729_000_030_000L,
                        type = dev.opencode.android.core.network.ConnectionEventType.HEARTBEAT,
                        message = "Heartbeat acknowledged",
                    ),
                ),
            ),
            hostState = HostLifecycleState(),
            onNavigateBack = {},
            onEditServer = {},
            onOpenInspector = {},
            onPairAgain = {},
            onTestConnection = {},
            onReconnect = {},
            onSetDefault = {},
            onClearLogs = {},
            onClearCheckError = {},
            onRequestShutdown = {},
            onCancelShutdown = {},
            onConfirmShutdown = {},
            onHandoff = {},
            onDismissNotice = {},
        )
    }

    // ------------------------------------------------------------------ edit_server

    @Test
    fun docsScreenEditServer() = capture("docs-screen-edit_server") {
        EditServerContent(
            uiState = EditServerUiState(
                profile = ServerProfile(
                    id = "server-1",
                    name = "MacBook Pro",
                    baseUrl = "http://192.168.1.10:4096",
                    isDefault = true,
                    health = ServerHealth.CONNECTED,
                    hasCredential = true,
                ),
                name = "MacBook Pro",
                url = "http://192.168.1.10:4096",
                // The password field is never pre-filled: empty means "keep the stored one".
                password = "",
                isDefault = true,
            ),
            onNavigateBack = {},
            onNameChange = {},
            onUrlChange = {},
            onPasswordChange = {},
            onDefaultChange = {},
            onTrustUserCertificatesChange = {},
            onSave = {},
            onClearError = {},
        )
    }

    // ------------------------------------------------------------------ event_inspector

    @Test
    fun docsScreenEventInspector() = capture("docs-screen-event_inspector") {
        EventInspectorContent(
            uiState = EventInspectorUiState(
                serverId = "server-1",
                serverName = "MacBook Pro",
                connectionState = ConnectionState.Connected(
                    connectedAt = 1_729_000_000_000L,
                    lastActivityAt = 1_729_000_060_000L,
                ),
            ),
            events = listOf(
                InspectedEvent(
                    id = "evt-1",
                    index = 0L,
                    receivedAt = 1_729_000_000_000L,
                    type = "server.connected",
                    rawJson = """{"type":"server.connected","version":"2.0.18"}""",
                    event = Event(
                        id = "evt-1",
                        type = "server.connected",
                        payload = EventPayload.ServerConnected,
                    ),
                ),
                InspectedEvent(
                    id = "evt-2",
                    index = 1L,
                    receivedAt = 1_729_000_005_000L,
                    type = "session.created",
                    rawJson = """{"type":"session.created","sessionID":"ses_abc123"}""",
                    event = Event(
                        id = "evt-2",
                        type = "session.created",
                        payload = SessionCreated(
                            sessionID = "ses_abc123",
                            projectID = "prj-1",
                            location = LocationRef(directory = "/home/dev/opencode-android"),
                            slug = "fix-login",
                            title = "Fix the login bug",
                            version = "2.0.18",
                        ),
                    ),
                ),
            ),
            onNavigateBack = {},
            onAddServer = {},
            onTogglePause = {},
            onClearEvents = {},
            onSearchChange = {},
            onSelectEvent = {},
            onClearCopiedMessage = {},
            onCopyJson = {},
        )
    }

    // ------------------------------------------------------------------ harness

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1f),
            ) {
                OpenCodeTheme(darkTheme = false) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        content()
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private companion object {
        /** Fixed instant so every baseline renders the same timestamps. */
        @Suppress("unused")
        val fixedNow: Long = 1_729_000_000_000L
    }
}
