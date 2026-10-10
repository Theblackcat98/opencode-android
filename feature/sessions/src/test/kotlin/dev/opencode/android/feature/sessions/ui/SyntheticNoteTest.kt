package dev.opencode.android.feature.sessions.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a synthetic note sends: the text and description trimmed, and a blank description sent as absent.
 *
 * The wire shape is covered by `InsightsWireTest`; this is the rule the dialog's enabled state and the request
 * share, so "blank" means the same thing in both places.
 */
class SyntheticNoteTest {

    @Test
    fun `text and description are trimmed before they are sent`() {
        val note = SyntheticNote.of("  shell output \n", "  from a plugin ")

        assertEquals(SyntheticNote("shell output", "from a plugin"), note)
    }

    @Test
    fun `a blank description is sent as absent, not as an empty string`() {
        assertEquals(SyntheticNote("shell output", null), SyntheticNote.of("shell output", "   "))
        assertEquals(SyntheticNote("shell output", null), SyntheticNote.of("shell output", null))
    }

    @Test
    fun `a note with no text is not sent, whatever its description says`() {
        assertNull(SyntheticNote.of("", "a description"))
        assertNull(SyntheticNote.of(" \n\t ", null))
    }
}
