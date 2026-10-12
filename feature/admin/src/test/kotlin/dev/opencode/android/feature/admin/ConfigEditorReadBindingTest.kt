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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
        server.answerPrefix("GET", "/api/fs/read/", "{\"model\":\"second-provider/second-model\"}")
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
        model.open("/first", "first.jsonc")
        model.open("/second", "second.jsonc")
        waitUntil { !model.state.value.reading && model.state.value.text.contains("second-provider") }

        assertEquals("/second", model.state.value.directory)
        assertEquals("second.jsonc", model.state.value.path)
        assertTrue(model.state.value.text.contains("second-provider/second-model"))
        model.requestSave()
        assertEquals("second.jsonc", model.state.value.plan?.target)
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
