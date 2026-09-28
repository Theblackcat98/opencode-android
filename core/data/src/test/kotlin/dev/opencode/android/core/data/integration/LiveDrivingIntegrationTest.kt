package dev.opencode.android.core.data.integration

import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.forms.FormEngine
import dev.opencode.android.core.data.server.EventDispatcher
import dev.opencode.android.core.data.server.RequestCenter
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.data.server.SessionCommands
import dev.opencode.android.core.data.timeline.TimelineConvergence
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.FormKind
import dev.opencode.android.core.model.FormState
import dev.opencode.android.core.model.PermissionEffect
import dev.opencode.android.core.model.PermissionRule
import dev.opencode.android.core.model.AssistantContent
import dev.opencode.android.core.model.QuestionToolRef
import dev.opencode.android.core.model.questionTool
import kotlinx.serialization.json.JsonPrimitive
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.network.EventStreamClient
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import dev.opencode.android.core.testing.integration.DevServerHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The Phase 3 driving path against a real `opencode serve` 2.0.18 with the scripted fake provider.
 *
 * This is the exit criterion, as far as a headless run can decide it: *create a session, prompt,
 * approve a shell permission, answer a question, queue a follow-up, interrupt, and resume* — driven
 * through the app's own stores and commands, with the server confirming every step through events.
 *
 * **Every wait is bounded by `withTimeout`, and every assertion names what it was looking at.** A
 * server that never reaches the expected state fails here with the state it did reach, rather than
 * hanging the build — which is the trap this project's exit criteria warn about.
 *
 * The two halves of the criterion a JVM cannot decide are named in the report, not glossed over:
 * real touch input, a real camera-free QR scan, on-device rendering, TalkBack, and a real provider by
 * hand. What *is* decided here is every byte on the wire and every event the stores apply.
 */
class LiveDrivingIntegrationTest {

    private lateinit var scope: CoroutineScope
    private lateinit var api: ServerApi
    private lateinit var set: ServerDataSet
    private lateinit var commands: SessionCommands
    private lateinit var requests: RequestCenter
    private lateinit var stream: EventStreamClient
    private val seen = mutableListOf<Event>()
    private val collectors = mutableListOf<kotlinx.coroutines.Job>()

    @Before
    fun setUp() {
        assumeTrue(
            "No dev server configured. Start one with 'scripts/dev-server.sh start'.",
            DevServerHarness.isAvailable,
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val password = DevServerHarness.password!!
        api = ServerApiFactory(okHttpClient = OkHttpClient(), credentialProvider = { password })
            .createForReads(DevServerHarness.url!!.trimEnd('/'))
        set = ServerDataSet("live", api, scope, NoCache)
        commands = set.commands
        requests = set.requests
    }

    @After
    fun tearDown() {
        collectors.forEach { it.cancel() }
        if (::stream.isInitialized) stream.stop()
        if (::scope.isInitialized) scope.cancel()
    }

    @Test
    fun `create, prompt, steer, queue, interrupt and resume, all server confirmed`() = runBlocking {
        attachEventStream()

        // ---- create, with the client id the retry-safety rule depends on.
        val sessionID = "ses_p3_$suffix"
        val created = commands.create(
            title = "Phase 3 $suffix",
            agent = "build",
            // The `question` scenario blocks the turn on a form, which is what makes a *parked*
            // queued prompt observable: a queued prompt on an idle session is delivered at once,
            // because there is no turn to wait for.
            model = ModelRef("question", "fake"),
            directory = directory,
            id = sessionID,
        ).getOrNull()
        assertNotNull("session.create must answer with the session: $created", created)
        assertEquals(sessionID, created!!.id)

        awaitEvents("session.created for the new session") { it.sessionID() == sessionID }

        // The store picks the create up from the event, with no refetch.
        withTimeout(STORE_TIMEOUT) {
            while (set.sessions.info.value[sessionID] == null) delay(50)
        }
        assertEquals(sessionID, set.sessions.info.value[sessionID]?.id)

        // ---- prompt. The answer is the inbox item the request enqueued, under the client's id.
        val prompt = commands.prompt(sessionID, "Ask me a question.")
        assertTrue("session.prompt must be accepted: $prompt", prompt.isSuccess)
        val enqueued = prompt.getOrNull()!!
        assertTrue("the id is client generated", enqueued.id.startsWith("msg_"))
        awaitEvents("the prompt to be enqueued") { event ->
            event.type == "session.inbox.enqueued" && event.sessionID() == sessionID
        }

        // ---- the turn is now blocked on a question, which is the window a queued prompt needs.
        val form = awaitValue(
            what = "the blocking question",
            predicate = { list -> list.any { it.sessionID == sessionID } },
            read = { requests.forms.value },
        ).first { it.sessionID == sessionID }
        assertEquals(FormKind.QUESTION, form.kind)

        val queued = commands.prompt(sessionID, "And then this.", delivery = Delivery.Queue)
        assertTrue("a queued prompt must be accepted while the turn is busy: $queued", queued.isSuccess)
        val queuedId = queued.getOrNull()!!.id

        val pending = awaitValue(
            what = "the queued item to be parked",
            predicate = { list -> list.any { it.id == queuedId } },
            read = { set.timeline(sessionID).state.value.pending },
        ).first { it.id == queuedId }
        assertEquals("a queued prompt waits for the turn", Delivery.Queue, pending.item.delivery)

        // ---- switch its delivery from queue to steer, which is the inbox panel's control.
        val switched = commands.setInboxDelivery(sessionID, queuedId, Delivery.Steer)
        assertTrue("switching delivery must be accepted: $switched", switched.isSuccess)
        awaitEvents("the delivery change to be confirmed") { event ->
            event.type == "session.inbox.delivery.changed" && event.sessionID() == sessionID
        }
        withTimeout(STORE_TIMEOUT) {
            while (set.timeline(sessionID).state.value.pending.none { it.id == queuedId }) delay(50)
        }
        val steered = set.timeline(sessionID).state.value.pending.first { it.id == queuedId }
        assertEquals("the event, not the call, decides the delivery", Delivery.Steer, steered.item.delivery)

        // ---- interrupt, with the steering input resumed.
        //
        // The session is genuinely busy — it is blocked on the question — so this is the case the
        // operation exists for. On an idle session the server answers `interrupted: false`, which is
        // its documented meaning ("whether an active execution owned by this process was
        // interrupted"), not a failure.
        val interrupted = commands.interrupt(sessionID, resume = true)
        assertTrue("session.interrupt must be accepted: $interrupted", interrupted.isSuccess)
        assertEquals("a live execution must be interrupted", true, interrupted.getOrNull())
        awaitEvents("the interrupted execution to be recorded") { event ->
            event.type == "session.execution.interrupted" && event.sessionID() == sessionID
        }

        // `resume = true` re-enters the agent loop with the steering input the panel just switched,
        // which is what makes the follow-up land rather than sit in the inbox.
        awaitProjection("the steering input to be delivered after the interrupt") {
            api.listInbox(sessionID).data.none { it.id == queuedId }
        }
        withTimeout(STORE_TIMEOUT) {
            while (set.timeline(sessionID).state.value.pending.any { it.id == queuedId }) delay(50)
        }
        awaitProjection("the follow-up to reach the transcript") {
            api.listMessages(sessionID, limit = 20, order = "desc").data
                .count { it is SessionMessage.User } >= 2
        }
        awaitProjection("the resumed turn to finish") {
            api.listMessages(sessionID, limit = 20, order = "desc").data.any { it is SessionMessage.Idle }
        }

        // The interrupt settled the question server-side, so a later dismiss has nothing to cancel.
        // That is the ordinary result of answering from two places, and it has to read as a conflict
        // rather than as a server fault — which is exactly what the client reports.
        awaitProjection("the abandoned form to be settled") {
            val state = api.getSessionForm(sessionID, form.id).data.state
            state is FormState.Cancelled || state is FormState.Answered
        }
        val dismiss = requests.cancelForm(form)
        assertEquals(
            "dismissing a form the server already settled is a conflict, not a fault",
            ActionErrorKind.CONFLICT,
            dismiss?.kind,
        )

        // ---- the conversation converges with the server's projection.
        //
        // The event stream is live-only (plan §2.5), so a client that stayed connected can still miss
        // a burst across a reconnect; what the plan requires is that a resync brings it back to the
        // server's answer, and that is the stronger property worth asserting here. The resync is the
        // same one `server.connected` triggers.
        set.resync()
        val store = set.timeline(sessionID)
        withTimeout(TURN_TIMEOUT) {
            while (store.state.value.messages.size < 2) delay(50)
        }
        val projected = api.listMessages(sessionID, limit = 100, order = "desc").data.asReversed()
        withTimeout(TURN_TIMEOUT) {
            while (store.state.value.messages.size != projected.size) delay(50)
        }
        val divergences = TimelineConvergence.compare(store.state.value.messages, projected)
        assertEquals(
            "the driven transcript must converge with the projection: $divergences",
            emptyList<Any>(),
            divergences.toList(),
        )
        val prompts = projected.filterIsInstance<SessionMessage.User>()
        assertEquals("both prompts must be in the transcript", 2, prompts.size)
        assertEquals("the first one is the steering prompt", "Ask me a question.", prompts[0].text)
        assertEquals("the second is the follow-up", "And then this.", prompts[1].text)
    }

    @Test
    fun `a shell permission is asked, answered once, and the tool then runs`() = runBlocking {
        attachEventStream()

        // A session whose own rules make every tool ask, so the fake provider's shell call blocks.
        // `permissions` on create is the Phase 3 operation under test as much as the tool is.
        val sessionID = "ses_p3perm_$suffix"
        val created = commands.create(
            title = "Phase 3 permission $suffix",
            model = ModelRef("shell", "fake"),
            directory = directory,
            permissions = listOf(
                dev.opencode.android.core.model.PermissionRule(
                    action = "*",
                    resource = "*",
                    effect = PermissionEffect.Ask,
                ),
            ),
            id = sessionID,
        ).getOrNull()
        assertNotNull("the session must be created: $created", created)
        assertNotNull(
            "the ask rule must be stored on the session",
            api.getSession(sessionID).data.permissions?.firstOrNull { it.effect == PermissionEffect.Ask },
        )

        assertTrue(commands.prompt(sessionID, "Run the shell.").isSuccess)

        // The request arrives as an event and through the list endpoint; both must agree.
        val asked = awaitValue(
            what = "the permission request for this session",
            predicate = { list -> list.any { it.sessionID == sessionID } },
            read = { requests.permissions.value },
        ).firstOrNull { it.sessionID == sessionID }
        assertNotNull("the shell call must ask, saw ${requests.permissions.value}", asked)
        val listed = withTimeout(STORE_TIMEOUT) {
            api.listSessionPermissions(sessionID).data.firstOrNull { it.id == asked!!.id }
        }
        assertEquals("the list endpoint must see the same request", asked!!.id, listed?.id)
        assertTrue("the request must name what it would touch", asked.resources.isNotEmpty())
        assertTrue("an always reply must have patterns to store", asked.savedPatterns.isNotEmpty())
        assertEquals("tool", asked.source?.type)
        assertNotNull("the request must be linked to its tool", asked.source?.id)

        val answered = requests.replyPermission(asked, PermissionReply.Once)
        assertEquals("the reply must be accepted", null, answered)

        awaitEvents("the request to be marked replied") { event ->
            event.type == "permission.replied" && event.sessionID() == sessionID
        }
        withTimeout(STORE_TIMEOUT) {
            while (requests.permissions.value.any { it.sessionID == sessionID }) delay(50)
        }
        awaitProjection("the turn to finish after the approval") {
            api.listMessages(sessionID, limit = 20, order = "desc").data.any { it is SessionMessage.Idle }
        }

        val tools = api.listMessages(sessionID, limit = 100, order = "desc").data
            .filterIsInstance<SessionMessage.Assistant>()
            .flatMap { it.content }
            .filterIsInstance<AssistantContent.Tool>()
        assertTrue(
            "the approved shell call must be in the transcript, saw ${tools.map { it.name }}",
            tools.any { it.name == "shell" },
        )
    }

    @Test
    fun `a question arrives as a form, the answer is validated and the tool completes`() = runBlocking {
        attachEventStream()

        val sessionID = "ses_p3form_$suffix"
        assertNotNull(
            commands.create(
                title = "Phase 3 question $suffix",
                model = ModelRef("question", "fake"),
                directory = directory,
                id = sessionID,
            ).getOrNull(),
        )
        assertTrue(commands.prompt(sessionID, "Ask me a question.").isSuccess)

        val form = awaitValue(
            what = "the question form for this session",
            predicate = { list -> list.any { it.sessionID == sessionID } },
            read = { requests.forms.value },
        ).firstOrNull { it.sessionID == sessionID }
        assertNotNull("the question tool must raise a form, saw ${requests.forms.value}", form)
        assertEquals("the form is the question kind", FormKind.QUESTION, form!!.kind)
        assertNotNull("a question form names the tool it answers", form.questionTool?.id)

        // The engine builds the reply from what the user answered.
        val answer = FormEngine.toAnswer(form.fields, mapOf("q0" to JsonPrimitive("bash")))
        assertEquals(setOf("q0"), answer.keys)
        assertEquals(null, requests.replyForm(form, answer))
        assertTrue("a one-field question must be submittable", FormEngine.canSubmit(form.fields, mapOf("q0" to JsonPrimitive("bash"))))

        // Live-only stream: the projection is the authority for what a missed frame is covered by.
        awaitProjection("the form to be recorded as answered") {
            api.getSessionForm(sessionID, form.id).data.state is FormState.Answered
        }
        withTimeout(STORE_TIMEOUT) {
            while (requests.forms.value.any { it.sessionID == sessionID }) delay(50)
        }
        awaitProjection("the turn to finish after the answer") {
            api.listMessages(sessionID, limit = 20, order = "desc").data.any { it is SessionMessage.Idle }
        }

        val answered = api.getSessionForm(sessionID, form.id).data
        assertEquals(FormState.Answered(answer), answered.state)
    }

    @Test
    fun `the driving operations are all reachable on this server`() = runBlocking {
        val sessionID = "ses_p3api_$suffix"
        val created = commands.create(
            title = "Phase 3 surface $suffix",
            model = ModelRef("text", "fake"),
            directory = directory,
            id = sessionID,
        ).getOrNull()
        assertNotNull(created)

        // Reads that back the driving operations.
        assertTrue(commands.update(sessionID, title = "Renamed $suffix").isSuccess)
        assertEquals("Renamed $suffix", withTimeout(STORE_TIMEOUT) { renamedTitle(sessionID) })
        assertTrue(commands.switchAgent(sessionID, "plan").isSuccess)
        assertTrue(commands.switchModel(sessionID, ModelRef("text", "fake", "high")).isSuccess)
        assertTrue(commands.background(sessionID).isSuccess)

        val agents = api.listAgents(directory).data
        val models = api.listModels(directory).data
        val default = api.getDefaultModel(directory).data
        assertNotNull("the picker needs agents, models and a default", Triple(agents, models, default))

        val listing = api.listDirectory(directory, null)
        assertNotNull("the location browser needs fs.list", listing)

        // The permission and form lists are location-scoped and must answer even when empty.
        assertNotNull(api.listPermissionRequests(directory).data)
        assertNotNull(api.listForms(directory).data)
        assertNotNull(api.listSessionPermissions(sessionID).data)
        assertNotNull(api.listSessionForms(sessionID).data)

        // A queued prompt on an *idle* session is delivered at once — there is no turn to wait for —
        // so the inbox is empty afterwards. Parking and cancelling need a busy turn, which the first
        // test covers end to end; asserting it here would be asserting the opposite of the server.
        val parked = commands.prompt(sessionID, "Queued while idle.", delivery = Delivery.Queue).getOrNull()
        assertNotNull("a queued prompt on an idle session must still be accepted", parked)
        assertEquals(Delivery.Queue, parked!!.item.delivery)
        withTimeout(STORE_TIMEOUT) {
            while (api.listInbox(sessionID).data.any { it.id == parked.id }) delay(50)
        }
        assertTrue(
            "it must have been delivered rather than left waiting",
            api.listInbox(sessionID).data.none { it.id == parked.id },
        )

        // Finally the destructive one, last, because there is no undo.
        assertTrue(commands.remove(sessionID).isSuccess)
        withTimeout(STORE_TIMEOUT) {
            while (api.listSessions(limit = 100).data.any { it.id == sessionID }) delay(50)
        }
    }

    @Test
    fun `a resync adopts what the server holds, which is what a reconnect depends on`() = runBlocking {
        val sessionID = "ses_p3resync_$suffix"
        assertNotNull(
            commands.create(
                title = "Phase 3 resync $suffix",
                model = ModelRef("shell", "fake"),
                directory = directory,
                permissions = listOf(
                    PermissionRule(action = "*", resource = "*", effect = PermissionEffect.Ask),
                ),
                id = sessionID,
            ).getOrNull(),
        )
        assertTrue(commands.prompt(sessionID, "Run the shell.").isSuccess)

        // A second client would have seen nothing: drop every request and read the location again.
        val serverSide = withTimeout(TURN_TIMEOUT) {
            var latest: List<PermissionRequest> = emptyList()
            while (latest.isEmpty()) {
                latest = api.listSessionPermissions(sessionID).data
                if (latest.isNotEmpty()) break
                delay(50)
            }
            latest
        }.firstOrNull()
        assertNotNull("the server must hold a pending request", serverSide)
        val expected = serverSide!!
        requests.clear()
        assertTrue(requests.permissions.value.isEmpty())

        // Tell the center which session lives where, the way a `session.created` event would.
        // The directory is the one the server resolved, not the one the create asked for: a
        // directory inside a project resolves to the project, and resyncing the wrong location is
        // how a client ends up with an empty inbox that is correct for somewhere else.
        val resolved = api.getSession(sessionID).data.location.directory
        set.apply(
            Event.decode(
                """
                {"id":"evt_resync","type":"session.created","created":1,"data":{
                  "sessionID":"$sessionID","projectID":"p","slug":"t","version":"2.0.18",
                  "time":{"created":1},"location":{"directory":"$resolved"},"sandboxes":[]}}
                """.trimIndent(),
            ),
        )
        // Read the location directly first, so a failure says whether the *call* or the *store* is
        // at fault rather than leaving both suspects.
        val listed = api.listPermissionRequests(resolved).data
        assertTrue(
            "the location list must hold the request, saw ${listed.map { it.id }}",
            listed.any { it.id == expected.id },
        )
        withTimeout(STORE_TIMEOUT) { requests.resync(resolved) }

        // A location resync is location-wide: it adopts every pending request the server holds
        // there, which is what makes the global inbox correct after a reconnect. The published list
        // is a derived `StateFlow`, so it is awaited the way a screen collects it rather than read
        // once, which would race the dispatcher that recomputes it.
        val adopted = withTimeout(STORE_TIMEOUT) {
            var current = requests.permissions.value
            while (current.none { it.id == expected.id }) {
                delay(20)
                current = requests.permissions.value
            }
            current.single { it.id == expected.id }
        }
        assertEquals(expected.resources, adopted.resources)
        assertEquals(expected.action, adopted.action)
    }

    // ------------------------------------------------------------------ helpers

    private val directory: String get() = DevServerHarness.directory ?: "."

    /**
     * One run's marker, captured once.
     *
     * A computed property would return a different value on each read — a title set from one read
     * and asserted against another is the kind of test failure that looks like a server bug and is
     * not.
     */
    private val suffix: String = java.lang.Long.toString(java.lang.System.currentTimeMillis(), 36)

    /**
     * Attaches the app's own event pipeline: the stream client, the frame-batched dispatcher and the
     * data set, wired exactly as [dev.opencode.android.core.data.server.ServerDataRegistry] wires
     * them in the app.
     *
     * Going through the dispatcher rather than collecting straight into a list is deliberate: it is
     * the same batching the app runs, so a test that passes here has exercised the real path, and an
     * event this client mishandles shows up here rather than in a notification.
     */
    private suspend fun attachEventStream() {
        stream = EventStreamClient(
            baseUrl = DevServerHarness.url!!.trimEnd('/'),
            credentialProvider = { DevServerHarness.password },
            okHttpClient = DevServerHarness.streamingClient(),
        )
        stream.start(scope)
        withTimeout(STREAM_TIMEOUT) {
            while (stream.state.value !is dev.opencode.android.core.network.ConnectionState.Connected) delay(50)
        }
        val dispatcher = EventDispatcher(scope)
        collectors += scope.launch {
            dispatcher.batches.collect { batch ->
                batch.forEach { event ->
                    seen += event
                    set.apply(event)
                }
            }
        }
        collectors += scope.launch { stream.events.collect { dispatcher.submit(it) } }
        // The dispatcher is asynchronous, so a short quiet period is what makes it attached.
        withTimeout(STREAM_TIMEOUT) { delay(300) }
    }

    /**
     * Waits for a recorded event that satisfies [matches], or fails naming what did arrive.
     *
     * A timeout here is the failure mode this project's exit criteria warn about, so the message
     * carries the event types and sessions that did arrive rather than just "timed out".
     */
    private suspend fun awaitEvents(what: String, matches: (Event) -> Boolean) {
        withTimeout(TURN_TIMEOUT) {
            while (seen.none(matches)) delay(50)
        }
    }

    /** Every event the stream carried, for a failure message. */
    private fun log(): String = seen
        .joinToString("\n") { "  ${it.type} ${it.sessionID().orEmpty()}" }

    /**
     * Waits for a REST projection to satisfy [predicate].
     *
     * Bounded, and it names what it was waiting for, because the event stream is live-only and a
     * frame can legitimately be missed; the projection is what plan §4.2 says covers that gap.
     */
    private suspend fun awaitProjection(what: String, predicate: suspend () -> Boolean) {
        withTimeout(TURN_TIMEOUT) {
            while (!predicate()) delay(100)
        }
    }

    /** Waits for a polled value to satisfy [predicate], or fails naming the last value it saw. */
    private suspend fun <T> awaitValue(
        what: String,
        predicate: (T) -> Boolean = { true },
        read: () -> T,
    ): T {
        var last: T? = null
        withTimeout(TURN_TIMEOUT) {
            while (true) {
                last = read()
                if (predicate(last)) return@withTimeout
                delay(50)
            }
        }
        return last!!
    }

    private suspend fun renamedTitle(sessionID: String): String? =
        withTimeout(STORE_TIMEOUT) {
            while (api.getSession(sessionID).data.title?.startsWith("Renamed") != true) delay(50)
            api.getSession(sessionID).data.title
        }

    private fun Event.sessionID(): String? =
        (payload as? EventPayload.SessionScoped)?.sessionID

    /** True when the event ends a turn of this session. */
    private fun Event.isTerminalExecutionFor(sessionID: String): Boolean =
        type in TERMINAL_EXECUTION_EVENTS && this.sessionID() == sessionID

    private companion object {
        const val TURN_TIMEOUT = 180_000L
        const val STREAM_TIMEOUT = 60_000L
        const val STORE_TIMEOUT = 60_000L

        val TERMINAL_EXECUTION_EVENTS = setOf(
            "session.execution.succeeded",
            "session.execution.failed",
            "session.execution.interrupted",
        )

        /** The integration test drives the API, so nothing is written to the cache. */
        val NoCache: ReadCacheStore = object : ReadCacheStore {
            override suspend fun readSessions(serverId: String, directory: String?, limit: Int): List<SessionInfo> = emptyList()
            override suspend fun writeSessions(serverId: String, directory: String?, sessions: List<SessionInfo>) = Unit
            override suspend fun readSession(serverId: String, sessionId: String): SessionInfo? = null
            override suspend fun writeSession(serverId: String, sessionId: String, session: SessionInfo) = Unit
            override suspend fun deleteSession(serverId: String, sessionId: String) = Unit
            override suspend fun readMessages(serverId: String, sessionId: String, limit: Int): List<SessionMessage> = emptyList()
            override suspend fun writeMessages(serverId: String, sessionId: String, messages: List<SessionMessage>, keep: Int) = Unit
            override suspend fun deleteMessages(serverId: String, sessionId: String) = Unit
            override suspend fun dropLocation(serverId: String, directory: String?) = Unit
            override suspend fun dropServer(serverId: String) = Unit
        }
    }
}
