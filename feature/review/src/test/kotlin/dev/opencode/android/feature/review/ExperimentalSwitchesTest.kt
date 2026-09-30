package dev.opencode.android.feature.review

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.opencode.android.core.data.preferences.ExperimentalPreferences
import dev.opencode.android.core.data.preferences.ExperimentalSettings
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The experimental switches sheet, and the rule that every preference has a row.
 *
 * **The bug this file exists for, twice.** `ExperimentalPreferences.configUpdate` had a setter, a screen
 * that waited for it (the configuration screen's shell card) and no row, so the card was disabled for
 * good while it told the user to turn the switch on "in settings" (manual test G7). `sessionInstructions`
 * had the same shape one screen over — the session instruction entries screen, with a hint naming "the
 * session-instructions experiment in settings" — and was found by asking why the first was the only broken
 * row. The count test is what stops a third: a preference added without a row fails here, by name.
 *
 * **The sheet and the view model are the real ones; the DataStore is not.** A switch has to be drawn from
 * what was *stored*, or it reads `true` for a turn the server will refuse, so what is under test is a flow
 * the setters write and the sheet reads — the shape the production store has. What is stubbed is the file
 * it lives in, which is not what this is about.
 *
 * **Not covered here: that `:app` hands each preference to its row.** `ExperimentalHost` is in the app
 * module, which has no Hilt or Robolectric test wiring (see `AGENTS.md`), so that half is a source check
 * and nothing more. What is held here is that the sheet, the view model and the preference agree, so a row
 * that is in the sheet is one a finger can reach.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentalSwitchesTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var preferences: RecordedPreferences
    private lateinit var model: ExperimentalSettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        preferences = RecordedPreferences()
        model = ExperimentalSettingsViewModel(preferences)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun showSheet() {
        compose.setContent {
            OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                val switches by model.settings.collectAsState()
                ExperimentalSettingsContent(
                    fileWrites = switches.fileWrites,
                    sessionTransfer = switches.sessionTransfer,
                    onFileWritesChange = model::setFileWrites,
                    onSessionTransferChange = model::setSessionTransfer,
                    configUpdate = switches.configUpdate,
                    onConfigUpdateChange = model::setConfigUpdate,
                    persistentPty = switches.persistentPty,
                    onPersistentPtyChange = model::setPersistentPty,
                    mcpRuntime = switches.mcpRuntime,
                    onMcpRuntimeChange = model::setMcpRuntime,
                    wellknownIntegrations = switches.wellknownIntegrations,
                    onWellknownIntegrationsChange = model::setWellknownIntegrations,
                    sessionInstructions = switches.sessionInstructions,
                    onSessionInstructionsChange = model::setSessionInstructions,
                )
            }
        }
    }

    // ---------------------------------------------------------------------------- the rule

    @Test
    fun `every preference has a row, and every row writes the one preference it is for`() {
        showSheet()

        val names = booleanFields(ExperimentalSettings::class.java)
        val rows = compose.onAllNodes(isToggleable())
        assertEquals(
            "ExperimentalSettings has ${names.size} switches and the sheet draws ${rows.fetchSemanticsNodes().size}",
            names.size,
            rows.fetchSemanticsNodes().size,
        )

        // One click per row, in the order they are drawn. Each must move exactly one preference off to on:
        // a row wired to the wrong setter moves none, and one that shares a switch moves two.
        val flipped = mutableSetOf<String>()
        repeat(names.size) { index ->
            val before = preferences.stored.value
            rows[index].performClick()
            compose.waitForIdle()
            val moved = preferences.stored.value.movedFrom(before)
            assertEquals("row $index moved ${moved.size} switches, not one", 1, moved.size)
            flipped += moved.single()
            // The switch shows what was stored, so a write that did not stick shows as still off.
            compose.onAllNodes(isToggleable())[index].assertIsOn()
        }
        assertEquals("a preference is not reachable from any row", names.toSet(), flipped)
    }

    @Test
    fun `every row is off by default, because every route behind it can change server state`() {
        showSheet()

        compose.onAllNodes(isToggleable()).fetchSemanticsNodes().indices.forEach { index ->
            compose.onAllNodes(isToggleable())[index].assertIsOff()
        }
        assertEquals("nothing was written to open the sheet", ExperimentalSettings(), preferences.stored.value)
    }

    // ----------------------------------------------------------------- the two rows that were missing

    @Test
    fun `the global configuration row says which file it reaches, and turns on that switch alone`() {
        showSheet()

        compose.onNodeWithText("Change the server's global configuration").assertExists()
        compose.onNodeWithText(
            "to the global file that every project on this server reads, not just this one",
            substring = true,
        ).assertExists()

        compose.onNodeWithText("Change the server's global configuration").performClick()

        assertTrue(preferences.stored.value.configUpdate)
        assertEquals(
            "one switch, and it is this one",
            setOf("configUpdate"),
            preferences.stored.value.movedFrom(ExperimentalSettings()),
        )
    }

    @Test
    fun `the session instructions row says when an entry takes effect, and turns on that switch alone`() {
        showSheet()

        compose.onNodeWithText("Session instruction entries").assertExists()
        compose.onNodeWithText(
            "They take effect at the next step of a turn that is already running",
            substring = true,
        ).assertExists()

        compose.onNodeWithText("Session instruction entries").performClick()

        assertTrue(preferences.stored.value.sessionInstructions)
        assertEquals(
            "one switch, and it is this one",
            setOf("sessionInstructions"),
            preferences.stored.value.movedFrom(ExperimentalSettings()),
        )
    }

    // --------------------------------------------------------------------------------- helpers

    /** The boolean members of [ExperimentalSettings], which is what a row is written for. */
    private fun booleanFields(type: Class<*>): List<String> =
        type.declaredFields.filter { it.type == Boolean::class.javaPrimitiveType }.map { it.name }

    /** The names of the boolean members that differ from [other]; every switch moves off to on. */
    private fun ExperimentalSettings.movedFrom(other: ExperimentalSettings): Set<String> =
        booleanFields(javaClass).filterTo(mutableSetOf()) { name -> read(name) != other.read(name) }

    private fun ExperimentalSettings.read(name: String): Boolean =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as Boolean

    /** The preferences in memory, written the way the view model writes them. */
    private class RecordedPreferences : ExperimentalPreferences {

        val stored = MutableStateFlow(ExperimentalSettings())

        override val settings: Flow<ExperimentalSettings> = stored

        override suspend fun setFileWrites(enabled: Boolean) = stored.update { it.copy(fileWrites = enabled) }

        override suspend fun setSessionTransfer(enabled: Boolean) =
            stored.update { it.copy(sessionTransfer = enabled) }

        override suspend fun setPersistentPty(enabled: Boolean) = stored.update { it.copy(persistentPty = enabled) }

        override suspend fun setMcpRuntime(enabled: Boolean) = stored.update { it.copy(mcpRuntime = enabled) }

        override suspend fun setWellknownIntegrations(enabled: Boolean) =
            stored.update { it.copy(wellknownIntegrations = enabled) }

        override suspend fun setConfigUpdate(enabled: Boolean) = stored.update { it.copy(configUpdate = enabled) }

        override suspend fun setSessionInstructions(enabled: Boolean) =
            stored.update { it.copy(sessionInstructions = enabled) }
    }
}
