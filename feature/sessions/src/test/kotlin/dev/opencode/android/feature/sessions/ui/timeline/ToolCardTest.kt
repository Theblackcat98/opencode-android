package dev.opencode.android.feature.sessions.ui.timeline

import dev.opencode.android.core.model.AssistantContent
import dev.opencode.android.core.model.StructuredError
import dev.opencode.android.core.model.ToolContent
import dev.opencode.android.core.model.ToolState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool card mapping: a tool name to a renderer, and a tool's arguments to the one line and the
 * detail the card shows.
 *
 * This is the part that decides what a transcript says about a tool call, and it is a pure function
 * of the model, so it is tested directly rather than through a screenshot.
 */
class ToolCardTest {

    @Test
    fun `every tool the plan names has a dedicated card`() {
        val named = listOf(
            "read", "glob", "grep", "edit", "write", "patch",
            "bash", "webfetch", "websearch", "skill", "task", "question", "execute",
        )
        for (name in named) {
            assertTrue("$name should have its own card", ToolCardKind.isKnown(name))
        }
        assertEquals(ToolCardKind.SHELL, ToolCardKind.of("bash"))
        assertEquals(ToolCardKind.SUBAGENT, ToolCardKind.of("task"))
    }

    @Test
    fun `a tool this client does not know falls back to a generic card`() {
        assertEquals(ToolCardKind.GENERIC, ToolCardKind.of("quantum_anneal"))
        assertFalse(ToolCardKind.isKnown("quantum_anneal"))
        val card = tool(name = "quantum_anneal", input = mapOf("input" to "qubits=8")).toCard()
        assertTrue(card.unknown)
        assertEquals("qubits=8", card.subject)
    }

    @Test
    fun `the file tools show their path and the search tools their pattern`() {
        assertEquals("src/Foo.kt", tool("read", mapOf("filePath" to "src/Foo.kt")).toCard().subject)
        assertEquals("src/Foo.kt", tool("write", mapOf("path" to "src/Foo.kt")).toCard().subject)
        assertEquals("**/*.kt", tool("glob", mapOf("pattern" to "**/*.kt")).toCard().subject)
        assertEquals("needle", tool("grep", mapOf("pattern" to "needle")).toCard().subject)
        assertEquals("https://x.dev", tool("webfetch", mapOf("url" to "https://x.dev")).toCard().subject)
        assertEquals("needle", tool("websearch", mapOf("query" to "needle")).toCard().subject)
        assertEquals("ls -la", tool("bash", mapOf("command" to "ls -la")).toCard().subject)
    }

    @Test
    fun `the status follows the tool state`() {
        assertEquals(ToolStatus.Streaming, tool("read", emptyMap(), state = ToolState.Streaming("{")).toCard().status)
        assertEquals(
            ToolStatus.Running,
            tool(
                "read",
                mapOf("filePath" to "a"),
                state = ToolState.Running(json(mapOf("filePath" to "a")), emptyMap()),
            )
                .toCard().status,
        )
        assertTrue(tool("read", mapOf("filePath" to "a"), output = "body").toCard().status is ToolStatus.Completed)
        val failed = tool(
            "read",
            mapOf("filePath" to "a"),
            state = ToolState.Error(json(mapOf("filePath" to "a")), StructuredError("io", "no such file")),
        ).toCard()
        assertEquals(StructuredError("io", "no such file"), (failed.status as ToolStatus.Failed).error)
    }

    @Test
    fun `a completed tool shows its text output and its duration`() {
        val card = tool("read", mapOf("filePath" to "a"), output = "the body").toCard()
        assertEquals("the body", card.detail)
        assertEquals(120L, card.durationMillis)
    }

    @Test
    fun `a subagent shows the child session and a question shows the answer`() {
        val subagent = tool(
            "task",
            mapOf("description" to "read the guide"),
            state = ToolState.Completed(
                input = json(mapOf("description" to "read the guide")),
                content = emptyList(),
                metadata = json(mapOf("sessionID" to "ses_child")),
            ),
        ).toCard()
        assertEquals("ses_child", subagent.detail)

        val question = tool("question", mapOf("question" to "which database?")).toCard()
        assertEquals("which database?", question.subject)
    }

    @Test
    fun `a failed shell call carries the reason and keeps its output`() {
        val card = failed(
            "bash",
            mapOf("command" to "cat missing.txt"),
            message = "Command exited with code 1",
            content = listOf(ToolContent.Text("cat: missing.txt: No such file or directory")),
        )
        assertEquals("Command exited with code 1", card.failure)
        assertEquals("Command exited with code 1", (card.status as ToolStatus.Failed).error.message)
        // The output is a separate thing the card already drew: the reason is added to it, not swapped for it.
        assertEquals("cat: missing.txt: No such file or directory", card.detail)
        assertEquals("cat missing.txt", card.subject)
    }

    @Test
    fun `a failed edit carries the reason and still has the diff of what it attempted`() {
        val message = "Invalid arguments for tool \"edit\":\n- path: Missing key\n\n" +
            "Update the arguments and call the tool again."
        val card = failed(
            "edit",
            mapOf("oldString" to "val limit = 10", "newString" to "val limit = 25"),
            message = message,
        )
        // Multi-line, and not trimmed or reflowed: the card draws exactly what the server recorded.
        assertEquals(message, card.failure)
        val diff = card.diff
        assertEquals(listOf('-', '+'), diff?.rows?.map { it.marker })
    }

    @Test
    fun `a failed generic call carries the reason once`() {
        val card = failed("quantum_anneal", mapOf("input" to "qubits=8"), message = "decoherence")
        assertEquals(ToolCardKind.GENERIC, card.kind)
        assertEquals("decoherence", card.failure)
        // It used to be the detail, which drew it as unlabelled "Output"; now it is only the reason.
        assertNull(card.detail)
    }

    @Test
    fun `every kind carries the reason of a failure`() {
        val names = listOf(
            "read", "glob", "grep", "edit", "write", "patch",
            "bash", "webfetch", "websearch", "skill", "task", "question", "execute", "quantum_anneal",
        )
        val kinds = names.map { ToolCardKind.of(it) }.toSet()
        assertEquals("the list should cover every kind", ToolCardKind.entries.toSet(), kinds)
        for (name in names) {
            assertEquals("$name should say why it failed", "boom", failed(name, emptyMap(), message = "boom").failure)
        }
    }

    @Test
    fun `a call that did not fail has no reason, and a blank message is not one`() {
        assertNull(tool("read", mapOf("filePath" to "a"), output = "body").toCard().failure)
        assertNull(tool("read", emptyMap(), state = ToolState.Streaming("{")).toCard().failure)
        assertNull(failed("read", emptyMap(), message = "  \n ").failure)
    }

    @Test
    fun `a screen reader is given the reason on one line and not all of a long one`() {
        assertEquals(
            "Invalid arguments: - path: Missing key Arguments provided: {}",
            spokenFailure("Invalid arguments:\n- path: Missing key\n\nArguments provided:\n{}\n"),
        )
        val long = "x".repeat(SPOKEN_FAILURE_LIMIT * 3)
        assertEquals(SPOKEN_FAILURE_LIMIT, spokenFailure(long).length)
    }

    private fun failed(
        name: String,
        input: Map<String, String>,
        message: String,
        content: List<ToolContent>? = null,
    ): ToolCard = tool(
        name,
        input,
        state = ToolState.Error(json(input), StructuredError("tool.execution", message), content),
    ).toCard()

    private fun tool(
        name: String,
        input: Map<String, String>,
        output: String? = null,
        state: ToolState = ToolState.Completed(
            input = json(input),
            content = listOfNotNull(output?.let { ToolContent.Text(it) }),
        ),
    ) = AssistantContent.Tool(
        id = "tool_$name",
        name = name,
        state = state,
        time = AssistantContent.Tool.Time(created = 1_000, ran = 1_010, completed = 1_130),
    )

    private fun json(values: Map<String, String>): Map<String, JsonElement> =
        values.mapValues { (_, value) -> JsonPrimitive(value) }
}
