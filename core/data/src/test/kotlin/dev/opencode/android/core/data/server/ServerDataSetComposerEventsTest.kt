package dev.opencode.android.core.data.server
import dev.opencode.android.core.data.config.VendoredSchema
import dev.opencode.android.core.data.sync.SyncStatus
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.network.ServerApi
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Phase 5 events, applied through the one place events are applied.
 *
 * `command.updated`, `skill.updated` and `reference.updated` carry an empty payload, so the location
 * in the envelope is the only thing to act on, and the assertion that matters is the *scope*: an
 * event naming one directory must not empty the list a screen is showing for another. Testing
 * [ServerDataSet.apply] rather than the catalog store is the difference between testing the wiring and
 * testing the thing the wiring calls.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerDataSetComposerEventsTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var set: ServerDataSet

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api: ServerApi = ServerApiFactory(okHttpClient = OkHttpClient(), credentialProvider = { null })
            .createForReads(server.url("/").toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .code(200)
                .addHeader("content-type", "application/json")
                .body(
                    when (request.url.encodedPath) {
                        "/api/command" -> """{"location":{"directory":"x"},"data":[{"name":"deploy"}]}"""
                        "/api/skill" ->
                            """{"location":{"directory":"x"},"data":[{"id":"s","name":"S","path":"/s","content":""}]}"""

                        "/api/reference" ->
                            """{"location":{"directory":"x"},"data":[{"name":"r","path":"/r","source":{"type":"local","path":"/r"}}]}"""

                        else -> "{}"
                    },
                )
                .build()
        }
        set = ServerDataSet(
            serverId = "s1",
            api = api,
            scope = scope,
            cache = FakeReadCacheStore(),
            schema = VendoredSchema.schema(),
        )
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        server.close()
    }

    @Test
    fun `a command updated event marks that location's commands stale`() = runTest {
        set.composerCatalogs.commands("/work").sync()
        set.composerCatalogs.commands("/other").sync()
        assertFalse(set.composerCatalogs.commands("/work").isStale)

        set.apply(event("command.updated", "/work"))

        assertTrue(set.composerCatalogs.commands("/work").isStale)
        assertFalse("another location's screen must not empty", set.composerCatalogs.commands("/other").isStale)
    }

    @Test
    fun `a skill updated event marks that location's skills stale`() = runTest {
        set.composerCatalogs.skills("/work").sync()
        set.apply(event("skill.updated", "/work"))
        assertTrue(set.composerCatalogs.skills("/work").isStale)
    }

    @Test
    fun `a reference updated event marks that location's references stale`() = runTest {
        set.composerCatalogs.references("/work").sync()
        set.apply(event("reference.updated", "/work"))
        assertTrue(set.composerCatalogs.references("/work").isStale)
    }

    @Test
    fun `an event with no location invalidates every location the client has open`() = runTest {
        set.composerCatalogs.commands("/work").sync()
        set.composerCatalogs.commands("/other").sync()

        set.apply(event("command.updated", null))

        assertTrue(set.composerCatalogs.commands("/work").isStale)
        assertTrue(set.composerCatalogs.commands("/other").isStale)
    }

    @Test
    fun `a location shutdown drops that location's catalogs`() = runTest {
        set.composerCatalogs.commands("/work").sync()
        assertEquals(listOf("deploy"), set.composerCatalogs.commands("/work").value?.map { it.name })

        set.apply(event("location.shutdown", "/work"))

        assertNull(set.composerCatalogs.commands("/work").value)
    }

    @Test
    fun `a resync makes every catalog stale again`() = runTest {
        set.composerCatalogs.commands("/work").sync()
        set.composerCatalogs.references("/work").sync()

        set.resync()

        assertTrue(set.composerCatalogs.commands("/work").isStale)
        assertTrue(set.composerCatalogs.references("/work").isStale)
    }

    @Test
    fun `clearing the set forgets the catalogs`() = runTest {
        set.composerCatalogs.commands("/work").sync()
        set.clear()
        assertNull(set.composerCatalogs.commands("/work").value)
        assertEquals(SyncStatus.Idle, set.composerCatalogs.commands("/work").state.value.status)
    }

    private fun event(type: String, directory: String?): Event {
        val location = directory?.let { ""","location":{"directory":"$it"}""" }.orEmpty()
        return Event.decode("""{"id":"evt_1","type":"$type","created":1$location,"data":{}}""")
    }
}
