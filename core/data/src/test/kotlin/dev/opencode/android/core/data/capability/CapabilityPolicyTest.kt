package dev.opencode.android.core.data.review

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.capability.CapabilityPolicy
import dev.opencode.android.core.data.capability.ExperimentalRoute
import dev.opencode.android.core.data.capability.HTTP_METHOD_NOT_ALLOWED
import dev.opencode.android.core.data.capability.HTTP_NOT_FOUND
import dev.opencode.android.core.data.capability.RouteAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability detection for the three experimental routes (plan §4.2, "Detect capabilities").
 *
 * The rule that matters is what a failure does **not** mean. A `500` is a server fault and a `409`
 * is a conflict, and both prove the route is there; only `404` and `405` hide a feature. A policy
 * that treated every failure as absence would switch a working feature off because of a transient
 * error, and the user's next action would disappear for a reason they cannot see.
 */
class CapabilityPolicyTest {

    @Test
    fun `a 404 hides the feature`() {
        assertEquals(RouteAvailability.Absent(404), CapabilityPolicy.from(404))
    }

    @Test
    fun `a 405 hides the feature, and says which status it was`() {
        assertEquals(RouteAvailability.Absent(405), CapabilityPolicy.from(HTTP_METHOD_NOT_ALLOWED))
    }

    @Test
    fun `a server fault does not hide the feature`() {
        assertNull(CapabilityPolicy.from(500))
        assertNull(CapabilityPolicy.from(503))
    }

    @Test
    fun `a conflict does not hide the feature`() {
        // A revert refused because the session is busy proves the route exists.
        assertNull(CapabilityPolicy.from(409))
    }

    @Test
    fun `an unauthorized answer does not hide the feature`() {
        assertNull(CapabilityPolicy.from(401))
        assertNull(CapabilityPolicy.from(403))
    }

    @Test
    fun `only a not-found error class implies absence`() {
        assertEquals(RouteAvailability.Absent(HTTP_NOT_FOUND), CapabilityPolicy.from(ActionErrorKind.NOT_FOUND))
        listOf(
            ActionErrorKind.SERVER,
            ActionErrorKind.CONFLICT,
            ActionErrorKind.SESSION_BUSY,
            ActionErrorKind.OFFLINE,
            ActionErrorKind.INVALID_REQUEST,
            ActionErrorKind.UNAUTHORIZED,
            ActionErrorKind.FORBIDDEN,
            ActionErrorKind.UNKNOWN,
        ).forEach { assertNull("kind=$it", CapabilityPolicy.from(it)) }
        assertNull(CapabilityPolicy.from(null))
    }

    @Test
    fun `a reported error is read off its status, because a 405 carries no tag to classify it`() {
        // Phase 8's capability probe needs this. A 405 arrives with an empty body and no `_tag`, so
        // `toActionError` can only classify it as SERVER from the status — and reading the *kind*
        // would leave a route the server has-but-refuses switched on, which is the exact state the
        // probe exists to detect.
        assertEquals(
            RouteAvailability.Absent(HTTP_METHOD_NOT_ALLOWED),
            CapabilityPolicy.from(
                ActionError(kind = ActionErrorKind.SERVER, message = "", httpStatus = HTTP_METHOD_NOT_ALLOWED),
            ),
        )
        assertEquals(
            RouteAvailability.Absent(HTTP_NOT_FOUND),
            CapabilityPolicy.from(
                ActionError(kind = ActionErrorKind.SERVER, message = "", httpStatus = HTTP_NOT_FOUND),
            ),
        )
        // A 500 is present, not absent: the route clearly is there.
        assertNull(CapabilityPolicy.from(ActionError(kind = ActionErrorKind.SERVER, message = "", httpStatus = 500)))
        // A dropped connection has no status and no bearing on the route.
        assertNull(CapabilityPolicy.from(ActionError(kind = ActionErrorKind.OFFLINE, message = "")))
        // And with no status at all, the kind is still consulted, so a not-found tag from a proxy
        // that rewrote the status still hides the feature.
        assertEquals(
            RouteAvailability.Absent(HTTP_NOT_FOUND),
            CapabilityPolicy.from(ActionError(kind = ActionErrorKind.NOT_FOUND, message = "")),
        )
    }

    @Test
    fun `an unprobed feature is offered, because a switch that hides everything is useless`() {
        assertTrue(CapabilityPolicy.isUsable(RouteAvailability.Unknown))
    }

    @Test
    fun `a present feature is offered`() {
        assertTrue(CapabilityPolicy.isUsable(RouteAvailability.Present))
    }

    @Test
    fun `an absent feature is hidden for the rest of the process`() {
        // Re-probing on every screen would be a request per screen for a route that will keep
        // answering 404. The settings switch is what makes the user try again.
        assertFalse(CapabilityPolicy.isUsable(RouteAvailability.Absent(404)))
        assertFalse(CapabilityPolicy.isUsable(RouteAvailability.Absent(405)))
    }

    @Test
    fun `availability answers whether a call may be made at all`() {
        assertTrue(RouteAvailability.Unknown.callable)
        assertTrue(RouteAvailability.Present.callable)
        assertFalse(RouteAvailability.Absent(404).callable)
    }

    @Test
    fun `every route has an id a preference can key on`() {
        val ids = ExperimentalRoute.entries.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        // Phase 7 added the session-terminal routes as one value, because the eleven persistent-PTY
        // routes are one feature that lives or dies together and a client that probed them separately
        // would show a terminal picker for a feature whose host it had not established.
        //
        // Phase 8 added the four runtime-MCP routes as one value for the same reason, and the
        // well-known-source route as a fifth of its own: it is a different kind of write, because it
        // makes the *server* fetch a URL rather than editing a table that dies at restart. A user who
        // agreed to the first has not agreed to the second.
        //
        // Phase 9 added two more, and both are kept apart from `fs_write` on purpose. `config_update` is
        // the one key the server will change for us (`shell`) and a user who will not let the app edit
        // their configuration files has not said no to picking a shell from the list the server itself
        // reported. `session_instructions` changes what a *running* session is told at the next step
        // boundary, which is a different risk from a file the user reviews and saves.
        //
        // Phase 10 added four, and the separations are the point. `session_stats` and `session_log`
        // are pure reads and neither is grouped with a write. `generate_and_wait` covers the two
        // routes that make a model call the user did not start in a session, and the widget behind
        // quick ask is the reason to be able to switch it off. `pair_device` stands entirely alone
        // because it is the only route in the app that is not in the published spec at all, so it can
        // be absent for a reason no version range explains.
        assertEquals(
            listOf(
                "fs_write",
                "session_export",
                "session_import",
                "persistent_pty",
                "mcp_runtime",
                "wellknown_integration",
                "config_update",
                "session_instructions",
                "session_stats",
                "generate_and_wait",
                "session_log",
                "pair_device",
            ),
            ids,
        )
    }
}
