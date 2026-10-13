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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #56: config editor reads are bound to the requested path and directory.
 *
 * A read captures its (directory, path) at request time. If the path changes while a read is
 * in flight, the stale read's result must not be published under the new path — only the newest
 * request's answer reaches the state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigEditorReadBindingTest {

    private val directory = "/work/app"
    private val pathA = ".opencode/a.jsonc"
    private val pathB = ".opencode/b.jsonc"
    private val contentA = """{"model":"model-a"}"""
    private val contentB = """{"model":"model-b"}"""

    private lateinit var server: AdminServer
    private lateinit var scope: CoroutineScope
    private lateinit var model: ConfigEditorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = AdminServer(directory)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        server.answerPrefix("GET", "/api/fs/read/", contentA)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        Dispatchers.resetMain()
    }

    private fun openEditor(path: String) {
        val set = server.dataSet(scope)
        model = ConfigEditorViewModel(MutableStateFlow(set), FakeExperimental())
        model.open(directory, path)
    }

    private fun awaitIdle() {
        // With UnconfinedTestDispatcher, coroutines run eagerly; poll until the read settles.
        var attempts = 0
        while (model.state.value.reading && attempts < 100) {
            Thread.sleep(10)
            attempts++
        }
    }

    @Test
    fun `a path switch publishes the new path's content, not the old`() {
        openEditor(pathA)
        awaitIdle()
        assertEquals("precondition: A's content loads", contentA, model.state.value.text)

        // Switch to B; the server now answers with B's content.
        server.answerPrefix("GET", "/api/fs/read/", contentB)
        model.setPath(pathB)
        awaitIdle()

        assertEquals(pathB, model.state.value.path)
        assertEquals("B's content must be shown, not A's", contentB, model.state.value.text)
    }

    @Test
    fun `the save target follows the requested path`() {
        openEditor(pathA)
        awaitIdle()
        model.setPath(pathB)
        awaitIdle()

        // The state path is what a save would target; it must be B, not a stale A.
        assertEquals(pathB, model.state.value.path)
    }
}
