package dev.opencode.android.core.data.forms

import dev.opencode.android.core.model.FormAnswer
import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.FormValues
import dev.opencode.android.core.model.FormWhen
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Why one field's answer is not acceptable (plan §6, Forms engine).
 *
 * These are the checks the schema declares — required, pattern, min/max length, min/max value — plus
 * the two a renderer needs anyway: a value outside a fixed option list, and a fraction in an
 * integer field. They are values rather than strings because the UI owns the wording, and because a
 * test asserts on the class, not on English.
 */
enum class FieldProblem {
    REQUIRED,
    PATTERN,
    TOO_SHORT,
    TOO_LONG,
    BELOW_MINIMUM,
    ABOVE_MAXIMUM,
    NOT_AN_INTEGER,
    NOT_AN_OPTION,
    TOO_FEW,
    TOO_MANY,
}

/** One field with everything a renderer needs, computed off the main thread. */
data class FormFieldState(
    val field: FormField,
    /** The current answer, or the field's default when the user has not entered one. */
    val value: JsonElement?,
    val visible: Boolean,
    val problem: FieldProblem?,
) {
    val key: String get() = this.field.key
    val isValid: Boolean get() = problem == null
}

/**
 * The forms engine: visibility, validation and answer building for a form (features doc §16).
 *
 * **Forms are one mechanism, so this is one implementation.** The `question` tool, web-search
 * consent, MCP elicitation and an integration login differ only in which fields they carry and how
 * the app presents them, so every one of them is rendered and validated here and Phase 4's
 * notification actions reuse the same answers.
 *
 * **Pure, and therefore testable without a form on screen.** It reads the field list and the current
 * answers and returns the fields to show, their problems and the payload to send. The alternative —
 * validating inside the composable — makes the rules untestable, which is how a `when` condition
 * quietly stops working.
 *
 * **Visibility is derived, not stored.** A field shows when it is not `hidden` and every one of its
 * `when` conditions holds against the *current* answers, so answering a question can reveal a
 * follow-up. An answer to a field that is not visible is kept in the state but left out of the
 * reply, because the server validates against the fields it sent.
 */
object FormEngine {

    /** The answers a form starts with: every field's declared default, if it has one. */
    fun defaults(fields: List<FormField>): FormAnswer = buildMap {
        fields.forEach { field ->
            when (field) {
                is FormField.StringField -> field.default?.let { put(field.key, FormValues.string(it)) }
                is FormField.NumberField -> field.default?.let { put(field.key, FormValues.number(it)) }
                is FormField.BooleanField -> field.default?.let { put(field.key, FormValues.boolean(it)) }
                is FormField.MultiselectField -> field.default
                    .takeIf { it.isNotEmpty() }
                    ?.let { put(field.key, FormValues.strings(it)) }

                is FormField.ExternalField, is FormField.Unknown -> Unit
            }
        }
    }

    /**
     * Every field with its current value, whether it is visible, and what is wrong with it.
     *
     * The full list is returned, not just the visible fields, so a renderer can keep the state of a
     * field the user has just hidden and a test can assert on a hidden field's problem.
     */
    fun fieldStates(fields: List<FormField>, answers: FormAnswer): List<FormFieldState> = fields.map { field ->
        val value = answers[field.key]
        val visible = isVisible(field, answers)
        FormFieldState(
            field = field,
            value = value ?: defaultOf(field),
            visible = visible,
            problem = if (visible) problemOf(field, value ?: defaultOf(field)) else null,
        )
    }

    /** The fields a form shows right now, in declaration order. */
    fun visibleFields(fields: List<FormField>, answers: FormAnswer): List<FormField> =
        fields.filter { isVisible(it, answers) }

    /**
     * True when [field] should be shown.
     *
     * `hidden` wins over everything, and every `when` condition has to hold. A condition compares
     * against the *current* answer of the key it names; for a multiselect answer `eq` means the
     * value is one of the selected options, because a condition on "which options are ticked" that
     * compared whole arrays would never fire.
     */
    fun isVisible(field: FormField, answers: FormAnswer): Boolean {
        if (field.hidden) return false
        return field.conditions.all { condition -> holds(condition, answers) }
    }

    /**
     * What is wrong with one field's answer, or `null` when it is acceptable.
     *
     * A required field with no answer is [FieldProblem.REQUIRED]; a field that is not required and
     * has no answer is always acceptable, which is what lets an optional question be skipped.
     */
    fun problemOf(field: FormField, value: JsonElement?): FieldProblem? = when (field) {
        is FormField.StringField -> stringProblem(field, value)
        is FormField.NumberField -> numberProblem(field, value)
        is FormField.BooleanField -> if (field.required && value == null) {
            FieldProblem.REQUIRED
        } else {
            null
        }

        is FormField.MultiselectField -> multiselectProblem(field, value)
        // An external field is a link, not an answer, and the unknown one has no rules.
        is FormField.ExternalField, is FormField.Unknown -> null
    }

    /** The problems of every field that has one, keyed by field key. */
    fun validate(fields: List<FormField>, answers: FormAnswer): Map<String, FieldProblem> = buildMap {
        fields.forEach { field ->
            if (!isVisible(field, answers)) return@forEach
            problemOf(field, answers[field.key] ?: defaultOf(field))?.let { put(field.key, it) }
        }
    }

    /** True when every visible field's answer is acceptable, which is what enables Reply. */
    fun canSubmit(fields: List<FormField>, answers: FormAnswer): Boolean =
        validate(fields, answers).isEmpty()

    /**
     * The payload of a reply: one entry per visible field that has an answer.
     *
     * Only the fields the server sent and that are currently visible are included, and an
     * unanswered optional field is left out rather than sent as a null: a null is not a value in
     * `Form.Value`, so sending one would be rejected as an invalid answer.
     */
    fun toAnswer(fields: List<FormField>, answers: FormAnswer): FormAnswer = buildMap {
        fields.forEach { field ->
            if (!isVisible(field, answers)) return@forEach
            val value = answers[field.key] ?: defaultOf(field) ?: return@forEach
            if (isEmpty(value)) return@forEach
            put(field.key, value)
        }
    }

    /** The states a form starts in, which is what a renderer builds its `remember`ed state from. */
    fun initialState(form: FormInfo): Map<String, JsonElement> = defaults(form.fields)

    // ------------------------------------------------------------------ internals

    private fun holds(condition: FormWhen, answers: FormAnswer): Boolean {
        val answer = answers[condition.key]
        val equal = when (answer) {
            is JsonArray -> answer.any { it == condition.value || it.contentOrNull() == condition.value.contentOrNull() }
            else -> answer == condition.value || answer?.contentOrNull() == condition.value.contentOrNull()
        }
        return if (condition.op == FormWhen.WhenOp.NEQ) !equal else equal
    }

    private fun stringProblem(field: FormField.StringField, value: JsonElement?): FieldProblem? {
        val text = value?.contentOrNull()
        val blank = text.isNullOrEmpty()
        if (blank) return if (field.required) FieldProblem.REQUIRED else null
        val minLength = field.minLength
        val maxLength = field.maxLength
        if (minLength != null && text.length < minLength) return FieldProblem.TOO_SHORT
        if (maxLength != null && text.length > maxLength) return FieldProblem.TOO_LONG
        field.pattern?.let { pattern ->
            // A pattern the device's regex engine cannot compile is a rule this client cannot
            // enforce, so it is skipped rather than turned into a permanent error. The server
            // still validates, and an invalid answer comes back as FormInvalidAnswer.
            val compiled = runCatching { Regex(pattern) }.getOrNull() ?: return@let
            if (!compiled.matches(text)) return FieldProblem.PATTERN
        }
        if (field.options.isNotEmpty() && !field.custom && field.options.none { it.value == text }) {
            return FieldProblem.NOT_AN_OPTION
        }
        return null
    }

    private fun numberProblem(field: FormField.NumberField, value: JsonElement?): FieldProblem? {
        if (value == null || value is JsonNull) return if (field.required) FieldProblem.REQUIRED else null
        val number = value.numberOrNull() ?: return FieldProblem.REQUIRED
        if (field.integer && number % 1.0 != 0.0 && !number.isNaN() && !number.isInfinite()) {
            return FieldProblem.NOT_AN_INTEGER
        }
        val minimum = field.minimum
        val maximum = field.maximum
        if (minimum != null && number < minimum) return FieldProblem.BELOW_MINIMUM
        if (maximum != null && number > maximum) return FieldProblem.ABOVE_MAXIMUM
        return null
    }

    private fun multiselectProblem(field: FormField.MultiselectField, value: JsonElement?): FieldProblem? {
        val selected = (value as? JsonArray)?.mapNotNull { it.contentOrNull() }.orEmpty()
        if (selected.isEmpty()) return if (field.required) FieldProblem.REQUIRED else null
        val minItems = field.minItems
        val maxItems = field.maxItems
        if (minItems != null && selected.size < minItems) return FieldProblem.TOO_FEW
        if (maxItems != null && selected.size > maxItems) return FieldProblem.TOO_MANY
        val known = field.options.map { it.value }.toSet()
        if (!field.custom && selected.any { it !in known }) return FieldProblem.NOT_AN_OPTION
        return null
    }

    private fun defaultOf(field: FormField): JsonElement? = defaults(listOf(field))[field.key]

    private fun isEmpty(value: JsonElement): Boolean = when (value) {
        is JsonNull -> true
        is JsonPrimitive -> value.isString && value.content.isEmpty()
        is JsonArray -> value.isEmpty()
        else -> false
    }

    private fun JsonElement?.contentOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.numberOrNull(): Double? = when (this) {
        is JsonPrimitive -> if (isString) {
            when (content) {
                "Infinity" -> Double.POSITIVE_INFINITY
                "-Infinity" -> Double.NEGATIVE_INFINITY
                "NaN" -> Double.NaN
                else -> content.toDoubleOrNull()
            }
        } else {
            content.toDoubleOrNull()
        }

        else -> null
    }
}
