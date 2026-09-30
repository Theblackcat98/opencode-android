package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.feature.composer.ui.ComposerBar
import dev.opencode.android.feature.composer.ui.ComposerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.opencode.android.feature.composer.R as ComposerR

/**
 * Send has to be on the screen, whole and answering taps, in every state the composer's button row can
 * be in.
 *
 * The bug this pins: while a turn ran, the row held seven 48 dp icons, two text buttons and Send, about
 * 430 dp of buttons in a row a 360 dp phone gives 336 dp. Send is measured last, so it took the
 * leftover width, which was nothing, and the a11y tree still listed "Send. Held to queue" for a button
 * nobody could see. Idle it was no better below 408 dp: the seven icons alone take 336 dp. A queued or
 * steered prompt was therefore impossible on any phone that is not a Pixel XL, and a tap at the far
 * right landed on "Background".
 *
 * A screenshot cannot say that a button is *whole*, so this asserts the node tree: every control on the
 * row is displayed, lies inside the window, is at least 48 dp square where a finger lands, and overlaps
 * no other control; and Send answers a click and a long click with its own callback and nobody else's.
 *
 * The staged-undo banner is on the same bar and answers to the same rule. It was written, translated and
 * screenshotted in the review module and never composed into the screen, so an undo staged on the server said
 * nothing on the phone and offered no Redo (manual test E2). It is above the field, only while an undo is
 * staged, its Redo is a 48 dp target that ASKS rather than redoes, and the confirmation is what redoes.
 *
 * The widths are real window widths (`@Config` qualifiers), because the composer's bar is the app's
 * `bottomBar` and has no outer padding to hide behind. 320 dp is the narrowest supported phone, 360 dp is
 * the plan's smallest reference device, and 427 dp is the emulator the bug was found on. The font scale
 * is 2.0, the largest the app is tested at; icons do not grow with it but every label does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1000dp-xhdpi")
class ComposerLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(DrivingFixtures.composerState())
    private var fontScale by mutableFloatStateOf(1f)

    private var sends = 0
    private var queued = 0
    private val interrupts = mutableListOf<Boolean>()
    private var backgrounds = 0
    private var asked = 0
    private var confirmed = 0
    private var declined = 0

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun everyControlIsReachableAt320dp() = everyControlIsReachable()

    @Test
    fun everyControlIsReachableAt360dp() = everyControlIsReachable()

    @Test
    @Config(qualifiers = "w427dp-h1000dp-xhdpi")
    fun everyControlIsReachableAt427dp() = everyControlIsReachable()

    @Test
    fun sendKeepsItsPlaceWhenTheTurnStartsAndEnds() {
        show()
        state = idle()
        compose.waitForIdle()
        val whileIdle = bounds(send())

        state = idle().copy(busy = true)
        compose.waitForIdle()
        val whileBusy = bounds(send())

        // The icon row and Send never move when Stop and Background appear: a thumb that is resting on
        // Send when the turn starts must still be on it.
        assertEquals("Send moved when the turn started", whileIdle, whileBusy)
    }

    @Test
    fun sendingShowsAProgressStateAndDoesNotAnswerTaps() {
        show()
        state = idle().copy(sending = true)
        compose.waitForIdle()

        send().assertIsDisplayed().assertIsNotEnabled()
        assertEquals(
            "a screen reader is told the prompt is on its way",
            string(ComposerR.string.composer_sending),
            send().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
    }

    @Test
    fun anIdleSendHasNoStateDescription() {
        show()
        state = idle()
        compose.waitForIdle()

        assertNull(send().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun aStagedUndoIsSaidAboveTheFieldAndOnlyWhileItIsStaged() {
        show()
        state = ComposerFixtures.stagedUndo()
        compose.waitForIdle()

        compose.onNodeWithText(string(ComposerR.string.composer_staged_banner)).assertIsDisplayed()
        compose.onNodeWithText(string(ComposerR.string.composer_staged_files, 3)).assertIsDisplayed()
        // Each file by its own name, so all three are on screen at once and none is a path cut off mid-word.
        for (name in listOf("RetryPolicy.kt", "RetryPolicyTest.kt", "CHANGELOG.md")) {
            compose.onNodeWithText(name).assertIsDisplayed()
        }
        compose.onNodeWithText(string(ComposerR.string.composer_redo)).assertIsDisplayed()
        val banner = bounds(compose.onNodeWithText(string(ComposerR.string.composer_staged_banner)))
        val field = bounds(compose.onNode(hasText("Rename the retry helper and update its callers.")))
        assertTrue("the banner is above the field: $banner vs $field", banner.bottom <= field.top)

        state = idle()
        compose.waitForIdle()

        compose.onNodeWithText(string(ComposerR.string.composer_staged_banner)).assertDoesNotExist()
        compose.onNodeWithText(string(ComposerR.string.composer_redo)).assertDoesNotExist()
    }

    @Test
    fun aStagedUndoThatRestoredNoFilesSaysSoInsteadOfListingNothing() {
        show()
        state = ComposerFixtures.stagedUndo(files = false)
        compose.waitForIdle()

        compose.onNodeWithText(string(ComposerR.string.composer_staged_none)).assertIsDisplayed()
        compose.onNodeWithText(string(ComposerR.string.composer_redo)).assertIsDisplayed()
    }

    @Test
    fun redoAsksAndOnlyTheConfirmationRedoes() {
        show()
        state = ComposerFixtures.stagedUndo()
        compose.waitForIdle()

        compose.onNodeWithText(string(ComposerR.string.composer_redo)).performClick()
        compose.waitForIdle()
        // The tap asked. Nothing was redone, and the question is the composer's to hold, so it is not on
        // screen until the state says it is.
        assertEquals("the button asks once", 1, asked)
        assertEquals("the button does not redo", 0, confirmed)
        compose.onNodeWithText(string(ComposerR.string.composer_redo_confirm_title)).assertDoesNotExist()

        state = state.copy(confirmingRedo = true)
        compose.waitForIdle()
        compose.onNodeWithText(string(ComposerR.string.composer_redo_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(string(ComposerR.string.composer_redo_confirm_body)).assertIsDisplayed()

        compose.onNode(hasText(string(ComposerR.string.composer_redo)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals("the confirmation redoes", 1, confirmed)
        assertEquals("and does not ask again", 1, asked)
        assertEquals("and is not a decline", 0, declined)
    }

    @Test
    fun decliningTheRedoKeepsTheUndoAndRedoesNothing() {
        show()
        state = ComposerFixtures.stagedUndo().copy(confirmingRedo = true)
        compose.waitForIdle()

        compose.onNodeWithText(string(ComposerR.string.composer_redo_keep)).performClick()
        compose.waitForIdle()

        assertEquals(1, declined)
        assertEquals("declining is not a redo", 0, confirmed)
    }

    @Test
    fun busyComposerAt360dp() = capture("composer-busy-360", scale = 1f)

    @Test
    fun busyComposerAt360dpAtDoubleFontScale() = capture("composer-busy-360-font2", scale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun busyComposerWithAnAttachmentAt320dpAtDoubleFontScale() =
        capture("composer-busy-320-font2-attachment", scale = 2f, withAttachment = true)

    @Test
    fun stagedUndoAt360dp() = captureState("composer-staged-360", scale = 1f, staged = ComposerFixtures.stagedUndo())

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun stagedUndoAt320dpAtDoubleFontScale() =
        captureState("composer-staged-320-font2", scale = 2f, staged = ComposerFixtures.stagedUndo())

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun theRedoConfirmationAt360dpAtDoubleFontScale() {
        // The runtime's font scale, not the composition's: a dialog is a window of its own and takes its density
        // from the platform, so a scale set only on the composition would leave its text at 1x.
        RuntimeEnvironment.setFontScale(2f)
        state = ComposerFixtures.stagedUndo().copy(confirmingRedo = true)
        show()
        compose.waitForIdle()
        // The dialog is captured by itself and not as part of the composer.
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/composer-redo-confirm-360-font2.png")
    }

    @Test
    fun stagedUndoWithNoFilesAt360dp() =
        captureState("composer-staged-none-360", scale = 1f, staged = ComposerFixtures.stagedUndo(files = false))

    // ------------------------------------------------------------------------------------------------

    private fun everyControlIsReachable() {
        show()
        for (scenario in scenarios()) {
            for (scale in listOf(1f, 2f)) {
                state = scenario.state
                fontScale = scale
                compose.waitForIdle()
                val where = "${scenario.name} at ${fontScale}x"
                assertControlsAreWhole(where, busy = scenario.state.busy, staged = scenario.state.isStaged)
                if (scenario.state.canSend) assertSendAnswers(where)
                if (scenario.state.busy) assertStopAndBackgroundAnswer(where)
                if (scenario.state.isStaged) assertRedoAsks(where)
            }
        }
    }

    private class Scenario(val name: String, val state: ComposerUiState)

    private fun scenarios(): List<Scenario> = buildList {
        for (busy in listOf(false, true)) {
            val turn = if (busy) "running" else "idle"
            add(Scenario("$turn turn", idle().copy(busy = busy)))
            add(Scenario("$turn turn with attachments", ComposerFixtures.carryingContext().copy(busy = busy)))
            add(
                Scenario(
                    "$turn turn with the inbox chip",
                    idle().copy(busy = busy, pending = DrivingFixtures.composerState().pending),
                ),
            )
            add(Scenario("$turn turn while sending", idle().copy(busy = busy, sending = true)))
            add(Scenario("$turn turn with a staged undo", ComposerFixtures.stagedUndo().copy(busy = busy)))
            add(
                Scenario(
                    "$turn turn with a staged undo that restored no file",
                    ComposerFixtures.stagedUndo(files = false).copy(busy = busy),
                ),
            )
        }
    }

    /** Text in the box, so that Send is enabled; the fixture the driving screenshots use, made idle. */
    private fun idle(): ComposerUiState = DrivingFixtures.composerState().copy(busy = false, pending = emptyList())

    private fun assertControlsAreWhole(where: String, busy: Boolean, staged: Boolean = false) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val controls = buildMap {
            for (id in ICONS) put(string(id), compose.onNodeWithContentDescription(string(id)))
            put("Send", send())
            if (staged) put("Redo", compose.onNodeWithText(string(ComposerR.string.composer_redo)))
            if (busy) {
                put("Stop", compose.onNodeWithText(string(ComposerR.string.composer_interrupt)))
                put("Background", compose.onNodeWithText(string(ComposerR.string.composer_background)))
            }
        }
        val placed = controls.mapValues { (name, node) ->
            try {
                node.assertIsDisplayed()
            } catch (error: AssertionError) {
                throw AssertionError("$name is not displayed, $where: ${error.message}", error)
            }
            val touch = node.fetchSemanticsNode().touchBoundsInRoot
            assertTrue("$name is outside the window ($touch not in $root) $where", root.encloses(touch))
            val min = 48f * RuntimeEnvironment.getApplication().resources.displayMetrics.density
            assertTrue("$name is ${touch.width}x${touch.height} px, under 48 dp, $where", touch.width >= min - 1f)
            assertTrue("$name is ${touch.width}x${touch.height} px, under 48 dp, $where", touch.height >= min - 1f)
            touch
        }
        val names = placed.keys.toList()
        for (i in names.indices) {
            for (j in i + 1 until names.size) {
                val a = placed.getValue(names[i])
                val b = placed.getValue(names[j])
                assertTrue("${names[i]} $a overlaps ${names[j]} $b, $where", !a.overlapsBy(b))
            }
        }
        if (!busy) {
            compose.onNodeWithText(string(ComposerR.string.composer_interrupt)).assertDoesNotExist()
            compose.onNodeWithText(string(ComposerR.string.composer_background)).assertDoesNotExist()
        }
        if (!staged) compose.onNodeWithText(string(ComposerR.string.composer_redo)).assertDoesNotExist()
    }

    private fun assertRedoAsks(where: String) {
        asked = 0
        confirmed = 0
        val sendsBefore = sends
        compose.onNodeWithText(string(ComposerR.string.composer_redo)).performClick()
        compose.waitForIdle()
        assertEquals("a tap on Redo asks and does not redo, $where", 1 to 0, asked to confirmed)
        assertEquals("a tap on Redo sent, $where", sendsBefore, sends)
    }

    private fun assertSendAnswers(where: String) {
        sends = 0
        queued = 0
        interrupts.clear()
        backgrounds = 0
        send().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals("a tap on Send sends, $where", 1 to 0, sends to queued)
        assertTrue("a tap on Send reached Stop, $where", interrupts.isEmpty())
        assertEquals("a tap on Send reached Background, $where", 0, backgrounds)

        send().performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("a long press on Send queues, and does not also send, $where", 1 to 1, sends to queued)
        assertTrue("a long press on Send reached Stop, $where", interrupts.isEmpty())
        assertEquals("a long press on Send reached Background, $where", 0, backgrounds)
    }

    private fun assertStopAndBackgroundAnswer(where: String) {
        interrupts.clear()
        backgrounds = 0
        val sendsBefore = sends
        compose.onNodeWithText(string(ComposerR.string.composer_interrupt)).performClick()
        compose.onNodeWithText(string(ComposerR.string.composer_background)).performClick()
        compose.waitForIdle()
        assertEquals("Stop interrupts without resuming, $where", listOf(false), interrupts)
        assertEquals("Background backgrounds, $where", 1, backgrounds)
        assertEquals("Stop and Background do not send, $where", sendsBefore, sends)
    }

    private fun Rect.overlapsBy(other: Rect): Boolean {
        val w = minOf(right, other.right) - maxOf(left, other.left)
        val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
        return w > 1f && h > 1f
    }

    private fun Rect.encloses(inner: Rect): Boolean =
        inner.left >= left - 1f && inner.top >= top - 1f && inner.right <= right + 1f && inner.bottom <= bottom + 1f

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot

    private fun send(): SemanticsNodeInteraction = compose.onNodeWithContentDescription(
        string(ComposerR.string.composer_send) + ". " + string(ComposerR.string.composer_queue_on_long_press),
    )

    private fun string(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun show() {
        compose.setContent {
            // The same density the platform builds from a configuration whose font scale is `fontScale`,
            // set here rather than on the runtime so a test can change it between two states.
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        // Bottom-anchored with no padding, as the session screen's bottom bar is.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.testTag(COMPOSER)) {
                                ComposerBar(
                                    state = state,
                                    onTextChange = { _, _ -> },
                                    onSend = { sends++ },
                                    onSendQueued = { queued++ },
                                    onDeliveryChange = {},
                                    onResumeChange = {},
                                    onInterrupt = { resume -> interrupts += resume },
                                    onBackground = { backgrounds++ },
                                    onOpenInbox = {},
                                    onOpenAgentPicker = {},
                                    onOpenModelPicker = {},
                                    onCycleAgent = {},
                                    onCycleVariant = {},
                                    onAskRedo = { asked++ },
                                    onConfirmRedo = { confirmed++ },
                                    onDismissRedo = { declined++ },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** The busy composer, cropped to the composer so the baseline is the bar and not a blank window. */
    private fun capture(name: String, scale: Float, withAttachment: Boolean = false) {
        fontScale = scale
        state = if (withAttachment) {
            ComposerFixtures.carryingContext().copy(busy = true, pending = idle().pending)
        } else {
            idle().copy(busy = true)
        }
        show()
        compose.onNodeWithTag(COMPOSER).captureRoboImage("src/test/screenshots/$name.png")
    }

    /** A composer in [staged]'s state, cropped to the composer. */
    private fun captureState(name: String, scale: Float, staged: ComposerUiState) {
        fontScale = scale
        state = staged
        show()
        compose.onNodeWithTag(COMPOSER).captureRoboImage("src/test/screenshots/$name.png")
    }

    private companion object {
        const val COMPOSER = "composer"

        /** The seven icon buttons of the row, in the order the row lays them out. */
        val ICONS = listOf(
            ComposerR.string.composer_attach,
            ComposerR.string.composer_add_skill,
            ComposerR.string.composer_history_older,
            ComposerR.string.composer_history_newer,
            ComposerR.string.composer_stash,
            ComposerR.string.composer_stash_pop,
            ComposerR.string.composer_editor,
        )
    }
}
