package dev.opencode.android.core.data.config

import dev.opencode.android.core.model.ConfigEntry
import dev.opencode.android.core.model.ConfigInfo
import kotlinx.serialization.json.JsonElement

/**
 * One configuration document, in the order the server returned it.
 *
 * **The position *is* the precedence.** `GET /api/config` answers "from lowest to highest priority",
 * so a later entry overrides an earlier one for a scalar and the arrays that combine (`plugins`,
 * `skills`, `permission`) combine in the same order (features doc §33.1). [index] is therefore not
 * decoration: it is what lets a row say which document a value came from without re-sorting, and
 * sorting by path would put a project file ahead of the global one.
 */
data class ConfigSource(val index: Int, val entry: ConfigEntry) {
    val path: String? get() = entry.path
    val isDocument: Boolean get() = entry is ConfigEntry.Document
    val info: ConfigInfo? get() = (entry as? ConfigEntry.Document)?.info
}

/**
 * What a document's **own text** sets, as opposed to what its merged projection reports.
 *
 * **This is the type that makes "with their source paths" answerable, and it is a separate type
 * because the API cannot answer it.** `config.get` gives every document a *cumulative* projection:
 * the second document's `info` already contains whatever the first one set. So a projection can say
 * "by the time document 2 was read, `model` was `x`" and never "document 2 is what set it" — with a
 * projection alone, a document that inherited a value is indistinguishable from one that wrote the
 * same value.
 *
 * Reading the file with `fs.read` and listing the top-level keys of its own text is what separates
 * them, and it is cheap: the editor reads that file anyway, and only the *nearest* document per
 * directory is ever read. Everything below the nearest one is reported by projection alone, and a row
 * says so.
 */
data class ConfigFileFacts(
    /** Which entry of `config.get` this is, by position. */
    val index: Int,
    /** The top-level keys the file's own text contains, whether or not the value is null. */
    val keys: Set<String>,
)

/** What one key's value ended up being, and which documents report it. */
data class ConfigValue(
    /** The value, or `null` when no document reports the key. */
    val element: JsonElement?,
    /** The highest-precedence document that reports the key, or `null`. */
    val source: ConfigSource?,
    /** Every document that reports the key, lowest precedence first. */
    val reports: List<ConfigSource>,
    /** The documents whose **own text** sets the key, when the client has read them. */
    val setters: List<ConfigSource>,
) {
    /** Whether any document reports the key. */
    val isSet: Boolean get() = element != null

    /** Whether any document's own text sets the key, which is the precise answer when it is known. */
    val isSetKnown: Boolean get() = setters.isNotEmpty() || reports.isEmpty()

    /**
     * Whether a document's own text set the key, and a different document is what the explorer will
     * blame for the value.
     *
     * Only meaningful when [setters] is non-empty, because a projection alone cannot tell an override
     * from an inheritance. [isSetKnown] is what a screen checks before showing this.
     */
    val isOverridden: Boolean get() = setters.size > 1

    /** The document whose own text set the key, when that is known. */
    val defining: ConfigSource? get() = setters.lastOrNull()
}

/**
 * One top-level key of the configuration, with everything the explorer shows about it.
 *
 * **[effective] is the runtime's own answer**, not a value this app merged. The server computes the
 * merge — "the last one wins for scalar values; arrays such as `plugins`, `skills` and `permissions`
 * combine" (features doc §33.1) — so reading it is the only way to be right about a rule this client
 * does not implement and would otherwise have to re-derive.
 *
 * **Where the projection reports nothing, the row says so rather than showing an empty value.**
 * Eleven of the schema's thirty-six keys have no projection at all, and "the server does not report
 * this key" leads a user to a different place than "this key is not set".
 */
data class ConfigRow(
    val key: ConfigKey,
    val value: ConfigValue,
    val effective: JsonElement?,
    /** Whether the runtime projection reports this key at all. */
    val isReported: Boolean,
) {
    /** Whether a lower-precedence document sets the key and this one wins. */
    val isOverridden: Boolean get() = value.isOverridden

    /** Whether the row has anything to show beyond "not set". */
    val hasContent: Boolean get() = value.isSet || isReported

    /** Whether the guided templates can edit this key. */
    val isTemplated: Boolean get() = key.hasTemplate

    /** Whether changing this key changes what the agent may do, which the confirmation must say. */
    val isPrivilegeChange: Boolean get() = key.isPrivilegeChange

    /**
     * Whether the app will offer an editor for this key.
     *
     * **`experimental` is the one, and the reason is what is under it.** Its `policies` array is the
     * server's resource grants — `provider.use` and its `allow`/`deny` effects (features doc §15) —
     * and the app shows those read-only rather than offering to write them, because there is no API
     * for it and a hand-written policy grant is a privilege change made by text entry with no
     * server-side validation. Its other members are a deprecated paste flag, a tool-batch flag and a
     * telemetry flag, none of which the guided templates cover. The row links to the file, which is
     * where they are edited, and says why.
     */
    val isReadOnly: Boolean get() = key.key == "experimental"
}

/**
 * Turns `config.get`'s answer into one row per top-level key of the vendored schema.
 *
 * **The schema decides which keys exist, and the documents decide what they hold.** That ordering is
 * what makes the plan's exit criterion checkable: the row list is produced by walking
 * [ConfigSchema.keys], so it has exactly one entry per key the file format defines, in the schema's
 * own order, whether or not a document sets it and whether or not the projection reports it. There is
 * no filter, no "hide empty" switch and no query string — a key nothing sets is a row that says so.
 *
 * **[facts] upgrades a row from "reported by" to "set by".** Without it a row says a key is reported
 * by every document at or after the one that introduced it, which is true and useless for the
 * override column. With the facts for the documents the client has read, [ConfigValue.setters] is the
 * precise answer and the override column becomes exact. Rows for unread documents keep the
 * projection's answer and [ConfigValue.isSetKnown] says so, which is the honest thing to render.
 */
object ConfigExplorer {

    /**
     * The rows for [entries], one per top-level key of [schema].
     *
     * @param entries `config.get`'s answer, in the server's own order: lowest precedence first.
     * @param facts what each *read* document's own text sets, keyed by its position in [entries].
     */
    fun rows(
        schema: ConfigSchema,
        entries: List<ConfigEntry>,
        facts: Map<Int, ConfigFileFacts> = emptyMap(),
    ): List<ConfigRow> {
        val sources = entries.mapIndexed { index, entry -> ConfigSource(index, entry) }
        val documents = sources.filter { it.isDocument }
        // `config.get` gives every document a cumulative projection, so the last document's `info` is
        // the server's merge of all of them. That is the effective answer for every key it reports.
        val effectiveInfo = documents.lastOrNull()?.info

        return schema.keys.map { key ->
            val name = key.key
            val reporters = documents.filter { it.reports(name) }
            val setters = reporters.filter { facts[it.index]?.keys?.contains(name) == true }
            val winner = reporters.lastOrNull()
            val effective = effectiveInfo?.projected(name)
            ConfigRow(
                key = key,
                value = ConfigValue(
                    element = winner?.info?.projected(name),
                    source = winner,
                    reports = reporters,
                    setters = setters,
                ),
                effective = effective,
                isReported = effectiveInfo?.projects(name) ?: false,
            )
        }
    }

    /** The rows that set anything or that the server reports, for a "configured only" filter. */
    fun configured(rows: List<ConfigRow>): List<ConfigRow> = rows.filter { it.hasContent }

    /**
     * Whether a document's cumulative projection mentions [name].
     *
     * **Presence, not "differs from the previous document".** A lower document's value survives into
     * every later projection by design, and that survival *is* the information: the row's
     * "reported by" column is how the user sees which files participate in the merge at all. Turning
     * it into a diff would hide the global file whenever a project file overrides it, which is the
     * case a user most needs explained.
     */
    private fun ConfigSource.reports(name: String): Boolean {
        val info = this.info ?: return false
        val projected = ConfigSchema.PROJECTION_NAMES[name] ?: return false
        return info.reportsProjection(projected)
    }
}

/**
 * Whether the projection carries [name].
 *
 * **The typed field, and the limits that come with it.** `ConfigInfo` is a typed class over a
 * projection the server sends with `additionalProperties: false`, so "carries the key" and "the
 * field is non-null" are the same question — except for an explicit `null`, which a configuration
 * file can use to clear a key and which the typed class cannot tell from an absent field. A key whose
 * file value is an explicit `null` therefore reads as unreported, and the editor's own file read is
 * what shows it. That is stated here rather than papered over, because the alternative is a row that
 * claims to know more than it does.
 */
internal fun ConfigInfo.reportsProjection(name: String): Boolean = when (name) {
    "\$schema" -> schema != null
    "shell" -> shell != null
    "model" -> model != null
    "default_agent" -> default_agent != null
    "update" -> update != null
    "share" -> share != null
    "enterprise" -> enterprise != null
    "username" -> username != null
    "permissions" -> permissions != null
    "agents" -> agents != null
    "snapshots" -> snapshots != null
    "watcher" -> watcher != null
    "formatter" -> formatter != null
    "lsp" -> lsp != null
    "media" -> media != null
    "tool_output" -> tool_output != null
    "mcp" -> mcp != null
    "compaction" -> compaction != null
    "skills" -> skills != null
    "commands" -> commands != null
    "instructions" -> instructions != null
    "references" -> references != null
    "websearch" -> websearch != null
    "plugins" -> plugins != null
    "worktree" -> worktree != null
    "warming" -> warming != null
    "providers" -> providers != null
    "experimental" -> experimental != null
    else -> false
}
