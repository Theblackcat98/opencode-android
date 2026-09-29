package dev.opencode.android.e2e

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.android.MainActivity
import dev.opencode.android.feature.servers.R as ServersR
import dev.opencode.android.feature.servers.ui.AddServerTags
import dev.opencode.android.feature.servers.ui.ServerStatusTags
import dev.opencode.android.feature.servers.ui.ServersTags
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Matrix section B, automated: the connection flows against a real `opencode serve`.
 *
 * Every test starts from a wiped app ([ClearDataRule]) and drives the real UI — no fakes,
 * no stubbed view models. Server endpoints arrive as instrumentation arguments (see [E2eConfig]).
 *
 * Mapping to `docs/MANUAL_TEST_MATRIX.md`:
 * - B1: the paste-link tab redeems a real pairing code. This is the same redemption path the QR
 *   scanner feeds; the camera half of B1 stays manual.
 * - B2: manual URL + password entry.
 * - B3: the Unencrypted badge on the list and on the status page for plain HTTP.
 * - B4: a wrong password reports "pair again" instead of crashing or hanging.
 * - B5: HTTPS with the self-signed CA in the device's user-CA directory and the server's
 *   "trust user CAs" toggle on connects, with no Unencrypted badge (needs `caInstalled=true`).
 * - B6: HTTPS with no CA and no trust opt-in refuses and says why (needs `caInstalled=false`).
 * - B11: removing a server deletes it from the list. (Token removal from the keystore is
 *   covered at the unit level by `SecureCredentialStoreTest`.)
 */
@RunWith(AndroidJUnit4::class)
class ConnectionFlowTest {

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
    fun b2_manualEntryAddsServerToList() {
        compose.addServerManually(
            name = uniqueServerName(),
            url = E2eConfig.serverUrl,
            password = E2eConfig.serverPassword,
        )
    }

    @Test
    fun b1_pastedPairingLinkRedeems() {
        val code = mintPairingCode(E2eConfig.serverUrl, E2eConfig.serverPassword)
        val link = "${E2eConfig.serverUrl.trimEnd('/')}/auth/connect/$code"

        compose.onNodeWithTag(ServersTags.ADD_BUTTON).performClick()
        compose.onNodeWithTag(AddServerTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag("tab_paste_link").performClick()
        compose.onNodeWithTag(AddServerTags.PASTE_LINK_INPUT)
            .performScrollTo()
            .performTextInput(link)
        compose.closeKeyboard()
        compose.onNodeWithTag(AddServerTags.PAIR_BUTTON).performScrollTo().performClick()

        compose.waitForAddScreenGone()
        compose.waitForServerListNotEmpty()
    }

    @Test
    fun b3_httpServerShowsUnencryptedBadgeOnListAndStatus() {
        val name = uniqueServerName()
        compose.addServerManually(name, E2eConfig.serverUrl, E2eConfig.serverPassword)

        val badgeDescription = targetString(ServersR.string.servers_unencrypted_description)
        // On the list, next to the server's name.
        compose.onNodeWithContentDescription(badgeDescription).assertIsDisplayed()

        // On the status page.
        compose.firstServerItem()[0].performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(ServerStatusTags.SCREEN)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(badgeDescription).assertIsDisplayed()
    }

    @Test
    fun b4_wrongPasswordReportsRepairWithoutCrashing() {
        val name = uniqueServerName("e2e-badpw")
        compose.onNodeWithTag(ServersTags.ADD_BUTTON).performClick()
        compose.onNodeWithTag("tab_manual").performClick()
        compose.onNodeWithTag(AddServerTags.MANUAL_URL_INPUT)
            .performScrollTo()
            .performTextInput(E2eConfig.serverUrl)
        compose.closeKeyboard()
        compose.onNodeWithTag(AddServerTags.MANUAL_NAME_INPUT)
            .performScrollTo()
            .performTextInput(name)
        compose.closeKeyboard()
        compose.onNodeWithTag(AddServerTags.MANUAL_PASSWORD_INPUT)
            .performScrollTo()
            .performTextInput("definitely-the-wrong-password")
        compose.closeKeyboard()
        compose.onNodeWithTag(AddServerTags.MANUAL_CONNECT_BUTTON)
            .performScrollTo()
            .performClick()

        // The failure dialog names the problem and offers "pair again" for a rejected credential.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(targetString(ServersR.string.error_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(targetString(ServersR.string.error_pair_button)).assertIsDisplayed()

        // Recover: pair-again drops to the paste tab, back leaves to an empty server list.
        // The app is alive and nothing was added.
        compose.onNodeWithText(targetString(ServersR.string.error_pair_button)).performClick()
        compose.onNodeWithTag(AddServerTags.PASTE_LINK_INPUT).assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag(ServersTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithText(name).assertDoesNotExist()
    }

    @Test
    fun b5_httpsWithCaInstalledAndTrustOptInConnects() {
        assumeTrue(
            "Self-signed CA was not placed in the emulator's user-CA directory",
            E2eConfig.caInstalled,
        )
        val name = uniqueServerName("e2e-https")
        compose.onNodeWithTag(ServersTags.ADD_BUTTON).performClick()
        compose.onNodeWithTag("tab_manual").performClick()
        compose.onNodeWithTag(AddServerTags.MANUAL_URL_INPUT)
            .performScrollTo()
            .performTextInput(E2eConfig.httpsUrl)
        compose.closeKeyboard()
        compose.onNodeWithTag(AddServerTags.MANUAL_NAME_INPUT)
            .performScrollTo()
            .performTextInput(name)
        compose.closeKeyboard()
        compose.onNodeWithTag(AddServerTags.MANUAL_PASSWORD_INPUT)
            .performScrollTo()
            .performTextInput(E2eConfig.serverPassword)
        compose.closeKeyboard()
        // Opt this server into trusting user-installed CAs (matrix B5), then connect.
        compose.onNodeWithTag(AddServerTags.TRUST_USER_CA_TOGGLE)
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(AddServerTags.MANUAL_CONNECT_BUTTON)
            .performScrollTo()
            .performClick()

        compose.waitForAddScreenGone()
        compose.onNodeWithText(name).assertIsDisplayed()
        // HTTPS: no Unencrypted badge anywhere.
        compose.onNodeWithContentDescription(
            targetString(ServersR.string.servers_unencrypted_description)
        ).assertDoesNotExist()
    }

    @Test
    fun b6_httpsWithoutTrustRefusesAndSaysWhy() {
        assumeTrue(
            "Self-signed CA is installed; the refusal case cannot be observed",
            !E2eConfig.caInstalled,
        )
        compose.onNodeWithTag(ServersTags.ADD_BUTTON).performClick()
        compose.onNodeWithTag("tab_manual").performClick()
        compose.onNodeWithTag(AddServerTags.MANUAL_URL_INPUT)
            .performScrollTo()
            .performTextInput(E2eConfig.httpsUrl)
        compose.closeKeyboard()
        compose.onNodeWithTag(AddServerTags.MANUAL_NAME_INPUT)
            .performScrollTo()
            .performTextInput(uniqueServerName("e2e-tls"))
        compose.closeKeyboard()
        compose.onNodeWithTag(AddServerTags.MANUAL_PASSWORD_INPUT)
            .performScrollTo()
            .performTextInput(E2eConfig.serverPassword)
        compose.closeKeyboard()
        // The trust toggle stays off: a private CA the device does not know must not connect.
        compose.onNodeWithTag(AddServerTags.MANUAL_CONNECT_BUTTON)
            .performScrollTo()
            .performClick()

        // A certificate failure explains itself; it is not an auth failure, so there is
        // no "pair again" recovery offered.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(targetString(ServersR.string.error_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(targetString(ServersR.string.error_pair_button)).assertDoesNotExist()
    }

    @Test
    fun b11_removingServerDeletesItFromTheList() {
        val name = uniqueServerName()
        compose.addServerManually(name, E2eConfig.serverUrl, E2eConfig.serverPassword)
        compose.deleteServer(name)
        compose.onNodeWithTag(ServersTags.SCREEN).assertIsDisplayed()
    }
}
