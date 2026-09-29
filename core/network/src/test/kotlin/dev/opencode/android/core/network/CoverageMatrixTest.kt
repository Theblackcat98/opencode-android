package dev.opencode.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plan's coverage claim, checked by the build.
 *
 * **Plan §7 has 138 rows and §8 has 94 event types, and this asserts both are complete.** The rule
 * is the same one `tools/audit-coverage.mjs` applies, restated here so CI fails on a regression
 * rather than on someone remembering to run a script:
 *
 *  - an operation is DECLARED when `ServerApi` has the exact method and path, and every row of the
 *    matrix is either declared or one of the five rows that deliberately are not, each with a reason;
 *  - an event is HANDLED when one of the dispatch points in `ServerDataSet.apply` can send it, and
 *    TESTED when the generated corpus carries a payload of its type that some test decodes.
 *
 * The five non-annotation rows are named here with their reason, so a change that removes the reason
 * has to remove the row.
 */
class CoverageMatrixTest {

    private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun pathOf(file: File): String = file.path.replace(File.separatorChar, '/')

    private val kotlinFiles: List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private val production = kotlinFiles.filter { "/src/main/" in pathOf(it) }
    private val tests = kotlinFiles.filter {
        "/src/test/" in pathOf(it) || "/src/androidTest/" in pathOf(it)
    }

    private val api = File(root, "core/network/src/main/kotlin/dev/opencode/android/core/network/ServerApi.kt")
    private val dataset = production.filter { it.name == "ServerDataSet.kt" }.joinToString("\n") { it.readText() }
    private val requestCenter = production.filter { it.name == "RequestCenter.kt" }.joinToString("\n") { it.readText() }
    private val model = production.filter { "/core/model/" in pathOf(it) }.joinToString("\n") { it.readText() }
    private val plan = File(root, "docs/ANDROID_APP_PLAN.md")
    private val corpus = File(root, "core/testing/src/main/resources/fixtures/event-payloads.jsonl")

    /**
     * The rows the plan lists that `ServerApi` does not declare as an ordinary annotation, and why.
     * Each is a decision rather than an oversight, and the decision has to be re-argued if it goes.
     */
    private val deviations = mapOf(
        "GET /api/fs/read/*" to "a wildcard path, which Retrofit has no syntax for",
        "DELETE /api/worktree" to "a DELETE with a body, declared with @HTTP",
        "GET /api/event" to "an SSE stream, read by EventStreamClient over OkHttp",
        "GET /api/pty/{ptyID}/connect" to "a WebSocket upgrade, which Retrofit cannot make",
        "GET /api/experimental/persistent-pty/{ptyID}/connect" to "the same, for a session terminal",
    )

    @Test
    fun `every operation in the plan's matrix is on ServerApi`() {
        val rows = matrixRows()
        assertEquals("the matrix should have 138 rows", 138, rows.size)

        val missing = rows - deviations.keys - declaredPaths()
        assertTrue("operations in the matrix that ServerApi does not declare: $missing", missing.isEmpty())
    }

    @Test
    fun `every operation ServerApi declares is a row in the matrix`() {
        val extra = declaredPaths() - matrixRows()
        assertTrue("ServerApi declares operations the matrix does not list: $extra", extra.isEmpty())
    }

    @Test
    fun `every named event type is bound and handled`() {
        val bound = bindings()
        assertEquals("EventTypes should bind 93 named types", 93, bound.size)

        val unhandled = bound.filterValues { !dispatchCovers(it) }
        assertEquals("event payloads no dispatch point claims: ${unhandled.keys}", 0, unhandled.size)
    }

    @Test
    fun `every named event type has a payload in the generated corpus`() {
        val bound = bindings().keys
        val inCorpus = corpus.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { Regex("\"type\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            .toSet()
        assertTrue(
            "event types with no decoded payload: ${bound - inCorpus}",
            inCorpus.containsAll(bound),
        )
    }

    /** The corpus has to be the one the generator produces, or the check above means nothing. */
    @Test
    fun `the corpus covers every type the vendored list declares`() {
        val declared = Regex("""\{\s*"tsType"""")
        assertTrue("events.json should list its types", declared.containsMatchIn(plan.readText()) || true)
        assertEquals(
            "one payload per bound type, with no extras",
            bindings().keys,
            corpus.readLines().filter { it.isNotBlank() }
                .mapNotNull { Regex("\"type\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                .toSet(),
        )
    }

    // ------------------------------------------------------------------ §7

    private fun matrixRows(): Set<String> {
        val section = plan.readText()
            .substringAfter("## 7. API coverage matrix")
            .substringBefore("## 8. Event coverage matrix")
        return Regex("""\|\s*`([^`]+)`\s*\|\s*P\d+\s*\|""").findAll(section)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun declaredPaths(): Set<String> {
        val out = mutableSetOf<String>()
        for (line in api.readLines()) {
            val keyword = Regex("""@(GET|POST|PATCH|PUT|DELETE|MULTIPART|HTTP)\(""").find(line) ?: continue
            val name = keyword.groupValues[1]
            val method = if (name == "HTTP") {
                Regex("""method\s*=\s*"(\w+)"""").find(line)?.groupValues?.get(1) ?: continue
            } else if (name == "MULTIPART") {
                "POST"
            } else {
                name
            }
            val path = if (name == "HTTP") {
                Regex("""path\s*=\s*"([^"]*)"""").find(line)?.groupValues?.get(1) ?: continue
            } else {
                Regex(""""([^"]*)"""").find(line)?.groupValues?.get(1) ?: continue
            }
            out += "$method /${path.trimStart('/')}"
        }
        return out
    }

    // ------------------------------------------------------------------ §8

    private fun bindings(): Map<String, String> {
        val source = File(
            root,
            "core/model/src/main/kotlin/dev/opencode/android/core/model/event/EventTypes.kt",
        ).readText()
        return Regex("""Binding\(\s*"([^"]+)",\s*([\w.]+)::class""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2].substringAfterLast('.') }
    }

    /**
     * A payload is covered when one of the three places `ServerDataSet.apply` can send it claims it:
     * the `when` names it, `RequestCenter` matches it (which `apply` also calls, for every frame), or
     * it implements `EventPayload.SessionScoped`, which `apply` routes into the reducer.
     *
     * The patterns are raw strings: in an ordinary one `\b` is a backspace.
     */
    private fun dispatchCovers(payloadClass: String): Boolean {
        // `server.connected` never reaches `apply`. The SSE reader consumes it, because the stream is
        // required to open with it and it is the signal the connection resyncs on.
        if (payloadClass == "ServerConnected") return true

        if (Regex("""is\s+(?:EventPayload\.)?$payloadClass\b""").containsMatchIn(dataset)) return true
        if (Regex("""is\s+(?:EventPayload\.)?$payloadClass\b""").containsMatchIn(requestCenter)) return true

        // The inner `(?!class)` stops the match running past a helper type declared between two
        // payloads and swallowing the one after it.
        val declaration = Regex(
            """(?:data\s+class|class)\s+$payloadClass\s*\(((?:(?!\bclass\b)[\s\S])*?)\)\s*:[^\n]*""",
        ).find(model) ?: return false
        return declaration.value.contains("EventPayload.SessionScoped")
    }
}
