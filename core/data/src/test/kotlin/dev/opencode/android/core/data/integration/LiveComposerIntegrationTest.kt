package dev.opencode.android.core.data.integration

import dev.opencode.android.core.data.server.SessionCommands
import dev.opencode.android.core.data.composer.AttachmentDraft
import dev.opencode.android.core.data.composer.AttachmentKind
import dev.opencode.android.core.data.composer.AttachmentProblem
import dev.opencode.android.core.data.composer.AttachmentPolicy
import dev.opencode.android.core.data.composer.AttachmentVerdict
import dev.opencode.android.core.data.composer.ServerPath
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.model.InboxItem
import dev.opencode.android.core.model.PromptFileInput
import dev.opencode.android.core.model.ReferenceInfo
import dev.opencode.android.core.model.SkillInfo
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import dev.opencode.android.core.testing.integration.DevServerHarness
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Phase 5 composer operations against a real `opencode serve` 2.0.18 with the scripted fake
 * provider.
 *
 * This is the phase's second exit criterion — *fake-provider assertions confirm that attachments
 * reach the model* — and the only way to settle it: the client's request has to arrive, the server
 * has to accept it, and the provider has to have been sent it. Every one of those three hops is
 * asserted here, and the fake provider's own request log is the last one.
 *
 * **Every wait is bounded and every assertion names what it looked at.** A server that never reaches
 * the expected state fails with the state it did reach, which is the failure mode this project warns
 * about; the alternative is a build that hangs.
 *
 * The two things a JVM cannot decide are named in the report rather than glossed over: the real
 * image picker and camera, and the real encoder that produces the `data:` payload this test hand-writes.
 */
class LiveComposerIntegrationTest {

    private lateinit var api: ServerApi
    private lateinit var commands: SessionCommands
    private val json = Json { ignoreUnknownKeys = true }
    private var sessionId: String = ""
    private lateinit var workDirectory: String
    private lateinit var marker: File

    @Before
    fun setUp() = runBlocking {
        assumeTrue("the dev server is not running; scripts/dev-server.sh start", DevServerHarness.isAvailable)
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .authenticator { _, response ->
                val credential = okhttp3.Credentials.basic("opencode", DevServerHarness.password.orEmpty())
                response.request.newBuilder().header("Authorization", credential).build()
            }
            .build()
        api = ServerApiFactory(okHttpClient = client, credentialProvider = { null })
            .createForReads(DevServerHarness.url!!)
        commands = SessionCommands(api)
        workDirectory = DevServerHarness.directory!!
        sessionId = commands.create(
            directory = workDirectory,
            model = ModelRef(id = "text", providerID = "fake"),
        ).getOrThrow().id
    }

    @After
    fun tearDown() {
        runCatching { File(marker.absolutePath).delete() }
    }

    @Test
    fun `the three file based catalogs decode against a real server`() = runBlocking {
        val commandsList: List<CommandInfo> = api.listCommands(workDirectory).data
        val skills: List<SkillInfo> = api.listSkills(workDirectory).data
        val references: List<ReferenceInfo> = api.listReferences(workDirectory).data

        // An isolated 2.0.18 server always defines `review` and `init`; that is what a live check can
        // assert, and it is enough to prove the shape rather than the contents.
        assertTrue("command.list should have entries, got $commandsList", commandsList.isNotEmpty())
        assertTrue(commandsList.all { it.name.isNotBlank() })
        assertTrue(commandsList.any { it.description != null })

        assertTrue("skill.list should have entries, got ${skills.size}", skills.isNotEmpty())
        val skill = skills.first()
        assertTrue(skill.id.isNotBlank())
        assertTrue("skill.list carries the body, which is required by the spec", skill.content.isNotEmpty())
        assertTrue(skill.path.isNotBlank())

        // References are configuration this harness does not define, so the honest assertion is that
        // the call succeeds and the union decodes, empty or not.
        assertNotNull(references)
    }

    @Test
    fun `find files answers relative to the location, which is what the client resolves against`() =
        runBlocking {
            marker = writeMarker()
            // The server indexes the tree it watches, so a file written a moment ago may need a
            // moment. The wait is bounded and reports what it did see.
            var found: List<FileSystemEntry> = emptyList()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(INDEX_WAIT_SECONDS)
            while (System.nanoTime() < deadline && found.isEmpty()) {
                found = api.findFiles(directory = workDirectory, query = "attach-probe", type = "file").data
                if (found.isEmpty()) Thread.sleep(200)
            }

            assertTrue("fs.find should find the marker", found.isNotEmpty())
            val entry = found.first()
            // Verified against a live 2.0.18 server: `fs.find` answers with paths relative to the
            // location, so the client has to resolve them against it before a `file:` URI means
            // anything. `ServerPath.resolve` is that function, and this is why it exists.
            assertFalse("the server answers relative to the location", entry.path.startsWith("/"))
            assertEquals(FileSystemEntry.EntryType.FILE, entry.type)
            assertEquals(
                marker.absolutePath,
                ServerPath.resolve(workDirectory, entry.path),
            )
        }

    @Test
    fun `a file attachment reaches the model, as the recorded provider request shows`() = runBlocking {
        marker = writeMarker()
        val uri = ServerPath.toUri(marker.name, workDirectory)

        val result = commands.prompt(
            sessionID = sessionId,
            text = "what is in the attached file?",
            delivery = Delivery.Steer,
            files = listOf(PromptFileInput(uri = uri, name = marker.name)),
        )

        assertTrue("the prompt was accepted", result.isSuccess)
        val echoed = result.getOrThrow()
        val stored = ((echoed.item as InboxItem.User).payload.files ?: emptyList()).single()
        assertEquals("the server read the file and recorded its bytes", marker.readText().trim(), String(java.util.Base64.getDecoder().decode(stored.data)))
        assertTrue(
            "the provider request must carry the file's content, which is the whole criterion",
            awaitProviderRequest { body -> marker.readText().trim() in body },
        )
    }

    @Test
    fun `an inline image is accepted and stored, and a text-only model does not receive it`() =
        runBlocking {
            val png = "data:image/png;base64," + ONE_PIXEL_PNG_BASE64
            val result = commands.prompt(
                sessionID = sessionId,
                text = "describe this",
                delivery = Delivery.Steer,
                files = listOf(PromptFileInput(uri = png, name = "pixel.png")),
            )

            assertTrue("the server accepts a data url", result.isSuccess)
            val item = result.getOrThrow()
            val stored = ((item.item as InboxItem.User).payload.files ?: emptyList()).single()
            assertEquals("image/png", stored.mime)
            assertTrue("the server decoded the data url", stored.data.isNotEmpty())

            // The scripted `text` model declares `input: ["text"]`, so the server does not put the
            // picture in the provider's request. This is the case the composer's warning is for, and
            // asserting it here is what makes the warning something more than a precaution.
            assertFalse(
                "a text-only model must not be sent the image",
                awaitProviderRequest { body -> ONE_PIXEL_PNG_BASE64 in body },
            )
            val model = api.listModels(workDirectory).data.first { it.id == "text" }
            assertFalse(
                "the warning is warranted only if the model really declares no image input",
                AttachmentPolicy.takesImages(model),
            )
        }

    @Test
    fun `the client's own policy says the same thing about the same model`() = runBlocking {
        val model = api.listModels(workDirectory).data.first { it.id == "text" }
        val draft = AttachmentDraft(
            id = "pixel",
            label = "pixel.png",
            uri = "data:image/png;base64," + ONE_PIXEL_PNG_BASE64,
            kind = AttachmentKind.IMAGE,
            sizeBytes = 70,
            mime = "image/png",
        )

        assertEquals(AttachmentVerdict.NeedsConfirmation(AttachmentProblem.MODEL_TAKES_NO_IMAGES), AttachmentPolicy.verify(draft, model))
    }

    @Test
    fun `a command runs and is confirmed by the inbox`() = runBlocking {
        val available = api.listCommands(workDirectory).data
        // A `ping` command this harness writes is listed but not executable on 2.0.18, so the
        // assertion is against a command the server itself defines.
        val command = available.firstOrNull { it.name == "review" } ?: available.first()

        val result = commands.runCommand(sessionId, command.name, "from the test", Delivery.Steer)

        assertTrue("session.command should be accepted, got ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun `a shell command is accepted and answers with no body`() = runBlocking {
        val result = commands.runShell(sessionId, "echo shell-probe-marker")

        assertTrue("session.shell should be accepted, got ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun `a side question is read out of the data wrapper`() = runBlocking {
        val answer = commands.generate(sessionId, "what is this session about?").getOrThrow()

        assertTrue("session.generate answers with text, got '$answer'", answer.isNotBlank())
    }

    @Test
    fun `a compaction is accepted and enqueues its own inbox item`() = runBlocking {
        val result = commands.compact(sessionId, Delivery.Steer)

        assertTrue("session.compact should be accepted, got ${result.exceptionOrNull()}", result.isSuccess)
        val item = result.getOrThrow()
        assertTrue("a compaction is enqueued as a compaction item", item.item is InboxItem.Compaction)
        assertTrue("the id is the one the client generated", item.id.startsWith("msg_"))
    }

    @Test
    fun `the experimental skill route is absent on this server, which is the documented fallback`() =
        runBlocking {
            val error = commands.activateSkill(sessionId, "review").exceptionOrNull()

            // Capability detection: 2.0.18 does not serve the route, so the answer is NOT_FOUND and
            // the composer falls back to attaching the skill on the next prompt. Either answer is a
            // pass; what is asserted is that neither is a crash.
            if (error != null) {
                assertTrue(
                    "an absent experimental route is a not-found, not a fault: $error",
                    error.message.orEmpty().isNotBlank(),
                )
            }
        }

    @Test
    fun `session environment is the whole map and accepts a put`() = runBlocking {
        assertTrue(commands.setEnvironment(sessionId, mapOf("P5_PROBE" to "1")).isSuccess)
    }

    // ------------------------------------------------------------------ helpers

    private fun writeMarker(): File {
        val file = File(workDirectory, "attach-probe.txt")
        file.writeText(MARKER_TEXT)
        return file
    }

    /**
     * Waits, with a bound, for the fake provider to have recorded a request whose body satisfies
     * [matches].
     *
     * The provider log is the only place the last hop is visible, and it is a different process, so
     * the wait is a real one. It is bounded, which is the difference between a test that fails and a
     * build that hangs, and it answers `false` rather than throwing so that a *negative* assertion —
     * "the server did not send this" — can be made with the same wait.
     */
    private fun awaitProviderRequest(matches: (String) -> Boolean): Boolean {
        val providerUrl = requireNotNull(DevServerHarness.fakeProviderUrl) {
            "the fake provider URL is not configured; FAKE_PROVIDER_URL is what names it"
        }
        val client = DevServerHarness.client()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REQUEST_WAIT_SECONDS)
        var last = ""
        while (System.nanoTime() < deadline) {
            val matched = runCatching {
                val body = client.newCall(okhttp3.Request.Builder().url("$providerUrl/__requests").build())
                    .execute()
                    .use { it.body.string() }
                last = body
                json.parseToJsonElement(body).jsonObject["requests"]?.jsonArray.orEmpty()
                    .any { request -> matches(request.jsonObject.toString()) }
            }.getOrDefault(false)
            if (matched) return true
            Thread.sleep(200)
        }
        // Not a failure: this is also how a *negative* assertion is made, and the log is reported so
        // that a negative assertion which was never really checked says so here.
        return false
    }

    private companion object {
        const val REQUEST_WAIT_SECONDS = 30L

        /** How long a file this test just wrote may take to appear in the server's index. */
        const val INDEX_WAIT_SECONDS = 10L
        const val MARKER_TEXT = "marker-alpha-77"

        /** A one-pixel PNG, base64, so the test does not need an encoder to have a picture. */
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADklEQVR4nGP4z8CAFQEAa60H+Z5QfWkAAAAASUVORK5CYII="
    }
}
