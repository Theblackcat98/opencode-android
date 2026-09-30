package dev.opencode.android.feature.servers.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.opencode.android.core.data.repository.ServerProfile
import dev.opencode.android.feature.servers.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The add and edit server forms keep what is typed, pasted or autofilled while the view model's echo of it
 * is still on its way.
 *
 * **Found on a device**: a 32-character password typed with `adb shell input text` (as fast as a paste or
 * a password manager fills it) ended up with its first two characters swapped, and the app then sent that
 * as the credential and reported the server had rejected it. The fields showed `state.password` back to
 * the user, and that state trails the keystrokes, so the field was rewound to an older text than the one
 * it had just reported. [LateEcho] is a state that trails on purpose, by a number of edits the test picks.
 *
 * Every assertion is on both sides of the round trip: the text on the screen, and the last text the screen
 * reported, which is what the view model would hold and what Save would send.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class ServerFormEchoTest {

    @get:Rule
    val compose = createComposeRule()

    /** 32 distinct alphanumerics, so any reordering or loss shows. */
    private val credential = "AbCdEfGhIjKlMnOpQrStUvWxYz012345"
    private val address = "https://opencode.example.com:4096"
    private val label = "Workstation on the second floor"
    private val link = "opencode://pair?url=https%3A%2F%2Fopencode.example.com&token=$credential"

    private class Field(val tag: String, val echo: LateEcho = LateEcho())

    private val editName = Field(EditServerTags.NAME_INPUT)
    private val editUrl = Field(EditServerTags.URL_INPUT)
    private val editPassword = Field(EditServerTags.PASSWORD_INPUT)

    private val manualUrl = Field(AddServerTags.MANUAL_URL_INPUT)
    private val manualName = Field(AddServerTags.MANUAL_NAME_INPUT)
    private val manualPassword = Field(AddServerTags.MANUAL_PASSWORD_INPUT)
    private val pairingLink = Field(AddServerTags.PASTE_LINK_INPUT)

    /** A masked field reports dots as its text, so the credential is revealed before it is read. */
    private fun revealCredential() {
        val show = ApplicationProvider.getApplicationContext<Context>().getString(R.string.server_password_show)
        compose.onNodeWithContentDescription(show).performClick()
    }

    private fun showEditForm() {
        compose.setContent {
            EditServerContent(
                uiState = EditServerUiState(
                    profile = ServerProfile(id = "s1", name = "old", baseUrl = "https://old.example.com"),
                    name = editName.echo.text,
                    url = editUrl.echo.text,
                    password = editPassword.echo.text,
                ),
                onNavigateBack = {},
                onNameChange = editName.echo::report,
                onUrlChange = editUrl.echo::report,
                onPasswordChange = editPassword.echo::report,
                onDefaultChange = {},
                onTrustUserCertificatesChange = {},
                onSave = {},
                onClearError = {},
            )
        }
        revealCredential()
    }

    private fun showManualEntry() {
        compose.setContent {
            ManualEntryTab(
                state = AddServerUiState(
                    selectedTab = AddServerTab.MANUAL,
                    manualUrlInput = manualUrl.echo.text,
                    manualNameInput = manualName.echo.text,
                    manualPasswordInput = manualPassword.echo.text,
                ),
                onUrlChange = manualUrl.echo::report,
                onNameChange = manualName.echo::report,
                onPasswordChange = manualPassword.echo::report,
                onTrustUserCertificatesChange = {},
                onConnect = {},
            )
        }
        revealCredential()
    }

    private fun showPasteLink() {
        compose.setContent {
            PasteLinkTab(
                state = AddServerUiState(selectedTab = AddServerTab.PASTE, pairingLinkInput = pairingLink.echo.text),
                onLinkChange = pairingLink.echo::report,
                onPair = {},
            )
        }
    }

    /** The text the field holds, which for a masked field is the real text and not the dots. */
    private fun onScreen(field: Field): String? = compose.onNodeWithTag(field.tag)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.EditableText)
        ?.text

    /** What is wrong with [field] now that [expected] was typed into it, or `null` when nothing is. */
    private fun mismatch(field: Field, expected: String): String? {
        val shown = onScreen(field)
        val reported = field.echo.lastReported
        if (shown == expected && reported == expected) return null
        return "${field.tag}: expected \"$expected\", the field shows \"$shown\" and last reported \"$reported\""
    }

    /**
     * Runs [enter] on each field in turn and fails once, naming every field that ended up wrong, so a run
     * that breaks the password is not hidden by the address that broke before it.
     */
    private fun enterEach(fields: List<Pair<Field, String>>, enter: (Field, String) -> Unit) {
        val problems = fields.mapNotNull { (field, text) ->
            enter(field, text)
            mismatch(field, text)
        }
        assertTrue(problems.joinToString(separator = "\n"), problems.isEmpty())
    }

    /** Types [text] a character at a time, letting the echo trail the typing by [lag] characters. */
    private fun typeWithLag(field: Field, text: String, lag: Int) {
        text.forEach { char ->
            compose.onNodeWithTag(field.tag).performTextInput(char.toString())
            while (field.echo.pending > lag) compose.runOnIdle { field.echo.publishOldest() }
        }
        compose.runOnIdle { while (field.echo.pending > 0) field.echo.publishOldest() }
        compose.waitForIdle()
    }

    /** Inserts [text] in one edit, as a paste or an autofill does, and lets its echo arrive afterwards. */
    private fun pasteThenEcho(field: Field, text: String) {
        compose.onNodeWithTag(field.tag).performTextInput(text)
        compose.runOnIdle { field.echo.publishOldest() }
        compose.waitForIdle()
    }

    private fun editFields() = listOf(editName to label, editUrl to address, editPassword to credential)

    private fun manualFields() = listOf(manualUrl to address, manualName to label, manualPassword to credential)

    // ------------------------------------------------------------------------- edit server

    @Test
    fun `edit server keeps a credential typed one edit ahead of its echo`() {
        showEditForm()
        enterEach(editFields()) { field, text -> typeWithLag(field, text, lag = 1) }
    }

    @Test
    fun `edit server keeps a credential typed several edits ahead of its echo`() {
        showEditForm()
        enterEach(editFields()) { field, text -> typeWithLag(field, text, lag = 5) }
    }

    @Test
    fun `edit server keeps a credential that is echoed only once it is all typed`() {
        showEditForm()
        enterEach(editFields()) { field, text -> typeWithLag(field, text, lag = text.length) }
    }

    @Test
    fun `edit server keeps a credential pasted or autofilled in one edit`() {
        showEditForm()
        enterEach(editFields()) { field, text -> pasteThenEcho(field, text) }
    }

    @Test
    fun `edit server keeps a credential when the echo skips the edits in between`() {
        showEditForm()
        enterEach(editFields()) { field, text ->
            text.forEachIndexed { index, char ->
                compose.onNodeWithTag(field.tag).performTextInput(char.toString())
                // A StateFlow conflates: every third keystroke the screen hears only the newest text.
                if (index % 3 == 2) compose.runOnIdle { field.echo.publishNewest() }
            }
            compose.runOnIdle { field.echo.publishNewest() }
            compose.waitForIdle()
        }
    }

    @Test
    fun `edit server adopts a replacement autofill makes over what was typed`() {
        showEditForm()
        typeWithLag(editPassword, "typed first", lag = 1)
        compose.onNodeWithTag(EditServerTags.PASSWORD_INPUT).performTextReplacement(credential)
        compose.runOnIdle { editPassword.echo.publishOldest() }
        compose.waitForIdle()
        assertEquals(null, mismatch(editPassword, credential))
    }

    // ------------------------------------------------------------------------- add server

    @Test
    fun `add server manual entry keeps a credential typed ahead of its echo`() {
        showManualEntry()
        enterEach(manualFields()) { field, text -> typeWithLag(field, text, lag = 1) }
    }

    @Test
    fun `add server manual entry keeps a credential that is echoed only once it is all typed`() {
        showManualEntry()
        enterEach(manualFields()) { field, text -> typeWithLag(field, text, lag = text.length) }
    }

    @Test
    fun `add server manual entry keeps a credential pasted in one edit`() {
        showManualEntry()
        enterEach(manualFields()) { field, text -> pasteThenEcho(field, text) }
    }

    @Test
    fun `add server pairing link keeps a link typed ahead of its echo`() {
        showPasteLink()
        enterEach(listOf(pairingLink to link)) { field, text -> typeWithLag(field, text, lag = 3) }
    }

    @Test
    fun `add server pairing link keeps a link pasted in one edit`() {
        showPasteLink()
        enterEach(listOf(pairingLink to link)) { field, text -> pasteThenEcho(field, text) }
    }

    @Test
    fun `add server shows an address the view model fills in from a scan`() {
        showManualEntry()
        typeWithLag(manualUrl, "http://typed", lag = 1)
        // A scanned bare address moves to the manual tab with the address filled in.
        compose.runOnIdle { manualUrl.echo.replaceFromOutside(address) }
        compose.waitForIdle()
        assertEquals(null, mismatch(manualUrl, address))
    }
}
