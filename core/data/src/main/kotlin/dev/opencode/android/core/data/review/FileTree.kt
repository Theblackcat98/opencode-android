package dev.opencode.android.core.data.review

/**
 * A file tree over the paths of a review, the TUI's flat file list made navigable (features doc
 * §38, "Diff viewer").
 *
 * **Two spellings, and the difference matters.** A diff arrives as paths the server spelled, some
 * absolute and some relative to the location. Each node keeps the *server's* full path in [path],
 * because that is what a comment, a `file:` URI and a `fs.read` all take, and it keeps a shorter
 * [displayPath] for the row that names it. The shorter one is produced by removing the **longest
 * common directory prefix** of the input, which is a fact about the input rather than a guess about
 * the project: a review of one file shows that file's own name, and a review of a whole tree shows
 * the directory the tree hangs from, as the root's own label.
 *
 * Folding is on `/` only. `.` and `..` are kept as segment names, because the path the server sent
 * is the path the client must send back and normalizing it would be the one thing this whole
 * codebase is not allowed to do (the P2 rule).
 */
object FileTree {

    /** Builds the tree for [paths]; duplicates are ignored, because a scope can name a file twice. */
    fun build(paths: List<String>): FileNode {
        val prefix = commonDirectoryPrefix(paths)
        // The root's own path carries no trailing separator: it is a directory a caller can pass
        // straight to `fs.list`, and `fs.list` answers a path with one differently from a path
        // without one.
        val rootPath = prefix.trimEnd('/')
        val root = Node(fullPath = rootPath)
        paths.forEach { root.insert(it, prefix) }
        // The display prefix is the root's path with no trailing separator, and it is the same for
        // every node: the tree strips the root from all of its descendants, not from one level.
        return root.toNode(name = rootPath, path = rootPath, displayPrefix = rootPath)
    }

    /** Every file path in tree order, which is the flat order a list and a search walk. */
    fun flatten(node: FileNode): List<String> = buildList {
        fun walk(current: FileNode) {
            current.files.forEach { add(it.path) }
            current.children.forEach { walk(it) }
        }
        walk(node)
    }

    /**
     * The longest directory prefix every path shares, with a trailing separator.
     *
     * Each path contributes its *directory* and the segments are matched whole, so
     * `["a/b/x.ts", "a/b/y.ts"]` gives `a/b/` and `["x/foo/a.ts", "x/foobar/b.ts"]` gives `x/` —
     * the two names that merely start alike are not merged, and a partial name must never become
     * the root's label, because a row saying `x/foo` when the real directories are `x/foo` and
     * `x/foobar` names a directory the server was never offered. A single path contributes its own
     * directory, which is what makes a one-file review show one row with the file's own name under
     * the directory it is in.
     */
    internal fun commonDirectoryPrefix(paths: List<String>): String {
        val directories = paths.map { path -> path.trimEnd('/').substringBeforeLast('/', "") }
        if (directories.isEmpty()) return ""
        var prefix = directories.first()
        for (candidate in directories.drop(1)) {
            prefix = commonPrefix(prefix, candidate)
            if (prefix.isEmpty()) return ""
        }
        return if (prefix == "/") "" else "$prefix/"
    }

    /**
     * The shared leading segments of two directory paths.
     *
     * The leading separator is inherited from the input rather than added: a review of relative
     * paths must not gain an absolute root that the server never used, which is the same rule the
     * rest of the codebase follows about paths (the P2 rule).
     */
    private fun commonPrefix(left: String, right: String): String {
        val leftParts = left.trim('/').split('/').filter { it.isNotEmpty() }
        val rightParts = right.trim('/').split('/').filter { it.isNotEmpty() }
        val shared = mutableListOf<String>()
        for (index in leftParts.indices) {
            if (index >= rightParts.size) break
            if (leftParts[index] != rightParts[index]) break
            shared += leftParts[index]
        }
        if (shared.isEmpty()) return ""
        val joined = shared.joinToString("/")
        return if (left.startsWith("/")) "/$joined" else joined
    }

    /**
     * The mutable tree, keyed by path segment and carrying the full path each node stands for.
     *
     * [fullPath] is what a directory node reports, so "open this directory in the browser" needs no
     * path arithmetic at the call site, and it is why the segments after the prefix are folded
     * rather than the whole path: the root already *is* the prefix.
     */
    private class Node(val fullPath: String) {
        private val directories = LinkedHashMap<String, Node>()
        private val files = LinkedHashMap<String, String>()

        /** Folds one path in, given the prefix already stripped for folding but kept for lookups. */
        fun insert(path: String, prefix: String) {
            val remainder = path.removePrefix(prefix)
            val parts = remainder.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return
            var cursor = this
            var cursorPath = fullPath
            parts.dropLast(1).forEach { segment ->
                val childPath = if (cursorPath.isEmpty()) segment else "$cursorPath/$segment"
                cursor = cursor.directories.getOrPut(segment) { Node(childPath) }
                cursorPath = childPath
            }
            cursor.files.getOrPut(path) { path }
        }

        fun toNode(name: String, path: String, displayPrefix: String, displayName: String? = null): FileNode {
            val strip = if (displayPrefix.isEmpty()) "" else "$displayPrefix/"
            return FileNode(
                name = displayName ?: name.substringAfterLast('/'),
                path = path,
                displayPath = "",
                isDirectory = true,
                children = directories.map { (key, node) ->
                    node.toNode(
                        name = key,
                        path = node.fullPath,
                        displayPrefix = displayPrefix,
                        displayName = key,
                    )
                },
                files = files.keys.sorted().map { filePath ->
                    FileNode(
                        name = filePath.substringAfterLast('/'),
                        path = filePath,
                        displayPath = filePath.removePrefix(strip),
                        isDirectory = false,
                        children = emptyList(),
                        files = emptyList(),
                    )
                },
            )
        }
    }
}

/**
 * One node of a review's file tree.
 *
 * [path] is the server's own spelling and is what a comment, a `file:` URI and a `fs.read` all
 * take. [displayPath] is the same path with the tree's common prefix removed, and is what the row
 * shows. A directory's [path] is the directory the server would accept.
 */
data class FileNode(
    val name: String,
    val path: String,
    val displayPath: String,
    val isDirectory: Boolean,
    val children: List<FileNode>,
    val files: List<FileNode>,
) {
    /** What a list shows: directories first, then files, each by name. */
    val entries: List<FileNode>
        get() = (children + files).sortedWith(compareBy({ if (it.isDirectory) 0 else 1 }, { it.name }))

    /** This node, its files and everything beneath it, in tree order. */
    fun all(): List<FileNode> = buildList {
        fun walk(node: FileNode) {
            add(node)
            node.files.forEach { add(it) }
            node.children.forEach { walk(it) }
        }
        walk(this@FileNode)
    }
}
