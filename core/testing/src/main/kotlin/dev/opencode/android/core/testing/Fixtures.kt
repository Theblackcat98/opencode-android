package dev.opencode.android.core.testing

import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Loads test fixtures recorded from live OpenCode servers (src/main/resources/fixtures).
 */
object Fixtures {
    fun stream(path: String): InputStream {
        val normalized = if (path.startsWith("/")) path.substring(1) else path
        val resourcePath = if (normalized.startsWith("fixtures/")) normalized else "fixtures/$normalized"
        return Fixtures::class.java.classLoader?.getResourceAsStream(resourcePath)
            ?: error("Fixture not found: $resourcePath")
    }

    fun raw(path: String): String = stream(path).bufferedReader().use { it.readText() }

    fun json(path: String): JsonElement = Json.parseToJsonElement(raw(path))

    fun events(): List<String> =
        raw("events.jsonl").lines().filter { it.isNotBlank() }

    val allScenarios = listOf(
        "text",
        "reasoning",
        "shell",
        "edit",
        "question",
        "subagent",
        "error",
        "long",
    )
}
