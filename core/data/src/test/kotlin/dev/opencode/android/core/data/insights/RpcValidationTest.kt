package dev.opencode.android.core.data.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RPC id/method validation (issue #17).
 *
 * An id or method that would break the URL path is refused before any request is sent.
 * [invalidRpcSegment] returns the offending segment, or null if the segment is usable.
 */
class RpcValidationTest {

    @Test
    fun `blank segments are rejected`() {
        assertEquals("", invalidRpcSegment(""))
        assertEquals("   ", invalidRpcSegment("   "))
    }

    @Test
    fun `dot segments are rejected`() {
        assertEquals(".", invalidRpcSegment("."))
        assertEquals("..", invalidRpcSegment(".."))
    }

    @Test
    fun `separators are rejected`() {
        assertEquals("a/b", invalidRpcSegment("a/b"))
        assertEquals("a?b", invalidRpcSegment("a?b"))
        assertEquals("a#b", invalidRpcSegment("a#b"))
    }

    @Test
    fun `ordinary names are accepted`() {
        assertNull(invalidRpcSegment("my-plugin"))
        assertNull(invalidRpcSegment("method_name"))
        assertNull(invalidRpcSegment("method.name"))
        assertNull(invalidRpcSegment("abc123"))
    }
}
