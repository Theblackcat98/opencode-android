package dev.opencode.android.feature.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.opencode.android.core.data.preferences.ExperimentalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Use this shell", end to end: the switch, the route, the confirmation that names the global file, and the
 * request (manual test G7).
 *
 * **The bug this file exists for.** The shell card is enabled only when `ExperimentalPreferences.configUpdate`
 * is on and the server has `experimental.config.update`, and nothing in the app could turn the preference on:
 * the experimental sheet had five switches and none was it, while the card told the user to turn it on "in
 * settings". So the button was disabled for good and the confirmation that names the global file could not be
 * reached. The switch is in the sheet now (`ExperimentalSwitchesTest` holds that half); this file holds what
 * the card does with it.
 *
 * **The view model, the surface and the `ServerApi` are the real ones over a MockWebServer, and the card is
 * the real screen**, driven by clicks. The preference is an in-memory stand-in for the DataStore, which is
 * not what these tests are about; it is written the way the sheet's view model writes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ShellSettingTest {

    @get:Rule
    val compose = createComposeRule()

    private val directory = "/work/app"
    private lateinit var server: AdminServer
    private lateinit var scope: CoroutineScope
    private lateinit var switches: FakeExperimental
    private lateinit var model: ConfigViewModel
    private var shown by mutableStateOf(ConfigUiState())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = AdminServer(directory)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        Dispatchers.resetMain()
    }

    private fun configGet(global: String? = "/root/.config/opencode/opencode.jsonc") {
        val documents = buildList {
            global?.let { add("""{"type":"document","path":"$it","info":{"shell":"/bin/sh"}}""") }
            add("""{"type":"document","path":"$directory/.opencode/opencode.jsonc","info":{"shell":"/bin/sh"}}""")
        }
        server.answer("GET /api/config", documents.joinToString(",", "[", "]"))
        // The nearest file is read for its own keys; that it is not there is not a failure of the screen.
        server.answerPrefix("GET", "/api/fs/read/", "", 404)
    }

    private fun open(settings: ExperimentalSettings) {
        switches = FakeExperimental(settings)
        val set = server.dataSet(scope)
        model = ConfigViewModel(MutableStateFlow(set), switches)
        model.open(directory)
        await("the configuration to be read") { model.state.value.loaded }
        shown = model.state.value
    }

    private fun showCard() {
        compose.setContent {
            ConfigScreen(
                state = shown,
                onReload = {},
                onOpenEditor = {},
                onOpenDefinitions = {},
                onShellChange = {
                    model.selectShell(it)
                    shown = model.state.value
                },
                onShellRequest = {
                    model.requestShell()
                    shown = model.state.value
                },
                onShellConfirm = {
                    model.confirmShell()
                    shown = model.state.value
                },
                onShellCancel = {
                    model.cancelShell()
                    shown = model.state.value
                },
                onDismissError = {},
            )
        }
    }

    // ------------------------------------------------------------------ switched off

    @Test
    fun `with the switch off the card says where to turn it on and Use this shell stays disabled`() {
        configGet()
        open(ExperimentalSettings(configUpdate = false))
        model.selectShell("/bin/zsh")
        shown = model.state.value

        showCard()

        assertFalse(model.state.value.shellUsable)
        assertFalse(model.state.value.shellAllowed)
        // The hint names the switch by its title and the place it is in, not "settings", which had none.
        compose.onNodeWithText(
            "Turn on \"Change the server's global configuration\" in Experimental features to change the shell.",
        ).assertExists()
        compose.onNodeWithText("Use this shell").assertIsNotEnabled()
        assertTrue("nothing was sent", server.requests.none { it.startsWith("PATCH") })
    }

    // ------------------------------------------------------------------ switched on, route present

    @Test
    fun `with the switch on and the route present a new shell is enabled, confirmed against the global file, and sent`() {
        configGet()
        server.answer("PATCH /api/experimental/config", "", 204)
        open(ExperimentalSettings(configUpdate = true))
        showCard()

        // The card is on, and an unchanged shell is not a change: the button waits for a different program.
        assertTrue(model.state.value.shellUsable)
        compose.onNodeWithText("Use this shell").assertIsNotEnabled()
        compose.onNodeWithText(
            "Turn on \"Change the server's global configuration\" in Experimental features to change the shell.",
        ).assertDoesNotExist()

        model.selectShell("/bin/zsh")
        shown = model.state.value
        compose.onNodeWithText("Use this shell").assertIsEnabled().performClick()

        // The dialog names the file the server will change — the global one, as the server reported it —
        // and not the project's, which is not what `experimental.config.update` writes.
        // (The same path is also a row of the list behind the dialog, so the lookup is inside the dialog.)
        compose.onNode(hasText("/root/.config/opencode/opencode.jsonc") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(
            hasText("The bash tool and every terminal on this server will run /bin/zsh") and
                hasAnyAncestor(isDialog()),
        ).assertExists()
        assertEquals("/root/.config/opencode/opencode.jsonc", shown.shellPlan?.target)
        assertTrue(shown.shellPlan!!.isPrivilegeChange)
        assertTrue("nothing is sent before the answer", server.requests.none { it.startsWith("PATCH") })

        compose.onNodeWithText("Confirm").performClick()
        await("the request to go out") { server.requests.any { it.startsWith("PATCH /api/experimental/config") } }

        assertEquals("""{"shell":"/bin/zsh"}""", server.lastBody("experimental/config"))
        await("the answer to be folded in") { !model.state.value.busy }
        assertNull(model.state.value.error)
    }

    @Test
    fun `a global file the server did not report is named by the file it will create`() {
        configGet(global = null)
        open(ExperimentalSettings(configUpdate = true))
        model.selectShell("/bin/zsh")

        model.requestShell()

        assertEquals("~/.config/opencode/opencode.jsonc", model.state.value.shellPlan?.target)
    }

    @Test
    fun `the confirmation does not call a configuration the server already has a new file`() {
        // The confirmation ends with a size, and a server-side change has none: it quoted "A new file of
        // 0 bytes" for `/home/nick/.config/opencode/opencode.json`, a working file named three lines
        // above it. `config.get` had already said the file is there.
        configGet(global = "/root/.config/opencode/opencode.json")
        open(ExperimentalSettings(configUpdate = true))
        model.selectShell("/bin/zsh")

        model.requestShell()

        val plan = model.state.value.shellPlan!!
        assertTrue("the server applies this one itself", plan.isServerSide)
        assertEquals(
            "the file is in `config.get`, so it exists and is not a file about to be created",
            0,
            plan.previousBytes,
        )
    }

    @Test
    fun `a file the route will create is still called a new one`() {
        configGet(global = null)
        open(ExperimentalSettings(configUpdate = true))
        model.selectShell("/bin/zsh")

        model.requestShell()

        val plan = model.state.value.shellPlan!!
        assertTrue(plan.isServerSide)
        assertNull("no file of that name was reported, so there is no before", plan.previousBytes)
    }

    @Test
    fun `the global file is the one reported, whichever of the two names it has, and never the project's`() {
        assertEquals(
            "/home/me/.config/opencode/opencode.json",
            globalConfigFile(
                listOf(
                    document("/home/me/.config/opencode/opencode.json"),
                    document("/work/app/.opencode/opencode.jsonc"),
                ),
            ),
        )
        // The route writes the last of the two that exist, so that is the one named.
        assertEquals(
            "/home/me/.config/opencode/opencode.jsonc",
            globalConfigFile(
                listOf(
                    document("/home/me/.config/opencode/opencode.json"),
                    document("/home/me/.config/opencode/opencode.jsonc"),
                ),
            ),
        )
        assertEquals(
            ConfigViewModel.GLOBAL_CONFIG,
            globalConfigFile(listOf(document("/work/app/.opencode/opencode.jsonc"))),
        )
    }

    // ------------------------------------------------------------------ switched on, route absent

    @Test
    fun `a server without the route turns the card off and says so instead of asking for a switch already on`() {
        configGet()
        server.answer("PATCH /api/experimental/config", "", 404)
        open(ExperimentalSettings(configUpdate = true))
        model.selectShell("/bin/zsh")
        model.requestShell()
        model.confirmShell()
        await("the refusal to come back") { !model.state.value.busy && model.state.value.error != null }
        shown = model.state.value

        showCard()

        // The switch is on and the route is not there: the card follows the route's own answer, which it did
        // not — it read the route when the switch last changed, so after this `404` it kept offering the button.
        assertTrue(model.state.value.shellAllowed)
        assertFalse(model.state.value.shellUsable)
        compose.onNodeWithText("This server does not have the route that changes the shell.").assertExists()
        compose.onNodeWithText("Use this shell").assertIsNotEnabled()
    }

    private fun document(path: String) =
        dev.opencode.android.core.model.ConfigEntry.Document(
            pathOrNull = path,
            info = dev.opencode.android.core.model.ConfigInfo(),
        )

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * NANOS_PER_MILLI
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("Timed out waiting for $what.\nState: ${model.state.value}\nRequests: ${server.requests}")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 5L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
