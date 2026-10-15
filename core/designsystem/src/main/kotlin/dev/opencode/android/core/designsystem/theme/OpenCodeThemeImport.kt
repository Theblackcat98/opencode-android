package dev.opencode.android.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.pow

/** Failure categories are mapped to localized strings by the importing screen. */
enum class ThemeImportFailure {
    TOO_LARGE,
    MALFORMED_JSON,
    MISSING_BASE,
    MISSING_MODE,
    LOW_CONTRAST,
}

class ThemeImportException(
    val failure: ThemeImportFailure,
    val details: List<String> = emptyList(),
) : IllegalArgumentException(failure.name)

/**
 * The normalized, client-side subset of a V2 theme that the app can safely apply.
 *
 * Colors are ARGB values keyed by Material role (`primary`, `onPrimary`, and so on). Missing roles
 * retain the built-in OpenCode scheme. The source JSON is never persisted; only this small, validated
 * palette is written to preferences.
 */
data class ImportedThemePalette(
    val name: String,
    val light: Map<String, Int>,
    val dark: Map<String, Int>,
    val unsupportedTokens: List<String> = emptyList(),
    val unsupportedTokenCount: Int = unsupportedTokens.size,
) {
    fun colorScheme(darkTheme: Boolean, fallback: ColorScheme): ColorScheme {
        val roles = if (darkTheme) dark else light
        fun color(role: String, original: Color): Color = roles[role]?.let(::Color) ?: original

        return fallback.copy(
            primary = color("primary", fallback.primary),
            onPrimary = color("onPrimary", fallback.onPrimary),
            secondary = color("secondary", fallback.secondary),
            onSecondary = color("onSecondary", fallback.onSecondary),
            error = color("error", fallback.error),
            onError = color("onError", fallback.onError),
            background = color("background", fallback.background),
            onBackground = color("onBackground", fallback.onBackground),
            surface = color("surface", fallback.surface),
            onSurface = color("onSurface", fallback.onSurface),
            surfaceVariant = color("surfaceVariant", fallback.surfaceVariant),
            onSurfaceVariant = color("onSurfaceVariant", fallback.onSurfaceVariant),
            surfaceContainer = color("surfaceContainer", fallback.surfaceContainer),
            surfaceContainerHigh = color("surfaceContainerHigh", fallback.surfaceContainerHigh),
            surfaceContainerHighest = color("surfaceContainerHighest", fallback.surfaceContainerHighest),
            outline = color("outline", fallback.outline),
        )
    }

    /** Store only the compact supported palette, never the raw theme file. */
    fun toStoredJson(): String = buildJsonObject {
        put("version", STORAGE_VERSION)
        put("name", name.take(MAX_NAME_LENGTH))
        put("light", roleObject(light))
        put("dark", roleObject(dark))
    }.toString()

    companion object {
        private const val STORAGE_VERSION = 1
        private const val MAX_NAME_LENGTH = 96
        private const val MAX_STORED_BYTES = 16 * 1024

        /** Invalid or outdated stored data is ignored so the built-in theme remains the fallback. */
        fun fromStoredJson(json: String?): ImportedThemePalette? {
            if (json.isNullOrBlank() || json.toByteArray(Charsets.UTF_8).size > MAX_STORED_BYTES) return null
            return runCatching {
                val root = Json.parseToJsonElement(json).jsonObject
                if (root["version"]?.jsonPrimitive?.intOrNull != STORAGE_VERSION) return null
                val name = root["name"]?.jsonPrimitive?.content?.take(MAX_NAME_LENGTH) ?: return null
                val light = readStoredRoles(root["light"]?.jsonObject ?: return null) ?: return null
                val dark = readStoredRoles(root["dark"]?.jsonObject ?: return null) ?: return null
                val palette = ImportedThemePalette(name, light, dark)
                if (OpenCodeThemeImporter.contrastFailures(palette, darkTheme = false).isNotEmpty()) return null
                if (OpenCodeThemeImporter.contrastFailures(palette, darkTheme = true).isNotEmpty()) return null
                palette
            }.getOrNull()
        }

        private fun roleObject(roles: Map<String, Int>): JsonObject =
            JsonObject(
                roles.filterKeys(OpenCodeThemeImporter.SUPPORTED_ROLES::contains)
                    .mapValues { JsonPrimitive(OpenCodeThemeImporter.toCssHex(it.value)) },
            )

        private fun readStoredRoles(values: JsonObject): Map<String, Int>? {
            if (values.keys.any { it !in OpenCodeThemeImporter.SUPPORTED_ROLES }) return null
            val result = linkedMapOf<String, Int>()
            values.forEach { (role, value) ->
                val text = (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return null
                result[role] = OpenCodeThemeImporter.parseHexColor(text)?.toArgb() ?: return null
            }
            return result
        }
    }
}

/**
 * Imports the supported part of OpenCode's V2 theme schema.
 *
 * Supported mappings are intentionally narrow: base/raised surfaces, muted/base text, base borders,
 * and primary/secondary/destructive actions. Unknown token values and unresolvable supported values
 * are reported by path. Missing tokens keep the app's built-in Material colors.
 */
object OpenCodeThemeImporter {
    const val MAX_SOURCE_BYTES = 256 * 1024
    const val MIN_TEXT_CONTRAST = 4.5
    const val MIN_COMPONENT_CONTRAST = 3.0
    internal const val MAX_JSON_NESTING = 64
    internal const val MAX_COLOR_REFERENCE_DEPTH = 64
    private const val MAX_UNSUPPORTED_SAMPLE = 24

    private data class Mapping(val token: String, val roles: List<String>)

    private val mappings = listOf(
        Mapping("background.base", listOf("background", "surface")),
        Mapping("text.base", listOf("onBackground", "onSurface")),
        Mapping("background.raised.base", listOf("surfaceVariant", "surfaceContainer")),
        Mapping("background.raised.high", listOf("surfaceContainerHigh")),
        Mapping("background.raised.max", listOf("surfaceContainerHighest")),
        Mapping("text.muted", listOf("onSurfaceVariant")),
        Mapping("border.base", listOf("outline")),
        Mapping("background.action.primary.base", listOf("primary")),
        Mapping("text.action.primary.base", listOf("onPrimary")),
        Mapping("background.action.secondary.base", listOf("secondary")),
        Mapping("text.action.secondary.base", listOf("onSecondary")),
        Mapping("background.action.destructive.base", listOf("error")),
        Mapping("text.action.destructive.base", listOf("onError")),
    )

    private val supportedPaths = mappings.mapTo(hashSetOf()) { it.token }

    /**
     * Parse a bounded V2 theme document and validate all text/background pairs before it can be
     * previewed or applied. One supplied mode is used for both light and dark, per OpenCode behavior.
     */
    fun parse(source: String, name: String): ImportedThemePalette {
        if (source.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) {
            throw ThemeImportException(ThemeImportFailure.TOO_LARGE)
        }
        if (hasExcessiveNesting(source)) throw ThemeImportException(ThemeImportFailure.MALFORMED_JSON)
        val root = try {
            Json.parseToJsonElement(source) as? JsonObject
                ?: throw ThemeImportException(ThemeImportFailure.MALFORMED_JSON)
        } catch (failure: ThemeImportException) {
            throw failure
        } catch (_: Exception) {
            throw ThemeImportException(ThemeImportFailure.MALFORMED_JSON)
        }
        val base = root["base"] as? JsonObject ?: throw ThemeImportException(ThemeImportFailure.MISSING_BASE)
        val light = root["light"] as? JsonObject
        val dark = root["dark"] as? JsonObject
        if (light == null && dark == null) throw ThemeImportException(ThemeImportFailure.MISSING_MODE)

        val unsupported = UnsupportedCollector()
        collectUnsupported(base, "base", unsupported)
        light?.let { collectUnsupported(it, "light", unsupported) }
        dark?.let { collectUnsupported(it, "dark", unsupported) }

        val lightSource = light ?: requireNotNull(dark)
        val darkSource = dark ?: requireNotNull(light)
        val lightRoles = resolveOverrides(base, lightSource, "light", unsupported)
        val darkRoles = if (light == null || dark == null) {
            lightRoles.toMap()
        } else {
            resolveOverrides(base, darkSource, "dark", unsupported)
        }
        val palette = ImportedThemePalette(
            name = name.ifBlank { "OpenCode theme" }.take(96),
            light = lightRoles,
            dark = darkRoles,
            unsupportedTokens = unsupported.sample.toList(),
            unsupportedTokenCount = unsupported.total,
        )
        val contrast = contrastFailures(palette, false) + contrastFailures(palette, true)
        if (contrast.isNotEmpty()) {
            throw ThemeImportException(ThemeImportFailure.LOW_CONTRAST, contrast.distinct())
        }
        return palette
    }

    /** Reject deep structures before the JSON parser or the unsupported-token visitor can recurse. */
    private fun hasExcessiveNesting(source: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in source) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true

                    '{', '[' -> {
                        depth++
                        if (depth > MAX_JSON_NESTING) return true
                    }

                    '}', ']' -> depth--
                }
            }
        }
        return false
    }

    private fun resolveOverrides(
        base: JsonObject,
        mode: JsonObject,
        modeName: String,
        unsupported: UnsupportedCollector,
    ): Map<String, Int> {
        val roles = linkedMapOf<String, Int>()
        val context = ResolveContext(base, mode)
        mappings.forEach { mapping ->
            val value = lookup(mode, mapping.token) ?: lookup(base, mapping.token) ?: return@forEach
            val primitive = value as? JsonPrimitive
            val color = primitive?.takeIf(JsonPrimitive::isString)?.content?.let { raw ->
                resolveColor(raw, context, setOf("token:${mapping.token}"))
            }
            if (color == null) {
                unsupported.add("$modeName.${mapping.token} (unresolved or unsupported color)")
            } else {
                mapping.roles.forEach { role -> roles[role] = color.toArgb() }
            }
        }
        return roles
    }

    private data class ResolveContext(val base: JsonObject, val mode: JsonObject)

    private fun resolveColor(raw: String, context: ResolveContext, visited: Set<String>): Color? {
        val value = raw.trim()
        if (value.equals("transparent", ignoreCase = true)) return Color.Transparent
        parseHexColor(value)?.let { return it }
        if (!value.startsWith('$')) return null
        val reference = value.drop(1)
        return if (reference.startsWith("hue.")) {
            resolveHue(reference, context, visited)
        } else {
            resolveToken(reference, context, visited)
        }
    }

    private fun resolveToken(path: String, context: ResolveContext, visited: Set<String>): Color? {
        val key = "token:$path"
        if (key in visited || visited.size >= MAX_COLOR_REFERENCE_DEPTH) return null
        val value = lookup(context.mode, path) ?: lookup(context.base, path) ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return resolveColor(primitive.content, context, visited + key)
    }

    private fun resolveHue(path: String, context: ResolveContext, visited: Set<String>): Color? {
        val key = "hue:$path"
        if (key in visited || visited.size >= MAX_COLOR_REFERENCE_DEPTH) return null
        val parts = path.removePrefix("hue.").split('.')
        if (parts.size < 2) return null
        val hues = context.mode["hue"] as? JsonObject ?: return null
        var value: JsonElement? = hues[parts.first()] ?: return null
        if (value is JsonPrimitive && value.isString && value.content.startsWith("\$hue.")) {
            val alias = value.content.removePrefix("\$hue.")
            val aliasedPath = "hue.$alias" + parts.drop(1).joinToString(separator = "", prefix = ".")
            return resolveHue(aliasedPath, context, visited + key)
        }
        for (part in parts.drop(1)) {
            value = (value as? JsonObject)?.get(part) ?: return null
        }
        val primitive = value as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return resolveColor(primitive.content, context, visited + key)
    }

    private fun lookup(root: JsonObject, path: String): JsonElement? =
        path.split('.').fold(root as JsonElement?) { current, segment -> (current as? JsonObject)?.get(segment) }

    private class UnsupportedCollector {
        val sample = mutableListOf<String>()
        var total: Int = 0
            private set

        fun add(path: String) {
            total++
            if (sample.size < MAX_UNSUPPORTED_SAMPLE) sample += path
        }
    }

    private fun collectUnsupported(root: JsonObject, section: String, collector: UnsupportedCollector) {
        fun visit(node: JsonElement, prefix: String) {
            if (prefix == "hue" || prefix.startsWith("hue.")) return
            if (prefix in supportedPaths) return
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    visit(value, if (prefix.isEmpty()) key else "$prefix.$key")
                }

                is JsonArray -> if (node.isNotEmpty()) collector.add("$section.$prefix")

                else -> collector.add("$section.$prefix")
            }
        }
        root.forEach { (key, value) -> visit(value, key) }
    }

    private val contrastPairs = listOf(
        "onPrimary" to "primary",
        "onSecondary" to "secondary",
        "onError" to "error",
        "onBackground" to "background",
        "onSurface" to "surface",
        "onSurface" to "surfaceVariant",
        "onSurface" to "surfaceContainer",
        "onSurface" to "surfaceContainerHigh",
        "onSurface" to "surfaceContainerHighest",
        "onSurfaceVariant" to "surface",
        "onSurfaceVariant" to "surfaceVariant",
        "onSurfaceVariant" to "surfaceContainer",
        "onSurfaceVariant" to "surfaceContainerHigh",
        "onSurfaceVariant" to "surfaceContainerHighest",
    )
    private val componentContrastPairs = listOf(
        "outline" to "background",
        "outline" to "surface",
    )

    /** Text and component contrast failures against the built-in role color beneath translucency. */
    fun contrastFailures(palette: ImportedThemePalette, darkTheme: Boolean): List<String> {
        val fallback = if (darkTheme) OpenCodeDarkColors else OpenCodeLightColors
        val scheme = palette.colorScheme(darkTheme, fallback)
        fun failures(
            pairs: List<Pair<String, String>>,
            minimum: Double,
        ): List<String> = pairs.mapNotNull { (foregroundRole, backgroundRole) ->
            val backgroundFallback = fallback.colorFor(backgroundRole)
            val background = scheme.colorFor(backgroundRole).compositeOver(backgroundFallback)
            val foreground = scheme.colorFor(foregroundRole).compositeOver(background)
            val ratio = contrastRatio(foreground, background)
            if (ratio < minimum) "$foregroundRole/$backgroundRole (${"%.2f".format(ratio)}:1)" else null
        }
        return failures(contrastPairs, MIN_TEXT_CONTRAST) + failures(componentContrastPairs, MIN_COMPONENT_CONTRAST)
    }

    private fun ColorScheme.colorFor(role: String): Color = when (role) {
        "primary" -> primary
        "onPrimary" -> onPrimary
        "secondary" -> secondary
        "onSecondary" -> onSecondary
        "error" -> error
        "onError" -> onError
        "background" -> background
        "onBackground" -> onBackground
        "surface" -> surface
        "onSurface" -> onSurface
        "surfaceVariant" -> surfaceVariant
        "onSurfaceVariant" -> onSurfaceVariant
        "surfaceContainer" -> surfaceContainer
        "surfaceContainerHigh" -> surfaceContainerHigh
        "surfaceContainerHighest" -> surfaceContainerHighest
        "outline" -> outline
        else -> Color.Unspecified
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        fun luminance(color: Color): Double {
            fun linear(channel: Double): Double =
                if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
            return 0.2126 * linear(color.red.toDouble()) +
                0.7152 * linear(color.green.toDouble()) +
                0.0722 * linear(color.blue.toDouble())
        }
        val first = luminance(foreground)
        val second = luminance(background)
        val (light, dark) = if (first > second) first to second else second to first
        return (light + 0.05) / (dark + 0.05)
    }

    internal fun parseHexColor(value: String): Color? {
        val digits = value.removePrefix("#")
        if (value.firstOrNull() != '#' || digits.any { it !in "0123456789abcdefABCDEF" }) return null
        fun pair(index: Int): Int = digits.substring(index, index + 2).toInt(16)
        return when (digits.length) {
            3 -> Color(digits[0].digitToInt(16) * 17 / 255f, digits[1].digitToInt(16) * 17 / 255f, digits[2].digitToInt(16) * 17 / 255f)

            4 -> Color(
                digits[0].digitToInt(16) * 17 / 255f,
                digits[1].digitToInt(16) * 17 / 255f,
                digits[2].digitToInt(16) * 17 / 255f,
                digits[3].digitToInt(16) * 17 / 255f,
            )

            6 -> Color(pair(0) / 255f, pair(2) / 255f, pair(4) / 255f)

            8 -> Color(pair(0) / 255f, pair(2) / 255f, pair(4) / 255f, pair(6) / 255f)

            else -> null
        }
    }

    internal fun toCssHex(argb: Int): String {
        val alpha = (argb ushr 24) and 0xFF
        val red = (argb ushr 16) and 0xFF
        val green = (argb ushr 8) and 0xFF
        val blue = argb and 0xFF
        return "#%02X%02X%02X%02X".format(red, green, blue, alpha)
    }

    internal val SUPPORTED_ROLES = setOf(
        "primary", "onPrimary", "secondary", "onSecondary", "error", "onError",
        "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
        "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest", "outline",
    )
}
