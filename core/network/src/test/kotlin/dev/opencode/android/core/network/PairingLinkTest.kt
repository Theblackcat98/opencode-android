package dev.opencode.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {

    @Test
    fun parsesAnHttpLinkWithAPort() {
        val link = PairingLink.parse("http://192.168.1.50:4096/auth/connect/abc123")

        assertEquals(PairingLink("http://192.168.1.50:4096", "abc123"), link)
    }

    @Test
    fun parsesAnHttpsLink() {
        val link = PairingLink.parse("https://box.example.com/auth/connect/Xy-_09")

        assertEquals(PairingLink("https://box.example.com", "Xy-_09"), link)
    }

    @Test
    fun dropsTheSchemeAndHostCase() {
        val link = PairingLink.parse("HTTP://Box.Example.COM:4096/auth/connect/Code")

        assertEquals(PairingLink("http://box.example.com:4096", "Code"), link)
    }

    @Test
    fun dropsADefaultPort() {
        assertEquals("http://box.example.com", PairingLink.parse("http://box.example.com:80/auth/connect/c")?.baseUrl)
        assertEquals("https://box.example.com", PairingLink.parse("https://box.example.com:443/auth/connect/c")?.baseUrl)
    }

    @Test
    fun toleratesATrailingSlashAndSurroundingWhitespace() {
        val link = PairingLink.parse("  http://10.0.0.5:4096/auth/connect/xyz/ \n")

        assertEquals(PairingLink("http://10.0.0.5:4096", "xyz"), link)
    }

    @Test
    fun keepsAPathPrefixForAReverseProxySubpath() {
        val link = PairingLink.parse("https://example.com/opencode/auth/connect/abc")

        assertEquals(PairingLink("https://example.com/opencode", "abc"), link)
    }

    @Test
    fun rejectsALinkWithoutAPairingCode() {
        assertNull(PairingLink.parse("http://192.168.1.50:4096/api/info"))
    }

    @Test
    fun rejectsAnUnsupportedScheme() {
        assertNull(PairingLink.parse("ftp://box/auth/connect/abc"))
        assertNull(PairingLink.parse("opencode://box/auth/connect/abc"))
    }

    @Test
    fun rejectsACodeWithUnexpectedCharacters() {
        assertNull(PairingLink.parse("http://box:4096/auth/connect/abc!def"))
        assertNull(PairingLink.parse("http://box:4096/auth/connect/"))
    }

    @Test
    fun rejectsBlankInput() {
        assertNull(PairingLink.parse(null))
        assertNull(PairingLink.parse(""))
        assertNull(PairingLink.parse("   "))
    }
}
