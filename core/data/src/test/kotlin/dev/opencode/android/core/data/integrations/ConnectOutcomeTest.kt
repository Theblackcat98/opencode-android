package dev.opencode.android.core.data.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every poll state has a stated consequence, and the terminal ones differ from each other.
 *
 * **This is the test a view model's `when` would otherwise not have.** Phase 7 shipped a terminal
 * whose branches all but one were `Unit`, so a real input path was dead and the suite was green.
 * The decision is here rather than in a composable so that the whole mapping is enumerable, and
 * this file is the enumeration.
 */
class ConnectOutcomeTest {

    /** Every state the union has, spelled out so a new one fails this file. */
    private val everyState = listOf(
        ConnectAttemptState.Idle,
        ConnectAttemptState.Pending,
        ConnectAttemptState.Unreachable("the network dropped"),
        ConnectAttemptState.Complete,
        ConnectAttemptState.Failed("the provider refused"),
        ConnectAttemptState.Expired,
        ConnectAttemptState.Cancelled,
    )

    @Test
    fun `every state maps to an outcome, and no state is left to an unhandled branch`() {
        val expected = mapOf(
            ConnectAttemptState.Idle to ConnectOutcome.KEEP_WAITING,
            ConnectAttemptState.Pending to ConnectOutcome.KEEP_WAITING,
            ConnectAttemptState.Unreachable("the network dropped") to ConnectOutcome.KEEP_WAITING,
            ConnectAttemptState.Complete to ConnectOutcome.REFRESH_AND_CLOSE,
            ConnectAttemptState.Failed("the provider refused") to ConnectOutcome.SHOW_FAILURE,
            ConnectAttemptState.Expired to ConnectOutcome.SHOW_EXPIRY,
            ConnectAttemptState.Cancelled to ConnectOutcome.STAY_CLOSED,
        )
        // Every state in the union is in the map. A state added without one is a compile error in
        // `outcome()`, and a state added to the union without a test entry fails here.
        assertEquals(everyState.size, expected.size)
        assertEquals("a state in the union has no test entry", emptyList<Any>(), everyState - expected.keys.toList())
        expected.forEach { (state, outcome) ->
            assertEquals("$state", outcome, state.outcome())
        }
    }

    @Test
    fun `a success is the only outcome that re-reads the catalog`() {
        // The credential list is the server's answer, never a local addition: a client that added a
        // row on success would show a login the server may have stored under a different label.
        val reloading = everyState.filter { it.outcome() == ConnectOutcome.REFRESH_AND_CLOSE }
        assertEquals(1, reloading.size)
        assertEquals(ConnectAttemptState.Complete, reloading.single())
    }

    @Test
    fun `a failure keeps the sheet open and a cancel does not`() {
        // "Try again" is the useful next action after a refusal, and a cancel has already said what
        // the user wanted, so the two must not be reported the same way.
        // "KEEP the sheet open" here means "do not dismiss it", not "the flow is not over": the
        // attempt is over, and what the sheet does with it is what differs.
        assertEquals(ConnectOutcome.SHOW_FAILURE, ConnectAttemptState.Failed("x").outcome())
        assertEquals(ConnectOutcome.STAY_CLOSED, ConnectAttemptState.Cancelled.outcome())
        assertFalse(ConnectOutcome.SHOW_FAILURE == ConnectOutcome.STAY_CLOSED)
        assertTrue(ConnectAttemptState.Failed("x").outcome().isTerminal)
    }

    @Test
    fun `a dropped connection is not a failure the user is shown`() {
        // This is the one that would be most damaging to get wrong: a phone losing Wi-Fi mid-consent
        // is ordinary, and telling the user their login failed would send them back to the provider
        // to do it again.
        val outcome = ConnectAttemptState.Unreachable("connection reset").outcome()
        assertEquals(ConnectOutcome.KEEP_WAITING, outcome)
        assertFalse(outcome.isTerminal)
    }

    @Test
    fun `an expiry is reported as an expiry rather than as a generic failure`() {
        // The two need different words, and the value exists so the UI cannot collapse them.
        assertEquals(ConnectOutcome.SHOW_EXPIRY, ConnectAttemptState.Expired.outcome())
        assertTrue(ConnectAttemptState.Expired.outcome().isTerminal)
        assertTrue(ConnectAttemptState.Failed("x").outcome().isTerminal)
    }
}
