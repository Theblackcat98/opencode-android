package dev.opencode.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffPolicyTest {

    @Test
    fun doublesFromOneSecondAndStopsAtThirtySeconds() {
        val policy = BackoffPolicy(jitterRatio = 0.0, random = Random(1))
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)
        val actual = (1..8).map { policy.delayMillis(it) }
        assertEquals(expected, actual)
    }

    @Test
    fun jitterStaysWithinTheConfiguredRatio() {
        val policy = BackoffPolicy(jitterRatio = 0.2)
        repeat(500) {
            val delay = policy.delayMillis(3)
            val base = 4_000L
            assertTrue("jittered delay $delay left the band", delay in (base - 800L)..(base + 800L))
        }
    }

    @Test
    fun jitterNeverProducesANegativeDelay() {
        val policy = BackoffPolicy(jitterRatio = 1.0, random = Random(7))
        repeat(500) { assertTrue(policy.delayMillis(1) >= 0L) }
    }

    @Test
    fun attemptNumbersBelowOneAreRejected() {
        val policy = BackoffPolicy()
        runCatching { policy.delayMillis(0) }.also { assertTrue(it.isFailure) }
    }

    @Test
    fun aVeryHighAttemptNumberDoesNotOverflow() {
        val policy = BackoffPolicy(jitterRatio = 0.0)
        assertEquals(30_000L, policy.delayMillis(Int.MAX_VALUE))
    }
}
