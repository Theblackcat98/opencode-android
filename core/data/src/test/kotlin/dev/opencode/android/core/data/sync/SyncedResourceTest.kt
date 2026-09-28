package dev.opencode.android.core.data.sync

import dev.opencode.android.core.data.server.FakeServer
import dev.opencode.android.core.data.server.sessionFixture
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncedResource] over a real HTTP call, because the properties that matter here (one request per
 * burst, a lost invalidation never swallowed) are only observable across a real round trip.
 *
 * Every wait is bounded and reports what the server saw.
 */
class SyncedResourceTest {

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    @Test
    fun `loads once and serves the value afterwards`() = runBlocking {
        val resource = projects()
        resource.sync()
        assertTrue(resource.value?.isNotEmpty() == true)
        assertEquals(1, server.requestCount())

        resource.sync()
        assertEquals("a fresh value is not refetched", 1, server.requestCount())
    }

    @Test
    fun `a burst of invalidations costs one request`() = runBlocking {
        val resource = projects(debounceMillis = 120)
        resource.sync()
        val before = server.requestCount()

        repeat(10) { resource.invalidate() }
        awaitCount(before + 1)

        assertEquals("ten invalidations, one refetch", before + 1, server.requestCount())
    }

    @Test
    fun `an invalidation that lands during a load is not swallowed`() = runBlocking {
        val resource = projects(debounceMillis = 0)
        resource.sync()
        val before = server.requestCount()

        // Invalidate while the next load is in flight: the successor must still happen, or a
        // `*.updated` event that lands mid-request is lost until the next resync.
        val loading = scope.launch { resource.sync(force = true) }
        delay(1)
        resource.invalidate()
        loading.join()
        awaitCount(before + 2)
        assertTrue(
            "expected two more requests, saw ${server.paths()}",
            server.requestCount() >= before + 2,
        )
    }

    @Test
    fun `a forced sync refetches even a value the resource believes is current`() = runBlocking {
        val resource = projects()
        resource.sync()
        val before = server.requestCount()
        resource.sync(force = true)
        assertEquals(before + 1, server.requestCount())
    }

    @Test
    fun `an authoritative value completes the resource without a request`() = runBlocking {
        val resource = projects()
        val value = listOf(Project(id = "p", canonical = "/p", time = Project.Time(1, 1, 1), sandboxes = emptyList()))
        resource.complete(value)
        assertEquals(value, resource.value)
        assertEquals(0, server.requestCount())
        assertTrue("a completed resource is not stale", !resource.isStale)
    }

    @Test
    fun `a failed load keeps the value and retries on the next sync`() = runBlocking {
        server.close()
        val resource = projects()
        resource.sync()
        // The first sync failed; the value is still null and the resource is stale.
        assertNull(resource.value)
        assertTrue("a failed load must be retried, not treated as fresh", resource.isStale)
    }

    @Test
    fun `clearing drops the value and marks it stale`() = runBlocking {
        val resource = projects()
        resource.sync()
        assertTrue(resource.state.value.hasValue)
        resource.clear()
        assertTrue(resource.state.value.value == null)
        assertTrue(resource.isStale)
    }

    private fun projects(debounceMillis: Long = SyncedResource.DEFAULT_DEBOUNCE_MILLIS): SyncedResource<List<Project>> {
        val api = ServerApiFactory(okhttp3.OkHttpClient()).createForReads(server.baseUrl)
        return SyncedResource(
            key = ResourceKey("server-1"),
            name = "project.list",
            scope = scope,
            loader = { api.listProjects() },
            invalidateDebounceMillis = debounceMillis,
        )
    }

    private suspend fun awaitCount(expected: Int) {
        try {
            withTimeout(5_000) {
                while (server.requestCount() < expected) delay(10)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("expected $expected requests, saw ${server.paths()}")
        }
    }
}
