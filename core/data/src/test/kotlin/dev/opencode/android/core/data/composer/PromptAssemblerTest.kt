package dev.opencode.android.core.data.composer

import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.PromptSkillInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one send actually does.
 *
 * Every rule here is a rule a user can be surprised by — a `/` that turns out to be a command, a
 * picture the model cannot see, a mention that got attached twice — so each is pinned as a
 * property of the assembled request rather than of the text that produced it.
 */
class PromptAssemblerTest {

    private val agents = listOf(AgentInfo(id = "build", name = "Build", mode = AgentInfo.AgentMode.PRIMARY))
    private val serverCommands = listOf(CommandInfo("deploy", "Ship it"), CommandInfo("compact", "The project's own"))

    @Test
    fun `plain text becomes a prompt`() {
        val assembly = PromptAssembler.assemble(ComposerInput(text = "hello"))
        val request = (assembly as Assembly.Prompt).request
        assertEquals("hello", request.text)
        assertEquals(Delivery.Steer, request.delivery)
        assertNull(request.resume)
        assertNull(request.files)
    }

    @Test
    fun `an empty composer sends nothing`() {
        assertEquals(Assembly.Empty, PromptAssembler.assemble(ComposerInput()))
    }

    @Test
    fun `leading whitespace does not stop a bang from being a shell line`() {
        val assembly = PromptAssembler.assemble(ComposerInput(text = "  !git status"))
        assertEquals("git status", (assembly as Assembly.Shell).request.command)
    }

    @Test
    fun `a leading bang anywhere else is a message`() {
        val assembly = PromptAssembler.assemble(ComposerInput(text = "what does ! mean"))
        assertTrue(assembly is Assembly.Prompt)
    }

    @Test
    fun `a server command becomes a command request with its arguments`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(text = "/deploy production now", serverCommands = serverCommands),
        )
        val request = (assembly as Assembly.Command).request
        assertEquals("deploy", request.name)
        assertEquals("production now", request.text)
        assertEquals(Delivery.Steer, request.delivery)
    }

    @Test
    fun `a server command beats a client command of the same name`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(text = "/compact", serverCommands = serverCommands),
        )
        assertEquals("compact", (assembly as Assembly.Command).request.name)
    }

    @Test
    fun `a client command becomes an action and sends nothing`() {
        assertEquals(
            Assembly.Client(ClientAction.NEW_SESSION, ""),
            PromptAssembler.assemble(ComposerInput(text = "/new")),
        )
    }

    @Test
    fun `a client command that takes arguments sends them`() {
        assertEquals(
            Assembly.Client(ClientAction.SIDE_QUESTION, "what does this do?"),
            PromptAssembler.assemble(ComposerInput(text = "/btw what does this do?")),
        )
    }

    @Test
    fun `a client command with no arguments yet sends nothing`() {
        assertEquals(Assembly.Empty, PromptAssembler.assemble(ComposerInput(text = "/btw ")))
    }

    @Test
    fun `a slash the catalogs do not know is sent as the message the user typed`() {
        // A `command.list` that has not loaded must not be able to swallow a sentence.
        val assembly = PromptAssembler.assemble(ComposerInput(text = "/nope hello", serverCommands = serverCommands))
        assertEquals("/nope hello", (assembly as Assembly.Prompt).request.text)
    }

    @Test
    fun `a mention of an agent becomes an agent attachment, not a file`() {
        val request = (PromptAssembler.assemble(ComposerInput(text = "ask @build about it", agents = agents)) as Assembly.Prompt)
            .request
        assertEquals(listOf("build"), request.agents?.map { it.name })
        assertNull(request.files)
        assertEquals("@build", request.agents?.single()?.mention?.text)
    }

    @Test
    fun `a mention of a path becomes a file uri`() {
        val request = (PromptAssembler.assemble(ComposerInput(text = "read @a.ts", location = "/work")) as Assembly.Prompt)
            .request
        assertEquals(listOf("file:///work/a.ts"), request.files?.map { it.uri })
        assertNull(request.agents)
    }

    @Test
    fun `a mention of a path with a range becomes a ranged uri`() {
        val request = (PromptAssembler.assemble(ComposerInput(text = "read @a.ts#20-45", location = "/work")) as Assembly.Prompt)
            .request
        assertEquals(listOf("file:///work/a.ts?start=20&end=45"), request.files?.map { it.uri })
    }

    @Test
    fun `a file attached by hand and named by a mention is attached once`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(
                text = "read @a.ts",
                location = "/work",
                attachments = listOf(serverFile("/work/a.ts", "a.ts")),
            ),
        )
        val request = (assembly as Assembly.Prompt).request
        assertEquals(listOf("file:///work/a.ts"), request.files?.map { it.uri })
        // The mention is what the server ties the attachment to, so it survives the deduplication.
        assertEquals("@a.ts", request.files?.single()?.mention?.text)
    }

    @Test
    fun `two different mentions are two attachments`() {
        val request = (PromptAssembler.assemble(ComposerInput(text = "@a.ts and @b.ts", location = "/work")) as Assembly.Prompt)
            .request
        assertEquals(
            listOf("file:///work/a.ts", "file:///work/b.ts"),
            request.files?.map { it.uri },
        )
    }

    @Test
    fun `skills are attached as the api takes them`() {
        val request = (PromptAssembler.assemble(
            ComposerInput(text = "go", skills = listOf(PromptSkillInput("review"))),
        ) as Assembly.Prompt).request
        assertEquals(listOf("review"), request.skills?.map { it.id })
    }

    @Test
    fun `skills travel on a command too`() {
        val request = (PromptAssembler.assemble(
            ComposerInput(
                text = "/deploy now",
                serverCommands = serverCommands,
                skills = listOf(PromptSkillInput("review")),
            ),
        ) as Assembly.Command).request
        assertEquals(listOf("review"), request.skills?.map { it.id })
    }

    @Test
    fun `queued delivery is sent as queue`() {
        val request = (PromptAssembler.assemble(
            ComposerInput(text = "later", delivery = Delivery.Queue),
        ) as Assembly.Prompt).request
        assertEquals(Delivery.Queue, request.delivery)
    }

    @Test
    fun `resume is only sent for steering input`() {
        val steered = (PromptAssembler.assemble(ComposerInput(text = "x", resume = true)) as Assembly.Prompt).request
        assertEquals(true, steered.resume)

        val queued = (PromptAssembler.assemble(
            ComposerInput(text = "x", resume = true, delivery = Delivery.Queue),
        ) as Assembly.Prompt).request
        assertNull("a queued prompt waits anyway; resume would contradict it", queued.resume)
    }

    @Test
    fun `an attachment with no model needs no confirmation`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(text = "look", attachments = listOf(imageAttachment(1_000))),
        )
        assertTrue(assembly is Assembly.Prompt)
    }

    @Test
    fun `a picture a model cannot take needs the user to agree first`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(
                text = "look",
                attachments = listOf(imageAttachment(1_000)),
                model = model(images = false),
            ),
        )
        assertEquals(Assembly.Refused(PromptProblem.ATTACHMENT_NEEDS_CONFIRMATION), assembly)
    }

    @Test
    fun `the confirmed send carries the picture`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(
                text = "look",
                attachments = listOf(imageAttachment(1_000)),
                model = model(images = false),
            ),
            confirmed = true,
        )
        val request = (assembly as Assembly.Prompt).request
        assertEquals(listOf("data:image/png;base64,AAAA"), request.files?.map { it.uri })
    }

    @Test
    fun `a picture is refused for a model that takes images only when it does not`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(text = "look", attachments = listOf(imageAttachment(1)), model = model(images = true)),
        )
        assertTrue(assembly is Assembly.Prompt)
    }

    @Test
    fun `a format the model is never sent is blocked, not confirmed`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(
                text = "read",
                attachments = listOf(
                    AttachmentDraft("1", "spec.pdf", "file:///work/spec.pdf", AttachmentKind.BINARY, mime = "application/pdf"),
                ),
                model = model(images = true),
            ),
            confirmed = true,
        )
        assertEquals(Assembly.Refused(PromptProblem.ATTACHMENT_BLOCKED), assembly)
    }

    @Test
    fun `an oversized attachment is blocked`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(
                text = "look",
                attachments = listOf(imageAttachment(AttachmentPolicy.MAX_DECODED_BYTES + 1)),
            ),
        )
        assertEquals(Assembly.Refused(PromptProblem.ATTACHMENT_BLOCKED), assembly)
    }

    @Test
    fun `an attachment at exactly the limit is allowed`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(text = "look", attachments = listOf(imageAttachment(AttachmentPolicy.MAX_DECODED_BYTES))),
        )
        assertTrue(assembly is Assembly.Prompt)
    }

    @Test
    fun `a directory is attached and is always allowed`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(
                text = "look",
                attachments = listOf(
                    AttachmentDraft("d", "src", "file:///work/src", AttachmentKind.DIRECTORY),
                ),
                model = model(images = false),
            ),
        )
        assertTrue(assembly is Assembly.Prompt)
    }

    @Test
    fun `a shell line refuses an attachment rather than dropping it`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(text = "!ls", attachments = listOf(imageAttachment(1))),
        )
        assertEquals(Assembly.Refused(PromptProblem.ATTACHMENT_BLOCKED), assembly)
    }

    @Test
    fun `a client command that takes no arguments refuses an attachment`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(text = "/new", attachments = listOf(imageAttachment(1))),
        )
        assertEquals(Assembly.Refused(PromptProblem.ATTACHMENT_BLOCKED), assembly)
    }

    @Test
    fun `a client command that takes arguments keeps the attachment question out of it`() {
        // `/btw` answers from the session context and has no place for a file, but it does have text.
        val assembly = PromptAssembler.assemble(
            ComposerInput(text = "/btw what is this", attachments = listOf(imageAttachment(1))),
        )
        assertEquals(Assembly.Client(ClientAction.SIDE_QUESTION, "what is this"), assembly)
    }

    @Test
    fun `an attachment alone is enough to send`() {
        val assembly = PromptAssembler.assemble(
            ComposerInput(text = "", attachments = listOf(serverFile("/work/a.ts", "a.ts"))),
        )
        val request = (assembly as Assembly.Prompt).request
        assertEquals("", request.text)
        assertEquals(listOf("file:///work/a.ts"), request.files?.map { it.uri })
    }

    @Test
    fun `the intent on its own answers the mode question`() {
        assertEquals(PromptIntent.Shell("ls"), PromptAssembler.intentOf("!ls"))
        assertEquals(PromptIntent.Command("deploy", "now"), PromptAssembler.intentOf("/deploy now", serverCommands))
        assertEquals(PromptIntent.Client(ClientAction.MODEL_PICKER, ""), PromptAssembler.intentOf("/models"))
        assertNull(PromptAssembler.intentOf(""))
        assertNull(PromptAssembler.intentOf("!"))
        assertNull(PromptAssembler.intentOf("/"))
        assertNull(PromptAssembler.intentOf("hello"))
    }

    @Test
    fun `the intent of a command with no arguments is empty text, not a missing command`() {
        assertEquals(PromptIntent.Command("deploy", ""), PromptAssembler.intentOf("/deploy", serverCommands))
    }

    @Test
    fun `an agent named by its display name is still an agent`() {
        val request = (PromptAssembler.assemble(ComposerInput(text = "ask @Build", agents = agents)) as Assembly.Prompt)
            .request
        assertEquals(listOf("Build"), request.agents?.map { it.name })
        assertNull(request.files)
    }

    private fun imageAttachment(bytes: Long) = AttachmentDraft(
        id = "img",
        label = "shot.png",
        uri = "data:image/png;base64,AAAA",
        kind = AttachmentKind.IMAGE,
        sizeBytes = bytes,
        mime = "image/png",
    )

    private fun serverFile(path: String, name: String) = AttachmentDraft(
        id = name,
        label = name,
        uri = "file://$path",
        kind = AttachmentKind.TEXT,
    )

    private fun model(images: Boolean) = ModelInfo(
        id = "m",
        modelID = "m",
        providerID = "p",
        name = "M",
        capabilities = ModelInfo.Capabilities(tools = true, input = if (images) listOf("image") else emptyList()),
        limit = ModelInfo.Limit(context = 1000),
    )
}
