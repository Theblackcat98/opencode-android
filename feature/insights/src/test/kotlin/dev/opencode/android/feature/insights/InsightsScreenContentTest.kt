package dev.opencode.android.feature.insights

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.SessionStats
import dev.opencode.android.core.model.SessionStatsTools
import dev.opencode.android.core.model.TokenUsage
import dev.opencode.android.core.model.ToolDetailMode
import dev.opencode.android.core.model.ToolTotals
import dev.opencode.android.core.model.ToolUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the usage dashboard actually **renders**, asserted on the node tree.
 *
 * **A screenshot baseline cannot be asked what it says.** A PNG that shows four cards and one that
 * shows four cards with the wrong numbers look the same to a diff, so the numbers are asserted here
 * and the pixels are asserted by the baseline set. The two together are what make "the dashboard
 * shows the server's figures" a statement about the app.
 *
 * The assertions worth having are the ones about *not lying*:
 *
 *  - a period with no calls says "no calls" and never "0% reliable", which would blame the user for a
 *    question nobody asked;
 *  - `tools=none` says the column is off rather than showing zeroes that look like a measurement;
 *  - an unrecognised mode says so instead of drawing zeros;
 *  - the heatmap grid is one description rather than 365 numbers, because a screen reader announcing a
 *    day at a time is unusable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class InsightsScreenContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val tokens = TokenUsage(100, 40, 5, TokenUsage.Cache(7, 2))

    private fun stats(
        sessions: Long = 4,
        tools: SessionStatsTools = SessionStatsTools.Summary(ToolTotals(0, 0, 0, 0)),
    ) = SessionStats(
        range = SessionStats.Range(1000, 2000),
        sessions = sessions,
        subagents = 2,
        prompts = 9,
        steps = 31,
        tokens = tokens,
        cost = 1.25,
        tools = tools,
        activeDays = 3,
        streak = 2,
        activity = listOf(
            SessionStats.Activity("2026-01-01", 5),
            SessionStats.Activity("2026-01-02", 26),
        ),
        models = listOf(
            SessionStats.ModelUsage(ModelRef("m1", "p1"), 3, tokens, 0.5),
        ),
    )

    private fun show(state: InsightsUiState) {
        compose.setContent {
            InsightsScreen(
                state = state,
                onRangeChange = {},
                onToggleToolDetail = {},
                onDismissError = {},
            )
        }
    }

    @Test
    fun `the totals are the servers numbers`() {
        show(InsightsUiState(stats = stats()))

        compose.onNodeWithText("Sessions").assertIsDisplayed()
        compose.onAllNodesWithText("4")[0].assertIsDisplayed()
        compose.onNodeWithText("Prompts").assertIsDisplayed()
        compose.onNodeWithText("9").assertIsDisplayed()
        compose.onNodeWithText("Cost").assertIsDisplayed()
        compose.onNodeWithText("$1.25").assertIsDisplayed()
    }

    @Test
    fun `token counts are broken out and the cache is shown when there is one`() {
        show(InsightsUiState(stats = stats()))

        compose.onNodeWithText("Input").assertIsDisplayed()
        compose.onNodeWithText("100").assertIsDisplayed()
        compose.onNodeWithText("Output").assertIsDisplayed()
        compose.onNodeWithText("40").assertIsDisplayed()
        compose.onNodeWithText("read 7, written 2").assertIsDisplayed()
    }

    /** A cache of zero is not a number worth a row. */
    @Test
    fun `a zero cache and a zero reasoning count are not shown`() {
        val bare = stats().copy(tokens = TokenUsage(1, 1, 0, TokenUsage.Cache(0, 0)))
        show(InsightsUiState(stats = bare))

        compose.onNodeWithText("Input").assertIsDisplayed()
        compose.onAllNodesWithText("read 0, written 0").assertCountEquals(0)
        compose.onAllNodesWithText("Reasoning").assertCountEquals(0)
    }

    @Test
    fun `a period with no tool calls says so rather than showing zero percent`() {
        show(InsightsUiState(stats = stats()))

        // `ToolTotals(0, 0, 0, 0).successRate` is null, and 0 of 0 is not a reliability figure.
        compose.onNodeWithText("No calls in this range").assertIsDisplayed()
        compose.onAllNodesWithText("0% of calls succeeded").assertCountEquals(0)
    }

    @Test
    fun `a real success rate is a percentage`() {
        val tools = SessionStatsTools.Summary(ToolTotals(10, 8, 1, 1))
        show(InsightsUiState(stats = stats(tools = tools)))

        compose.onNodeWithText("80% of calls succeeded").assertIsDisplayed()
    }

    @Test
    fun `tools off says the column is off`() {
        show(InsightsUiState(stats = stats(tools = SessionStatsTools.None)))

        compose.onNodeWithText("Tool reliability is switched off for this query.").assertIsDisplayed()
    }

    @Test
    fun `a mode this build does not know says so instead of drawing zeros`() {
        val unknown = SessionStatsTools.Unknown("heatmap", kotlinx.serialization.json.JsonObject(emptyMap()))
        show(InsightsUiState(stats = stats(tools = unknown)))

        compose.onNodeWithText("This server reported a tool mode this version does not know.")
            .assertIsDisplayed()
        compose.onAllNodesWithText("No calls in this range").assertCountEquals(0)
    }

    @Test
    fun `the detail lists every tool the server sent`() {
        val tools = SessionStatsTools.Detail(
            totals = ToolTotals(6, 6, 0, 0),
            usage = listOf(
                ToolUsage("bash", 6, 6, 0, 0, durationP50 = 42.5),
                ToolUsage("read", 1, 0, 1, 0, durationP50 = 3.0),
            ),
        )
        show(InsightsUiState(stats = stats(tools = tools), tools = ToolDetailMode.Detail))

        compose.onNodeWithText("bash").assertIsDisplayed()
        compose.onNodeWithText("100% succeeded, p50 42 ms").assertIsDisplayed()
        compose.onNodeWithText("read").assertIsDisplayed()
        compose.onNodeWithText("0% succeeded, p50 3 ms").assertIsDisplayed()
    }

    @Test
    fun `the heatmap is one description rather than a number per day`() {
        show(InsightsUiState(stats = stats()))

        compose.onNodeWithText("Streak: 2 days").assertIsDisplayed()
        // The grid is a description, not text: a screen reader announcing one day at a time would be
        // unusable, so the cells carry no semantics and the card carries this sentence.
        compose.onNode(
            hasContentDescription("3 active days, a streak of 2, busiest day 26 steps"),
        ).assertExists()
    }

    @Test
    fun `an empty range says so instead of drawing an empty grid`() {
        val empty = stats().copy(activity = emptyList())
        show(InsightsUiState(stats = empty))

        compose.onNodeWithText("No activity in this range.").assertIsDisplayed()
    }

    @Test
    fun `an error is shown with something to dismiss it`() {
        val state = InsightsUiState(
            error = ActionError(ActionErrorKind.OFFLINE, "The server could not be reached"),
        )
        show(state)

        compose.onNodeWithText("The server could not be reached").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
    }

    @Test
    fun `a server without the route is not offered a broken page`() {
        val state = InsightsUiState(availability = RouteAvailability.Absent(404))
        assertTrue("the screen hides itself rather than showing an error", !state.available)
    }

    @Test
    fun `the ranges are the ones the plan names`() {
        assertEquals(listOf(7, 30, 90, 365), StatsRange.offered.map { it.days })
        assertEquals(30, StatsRange.Default.days)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(expected: Int) {
        assertEquals(expected, fetchSemanticsNodes().size)
    }
}
