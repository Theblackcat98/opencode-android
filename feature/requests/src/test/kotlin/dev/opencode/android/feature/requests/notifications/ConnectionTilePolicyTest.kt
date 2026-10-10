package dev.opencode.android.feature.requests.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What the Quick Settings tile shows and what a tap does (matrix J9).
 *
 * **A plain function, so it runs without a device.** The tile service only maps the result onto the platform's
 * tile states, so these assertions are the whole rule: lit when the service runs and a tap stops it, unlit when
 * it does not and a tap starts it.
 */
class ConnectionTilePolicyTest {

    @Test
    fun `a running service lights the tile and a tap stops it`() {
        val decision = ConnectionTilePolicy.decide(serviceRunning = true)
        assertEquals(ConnectionTileDecision(active = true, tap = ConnectionTileTap.STOP), decision)
    }

    @Test
    fun `a stopped service leaves the tile unlit and a tap starts it`() {
        val decision = ConnectionTilePolicy.decide(serviceRunning = false)
        assertEquals(ConnectionTileDecision(active = false, tap = ConnectionTileTap.START), decision)
    }

    @Test
    fun `the tap always does the opposite of what the tile shows`() {
        // The property the two cases above are instances of. A tile that is lit and offers a start, or unlit
        // and offers a stop, would invert the user's intent, so this is stated for both inputs.
        listOf(true, false).forEach { running ->
            val decision = ConnectionTilePolicy.decide(running)
            val expectedTap = if (decision.active) ConnectionTileTap.STOP else ConnectionTileTap.START
            assertEquals("tile active=${decision.active}", expectedTap, decision.tap)
            assertNotEquals(decision.active, decision.tap == ConnectionTileTap.START)
        }
    }
}
