package dev.opencode.android.feature.composer.ui

import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The global inbox answers requests through a composer that never opened a session.
 *
 * **The inbox is not a session screen.** It lists what every session of the server is waiting on, and its
 * host asks for a [ComposerViewModel] of its own, because answering is the composer's operation. That
 * instance has no session open, and `replyPermission`, `submitForm` and `cancelForm` used to return
 * silently without one, so "Allow once" in the inbox sent nothing and said nothing. A request names its own
 * session (`request.sessionID`), so the answer needs none opened here; these tests hold that to the wire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerRequestAnswerTest {

    private lateinit var server: ComposerServer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = ComposerServer()
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a permission is answered to the session that asked, by a composer with no session open`() = runTest {
        server.accepted("/api/session/ses_other/permission/per_1/reply")
        val composer = unopenedComposer()

        composer.replyPermission(
            PermissionRequest(id = "per_1", sessionID = "ses_other", action = "bash"),
            PermissionReply.Once,
        )

        val call = server.awaitCall("the permission reply") { it.path.endsWith("/permission/per_1/reply") }
        assertEquals("POST", call.method)
        assertEquals("/api/session/ses_other/permission/per_1/reply", call.path)
        assertTrue("the reply carries the decision: ${call.body}", call.body.contains("\"once\""))
    }

    @Test
    fun `a form is cancelled on the session that asked, by a composer with no session open`() = runTest {
        server.accepted("/api/session/ses_other/form/frm_1")
        val composer = unopenedComposer()

        composer.cancelForm(form(id = "frm_1", sessionID = "ses_other"))

        val call = server.awaitCall("the form cancel") { it.path.endsWith("/form/frm_1") }
        assertEquals("DELETE", call.method)
        assertEquals("/api/session/ses_other/form/frm_1", call.path)
    }

    @Test
    fun `a form is answered on the session that asked, by a composer with no session open`() = runTest {
        server.accepted("/api/session/ses_other/form/frm_2/reply")
        val composer = unopenedComposer()

        composer.submitForm(form(id = "frm_2", sessionID = "ses_other"), mapOf("answer" to JsonPrimitive("yes")))

        val call = server.awaitCall("the form reply") { it.path.endsWith("/form/frm_2/reply") }
        assertEquals("POST", call.method)
        assertEquals("/api/session/ses_other/form/frm_2/reply", call.path)
    }

    private fun unopenedComposer(): ComposerViewModel = ComposerViewModel(
        active = MutableStateFlow(server.set),
        modelPreferences = FakeModelPreferences,
        memory = FakeComposerMemory(),
        attachmentReader = AttachmentReader(NoImages),
    )

    private fun form(id: String, sessionID: String): FormInfo = FormInfo(id = id, sessionID = sessionID, title = "A question")

    /** Waits for a request to reach the server, which is a fact about another thread and not about virtual time. */
    private suspend fun ComposerServer.awaitCall(
        what: String,
        matches: (ComposerServer.Call) -> Boolean,
    ): ComposerServer.Call =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(WAIT_MILLIS) {
                var found = calls.firstOrNull(matches)
                while (found == null) {
                    delay(POLL_MILLIS)
                    found = calls.firstOrNull(matches)
                }
                found
            }
        } ?: throw AssertionError("Timed out waiting for $what; the server saw ${calls.map { "${it.method} ${it.path}" }}")

    private companion object {
        const val WAIT_MILLIS = 3_000L
        const val POLL_MILLIS = 20L
    }
}
