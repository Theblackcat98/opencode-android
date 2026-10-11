package dev.opencode.android.feature.sessions.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home's groups, the Settings scope and the Chat tab's choice are pure functions of what the stores hold. */
class HomeStateTest {

    private val projects = TimelineFixtures.projects()
    private val rows = TimelineFixtures.homeRows()

    @Test
    fun onlyAddedProjectsAreGroupedInTheOrderTheyWereAdded() {
        val groups = homeGroups(projects, listOf("project-2", "project-1"), rows)

        assertEquals(listOf("project-2", "project-1"), groups.map { it.project.id })
        assertEquals(listOf("ses_other"), groups[0].sessions.map { it.id })
    }

    @Test
    fun aProjectThatWasNotAddedIsNotOnTheHome() {
        val groups = homeGroups(projects, listOf("project-2"), rows)

        assertEquals(listOf("project-2"), groups.map { it.project.id })
    }

    @Test
    fun anAddedProjectTheServerNoLongerListsIsDropped() {
        val groups = homeGroups(projects, listOf("gone", "project-1"), rows)

        assertEquals(listOf("project-1"), groups.map { it.project.id })
    }

    @Test
    fun subagentsAreLeftOutAndTheNewestComeFirst() {
        val group = homeGroups(projects, listOf("project-1"), rows, limit = 2).single()

        assertEquals(listOf("ses_running", "ses_retry"), group.sessions.map { it.id })
        assertEquals("every root session is counted, not only the ones shown", 4, group.total)
        assertEquals("running and retrying both count as running", 2, group.running)
        assertTrue(group.sessions.none { it.isChild })
    }

    @Test
    fun settingsScopesToAddedProjectsAndFallsBackToEveryOne() {
        assertEquals(listOf("project-2"), settingsProjects(projects, listOf("project-2")).map { it.id })
        assertEquals(2, settingsProjects(projects, emptyList()).size)
    }

    @Test
    fun chatOpensTheLastSessionUntilTheServerSaysItIsGone() {
        assertEquals(ChatTarget.Open("ses_read"), chatTarget("ses_read", rows, loaded = true))
        assertEquals("trusted before the list loads", ChatTarget.Open("deleted"), chatTarget("deleted", emptyList(), loaded = false))
        assertEquals(
            "a deleted last session falls through to the newest root",
            ChatTarget.Open("ses_running"),
            chatTarget("deleted", rows, loaded = true),
        )
    }

    @Test
    fun chatWithNothingToOpenIsEmptyOnlyOnceLoaded() {
        assertEquals(ChatTarget.Loading, chatTarget(null, emptyList(), loaded = false))
        assertEquals(ChatTarget.Empty, chatTarget(null, emptyList(), loaded = true))
    }
}
