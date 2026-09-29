package dev.opencode.android.core.data.config

import dev.opencode.android.core.testing.VendoredSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The vendored schema, read from the repository rather than embedded in a test.
 *
 * **Every test in this package validates against the file the app ships.** Reading
 * `api/opencode-2.0.x/config.schema.json` at run time is what makes these tests a statement about the
 * schema rather than about a fixture that happens to agree with it: a schema update that adds a key, an
 * assertion or an annotation the validator does not know fails here instead of quietly changing what the
 * app accepts.
 */
object VendoredSchema {

    fun text(): String = VendoredSpec.configSchemaText()

    fun schema(): ConfigSchema = ConfigSchema.parse(text())

    fun root(): JsonObject = VendoredSpec.configSchemaRoot()

    fun tree(): JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(text())
}

/** The configuration file schema's top-level key names, in the file's own order. */
fun topLevelPropertyNames(root: JsonObject): List<String> = VendoredSpec.topLevelConfigKeys()

/** The `properties` map of one `$defs` entry of the configuration file schema. */
fun propertiesOf(root: JsonObject, definition: String): Map<String, JsonElement> {
    val defs = root.getValue("\$defs") as JsonObject
    val schema = defs.getValue(definition) as JsonObject
    @Suppress("UNCHECKED_CAST")
    return schema.getValue("properties") as Map<String, JsonElement>
}

/** Every keyword the vendored schema uses, with its local references followed. */
object SchemaKeywords {
    fun used(root: JsonObject): Set<String> = VendoredSpec.schemaKeywords().keywords

    fun walk(root: JsonObject): VendoredSpec.Walk = VendoredSpec.schemaKeywords()
}

/** `Json.parseToJsonElement`, so a test can write a document as text. */
fun json(text: String): JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(text)
