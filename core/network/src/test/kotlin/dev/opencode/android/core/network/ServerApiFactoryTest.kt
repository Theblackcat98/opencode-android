package dev.opencode.android.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerApiFactoryTest {

    @Test
    fun normalizesAnAddressIntoABaseUrlRetrofitAccepts() {
        assertEquals("http://192.168.1.50:4096/", "http://192.168.1.50:4096".toServerBaseUrl())
        assertEquals("http://192.168.1.50:4096/", "http://192.168.1.50:4096/".toServerBaseUrl())
        assertEquals("https://box.example.com/", "https://box.example.com".toServerBaseUrl())
    }

    @Test
    fun assumesHttpForABareAddress() {
        assertEquals("http://192.168.1.50:4096/", "192.168.1.50:4096".toServerBaseUrl())
        assertEquals("http://box.local/", "box.local".toServerBaseUrl())
    }

    @Test
    fun trimsSurroundingWhitespaceAndAQuery() {
        assertEquals("http://box:4096/", "  http://box:4096/?x=1  ".toServerBaseUrl())
    }

    @Test
    fun keepsAReverseProxyPathPrefix() {
        assertEquals("https://example.com/opencode/", "https://example.com/opencode".toServerBaseUrl())
    }

    @Test
    fun rejectsAddressesItCannotUse() {
        assertTrue(runCatching { "not a url".toServerBaseUrl() }.isFailure)
        assertTrue(runCatching { "ftp://box".toServerBaseUrl() }.isFailure)
        assertTrue(runCatching { "http://".toServerBaseUrl() }.isFailure)
    }

    @Test
    fun marksCleartextLanServersForTheUnencryptedBadge() {
        assertTrue("http://192.168.1.50:4096/".toHttpUrl().isCleartextLan())
        assertTrue("http://box.example.com/".toHttpUrl().isCleartextLan())
        assertFalse("https://box.example.com/".toHttpUrl().isCleartextLan())
        assertFalse("http://localhost:4096/".toHttpUrl().isCleartextLan())
        assertFalse("http://127.0.0.1:4096/".toHttpUrl().isCleartextLan())
        assertFalse("http://[::1]:4096/".toHttpUrl().isCleartextLan())
        assertFalse("http://10.0.2.2:4096/".toHttpUrl().isCleartextLan())
    }

    @Test
    fun recognizesLoopbackHostsIncludingTheEmulatorAlias() {
        assertTrue(isLoopbackHost("localhost"))
        assertTrue(isLoopbackHost("LOCALHOST"))
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("::1"))
        assertTrue(isLoopbackHost("[::1]"))
        assertTrue(isLoopbackHost("10.0.2.2"))
        assertFalse(isLoopbackHost("192.168.1.50"))
        assertFalse(isLoopbackHost("box.local"))
    }
}
