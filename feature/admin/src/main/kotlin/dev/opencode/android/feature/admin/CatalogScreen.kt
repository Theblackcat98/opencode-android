package dev.opencode.android.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.sync.SyncedState
import dev.opencode.android.core.data.sync.SyncStatus
import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.ReferenceInfo
import dev.opencode.android.core.model.ReferenceSource
import dev.opencode.android.core.model.SkillInfo

/** Which catalog the browser is showing. */
enum class AdminCatalog(val id: String) {
    AGENTS("agents"),
    COMMANDS("commands"),
    SKILLS("skills"),
    REFERENCES("references"),
}

/**
 * One row of a catalog, already in the screen's own shape.
 *
 * **A flat list of `CatalogRow` rather than a `when` over four model types at every call site.** The
 * four catalogs have nothing in common structurally, so the alternative is four lists and four `when`s
 * in the screen; a row type makes "the agents tab shows these fields" one line each and keeps the
 * `LazyColumn` single, which is what lets all four share stable keys and one scroll position.
 */
data class CatalogRow(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val facts: List<Pair<String, String>> = emptyList(),
    /** The body the row expands to show, for a skill or an agent's prompt. */
    val body: String? = null,
    val tag: String,
)

/** The browser's state: which tab, and the four catalogs. */
data class CatalogUiState(
    val tab: AdminCatalog = AdminCatalog.AGENTS,
    val search: String = "",
    val agents: SyncedState<List<AgentInfo>> = SyncedState(),
    val commands: SyncedState<List<CommandInfo>> = SyncedState(),
    val skills: SyncedState<List<SkillInfo>> = SyncedState(),
    val references: SyncedState<List<ReferenceInfo>> = SyncedState(),
) {
    /** The rows of the current tab, filtered by the search field. */
    /** The load state of the tab being shown, so the screen has one branch rather than four. */
    val currentStatus: SyncStatus
        get() = when (tab) {
            AdminCatalog.AGENTS -> agents.status
            AdminCatalog.COMMANDS -> commands.status
            AdminCatalog.SKILLS -> skills.status
            AdminCatalog.REFERENCES -> references.status
        }

    val rows: List<CatalogRow> by lazy {
        val needle = search.trim().lowercase()
        fun keep(key: String) = needle.isEmpty() || key.lowercase().contains(needle)
        when (tab) {
            AdminCatalog.AGENTS -> agents.value.orEmpty()
                .filter { keep(it.name) }
                .map(::agentRow)

            AdminCatalog.COMMANDS -> commands.value.orEmpty()
                .filter { keep(it.name) }
                .map(::commandRow)

            AdminCatalog.SKILLS -> skills.value.orEmpty()
                .filter { keep(it.name) }
                .map(::skillRow)

            AdminCatalog.REFERENCES -> references.value.orEmpty()
                .filter { keep(it.name) }
                .map(::referenceRow)
        }
    }
}

/**
 * An agent, with the six things the plan's browser asks for (features doc §7; plan §6).
 *
 * **The system prompt is in the row rather than fetched on demand.** `agent.list` carries it in
 * `system`, and a row that shows it collapsed is one tap from the thing the user opened this screen to
 * read. It is bounded in height and scrollable so a long prompt does not push the other fields out of
 * reach — which is the failure a "just dump the object" row has.
 *
 * **The permission block is summarised, not printed.** An agent's `permissions` is the same
 * `PermissionConfig` map the top-level key uses and it is what decides what the agent may do, so it is
 * shown — as its shape, through the same [showValue] the explorer uses, so a value that is a secret is
 * redacted by the same rule rather than by a second one that could be forgotten.
 */
private fun agentRow(agent: AgentInfo) = CatalogRow(
    key = agent.id,
    title = agent.name,
    subtitle = agent.description,
    facts = buildList {
        add("Mode" to agent.mode)
        agent.model?.let { add("Model" to it.id) }
        agent.steps?.let { add("Steps" to it.toString()) }
        agent.color?.let { add("Color" to it) }
        if (agent.hidden) add("Hidden" to "yes")
        agent.permissions?.let { add("Permissions" to showValue("permission", it)) }
    },
    body = agent.system,
    tag = AdminTags.agent(agent.name),
)

/**
 * A command, with its template.
 *
 * **`command.list` carries only a name and a description** (`CommandInfo` has two fields), so the
 * template is not here to show — it is in the file, and that is what the definition editor opens. The row
 * says so rather than showing an empty body, because an empty body next to a command that obviously has
 * a template looks like a bug in the app.
 */
private fun commandRow(command: CommandInfo) = CatalogRow(
    key = command.name,
    title = "/${command.name}",
    subtitle = command.description,
    facts = listOf("Template" to "in the command file"),
    tag = AdminTags.command(command.name),
)

private fun skillRow(skill: SkillInfo) = CatalogRow(
    key = skill.id,
    title = skill.name,
    subtitle = skill.description,
    facts = buildList {
        add("Path" to skill.path)
        if (skill.autoinvoke == true) add("Autoinvoke" to "yes")
    },
    body = skill.content,
    tag = AdminTags.skill(skill.name),
)

private fun referenceRow(reference: ReferenceInfo) = CatalogRow(
    key = reference.name,
    title = reference.name,
    subtitle = reference.description,
    facts = buildList {
        when (val source = reference.source) {
            is ReferenceSource.Local -> add("Path" to source.path)
            is ReferenceSource.Git -> {
                add("Repository" to source.repository)
                source.branch?.let { add("Branch" to it) }
            }
            is ReferenceSource.Unknown -> add("Source" to "unknown")
        }
        if (reference.hidden == true) add("Hidden" to "yes")
    },
    tag = AdminTags.reference(reference.name),
)

/**
 * The catalog browsers (plan §6, "Catalog browsers").
 *
 * **One screen, four tabs, one list.** The four catalogs are read-only projections the server computes
 * (`agent.list`, `command.list`, `skill.list`, `reference.list`) and none of them has a per-item detail
 * route, so a tab per catalog with a shared row is the whole design — and it is why the composed
 * `CatalogScreen` takes the four `SyncedResource`s as parameters rather than reaching for the registry:
 * `feature/admin` may not import the feature that owns the composer's catalogs.
 */
@Composable
fun CatalogScreen(
    state: CatalogUiState,
    onTabChange: (AdminCatalog) -> Unit,
    onSearchChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdminCatalog.entries.forEach { tab ->
                FilterChip(
                    selected = state.tab == tab,
                    onClick = { onTabChange(tab) },
                    label = { Text(stringResource(tabLabel(tab))) },
                    modifier = Modifier.testTag(AdminTags.tab(tab.id)),
                )
            }
        }
        LabelledField(
            label = stringResource(R.string.admin_catalog_search),
            value = state.search,
            onValueChange = onSearchChange,
            tag = "catalog:search",
        )
        HorizontalDivider()
        when (val status = state.currentStatus) {
            is SyncStatus.Failed -> {
                // A catalog read fails as a raw `Throwable`; the screen reports the class of it rather
                // than the request, which for these routes would name a directory the user chose.
                ErrorLine(error = status.error.toActionError(), onDismiss = { })
            }

            SyncStatus.Loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.admin_catalog_loading))
            }

            SyncStatus.Ready, SyncStatus.Idle -> {
                if (state.rows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.admin_catalog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        // Keyed on the row's own identity, never on its position: a catalog is re-read on
                        // `agent.updated`, `command.updated`, `skill.updated` and `reference.updated`.
                        items(items = state.rows, key = { it.key }) { row -> CatalogRowCard(row) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRowCard(row: CatalogRow) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(row.tag),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            row.subtitle?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            row.facts.forEach { (label, value) -> Fact(label = label, value = value, monospace = true) }
            row.body?.let { body ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    // Bounded, so a long system prompt or skill body does not push the rest of the
                    // list off the screen and leave the user with one enormous card.
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private fun tabLabel(tab: AdminCatalog): Int = when (tab) {
    AdminCatalog.AGENTS -> R.string.admin_catalog_agents
    AdminCatalog.COMMANDS -> R.string.admin_catalog_commands
    AdminCatalog.SKILLS -> R.string.admin_catalog_skills
    AdminCatalog.REFERENCES -> R.string.admin_catalog_references
}
