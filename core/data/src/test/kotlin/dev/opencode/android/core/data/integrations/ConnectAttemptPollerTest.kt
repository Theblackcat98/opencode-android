package dev.opencode.android.core.data.integrations

import dev.opencode.android.core.model.AttemptWindow
import dev.opencode.android.core.model.CommandAttemptStatus
import dev.opencode.android.core.model.OAuthAttemptStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The `mode=auto` polling loop, driven against a virtual clock and a scripted server.
 *
 * **Every wait is bounded and every exit is asserted.** The loop has three ways out — a terminal
 * status, a cancel, and the poll budget — and each of them is exercised here with an explicit
 * `advanceUntilIdle` and a post-condition. That is deliberate: a test that waits for a `StateFlow` to
 * reach a value with no timeout is the exact trap a previous phase fell into, where a terminal whose
 * `when` had a dead branch left the test waiting for a value that never came.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectAttemptPollerTest {

    private val window = AttemptWindow(created = 0.0, expires = 600_000.0)

    @Test
    fun `a pending status keeps the attempt polling and never ends it`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = ConnectAttemptPoller(intervalMillis = 100, maxPolls = 5)
        var polls = 0

        poller.startOauth(
            scope = scope,
            attemptID = "att_1",
            read = {
                polls++
                OAuthAttemptStatus.Pending(window)
            },
        )
        advanceUntilIdle()

        assertEquals(5, polls)
        assertEquals(5, poller.progress.polls)
        // The budget ran out rather than a terminal state, so the loop says so itself instead of
        // leaving the sheet spinning forever.
        assertEquals(ConnectAttemptState.Expired, poller.progress.state)
        assertTrue(poller.progress.isTerminal)
    }

    @Test
    fun `a complete status ends the loop on the first poll that says so`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = ConnectAttemptPoller(intervalMillis = 100, maxPolls = 100)
        val seen = mutableListOf<ConnectAttemptState>()
        var polls = 0

        poller.startOauth(
            scope = scope,
            attemptID = "att_1",
            read = {
                polls++
                if (polls <
                    3
                ) {
                    OAuthAttemptStatus.Pending(window)
                } else {
                    OAuthAttemptStatus.Complete(window)
                }
            },
            onState = { seen += it.state },
        )
        advanceUntilIdle()

        assertEquals(3, polls)
        assertEquals(ConnectAttemptState.Complete, poller.progress.state)
        // Exactly one terminal transition, and no poll after it: a loop that kept asking would be a
        // request a minute for a login nobody is waiting on any more.
        assertEquals(1, seen.count { it == ConnectAttemptState.Complete })
    }

    @Test
    fun `a failed status carries the server's own reason`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = ConnectAttemptPoller(intervalMillis = 100, maxPolls = 10)

        poller.startOauth(
            scope = scope,
            attemptID = "att_1",
            read = { OAuthAttemptStatus.Failed("the provider refused the grant", window) },
        )
        advanceUntilIdle()

        assertEquals(ConnectAttemptState.Failed("the provider refused the grant"), poller.progress.state)
        assertEquals(1, poller.progress.polls)
    }

    @Test
    fun `an expired status ends the loop`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = ConnectAttemptPoller(intervalMillis = 100, maxPolls = 10)

        poller.startOauth(
            scope = scope,
            attemptID = "att_1",
            read = { OAuthAttemptStatus.Expired(window) },
        )
        advanceUntilIdle()

        assertEquals(ConnectAttemptState.Expired, poller.progress.state)
    }

    @Test
    fun `an attempt the server no longer has ends rather than polling forever`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = ConnectAttemptPoller(intervalMillis = 100, maxPolls = 50)
        var polls = 0

        // `null` is what the store answers for a 404: the attempt is gone. Polling it would never
        // produce anything else.
        poller.startOauth(scope, "att_gone", read = {
            polls++
            null
        })
        advanceUntilIdle()

        assertEquals(1, polls)
        assertEquals(ConnectAttemptState.Expired, poller.progress.state)
    }

    @Test
    fun `a transport failure does not end the attempt and the loop recovers`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = ConnectAttemptPoller(intervalMillis = 100, maxPolls = 10)
        val seen = mutableListOf<ConnectAttemptState>()
        var polls = 0

        poller.startOauth(
            scope = scope,
            attemptID = "att_1",
            read = {
                polls++
                // The first two attempts are the phone losing Wi-Fi mid-consent, which is the common
                // case and must not tell the user their login failed.
                if (polls <= 2) throw IOException("connection reset") else OAuthAttemptStatus.Complete(window)
            },
            onState = { seen += it.state },
        )
        advanceUntilIdle()

        assertEquals(ConnectAttemptState.Complete, poller.progress.state)
        assertEquals(2, seen.count { it is ConnectAttemptState.Unreachable })
    }

    @Test
    fun `cancelling stops the loop at once and is a terminal state of its own`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = ConnectAttemptPoller(intervalMillis = 100, maxPolls = 100)
        var polls = 0

        poller.startOauth(scope, "att_1", read = {
            polls++
            OAuthAttemptStatus.Pending(window)
        })
        advanceTimeBy(150)
        val before = polls
        poller.cancel()
        advanceUntilIdle()

        assertEquals(ConnectAttemptState.Cancelled, poller.progress.state)
        assertTrue(poller.progress.isTerminal)
        // No poll happened after the cancel. A cancel that keeps asking is a login the user cannot
        // get rid of, which is the opposite of what Cancel means.
        assertEquals(before, polls)
    }

    @Test
    fun `a command attempt accumulates its output across polls`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = ConnectAttemptPoller(intervalMillis = 100, maxPolls = 4)
        val pages = listOf(
            "Open https://github.com/login/device",
            "Enter code: WDJB-MJHT-2K9P",
            "Waiting for you to sign in",
            "Waiting for you to sign in",
        )
        var index = 0

        poller.startCommand(
            scope = scope,
            attemptID = "att_3",
            read = { CommandAttemptStatus.Pending(pages[index++].takeIf { index <= pages.size }, window) },
        )
        advanceUntilIdle()

        // Every distinct line is kept, so the code the user has to type does not scroll away.
        assertTrue(poller.progress.output.contains("Enter code: WDJB-MJHT-2K9P"))
        assertTrue(poller.progress.output.contains("Waiting for you to sign in"))
        // The identical repeat is dropped rather than filling the panel with sixty copies.
        assertEquals(1, poller.progress.output.split("Waiting for you to sign in").size - 1)
    }

    @Test
    fun `a command attempt that completes stops polling and keeps what it printed`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = ConnectAttemptPoller(intervalMillis = 100, maxPolls = 50)
        var polls = 0

        poller.startCommand(
            scope = scope,
            attemptID = "att_3",
            read = {
                polls++
                if (polls < 2) {
                    CommandAttemptStatus.Pending("Authorised", window)
                } else {
                    CommandAttemptStatus.Complete(window)
                }
            },
        )
        advanceUntilIdle()

        assertEquals(ConnectAttemptState.Complete, poller.progress.state)
        assertTrue(poller.progress.output.contains("Authorised"))
        assertEquals(2, polls)
    }

    @Test
    fun `a command attempt that fails reports the host's exit reason`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = ConnectAttemptPoller(intervalMillis = 100, maxPolls = 10)

        poller.startCommand(
            scope = scope,
            attemptID = "att_3",
            read = { CommandAttemptStatus.Failed("gh: not authenticated", window) },
        )
        advanceUntilIdle()

        assertEquals(ConnectAttemptState.Failed("gh: not authenticated"), poller.progress.state)
        assertTrue(poller.progress.output.contains("gh: not authenticated"))
    }

    @Test
    fun `an unknown status is not terminal, so a new server status cannot end a login`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = ConnectAttemptPoller(intervalMillis = 100, maxPolls = 3)

        poller.startOauth(
            scope = scope,
            attemptID = "att_1",
            read = {
                OAuthAttemptStatus.Unknown(
                    "awaiting-review",
                    kotlinx.serialization.json.JsonObject(emptyMap()),
                )
            },
        )
        advanceUntilIdle()

        // Unknown is folded to Pending; the budget, not the status, is what ends it.
        assertEquals(ConnectAttemptState.Expired, poller.progress.state)
        assertEquals(3, poller.progress.polls)
    }

    @Test
    fun `starting a second attempt replaces the first rather than leaving it polling`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = ConnectAttemptPoller(intervalMillis = 100, maxPolls = 100)
        val attempts = mutableListOf<String>()

        poller.startOauth(scope, "att_1", read = {
            attempts += "att_1"
            OAuthAttemptStatus.Pending(window)
        })
        advanceTimeBy(150)
        poller.startOauth(scope, "att_2", read = {
            attempts += "att_2"
            OAuthAttemptStatus.Complete(window)
        })
        advanceUntilIdle()

        assertEquals("att_2", poller.progress.attemptID)
        // Nothing polled the first attempt after the second started. Two logins polling at once is a
        // bug a user would only see as a battery complaint.
        assertFalse(attempts.contains("att_1") && attempts.lastIndexOf("att_1") > attempts.indexOf("att_2"))
    }

    @Test
    fun `the default budget is long enough for a real consent flow and the interval is not a hot loop`() {
        // These are numbers a test pins so a later "tune the polling" change cannot quietly turn a
        // two-second poll into a hundred-millisecond one, which is what a metered phone notices.
        assertEquals(2_000L, ConnectAttemptPoller.DEFAULT_INTERVAL_MILLIS)
        assertEquals(1_000, ConnectAttemptPoller.DEFAULT_MAX_POLLS)
        // Roughly half an hour of polling, which covers a slow provider plus a browser sign-in.
        val minutes = ConnectAttemptPoller.DEFAULT_INTERVAL_MILLIS * ConnectAttemptPoller.DEFAULT_MAX_POLLS / 60_000
        assertTrue("the budget is only $minutes minutes", minutes > 20)
    }

    @Test
    fun `every state is classified as running or terminal, and nothing is both or neither`() {
        // A screen switches on `isRunning` to show a spinner and on `isTerminal` to stop one. A
        // state that is neither would leave the row with no branch at all, which is the dead-`when`
        // bug a previous phase shipped as a terminal whose input never reached the socket.
        val states = listOf(
            ConnectAttemptState.Idle,
            ConnectAttemptState.Pending,
            ConnectAttemptState.Complete,
            ConnectAttemptState.Failed("x"),
            ConnectAttemptState.Expired,
            ConnectAttemptState.Cancelled,
            ConnectAttemptState.Unreachable("y"),
        )
        states.forEach { state ->
            val running = state.isRunning
            val terminal = state.isTerminal
            val decided = running || terminal || state == ConnectAttemptState.Idle
            assertTrue("$state is neither running nor terminal nor idle", decided)
            assertTrue("$state is both running and terminal", !(running && terminal))
        }
        // And the classifications themselves, so a change to `isRunning` is visible here.
        assertFalse(ConnectAttemptState.Idle.isRunning)
        assertTrue(ConnectAttemptState.Pending.isRunning)
        // A dropped connection is *not* terminal: the loop keeps going, so the row keeps polling.
        assertTrue(ConnectAttemptState.Unreachable("x").isRunning)
        assertFalse(ConnectAttemptState.Unreachable("x").isTerminal)
        assertTrue(ConnectAttemptState.Cancelled.isTerminal)
        assertFalse(ConnectAttemptState.Cancelled.isRunning)
    }
}
