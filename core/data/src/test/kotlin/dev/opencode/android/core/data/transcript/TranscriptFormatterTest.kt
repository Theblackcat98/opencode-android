package dev.opencode.android.core.data.transcript

import dev.opencode.android.core.model.AssistantContent
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.Outcome
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.ShellOutput
import dev.opencode.android.core.model.ShellStatus
import dev.opencode.android.core.model.StructuredError
import dev.opencode.android.core.model.ToolState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "copy" puts on the clipboard.
 *
 * The output goes to a chat app or a terminal, so it has to be plain text and it has to name what it
 * copied. A message type this client does not recognise is labelled rather than dropped: a transcript
 * that silently loses messages is worse than one that says what it could not read.
 */
class TranscriptFormatterTest {

    @Test
    fun `a user message keeps its text and names its attachments`() {
        val text = TranscriptFormatter.message(
            SessionMessage.User(
                id = "msg_1",
                time = SessionMessage.CreatedTime(1),
                text = "do the thing",
                files = listOf(
                    dev.opencode.android.core.model.PromptFileAttachment(
                        data = "x",
                        mime = "image/png",
                        source = dev.opencode.android.core.model.PromptFileSource.Inline,
                        name = "shot.png",
                    ),
                ),
            ),
        )
        assertTrue(text.contains("## You"))
        assertTrue(text.contains("do the thing"))
        assertTrue(text.contains("shot.png"))
    }

    @Test
    fun `an assistant message shows its text, its reasoning and its tools`() {
        val text = TranscriptFormatter.message(
            assistant(
                content = listOf(
                    AssistantContent.Reasoning("thinking"),
                    AssistantContent.Text("the answer"),
                    AssistantContent.Tool(
                        id = "tool_1",
                        name = "shell",
                        state = ToolState.Completed(
                            input = mapOf("command" to JsonPrimitive("ls -la")),
                            content = listOf(dev.opencode.android.core.model.ToolContent.Text("total 0")),
                        ),
                        time = AssistantContent.Tool.Time(created = 1),
                    ),
                ),
            ),
        )
        assertTrue(text.contains("> thinking"))
        assertTrue(text.contains("the answer"))
        assertTrue(text.contains("**shell** `ls -la`"))
        assertTrue("the output is indented", text.contains("    total 0"))
    }

    @Test
    fun `a tool's most identifying argument is the one that is shown`() {
        val read = TranscriptFormatter.message(
            assistant(
                content = listOf(
                    AssistantContent.Tool(
                        id = "tool_1",
                        name = "read",
                        state = ToolState.Completed(
                            input = mapOf(
                                "filePath" to JsonPrimitive("src/a.ts"),
                                "limit" to JsonPrimitive("20"),
                            ),
                            content = emptyList(),
                        ),
                        time = AssistantContent.Tool.Time(created = 1),
                    ),
                ),
            ),
        )
        assertTrue(read.contains("src/a.ts"))
        assertTrue("an argument is not the subject", !read.contains("`20`"))
    }

    @Test
    fun `a failed tool shows its error`() {
        val text = TranscriptFormatter.message(
            assistant(
                content = listOf(
                    AssistantContent.Tool(
                        id = "tool_1",
                        name = "edit",
                        state = ToolState.Error(
                            input = mapOf("filePath" to JsonPrimitive("a.ts")),
                            error = StructuredError(type = "tool", message = "no such file"),
                        ),
                        time = AssistantContent.Tool.Time(created = 1),
                    ),
                ),
                error = null,
            ),
        )
        assertTrue(text.contains("no such file"))
    }

    @Test
    fun `a turn divider names the outcome`() {
        assertTrue(
            TranscriptFormatter.message(
                SessionMessage.Idle(
                    id = "msg_i",
                    time = SessionMessage.CreatedTime(1),
                    outcome = Outcome.Succeeded,
                ),
            ).contains("succeeded"),
        )
    }

    @Test
    fun `a shell message keeps its command and its output`() {
        val text = TranscriptFormatter.message(
            SessionMessage.Shell(
                id = "msg_s",
                time = SessionMessage.Shell.Time(created = 1),
                shellID = "sh_1",
                command = "echo hi",
                status = ShellStatus.Exited,
                output = ShellOutput(output = "hi", cursor = 2, size = 2, truncated = false),
            ),
        )
        assertTrue(text.contains("\$ echo hi"))
        assertTrue(text.contains("    hi"))
    }

    @Test
    fun `a message type this client does not know is named, not dropped`() {
        val text = TranscriptFormatter.message(
            SessionMessage.Unknown("telemetry", JsonObject(mapOf("type" to JsonPrimitive("telemetry")))),
        )
        assertTrue(text.contains("telemetry"))
    }

    @Test
    fun `the whole conversation keeps the order and separates messages`() {
        val transcript = TranscriptFormatter.transcript(
            messages = listOf(
                user("msg_1", "first"),
                assistantMessage(AssistantContent.Text("second")),
                SessionMessage.Idle(id = "msg_3", time = SessionMessage.CreatedTime(3), outcome = Outcome.Succeeded),
            ),
            title = "My session",
        )
        val first = transcript.indexOf("first")
        val second = transcript.indexOf("second")
        assertTrue(transcript.startsWith("# My session"))
        assertTrue(first in 0 until second)
        assertTrue(transcript.endsWith("\n"))
    }

    @Test
    fun `an empty conversation still ends with a newline rather than nothing`() {
        assertEquals("\n", TranscriptFormatter.transcript(emptyList()))
    }

    private fun user(id: String, text: String) =
        SessionMessage.User(id = id, time = SessionMessage.CreatedTime(1), text = text)

    private fun assistantMessage(vararg content: AssistantContent) =
        assistant(content.toList())

    private fun assistant(
        content: List<AssistantContent>,
        error: StructuredError? = null,
    ) = SessionMessage.Assistant(
        id = "msg_a",
        time = SessionMessage.Assistant.Time(created = 1),
        agent = "build",
        model = ModelRef("text", "fake"),
        content = content,
        error = error,
    )
}
