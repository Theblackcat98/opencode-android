package dev.opencode.android.core.data.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * What the explorer and the diagnostics are allowed to show of a configuration value.
 *
 * **A configuration file is not a secret store, and this app must not become one by accident.** It
 * holds API keys (`provider.*.options.apiKey`, `provider.*.options.api`), a private base URL
 * (`enterprise.url`), the OAuth `clientSecret` an MCP server may carry, and whatever a user put in
 * `username`. Any of those reaching a log line, a `toString()`, a crash report or a committed
 * screenshot is the leak the Keystore work exists to prevent — and the explorer is exactly the
 * screen that would do it, because its job is to show the user their whole configuration.
 *
 * **So nothing renders a raw value by default.** [describe] is the only function the screens call,
 * and it redacts by *key name* first and by *value shape* second:
 *
 *  - a **key** that names a secret is redacted whatever its value is — an `apiKey` that happens to
 *    hold `"none"` is still an `apiKey`, and its emptiness is not the user's business;
 *  - a **value** that looks like a credential is redacted whatever its key — a key called
 *    `label` holding `sk-ant-api03-…` is still a key, and a name is not a promise;
 *  - everything else is described by **shape**, not by content: `a string of 12 characters`,
 *    `a list of 3 entries`, `an object with 2 keys`. The user is looking at the file; they do not
 *    need the app to repeat it back at them, and a value they cannot see is a value that cannot
 *    leak into a screenshot they share.
 *
 * **[reveal] exists for exactly one caller: the editor's own text field.** Someone editing
 * `opencode.jsonc` has to see what they typed, because the alternative is editing a file they
 * cannot read. It is deliberately not reachable from a row, a diagnostic or a log.
 */
object ConfigRedaction {

    /** What replaces a value that must not be shown. */
    const val MASK: String = "••••••"

    /**
     * Keys whose value is never shown.
     *
     * **Named from the vendored schema, not guessed.** `ProviderConfig` has `options.apiKey` and
     * `options.api`, `ConfigV2.Reference.*` and the MCP remote and OAuth shapes carry a
     * `clientSecret`, and `enterprise.url` is a private endpoint. A key named `api`, `key`, `token`,
     * `secret`, `password` or `credential` in any casing is redacted too, because a future schema
     * revision may add one this build has never seen and the failure mode of missing it is a
     * leaked key rather than a hidden field.
     */
    private val SECRET_KEY_PARTS: Set<String> = setOf(
        "apikey", "api_key", "key", "token", "secret", "password", "passwd", "credential",
        "authorization", "auth", "bearer", "session", "cookie",
    )

    /**
     * Value shapes that are credentials whatever they are called.
     *
     * **Two families, both narrow.** A prefix that a provider issues — `sk-`, `ghp_`, `gho_`,
     * `github_pat_`, `xoxb-`, `AIza`, `AKIA` — and a JWT, which is three base64url segments
     * separated by dots and starts with `ey`. `apiKey: "none"` and `label: "sk-…"` are the two cases
     * the key-name rule and this rule each catch and neither alone catches completely.
     */
    private val SECRET_VALUE_PREFIXES: List<String> = listOf(
        "sk-", "sk_", "ghp_", "gho_", "ghu_", "ghs_", "github_pat_", "xoxb-", "xoxp-", "xapp-",
        "AIza", "AKIA", "ASIA", "ya29.", "glpat-", "npm_", "dop_v1_", "hf_", "r8_",
    )

    private val JWT = Regex("^ey[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}$")

    /** Whether [key] names something that must not be shown. */
    fun isSecretKey(key: String): Boolean {
        val normalized = key.lowercase().replace("-", "_")
        return SECRET_KEY_PARTS.any { part ->
            normalized == part || normalized.endsWith("_$part") || normalized.startsWith("${part}_")
        }
    }

    /** Whether [text] is shaped like a credential whatever it is stored under. */
    fun isSecretValue(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 12) return false
        return JWT.containsMatchIn(trimmed) || SECRET_VALUE_PREFIXES.any { trimmed.startsWith(it) }
    }

    /** Whether a value at [key] may be shown to a person. */
    fun mayReveal(key: String?, value: JsonElement): Boolean = when (value) {
        is JsonPrimitive -> !value.isString || (!isSecretKey(key.orEmpty()) && !isSecretValue(value.content))

        // A container is described by its shape, never by its contents, so an object whose *nested*
        // keys hold secrets cannot leak by being expanded. The rows inside it go through this
        // function again, one key at a time.
        else -> true
    }

    /**
     * How to show the value at [key] in the explorer: never the content, only its shape.
     *
     * **This is the function a screen calls.** It returns a short string with no route, no key and no
     * credential in it, which is what makes a screenshot of the explorer safe to commit.
     */
    fun describe(key: String?, value: JsonElement): String = when (value) {
        is JsonNull -> "null"

        is JsonPrimitive -> when {
            value.isString -> {
                val text = value.content
                if (isSecretKey(key.orEmpty()) || isSecretValue(text)) {
                    "$MASK (a string of ${text.length} characters)"
                } else {
                    text
                }
            }

            value.booleanOrNull != null -> value.content

            value.doubleOrNull != null -> value.content

            else -> "a value"
        }

        is JsonArray -> "a list of ${value.size} ${entry(value.size)}"

        is JsonObject -> "an object with ${value.size} ${entry(value.size)}"
    }

    /**
     * The number of characters in a value, so a row can say "a string of 12 characters" without
     * reading the characters.
     *
     * **The one place the length of a secret is exposed, deliberately.** A user who cannot see their
     * own key needs to be able to tell "the key is missing" from "the key is here", and a length is
     * the narrowest answer that does that — the same reasoning [Secret.toString] uses.
     */
    fun lengthOf(value: JsonElement): Int? = (value as? JsonPrimitive)?.content?.length?.takeIf { value.isString }

    private fun entry(count: Int) = if (count == 1) "entry" else "entries"
}
