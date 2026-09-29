package dev.opencode.android.core.data.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One thing wrong with a configuration document, in the terms a person can act on.
 *
 * **A diagnostic names a place, a rule and what it expected.** The exit criterion is "invalid
 * documents producing useful diagnostics at a useful location", and a diagnostic without a location
 * is not useful on a phone: the editor is one scrolling column of text, so being told which line to
 * look at is the only thing that lets anyone fix a file.
 *
 * **[found] never carries a value that could be a key.** A configuration file holds API keys under
 * `provider.*.options.apiKey` and `enterprise`, and a diagnostic that echoed one would put it in a
 * log line, an exception message and a screenshot. It says `a string of 40 characters` instead —
 * see [ConfigRedaction] for the same rule applied to the explorer.
 */
data class SchemaDiagnostic(
    /** The instance path, in JSON Pointer form: `/mcp/linear/command/0`. Empty means the root. */
    val path: String,
    /** The schema keyword that rejected it, which says what kind of rule it was. */
    val keyword: String,
    /** What the schema wanted, in words: `a string`, `one of allow, ask, deny`. */
    val expected: String,
    /** What was there, described without its content. */
    val found: String,
    /** 1-based line, when the byte offset could be resolved. */
    val line: Int? = null,
    /** 1-based column, when the byte offset could be resolved. */
    val column: Int? = null,
)

/**
 * A validator for the JSON Schema dialect `api/opencode-2.0.x/config.schema.json` is written in,
 * covering exactly the assertion keywords that file uses.
 *
 * **This is a subset validator and says so, in the constant below.** The vendored schema is draft
 * 2020-12 and its assertions are `type`, `enum`, `const`, `properties`, `additionalProperties`,
 * `required`, `items`, `prefixItems`, `minItems`, `maxItems`, `minimum`, `maximum`,
 * `exclusiveMinimum`, `exclusiveMaximum`, `pattern`, `anyOf` and `$ref`. Anything else is an
 * annotation — `description`, `hidden` — and ignoring an annotation is correct. Ignoring an
 * *assertion* this list does not name would be a silent hole, so `UnsupportedKeywordTest` walks the
 * vendored file and fails on any keyword that is neither supported nor a known annotation. That
 * test is what makes the subset trustworthy rather than merely believed sufficient, and it is the
 * reason it is safe to ship rather than reach for a dependency.
 *
 * **`$ref` is applied alongside its siblings, as draft 2020-12 says.** It matters for two of the
 * schema's keys: `model` and `small_model` are `{"type": "string", "$ref": "…models.dev…"}`, and a
 * client that followed a `$ref` exclusively would apply no check at all to the one key the guided
 * "default model" template writes.
 *
 * **An external `$ref` is skipped, not guessed.** The schema references
 * `https://models.dev/model-schema.json#/$defs/Model`, which is not vendored and cannot be fetched
 * from a phone talking to a server on a LAN. Skipping the unresolvable branch while still applying
 * its siblings is the honest behaviour; the alternative — treating an unknown `$ref` as "anything
 * goes" for the enclosing object — would also discard the `type: string` beside it. [unresolvedRefs]
 * reports what was skipped so a caller can say so instead of implying the key was fully checked.
 */
class JsonSchemaValidator(private val schema: JsonObject) {

    private val defs: JsonObject = schema["\$defs"] as? JsonObject ?: JsonObject(emptyMap())
    private val patterns = mutableMapOf<String, Regex>()
    private val skipped = mutableListOf<String>()

    /** External references that were skipped, so a caller can report the limits of this run. */
    var unresolvedRefs: List<String> = emptyList()
        private set

    /**
     * Validates [instance] and answers every problem, in document order.
     *
     * **All problems, not the first.** Someone editing a JSON file on a phone gets one round trip per
     * save, so a validator that stopped at the first error would make a file with three mistakes take
     * three saves to fix.
     */
    fun validate(instance: JsonElement): List<SchemaDiagnostic> {
        skipped.clear()
        val found = mutableListOf<SchemaDiagnostic>()
        check(instance, schema, "", found)
        unresolvedRefs = skipped.toSet().toList()
        return found
    }

    /** Whether [instance] has no problems. */
    fun isValid(instance: JsonElement): Boolean = validate(instance).isEmpty()

    // ------------------------------------------------------------------------------ dispatch

    private fun check(node: JsonElement, rule: JsonElement, path: String, found: MutableList<SchemaDiagnostic>) {
        when (rule) {
            is JsonObject -> checkSchema(node, rule, path, found)
            // `true` and `false` are legal schemas in 2020-12. `false` rejects everything and `true`
            // accepts everything; the vendored file uses the object form throughout, so both are
            // honoured rather than assumed away.
            is JsonPrimitive -> if (rule.booleanOrNull == false) {
                found += report(path, "schema", "a value this schema allows", describe(node))
            }

            else -> Unit
        }
    }

    private fun checkSchema(node: JsonElement, rule: JsonObject, path: String, found: MutableList<SchemaDiagnostic>) {
        rule["\$ref"]?.let { ref ->
            val text = (ref as? JsonPrimitive)?.contentOrNull
            val target = resolve(text)
            when {
                target == null -> if (text != null && !text.startsWith("#/\$defs/")) skipped += text
                else -> check(node, target, path, found)
            }
        }

        val typeNames = typeNamesOf(rule["type"])
        if (typeNames.isNotEmpty() && typeNames.none { matchesType(node, it) }) {
            found += report(path, "type", typeNames.joinToString(" or ") { "a $it" }, describe(node))
            // Every other assertion is phrased in terms of the type, so continuing would add a
            // cascade of complaints about a value whose shape is already wrong.
            return
        }

        (rule["enum"] as? JsonArray)?.let { options ->
            if (options.none { it == node }) {
                found += report(path, "enum", "one of ${options.joinToString(", ") { describe(it) }}", describe(node))
            }
        }

        rule["const"]?.let { wanted ->
            if (wanted != node) found += report(path, "const", describe(wanted), describe(node))
        }

        (rule["anyOf"] as? JsonArray)?.let { branches ->
            // `anyOf` passes when *any* branch passes, so its failures are the branches' failures and
            // not its own. Reporting all of them would list every way the value is not a permission
            // rule *and* not an action map *and* not an object map, which is noise; the closest
            // branch's complaints are what points at the actual mistake.
            val perBranch = branches.map { branch ->
                val branchFound = mutableListOf<SchemaDiagnostic>()
                check(node, branch, path, branchFound)
                branchFound
            }
            if (perBranch.all { it.isNotEmpty() }) {
                found += report(
                    path,
                    "anyOf",
                    describeAnyOf(branches),
                    describe(node),
                )
                // The closest branch's own diagnostics are kept alongside: `anyOf` alone tells a user
                // nothing about *which* of the shapes they were closer to.
                perBranch.minByOrNull { it.size }?.takeIf { it.isNotEmpty() && it.size <= CLOSEST_BRANCH }?.let {
                    found += it
                }
            }
        }

        when (node) {
            is JsonObject -> checkObjectNode(node, rule, path, found)
            is JsonArray -> checkArrayNode(node, rule, path, found)
            is JsonPrimitive -> checkScalar(node, rule, path, found)
            else -> Unit
        }
    }

    private fun checkObjectNode(node: JsonObject, rule: JsonObject, path: String, found: MutableList<SchemaDiagnostic>) {
        val properties = rule["properties"] as? JsonObject

        (rule["required"] as? JsonArray)?.forEach { entry ->
            val name = (entry as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (!node.containsKey(name)) {
                found += report(pointer(path, name), "required", "the key $name", "nothing")
            }
        }

        val additional = rule["additionalProperties"]
        node.forEach { (key, value) ->
            val childPath = pointer(path, key)
            val childRule = properties?.get(key)
            when {
                childRule != null -> check(value, childRule, childPath, found)
                additional == null -> Unit
                additional is JsonPrimitive && additional.booleanOrNull == false ->
                    found += report(childPath, "additionalProperties", "a key this schema allows", describe(value))
                // A schema rather than a boolean: every named key is checked above and this checks
                // the rest, which is how `agent`, `command`, `provider` and `mcp` are shaped.
                else -> check(value, additional, childPath, found)
            }
        }
    }

    private fun checkArrayNode(node: JsonArray, rule: JsonObject, path: String, found: MutableList<SchemaDiagnostic>) {
        val prefix = rule["prefixItems"] as? JsonArray
        val items = rule["items"]
        node.forEachIndexed { index, element ->
            // `prefixItems` is positional and `items` covers the rest, which is how 2020-12 reads the
            // pair; the vendored schema uses both and never a tuple-form `items`.
            check(element, prefix?.getOrNull(index) ?: items ?: return@forEachIndexed, "$path/$index", found)
        }
        val minItems = (rule["minItems"] as? JsonPrimitive)?.longOrNull
        if (minItems != null && node.size < minItems) {
            found += report(path, "minItems", "at least $minItems ${entry(node.size)}", describe(node))
        }
        val maxItems = (rule["maxItems"] as? JsonPrimitive)?.longOrNull
        if (maxItems != null && node.size > maxItems) {
            found += report(path, "maxItems", "at most $maxItems ${entry(node.size)}", describe(node))
        }
    }

    private fun checkScalar(node: JsonPrimitive, rule: JsonObject, path: String, found: MutableList<SchemaDiagnostic>) {
        if (node.isString) {
            val pattern = (rule["pattern"] as? JsonPrimitive)?.contentOrNull ?: return
            val regex = patterns.getOrPut(pattern) { Regex(pattern) }
            val text = node.contentOrNull ?: return
            if (!regex.containsMatchIn(text)) {
                found += report(path, "pattern", "a value matching $pattern", describe(node))
            }
            return
        }
        val number = node.doubleOrNull ?: return
        val minimum = (rule["minimum"] as? JsonPrimitive)?.doubleOrNull
        if (minimum != null && number < minimum) found += report(path, "minimum", "at least $minimum", describe(node))
        val maximum = (rule["maximum"] as? JsonPrimitive)?.doubleOrNull
        if (maximum != null && number > maximum) found += report(path, "maximum", "at most $maximum", describe(node))
        val exclusiveMinimum = (rule["exclusiveMinimum"] as? JsonPrimitive)?.doubleOrNull
        if (exclusiveMinimum != null && number <= exclusiveMinimum) {
            found += report(path, "exclusiveMinimum", "greater than $exclusiveMinimum", describe(node))
        }
        val exclusiveMaximum = (rule["exclusiveMaximum"] as? JsonPrimitive)?.doubleOrNull
        if (exclusiveMaximum != null && number >= exclusiveMaximum) {
            found += report(path, "exclusiveMaximum", "less than $exclusiveMaximum", describe(node))
        }
    }

    // ------------------------------------------------------------------------------ helpers

    private fun typeNamesOf(node: JsonElement?): List<String> = when (node) {
        is JsonPrimitive -> listOfNotNull(node.contentOrNull)
        is JsonArray -> node.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    private fun matchesType(node: JsonElement, type: String): Boolean = when (type) {
        "object" -> node is JsonObject
        "array" -> node is JsonArray
        "string" -> node is JsonPrimitive && node.isString
        "boolean" -> node is JsonPrimitive && node.booleanOrNull != null
        // An integer is a number with no fractional part. `1.0` is a number and not an integer, so a
        // config saying `"mcp_timeout": 1.5` is rejected for the same reason `0` is.
        "integer" -> node is JsonPrimitive && !node.isString &&
            node.doubleOrNull?.let { !it.isInfinite() && !it.isNaN() && it == Math.floor(it) } == true
        "number" -> node is JsonPrimitive && !node.isString && node.doubleOrNull != null
        "null" -> node is JsonNull
        // A type this dialect does not define cannot reject, and inventing a rejection for it would
        // make a future schema addition fail every document.
        else -> true
    }

    /**
     * A description of a value, for a diagnostic — never the value itself.
     *
     * A configuration file holds API keys under `provider.*.options.apiKey` and under `enterprise`.
     * A diagnostic that echoed one would put it into a log line, an exception message and a
     * screenshot, so this prints a type and a length and the person reads the value from the file
     * they are already looking at.
     */
    private fun describe(node: JsonElement): String = when (node) {
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            node.isString -> "a string of ${node.contentOrNull.orEmpty().length} characters"
            node.booleanOrNull != null -> "the boolean ${node.content}"
            node.doubleOrNull != null -> "the number ${node.content}"
            else -> "a value"
        }

        is JsonArray -> "an array of ${node.size} ${entry(node.size)}"
        is JsonObject -> "an object with ${node.size} ${entry(node.size)}"
    }

    /**
     * What an `anyOf` wanted, in one line.
     *
     * A branch's `type` is the useful half; a nested `anyOf` has no type of its own, so the branch
     * is described as "another shape" rather than by a keyword the reader would have to look up.
     */
    private fun describeAnyOf(branches: JsonArray): String {
        val described = branches.map { branch ->
            val type = (branch as? JsonObject)?.get("type")
            if (type is JsonPrimitive) "a ${type.contentOrNull}" else "another allowed shape"
        }
        return "one of: ${described.distinct().joinToString(", ")}"
    }

    private fun entry(count: Int) = if (count == 1) "entry" else "entries"

    private fun report(path: String, keyword: String, expected: String, found: String) =
        SchemaDiagnostic(path = path, keyword = keyword, expected = expected, found = found)

    /** JSON Pointer escaping, which RFC 6901 requires for `~` and `/`. */
    private fun pointer(parent: String, key: String): String =
        parent + "/" + key.replace("~", "~0").replace("/", "~1")

    private fun resolve(ref: String?): JsonElement? {
        val name = ref?.takeIf { it.startsWith("#/\$defs/") }?.removePrefix("#/\$defs/") ?: return null
        return defs[name.replace("~1", "/").replace("~0", "~")]
    }

    companion object {
        /**
         * How many diagnostics of the closest `anyOf` branch are worth showing.
         *
         * A cap rather than a rule: three complaints from the branch that got furthest is enough to
         * name the mistake, and a branch that failed on thirty counts is a branch the user was not
         * trying to write.
         */
        private const val CLOSEST_BRANCH = 3

        /**
         * The assertion keywords this validator honours.
         *
         * Read by `UnsupportedKeywordTest`, which walks the vendored schema and fails on any keyword
         * that is neither here nor a known annotation.
         */
        val ASSERTION_KEYWORDS: Set<String> = setOf(
            "\$ref", "\$defs", "\$schema", "type", "enum", "const", "properties", "additionalProperties",
            "required", "items", "prefixItems", "minItems", "maxItems", "minimum", "maximum",
            "exclusiveMinimum", "exclusiveMaximum", "pattern", "anyOf",
        )

        /** Keywords that carry no assertion, so ignoring them is correct. */
        val ANNOTATION_KEYWORDS: Set<String> = setOf(
            "description", "hidden", "allowComments", "allowTrailingCommas",
        )
    }
}
