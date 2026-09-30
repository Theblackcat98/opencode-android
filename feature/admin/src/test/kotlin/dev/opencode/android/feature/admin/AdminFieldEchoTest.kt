package dev.opencode.android.feature.admin

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The admin screens' fields keep what is typed or pasted while the view model's echo of it is still on
 * its way.
 *
 * Every field on these screens shows text a view model publishes, and that state reaches the composition
 * a frame after it was written, so a field that displays it is rewound to an older text than the one it
 * just reported: fast typing, a paste or an autofill loses or reorders characters. [LateEcho] is a state
 * that trails on purpose. The fields here are the [LabelledField] (which the templates, the rules, the
 * instruction key and the catalog search all use) and the two multi-line editors' shapes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class AdminFieldEchoTest {

    @get:Rule
    val compose = createComposeRule()

    private class Field(val tag: String, val text: String) {
        val echo = LateEcho()
    }

    private val path = Field("config:path", "/home/me/project/.opencode/opencode.jsonc")
    private val draft = Field(
        AdminTags.CONFIG_EDITOR,
        "{\n  \"model\": \"provider/model-name\",\n  \"share\": \"manual\"\n}",
    )
    private val key = Field(AdminTags.INSTRUCTION_KEY, "docs-style-guide-2026")
    private val value = Field(AdminTags.INSTRUCTION_VALUE, "[\"docs/style.md\",\"docs/api.md\"]")

    private fun showConfigEditor() {
        compose.setContent {
            AdminScaffoldless {
                ConfigEditorScreen(
                    state = ConfigEditorUiState(path = path.echo.text, draft = draft.echo.text),
                    onPathChange = path.echo::report,
                    onDraftChange = draft.echo::report,
                    onOpenTemplate = {},
                    onSave = {},
                    onConfirm = {},
                    onCancel = {},
                    onDismissOutcome = {},
                    onDismissError = {},
                )
            }
        }
    }

    private fun showInstructions() {
        compose.setContent {
            AdminScaffoldless {
                InstructionsScreen(
                    state = InstructionsUiState(usable = true, key = key.echo.text, value = value.echo.text),
                    onKeyChange = key.echo::report,
                    onValueChange = value.echo::report,
                    onPut = {},
                    onRequestRemove = {},
                    onConfirmRemove = {},
                    onCancelRemove = {},
                    onDismissError = {},
                )
            }
        }
    }

    private fun onScreen(field: Field): String? = compose.onNodeWithTag(field.tag)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.EditableText)
        ?.text

    private fun mismatch(field: Field): String? {
        val shown = onScreen(field)
        val reported = field.echo.lastReported
        if (shown == field.text && reported == field.text) return null
        return "${field.tag}: expected \"${field.text}\", the field shows \"$shown\" and last reported \"$reported\""
    }

    /** Runs [enter] on each of [fields] in turn and fails once, naming every field that ended up wrong. */
    private fun enterEach(vararg fields: Field, enter: (Field) -> Unit) {
        val problems = fields.mapNotNull { field ->
            enter(field)
            mismatch(field)
        }
        assertTrue(problems.joinToString(separator = "\n"), problems.isEmpty())
    }

    /** Types a character at a time, letting the echo trail the typing by [lag] characters. */
    private fun typeWithLag(field: Field, lag: Int) {
        field.text.forEach { char ->
            compose.onNodeWithTag(field.tag).performTextInput(char.toString())
            while (field.echo.pending > lag) compose.runOnIdle { field.echo.publishOldest() }
        }
        compose.runOnIdle { while (field.echo.pending > 0) field.echo.publishOldest() }
        compose.waitForIdle()
    }

    private fun pasteThenEcho(field: Field) {
        compose.onNodeWithTag(field.tag).performTextInput(field.text)
        compose.runOnIdle { field.echo.publishOldest() }
        compose.waitForIdle()
    }

    @Test
    fun `the config editor keeps its path and its document typed ahead of the echo`() {
        showConfigEditor()
        enterEach(path, draft) { typeWithLag(it, lag = 1) }
    }

    @Test
    fun `the config editor keeps its path and its document when the echo trails by many edits`() {
        showConfigEditor()
        enterEach(path, draft) { typeWithLag(it, lag = 6) }
    }

    @Test
    fun `the config editor keeps its path and its document pasted in one edit`() {
        showConfigEditor()
        enterEach(path, draft) { pasteThenEcho(it) }
    }

    @Test
    fun `the instruction form keeps its key and its value typed ahead of the echo`() {
        showInstructions()
        enterEach(key, value) { typeWithLag(it, lag = 1) }
    }

    @Test
    fun `the instruction form keeps its key and its value when the echo trails by many edits`() {
        showInstructions()
        enterEach(key, value) { typeWithLag(it, lag = 6) }
    }

    @Test
    fun `the instruction form keeps its key and its value pasted in one edit`() {
        showInstructions()
        enterEach(key, value) { pasteThenEcho(it) }
    }
}
