package dev.opencode.android.core.data.review

import dev.opencode.android.core.model.FileDiff
import dev.opencode.android.core.model.FileDiffStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unified-diff parser, over the patches a real server actually produces and the ones it should
 * never produce.
 *
 * Every case here is a way a `git diff` body can be wrong in the wild: a missing trailing newline, a
 * CRLF transport, a binary file, a hunk whose counts lie, a rename, a patch that is not a patch at
 * all. The parser is total, so the assertions are about what the value *contains* rather than about
 * whether a call threw.
 */
class UnifiedDiffTest {

    private fun patch(body: String) = UnifiedDiff.parse(patch = body, file = "src/a.kt")

    @Test
    fun `a single hunk keeps its lines, kinds and both line numbers`() {
        val parsed = patch(
            """
            @@ -1,3 +1,4 @@
             package a
            -val x = 1
            +val x = 2
            +val y = 3
            """.trimIndent() + "\n",
        )

        assertEquals(1, parsed.hunks.size)
        val hunk = parsed.hunks.single()
        assertEquals(1, hunk.oldStart)
        assertEquals(3, hunk.oldCount)
        assertEquals(1, hunk.newStart)
        assertEquals(4, hunk.newCount)
        assertEquals(
            listOf(DiffLineKind.CONTEXT, DiffLineKind.REMOVED, DiffLineKind.ADDED, DiffLineKind.ADDED),
            hunk.lines.map { it.kind },
        )
        assertEquals(listOf("package a", "val x = 1", "val x = 2", "val y = 3"), hunk.lines.map { it.text })
        // The context line is line 1 on both sides, the removal is old line 2, and the additions are
        // new lines 2 and 3.
        assertEquals(listOf(1, 2, null, null), hunk.lines.map { it.oldNumber })
        assertEquals(listOf(1, null, 2, 3), hunk.lines.map { it.newNumber })
        assertEquals(2, hunk.additions)
        assertEquals(1, hunk.deletions)
    }

    @Test
    fun `an empty context line is context, not an unparsed line`() {
        // A blank line in the middle of a file: git writes " " for it, and a hand-written patch
        // writes nothing at all. Both have to come out as a context line with no text.
        val parsed = patch("@@ -1,3 +1,3 @@\n a\n\n b\n")

        assertEquals(3, parsed.lines.size)
        assertEquals(DiffLineKind.CONTEXT, parsed.lines[1].kind)
        assertEquals("", parsed.lines[1].text)
        assertEquals(2, parsed.lines[1].oldNumber)
    }

    @Test
    fun `a context line with no leading space is kept verbatim`() {
        val parsed = patch("@@ -1,2 +1,2 @@\ncontext\n+added\n")

        assertEquals(DiffLineKind.CONTEXT, parsed.lines.first().kind)
        assertEquals("context", parsed.lines.first().text)
    }

    @Test
    fun `a CRLF transport does not become part of the content`() {
        val parsed = patch("@@ -1,2 +1,2 @@\r\n a\r\n-b\r\n+c\r\n")

        assertEquals(listOf("a", "b", "c"), parsed.lines.map { it.text })
        assertTrue(parsed.lines.none { it.text.contains('\r') })
    }

    @Test
    fun `a patch with no trailing newline produces no phantom last line`() {
        val parsed = patch("@@ -1,2 +1,1 @@\n a\n-b")

        assertEquals(2, parsed.lines.size)
        assertEquals(DiffLineKind.REMOVED, parsed.lines.last().kind)
        assertEquals("b", parsed.lines.last().text)
    }

    @Test
    fun `a no-newline marker qualifies the line before it and is not a line`() {
        // git writes one marker per affected line, and both sides of a replacement are affected
        // when neither file ended in a newline.
        val parsed = patch("@@ -1,2 +1,1 @@\n-a\n\\ No newline at end of file\n+b\n\\ No newline at end of file\n")

        assertEquals(2, parsed.lines.size)
        assertTrue(parsed.lines[0].noNewlineAtEnd)
        assertTrue(parsed.lines[1].noNewlineAtEnd)
    }

    @Test
    fun `a no-newline marker after only one side of a replacement marks only that side`() {
        val parsed = patch("@@ -1,2 +1,1 @@\n-a\n+b\n\\ No newline at end of file\n")

        assertFalse(parsed.lines[0].noNewlineAtEnd)
        assertTrue(parsed.lines[1].noNewlineAtEnd)
    }

    @Test
    fun `a no-newline marker in the middle qualifies only what came before it`() {
        val parsed = patch("@@ -1,3 +1,3 @@\n-a\n\\ No newline at end of file\n+b\n c\n")

        assertTrue(parsed.lines[0].noNewlineAtEnd)
        assertFalse(parsed.lines[1].noNewlineAtEnd)
        assertFalse(parsed.lines[2].noNewlineAtEnd)
    }

    @Test
    fun `a body longer than the declared counts is kept`() {
        // The counts are the producer's claim. The lines are the artifact, and a reviewer has to be
        // able to see every line the server sent.
        val parsed = patch("@@ -1,1 +1,1 @@\n a\n+b\n+c\n")

        assertEquals(3, parsed.lines.size)
        // The lines past the declared new count get no new number: they are outside the range the
        // hunk claims, and inventing one would put a comment on a line that does not exist.
        assertEquals(listOf(1, null, null), parsed.lines.map { it.newNumber })
    }

    @Test
    fun `a body shorter than the declared counts simply ends`() {
        val parsed = patch("@@ -1,5 +1,5 @@\n a\n-b\n")

        assertEquals(2, parsed.lines.size)
    }

    @Test
    fun `an empty patch is binary rather than an empty diff`() {
        val parsed = UnifiedDiff.parse(FileDiff("a.png", "", 0, 0, FileDiffStatus.Added))

        assertTrue(parsed.binary)
        assertEquals(0, parsed.hunks.size)
        // There is something to show: "this file is binary" is a sentence, not an absence.
        assertTrue(parsed.hasContent)
    }

    @Test
    fun `a Binary files marker is binary`() {
        val parsed = patch("Binary files a/logo.png and b/logo.png differ\n")

        assertTrue(parsed.binary)
        assertEquals(0, parsed.hunks.size)
    }

    @Test
    fun `a GIT binary patch header is binary`() {
        val parsed = patch("GIT binary patch\nliteral 12\n")

        assertTrue(parsed.binary)
    }

    @Test
    fun `a rename keeps the old path and the new one is the file`() {
        val parsed = UnifiedDiff.parse(
            patch = "diff --git a/old.ts b/new.ts\n" +
                "similarity index 90%\n" +
                "rename from old.ts\n" +
                "rename to new.ts\n" +
                "@@ -1 +1 @@\n" +
                "-a\n" +
                "+b\n",
            file = "new.ts",
        )

        assertEquals("old.ts", parsed.previousPath)
        assertEquals("new.ts", parsed.file)
        assertEquals(1, parsed.hunks.size)
    }

    @Test
    fun `an added file has no old path`() {
        val parsed = UnifiedDiff.parse(
            patch = "diff --git a/new.ts b/new.ts\n" +
                "new file mode 100644\n" +
                "index 0000000..1234567\n" +
                "--- /dev/null\n" +
                "+++ b/new.ts\n" +
                "@@ -0,0 +1 @@\n" +
                "+hello\n",
            file = "new.ts",
        )

        assertNull(parsed.previousPath)
        assertEquals(1, parsed.lines.size)
        assertEquals(1, parsed.lines.single().newNumber)
    }

    @Test
    fun `a hunk header without counts means one line`() {
        val parsed = patch("@@ -3 +3 @@\n-a\n+b\n")

        val hunk = parsed.hunks.single()
        assertEquals(3, hunk.oldStart)
        assertEquals(1, hunk.oldCount)
        assertEquals(3, hunk.newStart)
        assertEquals(1, hunk.newCount)
    }

    @Test
    fun `a hunk header with no heading parses`() {
        val parsed = patch("@@ -1,2 +1,2 @@\n a\n b\n")

        assertEquals("", parsed.hunks.single().heading)
    }

    @Test
    fun `a hunk heading is the function name git wrote`() {
        val parsed = patch("@@ -10,3 +10,3 @@ fun main() {\n a\n-b\n+c\n")

        assertEquals("fun main() {", parsed.hunks.single().heading)
    }

    @Test
    fun `several hunks keep their order and their own numbering`() {
        val parsed = patch(
            """
            @@ -1,2 +1,2 @@
             a
            -b
            +B
            @@ -40,2 +41,3 @@
             x
            +inserted
             y
            """.trimIndent() + "\n",
        )

        assertEquals(2, parsed.hunks.size)
        assertEquals(listOf(1, 41), parsed.hunks.map { it.newStart })
        // The second hunk's context after an insertion is new line 43, because the insertion took
        // new line 42.
        assertEquals(listOf(41, 42, 43), parsed.hunks[1].lines.map { it.newNumber })
    }

    @Test
    fun `a malformed hunk header is kept rather than dropped`() {
        val parsed = patch("@@ -1,1 +1,1 @@\n-a\n+b\n@@ this is not a range @@\n-x\n+y\n")

        assertEquals(1, parsed.hunks.size)
        // Everything from the unparseable header on is kept, verbatim, rather than guessed at.
        assertEquals(listOf("@@ this is not a range @@", "-x", "+y"), parsed.unparsed)
    }

    @Test
    fun `a markerless line inside a hunk is context, not junk`() {
        // Some producers strip the leading space from context lines. A line with no marker at all is
        // therefore content, and dropping it would shorten the diff the reviewer is reading.
        val parsed = patch("@@ -1,1 +1,1 @@\nunmarked context\n")

        assertEquals(DiffLineKind.CONTEXT, parsed.lines.single().kind)
        assertEquals("unmarked context", parsed.lines.single().text)
    }

    @Test
    fun `a patch that is not a diff at all is kept as unparsed and does not throw`() {
        val parsed = patch("this is not a patch\nit is just some text\n")

        assertEquals(0, parsed.hunks.size)
        assertEquals(2, parsed.unparsed.size)
        assertTrue(parsed.hasContent)
        assertFalse(parsed.binary)
    }

    @Test
    fun `a stray backslash line is kept as unparsed content`() {
        val parsed = patch("@@ -1,1 +1,1 @@\n a\n\\ not the marker\n")

        assertEquals(DiffLineKind.UNPARSED, parsed.lines[1].kind)
        assertEquals(" not the marker", parsed.lines[1].text)
    }

    @Test
    fun `the server's counts are the file's counts`() {
        val diff =
            FileDiff(
                "a.kt",
                "@@ -1,1 +1,1 @@\n-a\n+b\n",
                additions = 7,
                deletions = 9,
                status = FileDiffStatus.Modified,
            )

        val parsed = UnifiedDiff.parse(diff)

        // The server computed them; the client does not second-guess it. A disagreement is a
        // question about the server, and replacing its answer with the client's would hide it.
        assertEquals(7, parsed.additions)
        assertEquals(9, parsed.deletions)
        // What the patch itself contains is still available, because a viewer's +/- counts come
        // from the lines and the header's counts come from the server.
        assertEquals(2, parsed.changeCount)
    }

    @Test
    fun `a huge patch parses without quadratic behaviour and keeps every line`() {
        val body = buildString {
            append("@@ -1,20000 +1,20000 @@\n")
            repeat(20_000) { index ->
                append(if (index % 2 == 0) " context $index\n" else "+added $index\n")
            }
        }

        val started = System.nanoTime()
        val parsed = UnifiedDiff.parse(patch = body, file = "big.kt")
        val millis = (System.nanoTime() - started) / 1_000_000

        assertEquals(20_000, parsed.lines.size)
        assertEquals(10_000, parsed.lines.count { it.kind == DiffLineKind.ADDED })
        // A thousand-line diff must not cost a second; the budget is loose but a quadratic parser
        // would miss it by three orders of magnitude.
        assertTrue("parsing 20k lines took $millis ms", millis < 1_000)
    }

    @Test
    fun `a patch with a very long single line does not hang`() {
        val line = "x".repeat(200_000)
        val parsed = patch("@@ -1,1 +1,1 @@\n+$line\n")

        assertEquals(1, parsed.lines.size)
        assertEquals(200_000, parsed.lines.single().text.length)
    }

    @Test
    fun `a multibyte line is measured in characters, not bytes`() {
        val parsed = patch("@@ -1,1 +1,1 @@\n-\u00e9\u00e8\u00ea\n+\u4f60\u597d\n")

        assertEquals("\u00e9\u00e8\u00ea", parsed.lines[0].text)
        assertEquals("\u4f60\u597d", parsed.lines[1].text)
    }

    @Test
    fun `a multi file patch splits on diff --git and names each file`() {
        val parsed = UnifiedDiff.parseAll(
            """
            diff --git a/src/a.kt b/src/a.kt
            index 111..222 100644
            --- a/src/a.kt
            +++ b/src/a.kt
            @@ -1,2 +1,2 @@
             keep
            -old
            +new
            diff --git a/src/b.kt b/src/b.kt
            new file mode 100644
            --- /dev/null
            +++ b/src/b.kt
            @@ -0,0 +1 @@
            +brand new
            """.trimIndent() + "\n",
        )

        assertEquals(2, parsed.size)
        assertEquals(listOf("src/a.kt", "src/b.kt"), parsed.map { it.file })
        assertEquals(FileDiffStatus.Added, parsed[1].status)
        assertEquals(FileDiffStatus.Modified, parsed[0].status)
        assertEquals(1, parsed[0].additions)
        assertEquals(1, parsed[0].deletions)
    }

    @Test
    fun `a path with a space survives the diff --git header`() {
        val parsed = UnifiedDiff.parseAll(
            "diff --git a/my docs/a file.ts b/my docs/a file.ts\n--- a/my docs/a file.ts\n+++ b/my docs/a file.ts\n@@ -1 +1 @@\n-a\n+b\n",
        )

        assertEquals("my docs/a file.ts", parsed.single().file)
    }

    @Test
    fun `the change count is what a file tree shows`() {
        val parsed = patch("@@ -1,4 +1,4 @@\n a\n-b\n+B\n c\n")

        assertEquals(2, parsed.changeCount)
    }

    @Test
    fun `the stable key includes the status so two changes to one file stay distinct`() {
        val modified = UnifiedDiff.parse(FileDiff("a.kt", "@@ -1 +1 @@\n-a\n+b\n", 1, 1, FileDiffStatus.Modified))
        val added = UnifiedDiff.parse(FileDiff("a.kt", "@@ -1 +1 @@\n-a\n+b\n", 1, 1, FileDiffStatus.Added))

        assertEquals("modified:a.kt", modified.key)
        assertEquals("added:a.kt", added.key)
    }

    @Test
    fun `splitLines is the reason a missing trailing newline is not a phantom line`() {
        assertEquals(listOf("a", "b"), UnifiedDiff.splitLines("a\nb"))
        assertEquals(listOf("a", "b"), UnifiedDiff.splitLines("a\nb\n"))
        assertEquals(listOf("a", "b"), UnifiedDiff.splitLines("a\r\nb"))
        assertEquals(listOf("a", "b"), UnifiedDiff.splitLines("a\rb"))
        assertEquals(emptyList<String>(), UnifiedDiff.splitLines(""))
    }
}
