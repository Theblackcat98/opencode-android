package dev.opencode.android.feature.requests.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import dev.opencode.android.core.model.FormField
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A form's text and number fields keep what is typed or pasted while the view model's echo of it is still
 * on its way.
 *
 * The answers these fields show reach the composition a frame after they were written, so a field that
 * displays them is rewound to an older text than the one it just reported: an answer typed or dictated
 * quickly is sent with characters missing or swapped. [LateEcho] is a state that trails on purpose, by a
 * number of edits the test picks.
 *
 * **A number is the sharper case.** The answer is a parsed number, so typing `3.` reports `3` and the state
 * text does not change; a field that shows the state text puts the point back to nothing and a decimal
 * cannot be typed at all, echo or no echo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class FormFieldEchoTest {

    @get:Rule
    val compose = createComposeRule()

    private val fields = listOf(
        FormField.StringField(key = NOTE, title = "Note"),
        FormField.NumberField(key = COUNT, title = "Count", integer = true),
        FormField.NumberField(key = RATIO, title = "Ratio"),
    )

    private val answers = mapOf(
        NOTE to LateEcho<JsonElement?>(null),
        COUNT to LateEcho<JsonElement?>(null),
        RATIO to LateEcho<JsonElement?>(null),
    )

    private fun showForm() {
        compose.setContent {
            FormFields(
                fields = fields,
                answers = answers.mapNotNull { (key, echo) -> echo.value?.let { key to it } }.toMap(),
                onAnswerChange = { key, answer -> answers.getValue(key).report(answer) },
                onOpenLink = {},
            )
        }
    }

    /** The field at [key]'s position among the form's text boxes, which are in the order the form declares. */
    private fun box(key: String) = compose.onAllNodes(hasSetTextAction())[fields.indexOfFirst { it.key == key }]

    private fun onScreen(key: String): String? = box(key).fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.EditableText)
        ?.text

    private fun reported(key: String): String? = (answers.getValue(key).lastReported as? JsonPrimitive)?.content

    /** Types [text] a character at a time, letting the echo trail the typing by [lag] edits. */
    private fun typeWithLag(key: String, text: String, lag: Int) {
        val echo = answers.getValue(key)
        text.forEach { char ->
            box(key).performTextInput(char.toString())
            while (echo.pending > lag) compose.runOnIdle { echo.publishOldest() }
        }
        compose.runOnIdle { while (echo.pending > 0) echo.publishOldest() }
        compose.waitForIdle()
    }

    @Test
    fun `a text answer typed ahead of the echo is kept`() {
        showForm()
        typeWithLag(NOTE, "please rebase onto the release branch first", lag = 1)
        assertEquals("please rebase onto the release branch first", reported(NOTE))
        assertEquals("please rebase onto the release branch first", onScreen(NOTE))
    }

    @Test
    fun `a text answer pasted in one edit is kept`() {
        showForm()
        val pasted = "please rebase onto the release branch first"
        box(NOTE).performTextInput(pasted)
        compose.runOnIdle { answers.getValue(NOTE).publishOldest() }
        compose.waitForIdle()
        assertEquals(pasted, reported(NOTE))
        assertEquals(pasted, onScreen(NOTE))
    }

    @Test
    fun `an integer answer typed ahead of the echo is kept`() {
        showForm()
        typeWithLag(COUNT, "20260930", lag = 1)
        assertEquals("20260930", reported(COUNT))
        assertEquals("20260930", onScreen(COUNT))
    }

    @Test
    fun `a decimal answer can be typed with the echo keeping up`() {
        showForm()
        typeWithLag(RATIO, "3.14159", lag = 0)
        assertEquals("3.14159", reported(RATIO))
        assertEquals("3.14159", onScreen(RATIO))
    }

    @Test
    fun `a decimal answer typed ahead of the echo is kept`() {
        showForm()
        typeWithLag(RATIO, "3.14159", lag = 2)
        assertEquals("3.14159", reported(RATIO))
        assertEquals("3.14159", onScreen(RATIO))
    }

    private companion object {
        const val NOTE = "note"
        const val COUNT = "count"
        const val RATIO = "ratio"
    }
}
