package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.InboxItem
import dev.opencode.android.core.model.PromptAgentAttachment
import dev.opencode.android.core.model.PromptFileInput
import dev.opencode.android.core.model.PromptFileSource
import dev.opencode.android.core.model.PromptSkillInput
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.atomic.AtomicLong

/**
 * The write side: what the client sends, and what it refuses to guess afterwards.
 *
 * The properties that matter are the wire shape and the failure behaviour. A prompt must carry a
 * client-generated `msg_` id so a retry is the same prompt, a rejected prompt must not leave a
 * phantom in the transcript, and no call may change a store on its own — the events do that.
 */
class SessionCommandsTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var api: ServerApi
    private lateinit var timeline: TimelineStore
    private lateinit var commands: SessionCommands

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        api = ServerApiFactory(okHttpClient = OkHttpClient(), credentialProvider = { null })
            .createForReads(server.url("/").toString())
        timeline = TimelineStore("s1", "ses_a", api, scope, NoCache)
        commands = SessionCommands(
            api = api,
            timeline = { if (it == "ses_a") timeline else null },
            ids = IdGenerator(clock = { 1_000L }, random = { "aaaa" }, counter = AtomicLong(0)),
        )
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        server.close()
    }

    @Test
    fun `a prompt carries a client id, the delivery and the resume flag`() = runTest {
        server.enqueue(json(200, """{"data":{"id":"msg_1","sessionID":"ses_a","type":"user","payload":{"text":"hi"},"delivery":"steer"}}"""))
        val result = commands.prompt("ses_a", "hi", delivery = Delivery.Queue, resume = false)

        assertTrue(result.isSuccess)
        val call = server.takeRequest()
        assertEquals("/api/session/ses_a/prompt", call.url.encodedPath)
        val body = call.bodyText()
        assertTrue("the id must be client generated", body.contains("\"id\":\"msg_"))
        assertTrue(body.contains("\"delivery\":\"queue\""))
        assertTrue(body.contains("\"resume\":false"))
    }

    @Test
    fun `a prompt shows a pending item immediately and the event reconciles it`() = runTest {
        server.enqueue(json(200, """{"data":{"id":"msg_1","sessionID":"ses_a","type":"user","payload":{"text":"hi"},"delivery":"steer"}}"""))
        commands.prompt("ses_a", "hi")

        val pending = timeline.state.value.pending
        assertEquals(1, pending.size)
        val id = pending.single().id
        assertTrue("the pending id is the request id", id.startsWith("msg_"))
        assertEquals(Delivery.Steer, pending.single().item.delivery)

        // The server's event carries the same id, so the pending entry is replaced rather than doubled.
        timeline.apply(
            dev.opencode.android.core.model.event.Event.decode(
                """
                {"id":"evt_1","type":"session.inbox.enqueued","created":2,"data":{
                  "inboxID":"$id","sessionID":"ses_a","item":{"type":"user","delivery":"steer",
                  "payload":{"text":"hi"}}}}
                """.trimIndent(),
            ),
        )
        assertEquals(1, timeline.state.value.pending.size)
        assertEquals(1, timeline.state.value.messages.size)
    }

    @Test
    fun `a rejected prompt leaves no phantom in the transcript`() = runTest {
        server.enqueue(json(409, """{"_tag":"ConflictError","message":"id reused"}"""))
        val result = commands.prompt("ses_a", "hi")

        assertEquals(ActionErrorKind.CONFLICT, result.actionErrorOrNull?.kind)
        assertTrue("the optimistic item must be withdrawn", timeline.state.value.pending.isEmpty())
    }

    @Test
    fun `an offline prompt leaves no phantom either`() = runTest {
        server.close()
        val result = commands.prompt("ses_a", "hi")

        assertEquals(ActionErrorKind.OFFLINE, result.actionErrorOrNull?.kind)
        assertTrue(timeline.state.value.pending.isEmpty())
    }

    @Test
    fun `a create omits what the user did not choose`() = runTest {
        server.enqueue(json(200, """{"data":{"id":"ses_new","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":1},"location":{"directory":"/w"}}}"""))
        val result = commands.create(title = "Name", directory = "/w")

        assertEquals("ses_new", result.getOrNull()?.id)
        val body = server.takeRequest().bodyText()
        assertTrue(body.contains("\"title\":\"Name\""))
        assertTrue(body.contains("\"directory\":\"/w\""))
        assertTrue("an unchosen model stays unsaid", !body.contains("model"))
        assertTrue("an unchosen agent stays unsaid", !body.contains("agent"))
    }

    @Test
    fun `an interrupt can be asked to resume the steering input`() = runTest {
        // A bare `SessionInterruptResponse`, not the `{data: …}` wrapper most routes use.
        server.enqueue(json(200, """{"interrupted":true}"""))
        val result = commands.interrupt("ses_a", resume = true)

        assertEquals(true, result.getOrNull())
        val call = server.takeRequest()
        assertEquals("/api/session/ses_a/interrupt", call.url.encodedPath)
        assertEquals("true", call.url.queryParameter("resume"))
    }

    @Test
    fun `an interrupt without a resume flag sends no query at all`() = runTest {
        server.enqueue(json(200, """{"interrupted":true}"""))
        commands.interrupt("ses_a", resume = null)

        val call = server.takeRequest()
        assertEquals("/api/session/ses_a/interrupt", call.url.encodedPath)
        assertNull(call.url.queryParameter("resume"))
    }

    @Test
    fun `switching an agent and a model are separate calls`() = runTest {
        server.enqueue(noContent())
        server.enqueue(noContent())
        assertTrue(commands.switchAgent("ses_a", "plan").isSuccess)
        assertEquals("/api/session/ses_a/agent", server.takeRequest().url.encodedPath)

        assertTrue(commands.switchModel("ses_a", ModelRef("text", "fake", "high")).isSuccess)
        val model = server.takeRequest()
        assertEquals("/api/session/ses_a/model", model.url.encodedPath)
        assertTrue("the variant rides in the ref", model.bodyText().contains("\"variant\":\"high\""))
    }

    @Test
    fun `inbox delivery switches and cancels are the inbox routes`() = runTest {
        server.enqueue(noContent())
        server.enqueue(noContent())
        commands.setInboxDelivery("ses_a", "msg_1", Delivery.Queue)
        assertEquals(
            "/api/session/ses_a/inbox/msg_1",
            server.takeRequest().url.encodedPath,
        )
        commands.cancelInboxItem("ses_a", "msg_1")
        assertEquals(
            "/api/session/ses_a/inbox/msg_1",
            server.takeRequest().url.encodedPath,
        )
    }

    @Test
    fun `removing a session is a delete and nothing else`() = runTest {
        server.enqueue(noContent())
        assertTrue(commands.remove("ses_a").isSuccess)
        val call = server.takeRequest()
        assertEquals("/api/session/ses_a", call.url.encodedPath)
        assertEquals("DELETE", call.method)
    }

    @Test
    fun `a prompt carries its files in the shape the route accepts`() = runTest {
        server.enqueue(enqueued("msg_1"))
        commands.prompt(
            sessionID = "ses_a",
            text = "look at this",
            files = listOf(PromptFileInput("file:///work/a.ts", name = "a.ts")),
            agents = listOf(PromptAgentAttachment("build")),
            skills = listOf(PromptSkillInput("review")),
        )

        val body = server.takeRequest().bodyText()
        // The request names a uri. The stored message shape — base64 and a mime — is a different
        // type on purpose, and sending it here would be a payload the server cannot read.
        assertTrue(body.contains("\"uri\":\"file:///work/a.ts\""))
        assertTrue(!body.contains("\"source\""))
        assertTrue(body.contains("\"agents\":[{\"name\":\"build\"}]"))
        assertTrue(body.contains("\"skills\":[{\"id\":\"review\"}]"))
    }

    @Test
    fun `the optimistic item shows the same chips the confirmed one will`() = runTest {
        server.enqueue(enqueued("msg_1"))
        commands.prompt(
            sessionID = "ses_a",
            text = "look",
            files = listOf(PromptFileInput("data:image/png;base64,AAAA", name = "shot.png")),
        )

        val payload = (timeline.state.value.pending.single().item as InboxItem.User).payload
        val file = payload.files?.single()
        assertEquals("shot.png", file?.name)
        assertEquals("image/png", file?.mime)
        assertEquals(PromptFileSource.Inline, file?.source)
    }

    @Test
    fun `a command shows a pending item and is withdrawn when the server refuses it`() = runTest {
        server.enqueue(json(404, """{"_tag":"CommandNotFoundError","message":"no such command"}"""))
        val result = commands.runCommand("ses_a", "deploy", "production")

        val call = server.takeRequest()
        assertEquals("/api/session/ses_a/command", call.url.encodedPath)
        assertTrue(call.bodyText().contains("\"name\":\"deploy\""))
        assertEquals(ActionErrorKind.NOT_FOUND, result.actionErrorOrNull?.kind)
        assertTrue(timeline.state.value.pending.isEmpty())
    }

    @Test
    fun `a shell command carries the client id that makes a retry the same command`() = runTest {
        server.enqueue(noContent())
        assertTrue(commands.runShell("ses_a", "git status --short").isSuccess)

        val call = server.takeRequest()
        assertEquals("/api/session/ses_a/shell", call.url.encodedPath)
        val body = call.bodyText()
        assertTrue("a command with a side effect must not run twice", body.contains("\"id\":\"msg_"))
        assertTrue(body.contains("\"command\":\"git status --short\""))
    }

    @Test
    fun `a compaction is its own route and answers with the inbox item`() = runTest {
        server.enqueue(json(200, """{"data":{"id":"msg_1","sessionID":"ses_a","type":"compaction","payload":{},"delivery":"steer"}}"""))
        val result = commands.compact("ses_a", Delivery.Queue)

        val call = server.takeRequest()
        assertEquals("/api/session/ses_a/compact", call.url.encodedPath)
        assertTrue(call.bodyText().contains("\"delivery\":\"queue\""))
        assertEquals("msg_1", result.getOrNull()?.id)
    }

    @Test
    fun `a busy session refuses a compaction as a conflict`() = runTest {
        server.enqueue(json(409, """{"_tag":"SessionBusyError","message":"busy","sessionID":"ses_a"}"""))
        assertEquals(ActionErrorKind.SESSION_BUSY, commands.compact("ses_a").actionErrorOrNull?.kind)
    }

    @Test
    fun `a side question is read out of the data wrapper`() = runTest {
        server.enqueue(json(200, """{"data":{"text":"because the guard runs first"}}"""))
        val answer = commands.generate("ses_a", "why is it like that?").getOrNull()

        val call = server.takeRequest()
        assertEquals("/api/session/ses_a/generate", call.url.encodedPath)
        assertEquals("why is it like that?", call.bodyText().let { it.substringAfter("\"prompt\":\"").substringBefore('"') })
        assertEquals("because the guard runs first", answer)
    }

    @Test
    fun `session environment is a put of the whole map`() = runTest {
        server.enqueue(noContent())
        assertTrue(commands.setEnvironment("ses_a", mapOf("A" to "1")).isSuccess)

        val call = server.takeRequest()
        assertEquals("/api/session/ses_a/environment", call.url.encodedPath)
        assertEquals("PUT", call.method)
        assertEquals("""{"variables":{"A":"1"}}""", call.bodyText())
    }

    @Test
    fun `a skill activation is the experimental route and is expected to be missing sometimes`() = runTest {
        server.enqueue(noContent())
        assertTrue(commands.activateSkill("ses_a", "review", resume = true).isSuccess)
        val call = server.takeRequest()
        assertEquals("/api/experimental/session/ses_a/skill", call.url.encodedPath)
        assertEquals("""{"id":"review","resume":true}""", call.bodyText())

        server.enqueue(json(404, """{"_tag":"SkillNotFoundError","message":"no such skill"}"""))
        assertEquals(ActionErrorKind.NOT_FOUND, commands.activateSkill("ses_a", "nope").actionErrorOrNull?.kind)
    }

    @Test
    fun `generated ids are unique and carry the prefixes the server checks`() {
        val ids = IdGenerator(clock = { 42L }, random = { "zz" })
        val messages = List(200) { ids.nextMessageID() }
        val sessions = List(200) { ids.nextSessionID() }

        assertEquals("ids must not repeat", 200, messages.toSet().size)
        assertTrue(messages.all { it.startsWith("msg_") })
        assertTrue(sessions.all { it.startsWith("ses_") })
    }

    @Test
    fun `an unauthorized answer is the class that asks for a re-pair`() = runTest {
        server.enqueue(json(401, """{"_tag":"UnauthorizedError","message":"nope"}"""))
        val error = commands.update("ses_a", title = "x").actionErrorOrNull

        assertEquals(ActionErrorKind.UNAUTHORIZED, error?.kind)
        assertTrue(error!!.needsRepair)
        assertEquals("nope", error.message)
    }

    @Test
    fun `an error body this client cannot decode still classifies by status`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("<html>not found</html>").build())
        val error = commands.update("ses_a", title = "x").actionErrorOrNull

        assertEquals(ActionErrorKind.NOT_FOUND, error?.kind)
    }

    private fun json(code: Int, body: String): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("content-type", "application/json")
        .body(body)
        .build()

    private fun noContent(): MockResponse = MockResponse.Builder().code(204).build()

    private fun enqueued(id: String): MockResponse = json(
        200,
        """{"data":{"id":"$id","sessionID":"ses_a","type":"user","payload":{"text":"hi"},"delivery":"steer"}}""",
    )

    private fun RecordedRequest.bodyText(): String = requireNotNull(body).utf8()

    /** The commands never read the cache, so this store writes nothing. */
    private companion object {
        val NoCache = object : dev.opencode.android.core.database.cache.ReadCacheStore {
            override suspend fun readSessions(serverId: String, directory: String?, limit: Int) = emptyList<dev.opencode.android.core.model.SessionInfo>()
            override suspend fun writeSessions(serverId: String, directory: String?, sessions: List<dev.opencode.android.core.model.SessionInfo>) = Unit
            override suspend fun readSession(serverId: String, sessionId: String): dev.opencode.android.core.model.SessionInfo? = null
            override suspend fun writeSession(serverId: String, sessionId: String, session: dev.opencode.android.core.model.SessionInfo) = Unit
            override suspend fun deleteSession(serverId: String, sessionId: String) = Unit
            override suspend fun readMessages(serverId: String, sessionId: String, limit: Int) = emptyList<dev.opencode.android.core.model.SessionMessage>()
            override suspend fun writeMessages(serverId: String, sessionId: String, messages: List<dev.opencode.android.core.model.SessionMessage>, keep: Int) = Unit
            override suspend fun deleteMessages(serverId: String, sessionId: String) = Unit
            override suspend fun dropLocation(serverId: String, directory: String?) = Unit
            override suspend fun dropServer(serverId: String) = Unit
        }
    }
}
