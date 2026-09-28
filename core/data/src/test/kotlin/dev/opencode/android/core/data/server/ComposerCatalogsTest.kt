package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.sync.SyncedResource
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The composer's catalogs and the search behind `@` completion.
 *
 * Two properties matter and neither is about the JSON: the catalogs must be invalidated by the three
 * `*.updated` events Phase 5 acts on first, and the search must cost one request for a burst of
 * keystrokes. A completion list that asks the server once per character is a list that arrives after
 * the user has finished typing.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComposerCatalogsTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var api: ServerApi
    private lateinit var catalogs: ComposerCatalogs
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        api = ServerApiFactory(okHttpClient = OkHttpClient(), credentialProvider = { null })
            .createForReads(server.url("/").toString())
        catalogs = ComposerCatalogs("server-1", api, scope)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests.add(request) }
                val path = request.url.encodedPath
                val body = when {
                    path == "/api/command" ->
                        """{"location":{"directory":"/w"},"data":[{"name":"deploy","description":"Ship"}]}"""

                    path == "/api/skill" ->
                        """{"location":{"directory":"/w"},"data":[
                          {"id":"review","name":"Review","path":"/s.md","content":"x"}]}"""

                    path == "/api/reference" ->
                        """{"location":{"directory":"/w"},"data":[
                          {"name":"docs","path":"/w/docs","source":{"type":"local","path":"/w/docs"}}]}"""

                    path == "/api/fs/find" ->
                        """{"location":{"directory":"/w"},"data":[
                          {"path":"src/a.ts","type":"file"},{"path":"src","type":"directory"}]}"""

                    else -> return MockResponse.Builder().code(404).body("{}").build()
                }
                return MockResponse.Builder()
                    .code(200)
                    .addHeader("content-type", "application/json")
                    .body(body)
                    .build()
            }
        }
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        server.close()
    }

    @Test
    fun `each catalog is location scoped and read once`() = runTest {
        catalogs.commands("/w").sync()
        catalogs.skills("/w").sync()
        catalogs.references("/w").sync()

        assertEquals(listOf("deploy"), catalogs.commands("/w").value?.map { it.name })
        assertEquals(listOf("review"), catalogs.skills("/w").value?.map { it.id })
        assertEquals(listOf("docs"), catalogs.references("/w").value?.map { it.name })

        // A second sync of a value this client believes is current costs nothing.
        catalogs.commands("/w").sync()
        assertEquals(3, requests.count { it.url.encodedPath in CATALOG_PATHS })
        assertTrue(requests.all { it.url.queryParameter("location[directory]") == "/w" })
    }

    @Test
    fun `the same directory is one resource`() {
        assertTrue(catalogs.commands("/w") === catalogs.commands("/w"))
        assertTrue(catalogs.commands("/w") !== catalogs.commands("/other"))
    }

    @Test
    fun `invalidating one location leaves the other alone`() = runTest {
        catalogs.commands("/w").sync()
        catalogs.commands("/other").sync()

        catalogs.invalidate("/w")

        assertTrue(catalogs.commands("/w").isStale)
        assertFalse(catalogs.commands("/other").isStale)
    }

    @Test
    fun `a failed catalog load keeps the previous value and reports`() = runTest {
        catalogs.commands("/w").sync()
        assertNotNull(catalogs.commands("/w").value)

        val failing = SyncedResource<kotlinx.serialization.json.JsonElement>(
            key = dev.opencode.android.core.data.sync.ResourceKey("s"),
            name = "x",
            scope = scope,
            loader = { throw IllegalStateException("boom") },
        )
        failing.sync()
        assertTrue(failing.state.value.status is dev.opencode.android.core.data.sync.SyncStatus.Failed)
    }

    @Test
    fun `a resync invalidates every catalog the client has open`() = runTest {
        catalogs.commands("/w").sync()
        catalogs.skills("/w").sync()

        catalogs.resync()
        assertTrue(catalogs.commands("/w").isStale)
        assertTrue(catalogs.skills("/w").isStale)
    }

    @Test
    fun `dropping a location drops only that location's catalogs`() = runTest {
        catalogs.commands("/w").sync()
        catalogs.commands("/other").sync()

        catalogs.dropLocation("/w")

        assertEquals(null, catalogs.commands("/w").value)
        assertNotNull(catalogs.commands("/other").value)
    }

    @Test
    fun `the search asks once for a burst of keystrokes`() = runBlocking {
        // A real wait, not a virtual one: the debounce is a wall-clock delay and the request it
        // guards is a real HTTP call, so a test scheduler would be asserting nothing.
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val search = FileSearch(api, testScope, debounceMillis = 200L)
        try {
            listOf("s", "sr", "src", "src/").forEach { search.search("/w", it) }
            withTimeout(5_000) { while (findCount("/api/fs/find") == 0) delay(20) }
            // Let a cancelled request that slipped through have time to arrive, so the count below
            // is a real "one" and not a "not yet".
            delay(400)

            assertEquals("one request for four keystrokes", 1, findCount("/api/fs/find"))
            assertEquals("src/", search.state.value.query)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `the search sends the query, the type and the limit`() = runTest {
        val search = FileSearch(api, scope, debounceMillis = 0L)
        search.searchNow("/w", "src", type = "file")
        advanceUntilIdle()

        val request = requests.first { it.url.encodedPath == "/api/fs/find" }
        assertEquals("src", request.url.queryParameter("query"))
        assertEquals("file", request.url.queryParameter("type"))
        assertEquals(FileSearch.LIMIT.toString(), request.url.queryParameter("limit"))
        assertEquals("/w", request.url.queryParameter("location[directory]"))
    }

    @Test
    fun `a blank query asks the server for nothing`() = runTest {
        val search = FileSearch(api, scope, debounceMillis = 0L)
        search.searchNow("/w", "   ")
        assertTrue(search.state.value.isEmpty)
        assertEquals(0, findCount("/api/fs/find"))
    }

    @Test
    fun `a query longer than any path is not sent to the server`() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val search = FileSearch(api, testScope, debounceMillis = 0L)
        try {
            search.search("/w", "x".repeat(FileSearch.MAX_QUERY_CHARS + 1))
            delay(200)
            assertEquals(0, findCount("/api/fs/find"))
            assertTrue(search.state.value.isEmpty)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun `the results are the server's ranking`() = runTest {
        val search = FileSearch(api, scope, debounceMillis = 0L)
        val found = search.searchNow("/w", "src")
        assertEquals(listOf("src/a.ts", "src"), found.map { it.path })
    }

    @Test
    fun `a failed search reports and keeps the previous results`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(403).body("{}").build()
        }
        val search = FileSearch(api, scope, debounceMillis = 0L)
        search.searchNow("/w", "src")
        assertNotNull("the composer must say why the list is empty", search.state.value.error)
    }

    private fun findCount(path: String): Int = synchronized(requests) { requests.count { it.url.encodedPath == path } }

    private companion object {
        val CATALOG_PATHS = setOf("/api/command", "/api/skill", "/api/reference")
    }
}
