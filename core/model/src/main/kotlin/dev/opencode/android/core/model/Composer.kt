package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.variant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /api/command` (schema `Command.Info`).
 *
 * A command is a named prompt template the server owns, defined in `.opencode/commands/`. The name
 * may be nested (`team/review`) and may come from an MCP server as `<server>:<prompt>`
 * (features doc §11), so a command name is not a single identifier and nothing may assume it is.
 */
@Serializable
data class CommandInfo(
    val name: String,
    val description: String? = null,
)

/**
 * `GET /api/skill` (schema `Skill.Info`).
 *
 * [content] is required by the spec and is the whole skill body, so a list of skills can be large.
 * That is the server's answer, not a choice: `skill.list` is what the composer reads to offer the
 * selector, and a client that trimmed the body would be guessing at what the skill says.
 */
@Serializable
data class SkillInfo(
    val id: String,
    val name: String,
    val description: String? = null,
    /** The skill may be invoked without being asked for; the TUI's skill selector shows these apart. */
    val autoinvoke: Boolean? = null,
    val path: String,
    val content: String,
)

/**
 * `GET /api/reference` (schema `Reference.Info`).
 *
 * A reference is a directory the server can attach to a prompt, either a local path or a cloned git
 * repository (features doc §19). It is attached by passing its path as a directory `file:`
 * attachment, which gives the model a non-recursive listing.
 */
@Serializable
data class ReferenceInfo(
    val name: String,
    val path: String,
    val description: String? = null,
    /** Hidden references still work when named, they are just not offered in completion. */
    val hidden: Boolean? = null,
    val source: ReferenceSource,
)

/** Where a reference lives (schema `Reference.Source`). */
@Serializable(with = ReferenceSourceSerializer::class)
sealed interface ReferenceSource {
    @Serializable
    data class Local(val path: String) : ReferenceSource

    @Serializable
    data class Git(val repository: String, val branch: String? = null) : ReferenceSource

    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        ReferenceSource,
        UnknownVariant
}

internal object ReferenceSourceSerializer : DiscriminatedUnionSerializer<ReferenceSource>(
    serialName = "dev.opencode.android.ReferenceSource",
    unknown = ReferenceSource::Unknown,
    variants = listOf(
        variant("local", ReferenceSource.Local.serializer()),
        variant("git", ReferenceSource.Git.serializer()),
    ),
)
