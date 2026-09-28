package dev.opencode.android.feature.servers.ui

import dev.opencode.android.core.data.repository.AddServerErrorType
import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.data.repository.toAddServerErrorType
import dev.opencode.android.core.network.ConnectionState
import dev.opencode.android.core.network.DisconnectCause
import dev.opencode.android.core.network.ValidationErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The failure to help mapping (plan §6, Phase 1).
 *
 * A failure class without a string resource, or two classes sharing one sentence, is the way this
 * mapping rots: the feature compiles, and the user is told something that does not apply to them.
 * This checks the mapping is total and one-to-one.
 *
 * The wording itself is verified by the resource file and by lint, not here: asserting on it would
 * need a Context, which means a Robolectric runner in a module that has no UI tests yet.
 */
class ServerMessagesTest {

    @Test
    fun everyFailureClassHasItsOwnSentence() {
        val sentences = AddServerErrorType.entries.associateWith { it.messageRes() }

        assertEquals(
            "Two failure classes share one sentence, so one of them says nothing useful",
            sentences.size,
            sentences.values.toSet().size,
        )
        sentences.forEach { (type, res) ->
            assertNotEquals("A resource id of 0 means '$type' has no string", 0, res)
        }
    }

    @Test
    fun theClassesThatNeedCommandsCarryHelp() {
        // Connection refused is the failure a first-time user hits most, and the message alone is
        // not actionable without the commands that fix it.
        assertNotNull(AddServerErrorType.UNREACHABLE.helpRes())
        assertNotNull(AddServerErrorType.PAIRING_CODE_REJECTED.helpRes())
        assertNotNull(AddServerErrorType.UNAUTHORIZED.helpRes())
        // A TLS failure has one obvious remedy: a certificate the phone trusts.
        assertNull(AddServerErrorType.TLS_ERROR.helpRes())
    }

    @Test
    fun everyValidationFailureMapsOntoAClassThatHasText() {
        ValidationErrorType.entries.forEach { type ->
            assertNotEquals(
                "A resource id of 0 means '$type' has no string",
                0,
                type.toAddServerErrorType().messageRes(),
            )
        }
    }

    @Test
    fun theValidationClassesTheHelpIsWrittenForMapWhereTheHelpExpects() {
        assertEquals(AddServerErrorType.UNREACHABLE, ValidationErrorType.CONNECTION_REFUSED.toAddServerErrorType())
        assertEquals(AddServerErrorType.UNREACHABLE, ValidationErrorType.UNKNOWN.toAddServerErrorType())
        assertEquals(AddServerErrorType.UNAUTHORIZED, ValidationErrorType.UNAUTHORIZED.toAddServerErrorType())
        assertEquals(AddServerErrorType.TLS_ERROR, ValidationErrorType.TLS_ERROR.toAddServerErrorType())
        assertEquals(AddServerErrorType.TIMEOUT, ValidationErrorType.TIMEOUT.toAddServerErrorType())
    }

    @Test
    fun everyHealthStateHasItsOwnLabel() {
        val labels = ServerHealth.entries.associateWith { it.labelRes() }
        assertEquals(ServerHealth.entries.size, labels.values.toSet().size)
        labels.forEach { (health, res) ->
            assertNotEquals("A resource id of 0 means '$health' has no label", 0, res)
        }
    }

    @Test
    fun everyConnectionStateHasALabelAndARejectedCredentialIsCalledOut() {
        val states = listOf(
            ConnectionState.Idle,
            ConnectionState.Connecting(1),
            ConnectionState.Connected(1L, 1L),
            ConnectionState.Suspended("because"),
            ConnectionState.Disconnected("closed", willRetry = true),
            ConnectionState.Disconnected("stopped", willRetry = false),
            ConnectionState.Disconnected(
                reason = "rejected",
                willRetry = false,
                cause = DisconnectCause.AUTHORIZATION_REQUIRED,
            ),
        )

        val labels = states.map { it.labelRes() }
        assertEquals("Two connection states share one label", states.size, labels.toSet().size)
        assertNotEquals("A resource id of 0 means a connection state has no label", 0, states.last().labelRes())
    }
}
