package dev.opencode.android.feature.review

import dev.opencode.android.core.data.review.ReviewScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReviewLoadGenerationTest {
    @Test
    fun `late response for scope A cannot replace UI state for scope B`() = runTest {
        val gate = ReviewLoadBinding()
        val owner = Any()
        var uiScope: ReviewScope = ReviewScope.Uncommitted
        val scopeA = ReviewScope.Committed
        val scopeB = ReviewScope.All
        val a = gate.begin(owner, "session-a", scopeA, null, "/repo")
        val delayedA = async {
            delay(100)
            if (gate.isCurrent(a, owner, "session-a", scopeA, null, "/repo")) uiScope = scopeA
        }
        gate.begin(owner, "session-b", scopeB, null, "/repo")
        uiScope = scopeB
        delayedA.await()

        assertEquals(scopeB, uiScope)
        assertFalse(gate.isCurrent(a, owner, "session-a", scopeA, null, "/repo"))
    }
}
