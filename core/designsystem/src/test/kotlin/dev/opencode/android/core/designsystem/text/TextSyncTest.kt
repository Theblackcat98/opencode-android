package dev.opencode.android.core.designsystem.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSyncTest {
    @Test
    fun aStaleEchoWhileTypingDoesNotRewindTheField() {
        val sync = TextSync()
        var field = ""
        // The user types "abc" faster than the view model echoes it back.
        for (typed in listOf("a", "ab", "abc")) {
            field = typed
            sync.onEdited(typed)
        }
        // The echoes arrive late and in order; none of them may overwrite "abc".
        assertNull(sync.onState("a", field))
        assertNull(sync.onState("ab", field))
        assertNull(sync.onState("abc", field))
    }

    @Test
    fun aSkippedEchoStillDrainsEverythingBeforeIt() {
        val sync = TextSync()
        for (typed in listOf("a", "ab", "abc")) sync.onEdited(typed)
        // StateFlow conflates: "ab" is never published.
        assertNull(sync.onState("a", "abc"))
        assertNull(sync.onState("abc", "abc"))
        // Nothing stale is left to mask a later external change.
        assertEquals("", sync.onState("", "abc"))
    }

    @Test
    fun aTextTheFieldNeverReportedIsAdopted() {
        val sync = TextSync()
        sync.onEdited("h")
        sync.onEdited("hi")
        // A completion or a history step replaces the text from outside.
        assertEquals("hello world", sync.onState("hello world", "hi"))
    }

    @Test
    fun theBoxClearedAfterASendIsAdopted() {
        val sync = TextSync()
        sync.onEdited("hi")
        assertNull(sync.onState("hi", "hi"))
        assertEquals("", sync.onState("", "hi"))
    }

    @Test
    fun anExternalTextThatAlreadyMatchesTheFieldChangesNothing() {
        val sync = TextSync()
        assertNull(sync.onState("same", "same"))
    }
}
