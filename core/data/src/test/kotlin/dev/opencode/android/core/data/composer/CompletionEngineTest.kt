package dev.opencode.android.core.data.composer

import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.model.ReferenceInfo
import dev.opencode.android.core.model.ReferenceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the composer offers for the text in the box.
 *
 * The list has three jobs and each is a property: it must offer the right *things* for the trigger,
 * it must be **ordered** so a person can reach a row without re-reading it, and it must insert text
 * that leaves the rest of the token intact. Those are checked over a fixed catalog here, because
 * ordering and insertion are exactly the things that are impossible to check by eye on a phone.
 */
class CompletionEngineTest {

    private val catalog = ComposerCatalog(
        agents = listOf(
            agent("build", "Build"),
            agent("plan", "Plan"),
            agent("general", "General"),
        ),
        commands = listOf(
            CommandInfo("deploy", "Ship it"),
            CommandInfo("team/review", "Review a team"),
            CommandInfo("github:pr", "Open a pull request"),
        ),
        references = listOf(
            ReferenceInfo("docs", "/work/docs", source = ReferenceSource.Local("/work/docs")),
            ReferenceInfo("secret", "/work/.secret", hidden = true, source = ReferenceSource.Local("/work/.secret")),
        ),
        files = listOf(
            file("/work/src", FileSystemEntry.EntryType.DIRECTORY),
            file("/work/src/a.ts"),
            file("/work/src/Apple.kt"),
            file("/work/src/nested"),
            file("/work/README.md"),
            file("/work/lib/alpha.ts"),
        ),
        location = "/work",
    )

    @Test
    fun `a bare at sign offers the directories, the references and the agents`() {
        val kinds = CompletionEngine.complete("@", catalog = catalog).map { it.kind }
        assertTrue(CompletionKind.DIRECTORY in kinds)
        assertTrue(CompletionKind.AGENT in kinds)
        assertTrue(CompletionKind.REFERENCE in kinds)
        assertTrue(CompletionKind.FILE in kinds)
    }

    @Test
    fun `a directory is offered before a file of the same rank`() {
        val labels = CompletionEngine.complete("@s", catalog = catalog).map { it.label }
        assertEquals(listOf("src", "a.ts", "Apple.kt", "nested"), labels.take(4))
    }

    @Test
    fun `a name match outranks a path match`() {
        // `alpha` is not a prefix of any path segment except the file's own name, so it has to be
        // found by the name rule rather than missed.
        val labels = CompletionEngine.complete("@alpha", catalog = catalog).map { it.label }
        assertEquals(listOf("alpha.ts"), labels)
    }

    @Test
    fun `an unrelated query offers nothing rather than everything`() {
        assertEquals(emptyList<Completion>(), CompletionEngine.complete("@zzzz", catalog = catalog))
    }

    @Test
    fun `a mention inside a sentence still completes`() {
        val completions = CompletionEngine.complete("look at @al", catalog = catalog)
        // The file is a name match and the agent is only a substring match, so the file comes first.
        // A substring match is offered at all because "@gen" is how someone types "General".
        assertEquals(listOf("alpha.ts", "General"), completions.map { it.label })
    }

    @Test
    fun `a hidden reference is not offered`() {
        val targets = CompletionEngine.complete("@", catalog = catalog)
            .filter { it.kind == CompletionKind.REFERENCE }
            .map { it.target }
        assertEquals(listOf("/work/docs"), targets)
    }

    @Test
    fun `a mention inserts the path relative to the location and keeps a typed range`() {
        val completion = CompletionEngine.complete("@src/a#20-", catalog = catalog).first()
        assertEquals("@src/a.ts#20-", completion.insertText)
        assertEquals("/work/src/a.ts", completion.target)
    }

    @Test
    fun `an agent mention inserts the agent id, not its display name`() {
        val completion = CompletionEngine.complete("@Buil", catalog = catalog).first { it.kind == CompletionKind.AGENT }
        assertEquals("@build", completion.insertText)
        assertEquals("build", completion.target)
    }

    @Test
    fun `a reference inserts its path`() {
        val completion = CompletionEngine.complete("@doc", catalog = catalog).first { it.kind == CompletionKind.REFERENCE }
        assertEquals("@docs", completion.insertText)
    }

    @Test
    fun `a slash offers the server commands and the client actions`() {
        val labels = CompletionEngine.complete("/", catalog = catalog).map { it.label }
        assertTrue("/deploy" in labels)
        assertTrue("/team/review" in labels)
        assertTrue("/compact" in labels)
        assertTrue("/btw" in labels)
    }

    @Test
    fun `an mcp prompt is offered as a command of its own kind`() {
        val completion = CompletionEngine.complete("/github", catalog = catalog).single()
        assertEquals(CompletionKind.MCP_COMMAND, completion.kind)
        assertEquals("github:pr", completion.target)
    }

    @Test
    fun `a server command shadows a client command of the same name`() {
        val shadowed = catalog.copy(
            commands = catalog.commands + CommandInfo("compact", "The project's own compact"),
        )
        val completion = CompletionEngine.complete("/compact", catalog = shadowed).single()
        assertEquals(CompletionKind.COMMAND, completion.kind)
        assertEquals("The project's own compact", completion.detail)
    }

    @Test
    fun `an argument closes the command palette`() {
        assertEquals(emptyList<Completion>(), CompletionEngine.complete("/deploy now", cursor = 11, catalog = catalog))
    }

    @Test
    fun `a shell line has nothing to complete`() {
        assertEquals(emptyList<Completion>(), CompletionEngine.complete("!git ", catalog = catalog))
    }

    @Test
    fun `a plain message has nothing to complete`() {
        assertEquals(emptyList<Completion>(), CompletionEngine.complete("just a message", catalog = catalog))
    }

    @Test
    fun `the list is capped so a server with thousands of files cannot fill the screen`() {
        val many = catalog.copy(
            files = (1..200).map { file("/work/file$it.ts") },
            agents = emptyList(),
            references = emptyList(),
        )
        assertEquals(CompletionEngine.LIMIT, CompletionEngine.complete("@file", catalog = many).size)
    }

    @Test
    fun `ordering is stable when two entries are equally good`() {
        val first = CompletionEngine.complete("@/work/li", catalog = catalog).map { it.label }
        val second = CompletionEngine.complete("@/work/li", catalog = catalog).map { it.label }
        assertEquals(first, second)
    }

    @Test
    fun `a completion key is unique for two entries that share a label`() {
        val duplicated = catalog.copy(
            files = listOf(file("/work/src/a.ts"), file("/work/lib/a.ts")),
            agents = emptyList(),
            references = emptyList(),
        )
        val keys = CompletionEngine.complete("@a.ts", catalog = duplicated).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `an empty catalog offers the app's own commands and nothing else`() {
        // A `command.list` that has not loaded must not empty the palette: the client commands need
        // no server catalog to be available.
        assertEquals(emptyList<Completion>(), CompletionEngine.complete("@", catalog = ComposerCatalog.EMPTY))
        val commands = CompletionEngine.complete("/", catalog = ComposerCatalog.EMPTY)
        assertEquals(ClientCommands.ALL.map { "/" + it.name }.sorted(), commands.map { it.label }.sorted())
        assertTrue(commands.all { it.kind == CompletionKind.CLIENT_COMMAND })
    }

    private fun agent(id: String, name: String) = AgentInfo(
        id = id,
        name = name,
        mode = AgentInfo.AgentMode.PRIMARY,
    )

    private fun file(path: String, type: String = FileSystemEntry.EntryType.FILE) = FileSystemEntry(path, type)
}
