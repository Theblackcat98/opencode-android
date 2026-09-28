package dev.opencode.android.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache the auth interceptor reads on every request.
 *
 * It is keyed by a normalized base URL, because the same host on another port, or the same host
 * behind another path prefix, is another server with another credential.
 */
class ServerCredentialCacheTest {

    @Test
    fun holdsAndReleasesACredential() {
        val cache = ServerCredentialCache()
        val url = "http://192.168.1.50:4096/"

        assertFalse(cache.holds(url))
        cache.put(url, "token-1")
        assertTrue(cache.holds(url))
        assertEquals("token-1", cache.credentialFor(url.toHttpUrl()))

        cache.forget(url)
        assertFalse(cache.holds(url))
        assertNull(cache.credentialFor(url.toHttpUrl()))
    }

    @Test
    fun matchesTheSameServerWrittenDifferently() {
        val cache = ServerCredentialCache()
        cache.put("http://192.168.1.50:4096", "token-1")

        listOf(
            "http://192.168.1.50:4096/",
            "HTTP://192.168.1.50:4096",
            "  http://192.168.1.50:4096  ",
        ).forEach { variant ->
            assertEquals(
                "'$variant' is the same server and must find the credential",
                "token-1",
                cache.credentialFor(variant.toHttpUrl()),
            )
        }
    }

    @Test
    fun aDifferentPortOrPrefixIsAnotherServer() {
        val cache = ServerCredentialCache()
        cache.put("http://box:4096", "token-a")
        cache.put("http://box:4097", "token-b")
        cache.put("http://box:4096/opencode", "token-c")

        assertEquals("token-a", cache.credentialFor("http://box:4096/".toHttpUrl()))
        assertEquals("token-b", cache.credentialFor("http://box:4097/".toHttpUrl()))
        assertEquals("token-c", cache.credentialFor("http://box:4096/opencode/".toHttpUrl()))
    }

    @Test
    fun storingABlankCredentialRemovesTheEntry() {
        val cache = ServerCredentialCache()
        cache.put("http://box:4096", "token")

        cache.put("http://box:4096", "  ")

        assertFalse(cache.holds("http://box:4096"))
    }

    @Test
    fun anUnusableBaseUrlIsIgnoredRatherThanStored() {
        val cache = ServerCredentialCache()

        cache.put("   ", "token")
        cache.put("", "token")

        assertFalse(cache.holds("http://box:4096"))
    }

    @Test
    fun theCacheIsBoundedSoARemovedServerCannotBeRememberedForever() {
        val cache = ServerCredentialCache(maxEntries = 4)
        repeat(10) { index -> cache.put("http://box-$index:4096", "token-$index") }

        val stored = (0..9).count { cache.holds("http://box-$it:4096") }
        assertTrue("The cache must stay bounded, held $stored of 10", stored <= 4)
        assertTrue("The most recent credential must still be there", cache.holds("http://box-9:4096"))
    }
}
