package dev.opencode.android.core.data.execution

import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.model.SessionInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * One node of a session's family: the session, its children, and how deep it sits.
 *
 * **The tree is the whole answer to "where am I".** A subagent's session is not a list row among
 * dozens; it is a child of the session that started it, a sibling of the other children, and one hop
 * from a parent the user may be looking at from a completely different part of the app. Navigation
 * and the tree are the same problem, so [SessionTree] answers both from one structure rather than a
 * tree plus three hand-written walks.
 */
data class SessionNode(
    val row: SessionRow,
    val children: List<SessionNode>,
    val depth: Int,
) {
    val id: String get() = row.id
    val title: String get() = row.title

    /** Whether this session has a child that is still working, which is what the strip shows. */
    val hasRunningChild: Boolean get() = children.any { it.row.isRunning }

    /** This node and every node under it, in depth-first order. */
    fun flatten(): List<SessionNode> = buildList {
        add(this@SessionNode)
        children.forEach { addAll(it.flatten()) }
    }
}

/**
 * The session family tree, built from the flat session list (plan §6, "The session family tree").
 *
 * **Built from `parentID`, and total about the parents that are missing.** A `session.list` page is
 * paged and a child may be on a page this client never fetched; a session the server has moved may
 * name a parent that has been deleted. A builder that dropped the orphan would make a subagent's
 * transcript unreachable from anywhere, so an orphan becomes a root — it is still a real session the
 * user can open, and [orphans] says which ones it was.
 *
 * **A cycle is drawn, not swallowed.** `parentID` is server-set, but the list is client data before it
 * is anything else, and a cycle in it would otherwise be an unbounded walk — or, worse, a family that
 * renders as empty and makes a real session unreachable from anywhere. So a session whose parents lead
 * back to itself is a root, and the descent still carries the ids it has visited.
 */
object SessionTree {

    /** The tree, roots newest first, children newest first so the child a turn just started is on top. */
    fun build(rows: List<SessionRow>): List<SessionNode> {
        val ids = rows.mapTo(HashSet()) { it.id }
        val byParent = rows.groupBy { it.session.parentID }
        val parentOf: Map<String, String?> = rows.associate { it.id to it.session.parentID }
        val cyclic = rows.filter { isCyclic(it.id, parentOf) }.mapTo(HashSet()) { it.id }
        return rows
            .filter { row ->
                val parent = row.session.parentID
                parent == null || parent !in ids || row.id in cyclic
            }
            .sortedByDescending { it.updated }
            .map { root -> node(root, byParent, depth = 0, visited = mutableSetOf()) }
    }

    /**
     * Whether following [id]'s parents leads back to it.
     *
     * The walk carries the ids it has seen, so a longer cycle terminates as well as a short one, and a
     * chain that simply ends answers `false`.
     */
    private fun isCyclic(id: String, parentOf: Map<String, String?>): Boolean {
        val seen = mutableSetOf(id)
        var current = parentOf[id]
        while (current != null && current in parentOf) {
            if (current == id) return true
            if (!seen.add(current)) return true
            current = parentOf[current]
        }
        return false
    }

    private fun node(
        row: SessionRow,
        byParent: Map<String?, List<SessionRow>>,
        depth: Int,
        visited: MutableSet<String>,
    ): SessionNode {
        if (!visited.add(row.id)) return SessionNode(row, emptyList(), depth)
        // A child that has already been visited is dropped rather than drawn as a leaf: in a cycle it
        // would put the same session on screen twice, and one row per session is what a `LazyColumn`'s
        // keys are for.
        val children = byParent[row.id]
            .orEmpty()
            .filter { it.id != row.id && it.id !in visited }
            .sortedByDescending { it.updated }
            .map { child -> node(child, byParent, depth + 1, visited) }
        return SessionNode(row, children, depth)
    }

    /**
     * The direct children of [id], newest first, from the flat list rather than the tree.
     *
     * A strip of running subagents cannot wait for a page: the child that just started is the reason
     * the strip exists, and it is the one least likely to be in a list read a minute ago.
     */
    fun childrenOf(rows: List<SessionRow>, id: String): List<SessionRow> = rows
        .filter { it.session.parentID == id }
        .sortedByDescending { it.updated }

    /** The children of [id] that are working, which is what the composer's strip shows. */
    fun runningChildrenOf(rows: List<SessionRow>, id: String): List<SessionRow> = childrenOf(rows, id)
        .filter { it.activity is SessionActivity.Running || it.activity is SessionActivity.Retrying }

    /** The chain from a session up to its root, nearest first; empty for a root. */
    fun ancestorsOf(rows: List<SessionRow>, id: String): List<SessionRow> {
        val byId = rows.associateBy { it.id }
        val chain = mutableListOf<SessionRow>()
        // Seeded with the starting id, so a cycle stops when it comes back round rather than returning
        // every session in it.
        val seen = mutableSetOf(id)
        var current = byId[id]?.session?.parentID
        while (current != null && seen.add(current)) {
            val parent = byId[current] ?: break
            chain += parent
            current = parent.session.parentID
        }
        return chain
    }

    /**
     * The next or previous session among the siblings of [id].
     *
     * Siblings are ordered the way the tree draws them, so "next" means "the one below it on
     * screen" — which is what the TUI's next and previous child do, and what a user pressing the
     * arrow twice expects. The answer is `null` at either end rather than a wrap, because wrapping
     * silently would make a held-down key travel through the whole tree.
     */
    fun sibling(rows: List<SessionRow>, id: String, delta: Int): SessionRow? {
        val ordered = rows
            .filter { it.session.parentID == rows.firstOrNull { row -> row.id == id }?.session?.parentID }
            .sortedByDescending { it.updated }
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return null
        return ordered.getOrNull(index + delta)
    }

    /** Sessions whose parent this client has never seen, which is what makes a family incomplete. */
    fun orphans(rows: List<SessionRow>): List<SessionRow> {
        val known = rows.mapTo(HashSet()) { it.id }
        return rows.filter { row -> row.session.parentID != null && row.session.parentID !in known }
    }

    /**
     * The session a subagent card names, or `null` when this client does not know it.
     *
     * The existence check is the point: a card that navigated to a session id the server has since
     * removed would open a screen that can only show an error, and "the child is gone" is a better
     * answer than an empty timeline.
     */
    fun childNamedBy(rows: List<SessionRow>, sessionID: String): SessionRow? {
        if (sessionID.isBlank()) return null
        return rows.firstOrNull { it.id == sessionID }
    }
}

/**
 * The subagent tool card's link out (plan §6, "Subagent tool cards open the child session").
 *
 * The card carries `metadata.sessionID` and nothing else, so this is where the id is read, and it is
 * read from the metadata rather than guessed from the tool's input. [resolve] returns the row, not
 * the id, so a caller cannot navigate to a session the client has never seen.
 */
object SubagentLink {

    /** The `metadata.sessionID` a subagent tool call carries (features doc §5, tool metadata). */
    const val METADATA_KEY: String = "sessionID"

    /**
     * The child session a card opens, or `null` when the card names none this client can use.
     *
     * @param rows every session the client knows, for the existence check
     * @param metadata the tool state's `metadata`, which is where the id lives
     */
    fun resolve(rows: List<SessionRow>, metadata: Map<String, JsonElement>?): SessionRow? {
        val named = (metadata?.get(METADATA_KEY) as? JsonPrimitive)?.content ?: return null
        return SessionTree.childNamedBy(rows, named)
    }
}

/** Whether a session is a subagent or a background command, which is `parentID != null`. */
val SessionInfo.isSubagent: Boolean get() = parentID != null
