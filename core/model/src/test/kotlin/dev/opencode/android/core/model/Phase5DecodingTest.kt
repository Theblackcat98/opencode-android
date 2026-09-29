package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 5 schemas: the three catalogs the composer completes from, and the request shapes the
 * rich text path sends.
 *
 * Two of these decode fixtures recorded from a live 2.0.18 server (started by
 * `scripts/dev-server.sh`), because they are the ones where the spec is thin: `skill.list` requires
 * a body the client has no use for, and `session.generate` answers with a wrapper that is easy to
 * get wrong. The rest are the spec's own shapes.
 */
class Phase5DecodingTest {

    @Test
    fun `a command list decodes, including a nested one and an mcp prompt`() {
        val raw = """
        [
          {"name": "deploy", "description": "Ship it"},
          {"name": "team/review"},
          {"name": "github:pr", "description": "Open a pull request"}
        ]
        """.trimIndent()
        val commands = OpenCodeJson.decodeFromString(ListSerializer(CommandInfo.serializer()), raw)
        assertEquals(3, commands.size)
        assertEquals("deploy", commands[0].name)
        assertEquals("Ship it", commands[0].description)
        assertNull(commands[1].description)
        assertTrue(commands[2].name.contains(':'))
    }

    @Test
    fun `a skill list decodes with the body the server sends`() {
        val raw = """
        [
          {
            "id": "review",
            "name": "Review",
            "description": "Review a diff",
            "autoinvoke": true,
            "path": "/work/.opencode/skills/review.md",
            "content": "# Review\n"
          },
          {"id": "plain", "name": "Plain", "path": "/p.md", "content": ""}
        ]
        """.trimIndent()
        val skills = OpenCodeJson.decodeFromString(ListSerializer(SkillInfo.serializer()), raw)
        assertEquals("review", skills[0].id)
        assertEquals(true, skills[0].autoinvoke)
        assertEquals("# Review\n", skills[0].content)
        assertNull(skills[1].autoinvoke)
    }

    @Test
    fun `a reference list decodes both sources`() {
        val raw = """
        [
          {"name": "docs", "path": "/work/docs", "description": "Project docs",
           "source": {"type": "local", "path": "/work/docs"}},
          {"name": "upstream", "path": "/repos/upstream", "hidden": true,
           "source": {"type": "git", "repository": "https://example.com/u.git", "branch": "main"}},
          {"name": "future", "path": "/x", "source": {"type": "s3", "bucket": "b"}}
        ]
        """.trimIndent()
        val references = OpenCodeJson.decodeFromString(ListSerializer(ReferenceInfo.serializer()), raw)
        assertEquals(ReferenceSource.Local("/work/docs"), references[0].source)
        assertEquals(ReferenceSource.Git("https://example.com/u.git", "main"), references[1].source)
        assertEquals(true, references[1].hidden)
        // An unknown source keeps its raw JSON rather than failing the whole list.
        val unknown = references[2].source as ReferenceSource.Unknown
        assertEquals("s3", unknown.discriminator)
        assertTrue(unknown.raw.keys.contains("bucket"))
    }

    @Test
    fun `a git reference without a branch decodes`() {
        val raw = """{"name":"u","path":"/r","source":{"type":"git","repository":"https://example.com/u.git"}}"""
        val reference = OpenCodeJson.decodeFromString<ReferenceInfo>(raw)
        assertEquals(ReferenceSource.Git("https://example.com/u.git", null), reference.source)
    }

    @Test
    fun `a prompt file attachment is a uri, not the stored base64`() {
        // The two shapes are genuinely different in the spec, and sending the wrong one is a payload
        // the server cannot read.
        val raw =
            """{"uri":"file:///work/src/a.ts?start=20&end=45","name":"a.ts",""" +
                """"mention":{"start":3,"end":13,"text":"@a.ts#20-45"}}"""
        val input = OpenCodeJson.decodeFromString<PromptFileInput>(raw)
        assertEquals("file:///work/src/a.ts?start=20&end=45", input.uri)
        // The range the attachment carries and the offset the mention points at are different
        // numbers: one is a line range on the server, the other a span in the prompt text.
        assertEquals(3, input.mention?.start)
        assertEquals(13, input.mention?.end)
    }

    @Test
    fun `a prompt request carries its attachments, agents, skills, delivery and resume`() {
        val request = PromptRequest(
            id = "msg_1",
            text = "look at this",
            files = listOf(PromptFileInput("data:image/png;base64,AAA", "shot.png")),
            agents = listOf(PromptAgentAttachment("build")),
            skills = listOf(PromptSkillInput("review")),
            delivery = Delivery.Queue,
            resume = false,
        )
        val encoded = OpenCodeJson.encodeToString(PromptRequest.serializer(), request)
        val decoded = OpenCodeJson.decodeFromString<PromptRequest>(encoded)
        assertEquals("msg_1", decoded.id)
        assertEquals("shot.png", decoded.files?.single()?.name)
        assertEquals(Delivery.Queue, decoded.delivery)
        assertEquals(false, decoded.resume)
    }

    @Test
    fun `a command request carries the same attachments a prompt does`() {
        val encoded = OpenCodeJson.encodeToString(
            SessionCommandRequest.serializer(),
            SessionCommandRequest(
                name = "deploy",
                text = "production",
                files = listOf(PromptFileInput("file:///work/a.ts")),
                agents = listOf(PromptAgentAttachment("build")),
                skills = listOf(PromptSkillInput("review")),
                delivery = Delivery.Steer,
            ),
        )
        val decoded = OpenCodeJson.decodeFromString<SessionCommandRequest>(encoded)
        assertEquals("deploy", decoded.name)
        assertEquals("production", decoded.text)
        assertEquals("build", decoded.agents?.single()?.name)
    }

    @Test
    fun `a shell request carries the client id that makes a retry the same command`() {
        val encoded = OpenCodeJson.encodeToString(
            SessionShellRequest.serializer(),
            SessionShellRequest(id = "msg_9", command = "git status"),
        )
        val decoded = OpenCodeJson.decodeFromString<SessionShellRequest>(encoded)
        assertEquals("msg_9", decoded.id)
        assertEquals("git status", decoded.command)
    }

    @Test
    fun `a side question answer is wrapped in data`() {
        // The route answers `{data: {text}}`; decoding `{text}` here would fail on every `/btw`.
        val decoded = OpenCodeJson.decodeFromString<SessionGenerateResponse>("""{"data":{"text":"because"}}""")
        assertEquals("because", decoded.data.text)
    }

    @Test
    fun `a compaction request is an id and a delivery`() {
        val encoded = OpenCodeJson.encodeToString(
            SessionCompactRequest.serializer(),
            SessionCompactRequest(id = "msg_3", delivery = Delivery.Queue),
        )
        assertTrue(encoded.contains("\"msg_3\""))
        assertTrue(encoded.contains("queue"))
    }

    @Test
    fun `session environment is the whole map`() {
        val encoded = OpenCodeJson.encodeToString(
            SessionEnvironmentRequest.serializer(),
            SessionEnvironmentRequest(mapOf("A" to "1", "B" to "")),
        )
        assertEquals("""{"variables":{"A":"1","B":""}}""", encoded)
    }

    @Test
    fun `a skill activation is an id and an optional resume`() {
        val encoded = OpenCodeJson.encodeToString(
            SkillActivationRequest.serializer(),
            SkillActivationRequest(id = "review", resume = true),
        )
        assertEquals("""{"id":"review","resume":true}""", encoded)
    }

    @Test
    fun `a stored file attachment keeps its source as a union`() {
        val raw = """{"data":"AAA","mime":"image/png","source":{"type":"inline"},"name":"s.png"}"""
        val stored = OpenCodeJson.decodeFromString<PromptFileAttachment>(raw)
        assertEquals(PromptFileSource.Inline, stored.source)
        assertEquals("image/png", stored.mime)
    }
}
