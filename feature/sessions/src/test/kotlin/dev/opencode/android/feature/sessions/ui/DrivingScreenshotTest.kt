package dev.opencode.android.feature.sessions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.opencode.android.core.data.catalog.ModelCatalog
import dev.opencode.android.core.data.forms.FormEngine
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.StructuredError
import dev.opencode.android.feature.composer.ui.AgentPicker
import dev.opencode.android.feature.composer.ui.ComposerBar
import dev.opencode.android.feature.composer.ui.ModelRow
import dev.opencode.android.feature.composer.ui.NewSessionContent
import dev.opencode.android.feature.requests.ui.ElicitationContent
import dev.opencode.android.feature.requests.ui.FormFields
import dev.opencode.android.feature.requests.ui.PendingRequestsScreen
import dev.opencode.android.feature.requests.ui.PermissionCard
import dev.opencode.android.feature.requests.ui.RequestActions
import dev.opencode.android.feature.requests.ui.RequestDock
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the Phase 3 screens (plan §5.3, "UI"; §5.4, dynamic type).
 *
 * The driving surfaces are the ones a user reads under time pressure — a permission to approve, a
 * question to answer, a prompt to send — so they are captured in light and dark and at a large font
 * scale, and every field type the forms engine supports appears in one shot. A layout that only works
 * at 1.0× is not a layout.
 *
 * Sheets are captured through their body composable ([NewSessionContent], [ElicitationContent]) rather
 * than the overlay: a `ModalBottomSheet` needs a sheet host, and an empty overlay screenshot would show
 * nothing about the form the user actually reads.
 *
 * Dynamic colour is off so the baselines are stable across the machines CI runs on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class DrivingScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun composer() = capture("composer") {
        ComposerBar(
            state = DrivingFixtures.composerState(),
            onTextChange = { _, _ -> },
            onSend = {},
            onSendQueued = {},
            onDeliveryChange = {},
            onResumeChange = {},
            onInterrupt = {},
            onBackground = {},
            onOpenInbox = {},
            onOpenAgentPicker = {},
            onOpenModelPicker = {},
            onCycleAgent = {},
            onCycleVariant = {},
        )
    }

    @Test
    fun requestDock() = capture("request-dock") {
        RequestDock(requests = DrivingFixtures.dockRequests(), actions = RequestActions())
    }

    @Test
    fun permissionCard() = capture("permission-card") {
        PermissionCard(
            request = DrivingFixtures.permissionRequest(),
            actions = RequestActions(),
        )
    }

    @Test
    fun formFields() = capture("form-fields") {
        FormFields(
            fields = DrivingFixtures.elicitationForm().fields,
            answers = FormEngine.initialState(DrivingFixtures.elicitationForm()),
            onAnswerChange = { _, _ -> },
            onOpenLink = {},
        )
    }

    @Test
    fun formFieldsWithProblems() = capture("form-fields-problems") {
        val form = DrivingFixtures.elicitationForm()
        FormFields(
            fields = form.fields,
            answers = mapOf("scope" to JsonPrimitive("Not A Scope")),
            onAnswerChange = { _, _ -> },
            onOpenLink = {},
            showProblems = true,
        )
    }

    @Test
    fun questionForm() = capture("form-question") {
        val form = DrivingFixtures.questionForm()
        FormFields(
            fields = form.fields,
            answers = mapOf("q0" to JsonPrimitive("bash")),
            onAnswerChange = { _, _ -> },
            onOpenLink = {},
        )
    }

    @Test
    fun elicitationSheet() = capture("form-elicitation", dark = true) {
        val form = DrivingFixtures.elicitationForm()
        ElicitationContent(
            form = form,
            answers = FormEngine.initialState(form),
            onAnswerChange = { _, _ -> },
            actions = RequestActions(),
            busy = false,
        )
    }

    @Test
    fun pendingRequestsScreen() = capture("requests-inbox") {
        PendingRequestsScreen(
            requests = DrivingFixtures.dockRequests(),
            sessionTitles = mapOf("ses_1" to "Fix the timeline convergence check"),
            actions = RequestActions(),
            onNavigateBack = {},
        )
    }

    @Test
    fun retryBanner() = capture("retry-banner") {
        RetryBanner(retry = DrivingFixtures.retryBanner(), now = 1_700_000_000_000L, onOpenLink = {})
    }

    @Test
    fun errorCard() = capture("error-card") {
        ErrorCard(
            error = StructuredError(
                type = "provider.auth",
                message = "usage exceeded for this key",
                status = 429,
            ),
        )
    }

    @Test
    fun newSession() = capture("new-session") {
        NewSessionContent(
            state = DrivingFixtures.newSessionState(),
            favorites = emptyList(),
            recents = emptyList(),
            modelSearch = "",
            onTitleChange = {},
            onSelectProject = {},
            onSelectDirectory = {},
            onOpenBrowser = {},
            onPathDraftChange = {},
            onSelectAgent = {},
            onSelectModel = {},
            onCreate = {},
        )
    }

    @Test
    fun agentPicker() = capture("agent-picker", dark = true) {
        AgentPicker(
            agents = DrivingFixtures.newSessionState().primaryAgents,
            selected = "build",
            onSelect = {},
        )
    }

    @Test
    fun modelRows() = capture("model-rows") {
        val groups = ModelCatalog.group(DrivingFixtures.newSessionState().models)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            groups.forEach { group ->
                group.entries.forEach { entry ->
                    ModelRow(
                        name = entry.info.name,
                        ref = entry.ref,
                        supportsTools = entry.supportsTools,
                        supportsImages = entry.supportsImageInput,
                        contextTokens = entry.info.limit.context,
                        inputCost = entry.inputCost,
                        outputCost = entry.outputCost,
                        selected = entry.info.id == "text",
                        isFavorite = entry.isFavorite,
                        variants = ModelCatalog.variantsOf(entry.info).map { it.id },
                        activeVariant = "default",
                        onSelect = {},
                        onSelectVariant = {},
                        onToggleFavorite = {},
                    )
                }
            }
        }
    }

    @Test
    fun drivingDarkTheme() = capture("driving-dark", dark = true) {
        ComposerBar(
            state = DrivingFixtures.composerState(),
            onTextChange = { _, _ -> },
            onSend = {},
            onSendQueued = {},
            onDeliveryChange = {},
            onResumeChange = {},
            onInterrupt = {},
            onBackground = {},
            onOpenInbox = {},
            onOpenAgentPicker = {},
            onOpenModelPicker = {},
            onCycleAgent = {},
            onCycleVariant = {},
        )
        RequestDock(requests = DrivingFixtures.dockRequests(), actions = RequestActions())
    }

    @Test
    fun drivingLargeFontScale() = capture("driving-large-font", fontScale = 1.5f) {
        ComposerBar(
            state = DrivingFixtures.composerState(),
            onTextChange = { _, _ -> },
            onSend = {},
            onSendQueued = {},
            onDeliveryChange = {},
            onResumeChange = {},
            onInterrupt = {},
            onBackground = {},
            onOpenInbox = {},
            onOpenAgentPicker = {},
            onOpenModelPicker = {},
            onCycleAgent = {},
            onCycleVariant = {},
        )
        RequestDock(requests = DrivingFixtures.dockRequests(), actions = RequestActions())
    }

    /** Renders [content] in the app theme and writes the image Roborazzi compares against. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        // Dynamic type is set on the runtime, not through the composition, so a card laid out at
        // 1.5x is the same card the system would lay out for a user who asked for it.
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            OpenCodeTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        content()
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
