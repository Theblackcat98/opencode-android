package dev.opencode.android.core.data.attention

import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.FormOption
import dev.opencode.android.core.model.FormValues
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Flattens a form's fields so a notification can carry them.
 *
 * **A `RemoteInput` answer is validated by [dev.opencode.android.core.data.forms.FormEngine], not by
 * the notification.** The shade has one text box and no way to show which field failed, so the
 * encoder keeps enough of each field for the engine to reproduce every rule it would apply on
 * screen: required, the option list, `custom`, the length bounds, the pattern, the item counts and
 * the numeric range. A field type this build does not know is encoded with its declared type and
 * loses its rules, which is the honest outcome — the engine has no rules for it either — rather than
 * a guess that would reject a valid answer.
 */
fun encodeField(field: FormField): EncodedFormField = when (field) {
    is FormField.StringField -> EncodedFormField(
        key = field.key,
        type = TYPE_STRING,
        title = field.title,
        required = field.required,
        hidden = field.hidden,
        options = field.options.map(FormOption::value),
        custom = field.custom,
        minLength = field.minLength,
        maxLength = field.maxLength,
        pattern = field.pattern,
        minItems = null,
        maxItems = null,
        minimum = null,
        maximum = null,
        default = field.default?.let(FormValues::string),
    )

    is FormField.NumberField -> EncodedFormField(
        key = field.key,
        type = if (field.integer) TYPE_INTEGER else TYPE_NUMBER,
        title = field.title,
        required = field.required,
        hidden = field.hidden,
        options = emptyList(),
        custom = false,
        minLength = null,
        maxLength = null,
        pattern = null,
        minItems = null,
        maxItems = null,
        minimum = field.minimum,
        maximum = field.maximum,
        default = field.default?.let(FormValues::number),
    )

    is FormField.BooleanField -> EncodedFormField(
        key = field.key,
        type = TYPE_BOOLEAN,
        title = field.title,
        required = field.required,
        hidden = field.hidden,
        options = emptyList(),
        custom = false,
        minLength = null,
        maxLength = null,
        pattern = null,
        minItems = null,
        maxItems = null,
        minimum = null,
        maximum = null,
        default = field.default?.let(FormValues::boolean),
    )

    is FormField.MultiselectField -> EncodedFormField(
        key = field.key,
        type = TYPE_MULTISELECT,
        title = field.title,
        required = field.required,
        hidden = field.hidden,
        options = field.options.map(FormOption::value),
        custom = field.custom,
        minLength = null,
        maxLength = null,
        pattern = null,
        minItems = field.minItems,
        maxItems = field.maxItems,
        minimum = null,
        maximum = null,
        default = field.default.takeIf { it.isNotEmpty() }?.let(FormValues::strings),
    )

    // An external field is a link, not an answer.
    is FormField.ExternalField -> EncodedFormField(
        key = field.key,
        type = TYPE_UNKNOWN,
        title = field.title,
        required = field.required,
        hidden = field.hidden,
        options = emptyList(),
        custom = false,
        minLength = null,
        maxLength = null,
        pattern = null,
        minItems = null,
        maxItems = null,
        minimum = null,
        maximum = null,
        default = null,
    )

    // A field type this build does not know keeps the name the server gave it, so a later build can
    // recognise it, and loses only the rules this one does not have.
    is FormField.Unknown -> EncodedFormField(
        key = field.key,
        type = field.declaredType ?: TYPE_UNKNOWN,
        title = field.title,
        required = field.required,
        hidden = field.hidden,
        options = emptyList(),
        custom = false,
        minLength = null,
        maxLength = null,
        pattern = null,
        minItems = null,
        maxItems = null,
        minimum = null,
        maximum = null,
        default = null,
    )
}

/** The encoded fields of a form, which is what a notification action carries. */
fun encodeFields(form: FormInfo): List<EncodedFormField> = form.fields.map(::encodeField)

/**
 * Rebuilds the form's fields from what a notification carried.
 *
 * The `when` conditions are not carried, because a form that answers from the shade is by definition
 * one free-text question with nothing to condition on; a form with conditions is opened instead.
 */
fun List<EncodedFormField>.toFormFields(): List<FormField> = map { field ->
    when (field.type) {
        TYPE_STRING -> FormField.StringField(
            key = field.key,
            title = field.title,
            required = field.required,
            hidden = field.hidden,
            minLength = field.minLength,
            maxLength = field.maxLength,
            pattern = field.pattern,
            options = field.options.map(::option),
            custom = field.custom,
            default = field.default?.textOrNull(),
        )

        TYPE_NUMBER, TYPE_INTEGER -> FormField.NumberField(
            key = field.key,
            title = field.title,
            required = field.required,
            hidden = field.hidden,
            minimum = field.minimum,
            maximum = field.maximum,
            default = field.default?.numberOrNull(),
            integer = field.type == TYPE_INTEGER,
        )

        TYPE_BOOLEAN -> FormField.BooleanField(
            key = field.key,
            title = field.title,
            required = field.required,
            hidden = field.hidden,
            default = field.default?.booleanOrNull(),
        )

        TYPE_MULTISELECT -> FormField.MultiselectField(
            key = field.key,
            title = field.title,
            required = field.required,
            hidden = field.hidden,
            options = field.options.map(::option),
            minItems = field.minItems,
            maxItems = field.maxItems,
            custom = field.custom,
            default = field.default?.textsOrEmpty().orEmpty(),
        )

        else -> FormField.Unknown(declaredType = field.type, key = field.key, raw = JsonObject(emptyMap()))
    }
}

/** Whether a text can be read as this field's declared type at all. */
fun EncodedFormField.accepts(text: String): Boolean = when (type) {
    TYPE_NUMBER, TYPE_INTEGER -> text.trim().toDoubleOrNull() != null
    TYPE_BOOLEAN -> text.trim().lowercase() in BOOLEAN_WORDS
    else -> true
}

private fun option(value: String) = FormOption(value = value, label = value)

private fun JsonElement.textOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.numberOrNull(): Double? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        isString -> when (content) {
            "Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            "NaN" -> Double.NaN
            else -> content.toDoubleOrNull()
        }

        else -> content.toDoubleOrNull()
    }

    else -> null
}

private fun JsonElement.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

private fun JsonElement.textsOrEmpty(): List<String> =
    (this as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        .orEmpty()

internal const val TYPE_STRING: String = "string"
internal const val TYPE_NUMBER: String = "number"
internal const val TYPE_INTEGER: String = "integer"
internal const val TYPE_BOOLEAN: String = "boolean"
internal const val TYPE_MULTISELECT: String = "multiselect"
internal const val TYPE_UNKNOWN: String = "unknown"

private val BOOLEAN_WORDS: Set<String> = setOf("true", "false")
