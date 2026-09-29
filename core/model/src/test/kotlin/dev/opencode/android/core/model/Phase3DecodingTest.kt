package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 3 request and response shapes.
 *
 * The payloads here were recorded against a live 2.0.18 server started by `scripts/dev-server.sh`,
 * which is the only way to be sure about a field the OpenAPI spec marks optional: a fixture that
 * came from a documentation example would happily be missing the field that actually decides
 * whether a reply is accepted.
 */
class Phase3DecodingTest {

    @Test
    fun `a permission request decodes with the patterns an always reply would store`() {
        val raw = """
        {
          "id": "per_0e79b073a0013JOnEsakG7mHiW",
          "sessionID": "ses_probe",
          "action": "shell",
          "resources": ["echo fake-provider-hello"],
          "save": ["echo *"],
          "source": {"type": "tool", "messageID": "msg_probe", "id": "call_shell_1"}
        }
        """.trimIndent()
        val request = OpenCodeJson.decodeFromString<PermissionRequest>(raw)
        assertEquals("shell", request.action)
        assertEquals(listOf("echo fake-provider-hello"), request.resources)
        assertEquals(listOf("echo *"), request.savedPatterns)
        assertEquals("call_shell_1", request.source?.id)
        assertEquals(PermissionSource.TOOL, request.source?.type)
    }

    @Test
    fun `a permission request without save or source decodes to empty defaults`() {
        val raw = """{"id":"per_a","sessionID":"ses_b","action":"read","resources":["a.txt"]}"""
        val request = OpenCodeJson.decodeFromString<PermissionRequest>(raw)
        assertNull(request.save)
        assertTrue(request.savedPatterns.isEmpty())
        assertNull(request.source)
    }

    @Test
    fun `a form decodes every field type the schema defines`() {
        val raw = FORM_WITH_EVERY_FIELD
        val form = OpenCodeJson.decodeFromString<FormInfo>(raw)
        assertEquals(6, form.fields.size)
        assertEquals(FormKind.MCP_ELICITATION, form.kind)

        val byKey = form.fields.associateBy { it.key }
        val string = byKey.getValue("name") as FormField.StringField
        assertEquals("Your name", string.title)
        assertEquals("text", string.format)
        assertEquals(2, string.minLength)
        assertTrue(string.custom)

        val number = byKey.getValue("age") as FormField.NumberField
        assertFalse("an age is an integer", number.integer)
        assertEquals(0.0, number.minimum!!, 0.0)

        val integer = byKey.getValue("count") as FormField.NumberField
        assertTrue("a count is an integer", integer.integer)
        assertEquals(10.0, integer.maximum!!, 0.0)

        val boolean = byKey.getValue("agree") as FormField.BooleanField
        assertTrue(boolean.required)

        val multiselect = byKey.getValue("langs") as FormField.MultiselectField
        assertEquals(1, multiselect.minItems)
        assertEquals(listOf("kotlin"), multiselect.default)

        val external = byKey.getValue("docs") as FormField.ExternalField
        assertEquals("https://example.invalid/setup", external.url)
    }

    @Test
    fun `an integer field survives a non-finite bound, which the server sends as a string`() {
        val raw = """
        {"id":"frm_a","sessionID":"ses_a","title":"t","fields":[
          {"key":"n","type":"integer","minimum":"-Infinity","maximum":"Infinity"}
        ]}
        """.trimIndent()
        val field = OpenCodeJson.decodeFromString<FormInfo>(raw).fields.single() as FormField.NumberField
        assertEquals(Double.NEGATIVE_INFINITY, field.minimum!!, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, field.maximum!!, 0.0)
    }

    @Test
    fun `a field type this client does not know keeps its key and its raw JSON`() {
        val raw = """
        {"id":"frm_a","sessionID":"ses_a","title":"t","fields":[
          {"key":"weird","type":"hologram","hint":42}
        ]}
        """.trimIndent()
        val field = OpenCodeJson.decodeFromString<FormInfo>(raw).fields.single()
        val unknown = field as FormField.Unknown
        assertEquals("weird", unknown.key)
        assertEquals("hologram", unknown.discriminator)
        assertEquals(42, (unknown.raw["hint"] as JsonPrimitive).content.toInt())
        assertEquals("weird", unknown.labelOrKey())
    }

    @Test
    fun `a field with no title is labelled by its key, because that is how it is addressed`() {
        val raw = """
        {"id":"frm_a","sessionID":"ses_a","title":"t","fields":[{"key":"q0","type":"string"}]}
        """.trimIndent()
        assertEquals("q0", OpenCodeJson.decodeFromString<FormInfo>(raw).fields.single().labelOrKey())
    }

    @Test
    fun `form state decodes for all three statuses`() {
        val pending = """{"id":"f","sessionID":"s","title":"t","fields":[],"state":{"status":"pending"}}"""
        val answered = """
        {"id":"f","sessionID":"s","title":"t","fields":[],"state":{"status":"answered","answer":{"q0":"bash"}}}
        """.trimIndent()
        val cancelled = """{"id":"f","sessionID":"s","title":"t","fields":[],"state":{"status":"cancelled"}}"""
        assertEquals(FormState.Pending, OpenCodeJson.decodeFromString<FormDetail>(pending).state)
        val state = OpenCodeJson.decodeFromString<FormDetail>(answered).state
        assertEquals(JsonPrimitive("bash"), (state as FormState.Answered).answer["q0"])
        assertEquals(FormState.Cancelled, OpenCodeJson.decodeFromString<FormDetail>(cancelled).state)
    }

    @Test
    fun `a state this client does not know keeps its raw JSON`() {
        val raw = """{"id":"f","sessionID":"s","title":"t","fields":[],"state":{"status":"later"}}"""
        val state = OpenCodeJson.decodeFromString<FormDetail>(raw).state as FormState.Unknown
        assertEquals("later", state.discriminator)
    }

    @Test
    fun `presentation kind comes from metadata, and an unknown kind is generic`() {
        assertEquals(FormKind.QUESTION, kindOf("question"))
        assertEquals(FormKind.WEBSEARCH_CONSENT, kindOf("websearch.provider"))
        assertEquals(FormKind.MCP_ELICITATION, kindOf("mcp-elicitation"))
        assertEquals(FormKind.GENERIC, kindOf("something.new"))
        assertEquals(FormKind.GENERIC, kindOf(null))
    }

    @Test
    fun `the mcp and question metadata the UI reads is typed`() {
        val elicitation = OpenCodeJson.decodeFromString<FormInfo>(FORM_WITH_EVERY_FIELD)
        assertEquals("docs-server", elicitation.mcpServer)
        assertEquals("We need more input", elicitation.mcpMessage)

        val question = OpenCodeJson.decodeFromString<FormInfo>(
            """
            {"id":"frm_q","sessionID":"ses_q","title":"Questions",
             "metadata":{"kind":"question","tool":{"messageID":"msg_a","id":"call_question_1"}},
             "fields":[]}
            """.trimIndent(),
        )
        assertEquals("call_question_1", question.questionTool?.id)
        assertEquals("msg_a", question.questionTool?.messageID)
    }

    @Test
    fun `a filesystem entry decodes both kinds and names itself`() {
        val raw =
            """{"location":{"directory":"/tmp"},"data":[""" +
                """{"path":"src/","type":"directory"},{"path":"README.md","type":"file"}]}"""
        val listing = OpenCodeJson.decodeFromString<LocationScoped<List<FileSystemEntry>>>(raw)
        assertEquals(2, listing.data.size)
        assertEquals("src", listing.data[0].name)
        assertTrue(listing.data[0].isDirectory)
        assertEquals("README.md", listing.data[1].name)
        assertFalse(listing.data[1].isDirectory)
    }

    @Test
    fun `a session create body omits what the user did not choose`() {
        val minimal = OpenCodeJson.encodeToString(
            SessionCreateRequest(location = LocationPublicRef("/work")),
        )
        assertEquals("""{"location":{"directory":"/work"}}""", minimal)

        val full = OpenCodeJson.encodeToString(
            SessionCreateRequest(
                id = "ses_client",
                title = "Name",
                agent = "build",
                model = ModelRef(id = "text", providerID = "fake", variant = "high"),
                location = LocationPublicRef("/work"),
            ),
        )
        val decoded = OpenCodeJson.decodeFromString<JsonObject>(full)
        assertEquals("ses_client", (decoded["id"] as JsonPrimitive).content)
        assertEquals("high", (decoded["model"] as JsonObject).let { (it["variant"] as JsonPrimitive).content })
    }

    @Test
    fun `a prompt body carries the client id, the delivery and the resume flag`() {
        val json = OpenCodeJson.encodeToString(
            PromptRequest(
                id = "msg_client",
                text = "hello",
                delivery = Delivery.Queue,
                resume = false,
            ),
        )
        val decoded = OpenCodeJson.decodeFromString<JsonObject>(json)
        assertEquals("msg_client", (decoded["id"] as JsonPrimitive).content)
        assertEquals("queue", (decoded["delivery"] as JsonPrimitive).content)
        assertEquals(false, (decoded["resume"] as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `a permission reply body sends the decision and optional feedback`() {
        val withFeedback = OpenCodeJson.encodeToString(
            PermissionReplyPayload(PermissionReply.Reject, "not that path"),
        )
        val decoded = OpenCodeJson.decodeFromString<JsonObject>(withFeedback)
        assertEquals("reject", (decoded["decision"] as JsonPrimitive).content)
        assertEquals("not that path", (decoded["message"] as JsonPrimitive).content)

        val once = OpenCodeJson.encodeToString(PermissionReplyPayload(PermissionReply.Once))
        assertEquals("""{"decision":"once"}""", once)
    }

    @Test
    fun `a form reply body is the answer map and nothing else`() {
        val json = OpenCodeJson.encodeToString(
            FormReplyPayload(mapOf("q0" to JsonPrimitive("bash"), "more" to JsonArray(listOf(JsonPrimitive("x"))))),
        )
        val answer = (OpenCodeJson.decodeFromString<JsonObject>(json)["answer"] as JsonObject)
        assertEquals("bash", (answer["q0"] as JsonPrimitive).content)
        assertEquals(1, (answer["more"] as JsonArray).size)
    }

    @Test
    fun `a non-finite form value is written as the string the server reads`() {
        assertEquals(""""Infinity"""", FormValues.number(Double.POSITIVE_INFINITY).toString())
        assertEquals(""""-Infinity"""", FormValues.number(Double.NEGATIVE_INFINITY).toString())
        assertEquals(""""NaN"""", FormValues.number(Double.NaN).toString())
        assertEquals("2", FormValues.number(2.0).toString())
    }

    @Test
    fun `an interrupt result decodes, and a body without the field means not interrupted`() {
        assertTrue(OpenCodeJson.decodeFromString<InterruptResult>("""{"interrupted":true}""").interrupted)
        assertFalse(OpenCodeJson.decodeFromString<InterruptResult>("""{}""").interrupted)
    }

    private fun kindOf(kind: String?): FormKind = FormKind.of(kind?.let { mapOf("kind" to JsonPrimitive(it)) })

    private companion object {
        /**
         * One form carrying every field type, in the shape a 2.0.18 server sends. The
         * `mcp-elicitation` kind is used so that the metadata fields are covered too.
         */
        val FORM_WITH_EVERY_FIELD = """
        {
          "id": "frm_probe",
          "sessionID": "ses_probe",
          "title": "MCP input",
          "metadata": {"kind": "mcp-elicitation", "server": "docs-server", "message": "We need more input"},
          "fields": [
            {"key":"name","title":"Your name","type":"string","format":"text","minLength":2,
             "options":[{"value":"a","label":"A"}],"custom":true},
            {"key":"age","type":"number","minimum":0},
            {"key":"count","type":"integer","maximum":10},
            {"key":"agree","type":"boolean","required":true,"default":false},
            {"key":"langs","type":"multiselect","options":[{"value":"kotlin","label":"Kotlin"}],
             "minItems":1,"default":["kotlin"]},
            {"key":"docs","type":"external","url":"https://example.invalid/setup"}
          ]
        }
        """.trimIndent()
    }
}
