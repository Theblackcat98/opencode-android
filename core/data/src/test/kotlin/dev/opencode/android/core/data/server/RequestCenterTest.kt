package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The request center: what the agent is blocked on, and what happens when the user answers
 * (plan §4.2).
 *
 * Driven through events and a MockWebServer rather than through a screen, because the behaviour
 * under test is reconciliation: an event that arrives twice, a resync after a reconnect, and a
 * reply that fails and therefore must leave the request pending.
 */
class RequestCenterTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var center: RequestCenter
    private lateinit var api: ServerApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        api = ServerApiFactory(
            okHttpClient = OkHttpClient(),
            credentialProvider = { null },
        ).createForReads(server.url("/").toString())
        center = RequestCenter("s1", api, scope)
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        server.close()
    }

    @Test
    fun `permission asked and replied reconcile the pending list`() = runTest {
        center.apply(askedEvent(REQUEST))
        assertEquals(listOf(REQUEST), center.permissions.value)

        center.apply(askedEvent(REQUEST))
        assertEquals("the same request twice is one request", 1, center.permissions.value.size)

        center.apply(repliedEvent(requestID = REQUEST.id))
        assertTrue(center.permissions.value.isEmpty())
    }

    @Test
    fun `form created, replied and cancelled reconcile the pending list`() = runTest {
        center.apply(formCreatedEvent(FORM))
        assertEquals(listOf(FORM), center.forms.value)

        center.apply(formRepliedEvent(FORM.id))
        assertTrue(center.forms.value.isEmpty())

        center.apply(formCreatedEvent(FORM))
        center.apply(formCancelledEvent(FORM.id))
        assertTrue(center.forms.value.isEmpty())
    }

    @Test
    fun `an event for another request does not disturb this one`() = runTest {
        center.apply(askedEvent(REQUEST))
        center.apply(askedEvent(REQUEST.copy(id = "per_other", action = "read")))
        assertEquals(2, center.permissions.value.size)
        center.apply(repliedEvent(requestID = "per_other"))
        assertEquals(listOf(REQUEST), center.permissions.value)
    }

    @Test
    fun `the global inbox lists forms before permissions`() = runTest {
        center.apply(askedEvent(REQUEST))
        center.apply(formCreatedEvent(FORM))
        val pending = center.pending.value
        assertEquals(2, pending.size)
        assertTrue("a question is the more urgent wait", pending.first() is PendingRequest.Form)
        assertEquals(setOf(REQUEST.sessionID, FORM.sessionID), center.sessionsWithPending.value)
    }

    @Test
    fun `the dock for a session shows only that session's requests`() = runTest {
        center.apply(askedEvent(REQUEST))
        center.apply(askedEvent(REQUEST.copy(id = "per_other", sessionID = "ses_other")))
        center.apply(formCreatedEvent(FORM))
        val mine = center.forSession(REQUEST.sessionID).value
        assertEquals(2, mine.size)
        assertTrue(mine.all { it.sessionID == REQUEST.sessionID })
    }

    @Test
    fun `a resync replaces what the location held rather than merging into it`() = runTest {
        center.apply(askedEvent(REQUEST))
        center.apply(sessionCreatedEvent())
        server.enqueue(jsonResponse(200, """{"location":{"directory":"/w"},"data":[]}"""))
        server.enqueue(jsonResponse(200, """{"location":{"directory":"/w"},"data":[]}"""))

        center.resync("/w")

        assertTrue("a request the server no longer lists is gone", center.permissions.value.isEmpty())
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().url.encodedPath.contains("/api/permission/request"))
    }

    @Test
    fun `a resync adopts what the server lists`() = runTest {
        center.apply(sessionCreatedEvent())
        server.enqueue(
            jsonResponse(
                200,
                """{"location":{"directory":"/w"},"data":[
                  {"id":"per_x","sessionID":"ses_probe","action":"shell","resources":["ls"]}]}""",
            ),
        )
        server.enqueue(
            jsonResponse(
                200,
                """{"location":{"directory":"/w"},"data":[
                  {"id":"frm_x","sessionID":"ses_probe","title":"Q","fields":[]}]}""",
            ),
        )

        center.resync("/w")

        assertEquals(listOf("per_x"), center.permissions.value.map { it.id })
        assertEquals(listOf("frm_x"), center.forms.value.map { it.id })
    }

    @Test
    fun `a failed resync leaves the pending list alone`() = runTest {
        center.apply(askedEvent(REQUEST))
        server.enqueue(jsonResponse(500, """{"_tag":"UnknownError","message":"boom"}"""))
        server.enqueue(jsonResponse(500, """{"_tag":"UnknownError","message":"boom"}"""))

        center.resync("/w")

        assertEquals("a dropped poll must not empty the inbox", listOf(REQUEST), center.permissions.value)
    }

    @Test
    fun `a successful reply leaves the request for the event to remove`() = runTest {
        center.apply(askedEvent(REQUEST))
        server.enqueue(noContent())
        val error = center.replyPermission(REQUEST, PermissionReply.Always)

        assertNull(error)
        val call = server.takeRequest()
        assertTrue(call.url.encodedPath.endsWith("/permission/${REQUEST.id}/reply"))
        assertTrue("the decision must be sent", call.bodyText().contains("\"decision\":\"always\""))
        assertTrue(
            "an event removes it, not the call",
            center.permissions.value.isNotEmpty(),
        )
    }

    @Test
    fun `a rejected reply reports the failure and leaves the request pending`() = runTest {
        center.apply(askedEvent(REQUEST))
        server.enqueue(jsonResponse(404, """{"_tag":"PermissionNotFoundError","message":"gone","requestID":"${REQUEST.id}"}"""))
        val error = center.replyPermission(REQUEST, PermissionReply.Reject, feedback = "no")

        assertEquals(ActionErrorKind.NOT_FOUND, error?.kind)
        assertEquals("gone", error?.message)
        assertTrue("the agent is still blocked on it", center.permissions.value.isNotEmpty())
        assertTrue(server.takeRequest().bodyText().contains("\"message\":\"no\""))
    }

    @Test
    fun `a form reply and cancel address the form endpoints`() = runTest {
        center.apply(formCreatedEvent(FORM))
        server.enqueue(noContent())
        assertNull(center.replyForm(FORM, mapOf("q0" to JsonPrimitive("bash"))))
        val reply = server.takeRequest()
        assertTrue(reply.url.encodedPath.endsWith("/form/${FORM.id}/reply"))
        assertTrue(reply.bodyText().contains("\"q0\":\"bash\""))

        server.enqueue(noContent())
        assertNull(center.cancelForm(FORM))
        assertTrue(server.takeRequest().url.encodedPath.endsWith("/form/${FORM.id}"))
    }

    @Test
    fun `dropping a location takes only that location's requests`() = runTest {
        center.apply(sessionCreatedEvent(directory = "/w"))
        center.apply(sessionCreatedEvent(sessionID = "ses_b", directory = "/other"))
        center.apply(askedEvent(REQUEST))
        center.apply(askedEvent(REQUEST.copy(id = "per_other", sessionID = "ses_b")))

        center.dropLocation("/w")

        assertEquals(listOf("per_other"), center.permissions.value.map { it.id })
    }

    @Test
    fun `a failed call maps an unreachable server to the offline class`() = runTest {
        server.close()
        val error = center.replyForm(FORM, emptyMap())
        assertEquals(ActionErrorKind.OFFLINE, error?.kind)
    }

    private fun jsonResponse(code: Int, body: String): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("content-type", "application/json")
        .body(body)
        .build()

    private fun noContent(): MockResponse = MockResponse.Builder().code(204).build()

    /** The recorded body as text, which is what a write has to assert on. */
    private fun RecordedRequest.bodyText(): String = requireNotNull(body).utf8()

    private fun jsonArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    private fun askedEvent(request: PermissionRequest): Event = Event.decode(
        """
        {"id":"evt_1","type":"permission.asked","created":1,"data":{
          "id":"${request.id}","sessionID":"${request.sessionID}","action":"${request.action}",
          "resources":${jsonArray(request.resources)},"save":${jsonArray(request.savedPatterns)}}}
        """.trimIndent(),
    )

    private fun repliedEvent(requestID: String): Event = Event.decode(
        """
        {"id":"evt_2","type":"permission.replied","created":2,"data":{
          "sessionID":"ses_probe","requestID":"$requestID","reply":"once"}}
        """.trimIndent(),
    )

    private fun formCreatedEvent(form: FormInfo): Event = Event.decode(
        """
        {"id":"evt_3","type":"form.created","created":3,"data":{"form":{
          "id":"${form.id}","sessionID":"${form.sessionID}","title":"Questions",
          "metadata":{"kind":"question"},"fields":[{"key":"q0","type":"string"}]}}}
        """.trimIndent(),
    )

    private fun formRepliedEvent(formID: String): Event = Event.decode(
        """{"id":"evt_4","type":"form.replied","created":4,"data":{"id":"$formID","sessionID":"ses_probe","answer":{"q0":"bash"}}}""",
    )

    private fun formCancelledEvent(formID: String): Event = Event.decode(
        """{"id":"evt_5","type":"form.cancelled","created":5,"data":{"id":"$formID","sessionID":"ses_probe"}}""",
    )

    private fun sessionCreatedEvent(
        sessionID: String = "ses_probe",
        directory: String = "/w",
    ): Event = Event.decode(
        """
        {"id":"evt_0","type":"session.created","created":0,"data":{
          "sessionID":"$sessionID","projectID":"p","time":{"created":0},"slug":"t","version":"2.0.18",
          "location":{"directory":"$directory"},"sandboxes":[]}}
        """.trimIndent(),
    )

    private companion object {
        val REQUEST = PermissionRequest(
            id = "per_1",
            sessionID = "ses_probe",
            action = "shell",
            resources = listOf("echo hello"),
            save = listOf("echo *"),
        )

        val FORM = FormInfo(
            id = "frm_1",
            sessionID = "ses_probe",
            title = "Questions",
            metadata = mapOf("kind" to JsonPrimitive("question")),
            fields = listOf(FormField.StringField(key = "q0")),
        )
    }
}
