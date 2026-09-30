package dev.opencode.android.feature.composer.ui

import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ------------------------------------------------------------------ a refused answer

    @Test
    fun `a permission the server no longer has is an error of the composer with no session, and stays pending`() =
        runTest {
            server.refusing(
                "/api/session/ses_other/permission/per_1/reply",
                404,
                """{"_tag":"PermissionNotFoundError","message":"No such request","requestID":"per_1"}""",
            )
            server.set.apply(permissionAsked(id = "per_1", sessionID = "ses_other"))
            val composer = unopenedComposer()

            composer.replyPermission(
                PermissionRequest(id = "per_1", sessionID = "ses_other", action = "bash"),
                PermissionReply.Once,
            )

            val error = composer.state.await("the refusal") { it.error != null }.error
            assertEquals(ActionErrorKind.NOT_FOUND, error?.kind)
            assertEquals(404, error?.httpStatus)
            assertEquals(
                "the server did not echo an answer, so the request is still waiting",
                listOf("per_1"),
                server.set.requests.currentPermissions().map { it.id },
            )
        }

    @Test
    fun `a form another client already settled is a conflict of the composer with no session, and stays pending`() =
        runTest {
            server.refusing(
                "/api/session/ses_other/form/frm_1/reply",
                409,
                """{"_tag":"FormAlreadySettledError","message":"Already answered","id":"frm_1"}""",
            )
            server.set.apply(formCreated(id = "frm_1", sessionID = "ses_other"))
            val composer = unopenedComposer()

            composer.submitForm(form(id = "frm_1", sessionID = "ses_other"), mapOf("answer" to JsonPrimitive("yes")))

            val error = composer.state.await("the refusal") { it.error != null }.error
            assertEquals(ActionErrorKind.CONFLICT, error?.kind)
            assertEquals(409, error?.httpStatus)
            assertEquals(listOf("frm_1"), server.set.requests.currentForms().map { it.id })
        }

    @Test
    fun `a cancel that cannot reach the server is offline, and the form stays pending`() = runTest {
        server.set.apply(formCreated(id = "frm_1", sessionID = "ses_other"))
        val composer = unopenedComposer()
        server.goOffline()

        composer.cancelForm(form(id = "frm_1", sessionID = "ses_other"))

        val error = composer.state.await("the failure") { it.error != null }.error
        assertEquals(ActionErrorKind.OFFLINE, error?.kind)
        assertEquals(listOf("frm_1"), server.set.requests.currentForms().map { it.id })
    }

    @Test
    fun `the next answer starts without the last one's failure, and the failure can be dismissed`() = runTest {
        server.refusing(
            "/api/session/ses_other/permission/per_1/reply",
            404,
            """{"_tag":"PermissionNotFoundError","message":"No such request","requestID":"per_1"}""",
        )
        server.accepted("/api/session/ses_other/permission/per_2/reply")
        val composer = unopenedComposer()
        composer.replyPermission(PermissionRequest("per_1", "ses_other", "bash"), PermissionReply.Once)
        composer.state.await("the first refusal") { it.error != null }

        composer.replyPermission(PermissionRequest("per_2", "ses_other", "bash"), PermissionReply.Once)

        server.awaitCall("the second reply") { it.path.endsWith("/permission/per_2/reply") }
        assertNull("the second answer went through and the old failure is not shown for it", composer.state.value.error)

        composer.replyPermission(PermissionRequest("per_1", "ses_other", "bash"), PermissionReply.Once)
        composer.state.await("the refusal again") { it.error != null }
        composer.dismissError()
        assertNull(composer.state.value.error)
    }

    private fun permissionAsked(id: String, sessionID: String) = server.event(
        "permission.asked",
        """{"id":"$id","sessionID":"$sessionID","action":"bash","resources":["ls"]}""",
    )

    private fun formCreated(id: String, sessionID: String) = server.event(
        "form.created",
        """{"form":{"id":"$id","sessionID":"$sessionID","title":"A question","fields":[{"key":"answer","type":"string"}]}}""",
    )

    private fun unopenedComposer(): ComposerViewModel = ComposerViewModel(
        active = MutableStateFlow(server.set),
        modelPreferences = FakeModelPreferences,
        memory = FakeComposerMemory(),
        attachmentReader = AttachmentReader(NoImages),
    )

    private fun form(id: String, sessionID: String): FormInfo = FormInfo(id = id, sessionID = sessionID, title = "A question")
}
