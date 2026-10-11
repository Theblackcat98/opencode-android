package dev.opencode.android.feature.sessions.ui.timeline

import dev.opencode.android.core.model.AssistantContent
import dev.opencode.android.core.model.StructuredError
import dev.opencode.android.core.model.ToolState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tool calls a transcript folds into one "Ran N tools" row.
 *
 * Folding is what keeps a long turn readable, and the one thing it must never do is hide a failure: a failed call's
 * reason has to be on screen without opening anything.
 */
class AssistantBlocksTest {

    @Test
    fun consecutiveCleanCallsFoldIntoOneGroup() {
        val blocks = assistantBlocks(listOf(done("a"), done("b"), done("c")))

        assertEquals(1, blocks.size)
        assertEquals(listOf("a", "b", "c"), (blocks.single() as AssistantBlock.Tools).cards.map { it.id })
    }

    @Test
    fun aFailedCallIsNeverFoldedAndSplitsTheRun() {
        val blocks = assistantBlocks(listOf(done("a"), done("b"), failed("x"), done("c"), done("d")))

        assertEquals(3, blocks.size)
        assertEquals(listOf("a", "b"), (blocks[0] as AssistantBlock.Tools).cards.map { it.id })
        val alone = blocks[1] as AssistantBlock.Part
        assertEquals("x", alone.card?.id)
        assertTrue(alone.card?.status is ToolStatus.Failed)
        assertEquals(listOf("c", "d"), (blocks[2] as AssistantBlock.Tools).cards.map { it.id })
        assertTrue(
            "no group carries a failure",
            blocks.filterIsInstance<AssistantBlock.Tools>().flatMap { it.cards }.none { it.status is ToolStatus.Failed },
        )
    }

    @Test
    fun aRunningCallAndAToDoListStayOnTheirOwn() {
        val blocks = assistantBlocks(listOf(done("a"), running("r"), todo("t"), done("b")))

        assertEquals(listOf("a", "r", "t", "b"), blocks.map { (it as AssistantBlock.Part).card?.id })
    }

    @Test
    fun textKeepsTheOrderOfTheMessage() {
        val blocks = assistantBlocks(listOf(done("a"), done("b"), AssistantContent.Text("between"), done("c")))

        assertTrue(blocks[0] is AssistantBlock.Tools)
        assertEquals("between", ((blocks[1] as AssistantBlock.Part).part as AssistantContent.Text).text)
        assertEquals("c", (blocks[2] as AssistantBlock.Part).card?.id)
    }

    @Test
    fun aToDoListIsReadFromItsArguments() {
        val card = todo("t").toCard()

        assertEquals(ToolCardKind.TODO, card.kind)
        assertEquals(listOf("Write the test", "Make it pass"), card.todos?.map { it.content })
        assertEquals(listOf(true, false), card.todos?.map { it.done })
        assertTrue(card.todos!![1].active)
    }

    private fun done(id: String) = tool(id, "read", ToolState.Completed(input = path(), content = emptyList()))

    private fun failed(id: String) =
        tool(id, "edit", ToolState.Error(path(), StructuredError("tool.execution", "rejected"), null))

    private fun running(id: String) = tool(
        id,
        "bash",
        ToolState.Running(input = mapOf("command" to JsonPrimitive("ls")), metadata = emptyMap()),
    )

    private fun todo(id: String) = tool(
        id,
        "todowrite",
        ToolState.Completed(
            input = mapOf(
                "todos" to JsonArray(
                    listOf(
                        JsonObject(mapOf("content" to JsonPrimitive("Write the test"), "status" to JsonPrimitive("completed"))),
                        JsonObject(mapOf("content" to JsonPrimitive("Make it pass"), "status" to JsonPrimitive("in_progress"))),
                    ),
                ),
            ),
            content = emptyList(),
        ),
    )

    private fun path(): Map<String, JsonElement> = mapOf("filePath" to JsonPrimitive("src/Foo.kt"))

    private fun tool(id: String, name: String, state: ToolState) = AssistantContent.Tool(
        id = id,
        name = name,
        state = state,
        time = AssistantContent.Tool.Time(created = 1_000, ran = 1_010, completed = 1_130),
    )
}
