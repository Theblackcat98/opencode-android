package dev.opencode.android.core.data.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One thing wrong with a document that could not even be parsed. */
data class DocumentParseFailure(
    /** 1-based line in the **original** text, comments and all. */
    val line: Int,
    val column: Int,
    /** What the parser said, with the document's own bytes removed. */
    val reason: String,
)

/**
 * A parsed document, or the syntax error that stopped it.
 *
 * **A syntax error and a schema error are different answers and are kept apart.** A syntax error
 * means the file cannot be read at all, so nothing can be shown from it and the editor has to ask for
 * a fix before anything else happens; a schema error means it reads fine and is wrong, which the
 * explorer can still describe. Collapsing them into one "invalid" would make a missing brace and a
 * misspelled key the same message.
 */
sealed interface ParsedDocument {
    /** The document as a tree. A configuration file is always an object, so this is a [JsonObject]. */
    data class Parsed(val document: JsonObject) : ParsedDocument

    /** The file could not be read. [failure] says where and why. */
    data class Failed(val failure: DocumentParseFailure) : ParsedDocument

    /** The tree, or `null` when the file could not be read. */
    val documentOrNull: JsonObject? get() = (this as? Parsed)?.document
}

/**
 * Read, modify and write back an `opencode.jsonc`.
 *
 * **Round-tripping is the hard part and it is why the text-level edit is surgical.** The file is
 * JSONC: comments, trailing commas and a formatting the user chose. Re-serialising the parsed tree
 * would delete every comment and rewrite the indentation, so a user who opened the editor to change
 * one model name would come back to a file that no longer looks like theirs and no longer has their
 * notes in it — and for a file people keep notes in, that is worse than refusing the edit. So
 * [setInText] rewrites only the bytes of the key it changes and leaves everything else exactly as it
 * was, including the comment above it.
 *
 * **[set] is the structural sibling, over a tree, with no text.** The guided templates validate a
 * tree, not a file, and [ConfigDocument.parse] has to produce the tree they validate; making the
 * templates go through text would mean re-serialising the user's whole file just to check a rule.
 *
 * **A parse failure is reported at a position in the original file, not in the masked copy.**
 * [Jsonc.mask] keeps every byte in place, so [parse] can hand the parser the masked text and still map
 * its offset back — which is the only reason the masking is byte-for-byte rather than a convenience.
 */
object ConfigDocument {

    /**
     * [text] as a tree, or the syntax error that stopped it.
     *
     * An empty or all-comment file is an **empty object**, not a failure: a user who has just created
     * `opencode.jsonc` and commented out the first key has a valid file with no keys, and the editor's
     * job is to let them add one.
     */
    fun parse(text: String): ParsedDocument {
        if (Jsonc.isBlank(text)) return ParsedDocument.Parsed(JsonObject(emptyMap()))
        val masked = Jsonc.mask(text)
        val element = try {
            kotlinx.serialization.json.Json.parseToJsonElement(masked)
        } catch (failure: kotlinx.serialization.SerializationException) {
            return ParsedDocument.Failed(failureOf(text, failure))
        } catch (failure: IllegalArgumentException) {
            return ParsedDocument.Failed(failureOf(text, failure))
        }
        return ParsedDocument.Parsed(
            element as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    /** The top-level keys a document's own text sets, which is what names a document's source. */
    fun topLevelKeys(text: String): Set<String> = parse(text).documentOrNull?.keys.orEmpty()

    /** [document] with [key] set to [value], over the tree. The key is added when it was absent. */
    fun set(document: JsonElement, key: String, value: JsonElement): JsonObject {
        val base = document as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(base + (key to value))
    }

    /** [document] with [key] removed, over the tree. */
    fun remove(document: JsonElement, key: String): JsonObject {
        val base = document as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(base - key)
    }

    /**
     * [text] with `"<key>": <value>` set, as text.
     *
     * **Only the key's own bytes are rewritten.** See the class note for why.
     *
     * @param indent the file's own indentation, detected from its first indented line and used when a
     *   key has to be appended.
     */
    fun setInText(text: String, key: String, value: JsonElement, indent: String = detectIndent(text)): String {
        val rendered = render(value, indent)
        val existing = spanOfValue(text, key)
        return if (existing != null) {
            text.substring(0, existing.valueStart) + rendered + text.substring(existing.valueEnd)
        } else {
            appendKey(text, key, rendered, indent)
        }
    }

    /** [text] with `"<key>"` removed, as text. A key that was not there is returned unchanged. */
    fun removeFromText(text: String, key: String): String {
        val existing = spanOfValue(text, key) ?: return text
        val end = existing.valueEnd
        // Swallowing the trailing comma is what keeps the remaining document valid rather than leaving
        // a dangling one, and swallowing the preceding whitespace is what stops a hole opening up.
        var to = end
        while (to < text.length && text[to].isWhitespace() && text[to] != '\n') to++
        if (to < text.length && text[to] == ',') to++
        var from = existing.keyStart
        while (from > 0 && text[from - 1].isWhitespace() && text[from - 1] != '\n') from--
        val head = text.substring(0, from).trimEnd()
        val tail = text.substring(to)
        // An object left empty is worse than no object.
        if (head.endsWith("{") && tail.trimStart().startsWith("}")) return head.dropLast(1) + tail
        return text.substring(0, from) + text.substring(to)
    }

    /**
     * A JSON value rendered for a configuration file.
     *
     * **Two-space indentation, one entry per line.** The vendored schema decides validity and this
     * only decides legibility; there is no canonical formatting in the spec, and a file written by a
     * human and a file written here should look alike.
     */
    fun render(value: JsonElement, indent: String = "  "): String = buildString {
        renderInto(this, value, "", indent)
    }

    // ------------------------------------------------------------------------------ internals

    private class Span(val keyStart: Int, val valueStart: Int, val valueEnd: Int)

    /**
     * The byte span of `"<key>": <value>`, or `null` when the key is not in the file.
     *
     * **A scanner over the masked text, not a regex or a re-serialisation.** It walks the document
     * tracking string state and nesting depth, and it finds only *top-level* members — so
     * `{"mcp": {"model": …}}` never makes `model` look like a top-level key, which is the mistake a
     * regex makes and the one that would corrupt a file the moment a nested key shared a name.
     */
    private fun spanOfValue(text: String, key: String): Span? {
        val masked = Jsonc.mask(text)
        var index = 0
        var depth = 0
        val length = masked.length
        while (index < length) {
            when (masked[index]) {
                '"' -> {
                    val end = skipString(masked, index)
                    if (depth == 1) {
                        val name = unquote(masked.substring(index, end))
                        // A member is `"key": value`; anything else with the same spelling is a value.
                        val colon = nextNonSpace(masked, end)
                        if (name == key && colon != null && masked[colon] == ':') {
                            val valueStart = nextNonSpace(masked, colon + 1) ?: return null
                            val valueEnd = spanEnd(masked, valueStart) ?: return null
                            return Span(keyStart = index, valueStart = valueStart, valueEnd = valueEnd)
                        }
                    }
                    index = end
                }

                '{', '[' -> {
                    depth++
                    index++
                }

                '}', ']' -> {
                    depth--
                    index++
                }

                else -> index++
            }
        }
        return null
    }

    /** The index just past the value starting at [start], or `null` for an unterminated one. */
    private fun spanEnd(masked: String, start: Int): Int? {
        val first = masked[start]
        return when {
            first == '"' -> skipString(masked, start)
            first == '{' || first == '[' -> {
                var depth = 0
                var index = start
                while (index < masked.length) {
                    when (masked[index]) {
                        '"' -> index = skipString(masked, index)
                        '{', '[' -> {
                            depth++
                            index++
                        }

                        '}', ']' -> {
                            depth--
                            index++
                            if (depth == 0) return index
                        }

                        else -> index++
                    }
                }
                null
            }

            // A bare literal runs to the next byte that cannot be part of it.
            else -> {
                var index = start
                while (index < masked.length && masked[index] !in ",}]\n \t\r") index++
                index
            }
        }
    }

    private fun skipString(masked: String, start: Int): Int {
        var index = start + 1
        while (index < masked.length) {
            when (masked[index]) {
                '\\' -> index += 2
                '"' -> return index + 1
                else -> index++
            }
        }
        return index
    }

    private fun nextNonSpace(masked: String, from: Int): Int? {
        var index = from
        while (index < masked.length && masked[index].isWhitespace()) index++
        return index.takeIf { it < masked.length }
    }

    private fun unquote(raw: String): String =
        if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        } else {
            raw
        }

    /** Adds `"key": value` before the object's closing brace, in the file's own indentation. */
    private fun appendKey(text: String, key: String, rendered: String, indent: String): String {
        val close = lastTopLevelBrace(text) ?: return text
        val before = text.substring(0, close)
        val after = text.substring(close)
        val trimmed = before.trimEnd()
        val isEmpty = trimmed.endsWith("{")
        val newline = if (before.contains('\n')) "\n" else ""
        val separator = if (isEmpty) "" else ",$newline"
        return "$trimmed$separator$indent${quote(key)}: $rendered$newline$after"
    }

    private fun lastTopLevelBrace(text: String): Int? {
        val masked = Jsonc.mask(text)
        var depth = 0
        var index = 0
        var last = -1
        while (index < masked.length) {
            when (masked[index]) {
                '"' -> index = skipString(masked, index)
                '{', '[' -> {
                    depth++
                    index++
                }

                '}', ']' -> {
                    if (depth == 1 && masked[index] == '}') last = index
                    depth--
                    index++
                }

                else -> index++
            }
        }
        return last.takeIf { it >= 0 }
    }

    private fun quote(key: String): String = JsonPrimitive(key).toString()

    private fun detectIndent(text: String): String =
        text.lineSequence()
            .map { it.takeWhile { character -> character == ' ' || character == '\t' } }
            .firstOrNull { it.isNotEmpty() }
            ?.let { existing -> if (existing.contains('\t')) "\t" else " ".repeat(existing.length) }
            ?: "  "

    private fun renderInto(out: StringBuilder, value: JsonElement, prefix: String, indent: String) {
        when (value) {
            is JsonObject -> {
                if (value.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                val inner = prefix + indent
                value.entries.forEachIndexed { index, (key, child) ->
                    if (index > 0) out.append(",\n")
                    out.append(inner).append(quote(key)).append(": ")
                    renderInto(out, child, inner, indent)
                }
                out.append('\n').append(prefix).append('}')
            }

            is JsonArray -> {
                if (value.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                val inner = prefix + indent
                value.forEachIndexed { index, child ->
                    if (index > 0) out.append(",\n")
                    out.append(inner)
                    renderInto(out, child, inner, indent)
                }
                out.append('\n').append(prefix).append(']')
            }

            else -> out.append(value.toString())
        }
    }

    private fun failureOf(text: String, failure: Throwable): DocumentParseFailure {
        val message = failure.message.orEmpty()
        // kotlinx reports "… at offset 12: … (line 3, column 5)". The offset is the useful half and it
        // is already in the original file's coordinates because the masking preserved length; the
        // line and column it also prints come from the same place.
        val offset = OFFSET.find(message)?.groupValues?.get(1)?.toIntOrNull()
        val position = offset?.let { Jsonc.positionOf(text, it) }
        val reportedLine = LINE.find(message)?.groupValues?.get(1)?.toIntOrNull()
        val reportedColumn = COLUMN.find(message)?.groupValues?.get(1)?.toIntOrNull()
        return DocumentParseFailure(
            line = position?.line ?: reportedLine ?: 1,
            column = position?.column ?: reportedColumn ?: 1,
            // The parser's first line names the position and the structural problem; the rest of the
            // message is `JSON input: …`, which quotes the document's own bytes — and a
            // configuration file holds API keys. So the line is kept and every quoted token after
            // "but had" is replaced, leaving "expected '}' but had an unexpected value".
            reason = message.lineSequence().firstOrNull().orEmpty()
                .replace(HAD_TOKEN, "had an unexpected value")
                .trim()
                .ifEmpty { "Could not be read" },
        )
    }

    private val OFFSET = Regex("offset (\\d+)")
    private val LINE = Regex("line (\\d+)")
    private val COLUMN = Regex("column (\\d+)")
    private val HAD_TOKEN = Regex("had '.*'")
}
