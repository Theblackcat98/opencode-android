package dev.opencode.android.feature.admin

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.preferences.ExperimentalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * A write the server refuses is something the editor says, in words, and the document stays in the box
 * (manual test G8).
 *
 * **The bug this file exists for.** With `.opencode/opencode.jsonc` read-only on the host, editing it and
 * confirming "This replaces .opencode/opencode.jsonc" made the server answer `POST
 * /api/experimental/fs/write` with `500`. The view model had the failure — `ConfigEditorUiState.error` was
 * set — and the screen never drew it: `ConfigEditorScreen` took an `onDismissError` and a state with an
 * `error`, and rendered neither. No snackbar, no banner, nothing at one, two or three seconds. The
 * definition editor drew its error; the config editor, which is the one whose write can be refused by a
 * file's mode, did not.
 *
 * **The view model, the surface and the `ServerApi` are the real ones over a MockWebServer**, and the
 * screen is the real one too, given the state the view model ended on. A test of the state alone would have
 * passed before the fix, because the state was always right; the claim is about what reaches the display.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigEditorWriteFailureTest {

    @get:Rule
    val compose = createComposeRule()

    private val directory = "/work/app"
    private val path = ".opencode/opencode.jsonc"
    private val original = """{"model":"placeholder-provider/placeholder-model"}"""
    private val edited = """{"model":"placeholder-provider/other-model"}"""

    private lateinit var server: AdminServer
    private lateinit var scope: CoroutineScope
    private lateinit var model: ConfigEditorViewModel
    private val dismissed = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = AdminServer(directory)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        server.answerPrefix("GET", "/api/fs/read/", original)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        Dispatchers.resetMain()
    }

    private fun openEditor(switches: ExperimentalSettings = ExperimentalSettings(fileWrites = true)) {
        val set = server.dataSet(scope)
        model = ConfigEditorViewModel(kotlinx.coroutines.flow.MutableStateFlow(set), FakeExperimental(switches))
        model.open(directory, path)
        await("the file to be read") { !model.state.value.reading && model.state.value.validating.not() }
    }

    /** Types [text], asks to save and confirms, leaving the view model where the user would see it. */
    private fun saveAs(text: String) {
        model.setDraft(text)
        await("the draft to be validated") { !model.state.value.validating }
        model.requestSave()
        assertNotNull("a valid document must reach the confirmation", model.state.value.plan)
        model.confirmSave()
        await("the save to finish") { !model.state.value.saving }
    }

    private fun show() {
        compose.setContent {
            ConfigEditorScreen(
                state = model.state.value,
                onPathChange = {},
                onDraftChange = {},
                onOpenTemplate = {},
                onSave = {},
                onConfirm = {},
                onCancel = {},
                onDismissOutcome = { dismissed += "outcome" },
                onDismissError = { dismissed += "error" },
            )
        }
    }

    // ------------------------------------------------------------------ G8

    @Test
    fun `a write the server refuses with a bare 500 says so, names the status and keeps the draft`() {
        server.answer("POST /api/experimental/fs/write", "", 500)
        openEditor()

        saveAs(edited)
        show()

        // What the user sees. The server gave no reason, so the row says what it does know and does not
        // put the client's own placeholder ("HTTP 500") in the server's mouth.
        compose.onNodeWithText("The server did not save this file").assertExists()
        compose.onNodeWithText("It answered HTTP 500 and gave no reason.", substring = true).assertExists()
        // The document is still in the box, exactly as typed, and is still the thing Save would send.
        compose.onNodeWithTag(AdminTags.CONFIG_EDITOR).assertTextContains(edited)
        assertEquals(edited, model.state.value.draft)
        assertNull("the confirmation is closed", model.state.value.plan)
        assertFalse(model.state.value.saving)
    }

    @Test
    fun `a write the server explains is shown in the server's own words, with the reference it logged`() {
        server.answer(
            "POST /api/experimental/fs/write",
            """{"_tag":"UnknownError","message":"EACCES: permission denied, open '$directory/$path'","ref":"err_1a2b3c4d"}""",
            500,
        )
        openEditor()

        saveAs(edited)
        show()

        compose.onNodeWithText("The server did not save this file").assertExists()
        compose.onNodeWithText("EACCES: permission denied", substring = true).assertExists()
        compose.onNodeWithText("err_1a2b3c4d", substring = true).assertExists()
        // A message the server did send is not followed by the sentence that says it sent none.
        compose.onNodeWithText("gave no reason", substring = true).assertDoesNotExist()
        assertEquals(ActionErrorKind.SERVER, model.state.value.error?.kind)
    }

    @Test
    fun `the error stays until the text is edited and a retry starts from a clean row`() {
        server.answer("POST /api/experimental/fs/write", "", 500)
        openEditor()
        saveAs(edited)
        assertNotNull(model.state.value.error)

        // Nothing clears it on its own: a failure that vanished by itself would be one the user might not
        // have read.
        Thread.sleep(SETTLE_MILLIS)
        assertNotNull(model.state.value.error)

        // A retry starts from a row with nothing in it, so a second failure is not mistaken for the first.
        model.requestSave()
        assertNull(model.state.value.error)
        assertEquals(edited, model.state.value.draft)

        // And editing the text is what a user does to fix it, which also clears the row.
        model.cancelSave()
        saveAs(edited)
        assertNotNull(model.state.value.error)
        model.setDraft(edited + " ")
        assertNull(model.state.value.error)
    }

    @Test
    fun `dismissing the error is offered on the row`() {
        server.answer("POST /api/experimental/fs/write", "", 500)
        openEditor()
        saveAs(edited)
        show()

        compose.onNodeWithTag(AdminTags.WRITE_FAILURE).assertExists()
        compose.onNodeWithTag(AdminTags.DISMISS_FAILURE).performClick()

        assertEquals(listOf("error"), dismissed)
    }

    // ------------------------------------------------------------------ the read half of the same hazard

    @Test
    fun `a file that exists and cannot be read is shown, and cannot be saved over`() {
        server.answerPrefix("GET", "/api/fs/read/", "", 500)
        openEditor()

        // The box is empty because nothing was read, which is not the same as a file that is empty; the
        // row says which, and Save is off even once something is typed, because what would be saved is
        // not the document the server holds.
        model.setDraft(edited)
        await("the draft to be validated") { !model.state.value.validating }
        show()

        compose.onNodeWithText("This file could not be read").assertExists()
        compose.onNodeWithText("It answered HTTP 500 and gave no reason.", substring = true).assertExists()
        assertNotNull(model.state.value.readError)
        assertFalse("Save must be off while the file is unreadable", model.state.value.canSave)
    }

    // ------------------------------------------------------------------ the success half

    @Test
    fun `a write that succeeded says what it did, with the server's diagnostics on the card`() {
        server.answer(
            "POST /api/experimental/fs/write",
            """{"location":{"directory":"$directory"},"data":{"path":"$path"}}""",
        )
        server.answer("POST /api/location/reload", "", 204)
        // The server reads a different file than the one written, which is the `source` diagnostic.
        server.answer(
            "GET /api/config",
            """[{"type":"document","path":"/elsewhere/opencode.jsonc","info":{}}]""",
        )
        openEditor()
        // The read-back after the write returns the new bytes.
        server.answerPrefix("GET", "/api/fs/read/", edited)

        saveAs(edited)
        show()

        val outcome = model.state.value.outcome
        assertNotNull("a successful write must leave an outcome", outcome)
        compose.onNodeWithText(outcome!!.summary, substring = true).assertExists()
        assertTrue(
            "the server's own diagnostic is on the card, not the document's problem list",
            model.state.value.diagnostics.isEmpty(),
        )
        assertTrue(model.state.value.isValid)
    }

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
        const val SETTLE_MILLIS = 200L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
