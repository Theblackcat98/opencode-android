package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDecodingTest {

    @Test
    fun decodesProjectsFixture() {
        val raw = Fixtures.raw("projects.json")
        val projects = OpenCodeJson.decodeFromString<List<Project>>(raw)

        assertFalse(projects.isEmpty())
        val project = projects.first()
        assertEquals("c42aa3ccc1e04f703386feb3e4f519d0bcd1d408", project.id)
        assertEquals("/home/nick/Documents/Projects/opencode-android", project.canonical)
        assertEquals("git", project.vcs)
        // Timestamps are per recording, so the contract is their order, not their value.
        assertTrue("a project is created before it is active", project.time.created > 0)
        assertTrue(project.time.updated >= project.time.created)
        assertTrue(project.time.active >= project.time.updated)
        assertEquals(emptyList<String>(), project.sandboxes)
    }

    @Test
    fun roundTripsProject() {
        val raw = Fixtures.raw("projects.json")
        val projects = OpenCodeJson.decodeFromString<List<Project>>(raw)
        val encoded = OpenCodeJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Project.serializer()),
            projects,
        )
        val decodedAgain = OpenCodeJson.decodeFromString<List<Project>>(encoded)

        assertEquals(projects, decodedAgain)
    }
}
