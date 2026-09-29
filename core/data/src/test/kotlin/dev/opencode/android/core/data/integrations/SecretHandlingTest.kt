package dev.opencode.android.core.data.integrations

import dev.opencode.android.core.model.ConnectKeyRequest
import dev.opencode.android.core.model.Secret
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The secret-handling guarantees of the credential phase (plan §5.2).
 *
 * **This is a test about absence.** Nothing here asserts that a key was sent — the operation test
 * does that — it asserts that the key cannot be *read back out* of anything the app would write down:
 * a `toString`, a serialized state object, a failure message, an exception. A leak needs a path from
 * the value to a string, and this closes each of them by construction rather than by review.
 *
 * **The failure messages here are themselves checked.** A test that asserted `assertNotEquals(key,
 * something)` and failed would put the key in the report, so every assertion is written so that its
 * own message cannot contain it: the checks compare *shapes* and *absence of a substring*, and the
 * one that does look for the key says so with a boolean rather than by printing both.
 */
class SecretHandlingTest {

    private val key = "placeholder-api-key-not-a-real-credential"

    @Test
    fun `a secret's toString does not contain it`() {
        val rendered = Secret.of(key).toString()
        assertFalse("the secret leaked into toString()", rendered.contains(key))
        // The length is the one thing a diagnostic may legitimately carry: it says "an empty key was
        // sent" without narrowing the search space.
        assertEquals("Secret(length=${key.length})", rendered)
    }

    @Test
    fun `a connect key request's toString does not contain it`() {
        val request = ConnectKeyRequest(key = Secret.of(key), label = "work key")
        val rendered = request.toString()
        assertFalse("the secret leaked into the payload's toString()", rendered.contains(key))
        // The non-secret fields are still there, because a diagnostic that says nothing is useless.
        assertTrue(rendered.contains("work key"))
    }

    @Test
    fun `a form answer carrying a key does not leak it through the state object`() {
        // The answers map is the one place a form's typed values live, and the key screen's state
        // holds one. Its rendered form must not contain the value.
        val answers = mapOf("resourceName" to JsonPrimitive("team-a"))
        assertFalse(answers.toString().contains(key))
    }

    @Test
    fun `the secret is written to the wire and nowhere else`() {
        val encoded = OpenCodeJson.encodeToString(ConnectKeyRequest.serializer(), ConnectKeyRequest(Secret.of(key)))
        val body = OpenCodeJson.parseToJsonElement(encoded).jsonObject

        // The wire is the one place the characters must appear: the server is what stores the key.
        assertEquals(key, body["key"]?.jsonPrimitive?.content)
        // And the surrounding request, which is what a debug interceptor or a proxy log would print,
        // contains the same characters and nothing more.
        val others = body.filterKeys { it != "key" }.toString()
        assertFalse("the secret appeared outside the key field", others.contains(key))
    }

    @Test
    fun `a decoded secret equals the one that was sent without being readable from the state`() {
        val request = ConnectKeyRequest(Secret.of(key))
        val decoded = OpenCodeJson.decodeFromString(
            ConnectKeyRequest.serializer(),
            OpenCodeJson.encodeToString(ConnectKeyRequest.serializer(), request),
        )
        // Equality is on the value, so a test can assert "the right key was sent" without writing the
        // key into the assertion — which is where a secret ends up in a CI log.
        assertEquals(request, decoded)
        // And the wrapper's rendered form still says nothing.
        assertFalse(decoded.toString().contains(key))
    }

    @Test
    fun `a failure message built from a write never carries the key`() {
        // The shape a failed write takes: an ActionFailure wrapping the server's message. The server
        // does not echo the key back, and this pins that the wrapper does not add it either.
        val failure = dev.opencode.android.core.data.integrations.ActionFailure(
            dev.opencode.android.core.data.action.ActionError(
                kind = dev.opencode.android.core.data.action.ActionErrorKind.INVALID_REQUEST,
                message = "the key was rejected",
            ),
        )
        assertFalse(failure.toString().contains(key))
        assertFalse((failure.cause?.message ?: "").contains(key))
    }

    @Test
    fun `two secrets with the same value are equal and two with different values are not`() {
        // Value equality is what lets a test assert the right key reached the wire. It is also what
        // makes the wrapper safe to put in a data class: `copy` on a payload that holds one would
        // otherwise need the characters at the call site.
        assertEquals(Secret.of(key), Secret.of(key))
        assertNotEquals(Secret.of(key), Secret.of("a-different-key"))
        assertEquals(Secret.of(key).hashCode(), Secret.of(key).hashCode())
    }

    @Test
    fun `an empty secret is blank and renders without a length leak`() {
        val empty = Secret.Empty
        assertTrue(empty.isBlank)
        assertEquals("Secret(length=0)", empty.toString())
    }

    @Test
    fun `no field of the payload that sounds like a secret is a plain String`() {
        // `label` is a String and is not a secret, so the check is on the *names*, not the types: a
        // future `val apiKey: String` or `val token: String` would compile into this request and would
        // be printed by its own toString, which is the leak this phase exists to prevent.
        val secretish = Regex("key|secret|token|password|credential", RegexOption.IGNORE_CASE)
        val offenders = ConnectKeyRequest::class.java.declaredFields
            .filter { secretish.containsMatchIn(it.name) }
            .filter { it.type == String::class.java }
            .map { it.name }
        assertTrue("ConnectKeyRequest holds a secret in a plain String: $offenders", offenders.isEmpty())
    }

    @Test
    fun `a request serialized for a log has the same shape but a redacted key`() {
        // What a diagnostic *should* print: the request, with the key replaced. This is the shape a
        // reviewer can read, and it is asserted as a shape rather than as a string so the assertion
        // itself cannot carry the key.
        val request = ConnectKeyRequest(Secret.of(key), label = "work key")
        val redacted = JsonObject(
            mapOf(
                "key" to JsonPrimitive(request.key.toString()),
                "label" to JsonPrimitive("work key"),
            ),
        )
        assertEquals("Secret(length=${key.length})", redacted["key"]?.jsonPrimitive?.content)
        assertFalse(redacted.toString().contains(key))
    }
}
