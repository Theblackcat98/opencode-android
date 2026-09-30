package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.OpenCodeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who raised a permission request, as the payload says it.
 *
 * A tool's request carries `source = {type: "tool", messageID, id}`; one created through
 * `POST /api/session/{id}/permission` carries none and may carry `metadata.title`. The screens label a request
 * from these two answers, so they are read from payloads in the shape the server sends rather than from
 * hand-built objects.
 */
class PermissionRequestOriginTest {

    @Test
    fun `a request with a tool source was raised by a tool call`() {
        val request = decode(
            """
            {
              "id": "per_tool", "sessionID": "ses_1", "action": "shell", "resources": ["ls"],
              "source": {"type": "tool", "messageID": "msg_1", "id": "call_1"}
            }
            """.trimIndent(),
        )

        assertTrue(request.raisedByToolCall)
        assertNull(request.title)
    }

    @Test
    fun `a request with no source was not raised by a tool call, and its metadata title is its title`() {
        val request = decode(
            """
            {
              "id": "per_plugin", "sessionID": "ses_1", "action": "external_directory", "resources": ["/etc/hosts"],
              "metadata": {"title": "Read the hosts file", "other": 3}
            }
            """.trimIndent(),
        )

        assertFalse(request.raisedByToolCall)
        assertEquals("Read the hosts file", request.title)
    }

    @Test
    fun `a request with no source and no metadata is still not a tool's`() {
        val request = decode("""{"id":"per_bare","sessionID":"ses_1","action":"read","resources":["a.txt"]}""")

        assertFalse(request.raisedByToolCall)
        assertNull(request.title)
    }

    @Test
    fun `a title that is blank or is not a string is no title`() {
        val notTitles = listOf("""{"title": ""}""", """{"title": "   "}""", """{"title": 7}""", """{"title": {"a": 1}}""")
        for (metadata in notTitles) {
            val request = decode(
                """{"id":"per_x","sessionID":"ses_1","action":"read","resources":[],"metadata":$metadata}""",
            )
            assertNull("metadata $metadata", request.title)
        }
    }

    @Test
    fun `a tool's request keeps a title it was given`() {
        val request = decode(
            """
            {
              "id": "per_t", "sessionID": "ses_1", "action": "edit", "resources": ["a.kt"],
              "metadata": {"title": "Edit a.kt"},
              "source": {"type": "tool", "messageID": "msg_1", "id": "call_1"}
            }
            """.trimIndent(),
        )

        assertTrue(request.raisedByToolCall)
        assertEquals("Edit a.kt", request.title)
    }

    private fun decode(raw: String): PermissionRequest = OpenCodeJson.decodeFromString(raw)
}
