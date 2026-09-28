package dev.opencode.android.core.data.server

import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The `fs.list` browser behind "pick a location".
 *
 * The property that matters is that the store asks again with the path the *server* gave. A real
 * listing is relative to the location and includes a `..` entry, so joining and normalizing locally
 * would produce paths the server never offered — and, on a server that resolves them, paths that point
 * somewhere else.
 */
class DirectoryBrowserTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var api: ServerApi
    private lateinit var browser: DirectoryBrowser

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        api = ServerApiFactory(okHttpClient = OkHttpClient(), credentialProvider = { null })
            .createForReads(server.url("/").toString())
        browser = DirectoryBrowser(api, scope)
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        server.close()
    }

    @Test
    fun `the location is asked for and the listing is kept`() = runTest {
        server.enqueue(
            json(
                """{"location":{"directory":"/w"},"data":[
                  {"path":"src/","type":"directory"},
                  {"path":"README.md","type":"file"}]}""",
            ),
        )
        assertNull(browser.listAndWait("/w"))
        assertEquals(2, browser.state.value.entries.size)
        val url = server.takeRequest().url
        assertEquals("/api/fs/list", url.encodedPath)
        assertEquals("/w", url.queryParameter("location[directory]"))
    }

    @Test
    fun `a path is sent as the server's own spelling, including a dot dot`() = runTest {
        server.enqueue(json("""{"location":{"directory":"/w"},"data":[]}"""))
        browser.listAndWait("/w", "../../../..")
        val query = server.takeRequest().url.queryParameter("path")
        assertEquals("../../../..", query)
    }

    @Test
    fun `the listing is directories first, then files, each alphabetical`() = runTest {
        server.enqueue(
            json(
                """{"location":{"directory":"/w"},"data":[
                  {"path":"zeta.txt","type":"file"},
                  {"path":"src/","type":"directory"},
                  {"path":"alpha.txt","type":"file"},
                  {"path":"docs/","type":"directory"}]}""",
            ),
        )
        browser.listAndWait("/w")
        assertEquals(
            listOf("docs", "src", "alpha.txt", "zeta.txt"),
            browser.state.value.sorted.map { it.name },
        )
    }

    @Test
    fun `a failed listing keeps the previous one and records why`() = runTest {
        server.enqueue(json("""{"location":{"directory":"/w"},"data":[{"path":"a","type":"file"}]}"""))
        browser.listAndWait("/w")
        server.enqueue(MockResponse.Builder().code(403).body("{}").build())

        val error = browser.listAndWait("/w", "secret")

        assertNotNull("the failure must be reported", error)
        assertEquals("a", browser.state.value.entries.single().name)
        assertEquals("the failed path is what the browser shows", "secret", browser.state.value.path)
        assertNotNull(browser.state.value.error)
    }

    @Test
    fun `an entry names itself from a path with a trailing separator`() {
        assertEquals("src", FileSystemEntry("src/", FileSystemEntry.EntryType.DIRECTORY).name)
        assertEquals("README.md", FileSystemEntry("a/b/README.md", FileSystemEntry.EntryType.FILE).name)
        assertEquals("/", FileSystemEntry("/", FileSystemEntry.EntryType.DIRECTORY).name)
    }

    private fun json(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("content-type", "application/json")
        .body(body)
        .build()
}
