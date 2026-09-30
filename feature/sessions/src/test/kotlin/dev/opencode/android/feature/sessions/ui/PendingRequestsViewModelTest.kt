package dev.opencode.android.feature.sessions.ui

import dev.opencode.android.core.data.server.ServerDataSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The home badge, the global inbox and the delete confirmation follow their stores.
 *
 * Each of them used to read its store inside a `map` on the active data set, which answers once, when the
 * server becomes active. A permission that arrived while the badge was on screen, or a subagent the
 * server started while the confirmation was open, was never shown until the screen was left and entered
 * again — and the badge is the one signal that a permission is blocking the agent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingRequestsViewModelTest {

    private val data = DataSetFixtures()
    private val set: ServerDataSet get() = data.set

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a permission that arrives while the badge is on screen is counted and leaves when answered`() = runTest {
        val requests = PendingRequestsViewModel(MutableStateFlow(set))
        backgroundScope.launch(Dispatchers.Unconfined) { requests.pending.collect { } }
        assertTrue(requests.pending.value.isEmpty())

        set.apply(
            data.event("permission.asked", """{"id":"per_1","sessionID":"ses_1","action":"bash","resources":[]}"""),
        )
        assertEquals(
            listOf("per_1"),
            requests.pending.await("the permission to reach the badge") { it.isNotEmpty() }.map { it.id },
        )

        set.apply(
            data.event("permission.replied", """{"sessionID":"ses_1","requestID":"per_1","reply":"once"}"""),
        )
        requests.pending.await("the answered permission to leave the badge") { it.isEmpty() }
    }

    @Test
    fun `a session that is created or renamed while the inbox is open is named in it`() = runTest {
        val requests = PendingRequestsViewModel(MutableStateFlow(set))
        backgroundScope.launch(Dispatchers.Unconfined) { requests.sessionTitles.collect { } }
        assertTrue(requests.sessionTitles.value.isEmpty())

        set.apply(data.sessionCreated("ses_1", title = "Fix the reconnect"))
        assertEquals(
            "Fix the reconnect",
            requests.sessionTitles.await("the new session to be named") { "ses_1" in it }.getValue("ses_1"),
        )

        set.apply(data.event("session.renamed", """{"sessionID":"ses_1","title":"Fix the resync"}"""))
        assertEquals(
            "Fix the resync",
            requests.sessionTitles.await("the rename to reach the inbox") { it["ses_1"] == "Fix the resync" }
                .getValue("ses_1"),
        )
    }

    @Test
    fun `the delete confirmation counts a subagent that starts while it is open`() = runTest {
        val management = SessionManagementViewModel(MutableStateFlow(set))
        management.openSession("ses_1")
        backgroundScope.launch(Dispatchers.Unconfined) { management.childCount.collect { } }
        set.apply(data.sessionCreated("ses_1"))
        assertEquals(0, management.childCount.value)

        set.apply(data.sessionCreated("ses_2", parentID = "ses_1"))

        assertEquals(1, management.childCount.await("the subagent to be counted") { it == 1 })
    }
}
