package dev.opencode.android.feature.composer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which typed strings are a directory the server can resolve.
 *
 * A path is only committed as the session's location once it passes this, because committing asks the
 * server about that directory and the server registers every directory it is asked about.
 */
class NewSessionPathTest {
    @Test
    fun `an absolute path without whitespace is a location`() {
        assertTrue(isAbsoluteServerPath("/home/nick/Documents/Projects/app"))
        assertTrue(isAbsoluteServerPath("/"))
    }

    @Test
    fun `a relative path, a scheme, whitespace and an empty segment are not`() {
        assertFalse(isAbsoluteServerPath(""))
        assertFalse(isAbsoluteServerPath("home/nick"))
        assertFalse(isAbsoluteServerPath("~/project"))
        assertFalse(isAbsoluteServerPath("file:///tmp"))
        assertFalse(isAbsoluteServerPath("/home/nick/My Project"))
        assertFalse(isAbsoluteServerPath("/home//nick"))
    }
}
