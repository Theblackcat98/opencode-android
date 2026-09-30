package dev.opencode.android.feature.composer.ui

import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The new-session sheet follows the project list and the directory browser instead of reading them once.
 *
 * Both are stores that answer *after* the sheet has drawn: the project list when the server has finished
 * its first read, and the browser's listing when `fs.list` returns. The sheet used to read each of them
 * inside the `combine` that builds its state, whose own inputs are the choices the user makes — so a
 * listing that arrived a moment after the user opened the browser was never shown, and the browser
 * stayed on a spinner over a directory the server had answered for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSessionReactivityTest {

    private lateinit var server: ComposerServer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = ComposerServer()
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `projects the server lists after the sheet opened appear in it`() = runTest {
        val sheet = newSession()
        assertTrue(sheet.state.value.projects.isEmpty())

        server.projects(listOf(project("project-1", "/work")))
        server.set.projects.sync()

        val listed = sheet.state.await("the project list to reach the sheet") { it.projects.isNotEmpty() }
        assertEquals(listOf("/work"), listed.projects.map { it.canonical })
    }

    @Test
    fun `a directory listing that arrives after the browser opened is shown`() = runTest {
        server.models(listOf(ComposerServer.model("text")))
        val sheet = newSession()
        sheet.selectLocation(LocationChoice.Typed(server.directory))
        // The catalogs are what the sheet reads on the way in, so the listing is the only thing still to come.
        sheet.state.await("the catalogs to load") { it.models.isNotEmpty() }

        server.listing(listOf(FileSystemEntry("src", "directory"), FileSystemEntry("README.md", "file")))
        sheet.openBrowser()

        val listed = sheet.state.await("the listing to reach the browser") { it.entries.isNotEmpty() }
        assertEquals(listOf("src", "README.md"), listed.entries.map { it.name })
        assertFalse("the listing has arrived, so the spinner is over", listed.browserLoading)
    }

    /** A sheet with a collector on it, because its state is only produced while somebody is looking. */
    private fun TestScope.newSession(): NewSessionViewModel {
        val sheet = NewSessionViewModel(MutableStateFlow(server.set), FakeModelPreferences)
        backgroundScope.launch(Dispatchers.Unconfined) { sheet.state.collect { } }
        return sheet
    }

    private fun project(id: String, canonical: String) = Project(
        id = id,
        canonical = canonical,
        time = Project.Time(created = 1L, updated = 2L, active = 3L),
        sandboxes = emptyList(),
    )
}
