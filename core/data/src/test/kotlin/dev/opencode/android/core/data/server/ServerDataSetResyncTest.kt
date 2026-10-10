package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.config.VendoredSchema
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.network.LocationParam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What [ServerDataSet.resync] asks the server about, read off the wire.
 *
 * `permission.request.list` and `form.list` are location-scoped, so a resync that asked once with no
 * directory would miss every project but the server's default one. The assertion is on the query the
 * server receives, `location[directory]`, which is the one the app sends; a request with no such
 * parameter is the one a resync sends when there is no project to name.
 */
class ServerDataSetResyncTest {

    private lateinit var fake: FakeServer
    private lateinit var scope: CoroutineScope
    private lateinit var set: ServerDataSet

    @Before
    fun setUp() {
        fake = FakeServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        set = ServerDataSet(
            serverId = "server-1",
            api = fake.api,
            scope = scope,
            cache = FakeReadCacheStore(),
            schema = VendoredSchema.schema(),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        fake.close()
    }

    @Test
    fun `a resync asks the requests of every project directory once each`() = runBlocking {
        fake.projects = listOf(project("/work"), project("/other"))

        set.resync()

        awaitAsked(PERMISSIONS, setOf("/work", "/other"))
        awaitAsked(FORMS, setOf("/work", "/other"))
        assertEquals(listOf("/other", "/work"), askedDirectories(PERMISSIONS).filterNotNull().sorted())
        assertEquals(listOf("/other", "/work"), askedDirectories(FORMS).filterNotNull().sorted())
    }

    @Test
    fun `a resync also asks about a location the set already holds`() = runBlocking {
        fake.projects = listOf(project("/work"))
        set.location("/elsewhere")

        set.resync()

        awaitAsked(PERMISSIONS, setOf("/work", "/elsewhere"))
        awaitAsked(FORMS, setOf("/work", "/elsewhere"))
        assertEquals(listOf("/elsewhere", "/work"), askedDirectories(PERMISSIONS).filterNotNull().sorted())
        assertEquals(listOf("/elsewhere", "/work"), askedDirectories(FORMS).filterNotNull().sorted())
    }

    @Test
    fun `with no project a resync asks once, with no directory`() = runBlocking {
        fake.projects = emptyList()

        set.resync()

        awaitAsked(PERMISSIONS, setOf(null))
        awaitAsked(FORMS, setOf(null))
        assertEquals(listOf<String?>(null), askedDirectories(PERMISSIONS))
        assertEquals(listOf<String?>(null), askedDirectories(FORMS))
    }

    /**
     * The directories a request to [path] carried, in arrival order. `null` is a request that named no
     * directory at all.
     */
    private fun askedDirectories(path: String): List<String?> = fake.requests()
        .filter { it.url.encodedPath == path }
        .map { it.url.queryParameter(LocationParam.QUERY_KEY) }

    /** Waits until every directory in [expected] has been asked about on [path]. */
    private suspend fun awaitAsked(path: String, expected: Set<String?>) {
        withTimeout(TIMEOUT_MILLIS) {
            while (!askedDirectories(path).containsAll(expected)) delay(POLL_MILLIS)
        }
    }

    private fun project(canonical: String) = Project(
        id = "project-$canonical",
        canonical = canonical,
        vcs = "git",
        time = Project.Time(created = 1, updated = 2, active = 3),
        sandboxes = emptyList(),
    )

    private companion object {
        const val PERMISSIONS = "/api/permission/request"
        const val FORMS = "/api/form"
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}
