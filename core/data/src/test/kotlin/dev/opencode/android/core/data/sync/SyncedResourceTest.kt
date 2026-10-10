package dev.opencode.android.core.data.sync

import dev.opencode.android.core.data.server.FakeServer
import dev.opencode.android.core.data.server.sessionFixture
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

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

    // The retry tests need virtual time, because the waits are the thing under test. They use a loader
    // that throws directly rather than HTTP. The scope is on the test's own scheduler rather than
    // `backgroundScope`, because `advanceUntilIdle` does not advance time for background work.

    @Test
    fun `a failed load is retried after the delay and then succeeds`() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val resource = retrying(retryDelayMillis = 100) {
            calls++
            if (calls == 1) throw IOException("connection reset")
            gate.await()
            "models"
        }

        resource.sync()
        assertEquals(1, calls)
        assertTrue("a failed load is Failed while its retry waits", resource.state.value.status is SyncStatus.Failed)

        advanceTimeBy(99)
        assertEquals("the retry waits out its delay", 1, calls)
        assertTrue(resource.state.value.status is SyncStatus.Failed)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls)
        assertEquals("a retry that has started is a load", SyncStatus.Loading, resource.state.value.status)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("models", resource.value)
        assertEquals(SyncStatus.Ready, resource.state.value.status)
    }

    @Test
    fun `retries stop at the attempt limit and the resource stays failed`() = runTest {
        var calls = 0
        val resource = retrying(retryDelayMillis = 10, maxAttempts = 3) {
            calls++
            throw IOException("still down")
        }

        // Attempt 1 fails at t=0 and retries at t=10; attempt 2 fails there and retries after 20 ms, at t=30.
        resource.sync()
        advanceTimeBy(10)
        runCurrent()
        assertEquals("the first retry ran at 10 ms", 2, calls)

        advanceTimeBy(19)
        assertEquals("the second retry waits twice as long", 2, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("the second retry ran at 30 ms", 3, calls)

        advanceUntilIdle()
        assertEquals("the attempt limit is final", 3, calls)
        assertTrue(resource.state.value.status is SyncStatus.Failed)
        assertNull(resource.value)
    }

    @Test
    fun `an explicit invalidation cancels the pending retry`() = runTest {
        var calls = 0
        val resource = retrying(retryDelayMillis = 100, debounceMillis = 1_000) {
            calls++
            if (calls == 1) throw IOException("down")
            "fresh"
        }

        resource.sync()
        // The retry was due at t=100. The invalidation's own refetch is debounced to t=1000.
        resource.invalidate()
        advanceTimeBy(500)
        runCurrent()
        assertEquals("the pending retry was cancelled, so nothing has run", 1, calls)

        advanceUntilIdle()
        assertEquals("only the invalidation's refetch ran after the failure", 2, calls)
        assertEquals("fresh", resource.value)
    }

    @Test
    fun `a client error is not retried`() = runTest {
        for (code in listOf(400, 401, 404)) {
            var calls = 0
            val resource = retrying(retryDelayMillis = 10) {
                calls++
                throw httpError(code)
            }

            resource.sync()
            advanceUntilIdle()
            assertEquals("HTTP $code will not change on a retry", 1, calls)
            assertTrue(resource.state.value.status is SyncStatus.Failed)
        }
    }

    @Test
    fun `a server error is retried`() = runTest {
        var calls = 0
        val resource = retrying(retryDelayMillis = 10) {
            calls++
            if (calls == 1) throw httpError(503)
            "ok"
        }

        resource.sync()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("ok", resource.value)
    }

    @Test
    fun `cancelling the owning scope cancels the pending retry`() = runTest {
        var calls = 0
        val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val resource = SyncedResource<String>(
            key = ResourceKey("server-1"),
            name = "test.list",
            scope = owner,
            loader = {
                calls++
                throw IOException("down")
            },
            retryDelayMillis = 100,
        )

        resource.sync()
        owner.cancel()
        advanceUntilIdle()
        assertEquals("the retry died with its scope", 1, calls)
    }

    private fun TestScope.retrying(
        retryDelayMillis: Long = 10,
        maxAttempts: Int = 3,
        debounceMillis: Long = 0,
        loader: suspend () -> String,
    ): SyncedResource<String> = SyncedResource(
        key = ResourceKey("server-1"),
        name = "test.list",
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        loader = loader,
        invalidateDebounceMillis = debounceMillis,
        retryDelayMillis = retryDelayMillis,
        maxAttempts = maxAttempts,
    )

    private fun httpError(code: Int): HttpException = HttpException(
        Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())),
    )

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
