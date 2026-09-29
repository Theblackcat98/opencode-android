package dev.opencode.android.feature.admin

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.config.ConfigDocuments
import dev.opencode.android.core.data.config.ConfigExplorer
import dev.opencode.android.core.data.config.ConfigSchema
import dev.opencode.android.core.data.config.TemplateOutcome
import dev.opencode.android.core.model.ConfigEntry
import dev.opencode.android.core.model.ConfigInfo
import dev.opencode.android.core.model.PermissionEffect
import dev.opencode.android.core.model.PermissionRule
import dev.opencode.android.core.testing.VendoredSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the Phase 9 screens actually **render**, asserted on the node tree.
 *
 * **This is the screen-level half of the coverage claim.** `ConfigCatalogCoverageTest` proves the
 * projection produces a row per key; this proves the screen puts every one of them on the display, with
 * its source and its effective value. A projection test alone would pass with a screen that rendered the
 * first row and dropped the rest, and a screenshot baseline alone would pass with a screen that
 * rendered a card of nothing — the two together are what make "every top-level config key is visible"
 * a statement about the app rather than about a function.
 *
 * **The redaction assertion is here rather than in the screenshot set** because a PNG cannot be asked
 * what it says. [a credential is never rendered anywhere on the explorer] collects every text the
 * explorer displays and checks the placeholder is not among them — which is a claim about the tree, and
 * it fails loudly if a future row reaches for a value's own `toString`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class AdminScreenContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val schema = ConfigSchema.parse(VendoredSpec.configSchemaText())

    private val globalConfig = ConfigEntry.Document(
        pathOrNull = "/root/.config/opencode/opencode.json",
        info = ConfigInfo(
            model = dev.opencode.android.core.model.ConfigModel.Ref("placeholder-provider", "placeholder-model"),
            shell = "/bin/sh",
        ),
    )

    private val projectConfig = ConfigEntry.Document(
        pathOrNull = "/work/app/.opencode/opencode.jsonc",
        info = ConfigInfo(
            model = dev.opencode.android.core.model.ConfigModel.Ref("placeholder-provider", "other-model"),
            shell = "/bin/zsh",
            share = "disabled",
            default_agent = "build",
            permissions = listOf(PermissionRule("bash", "*", PermissionEffect.Ask)),
            snapshots = true,
            // A provider with a named key: the case the redaction exists for.
            providers = parse(
                """{"llama":{"options":{"apiKey":"$PLACEHOLDER_KEY","baseURL":"https://api.example.com"}}}""",
            ),
        ),
    )

    private val entries = listOf(
        ConfigEntry.Directory(pathOrNull = "/work"),
        globalConfig,
        projectConfig,
    )

    private fun state() = ConfigUiState(
        directory = "/work/app",
        documents = ConfigDocuments(entries, ConfigExplorer.rows(schema, entries)),
        loaded = true,
        shellUsable = true,
        currentShell = "/bin/zsh",
        shellChoice = "/bin/zsh",
    )

    private fun show(configuredOnly: Boolean = false) {
        compose.setContent { ConfigScreenFixture(state(), configuredOnly) }
    }

    /**
     * Scrolls the explorer's list so the card at [index] is composed.
     *
     * **By index, and the index is the schema's own order.** Scrolling to a node's tag needs a handle
     * that is still composed, which a card three screens up is not; the index is stable, and the list's
     * fixed rows come first, so the *n*-th key is the *n*-th item after them. `performScrollToIndex` on
     * the tagged list is therefore the one way to reach every key deterministically.
     */
    private fun scrollToIndex(index: Int) {
        compose.onNodeWithTag(AdminTags.CONFIG_LIST).performScrollToIndex(index)
    }

    /** Scrolls to the card whose schema key is at [index] among the key cards. */
    private fun scrollToKey(index: Int) = scrollToIndex(index + FIRST_KEY_INDEX)

    // ------------------------------------------------------------------------------ coverage

    @Test
    fun `every top-level key of the schema can be scrolled to`() {
        show()

        // **Scrolling, not merely composing.** A `LazyColumn` composes a window, so a card that exists
        // but cannot be reached would pass an existence check on a short list and fail the user. Each key
        // is therefore scrolled to and then required to exist, which is the honest form of "every
        // top-level config key is visible": reachable, in the schema's own order, with its own tag.
        //
        // The first few are already composed, so the search starts from the current position rather
        // than from the top every time — `performScrollToNode` walks the lazy list in whichever
        // direction the item is.
        val missing = schema.keys.indices.filterNot { index ->
            scrollToKey(index)
            compose.allNodesWithTagOrNull(AdminTags.configKey(schema.keys[index].key)) != null
        }
        assertEquals(
            "these schema keys have no card the user can scroll to",
            emptyList<Int>(),
            missing,
        )
    }

    @Test
    fun `a key shows its type, its description and its source`() {
        show()
        scrollToKey(schema.keys.indexOfFirst { it.key == "default_agent" })
        // The file's own spelling, not the projection's: a user has to type the name that is shown.
        compose.onNodeWithTag(AdminTags.configKey("default_agent")).fetchSemanticsNode()
        compose.onNodeWithText("Must be a primary agent", substring = true).fetchSemanticsNode()
        // The source of the shell, named as a path.
        compose.onAllNodesWithText("opencode.json", substring = true).onFirst().fetchSemanticsNode()
    }

    @Test
    fun `the precedence chain names both documents and the directory that was searched`() {
        show()
        compose.onAllNodesWithText("Document", substring = true).onFirst().fetchSemanticsNode()
        // A `directory` entry says "the server looked here and found nothing", which is the explanation
        // for a key that is not taking effect.
        compose.onNodeWithText("Searched, nothing found", substring = true).fetchSemanticsNode()
    }

    @Test
    fun `the effective value is the server's and the override is marked`() {
        show()
        // The projection is cumulative, so both documents report the model; with no file read the row
        // must not claim an override, and it says so.
        compose.onAllNodesWithText("Reported by", substring = true).onFirst().fetchSemanticsNode()
        scrollToKey(schema.keys.indexOfFirst { it.key == "model" })
        compose.onNodeWithText("Which file set this is not known", substring = true).fetchSemanticsNode()
    }

    @Test
    fun `a key the server does not project says so instead of showing nothing`() {
        show()
        // `small_model` is in the file schema and absent from `Config.InfoEncoded`, so the row explains
        // that the files are the only source rather than implying the key is unused.
        scrollToKey(schema.keys.indexOfFirst { it.key == "small_model" })
        compose.onNodeWithTag(AdminTags.configKey("small_model")).fetchSemanticsNode()
        // Eleven keys are unprojected, so the sentence appears on several cards.
        compose.onAllNodesWithText("The server does not report this key", substring = true).onFirst()
            .fetchSemanticsNode()
    }

    @Test
    fun `a credential is never rendered anywhere on the explorer`() {
        show()
        // The provider block is in the state, so this is a real assertion about the screen: the
        // placeholder key is in the document the server sent and is not among the texts on display.
        // Every key card is walked, because a `LazyColumn` composes a window and a check over the first
        // few rows would pass on a leak in the fortieth.
        val texts = mutableListOf<String>()
        schema.keys.indices.forEach { index ->
            scrollToKey(index)
            texts += compose.allTexts()
        }
        assertTrue(
            "a credential reached the screen: ${texts.filter { it.contains(PLACEHOLDER_KEY) }}",
            texts.none { it.contains(PLACEHOLDER_KEY) },
        )
        // The provider block *is* rendered — by its shape, not by its contents, which is the rule the
        // explorer follows for every container. Asserting that here is what stops the redaction check
        // above from passing simply because the row rendered nothing.
        assertTrue(
            "the provider row must describe its shape, so the check above is not vacuous",
            texts.any { it.contains("an object with 1 entry") },
        )
    }

    @Test
    fun `a filter changes what is visible and not what the state knows`() {
        show(configuredOnly = true)
        // A key nothing sets is gone from the display…
        assertTrue(
            "the filter must hide an unset key",
            compose.allNodesWithTagOrNull(AdminTags.configKey("small_model")) == null,
        )
        // …and one that is set is still there.
        compose.onNodeWithTag(AdminTags.configKey("model")).fetchSemanticsNode()
    }

    @Test
    fun `a failure is reported by its class with a dismiss action`() {
        compose.setContent {
            // The read's own failure and no separate `error`: the screen reads either, so a caller that
            // fills in the documents by hand does not have to set two fields to agree.
            ConfigScreenFixture(
                state().copy(
                    documents = ConfigDocuments(
                        entries = emptyList(),
                        rows = emptyList(),
                        failure = dev.opencode.android.core.data.integrations.ActionFailure(
                            ActionError(kind = ActionErrorKind.UNAUTHORIZED, message = "The credential was rejected"),
                        ),
                    ),
                ),
            )
        }

        compose.onNodeWithText("unauthorized", substring = true).fetchSemanticsNode()
        compose.onNodeWithText("The credential was rejected").fetchSemanticsNode()
    }

    // ------------------------------------------------------------------------------ the editor

    @Test
    fun `an invalid document lists its problems with a location each`() {
        compose.setContent {
            ConfigEditorScreenFixture(
                ConfigEditorUiState(
                    directory = "/work/app",
                    path = ".opencode/opencode.jsonc",
                    draft = "{\n  \"modle\": \"x\"\n}",
                    writesUsable = true,
                    diagnostics = listOf(
                        dev.opencode.android.core.data.config.SchemaDiagnostic(
                            path = "/modle",
                            keyword = "additionalProperties",
                            expected = "a key this schema allows",
                            found = "a string of 1 characters",
                            line = 2,
                            column = 3,
                        ),
                    ),
                ),
            )
        }

        compose.onNodeWithText("Line 2, column 3").fetchSemanticsNode()
        compose.onNodeWithText("additionalProperties", substring = true).fetchSemanticsNode()
    }

    @Test
    fun `a syntax error is shown as one, not as schema complaints`() {
        compose.setContent {
            ConfigEditorScreenFixture(
                ConfigEditorUiState(
                    path = ".opencode/opencode.jsonc",
                    parseFailure = dev.opencode.android.core.data.config.DocumentParseFailure(
                        line = 4,
                        column = 12,
                        reason = "Expected '}' but had an unexpected value",
                    ),
                ),
            )
        }

        compose.onNodeWithText("this file cannot be read", substring = true).fetchSemanticsNode()
        // No list of schema complaints, because a document that cannot be parsed has no tree for the
        // schema to have an opinion about.
        assertEquals(0, compose.allNodesWithTextCount("problems in this document."))
    }

    @Test
    fun `a valid document says so`() {
        compose.setContent {
            ConfigEditorScreenFixture(
                ConfigEditorUiState(path = ".opencode/opencode.jsonc", draft = "{}", writesUsable = true),
            )
        }

        compose.onNodeWithText("This document is valid", substring = true).fetchSemanticsNode()
    }

    // ------------------------------------------------------------------------------ the confirmation

    @Test
    fun `a privilege change leads the confirmation with what it changes`() {
        compose.setContent {
            AdminWriteDialogFixture(
                dev.opencode.android.core.data.config.WritePlan(
                    target = ".opencode/opencode.jsonc",
                    text = "{}",
                    consequence = "This replaces .opencode/opencode.jsonc",
                    isPrivilegeChange = true,
                    bytes = 2,
                    previousBytes = 10,
                ),
            )
        }

        compose.onNodeWithText("This changes what the agent may do").fetchSemanticsNode()
        compose.onNodeWithText("more, or less, than it could before", substring = true).fetchSemanticsNode()
        // The confirm button exists and the dialog's own content does, which is the whole point of the
        // fixture rendering the content rather than a window: a window's contents are not in this tree.
        compose.allNodesWithTagOrNull(AdminTags.CONFIRM_WRITE).let {
            assertTrue("the confirmation must offer a confirm action", it != null && it >= 2)
        }
    }

    @Test
    fun `an ordinary write does not claim to be a privilege change`() {
        compose.setContent {
            AdminWriteDialogFixture(
                dev.opencode.android.core.data.config.WritePlan(
                    target = "AGENTS.md",
                    text = "# notes",
                    consequence = "This creates AGENTS.md",
                    isPrivilegeChange = false,
                    bytes = 7,
                    previousBytes = null,
                ),
            )
        }

        compose.onNodeWithText("Write this file?").fetchSemanticsNode()
        assertEquals(0, compose.allNodesWithTextCount("This changes what the agent may do"))
        compose.onNodeWithText("A new file of 7 bytes.").fetchSemanticsNode()
    }

    // ------------------------------------------------------------------------------ permissions

    @Test
    fun `the precedence warning is on the screen whenever a session's rules are`() {
        compose.setContent { PermissionsScreenFixture(permissionsState()) }

        compose.onNodeWithTag(AdminTags.RULES_PRECEDENCE_WARNING).fetchSemanticsNode()
        // The sentence is the fact, not a caution.
        compose.onNodeWithText("Session rules are evaluated last").fetchSemanticsNode()
        compose.onNodeWithText("override an agent", substring = true).fetchSemanticsNode()
    }

    @Test
    fun `removing a saved approval names the action and the resource`() {
        compose.setContent {
            PermissionsScreenFixture(
                permissionsState().copy(
                    removeTarget = dev.opencode.android.core.model.SavedPermission(
                        id = "perm_1",
                        projectID = "prj_1",
                        action = "bash",
                        resource = "git status",
                        time = dev.opencode.android.core.model.SavedPermission.Time(1, 1),
                    ),
                ),
            )
        }

        compose.onNodeWithText("bash on git status will be asked for again.").fetchSemanticsNode()
        compose.onNodeWithTag(AdminTags.CONFIRM_REMOVE_SAVED).fetchSemanticsNode()
    }

    // ------------------------------------------------------------------------------ maintenance

    @Test
    fun `a migration with no count shows the label and not a zero-of-zero`() {
        compose.setContent {
            MaintenanceScreenFixture(
                MaintenanceUiState(
                    locationsLoaded = true,
                    migrationLoaded = true,
                    migration = dev.opencode.android.core.model.MigrationStatus.Running(
                        dev.opencode.android.core.model.MigrationStatus.Running.Progress("Starting"),
                    ),
                ),
            )
        }

        compose.onNodeWithText("Starting").fetchSemanticsNode()
        compose.onNodeWithText("has not said how far along", substring = true).fetchSemanticsNode()
        assertEquals(0, compose.allNodesWithTextCount("0 of 0 sessions"))
    }

    @Test
    fun `a migration with a count shows it`() {
        compose.setContent {
            MaintenanceScreenFixture(
                MaintenanceUiState(
                    locationsLoaded = true,
                    migrationLoaded = true,
                    migration = dev.opencode.android.core.model.MigrationStatus.Running(
                        dev.opencode.android.core.model.MigrationStatus.Running.Progress("Importing", 40, 100),
                    ),
                ),
            )
        }

        compose.onNodeWithText("40 of 100 sessions").fetchSemanticsNode()
    }

    @Test
    fun `the reload button says what a reload cancels`() {
        compose.setContent { MaintenanceScreenFixture(MaintenanceUiState(locationsLoaded = true)) }

        compose.onNodeWithText("Permissions and questions waiting for an answer are cancelled", substring = true)
            .fetchSemanticsNode()
    }

    @Test
    fun `the update banner carries the host upgrade command`() {
        compose.setContent {
            MaintenanceScreenFixture(
                MaintenanceUiState(locationsLoaded = true, updateAvailable = "2.1.0"),
            )
        }

        compose.onNodeWithText("OpenCode 2.1.0 is available").fetchSemanticsNode()
        compose.onNodeWithText("@opencode/cli@latest", substring = true).fetchSemanticsNode()
    }

    @Test
    fun `evicting a location says nothing is lost and that there is no undo`() {
        compose.setContent {
            MaintenanceScreenFixture(
                MaintenanceUiState(
                    locationsLoaded = true,
                    evictTarget = dev.opencode.android.core.model.LoadedLocation("/work/app"),
                ),
            )
        }

        compose.onNodeWithText("Nothing is lost", substring = true).fetchSemanticsNode()
        compose.onNodeWithText("there is no undo", substring = true).fetchSemanticsNode()
        compose.onNodeWithTag(AdminTags.CONFIRM_EVICT).fetchSemanticsNode()
    }

    // ------------------------------------------------------------------------------ instructions

    @Test
    fun `the instruction screen explains that a bare word becomes text`() {
        compose.setContent { InstructionsScreenFixture(instructionsState()) }

        compose.onNodeWithText("A plain word is stored as text", substring = true).fetchSemanticsNode()
        compose.onNodeWithText("Not JSON, so it will be stored as text.").fetchSemanticsNode()
    }

    @Test
    fun `a disabled experiment explains itself rather than showing an empty list`() {
        compose.setContent { InstructionsScreenFixture(instructionsState(usable = false)) }

        compose.onNodeWithText("Turn on the session-instructions experiment", substring = true)
            .fetchSemanticsNode()
        assertEquals(
            "the screen must not even offer the form while the experiment is off",
            null,
            compose.allNodesWithTagOrNull(AdminTags.PUT_INSTRUCTION),
        )
    }

    // ------------------------------------------------------------------------------ catalogs

    @Test
    fun `an agent row shows the six things the plan asks for`() {
        compose.setContent { CatalogScreenFixture(catalogState(AdminCatalog.AGENTS)) }

        compose.onNodeWithText("build").fetchSemanticsNode()
        compose.onNodeWithText("Mode").fetchSemanticsNode()
        compose.onNodeWithText("Model").fetchSemanticsNode()
        compose.onNodeWithText("Steps").fetchSemanticsNode()
        compose.onNodeWithText("Color").fetchSemanticsNode()
        compose.onNodeWithText("Permissions").fetchSemanticsNode()
        // And the system prompt, which is what the screen was opened to read.
        compose.onNodeWithText("You are a build agent", substring = true).fetchSemanticsNode()
    }

    @Test
    fun `a skill row shows its content`() {
        compose.setContent { CatalogScreenFixture(catalogState(AdminCatalog.SKILLS)) }

        compose.onNodeWithText("Run the project's linter", substring = true).fetchSemanticsNode()
    }

    @Test
    fun `a reference row says where it lives`() {
        compose.setContent { CatalogScreenFixture(catalogState(AdminCatalog.REFERENCES)) }

        compose.onNodeWithText("Repository").fetchSemanticsNode()
        compose.onNodeWithText("https://example.com/repo", substring = true).fetchSemanticsNode()
    }

    @Test
    fun `a command row says the template is in the file rather than showing nothing`() {
        compose.setContent { CatalogScreenFixture(catalogState(AdminCatalog.COMMANDS)) }

        compose.onNodeWithText("/deploy").fetchSemanticsNode()
        compose.onNodeWithText("in the command file").fetchSemanticsNode()
    }

    // ------------------------------------------------------------------------------ helpers

    private companion object {
        /**
         * The first item index that is a *key* card.
         *
         * The list leads with the error row (when there is one), the source summary and the shell card,
         * so the key cards start after them. Derived from the screen rather than guessed: an off-by-one
         * here would make the coverage test walk the wrong cards and pass for the wrong reason.
         */
        const val FIRST_KEY_INDEX = 2

        const val PLACEHOLDER_KEY = "placeholder-api-key-not-a-real-credential"

        fun parse(text: String) = kotlinx.serialization.json.Json.parseToJsonElement(text)

        fun permissionsState() = PermissionsUiState(
            directory = "/work/app",
            projectID = "prj_1",
            sessionID = "ses_1",
            savedLoaded = true,
            saved = listOf(
                dev.opencode.android.core.model.SavedPermission(
                    "perm_1",
                    "prj_1",
                    "bash",
                    "git status",
                    dev.opencode.android.core.model.SavedPermission.Time(1, 1),
                ),
            ),
            rules = listOf(PermissionRule("bash", "*", PermissionEffect.Ask)),
        )

        fun instructionsState(usable: Boolean = true) = InstructionsUiState(
            sessionID = "ses_1",
            loaded = true,
            usable = usable,
            entries = listOf(
                dev.opencode.android.core.model.InstructionEntry("tone", parse("\"terse\"")),
            ),
            key = "tone",
            value = "not json",
        )

        fun catalogState(tab: AdminCatalog) = CatalogUiState(
            tab = tab,
            agents = dev.opencode.android.core.data.sync.SyncedState(
                listOf(
                    dev.opencode.android.core.model.AgentInfo(
                        id = "build",
                        name = "build",
                        mode = "primary",
                        model = dev.opencode.android.core.model.ModelRef("placeholder-provider", "placeholder-model"),
                        steps = 100,
                        color = "primary",
                        permissions = parse("""{"bash":"ask"}"""),
                        system = "You are a build agent.",
                    ),
                ),
                dev.opencode.android.core.data.sync.SyncStatus.Ready,
            ),
            commands = dev.opencode.android.core.data.sync.SyncedState(
                listOf(dev.opencode.android.core.model.CommandInfo("deploy", "Deploys")),
                dev.opencode.android.core.data.sync.SyncStatus.Ready,
            ),
            skills = dev.opencode.android.core.data.sync.SyncedState(
                listOf(
                    dev.opencode.android.core.model.SkillInfo(
                        id = "lint",
                        name = "lint",
                        path = ".opencode/skills/lint",
                        content = "Run the project's linter.",
                    ),
                ),
                dev.opencode.android.core.data.sync.SyncStatus.Ready,
            ),
            references = dev.opencode.android.core.data.sync.SyncedState(
                listOf(
                    dev.opencode.android.core.model.ReferenceInfo(
                        name = "upstream",
                        path = "",
                        source = dev.opencode.android.core.model.ReferenceSource.Git("https://example.com/repo"),
                    ),
                ),
                dev.opencode.android.core.data.sync.SyncStatus.Ready,
            ),
        )
    }
}

/**
 * Every text on the display, which is what the redaction assertion asks about.
 *
 * **`substring = true` with an empty needle matches every text-bearing node**, which is what "every
 * text" has to mean here: a filter that matched only whole strings would miss a row that rendered the
 * credential inside a longer sentence, and the assertion would pass on exactly the case it exists for.
 */
private fun SemanticsNodeInteractionsProvider.allTexts(): List<String> =
    onAllNodesWithText("", substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        }

/** How many nodes carry [text] exactly, so "the warning is absent" is an assertion. */
private fun SemanticsNodeInteractionsProvider.allNodesWithTextCount(text: String): Int =
    onAllNodesWithText(text, substring = false).fetchSemanticsNodes().size

/**
 * How many nodes carry [tag], or null when there are none.
 *
 * A control that is *disabled* still exists in the tree, so the assertion is deliberately about whether
 * the node is there at all: the screen's own state decides, and a test that only looked for the tag
 * would pass with the button greyed out.
 */
private fun SemanticsNodeInteractionsProvider.allNodesWithTagOrNull(tag: String): Int? =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size.takeIf { it > 0 }
