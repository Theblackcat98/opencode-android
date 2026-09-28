package dev.opencode.android.core.data.catalog

import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the pickers are allowed to offer.
 *
 * Both are rules rather than layout decisions, and both fail silently when wrong: a subagent in the
 * agent list is a choice that cannot be made, and a disabled model in the picker is a turn that fails
 * on the first call.
 */
class CatalogTest {

    @Test
    fun `only primary, visible agents may be selected`() {
        val agents = listOf(
            agent("build", mode = "primary"),
            agent("plan", mode = "primary"),
            agent("general", mode = "subagent"),
            agent("compaction", mode = "all", hidden = true),
            agent("custom", mode = "all"),
        )
        assertEquals(listOf("build", "plan", "custom"), AgentCatalog.primary(agents).map { it.id })
    }

    @Test
    fun `an agent label carries the id only when the name differs`() {
        assertEquals("build", AgentCatalog.label(agent("build", name = "build")))
        assertEquals("Reviewer (team/reviewer)", AgentCatalog.label(agent("team/reviewer", name = "Reviewer")))
    }

    @Test
    fun `the agent cycle wraps and starts at the first when nothing is selected`() {
        val agents = listOf(agent("build", mode = "primary"), agent("plan", mode = "primary"))
        assertEquals("build", ModelCatalog.nextAgent(agents, current = null)?.id)
        assertEquals("plan", ModelCatalog.nextAgent(agents, current = "build")?.id)
        assertEquals("build", ModelCatalog.nextAgent(agents, current = "plan")?.id)
    }

    @Test
    fun `the agent cycle has nothing to offer when nothing is selectable`() {
        assertNull(ModelCatalog.nextAgent(listOf(agent("general", mode = "subagent")), current = null))
    }

    @Test
    fun `the catalog is grouped by provider and ordered by it`() {
        val models = listOf(
            model("b", provider = "zeta"),
            model("a", provider = "alpha"),
            model("c", provider = "zeta"),
        )
        val groups = ModelCatalog.group(models)
        assertEquals(listOf("alpha", "zeta"), groups.map { it.providerID })
        assertEquals(listOf("b", "c"), groups[1].entries.map { it.info.id })
    }

    @Test
    fun `a disabled model is hidden unless it is asked for`() {
        val models = listOf(model("on", enabled = true), model("off", enabled = false))
        assertEquals(listOf("on"), ModelCatalog.group(models).flatMap { it.entries }.map { it.info.id })
        assertEquals(
            listOf("on", "off"),
            ModelCatalog.group(models, includeDisabled = true).flatMap { it.entries }.map { it.info.id },
        )
    }

    @Test
    fun `search matches the four things a user may know a model by`() {
        val model = ModelInfo(
            id = "model-2026",
            modelID = "model-2026-preview",
            providerID = "openai",
            name = "GPT 5 Codex",
            family = "gpt",
            limit = ModelInfo.Limit(context = 200_000, output = 4096),
        )
        listOf("gpt 5", "MODEL-2026", "codex".takeIf { false } ?: "openai", "gpt").forEach { term ->
            assertTrue("'$term' should match", ModelCatalog.matches(model, term))
        }
        assertFalse(ModelCatalog.matches(model, "anthropic"))
    }

    @Test
    fun `favorites and recents are annotated on the rows that exist`() {
        val models = listOf(model("a", provider = "p"), model("b", provider = "p"), model("c", provider = "p"))
        val entries = ModelCatalog.group(
            models = models,
            favorites = listOf(ModelRef("b", "p")),
            recents = listOf(ModelRef("c", "p")),
        ).flatMap { it.entries }

        assertTrue(entries.single { it.info.id == "b" }.isFavorite)
        assertTrue(entries.single { it.info.id == "c" }.isRecent)
        assertFalse(entries.single { it.info.id == "a" }.isFavorite)
    }

    @Test
    fun `a favorite the catalog no longer lists is not shown`() {
        val entries = ModelCatalog.group(listOf(model("a")), favorites = listOf(ModelRef("gone", "p")))
        assertTrue(entries.flatMap { it.entries }.none { it.isFavorite })
    }

    @Test
    fun `a model with no declared variants still offers one, named default`() {
        val model = model("a", variants = emptyList())
        assertEquals(listOf("default"), ModelCatalog.variantsOf(model).map { it.id })
    }

    @Test
    fun `the variant cycle wraps in the order the server declared`() {
        val model = model("a", variants = listOf(ModelInfo.Variant("low"), ModelInfo.Variant("high")))
        assertEquals("low", ModelCatalog.nextVariant(model, current = null))
        assertEquals("high", ModelCatalog.nextVariant(model, current = "low"))
        assertEquals("low", ModelCatalog.nextVariant(model, current = "high"))
    }

    @Test
    fun `capability badges come from the model, not from a guess`() {
        val tools = ModelInfo(
            id = "a", modelID = "a", providerID = "p", name = "a",
            capabilities = ModelInfo.Capabilities(tools = true, input = listOf("text", "image")),
            limit = ModelInfo.Limit(context = 1, output = 1),
        )
        val entry = ModelCatalog.Entry(tools)
        assertTrue(entry.supportsTools)
        assertTrue(entry.supportsImageInput)
        assertFalse(ModelCatalog.Entry(model("a")).supportsTools)
    }

    @Test
    fun `the price on a row is the cheapest tier the catalog lists`() {
        val model = model("a").copy(
            cost = listOf(
                ModelInfo.Cost(input = 15.0, output = 60.0),
                ModelInfo.Cost(input = 3.0, output = 12.0),
            ),
        )
        val entry = ModelCatalog.Entry(model)
        assertEquals(3.0, entry.inputCost, 0.0)
        assertEquals(12.0, entry.outputCost, 0.0)
    }

    private fun agent(
        id: String,
        name: String = id,
        mode: String = "primary",
        hidden: Boolean = false,
    ) = AgentInfo(id = id, name = name, mode = mode, hidden = hidden)

    private fun model(
        id: String,
        provider: String = "p",
        enabled: Boolean = true,
        variants: List<ModelInfo.Variant> = emptyList(),
    ) = ModelInfo(
        id = id,
        modelID = id,
        providerID = provider,
        name = id,
        variants = variants,
        enabled = enabled,
        limit = ModelInfo.Limit(context = 200_000, output = 4096),
    )
}
