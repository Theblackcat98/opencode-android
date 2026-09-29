package dev.opencode.android.feature.admin

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.opencode.android.core.data.config.ConfigDocuments
import dev.opencode.android.core.data.config.ConfigExplorer
import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.config.DefinitionKind
import dev.opencode.android.core.data.config.DocumentParseFailure
import dev.opencode.android.core.data.config.SchemaDiagnostic
import dev.opencode.android.core.data.config.TemplateOutcome
import dev.opencode.android.core.data.config.WritePlan
import dev.opencode.android.core.data.sync.SyncStatus
import dev.opencode.android.core.data.sync.SyncedState
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.ConfigEntry
import dev.opencode.android.core.model.InstructionEntry
import dev.opencode.android.core.model.LoadedLocation
import dev.opencode.android.core.model.MigrationStatus
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.PermissionEffect
import dev.opencode.android.core.model.PermissionRule
import dev.opencode.android.core.model.ReferenceInfo
import dev.opencode.android.core.model.ReferenceSource
import dev.opencode.android.core.model.SavedPermission
import dev.opencode.android.core.model.SkillInfo
import dev.opencode.android.core.testing.VendoredSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for the Phase 9 screens (plan §5.3, "UI"; §5.4, dynamic type).
 *
 * **Every value here is a placeholder, and the configuration ones are placeholders for a second reason.**
 * A baseline is a committed PNG, so a screenshot of the config explorer or the editor would put whatever
 * the document held into a file anyone can read — and a configuration file holds API keys. The document
 * used below therefore names `placeholder-api-key-not-a-real-credential` wherever a key would go, and
 * `ConfirmedWriteTest` proves separately that such a value is never rendered even if a user had one.
 *
 * **The states captured are the ones a user reads and then acts on**: the explorer with a mixed
 * configuration, the diagnostics on an invalid document, the write confirmation for a privilege change,
 * a guided template, the definition editor, the permissions screen with its precedence warning, the
 * maintenance screen, and the instruction entries. Light and dark, and once at 1.5× font.
 *
 * The screens are captured as plain composables rather than inside a dialog's window: a
 * `ModalBottomSheet` renders into a window a capture does not see, which is the failure P8 found and
 * fixed, and the confirmation below is an `AlertDialog` for the same reason — it is captured through
 * [AdminWriteDialogFixture], which renders the dialog's *content* rather than a host window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class AdminScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val schema = ConfigSchema.parse(VendoredSpec.configSchemaText())

    // ------------------------------------------------------------------ config explorer

    @Test
    fun configExplorer() = capture("config-explorer") { ConfigScreenFixture(configState()) }

    @Test
    fun configExplorerDark() = capture("config-explorer-dark", dark = true) { ConfigScreenFixture(configState()) }

    @Test
    fun configExplorerLargeFont() = capture("config-explorer-large-font", fontScale = 1.5f) {
        ConfigScreenFixture(configState())
    }

    @Test
    fun configExplorerConfiguredOnly() = capture("config-explorer-configured") {
        // The filter is passed to the screen, not baked into the fixture: a fixture that ignored it
        // produced a baseline byte-identical to the unfiltered one, which is the only way to notice.
        ConfigScreenFixture(configState(), configuredOnly = true)
    }

    @Test
    fun configExplorerEmpty() = capture("config-explorer-empty") { ConfigScreenFixture(ConfigUiState(loaded = true)) }

    @Test
    fun configExplorerFailed() = capture("config-explorer-failed") {
        ConfigScreenFixture(
            configState().copy(
                documents = ConfigDocuments(
                    entries = emptyList(),
                    rows = emptyList(),
                    failure = dev.opencode.android.core.data.integrations.ActionFailure(
                        dev.opencode.android.core.data.action.ActionError(
                            kind = dev.opencode.android.core.data.action.ActionErrorKind.UNAUTHORIZED,
                            message = "The credential was rejected",
                        ),
                    ),
                ),
                loaded = true,
                loading = false,
            ),
        )
    }

    // ------------------------------------------------------------------ the editor

    @Test
    fun configEditorValid() = capture("config-editor-valid") { ConfigEditorScreenFixture(editorState()) }

    @Test
    fun configEditorValidDark() = capture("config-editor-valid-dark", dark = true) {
        ConfigEditorScreenFixture(editorState())
    }

    @Test
    fun configEditorInvalid() = capture("config-editor-invalid") {
        ConfigEditorScreenFixture(
            editorState().copy(
                draft = "{\n" +
                    "  // the default model\n" +
                    "  \"modle\": \"placeholder-provider/placeholder-model\",\n" +
                    "  \"snapshot\": \"yes\"\n" +
                    "}",
                diagnostics = listOf(
                    SchemaDiagnostic(
                        "/modle",
                        "additionalProperties",
                        "a key this schema allows",
                        "a string of 9 characters",
                        line = 3,
                        column = 3,
                    ),
                    SchemaDiagnostic(
                        "/snapshot",
                        "type",
                        "a boolean",
                        "a string of 3 characters",
                        line = 4,
                        column = 3,
                    ),
                ),
            ),
        )
    }

    @Test
    fun configEditorSyntaxError() = capture("config-editor-syntax") {
        ConfigEditorScreenFixture(
            editorState().copy(
                parseFailure = DocumentParseFailure(
                    line = 4,
                    column = 14,
                    reason = "Expected '}' but had an unexpected value",
                ),
            ),
        )
    }

    @Test
    fun configEditorLargeFont() = capture("config-editor-large-font", fontScale = 1.5f) {
        ConfigEditorScreenFixture(editorState())
    }

    // ------------------------------------------------------------------ the write confirmation

    @Test
    fun writeConfirmationPrivilege() = capture("write-confirm-privilege") {
        AdminWriteDialogFixture(privilegePlan())
    }

    @Test
    fun writeConfirmationPlain() = capture("write-confirm-plain") {
        AdminWriteDialogFixture(plainPlan())
    }

    @Test
    fun writeConfirmationLargeFont() = capture("write-confirm-large-font", fontScale = 1.5f) {
        AdminWriteDialogFixture(privilegePlan())
    }

    // ------------------------------------------------------------------ templates

    @Test
    fun templatePermission() = capture("template-permission") {
        TemplateSheetFixture(
            draft = ConfigTemplateDraft(
                choice = ConfigTemplateChoice.PERMISSION,
                action = "bash",
                resource = "*",
                effect = "ask",
            ),
            outcome = null,
        )
    }

    @Test
    fun templatePermissionValid() = capture("template-permission-valid") {
        TemplateSheetFixture(
            draft = ConfigTemplateDraft(
                choice = ConfigTemplateChoice.PERMISSION,
                action = "bash",
                resource = "*",
                effect = "ask",
            ),
            outcome = TemplateOutcome.Ready(
                kotlinx.serialization.json.Json.parseToJsonElement("""{"permission":{"bash":{"*":"ask"}}}"""),
            ),
        )
    }

    @Test
    fun templateModel() = capture("template-model") {
        TemplateSheetFixture(ConfigTemplateDraft(choice = ConfigTemplateChoice.MODEL), outcome = null)
    }

    @Test
    fun templateMcp() = capture("template-mcp") {
        TemplateSheetFixture(
            ConfigTemplateDraft(choice = ConfigTemplateChoice.MCP, name = "files", mcpUrl = "https://mcp.example.com/"),
            outcome = null,
        )
    }

    @Test
    fun templateMcpBadUrl() = capture("template-mcp-bad-url") {
        TemplateSheetFixture(
            ConfigTemplateDraft(choice = ConfigTemplateChoice.MCP, name = "files", mcpUrl = "file:///etc/passwd"),
            outcome = null,
        )
    }

    @Test
    fun templateAgent() = capture("template-agent") {
        TemplateSheetFixture(ConfigTemplateDraft(choice = ConfigTemplateChoice.AGENT, name = "review"), outcome = null)
    }

    // ------------------------------------------------------------------ definitions

    @Test
    fun definitionEditor() = capture("definition-agent") { DefinitionScreenFixture(definitionState()) }

    @Test
    fun definitionEditorDark() = capture("definition-agent-dark", dark = true) {
        DefinitionScreenFixture(definitionState())
    }

    @Test
    fun definitionEditorInvalidName() = capture("definition-agent-bad-name") {
        DefinitionScreenFixture(
            definitionState().copy(
                name = "../escape",
                nameProblem = "A name may use letters, digits, dots, dashes and underscores only",
                path = "",
            ),
        )
    }

    // ------------------------------------------------------------------ permissions

    @Test
    fun permissionsScreen() = capture("permissions-saved") { PermissionsScreenFixture(permissionsState()) }

    @Test
    fun permissionsScreenDark() = capture("permissions-saved-dark", dark = true) {
        PermissionsScreenFixture(permissionsState())
    }

    @Test
    fun permissionsRulesEditing() = capture("permissions-rules-editing") {
        PermissionsScreenFixture(permissionsState(editing = true))
    }

    @Test
    fun permissionsRemoveConfirm() = capture("permissions-remove-saved") {
        PermissionsScreenFixture(
            permissionsState().copy(
                removeTarget = SavedPermission(
                    id = "perm_1",
                    projectID = "prj_1",
                    action = "bash",
                    resource = "git status",
                    time = SavedPermission.Time(1_700_000_000_000, 1_700_000_000_000),
                ),
            ),
        )
    }

    @Test
    fun permissionsLargeFont() = capture("permissions-large-font", fontScale = 1.5f) {
        PermissionsScreenFixture(permissionsState())
    }

    // ------------------------------------------------------------------ maintenance

    @Test
    fun maintenanceScreen() = capture("maintenance") { MaintenanceScreenFixture(maintenanceState()) }

    @Test
    fun maintenanceScreenDark() = capture("maintenance-dark", dark = true) {
        MaintenanceScreenFixture(maintenanceState())
    }

    @Test
    fun maintenanceEvictConfirm() = capture("maintenance-evict") {
        MaintenanceScreenFixture(
            maintenanceState().copy(evictTarget = LoadedLocation("/work/app")),
        )
    }

    @Test
    fun maintenanceMigrationRunning() = capture("maintenance-migration") {
        MaintenanceScreenFixture(
            maintenanceState().copy(
                migration = MigrationStatus.Running(
                    MigrationStatus.Running.Progress("Importing sessions", 40, 100),
                ),
            ),
        )
    }

    @Test
    fun maintenanceMigrationUncounted() = capture("maintenance-migration-uncounted") {
        MaintenanceScreenFixture(
            maintenanceState().copy(
                migration = MigrationStatus.Running(MigrationStatus.Running.Progress("Starting")),
            ),
        )
    }

    // ------------------------------------------------------------------ instructions

    @Test
    fun instructionsScreen() = capture("instructions") { InstructionsScreenFixture(instructionsState()) }

    @Test
    fun instructionsScreenOff() = capture("instructions-off") {
        InstructionsScreenFixture(instructionsState(usable = false))
    }

    @Test
    fun instructionsDark() = capture("instructions-dark", dark = true) {
        InstructionsScreenFixture(instructionsState())
    }

    // ------------------------------------------------------------------ catalogs

    @Test
    fun catalogAgents() = capture("catalog-agents") { CatalogScreenFixture(catalogState(AdminCatalog.AGENTS)) }

    @Test
    fun catalogAgentsDark() = capture("catalog-agents-dark", dark = true) {
        CatalogScreenFixture(catalogState(AdminCatalog.AGENTS))
    }

    @Test
    fun catalogCommands() = capture("catalog-commands") { CatalogScreenFixture(catalogState(AdminCatalog.COMMANDS)) }

    @Test
    fun catalogSkills() = capture("catalog-skills") { CatalogScreenFixture(catalogState(AdminCatalog.SKILLS)) }

    @Test
    fun catalogReferences() = capture("catalog-references") {
        CatalogScreenFixture(catalogState(AdminCatalog.REFERENCES))
    }

    @Test
    fun catalogLargeFont() = capture("catalog-large-font", fontScale = 1.5f) {
        CatalogScreenFixture(catalogState(AdminCatalog.AGENTS))
    }

    // ------------------------------------------------------------------------------ fixtures

    private fun capture(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                OpenCodeTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        content()
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("$ROBO_PATH/$name.png")
    }

    private companion object {
        const val ROBO_PATH = "src/test/screenshots"

        /** A value that is shaped like a credential and is not one. */
        const val PLACEHOLDER_KEY = "placeholder-api-key-not-a-real-credential"

        fun document(path: String, info: dev.opencode.android.core.model.ConfigInfo) =
            ConfigEntry.Document(pathOrNull = path, info = info)

        fun parse(text: String) = kotlinx.serialization.json.Json.parseToJsonElement(text)

        val globalConfig = document(
            "/root/.config/opencode/opencode.json",
            dev.opencode.android.core.model.ConfigInfo(
                model = dev.opencode.android.core.model.ConfigModel.Ref("placeholder-provider", "placeholder-model"),
                shell = "/bin/sh",
            ),
        )

        /**
         * A project block with a provider whose key is named, which is the redaction rule exercised on
         * a real screen: the row shows the key as a length and never as text.
         */
        private val providerBlock = parse(
            """{"llama":{"options":{"apiKey":"$PLACEHOLDER_KEY","baseURL":"http://127.0.0.1:11434/v1"}}}""",
        )

        val projectConfig = document(
            "/work/app/.opencode/opencode.jsonc",
            dev.opencode.android.core.model.ConfigInfo(
                model = dev.opencode.android.core.model.ConfigModel.Ref("placeholder-provider", "other-model"),
                shell = "/bin/zsh",
                share = "disabled",
                default_agent = "build",
            ).copy(
                permissions = listOf(PermissionRule("bash", "*", PermissionEffect.Ask)),
                // The projection's own name, which is what `ConfigInfo` carries; `snapshot` is the
                // file's spelling and the explorer maps between them.
                snapshots = true,
                watcher = parse("""{"ignore":[".git/**"]}"""),
                providers = providerBlock,
            ),
        )

        fun configState() = ConfigUiState(
            directory = "/work/app",
            documents = ConfigDocuments(
                entries = listOf(
                    ConfigEntry.Directory(pathOrNull = "/work"),
                    globalConfig,
                    projectConfig,
                ),
                rows = ConfigExplorer.rows(
                    ConfigSchema.parse(VendoredSpec.configSchemaText()),
                    listOf(ConfigEntry.Directory("/work"), globalConfig, projectConfig),
                ),
            ),
            loading = false,
            loaded = true,
            shellUsable = true,
            currentShell = "/bin/zsh",
            shellChoice = "/bin/zsh",
        )

        val editorText = """
            {
              // the default model for new sessions
              "model": "placeholder-provider/placeholder-model",
              "share": "disabled",
              "mcp": {
                "files": {
                  "type": "remote",
                  "url": "https://mcp.example.com/"
                }
              }
            }
        """.trimIndent()

        fun editorState() = ConfigEditorUiState(
            directory = "/work/app",
            path = ".opencode/opencode.jsonc",
            text = editorText,
            draft = editorText,
            isNewFile = false,
            writesUsable = true,
        )

        fun privilegePlan() = WritePlan(
            target = ".opencode/opencode.jsonc",
            text = editorText,
            consequence = "This replaces .opencode/opencode.jsonc; the server reads it after a reload",
            isPrivilegeChange = true,
            bytes = editorText.toByteArray().size,
            previousBytes = 180,
        )

        fun plainPlan() = privilegePlan().copy(
            target = "AGENTS.md",
            consequence = "Every agent working in this location is told this instead of what it was told",
            isPrivilegeChange = false,
            previousBytes = null,
        )

        val agentFrontMatter = """
            ---
            description: "Reviews a diff"
            mode: "subagent"
            model: "placeholder-provider/placeholder-model"
            steps: "20"
            ---

            # Review

            Read the diff and report anything that looks wrong.
        """.trimIndent()

        fun definitionState() = DefinitionUiState(
            directory = "/work/app",
            kind = DefinitionKind.AGENT,
            name = "review",
            path = ".opencode/agents/review.md",
            text = agentFrontMatter,
            draft = agentFrontMatter,
            isNewFile = false,
            writesUsable = true,
        )

        fun permissionsState(editing: Boolean = false) = PermissionsUiState(
            directory = "/work/app",
            projectID = "prj_1",
            sessionID = "ses_1",
            savedLoaded = true,
            saved = listOf(
                SavedPermission(
                    "perm_1",
                    "prj_1",
                    "bash",
                    "git status",
                    SavedPermission.Time(1_700_000_000_000, 1_700_000_000_000),
                ),
                SavedPermission(
                    "perm_2",
                    "prj_1",
                    "edit",
                    "/work/app/src/**",
                    SavedPermission.Time(1_700_000_100_000, 1_700_000_100_000),
                ),
            ),
            rules = listOf(
                PermissionRule("bash", "*", PermissionEffect.Ask),
                PermissionRule("external_directory", "/tmp/**", PermissionEffect.Deny),
            ),
            drafts = listOf(
                PermissionRuleDraft("bash", "*", PermissionEffect.Ask, null),
                PermissionRuleDraft("external_directory", "/tmp/**", PermissionEffect.Deny, null),
            ),
            editing = editing,
        )

        fun maintenanceState() = MaintenanceUiState(
            locationsLoaded = true,
            locations = listOf(
                LoadedLocation("/work/app"),
                LoadedLocation("/work/other"),
            ),
            migration = MigrationStatus.Required,
            migrationLoaded = true,
        )

        fun instructionsState(usable: Boolean = true) = InstructionsUiState(
            sessionID = "ses_1",
            loaded = true,
            usable = usable,
            entries = listOf(
                InstructionEntry("tools", parse("""{"bash":false}""")),
                InstructionEntry("tone", kotlinx.serialization.json.JsonPrimitive("terse")),
            ),
            key = "tone",
            value = "concise",
        )

        fun catalogState(tab: AdminCatalog) = CatalogUiState(
            tab = tab,
            agents = SyncedState(
                listOf(
                    AgentInfo(
                        id = "build",
                        name = "build",
                        mode = "primary",
                        model = ModelRef("placeholder-provider", "placeholder-model"),
                        description = "Writes code and runs commands",
                        steps = 100,
                        color = "primary",
                        permissions = kotlinx.serialization.json.Json.parseToJsonElement(
                            """{"bash":"ask","edit":{"*":"allow"}}""",
                        ),
                        system = "You are a build agent. Make the change, then run the tests.",
                    ),
                    AgentInfo(id = "review", name = "review", mode = "subagent", description = "Reviews a diff"),
                ),
                SyncStatus.Ready,
            ),
            commands = SyncedState(
                listOf(CommandInfo("deploy", "Deploys the current branch")),
                SyncStatus.Ready,
            ),
            skills = SyncedState(
                listOf(
                    SkillInfo(
                        id = "lint",
                        name = "lint",
                        description = "Runs the linter and explains the failures",
                        path = ".opencode/skills/lint",
                        content = "# Lint\n\nRun the project's linter and summarise what it reports.",
                    ),
                ),
                SyncStatus.Ready,
            ),
            references = SyncedState(
                listOf(
                    ReferenceInfo(
                        name = "docs",
                        path = "./docs",
                        description = "The project's documentation",
                        source = ReferenceSource.Local("./docs"),
                    ),
                    ReferenceInfo(
                        name = "upstream",
                        path = "",
                        source = ReferenceSource.Git("https://example.com/repo", "main"),
                    ),
                ),
                SyncStatus.Ready,
            ),
        )
    }
}
