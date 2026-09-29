package dev.opencode.android.core.testing

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The vendored V2 contracts, read from the repository at test time.
 *
 * **These are the authorities, not fixtures.** `api/opencode-2.0.x/` is what Phase 0 vendored and what
 * `tools/check-api-drift` watches, so a test that reads it is a statement about the contract the app is
 * built against; a test that read a checked-in copy of it would be a statement about whichever copy had
 * drifted furthest. That is the whole reason a handful of tests carry no expected values of their own
 * and compare against these files instead.
 *
 * **The files are located by walking up from the working directory**, because a JVM test's working
 * directory is the module's own (`core/data`, `feature/admin`) while a run from the repository root sees
 * them one level up. Two hardcoded candidates would break the moment a test moved to a third module, so
 * this walks and the first hit wins.
 */
object VendoredSpec {

    /** `api/opencode-2.0.x/config.schema.json`: the configuration *file* schema. */
    fun configSchemaText(): String = read("config.schema.json")

    /** `api/opencode-2.0.x/openapi.json`: the wire contract, including `Config.InfoEncoded`. */
    fun openApiText(): String = read("openapi.json")

    /** `api/opencode-2.0.x/events.json`: the event catalog. */
    fun eventsText(): String = read("events.json")

    private fun read(name: String): String {
        val start = File(".").absoluteFile
        var directory: File? = start
        while (directory != null) {
            val candidate = File(directory, "api/opencode-2.0.x/$name")
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error(
            "api/opencode-2.0.x/$name not found above ${start.path}. " +
                "Run the tests from a Gradle task, not from a copied directory.",
        )
    }

    /** The configuration file schema's top-level key names, in the file's own order. */
    fun topLevelConfigKeys(): List<String> {
        val root = configSchemaRoot()
        val defs = root.getValue("\$defs") as JsonObject
        val name = (root.getValue("\$ref") as JsonPrimitive).content.removePrefix("#/\$defs/")
        val config = defs.getValue(name) as JsonObject
        return (config.getValue("properties") as JsonObject).keys.toList()
    }

    /** The `properties` map of a component of the OpenAPI spec. */
    fun propertiesOf(component: String): Map<String, JsonElement> {
        val schemas = openApiRoot().getValue("components").jsonObject
            .getValue("schemas").jsonObject
        @Suppress("UNCHECKED_CAST")
        return schemas.getValue(component).jsonObject.getValue("properties") as Map<String, JsonElement>
    }

    fun configSchemaRoot(): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(configSchemaText()).jsonObject

    fun openApiRoot(): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(openApiText()).jsonObject

    /**
     * Every **keyword** the configuration schema uses, with its local `$defs` followed.
     *
     * **The keys of a `properties` or `$defs` map are names, not keywords**, and that distinction is the
     * point: without it the schema's two hundred property names read as unknown keywords, and the real
     * question — which assertions does this file actually use? — never gets answered. So the walk
     * carries whether the current object's keys are names and records only the rules when they are.
     */
    fun schemaKeywords(): Walk {
        val root = configSchemaRoot()
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

                is JsonArray -> node.forEach { visit(it, seenRefs, keysAreNames) }
                else -> Unit
            }
        }
        visit(root, emptySet(), keysAreNames = false)
        return Walk(found, refs)
    }

    /** The keywords used, and how many local references were followed to reach them. */
    data class Walk(val keywords: Set<String>, val localRefs: Int)
}
