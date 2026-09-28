package dev.opencode.android.core.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The file tree, and the navigation a reviewer does with it. */
class FileTreeTest {

    @Test
    fun `a single file's tree is one row with the file's own name`() {
        val tree = FileTree.build(listOf("/home/dev/project/src/a.kt"))

        // The root is the file's own directory, so the row is not decorated with a path the
        // reviewer has to expand to see one file, and the root's path is one `fs.list` accepts.
        assertEquals("/home/dev/project/src", tree.path)
        assertEquals(1, tree.files.size)
        assertEquals("a.kt", tree.files.single().name)
        assertEquals("a.kt", tree.files.single().displayPath)
        assertEquals("/home/dev/project/src/a.kt", tree.files.single().path)
    }

    @Test
    fun `a common directory becomes the tree's root and the rows are relative to it`() {
        val tree = FileTree.build(
            listOf(
                "/home/dev/project/src/main/kotlin/a.kt",
                "/home/dev/project/src/main/kotlin/b.kt",
                "/home/dev/project/README.md",
            ),
        )

        assertEquals("/home/dev/project", tree.path)
        assertEquals(listOf("src", "README.md"), tree.entries.map { it.name })
        val src = tree.children.single()
        assertEquals("/home/dev/project/src", src.path)
        assertEquals("main", src.children.single().name)
    }

    @Test
    fun `every file keeps the server's own path for lookups`() {
        val paths = listOf("src/a.kt", "src/deep/b.kt")
        val tree = FileTree.build(paths)

        assertEquals(paths.toSet(), FileTree.flatten(tree).toSet())
        tree.all().filter { !it.isDirectory }.forEach { node ->
            assertTrue(node.path in paths)
        }
    }

    @Test
    fun `a directory node's path is a directory the server would accept`() {
        // The tree's root is the common directory, so `src` is the root and its own path is a path
        // `fs.list` takes without any arithmetic at the call site.
        val tree = FileTree.build(listOf("src/main/a.kt", "src/test/b.kt"))

        assertTrue(tree.isDirectory)
        assertEquals("src", tree.path)
        assertEquals(listOf("main", "test"), tree.children.map { it.name })
        assertTrue(tree.children.all { it.path == "src/${it.name}" })
        assertEquals(2, tree.all().count { !it.isDirectory })
    }

    @Test
    fun `a nested directory below the root carries the whole path`() {
        val tree = FileTree.build(listOf("src/main/kotlin/a.kt", "src/test/b.kt"))

        val main = tree.children.single { it.name == "main" }
        assertEquals("src/main", main.path)
        assertEquals("kotlin", main.children.single().name)
        assertEquals("src/main/kotlin", main.children.single().path)
    }

    @Test
    fun `relative and absolute paths in one review do not collide`() {
        val tree = FileTree.build(listOf("src/a.kt", "/home/dev/project/src/a.kt"))

        // The two are different files on the server, and the tree must not merge them under one
        // name: a review of both would otherwise show one row for two files.
        assertEquals(2, tree.all().count { !it.isDirectory })
    }

    @Test
    fun `duplicate paths appear once`() {
        val tree = FileTree.build(listOf("src/a.kt", "src/a.kt"))

        assertEquals(1, tree.all().count { !it.isDirectory })
    }

    @Test
    fun `a path with a dot segment is kept, because the server spelled it that way`() {
        // Normalizing would be the one thing this codebase is not allowed to do: the path the
        // server sent is the path every later call has to use.
        val tree = FileTree.build(listOf("src/../lib/a.kt"))

        assertEquals("src/../lib/a.kt", tree.all().first { !it.isDirectory }.path)
    }

    @Test
    fun `directories sort before files`() {
        val tree = FileTree.build(listOf("z.kt", "a-dir/b.kt", "a.kt"))

        assertEquals(listOf("a-dir", "a.kt", "z.kt"), tree.entries.map { it.name })
    }

    @Test
    fun `the common prefix is whole segments only`() {
        // `/a/b` and `/a/bb` share the directory `/a`, and that is the whole-segment answer.
        assertEquals("/a/", FileTree.commonDirectoryPrefix(listOf("/a/b/x.ts", "/a/bb/y.ts")))
        assertEquals("/a/b/", FileTree.commonDirectoryPrefix(listOf("/a/b/x.ts", "/a/b/y.ts")))
    }

    @Test
    fun `two directories that merely start alike are not merged`() {
        // `foo` and `foobar` share no segment, so the prefix stops at `x` rather than inventing
        // `x/foo`.
        assertEquals("/x/", FileTree.commonDirectoryPrefix(listOf("/x/foo/a.ts", "/x/foobar/b.ts")))
    }

    @Test
    fun `a single path contributes its own directory`() {
        assertEquals("/a/b/", FileTree.commonDirectoryPrefix(listOf("/a/b/c.ts")))
    }

    @Test
    fun `paths in different top level directories share nothing`() {
        assertEquals("", FileTree.commonDirectoryPrefix(listOf("/a/x.ts", "/b/y.ts")))
    }

    @Test
    fun `an empty input is an empty tree`() {
        val tree = FileTree.build(emptyList())

        assertTrue(tree.entries.isEmpty())
        assertEquals(emptyList<String>(), FileTree.flatten(tree))
    }
}

/** Next/previous file and hunk, and the mark-reviewed bookkeeping. */
class ReviewNavigationTest {

    private fun file(name: String, hunks: Int) = ParsedFile(
        file = name,
        hunks = List(hunks) { DiffHunk("h$it", it + 1, 1, it + 1, 1, listOf(DiffLine(DiffLineKind.CONTEXT, "x", it + 1, it + 1))) },
        additions = 0,
        deletions = 0,
        status = dev.opencode.android.core.model.FileDiffStatus.Modified,
    )

    private val three = listOf(file("a.kt", 2), file("b.kt", 1), file("c.kt", 3))

    @Test
    fun `a review opens at the first file`() {
        assertEquals(ReviewPosition("a.kt", 0), ReviewNavigator.start(three))
        assertEquals(ReviewPosition(), ReviewNavigator.start(emptyList()))
    }

    @Test
    fun `the next file wraps`() {
        var position = ReviewNavigator.start(three)
        position = ReviewNavigator.nextFile(three, position)
        assertEquals("b.kt", position.file)
        position = ReviewNavigator.nextFile(three, position)
        assertEquals("c.kt", position.file)
        // A reviewer at the end of a review does not mean "stop".
        position = ReviewNavigator.nextFile(three, position)
        assertEquals("a.kt", position.file)
    }

    @Test
    fun `the previous file wraps too`() {
        var position = ReviewNavigator.start(three)
        position = ReviewNavigator.previousFile(three, position)
        assertEquals("c.kt", position.file)
    }

    @Test
    fun `an empty review cannot move`() {
        val from = ReviewPosition()
        assertEquals(from, ReviewNavigator.nextFile(emptyList(), from))
        assertEquals(from, ReviewNavigator.previousFile(emptyList(), from))
        assertEquals(from, ReviewNavigator.nextHunk(emptyList(), from))
    }

    @Test
    fun `the next hunk advances inside the file`() {
        val position = ReviewPosition("a.kt", 0)
        assertEquals(ReviewPosition("a.kt", 1), ReviewNavigator.nextHunk(three, position))
    }

    @Test
    fun `the next hunk crosses into the next file`() {
        // A reviewer working through a review wants the next thing to read, wherever it is.
        assertEquals(ReviewPosition("b.kt", 0), ReviewNavigator.nextHunk(three, ReviewPosition("a.kt", 1)))
    }

    @Test
    fun `the previous hunk crosses back into the previous file's last hunk`() {
        assertEquals(ReviewPosition("a.kt", 1), ReviewNavigator.previousHunk(three, ReviewPosition("b.kt", 0)))
    }

    @Test
    fun `a single hunk file has no next hunk of its own`() {
        // b.kt has one hunk, so there is nothing after it inside the file and the move crosses on.
        assertEquals(ReviewPosition("c.kt", 0), ReviewNavigator.nextHunk(three, ReviewPosition("b.kt", 0)))
    }

    @Test
    fun `a position for a file that is gone is cleared, not left dangling`() {
        val stale = ReviewPosition("gone.kt", 5)
        assertEquals(ReviewPosition(), stale.clampedTo(three))
    }

    @Test
    fun `a position is clamped when the file shrank under the user`() {
        val stale = ReviewPosition("a.kt", 9)
        assertEquals(ReviewPosition("a.kt", 1), stale.clampedTo(three))
    }

    @Test
    fun `a single file review says it is a single file review`() {
        assertFalse(ReviewNavigator.hasSeveralFiles(listOf(file("a.kt", 1))))
        assertTrue(ReviewNavigator.hasSeveralFiles(three))
        assertFalse(ReviewNavigator.hasFiles(emptyList()))
    }

    @Test
    fun `the first unreviewed file is where a re-opened review lands`() {
        val reviewed = ReviewedFiles(setOf(three[0].key))

        assertEquals(ReviewPosition("b.kt", 0), ReviewNavigator.nextUnreviewed(three, reviewed))
    }

    @Test
    fun `a fully reviewed review lands on the first file rather than nowhere`() {
        val reviewed = ReviewedFiles(three.map { it.key }.toSet())

        assertEquals(ReviewPosition("a.kt", 0), ReviewNavigator.nextUnreviewed(three, reviewed))
        assertTrue(ReviewNavigator.isComplete(three, reviewed))
    }

    @Test
    fun `an empty review is never complete`() {
        // "Nothing to review" and "reviewed everything" are different, and conflating them would
        // show a progress bar at 100% for a project with no changes.
        assertFalse(ReviewNavigator.isComplete(emptyList(), ReviewedFiles()))
    }

    @Test
    fun `marking reviewed is a toggle that reports the new state`() {
        val files = ReviewedFiles()
        val key = three[0].key

        assertTrue(files.toggle(key))
        assertTrue(key in files)
        assertFalse(files.toggle(key))
        assertFalse(key in files)
    }

    @Test
    fun `a reviewed key carries the change kind, so two changes to one file are two things`() {
        val modified = file("a.kt", 1)
        val added = modified.copy(status = dev.opencode.android.core.model.FileDiffStatus.Added)
        val files = ReviewedFiles()

        files.add(modified.key)
        assertFalse(added.key in files)
    }

    @Test
    fun `with returns a new set and leaves the old one alone`() {
        val original = ReviewedFiles()
        val copy = original.with("k", reviewed = true)

        assertTrue("k" in copy)
        assertEquals(0, original.size)
    }
}
