package dev.opencode.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import dev.opencode.android.core.data.server.ServerDataRegistry
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.feature.requests.notifications.NotificationIntents
import dev.opencode.android.navigation.OpenCodeApp
import javax.inject.Inject

/**
 * The single activity.
 *
 * A pairing link arrives two ways: as a deep link into `…/auth/connect/<code>`, or as shared text.
 * Both are reduced to one payload, which the add-server screen treats identically, because from
 * there they are the same thing: a string to pair with.
 *
 * **A notification body is the third way in** (plan §6, Phase 4). Tapping a permission or a finished
 * turn carries a server and a session, and the navigation graph turns that into the session screen
 * without the user choosing anything. It is handled in [onNewIntent] too, because a notification
 * tapped while the app is already open arrives as a new intent rather than a fresh `onCreate`.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var coordinator: dev.opencode.android.core.data.attention.AttentionCoordinator

    @Inject
    lateinit var serviceLauncher: dev.opencode.android.feature.requests.notifications.ConnectionServiceLauncher

    /**
     * The read model, handed to the graph so the catalog browsers can resolve the sessions module's
     * agents and the composer's three lists.
     *
     * **Injected here rather than at the composable** because it is not a `ViewModel`, and
     * `hiltViewModel()` is the only injection the navigation graph does.
     */
    @Inject
    lateinit var dataSets: ServerDataRegistry

    private var sharedPayload by mutableStateOf<String?>(null)

    /** Where a tapped notification should land; the graph consumes it and it stays set. */
    private var openSession by mutableStateOf<OpenSessionTarget?>(null)

    /** A tapped shell completion, which opens a checkout's command panel rather than a session. */
    private var openLocation by mutableStateOf<OpenLocationTarget?>(null)

    /** A tapped launcher shortcut, which lands on the servers list or opens the new-session sheet. */
    private var openShortcut by mutableStateOf<OpenShortcutTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The attention layer is started in the application too; starting it here as well is harmless
        // because both calls are idempotent, and it means a launcher icon press with the process
        // already warm needs no ordering to be right.
        coordinator.start()
        serviceLauncher.start()

        sharedPayload = if (savedInstanceState == null) sharedPayloadOf(intent) else null
        openSession = if (savedInstanceState == null) openSessionOf(intent) else null
        openLocation = if (savedInstanceState == null) openLocationOf(intent) else null
        // Read only on a fresh launch. After a configuration change the intent is still the shortcut's, and
        // reading it again would send the user back to the screen the shortcut opened in the first place.
        openShortcut = if (savedInstanceState == null) shortcutOf(intent) else null

        setContent {
            OpenCodeTheme {
                OpenCodeApp(
                    sharedPayload = sharedPayload,
                    openSession = openSession,
                    openLocation = openLocation,
                    openShortcut = openShortcut,
                    // The catalog browsers read the sessions module's agents and the composer's three
                    // lists, and the app module is the only place that can hold both.
                    serverDataSets = dataSets,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedPayload = sharedPayloadOf(intent)
        openSession = openSessionOf(intent)
        openLocation = openLocationOf(intent)
        openShortcut = shortcutOf(intent)
    }

    private fun shortcutOf(intent: Intent?): OpenShortcutTarget? = LauncherShortcuts.targetOf(intent?.action)

    private fun sharedPayloadOf(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** A notification tap, reduced to a server and a session the graph can route. */
    private fun openSessionOf(intent: Intent?): OpenSessionTarget? {
        if (intent?.action != NotificationIntents.ACTION_OPEN_SESSION) return null
        val sessionId = intent.getStringExtra(NotificationIntents.EXTRA_SESSION_ID)?.takeIf(String::isNotBlank)
            ?: return null
        return OpenSessionTarget(
            serverId = intent.getStringExtra(NotificationIntents.EXTRA_SERVER_ID),
            sessionId = sessionId,
        )
    }

    /**
     * A shell completion, which names a checkout rather than a conversation.
     *
     * A finished command has no session, so it gets its own target and its own route; routing it
     * through [OpenSessionTarget] with an empty session id would navigate to a session screen with no
     * session in it.
     */
    private fun openLocationOf(intent: Intent?): OpenLocationTarget? {
        if (intent?.action != NotificationIntents.ACTION_OPEN_LOCATION) return null
        val directory = intent.getStringExtra(NotificationIntents.EXTRA_DIRECTORY)?.takeIf(String::isNotBlank)
            ?: return null
        return OpenLocationTarget(
            serverId = intent.getStringExtra(NotificationIntents.EXTRA_SERVER_ID),
            directory = directory,
        )
    }
}

/** What a tapped shell-completion notification asks the navigation graph to show. */
data class OpenLocationTarget(
    val serverId: String?,
    val directory: String,
)

/** What a tapped notification asks the navigation graph to show. */
data class OpenSessionTarget(
    val serverId: String?,
    val sessionId: String,
)

/**
 * The two static launcher shortcuts (`res/xml/shortcuts.xml`), and where each one lands.
 *
 * **The action strings sit in this app's own namespace.** [MainActivity] is exported, so any app can send
 * them, and that is harmless: each one only opens a root screen. They are the same strings the XML
 * declares, and `LauncherShortcutTest` holds the two together.
 */
object LauncherShortcuts {
    const val ACTION_SERVERS: String = "dev.opencode.android.action.SHORTCUT_SERVERS"
    const val ACTION_NEW_SESSION: String = "dev.opencode.android.action.SHORTCUT_NEW_SESSION"

    /** The target for a shortcut's action, or `null` for any other action. */
    fun targetOf(action: String?): OpenShortcutTarget? = when (action) {
        ACTION_SERVERS -> OpenShortcutTarget(ShortcutDestination.SERVERS)
        ACTION_NEW_SESSION -> OpenShortcutTarget(ShortcutDestination.NEW_SESSION)
        else -> null
    }
}

/** Where a tapped launcher shortcut lands. */
enum class ShortcutDestination { SERVERS, NEW_SESSION }

/**
 * What a tapped launcher shortcut asks the navigation graph to show.
 *
 * **Identity equality, on purpose.** The graph reacts to a change in [MainActivity]'s state, and a data
 * class would compare two taps of the same shortcut as equal, so the second tap would never reach the
 * graph at all.
 */
class OpenShortcutTarget(val destination: ShortcutDestination)
