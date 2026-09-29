package dev.opencode.android.core.data.insights

import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.model.CreateFormRequest
import dev.opencode.android.core.model.CreatePermissionRequest
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.PermissionEffect
import dev.opencode.android.core.model.SessionStats
import dev.opencode.android.core.model.SessionStatsTools
import dev.opencode.android.core.model.ToolTotals
import dev.opencode.android.core.model.ToolDetailMode
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/**
 * The Phase 10 wire, over real HTTP.
 *
 * **Every one of the nine new operations is exercised against a MockWebServer, not against a mock
 * of the interface.** That is the only way the assertions below mean anything: a mocked
 * `ServerApi` would pass whether or not the route had the right method, the right query parameter
 * names, or the right body shape, and every one of those is a mistake this client could make and
 * only discover against a real server.
 *
 * The properties that are asserted here and cannot be asserted anywhere else:
 *
 *  - `experimental.session.stats` sends **nothing** for the parameters the user did not choose, so
 *    the server applies its own range rather than one this client invented.
 *  - `session.synthetic` and `session.permission.create` generate a `msg_`/`per_` id, so a retry
 *    cannot produce a second inbox item or a second request.
 *  - `rpc.call` refuses a path segment that would break the URL instead of sending it.
 *  - A `404` and a `405` from a route the server may not have hide the feature; a `500` does not.
 *  - The session log stops at `log.synced` and does not treat the end of a stream as a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsightsWireTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ServerApi
    private lateinit var surface: InsightsSurface
    private val sent = mutableListOf<RecordedRequest>()
    private val bodies = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()
    private val requests: MutableList<String> = mutableListOf()
    private val replies = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = ServerApiFactory(OkHttpClient()).createForReads(server.url("/").toString())
        surface = InsightsSurface(api)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(sent) { sent += request }
                val path = request.url.encodedPath
                synchronized(requests) { requests += "${request.method} $path?${request.url.query ?: ""}" }
                val key = "${request.method} $path"
                replies[key]?.let { return json(200, it) }
                statuses[path]?.let { status ->
                    if (status == 204) return MockResponse.Builder().code(status).build()
                    return json(status, bodies[path] ?: "{}")
                }
                val body = bodies[path]
                    ?: replies.entries.firstOrNull { it.key.endsWith(path) }?.value
                    ?: "{}"
                return json(200, body)
            }
        }
    }

    @After
    fun tearDown() = server.close()

    private fun json(status: Int, body: String): MockResponse = MockResponse.Builder()
        .code(status)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun lastRequest(): String = synchronized(requests) { requests.last() }

    private fun lastBody(): String = synchronized(sent) {
        String((sent.last().body ?: ByteString.EMPTY).toByteArray(), Charsets.UTF_8)
    }

    /** A complete statistics answer with [tools] swapped in, so every mode is a real body. */
    private fun statsWith(tools: String): String = """
        {"data":{
          "range":{"from":1000,"to":2000},
          "sessions":4,"subagents":2,"prompts":9,"steps":31,
          "tokens":{"input":100,"output":40,"reasoning":5,"cache":{"read":7,"write":2}},
          "cost":1.25,
          "tools":$tools,
          "activeDays":3,"streak":2,
          "activity":[{"date":"2026-01-01","steps":5}],
          "models":[]
        }}
    """.trimIndent()

    private fun answer(path: String, body: String) {
        bodies[path] = body
    }

    /** Answers one exact method and path, which is what the parameterised RPC route needs. */
    private fun answerRPC(key: String, body: String) {
        replies[key] = body
    }

    // ------------------------------------------------------------------ experimental.session.stats

    private val statsBody = """
        {"data":{
          "range":{"from":1000,"to":2000},
          "sessions":4,"subagents":2,"prompts":9,"steps":31,
          "tokens":{"input":100,"output":40,"reasoning":5,"cache":{"read":7,"write":2}},
          "cost":1.25,
          "tools":{"mode":"detail","totals":{"calls":10,"succeeded":8,"failed":1,"unfinished":1},
                   "usage":[{"name":"bash","calls":6,"succeeded":6,"failed":0,"unfinished":0,"durationP50":42.5}]},
          "activeDays":3,"streak":2,
          "activity":[{"date":"2026-01-01","steps":5},{"date":"2026-01-02","steps":26}],
          "models":[{"model":{"id":"m1","providerID":"p1"},"steps":3,
                     "tokens":{"input":10,"output":2,"reasoning":0,"cache":{"read":0,"write":0}},"cost":0.5}]
        }}
    """.trimIndent()

    @Test
    fun `stats sends only the parameters the caller chose`() = runTest {
        answer("/api/experimental/session/stats", statsBody)

        val result = surface.sessionStats()

        assertEquals(statsBody.let { _ -> true }, result.isSuccess)
        // Nothing at all: the server picks its own range, and a client that sent a made-up default
        // would show a different window than opencode stats does for the same question.
        assertEquals("GET /api/experimental/session/stats?", lastRequest())
        assertEquals(RouteAvailability.Present, surface.stats.value)
    }

    @Test
    fun `stats sends the range, project, zone and tool detail the user picked`() = runTest {
        answer("/api/experimental/session/stats", statsBody)

        surface.sessionStats(
            from = 1000,
            to = 2000,
            project = "prj_1",
            timezone = "Europe/Berlin",
            tools = ToolDetailMode.Detail,
            directory = "/work",
        )

        val query = lastRequest()
        assertTrue(query, query.contains("from=1000"))
        assertTrue(query, query.contains("to=2000"))
        assertTrue(query, query.contains("project=prj_1"))
        assertTrue(query, query.contains("timezone=Europe/Berlin"))
        assertTrue(query, query.contains("tools=detail"))
        assertTrue(query, query.contains("location[directory]=/work"))
    }

    @Test
    fun `stats decodes the heatmap, the totals, the models and the tool reliability`() = runTest {
        answer("/api/experimental/session/stats", statsBody)

        val stats = surface.sessionStats(tools = ToolDetailMode.Detail).getOrThrow()

        assertEquals(SessionStats.Range(1000, 2000), stats.range)
        assertEquals(4, stats.sessions)
        assertEquals(2, stats.subagents)
        assertEquals(9, stats.prompts)
        assertEquals(31, stats.steps)
        assertEquals(154, stats.tokens.total)
        assertEquals(1.25, stats.cost, 0.0001)
        assertEquals(3, stats.activeDays)
        assertEquals(2, stats.streak)
        assertEquals(listOf("2026-01-01", "2026-01-02"), stats.activity.map { it.date })
        assertEquals(listOf(5L, 26L), stats.activity.map { it.steps })
        assertEquals("p1/m1", stats.models.single().model.toString())
        assertEquals(0.5, stats.models.single().cost, 0.0001)

        val tools = stats.tools as SessionStatsTools.Detail
        assertEquals(10, tools.totals.calls)
        assertEquals(0.8, tools.totals.successRate!!, 0.0001)
        val bash = tools.usage.single()
        assertEquals("bash", bash.name)
        assertEquals(42.5, bash.durationP50!!, 0.0001)
        assertEquals(1.0, bash.successRate!!, 0.0001)
    }

    /**
     * The three modes are three different answers, and the discriminator is `mode` rather than the
     * `type` every other union in this package keys on. A serializer that assumed `type` would read
     * every one of these as `Unknown` and a dashboard would show no tool data on a server that sent
     * plenty, which is what this test exists to prevent.
     */
    @Test
    fun `the three tool modes decode to different answers`() = runTest {
        answer("/api/experimental/session/stats", statsWith("""{"mode":"none"}"""))
        val none = surface.sessionStats(tools = ToolDetailMode.None).getOrThrow().tools
        assertEquals(SessionStatsTools.None, none)
        assertTrue(lastRequest().contains("tools=none"))

        answer(
            "/api/experimental/session/stats",
            statsWith("""{"mode":"summary","totals":{"calls":0,"succeeded":0,"failed":0,"unfinished":0}}"""),
        )
        val summary = surface.sessionStats(tools = ToolDetailMode.Summary).getOrThrow().tools
        assertEquals(SessionStatsTools.Summary(ToolTotals(0, 0, 0, 0)), summary)
        // A summary of zero is the server answering "no calls", not the client failing to ask. A
        // dashboard that drew "no tools used" for a user who switched the column off would be
        // inventing a fact, and 0 of 0 is not a reliability figure.
        assertNull("0 of 0 is not a reliability figure", (summary as SessionStatsTools.Summary).totals.successRate)

        answer(
            "/api/experimental/session/stats",
            statsWith(
                """{"mode":"detail","totals":{"calls":1,"succeeded":0,"failed":1,"unfinished":0},"usage":[]}""",
            ),
        )
        val detail = surface.sessionStats(tools = ToolDetailMode.Detail).getOrThrow().tools
        assertEquals(0.0, (detail as SessionStatsTools.Detail).totals.successRate!!, 0.0001)
    }

    /** A mode this build has never heard of falls back rather than failing the whole answer. */
    @Test
    fun `an unknown tool mode is kept and does not fail the dashboard`() = runTest {
        answer("/api/experimental/session/stats", statsWith("""{"mode":"heatmap","totals":{}}"""))

        val stats = surface.sessionStats().getOrThrow()

        val unknown = stats.tools as SessionStatsTools.Unknown
        assertEquals("heatmap", unknown.discriminator)
        // The rest of the answer is still usable, which is the whole reason the union has a fallback.
        assertEquals(4, stats.sessions)
    }

    // ------------------------------------------------------------------ rpc.call

    @Test
    fun `an rpc call sends the input verbatim and returns the output verbatim`() = runTest {
        answerRPC("POST /api/rpc/git_hooks/status", """{"output":{"clean":true,"branch":"main"}}""")

        val result = surface.callRpc("git_hooks", "status", buildJsonObject { put("verbose", true) })

        val output = result.getOrThrow()
        assertEquals(JsonPrimitive(true), output["output"]?.let { (it as JsonObject)["clean"] })
        assertEquals("POST /api/rpc/git_hooks/status?", lastRequest())
        assertEquals("""{"input":{"verbose":true}}""", lastBody())
    }

    @Test
    fun `an rpc call with no input sends an empty body rather than null`() = runTest {
        answerRPC("POST /api/rpc/p/ping", """{"output":"pong"}""")

        surface.callRpc("p", "ping")

        assertEquals("""{}""", lastBody())
    }

    /**
     * The route builds a URL from two path segments, so a segment that is empty, `.`, `..` or holds a
     * separator would change which route is called. Refusing it here is the difference between a
     * clear message and a request to an endpoint nobody intended.
     */
    @Test
    fun `an rpc call refuses a segment that would break the url`() = runTest {
        for (bad in listOf("", "  ", ".", "..", "a/b", "a?b", "a#b")) {
            val result = surface.callRpc(bad, "status")
            assertEquals("rpc id '$bad'", ActionErrorKind.INVALID_REQUEST, result.exceptionOrNull()?.let {
                (it as dev.opencode.android.core.data.integrations.ActionFailure).error.kind
            })
        }
        val methodResult = surface.callRpc("good", "bad/method")
        assertEquals(
            ActionErrorKind.INVALID_REQUEST,
            (methodResult.exceptionOrNull() as dev.opencode.android.core.data.integrations.ActionFailure).error.kind,
        )
        // Nothing was sent: a refused call must not reach the network.
        assertTrue(requests.isEmpty())
    }

    // ------------------------------------------------------------------ experimental.generate

    @Test
    fun `quick ask sends the prompt and reads the text back`() = runTest {
        answer("/api/experimental/generate", """{"data":{"text":"Because the build is green."}}""")

        val text = surface.generateText("why is it green?").getOrThrow()

        assertEquals("Because the build is green.", text.text)
        assertEquals("POST /api/experimental/generate?", lastRequest())
        assertEquals("""{"prompt":"why is it green?"}""", lastBody())
        assertEquals(RouteAvailability.Present, surface.generate.value)
    }

    @Test
    fun `quick ask can name a model and says so in the body`() = runTest {
        answer("/api/experimental/generate", """{"data":{"text":"ok"}}""")

        surface.generateText("hi", ModelRef("m1", "p1", "fast"))

        assertEquals("""{"prompt":"hi","model":{"id":"m1","providerID":"p1","variant":"fast"}}""", lastBody())
    }

    // ------------------------------------------------------------------ session tools

    @Test
    fun `synthetic input carries a client id so a retry is the same item`() = runTest {
        answer(
            "/api/session/ses_1/synthetic",
            """{"data":{"id":"msg_1","sessionID":"ses_1","time":{"created":7},
               "payload":{"text":"shell output"},"delivery":"queue"}}""",
        )

        val result = surface.addSyntheticInput("ses_1", "shell output", description = "from a plugin")

        assertEquals("msg_1", result.getOrThrow().id)
        val body = Json.parseToJsonElement(lastBody()).jsonObject
        // The client mints the id, and it has to be a `msg_` one: the server treats a repeated id
        // with the same payload as the same request, which is what makes a retry after a dropped
        // response safe rather than a second inbox item.
        assertTrue(
            "the generated id must start with msg_",
            (body["id"] as JsonPrimitive).content.startsWith("msg_"),
        )
        assertEquals("shell output", (body["text"] as JsonPrimitive).content)
        assertEquals("from a plugin", (body["description"] as JsonPrimitive).content)
    }

    @Test
    fun `synthetic input can steer instead of queue`() = runTest {
        answer(
            "/api/session/ses_1/synthetic",
            """{"data":{"id":"msg_2","sessionID":"ses_1","time":{"created":7},
               "payload":{"text":"now"},"delivery":"steer"}}""",
        )

        surface.addSyntheticInput("ses_1", "now", delivery = Delivery.Steer)

        val body = Json.parseToJsonElement(lastBody()).jsonObject
        assertEquals("steer", (body["delivery"] as JsonPrimitive).content)
    }

    @Test
    fun `a created permission request carries a per id and reports the servers effect`() = runTest {
        answer("/api/session/ses_1/permission", """{"data":{"id":"per_9","effect":"ask"}}""")

        val result = surface.createPermissionRequest(
            "ses_1",
            CreatePermissionRequest(action = "bash", resources = listOf("rm -rf /")),
        )

        assertEquals("per_9", result.getOrThrow().id)
        assertEquals(PermissionEffect.Ask, result.getOrThrow().effect)
        assertTrue(result.getOrThrow().effect.needsAnswer)
        val body = Json.parseToJsonElement(lastBody()).jsonObject
        assertTrue((body["id"] as JsonPrimitive).content.startsWith("per_"))
        assertEquals("bash", (body["action"] as JsonPrimitive).content)
        assertEquals(1, (body["resources"] as kotlinx.serialization.json.JsonArray).size)
    }

    /**
     * A request the standing approvals already cover comes back `allow`, and the client has to be
     * able to tell that from one that blocks the agent: offering a choice the user cannot make is
     * worse than saying the permission is already granted.
     */
    @Test
    fun `an already allowed permission request is not a question`() = runTest {
        answer("/api/session/ses_1/permission", """{"data":{"id":"per_9","effect":"allow"}}""")

        val effect = surface.createPermissionRequest(
            "ses_1",
            CreatePermissionRequest(action = "bash", resources = listOf("ls")),
        ).getOrThrow().effect

        assertEquals(PermissionEffect.Allow, effect)
        assertTrue(!effect.needsAnswer)
    }

    @Test
    fun `a created form sends its fields and reads the form back`() = runTest {
        answer(
            "/api/session/ses_1/form",
            """{"data":{"id":"frm_1","sessionID":"ses_1","title":"Pick one",
               "fields":[{"key":"a","type":"string","label":"A"}]}}""",
        )

        val form = surface.createForm(
            "ses_1",
            CreateFormRequest(
                title = "Pick one",
                fields = listOf(FormField.StringField(key = "a", title = "A")),
            ),
        ).getOrThrow()

        assertEquals("frm_1", form.id)
        assertEquals("Pick one", form.title)
        val body = Json.parseToJsonElement(lastBody()).jsonObject
        assertTrue((body["id"] as JsonPrimitive).content.startsWith("frm_"))
        assertEquals(1, (body["fields"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `waiting for a session posts and accepts an empty answer`() = runTest {
        statuses["/api/experimental/session/ses_1/wait"] = 204

        assertTrue(surface.waitUntilIdle("ses_1").isSuccess)
        assertEquals("POST /api/experimental/session/ses_1/wait?", lastRequest())
    }

    /**
     * A wait that the server will not perform is a `503`, and it is a server-setup fact rather than a
     * reason to tell the user to try again.
     */
    @Test
    fun `a wait the server refuses is a server error and does not hide the route`() = runTest {
        statuses["/api/experimental/session/ses_1/wait"] = 503
        bodies["/api/experimental/ses_1/wait"] = """{"_tag":"ServiceUnavailableError","message":"no host"}"""

        val failure = surface.waitUntilIdle("ses_1").exceptionOrNull()
        val error = (failure as dev.opencode.android.core.data.integrations.ActionFailure).error

        assertEquals(ActionErrorKind.SERVER, error.kind)
        // 503 proves the route is there, so the feature must stay switched on.
        assertEquals(RouteAvailability.Present, surface.generate.value)
    }

    // ------------------------------------------------------------------ POST /api/pair

    @Test
    fun `a pairing code is minted and its expiry read`() = runTest {
        answer("/api/pair", """{"code":"abcd_1234","expires_in":300}""")

        val code = surface.createPairingCode().getOrThrow()

        assertEquals("abcd_1234", code.code)
        assertEquals(300, code.expiresIn)
        assertEquals("POST /api/pair?", lastRequest())
        assertEquals(RouteAvailability.Present, surface.pairDevice.value)
    }

    // ------------------------------------------------------------------ capability gating

    @Test
    fun `a missing route hides the feature`() = runTest {
        statuses["/api/experimental/session/stats"] = 404
        statuses["/api/experimental/generate"] = 404
        statuses["/api/pair"] = 404

        surface.sessionStats()
        surface.generateText("hi")
        surface.createPairingCode()

        assertEquals(RouteAvailability.Absent(404), surface.stats.value)
        assertEquals(RouteAvailability.Absent(404), surface.generate.value)
        assertEquals(RouteAvailability.Absent(404), surface.pairDevice.value)
    }

    /** A `405` is a route the server has but refuses on this method, and it hides the feature too. */
    @Test
    fun `a refused method also hides the feature`() = runTest {
        statuses["/api/pair"] = 405

        surface.createPairingCode()

        assertEquals(RouteAvailability.Absent(405), surface.pairDevice.value)
    }

    /** A `500` is a fault, not a missing route, so the feature stays. */
    @Test
    fun `a server fault does not hide the feature`() = runTest {
        statuses["/api/experimental/session/stats"] = 500

        surface.sessionStats()

        assertEquals(RouteAvailability.Present, surface.stats.value)
    }

    // ------------------------------------------------------------------ session log

    /**
     * Collected with [runBlocking] on [Dispatchers.IO], and not on `runTest`'s virtual clock.
     *
     * The reader blocks on a socket, which no virtual-time scheduler can advance: under `runTest` the
     * `withTimeout` below expires while the read is still parked. Real time, a real dispatcher and a
     * bounded timeout is the only combination that both works and can still fail instead of hanging.
     */
    @Test
    fun `the log replays entries and stops at log synced`() = runBlocking(Dispatchers.IO) {
        val stream = buildString {
            append("event: session.inbox.enqueued\n")
            append("""data: {"seq":1,"kind":"inbox"}""")
            append("\n\n")
            append("""data: {"seq":2,"kind":"step"}""")
            append("\n\n")
            append("event: log.synced\n")
            append("""data: {"seq":2}""")
            append("\n\n")
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(stream)
                .build()
        }

        val entries = withTimeout(5.seconds) { surface.readSessionLog("ses_1").toList() }

        assertEquals(2, entries.size)
        assertEquals(1L, entries[0].seq)
        assertEquals("inbox", (entries[0].fields["kind"] as JsonPrimitive).content)
        assertEquals("session.inbox.enqueued", entries[0].event)
        assertEquals(2L, entries[1].seq)
        // Reaching log.synced ends the replay without an error, and the frame itself is not an entry.
        assertTrue(entries.none { it.isSynced })
        assertEquals(RouteAvailability.Present, surface.log.value)
    }

    @Test
    fun `the log passes after and follow through as the routes own spelling`() = runBlocking(Dispatchers.IO) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(sent) { sent += request }
                synchronized(requests) { requests += "${request.method} ${request.url.encodedPath}?${request.url.query ?: ""}" }
                return MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("")
                    .build()
            }
        }
        val client = SessionLogClient(api, surface)

        withTimeout(5.seconds) { client.read("ses_1", after = "42", follow = true).toList() }

        val request = lastRequest()
        assertTrue(request, request.contains("after=42"))
        assertTrue(request, request.contains("follow=true"))
    }

    @Test
    fun `a log entry survives a payload that is neither an object nor a quoted one`() {
        val entry = SessionLogEntry.parse("not json at all", id = "1")
        assertEquals(0, entry.fields.size)
        assertNull(entry.seq)
    }

    @Test
    fun `a quoted payload is unwrapped so the fields are readable`() {
        val entry = SessionLogEntry.parse("\"{\\\"seq\\\":9}\"")
        assertEquals(9L, entry.seq)
    }
}
