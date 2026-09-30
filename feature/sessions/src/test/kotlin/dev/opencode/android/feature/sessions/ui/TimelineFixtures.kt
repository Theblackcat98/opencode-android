package dev.opencode.android.feature.sessions.ui

import dev.opencode.android.core.data.server.PagingState
import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.SessionFilter
import dev.opencode.android.core.data.server.SessionModelChip
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.data.server.TimelinePaging
import dev.opencode.android.core.model.AssistantContent
import dev.opencode.android.core.model.FinishReason
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.Outcome
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.StructuredError
import dev.opencode.android.core.model.TokenUsage
import dev.opencode.android.core.model.ToolContent
import dev.opencode.android.core.model.ToolState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The shapes the screenshots and the renderer tests draw.
 *
 * Every message type in features doc §5 and every compact tool renderer the plan lists is here, so a
 * test can name the case it means instead of assembling a fixture inline. They are built with the
 * same models the server decodes into, which is the point: a renderer test that used a parallel
 * fake shape would pass while the real projection broke.
 */
object TimelineFixtures {

    fun sampleMessages(): List<SessionMessage> = listOf(
        userMessage(),
        markdownAnswer(),
        shellMessage(),
        idleMessage(),
    )

    fun userMessage(): SessionMessage.User = SessionMessage.User(
        id = "msg_user",
        time = SessionMessage.CreatedTime(1_000),
        text = "Read the timeline test and tell me what it covers.",
        files = listOf(
            dev.opencode.android.core.model.PromptFileAttachment(
                data = "aGk=",
                mime = "image/png",
                source = dev.opencode.android.core.model.PromptFileSource.Inline,
                name = "screenshot.png",
            ),
        ),
    )

    fun markdownAnswer(): SessionMessage.Assistant = assistant(
        id = "msg_assistant",
        agent = "build",
        content = listOf(
            AssistantContent.Text(
                """
                ## What the timeline covers

                The golden tests replay a recorded stream, and **every** message type has a renderer:

                - user prompts, with attachment chips
                - assistant answers as Markdown
                - tool calls, one card per tool
                - compaction blocks and idle dividers

                ```kotlin
                val state = TimelineState.of(projected)
                ```

                | Type | Count |
                | --- | ---: |
                | user | 1 |
                | assistant | 3 |

                [the plan](https://opencode.ai) and a stray * marker.
                """.trimIndent(),
            ),
        ),
        finished = true,
    )

    fun reasoningAnswer(): SessionMessage.Assistant = assistant(
        id = "msg_reasoning",
        agent = "build",
        content = listOf(
            AssistantContent.Reasoning(
                text = "The user wants every message type, so the golden fixture has to include " +
                    "the rare ones, not just the common text answer.",
                state = JsonObject(emptyMap()),
                time = AssistantContent.Reasoning.Time(created = 2_000, completed = 4_300),
            ),
            AssistantContent.Text("Checked the fixture and it covers them all."),
        ),
        finished = true,
    )

    fun shellMessage(): SessionMessage.Shell = SessionMessage.Shell(
        id = "msg_shell",
        metadata = mapOf("background" to JsonPrimitive(true)),
        time = SessionMessage.Shell.Time(created = 5_000, completed = 5_200),
        shellID = "sh_1",
        command = "echo fixture-shell",
        status = dev.opencode.android.core.model.ShellStatus.Exited,
        exit = 0.0,
        output = dev.opencode.android.core.model.ShellOutput("fixture-shell\n", 14, 14, false),
    )

    /** One assistant message per compact tool renderer the plan names. */
    fun toolCards(): List<SessionMessage> = listOf(
        tool("read", input = mapOf("filePath" to "src/main/kotlin/Foo.kt"), output = "package dev\n"),
        tool(
            "glob",
            input = mapOf("pattern" to "**/*.kt"),
            output = "src/main/kotlin/Foo.kt\nsrc/test/kotlin/FooTest.kt\n",
        ),
        tool(
            "grep",
            input = mapOf("pattern" to "TimelineReducer"),
            output = "core/data/.../TimelineReducer.kt:41:class TimelineReducer\n",
        ),
        tool(
            "edit",
            input = mapOf(
                "filePath" to "src/main/kotlin/Foo.kt",
                "oldString" to "val limit = 10\nval name = \"foo\"",
                "newString" to "val limit = 25",
            ),
            metadata = mapOf("files" to "src/main/kotlin/Foo.kt"),
        ),
        tool(
            "write",
            input = mapOf(
                "filePath" to "src/main/kotlin/Bar.kt",
                "content" to "package dev\n\nobject Bar {\n    const val ID = 1\n}",
            ),
            metadata = mapOf("files" to "src/main/kotlin/Bar.kt"),
        ),
        tool(
            "patch",
            input = mapOf("patch" to "*** Begin Patch\n*** Update File: Foo.kt"),
            output = "Applied 1 change",
        ),
        tool("bash", input = mapOf("command" to "ls -la"), output = "total 8\ndrwxr-xr-x  build\n"),
        tool("webfetch", input = mapOf("url" to "https://opencode.ai/docs"), output = "# Docs\n"),
        tool("websearch", input = mapOf("query" to "opencode android client"), output = "1. opencode.ai\n"),
        tool("skill", input = mapOf("name" to "analyzing-projects")),
        tool(
            "task",
            input = mapOf("description" to "read the project guide"),
            metadata = mapOf("sessionID" to "ses_child"),
            output = "The guide says ...",
        ),
        tool(
            "question",
            input = mapOf("question" to "Which database?"),
            output = "Postgres",
        ),
        tool("custom_tool", input = mapOf("input" to "anything"), output = "done"),
    )

    /**
     * The edit an emulator run recorded: the server rejected its arguments (the model left out `path`), so the
     * card has a diff of what was attempted and, in `state.error`, the only place that says why it was not done.
     */
    fun failedEdit(): SessionMessage.Assistant = failedTool(
        name = "edit",
        input = mapOf("oldString" to "val limit = 10\nval name = \"foo\"", "newString" to "val limit = 25"),
        error = StructuredError("tool.execution", EDIT_REJECTED),
    )

    /** A shell call that ran and exited non-zero: its stderr is the output, the error is the exit status. */
    fun failedShell(): SessionMessage.Assistant = failedTool(
        name = "bash",
        input = mapOf("command" to "cat missing.txt"),
        error = StructuredError("tool.execution", "Command exited with code 1"),
        output = "cat: missing.txt: No such file or directory\n",
    )

    /** A tool this client has no card for, failing with a short message. */
    fun failedUnknownTool(): SessionMessage.Assistant = failedTool(
        name = "custom_tool",
        input = mapOf("input" to "anything"),
        error = StructuredError("tool.execution", "The custom tool refused the request"),
    )

    /** The message the server recorded for [failedEdit], multi-line and quoting the arguments it rejected. */
    const val EDIT_REJECTED = "Invalid arguments for tool \"edit\":\n- path: Missing key\n\n" +
        "Arguments provided:\n{\"oldString\":\"val limit = 10\",\"newString\":\"val limit = 25\"}\n\n" +
        "Update the arguments and call the tool again."

    private fun failedTool(
        name: String,
        input: Map<String, String>,
        error: StructuredError,
        output: String? = null,
    ): SessionMessage.Assistant = assistant(
        id = "msg_failed_tool_$name",
        agent = "build",
        content = listOf(
            AssistantContent.Tool(
                id = "tool_failed_$name",
                name = name,
                executed = true,
                state = ToolState.Error(
                    input = input.mapValues { (_, value) ->
                        JsonPrimitive(value) as kotlinx.serialization.json.JsonElement
                    },
                    error = error,
                    content = output?.let { listOf(ToolContent.Text(it)) },
                ),
                time = AssistantContent.Tool.Time(created = 6_000, ran = 6_010, completed = 6_120),
            ),
        ),
        finished = true,
    )

    private fun tool(
        name: String,
        input: Map<String, String>,
        output: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): SessionMessage.Assistant = assistant(
        id = "msg_tool_$name",
        agent = "build",
        content = listOf(
            AssistantContent.Tool(
                id = "tool_$name",
                name = name,
                executed = true,
                state = ToolState.Completed(
                    input = input.mapValues { (_, value) ->
                        JsonPrimitive(value) as kotlinx.serialization.json.JsonElement
                    },
                    content = listOfNotNull(output?.let { ToolContent.Text(it) }),
                    metadata = metadata.mapValues { (_, value) ->
                        JsonPrimitive(value) as kotlinx.serialization.json.JsonElement
                    },
                ),
                time = AssistantContent.Tool.Time(created = 6_000, ran = 6_010, completed = 6_120),
            ),
        ),
        finished = true,
    )

    fun compaction(): SessionMessage.Compaction = SessionMessage.Compaction(
        id = "msg_compaction",
        time = SessionMessage.CreatedTime(7_000),
        status = SessionMessage.Compaction.COMPLETED,
        reason = dev.opencode.android.core.model.CompactionReason.Auto,
        summary = "The conversation was compacted to keep the context window.",
        cost = 0.01,
        tokens = TokenUsage(1_000, 200, 0, TokenUsage.Cache(0, 0)),
    )

    fun idle(outcome: String): SessionMessage.Idle = idleMessage().copy(
        id = "msg_idle_$outcome",
        outcome = Outcome(outcome),
    )

    fun agentSwitch(): SessionMessage.AgentSwitched = SessionMessage.AgentSwitched(
        id = "msg_agent_switch",
        time = SessionMessage.CreatedTime(8_000),
        agent = "plan",
        previous = "build",
    )

    fun modelSwitch(): SessionMessage.ModelSwitched = SessionMessage.ModelSwitched(
        id = "msg_model_switch",
        time = SessionMessage.CreatedTime(8_100),
        model = ModelRef("reasoning", "fake"),
        previous = ModelRef("text", "fake", "default"),
    )

    fun locationSwitch(): SessionMessage.LocationSwitched = SessionMessage.LocationSwitched(
        id = "msg_location_switch",
        time = SessionMessage.CreatedTime(8_200),
        location = LocationPublicRef("/home/dev/other-project"),
        previous = SessionMessage.LocationSwitched.Previous(LocationPublicRef("/home/dev/opencode-android")),
    )

    fun failedStep(): SessionMessage.Assistant = assistant(
        id = "msg_failed",
        agent = "build",
        content = listOf(AssistantContent.Text("The provider refused the call.")),
        finished = true,
    ).let { base ->
        SessionMessage.Assistant(
            id = base.id,
            time = base.time,
            agent = base.agent,
            model = base.model,
            content = base.content,
            error = StructuredError("provider.rate_limit", "Rate limit exceeded", 429),
            retry = SessionMessage.Assistant.Retry(
                attempt = 2,
                at = 9_000,
                error = StructuredError("provider.rate_limit", "Rate limit exceeded", 429),
            ),
        )
    }

    fun unknownMessage(): SessionMessage.Unknown = SessionMessage.Unknown(
        discriminator = "future-type",
        raw = JsonObject(mapOf("id" to JsonPrimitive("msg_future"), "type" to JsonPrimitive("future-type"))),
    )

    private fun idleMessage(): SessionMessage.Idle = SessionMessage.Idle(
        id = "msg_idle",
        time = SessionMessage.CreatedTime(9_000),
        outcome = Outcome.Succeeded,
    )

    private fun assistant(
        id: String,
        agent: String,
        content: List<AssistantContent>,
        finished: Boolean,
    ): SessionMessage.Assistant = SessionMessage.Assistant(
        id = id,
        time = SessionMessage.Assistant.Time(
            created = 2_000,
            streamed = if (finished) 2_100 else null,
            completed = if (finished) 2_200 else null,
        ),
        agent = agent,
        model = ModelRef("text", "fake", "default"),
        content = content,
        finish = if (finished) FinishReason.Stop else null,
        cost = 0.02,
        tokens = TokenUsage(1_200, 340, 210, TokenUsage.Cache(80, 0)),
    )

    fun projects(): List<Project> = listOf(
        Project(
            id = "project-1",
            canonical = "/home/dev/opencode-android",
            vcs = "git",
            name = "opencode-android",
            time = Project.Time(created = 1, updated = 2, active = 3),
            sandboxes = listOf("/home/dev/opencode-android/.worktrees/feature"),
        ),
        Project(
            id = "project-2",
            canonical = "/home/dev/other",
            vcs = "git",
            name = "other",
            time = Project.Time(created = 1, updated = 2, active = 2),
            sandboxes = emptyList(),
        ),
    )

    fun sessionListState(): SessionListUiState = SessionListUiState(
        rows = listOf(
            row(
                "ses_running",
                "Refactor the timeline reducer",
                running = true,
                unread = true,
                updated = 1_000,
                cost = 0.42,
                tokens = 180_000,
            ),
            row(
                "ses_retry",
                "Fix the flaky SSE test",
                retrying = true,
                unread = true,
                updated = 900,
                cost = 0.02,
                tokens = 3_200,
            ),
            row("ses_plain", "Add the location catalog", updated = 800, cost = 0.11, tokens = 42_000, children = 2),
            row("ses_read", "Review the diff", updated = 700, cost = 0.0, tokens = 0),
        ),
        projects = projects(),
        search = "",
        filter = SessionFilter.Roots,
        paging = PagingState(loading = false, hasMore = true),
        now = 1_000_000,
    )

    fun sessionState(): SessionUiState = SessionUiState(
        title = "Refactor the timeline reducer",
        agent = "build",
        model = SessionModelUi("fake/reasoning", "high"),
        cost = 0.42,
        contextUsed = 48_000,
        contextLimit = 200_000,
        activity = SessionActivityUi.Retrying(2, 1_060_000),
        messages = sampleMessages(),
        paging = TimelinePaging(hasMore = true),
        following = false,
        serverId = "server-1",
        directory = "/home/dev/opencode-android",
    )

    private fun row(
        id: String,
        title: String,
        running: Boolean = false,
        retrying: Boolean = false,
        unread: Boolean = false,
        updated: Long = 1_000,
        cost: Double = 0.0,
        tokens: Long = 0,
        children: Int = 0,
    ): SessionRow = SessionRow(
        session = dev.opencode.android.core.model.SessionInfo(
            id = id,
            projectID = "project-1",
            agent = "build",
            model = ModelRef("text", "fake", "default"),
            cost = cost,
            tokens = TokenUsage(tokens / 2, tokens / 4, 0, TokenUsage.Cache(0, 0)),
            outcome = Outcome.Succeeded,
            time = dev.opencode.android.core.model.SessionInfo.Time(
                created = 0,
                updated = updated,
                idle = if (unread) updated + 10 else null,
                viewed = if (unread) null else updated,
            ),
            title = title,
            location = LocationPublicRef("/home/dev/opencode-android"),
        ),
        activity = when {
            retrying -> SessionActivity.Retrying(2, 1_060_000, "Rate limited", null)
            running -> SessionActivity.Running
            else -> SessionActivity.Idle
        },
        status = if (retrying) {
            dev.opencode.android.core.model.SessionStatus.Retry(
                2,
                "Rate limited",
                1_060_000,
            )
        } else {
            dev.opencode.android.core.model.SessionStatus.Idle
        },
        childCount = children,
    )
}
