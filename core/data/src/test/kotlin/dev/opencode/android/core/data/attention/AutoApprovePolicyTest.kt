package dev.opencode.android.core.data.attention

import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-approve, the way the TUI's `autoaccept` works (features doc §14; plan §6, Phase 4).
 *
 * The properties worth stating are the ones that cannot be recovered after the fact: that the
 * decision is always `once`, that it is always time-limited, and that nothing here evaluates a
 * permission rule. That last one is the reason the plan rejected a server-side session rule, and the
 * strongest form of the test is the shape of the function: it has no rules to get wrong.
 */
class AutoApprovePolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a pending request in a session with auto-approve on is approved`() {
        val decision = AutoApprovePolicy.decide(
            request = request("ses_1"),
            settings = settings(autoApproveSessions = mapOf("ses_1" to now + 60_000)),
            now = now,
        )
        assertTrue(decision is AutoApproveDecision.Approve)
    }

    @Test
    fun `the decision is always once, never always`() {
        // An `always` reply stores patterns on the server as a standing change, which is exactly what
        // plan §5.2 requires an explicit confirmation for. Auto-approve is a mode the user turns on
        // and off; it does not quietly widen the server's rules.
        assertEquals(PermissionReply.Once, AutoApprovePolicy.decision)
    }

    @Test
    fun `a request with no saved patterns is still approved`() {
        // There is no rule check here, and this is what proves it: a client that tried to be careful
        // would skip a request it could not turn into a standing rule. There is no such requirement.
        val decision = AutoApprovePolicy.decide(
            request = request("ses_1", save = null),
            settings = settings(autoApproveSessions = mapOf("ses_1" to now + 60_000)),
            now = now,
        )
        assertTrue(decision is AutoApproveDecision.Approve)
    }

    @Test
    fun `an expired window asks instead`() {
        val decision = AutoApprovePolicy.decide(
            request = request("ses_1"),
            settings = settings(autoApproveSessions = mapOf("ses_1" to now - 1)),
            now = now,
        )
        assertEquals(AutoApproveDecision.Ask(request("ses_1"), AutoApproveSkip.NOT_RUNNING), decision)
    }

    @Test
    fun `auto-approve is off by default`() {
        val decision = AutoApprovePolicy.decide(request("ses_1"), settings(), now)
        assertTrue(decision is AutoApproveDecision.Ask)
    }

    @Test
    fun `auto-approve covers one session, not the next`() {
        val settings = settings(autoApproveSessions = mapOf("ses_1" to now + 60_000))
        assertTrue(AutoApprovePolicy.decide(request("ses_1"), settings, now) is AutoApproveDecision.Approve)
        assertTrue(AutoApprovePolicy.decide(request("ses_2"), settings, now) is AutoApproveDecision.Ask)
    }

    @Test
    fun `the global scope covers every session while it runs`() {
        val settings = settings(autoApproveGlobalUntil = now + 60_000)
        assertTrue(AutoApprovePolicy.decide(request("ses_9"), settings, now) is AutoApproveDecision.Approve)
        val expired = settings(autoApproveGlobalUntil = now - 1)
        assertTrue(AutoApprovePolicy.decide(request("ses_9"), expired, now) is AutoApproveDecision.Ask)
    }

    @Test
    fun `a request already answered is not answered again`() {
        // A retried approve is the same request. Without this, a slow response to the first call and a
        // second pass over the pending list would send the reply twice.
        val decision = AutoApprovePolicy.decide(
            request = request("ses_1"),
            settings = settings(autoApproveSessions = mapOf("ses_1" to now + 60_000)),
            now = now,
            alreadyDecided = setOf("prm_1"),
        )
        assertEquals(AutoApproveDecision.Ask(request("ses_1"), AutoApproveSkip.ALREADY_DECIDED), decision)
    }

    @Test
    fun `the sessions auto-approve covers are the ones a notification must skip`() {
        val settings = settings(
            autoApproveGlobalUntil = now + 60_000,
            autoApproveSessions = mapOf("ses_2" to now - 1),
        )
        val covered = AutoApprovePolicy.autoApprovedSessions(
            settings = settings,
            sessionIds = listOf("ses_1", "ses_2", "ses_3"),
            now = now,
        )
        // The global scope is running, so every session is covered even where its own window has
        // expired: one of them approving and the other not would be a surprise the user cannot see.
        assertEquals(setOf("ses_1", "ses_2", "ses_3"), covered)
    }

    @Test
    fun `an expired per-session window is not reported as covering`() {
        val settings = settings(autoApproveSessions = mapOf("ses_1" to now - 1))
        // A session with no window at all is not covered either: the point of the set is which
        // requests to leave out of the shade, and leaving the wrong ones out hides real permissions.
        val covered = AutoApprovePolicy.autoApprovedSessions(settings, listOf("ses_1", "ses_2"), now)
        assertEquals(emptySet<String>(), covered)
    }

    @Test
    fun `no scope running means nothing is covered`() {
        assertTrue(AutoApprovePolicy.autoApprovedSessions(settings(), listOf("ses_1"), now).isEmpty())
    }

    @Test
    fun `an expired window is not left in the file's favour`() {
        // The window is stored as an instant, not a length, so a reboot cannot extend it.
        assertFalse(settings(autoApproveGlobalUntil = now - 1).autoApproveActive("ses_1", now))
        assertTrue(settings(autoApproveGlobalUntil = now + 1).autoApproveActive("ses_1", now))
    }

    private fun request(sessionId: String, save: List<String>? = listOf("bash *")) = PermissionRequest(
        id = "prm_1",
        sessionID = sessionId,
        action = "bash",
        resources = listOf("ls"),
        save = save,
    )

    private fun settings(
        autoApproveGlobalUntil: Long? = null,
        autoApproveSessions: Map<String, Long> = emptyMap(),
    ) = AttentionSettings(
        autoApproveGlobalUntil = autoApproveGlobalUntil,
        autoApproveSessions = autoApproveSessions,
    )
}
