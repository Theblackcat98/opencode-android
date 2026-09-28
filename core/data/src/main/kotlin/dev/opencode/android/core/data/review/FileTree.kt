package dev.opencode.android.core.data.review

/**
 * A file tree over the paths of a review, the TUI's flat file list made navigable (features doc
 * §38, "Diff viewer").
 *
 * **Two spellings, and the difference matters.** A diff arrives as paths the server spelled, some
 * absolute and some relative to the location. Each node keeps the *server's* full path in [path],
 * because that is what a comment and a `file:` URI have to carry, and it keeps a shorter
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
        val root = Node()
        paths.forEach { root.insert(it) }
        val prefix = commonDirectoryPrefix(paths)
        return root.toNode(name = prefix.ifEmpty { "" }, path = prefix, displayPrefix = prefix)
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
     * Only whole segments count, and a path that is *itself* a file is excluded from the vote: with
     * `["a/x.ts", "a/y.ts"]` the prefix is `a/`, while with `["a/x.ts", "a/b/y.ts"]` it is `a/`
     * as well, and with a single path it is empty, which is what makes a one-file review show one
     * row with the file's own name.
     */
    internal fun commonDirectoryPrefix(paths: List<String>): String {
        val directories = paths.map { path -> path.trimEnd('/').substringBeforeLast('/', "") }
        if (directories.isEmpty()) return ""
        var prefix = directories.first()
        for (candidate in directories.drop(1)) {
            prefix = commonPrefix(prefix, candidate)
            if (prefix.isEmpty()) return ""
        }
        // The prefix must be a whole segment boundary on both sides, so a partial name never
        // becomes the root's label.
        return if (prefix.isEmpty() || prefix == "/") "" else "$prefix/"
    }

    private fun commonPrefix(left: String, right: String): String {
        val leftParts = left.trim('/').split('/').filter { it.isNotEmpty() }
        val rightParts = right.trim('/').split('/').filter { it.isNotEmpty() }
        val shared = mutableListOf<String>()
        for (index in leftParts.indices) {
            if (index >= rightParts.size) break
            if (leftParts[index] != rightParts[index]) break
            shared += leftParts[index]
        }
        return if (shared.isEmpty()) "" else shared.joinToString("/", prefix = "/")
    }

    private class Node {
        private val directories = LinkedHashMap<String, Node>()
        private val files = LinkedHashMap<String, FileNode>()

        fun insert(path: String) {
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return
            val leaf = parts.last()
            var cursor = this
            parts.dropLast(1).forEach { segment ->
                cursor = cursor.directories.getOrPut(segment) { Node() }
            }
            files.getOrPut(path) { FileNode(leaf, path, path, false, emptyList(), emptyList()) }
        }

        fun toNode(name: String, path: String, displayPrefix: String, displayName: String? = null): FileNode {
            val childPrefix = if (displayPrefix.isEmpty()) "" else "$displayPrefix/"
            return FileNode(
                name = displayName ?: name.substringAfterLast('/'),
                path = path,
                displayPath = "",
                isDirectory = true,
                children = directories.map { (key, node) ->
                    node.toNode(
                        name = key,
                        path = if (path.isEmpty()) key else "$path/$key",
                        displayPrefix = childPrefix,
                        displayName = key,
                    )
                },
                files = files.keys.sorted().map { filePath ->
                    FileNode(
                        name = filePath.substringAfterLast('/'),
                        path = filePath,
                        displayPath = filePath.removePrefix(childPrefix),
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
 * shows. A directory's [path] is the directory the server would accept, so "open this directory in
 * the browser" needs no path arithmetic at the call site.
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

    /** This node and everything beneath it, in tree order. */
    fun all(): List<FileNode> = buildList {
        fun walk(node: FileNode) {
            add(node)
            node.children.forEach { walk(it) }
        }
        walk(this@FileNode)
    }
}
