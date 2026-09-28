package dev.opencode.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {

    @Test
    fun parsesStandardPairingLink() {
        val link = PairingLink.parse("http://192.168.1.50:4096/auth/connect/abc-123_XYZ")
        assertNotNull(link)
        assertEquals("http://192.168.1.50:4096", link!!.baseUrl)
        assertEquals("abc-123_XYZ", link.code)
    }

    @Test
    fun parsesHttpsPairingLinkWithCustomPort() {
        val link = PairingLink.parse("https://opencode.internal.net:8443/auth/connect/code999")
        assertNotNull(link)
        assertEquals("https://opencode.internal.net:8443", link!!.baseUrl)
        assertEquals("code999", link.code)
    }

    @Test
    fun parsesLinkWithTrailingSlash() {
        val link = PairingLink.parse("http://localhost:4096/auth/connect/secret_code/")
        assertNotNull(link)
        assertEquals("http://localhost:4096", link!!.baseUrl)
        assertEquals("secret_code", link.code)
    }

    @Test
    fun parsesLinkWithSurroundingWhitespace() {
        val link = PairingLink.parse("   http://10.0.2.2:4096/auth/connect/code_1   ")
        assertNotNull(link)
        assertEquals("http://10.0.2.2:4096", link!!.baseUrl)
        assertEquals("code_1", link.code)
    }

    @Test
    fun returnsNullForInvalidUrls() {
        assertNull(PairingLink.parse(null))
        assertNull(PairingLink.parse(""))
        assertNull(PairingLink.parse("not a url"))
        assertNull(PairingLink.parse("ftp://example.com/auth/connect/code"))
        assertNull(PairingLink.parse("http://example.com/api/info"))
        assertNull(PairingLink.parse("http://example.com/auth/connect/"))
        assertNull(PairingLink.parse("http://example.com/auth/connect/code with spaces"))
    }
}
