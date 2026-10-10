package dev.opencode.android.feature.insights

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The usage dashboard's project filter, over a real server.
 *
 * **The project row is read from the active server's own list, and choosing a project re-queries with its id.**
 * What a screen shows after a choice is the server's answer to the query the choice made, so these tests read the
 * figures back after the real call has returned: a project's figures must replace the others, and going back to
 * every project must drop the filter from the request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var server: InsightsServer
    private lateinit var model: InsightsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        server = InsightsServer()
        server.answer("GET /api/project", PROJECTS)
        // The figures depend on the project in the query, so a choice that did not reach the server would show
        // the wrong numbers rather than just a different request.
        server.answer("GET /api/experimental/session/stats") { request ->
            if (request.url.queryParameter("project") == "prj_1") stats(sessions = 2) else stats(sessions = 4)
        }
        model = InsightsViewModel(MutableStateFlow(server.set))
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `the project row is the projects the active server knows, read from its set`() {
        assertTrue(model.state.value.projects.isEmpty())

        runBlocking { server.set.projects.sync() }
        settle("the project list") { model.state.value.projects.isNotEmpty() }

        assertEquals(listOf("prj_1", "prj_2"), model.state.value.projects.map { it.id })
        // The list was asked for once, by the set. The view model asked for nothing of its own.
        assertEquals(listOf("GET /api/project?"), server.requests)
    }

    @Test
    fun `choosing a project re-queries with its id and shows that project's figures`() {
        model.open()
        settle("the figures for every project") { model.state.value.stats?.sessions == 4L }

        model.setProject("prj_1")
        settle("the figures for prj_1") { model.state.value.stats?.sessions == 2L }

        assertEquals("prj_1", model.state.value.project)
        assertTrue(server.requests.last(), server.requests.last().contains("project=prj_1"))
        assertEquals(2, server.requests.count { it.startsWith(STATS) })
    }

    @Test
    fun `choosing all projects again drops the project from the query`() {
        model.open()
        settle("the figures for every project") { model.state.value.stats?.sessions == 4L }
        model.setProject("prj_1")
        settle("the figures for prj_1") { model.state.value.stats?.sessions == 2L }

        model.setProject(null)
        settle("the figures for every project again") { model.state.value.stats?.sessions == 4L }

        assertEquals(null, model.state.value.project)
        val last = server.requests.last()
        assertTrue(last, last.startsWith("$STATS?"))
        assertFalse(last, last.contains("project="))
    }

    @Test
    fun `choosing the project already selected sends no request`() {
        model.open()
        settle("the figures for every project") { model.state.value.stats?.sessions == 4L }
        model.setProject("prj_1")
        settle("the figures for prj_1") { model.state.value.stats?.sessions == 2L }

        model.setProject("prj_1")
        model.setProject(null)
        settle("the figures for every project again") { model.state.value.stats?.sessions == 4L }

        // Three requests and no fourth: the repeated choice was dropped before it reached the server. A
        // request for it would have been recorded before the one that answers the last choice.
        assertEquals(3, server.requests.count { it.startsWith(STATS) })
    }

    /** Runs the test scheduler and the real server until [condition] holds, and fails with what was seen if it never does. */
    private fun settle(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * NANOS_PER_MILLI
        while (true) {
            scheduler.advanceUntilIdle()
            if (condition()) return
            if (System.nanoTime() > deadline) {
                throw AssertionError(
                    "Timed out after ${TIMEOUT_MILLIS}ms waiting for: $what.\nState: ${model.state.value}\n" +
                        "Requests: ${server.requests}",
                )
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun stats(sessions: Int): String = """
        {"data":{
          "range":{"from":1000,"to":2000},
          "sessions":$sessions,"subagents":0,"prompts":0,"steps":0,
          "tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
          "cost":0.0,
          "tools":{"mode":"summary","totals":{"calls":0,"succeeded":0,"failed":0,"unfinished":0}},
          "activeDays":0,"streak":0,"activity":[],"models":[]
        }}
    """.trimIndent()

    private companion object {
        const val STATS = "GET /api/experimental/session/stats"
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 5L
        const val NANOS_PER_MILLI = 1_000_000L

        val PROJECTS = """
            [
              {"id":"prj_1","canonical":"/work/app","name":"app","time":{"created":1,"updated":1,"active":1},"sandboxes":[]},
              {"id":"prj_2","canonical":"/work/site","time":{"created":1,"updated":1,"active":1},"sandboxes":[]}
            ]
        """.trimIndent()
    }
}
