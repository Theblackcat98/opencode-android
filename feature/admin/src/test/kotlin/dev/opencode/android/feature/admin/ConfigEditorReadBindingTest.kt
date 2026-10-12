package dev.opencode.android.feature.admin

import dev.opencode.android.core.data.preferences.ExperimentalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigEditorReadBindingTest {

    private lateinit var server: AdminServer
    private lateinit var scope: CoroutineScope
    private lateinit var model: ConfigEditorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = AdminServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val set = server.dataSet(scope)
        model = ConfigEditorViewModel(MutableStateFlow(set), FakeExperimental(ExperimentalSettings(fileWrites = true)))
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a late read cannot publish contents or save target for an older directory and path`() {
        val firstReadStarted = CountDownLatch(1)
        val releaseFirstResponse = CountDownLatch(1)
        val firstResponseReady = CountDownLatch(1)
        val readCount = AtomicInteger()
        val fallback = server.server.dispatcher
        server.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.url.encodedPath.startsWith("/api/fs/read/")) {
                    return fallback.dispatch(request)
                }
                val firstRead = readCount.getAndIncrement() == 0
                if (firstRead) {
                    firstReadStarted.countDown()
                    check(releaseFirstResponse.await(5, TimeUnit.SECONDS))
                }
                val response = MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(
                        if (firstRead) "{\"model\":\"first-provider/first-model\"}"
                        else "{\"model\":\"second-provider/second-model\"}",
                    )
                if (firstRead) {
                    response.bodyDelay(300, TimeUnit.MILLISECONDS)
                    firstResponseReady.countDown()
                }
                return response.build()
            }
        }

        try {
            model.open("/first", "first.jsonc")
            assertTrue("the first read never reached the server", firstReadStarted.await(5, TimeUnit.SECONDS))
            model.open("/second", "second.jsonc")
            waitUntil { !model.state.value.reading && model.state.value.text.contains("second-provider") }

            releaseFirstResponse.countDown()
            assertTrue("the delayed first response was not released", firstResponseReady.await(5, TimeUnit.SECONDS))
            Thread.sleep(500)

            assertEquals("/second", model.state.value.directory)
            assertEquals("second.jsonc", model.state.value.path)
            assertTrue(model.state.value.text.contains("second-provider/second-model"))
            model.requestSave()
            assertEquals("second.jsonc", model.state.value.plan?.target)
        } finally {
            releaseFirstResponse.countDown()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                error("Timed out waiting for config editor state: ${model.state.value}")
            }
            Thread.sleep(5)
        }
    }
}
