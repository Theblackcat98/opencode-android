package dev.opencode.android.core.data.forms

import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.FormField.MultiselectField
import dev.opencode.android.core.model.FormField.NumberField
import dev.opencode.android.core.model.FormField.StringField
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.FormOption
import dev.opencode.android.core.model.FormValues
import dev.opencode.android.core.model.FormWhen
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The forms engine (features doc §16).
 *
 * The rules are pure, so they are tested here rather than through a form on screen: conditional
 * visibility and validation are exactly the parts that break silently, and a screenshot cannot show
 * that an answer to a hidden field was wrongly included in the payload.
 */
class FormEngineTest {

    @Test
    fun `defaults come from every field that declares one`() {
        val fields = listOf(
            StringField(key = "a", default = "one"),
            NumberField(key = "b", default = 3.0),
            MultiselectField(key = "c", default = listOf("x", "y")),
            StringField(key = "d"),
        )
        assertEquals(
            mapOf(
                "a" to JsonPrimitive("one"),
                "b" to JsonPrimitive(3),
                "c" to FormValues.strings(listOf("x", "y")),
            ),
            FormEngine.defaults(fields),
        )
    }

    @Test
    fun `a hidden field never shows`() {
        val field = StringField(key = "a", hidden = true)
        assertFalse(FormEngine.isVisible(field, emptyMap()))
    }

    @Test
    fun `a when condition compares against the current answer`() {
        val field = StringField(
            key = "b",
            conditions = listOf(FormWhen("a", FormWhen.WhenOp.EQ, JsonPrimitive("yes"))),
        )
        assertTrue(FormEngine.isVisible(field, mapOf("a" to JsonPrimitive("yes"))))
        assertFalse(FormEngine.isVisible(field, mapOf("a" to JsonPrimitive("no"))))
        assertFalse(FormEngine.isVisible(field, emptyMap()))
    }

    @Test
    fun `a neq condition inverts the comparison`() {
        val field = StringField(
            key = "b",
            conditions = listOf(FormWhen("a", FormWhen.WhenOp.NEQ, JsonPrimitive("no"))),
        )
        assertTrue(FormEngine.isVisible(field, mapOf("a" to JsonPrimitive("yes"))))
        assertFalse(FormEngine.isVisible(field, mapOf("a" to JsonPrimitive("no"))))
    }

    @Test
    fun `every condition of a field has to hold`() {
        val field = StringField(
            key = "c",
            conditions = listOf(
                FormWhen("a", FormWhen.WhenOp.EQ, JsonPrimitive("yes")),
                FormWhen("b", FormWhen.WhenOp.EQ, JsonPrimitive("2")),
            ),
        )
        assertFalse(FormEngine.isVisible(field, mapOf("a" to JsonPrimitive("yes"))))
        assertTrue(FormEngine.isVisible(field, mapOf("a" to JsonPrimitive("yes"), "b" to JsonPrimitive("2"))))
    }

    @Test
    fun `a boolean condition compares as a boolean, not as text`() {
        val field = StringField(
            key = "b",
            conditions = listOf(FormWhen("agree", FormWhen.WhenOp.EQ, JsonPrimitive(true))),
        )
        assertTrue(FormEngine.isVisible(field, mapOf("agree" to JsonPrimitive(true))))
        assertFalse(FormEngine.isVisible(field, mapOf("agree" to JsonPrimitive("true"))))
    }

    @Test
    fun `a condition on a multiselect answer means membership`() {
        val field = StringField(
            key = "detail",
            conditions = listOf(
                FormWhen("langs", FormWhen.WhenOp.EQ, JsonPrimitive("kotlin")),
            ),
        )
        assertTrue(
            "selecting kotlin should reveal the follow-up",
            FormEngine.isVisible(field, mapOf("langs" to FormValues.strings(listOf("java", "kotlin")))),
        )
        assertFalse(
            FormEngine.isVisible(field, mapOf("langs" to FormValues.strings(listOf("java")))),
        )
    }

    @Test
    fun `a required field with no answer is the only required problem`() {
        val required = StringField(key = "a", required = true)
        val optional = StringField(key = "b")
        assertEquals(FieldProblem.REQUIRED, FormEngine.problemOf(required, null))
        assertNull(FormEngine.problemOf(optional, null))
    }

    @Test
    fun `string length, pattern and option checks run in that order`() {
        val field = StringField(
            key = "a",
            required = true,
            minLength = 3,
            maxLength = 5,
            pattern = "^[a-z]+$",
            options = listOf(FormOption(value = "bash", label = "bash")),
        )
        assertEquals(FieldProblem.REQUIRED, FormEngine.problemOf(field, JsonPrimitive("")))
        assertEquals(FieldProblem.TOO_SHORT, FormEngine.problemOf(field, JsonPrimitive("ab")))
        assertEquals(FieldProblem.TOO_LONG, FormEngine.problemOf(field, JsonPrimitive("abcdef")))
        assertEquals(FieldProblem.PATTERN, FormEngine.problemOf(field, JsonPrimitive("a1c")))
        assertEquals(FieldProblem.NOT_AN_OPTION, FormEngine.problemOf(field, JsonPrimitive("zsh")))
        assertNull(FormEngine.problemOf(field, JsonPrimitive("bash")))
    }

    @Test
    fun `a custom string field accepts an answer outside its options`() {
        val field = StringField(
            key = "a",
            options = listOf(FormOption(value = "bash", label = "bash")),
            custom = true,
        )
        assertNull(FormEngine.problemOf(field, JsonPrimitive("zsh")))
    }

    @Test
    fun `a pattern this client cannot compile is skipped rather than failing the answer`() {
        val field = StringField(key = "a", pattern = "([unclosed")
        assertNull(FormEngine.problemOf(field, JsonPrimitive("anything")))
    }

    @Test
    fun `numeric bounds and integrality are checked`() {
        val integer = NumberField(key = "n", minimum = 1.0, maximum = 10.0, integer = true)
        assertEquals(FieldProblem.BELOW_MINIMUM, FormEngine.problemOf(integer, JsonPrimitive(0.0)))
        assertEquals(FieldProblem.ABOVE_MAXIMUM, FormEngine.problemOf(integer, JsonPrimitive(11.0)))
        assertEquals(FieldProblem.NOT_AN_INTEGER, FormEngine.problemOf(integer, JsonPrimitive(2.5)))
        assertNull(FormEngine.problemOf(integer, JsonPrimitive(2.0)))
    }

    @Test
    fun `a non-finite bound does not reject a finite answer`() {
        // The schema allows "Infinity" as a bound, and the server sends it as a string.
        val field = NumberField(key = "n", maximum = Double.POSITIVE_INFINITY)
        assertNull(FormEngine.problemOf(field, JsonPrimitive(1.0e300)))
        val negative = NumberField(key = "n", minimum = Double.NEGATIVE_INFINITY)
        assertNull(FormEngine.problemOf(negative, JsonPrimitive(-1.0e300)))
    }

    @Test
    fun `a numeric answer sent as a non-finite string still reads as a number`() {
        val field = NumberField(key = "n", maximum = 10.0)
        assertEquals(FieldProblem.ABOVE_MAXIMUM, FormEngine.problemOf(field, JsonPrimitive("Infinity")))
        assertNull(FormEngine.problemOf(field, JsonPrimitive("5")))
    }

    @Test
    fun `multiselect item counts and unknown values are checked`() {
        val field = MultiselectField(
            key = "m",
            required = true,
            minItems = 1,
            maxItems = 2,
            options = listOf(FormOption(value = "a", label = "a"), FormOption(value = "b", label = "b")),
        )
        assertEquals(FieldProblem.REQUIRED, FormEngine.problemOf(field, FormValues.strings(emptyList())))
        assertEquals(FieldProblem.TOO_MANY, FormEngine.problemOf(field, FormValues.strings(listOf("a", "b", "a"))))
        assertEquals(
            FieldProblem.NOT_AN_OPTION,
            FormEngine.problemOf(field, FormValues.strings(listOf("a", "zzz"))),
        )
        assertNull(FormEngine.problemOf(field, FormValues.strings(listOf("a"))))
    }

    @Test
    fun `the reply carries only the visible fields that have an answer`() {
        val fields = listOf(
            StringField(key = "always", default = "kept"),
            StringField(key = "sometimes"),
            StringField(key = "secret", hidden = true),
        )
        val answer = FormEngine.toAnswer(
            fields,
            mapOf(
                "always" to JsonPrimitive("kept"),
                "secret" to JsonPrimitive("leaked"),
            ),
        )
        assertEquals("a hidden field's answer must not be sent", setOf("always"), answer.keys)
    }

    @Test
    fun `a field that stops being visible drops out of the reply`() {
        val fields = listOf(
            StringField(key = "a"),
            StringField(
                key = "b",
                conditions = listOf(FormWhen("a", FormWhen.WhenOp.EQ, JsonPrimitive("yes"))),
            ),
        )
        val shown = FormEngine.toAnswer(fields, mapOf("a" to JsonPrimitive("yes"), "b" to JsonPrimitive("detail")))
        assertEquals(setOf("a", "b"), shown.keys)

        val hidden = FormEngine.toAnswer(fields, mapOf("a" to JsonPrimitive("no"), "b" to JsonPrimitive("detail")))
        assertEquals("answering 'no' must un-ask the follow-up", setOf("a"), hidden.keys)
    }

    @Test
    fun `an empty answer is not sent, because Form Value has no null`() {
        val fields = listOf(StringField(key = "a"), StringField(key = "b"))
        val answer = FormEngine.toAnswer(fields, mapOf("a" to JsonPrimitive(""), "b" to JsonPrimitive("x")))
        assertEquals(setOf("b"), answer.keys)
    }

    @Test
    fun `canSubmit requires every visible field to be acceptable`() {
        val fields = listOf(StringField(key = "a", required = true), StringField(key = "b"))
        assertFalse(FormEngine.canSubmit(fields, emptyMap()))
        assertTrue(FormEngine.canSubmit(fields, mapOf("a" to JsonPrimitive("x"))))
    }

    @Test
    fun `fieldStates report the default when nothing was entered`() {
        val states = FormEngine.fieldStates(
            listOf(StringField(key = "a", default = "d", required = true)),
            emptyMap(),
        )
        assertEquals(JsonPrimitive("d"), states.single().value)
        assertNull("a default satisfies a required field", states.single().problem)
    }

    @Test
    fun `a question form decodes and answers through the engine end to end`() {
        // The exact payload a 2.0.18 server sends for the `question` tool, recorded live.
        val raw = """
        {
          "id": "frm_probe",
          "sessionID": "ses_probe",
          "title": "Questions",
          "metadata": {"kind": "question", "tool": {"messageID": "msg_a", "id": "call_question_1"}},
          "fields": [
            {
              "key": "q0",
              "title": "Shell",
              "description": "Which fake shell should run?",
              "type": "string",
              "options": [
                {"value": "bash", "label": "bash", "description": "Run bash"},
                {"value": "sh", "label": "sh", "description": "Run sh"}
              ],
              "custom": true
            }
          ]
        }
        """.trimIndent()
        val form = OpenCodeJson.decodeFromString<FormInfo>(raw)
        val field = form.fields.single() as StringField
        assertEquals("q0", field.key)
        assertEquals(2, field.options.size)
        assertTrue("custom lets the user type a shell that is not listed", field.custom)
        assertEquals("question", form.metadata?.get("kind")?.let { (it as JsonPrimitive).content })

        val answer = FormEngine.toAnswer(form.fields, mapOf("q0" to JsonPrimitive("bash")))
        assertEquals(mapOf("q0" to JsonPrimitive("bash")), answer)
    }
}
