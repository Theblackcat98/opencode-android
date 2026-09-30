package dev.opencode.android.feature.review

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The history panel's search box keeps what is typed or pasted while the view model's echo of it is still
 * on its way.
 *
 * The state the box shows reaches the composition a frame after it was written, so a box that displays it is
 * rewound to an older text than the one it just reported, and the search runs on a query nobody typed.
 * [LateEcho] is a state that trails on purpose, by a number of edits the test picks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class HistorySearchEchoTest {

    @get:Rule
    val compose = createComposeRule()

    private val query = "where did the flaky migration test start failing"
    private val search = LateEcho()

    private fun show() {
        compose.setContent {
            HistoryPanel(
                state = HistoryUiState(search = search.text),
                onPreviousPrompt = {},
                onNextPrompt = {},
                onSearchChange = search::report,
                onOpenContext = {},
                onCloseContext = {},
                onSanitizeChange = {},
                onExportJson = {},
                onExportMarkdown = {},
                onImport = {},
                onDismissTransfer = {},
            )
        }
    }

    /** The panel's only text box. */
    private val box get() = compose.onNode(hasSetTextAction())

    private fun onScreen(): String? = box.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    @Test
    fun `the history search keeps a query typed ahead of the echo`() {
        show()
        query.forEach { char ->
            box.performTextInput(char.toString())
            while (search.pending > 1) compose.runOnIdle { search.publishOldest() }
        }
        compose.runOnIdle { while (search.pending > 0) search.publishOldest() }
        compose.waitForIdle()
        assertEquals(query, search.lastReported)
        assertEquals(query, onScreen())
    }

    @Test
    fun `the history search keeps a query pasted in one edit`() {
        show()
        box.performTextInput(query)
        compose.runOnIdle { search.publishOldest() }
        compose.waitForIdle()
        assertEquals(query, search.lastReported)
        assertEquals(query, onScreen())
    }
}
