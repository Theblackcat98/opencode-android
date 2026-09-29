package dev.opencode.android.core.data.execution

import dev.opencode.android.core.data.server.SessionActivity
import dev.opencode.android.core.data.server.SessionRow
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.TokenUsage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session family tree, and the subagent card's link out (plan §6, "Subagents").
 *
 * **These are the tests that make "a subagent can be watched" a statement about code.** The tree is a
 * pure function of the session rows, so everything interesting — orphans, cycles, siblings, a child on
 * a page this client never fetched — is decided here rather than on a device.
 */
class SessionTreeTest {

    @Test
    fun `a child is nested under its parent`() {
        val rows = listOf(row("ses_parent"), row("ses_child", parent = "ses_parent"))
        val tree = SessionTree.build(rows)
        assertEquals(1, tree.size)
        assertEquals("ses_parent", tree.single().id)
        assertEquals(listOf("ses_child"), tree.single().children.map { it.id })
        assertEquals(1, tree.single().children.single().depth)
    }

    @Test
    fun `roots are newest first and children are newest first`() {
        val rows = listOf(
            row("ses_a", updated = 10),
            row("ses_b", updated = 30),
            row("ses_child_old", parent = "ses_b", updated = 5),
            row("ses_child_new", parent = "ses_b", updated = 40),
        )
        val tree = SessionTree.build(rows)
        assertEquals(listOf("ses_b", "ses_a"), tree.map { it.id })
        assertEquals(listOf("ses_child_new", "ses_child_old"), tree.first().children.map { it.id })
    }

    @Test
    fun `an orphan becomes a root rather than disappearing`() {
        // The parent is not in this client's pages, or was deleted on the server. Either way the child
        // is a real session the user can open, so it is drawn at the top rather than dropped.
        val rows = listOf(row("ses_orphan", parent = "ses_missing"))
        val tree = SessionTree.build(rows)
        assertEquals(listOf("ses_orphan"), tree.map { it.id })
        assertEquals(listOf("ses_orphan"), SessionTree.orphans(rows).map { it.id })
    }

    @Test
    fun `a session that is its own parent is a root with no children`() {
        val rows = listOf(row("ses_loop", parent = "ses_loop"))
        val tree = SessionTree.build(rows)
        assertEquals(1, tree.size)
        assertEquals(emptyList<String>(), tree.single().children.map { it.id })
    }

    @Test
    fun `a two-session cycle is drawn rather than swallowed`() {
        val rows = listOf(row("ses_a", parent = "ses_b"), row("ses_b", parent = "ses_a"))
        val tree = SessionTree.build(rows)
        // A family that renders as empty would make two real sessions unreachable from anywhere, so
        // both are roots. The descent still stops: a node reached a second time becomes a leaf, so the
        // drawn tree is bounded by the number of sessions.
        assertEquals(setOf("ses_a", "ses_b"), tree.map { it.id }.toSet())
        val flat = tree.flatMap { it.flatten() }
        assertEquals(setOf("ses_a", "ses_b"), flat.map { it.id }.toSet())
        // Two sessions, four drawn nodes at most: a node reached a second time becomes a leaf, so the
        // tree is bounded by the list whatever the list claims about parentage.
        assertTrue("drew ${flat.size} nodes", flat.size <= 2 * rows.size)
    }

    @Test
    fun `children are the direct ones only`() {
        val rows = listOf(
            row("ses_p"),
            row("ses_c", parent = "ses_p"),
            row("ses_g", parent = "ses_c"),
        )
        assertEquals(listOf("ses_c"), SessionTree.childrenOf(rows, "ses_p").map { it.id })
        assertEquals(listOf("ses_g"), SessionTree.childrenOf(rows, "ses_c").map { it.id })
    }

    @Test
    fun `running children are the working ones`() {
        val rows = listOf(
            row("ses_p"),
            row("ses_running", parent = "ses_p", activity = SessionActivity.Running),
            row("ses_retrying", parent = "ses_p", activity = SessionActivity.Retrying(1, 0, "boom")),
            row("ses_idle", parent = "ses_p"),
        )
        assertEquals(
            listOf("ses_running", "ses_retrying"),
            SessionTree.runningChildrenOf(rows, "ses_p").map { it.id },
        )
    }

    @Test
    fun `ancestors walk to the root and stop on a cycle`() {
        val rows = listOf(
            row("ses_p"),
            row("ses_c", parent = "ses_p"),
            row("ses_g", parent = "ses_c"),
        )
        assertEquals(listOf("ses_c", "ses_p"), SessionTree.ancestorsOf(rows, "ses_g").map { it.id })
        assertEquals(emptyList<String>(), SessionTree.ancestorsOf(rows, "ses_p").map { it.id })

        val cyclic = listOf(row("ses_a", parent = "ses_b"), row("ses_b", parent = "ses_a"))
        assertEquals(listOf("ses_b"), SessionTree.ancestorsOf(cyclic, "ses_a").map { it.id })
    }

    @Test
    fun `siblings move within the parent's order and stop at the ends`() {
        val rows = listOf(
            row("ses_p"),
            row("ses_1", parent = "ses_p", updated = 30),
            row("ses_2", parent = "ses_p", updated = 20),
            row("ses_3", parent = "ses_p", updated = 10),
        )
        assertEquals("ses_2", SessionTree.sibling(rows, "ses_1", 1)?.id)
        assertEquals("ses_1", SessionTree.sibling(rows, "ses_2", -1)?.id)
        assertNull(SessionTree.sibling(rows, "ses_3", 1))
        assertNull(SessionTree.sibling(rows, "ses_1", -1))
        // A sibling of a root is another root, and an unknown id has no siblings at all.
        assertNull(SessionTree.sibling(rows, "ses_unknown", 1))
    }

    @Test
    fun `a subagent card opens the child it names`() {
        val rows = listOf(row("ses_p"), row("ses_child", parent = "ses_p"))
        val metadata = mapOf(SubagentLink.METADATA_KEY to JsonPrimitive("ses_child"))
        assertEquals("ses_child", SubagentLink.resolve(rows, metadata)?.id)
    }

    @Test
    fun `a subagent card naming a session this client does not know opens nothing`() {
        val rows = listOf(row("ses_p"))
        val metadata = mapOf(SubagentLink.METADATA_KEY to JsonPrimitive("ses_gone"))
        assertNull(SubagentLink.resolve(rows, metadata))
    }

    @Test
    fun `a subagent card with no metadata opens nothing`() {
        val rows = listOf(row("ses_p"), row("ses_child", parent = "ses_p"))
        assertNull(SubagentLink.resolve(rows, null))
        assertNull(SubagentLink.resolve(rows, emptyMap()))
        assertNull(SubagentLink.resolve(rows, mapOf(SubagentLink.METADATA_KEY to JsonPrimitive(""))))
    }

    @Test
    fun `flatten returns the node then its descendants`() {
        val rows = listOf(
            row("ses_p"),
            row("ses_c", parent = "ses_p"),
            row("ses_g", parent = "ses_c"),
        )
        assertEquals(
            listOf("ses_p", "ses_c", "ses_g"),
            SessionTree.build(rows).single().flatten().map { it.id },
        )
    }

    private fun row(
        id: String,
        parent: String? = null,
        updated: Long = 0,
        activity: SessionActivity = SessionActivity.Idle,
    ) = SessionRow(
        session = SessionInfo(
            id = id,
            parentID = parent,
            projectID = "prj",
            cost = 0.0,
            tokens = TokenUsage.Zero,
            time = SessionInfo.Time(created = 0, updated = updated),
            location = LocationPublicRef("/work"),
        ),
        activity = activity,
        status = null,
        childCount = 0,
    )
}
