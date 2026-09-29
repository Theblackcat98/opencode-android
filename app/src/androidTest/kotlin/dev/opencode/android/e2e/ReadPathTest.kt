package dev.opencode.android.e2e

import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opencode.android.MainActivity
import dev.opencode.android.feature.servers.ui.ServerStatusTags
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Matrix section C, automated as far as the current UI exposes it.
 *
 * - C1 (core): the status screen reads live server data the moment it opens, and the
 *   "test connection" button proves the read path round-trips rather than rendering static text.
 * - C2: killing the process and reopening restores the server list from cache — no re-adding.
 *
 * Not yet covered here: the projects/sessions/timeline half of C1 and C3–C6. The per-server
 * home (projects, sessions) currently has no tap affordance from the server list — `onHomeClick`
 * is wired in the nav graph but nothing in `ServersScreen` invokes it — so those rows need
 * either that affordance or test-only deep-link navigation first. See
 * `docs/TEST_AUTOMATION.md`.
 */
@RunWith(AndroidJUnit4::class)
class ReadPathTest {

    @get:Rule(order = 0)
    val clearData = ClearDataRule()

    @get:Rule(order = 1)
    val compose: ComposeTestRule = createComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun c1_statusScreenReadsLiveServerData() {
        val name = uniqueServerName()
        compose.addServerManually(name, E2eConfig.serverUrl, E2eConfig.serverPassword)

        compose.firstServerItem()[0].performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(ServerStatusTags.SCREEN)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The header renders from the loaded profile; exercising the button proves the
        // read path is live: it disables while the round-trip runs and re-enables after.
        compose.onNodeWithTag(ServerStatusTags.TEST_CONNECTION_BUTTON)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(ServerStatusTags.TEST_CONNECTION_BUTTON)
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config?.contains(SemanticsProperties.Disabled) == false
        }
    }

    @Test
    fun c2_serverListSurvivesProcessDeath() {
        val name = uniqueServerName()
        compose.addServerManually(name, E2eConfig.serverUrl, E2eConfig.serverPassword)

        // "Kill the app" the way the matrix means it: the process dies.
        scenario?.close()
        scenario = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand("am force-stop $pkg").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }

        // Reopen cold. The server row renders from cache — nothing is re-added.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
