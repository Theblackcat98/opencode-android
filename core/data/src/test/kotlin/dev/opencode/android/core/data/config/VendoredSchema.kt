package dev.opencode.android.core.data.config

import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The vendored schema, read from the repository rather than embedded in the test.
 *
 * **Every test in this package validates against the file the app ships.** Reading
 * `api/opencode-2.0.x/config.schema.json` at run time is what makes these tests a statement about the
 * schema rather than about a fixture that happens to agree with it: a schema update that adds a key,
 * an assertion or an annotation the validator does not know fails here instead of quietly changing
 * what the app accepts.
 */
object VendoredSchema {

    /**
     * The file, located the way `EventDecodingTest` locates `events.json`.
     *
     * Three candidate layouts, because `core:data`'s test working directory is the module directory
     * while a run from the repository root sees the file one level up.
     */
    fun text(): String = CANDIDATES
        .map(::File)
        .firstOrNull { it.isFile }
        ?.readText()
        ?: error("config.schema.json not found from ${File(".").absolutePath}; tried ${CANDIDATES.joinToString()}")

    fun schema(): ConfigSchema = ConfigSchema.parse(text())

    fun root(): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text()).jsonObject

    /** The parsed file again, which is what a validator is built from directly in a test. */
    fun tree(): JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(text())

    private val CANDIDATES = listOf(
        "api/opencode-2.0.x/config.schema.json",
        "../../api/opencode-2.0.x/config.schema.json",
        "../../../api/opencode-2.0.x/config.schema.json",
    )
}

/**
 * The vendored OpenAPI spec, read from the repository.
 *
 * **Two vendored files, two jobs.** `config.schema.json` describes the *configuration file* — the
 * thirty-six top-level keys a user writes. `openapi.json` describes the *wire*, including
 * `Config.InfoEncoded`, the twenty-eight-key projection `config.get` returns and which the explorer's
 * `PROJECTION_NAMES` table maps onto. Both were vendored by Phase 0 and both are read here, so the
 * mapping is checked against the spec rather than against a list someone typed.
 */
object VendoredSpec {

    fun text(): String = CANDIDATES
        .map(::File)
        .firstOrNull { it.isFile }
        ?.readText()
        ?: error("openapi.json not found from ${File(".").absolutePath}; tried ${CANDIDATES.joinToString()}")

    fun root(): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text()).jsonObject

    /** One named schema from `components.schemas`. */
    fun component(name: String): JsonObject = root().getValue("components").jsonObject
        .getValue("schemas").jsonObject
        .getValue(name).jsonObject

    /** The `properties` map of a component schema. */
    fun propertiesOf(component: String): Map<String, JsonElement> {
        @Suppress("UNCHECKED_CAST")
        return component(component).getValue("properties") as Map<String, JsonElement>
    }

    private val CANDIDATES = listOf(
        "api/opencode-2.0.x/openapi.json",
        "../../api/opencode-2.0.x/openapi.json",
        "../../../api/opencode-2.0.x/openapi.json",
    )
}

/**
 * The configuration file schema's top-level key names, in the file's own order.
 *
 * Read without going through [ConfigSchema] so the coverage test compares the client's answer against
 * the authority rather than against itself.
 */
fun topLevelPropertyNames(root: JsonObject): List<String> {
    val defs = root.getValue("\$defs") as JsonObject
    val name = (root.getValue("\$ref") as JsonPrimitive).content.removePrefix("#/\$defs/")
    val config = defs.getValue(name) as JsonObject
    return (config.getValue("properties") as JsonObject).keys.toList()
}

/** The `properties` map of one `$defs` entry of the configuration file schema. */
fun propertiesOf(root: JsonObject, definition: String): Map<String, JsonElement> {
    val defs = root.getValue("\$defs") as JsonObject
    val schema = defs.getValue(definition) as JsonObject
    @Suppress("UNCHECKED_CAST")
    return schema.getValue("properties") as Map<String, JsonElement>
}

/**
 * Every **keyword** the vendored schema uses, walked through the local `$defs`.
 *
 * **The keys of a `properties` or `$defs` map are names, not keywords.** That distinction is the
 * whole test: without it the schema's two hundred property names — `bash`, `cwd`, `apiKey` — would read
 * as unknown keywords and fail, and the real question ("which assertions did the schema use?") would be
 * invisible. So the walk carries whether the current object's keys are names, and when they are it
 * records the values without recording the names.
 */
object SchemaKeywords {

    /** Every keyword used, plus how many local `$ref`s were followed. */
    data class Walk(val keywords: Set<String>, val localRefs: Int)

    fun used(root: JsonObject): Set<String> = walk(root).keywords

    fun walk(root: JsonObject): Walk {
        val defs = root["\$defs"] as? JsonObject
        val found = linkedSetOf<String>()
        var refs = 0

        fun visit(node: JsonElement, seenRefs: Set<String>, keysAreNames: Boolean) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    if (keysAreNames) {
                        visit(value, seenRefs, keysAreNames = false)
                    } else {
                        found.add(key)
                        when (key) {
                            "\$ref" -> {
                                val ref = (value as? JsonPrimitive)?.content ?: return@forEach
                                if (!ref.startsWith("#/\$defs/")) return@forEach
                                val name = ref.removePrefix("#/\$defs/")
                                if (name in seenRefs) return@forEach
                                refs++
                                defs?.get(name)?.let { visit(it, seenRefs + name, keysAreNames = false) }
                            }

                            "properties", "\$defs" -> visit(value, seenRefs, keysAreNames = true)
                            "prefixItems", "items", "anyOf", "allOf", "oneOf" ->
                                visit(value, seenRefs, keysAreNames = false)

                            else -> Unit
                        }
                    }
                }

                is kotlinx.serialization.json.JsonArray ->
                    node.forEach { visit(it, seenRefs, keysAreNames) }

                else -> Unit
            }
        }

        visit(root, emptySet(), keysAreNames = false)
        return Walk(found, refs)
    }
}

/** `Json.parseToJsonElement`, so a test can write a document as text. */
fun json(text: String): JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(text)
