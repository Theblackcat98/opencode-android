package dev.opencode.android.feature.requests.notifications

/** What a tap on the Quick Settings tile does next. */
enum class ConnectionTileTap {
    START,
    STOP,
}

/**
 * How the Quick Settings tile looks and what a tap does, from whether the connection service is running.
 *
 * [active] is the tile's lit state. [tap] is the action the tile offers, and it is always the opposite of
 * what the tile shows: a lit tile stops the connection, an unlit one starts it.
 */
data class ConnectionTileDecision(
    val active: Boolean,
    val tap: ConnectionTileTap,
)

/**
 * The tile's face and its next action, as a pure function of whether the service is running.
 *
 * **Pure, so it is tested without a device.** The tile service maps [ConnectionTileDecision.active] onto the
 * platform's tile states and nothing else, so the rule that a lit tile stops and an unlit one starts is
 * stated here once and asserted by a test, not spread across the service's callbacks.
 */
object ConnectionTilePolicy {

    fun decide(serviceRunning: Boolean): ConnectionTileDecision =
        if (serviceRunning) {
            ConnectionTileDecision(active = true, tap = ConnectionTileTap.STOP)
        } else {
            ConnectionTileDecision(active = false, tap = ConnectionTileTap.START)
        }
}
