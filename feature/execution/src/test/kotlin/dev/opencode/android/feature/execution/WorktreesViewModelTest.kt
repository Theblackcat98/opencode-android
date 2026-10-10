package dev.opencode.android.feature.execution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A refused worktree create keeps what the user typed, shows the server's reason, and is dismissable.
 *
 * Issue 7. The view model, the read model and the `ServerApi` are the real ones over a `MockWebServer`
 * ([ExecutionServer]), so the refusal is the server's own `WorktreeError` envelope and the drafts are read
 * back after the real call has returned.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorktreesViewModelTest {

    private val directory = "/work/app"
    private val scheduler = TestCoroutineScheduler()
    private lateinit var server: ExecutionServer
    private lateinit var model: WorktreesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        server = ExecutionServer(directory)
        server.answer("GET /api/worktree", "[]")
        model = WorktreesViewModel(MutableStateFlow(server.set))
        model.open("prj_1")
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a refused create keeps the three drafts, shows the server's reason, and can be dismissed`() {
        server.answer(
            "POST /api/worktree",
            """{"name":"WorktreeError","data":{"message":"branch feature/x does not exist"}}""",
            status = 400,
        )
        model.setFrom("feature/x")
        model.setBranch("feature/y")
        model.setName("spike")

        model.create()
        settle("the refusal to arrive") { model.state.value.error != null }

        assertEquals("branch feature/x does not exist", model.state.value.error)
        assertEquals("feature/x", model.state.value.draftFrom)
        assertEquals("feature/y", model.state.value.draftBranch)
        assertEquals("spike", model.state.value.draftName)
        assertFalse(model.state.value.creating)
        assertEquals(
            """{"projectID":"prj_1","from":"feature/x","branch":"feature/y","name":"spike"}""",
            server.lastBody("/api/worktree", method = "POST"),
        )

        model.dismissError()

        assertNull(model.state.value.error)
        assertEquals("spike", model.state.value.draftName)
    }

    @Test
    fun `an accepted create clears the drafts and names the directory it made`() {
        server.answer("POST /api/worktree", """{"directory":"/work/.wt/spike"}""")
        model.setFrom("main")
        model.setName("spike")

        model.create()
        settle("the creation to be accepted") { model.state.value.notice != null }

        assertEquals("/work/.wt/spike", model.state.value.notice)
        assertNull(model.state.value.error)
        assertEquals("", model.state.value.draftFrom)
        assertEquals("", model.state.value.draftName)
    }

    /** Lets the view model's coroutines and the MockWebServer's thread run until [condition] holds. */
    private fun settle(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * NANOS_PER_MILLI
        while (!condition()) {
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

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 5L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
