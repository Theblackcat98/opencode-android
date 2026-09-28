package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.ExtendedNumberSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.variant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Free-form form metadata. `metadata.kind` is what decides how a form is presented. */
typealias FormMetadata = Map<String, JsonElement>

/** A form awaiting an answer (schema `Form.Info`). */
@Serializable
data class FormInfo(
    val id: String,
    val sessionID: String,
    val title: String,
    val metadata: FormMetadata? = null,
    val fields: List<FormField> = emptyList(),
) {
    /** How this form should be presented, derived from `metadata.kind` (features doc §16). */
    val kind: FormKind get() = FormKind.of(metadata)
}

/** A form with its state (schema `Form.Detail`), which is what `session.form.get` returns. */
@Serializable
data class FormDetail(
    val id: String,
    val sessionID: String,
    val title: String,
    val metadata: FormMetadata? = null,
    val fields: List<FormField> = emptyList(),
    val state: FormState = FormState.Pending,
) {
    val kind: FormKind get() = FormKind.of(metadata)

    /** The projection the rest of the app uses, with the state dropped. */
    fun toInfo(): FormInfo = FormInfo(id, sessionID, title, metadata, fields)
}

/** Whether a form is pending, answered or cancelled (schema `Form.State`). */
@Serializable(with = FormStateSerializer::class)
sealed interface FormState {
    @Serializable
    data object Pending : FormState

    @Serializable
    data class Answered(val answer: FormAnswer) : FormState

    @Serializable
    data object Cancelled : FormState

    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        FormState,
        UnknownVariant
}

/**
 * `Form.State` is discriminated by `status`, and the union strips it before decoding, so the
 * `answered` variant decodes straight into its `answer` map.
 */
internal object FormStateSerializer : DiscriminatedUnionSerializer<FormState>(
    serialName = "dev.opencode.android.FormState",
    discriminator = "status",
    unknown = FormState::Unknown,
    variants = listOf(
        variant("pending", FormState.Pending.serializer()),
        variant("answered", FormState.Answered.serializer()),
        variant("cancelled", FormState.Cancelled.serializer()),
    ),
)

/**
 * How a form's `metadata.kind` selects its presentation.
 *
 * Features doc §16 names three sources with distinct shapes: the `question` tool, first-use
 * web-search consent and MCP elicitation. They are one mechanism with three presentations, which is
 * why this is a property of the metadata rather than three form types.
 */
enum class FormKind {
    /** The `question` tool: one field per question, rendered inline beside its tool card. */
    QUESTION,

    /** `websearch.provider`: consent and provider selection, as a dialog. */
    WEBSEARCH_CONSENT,

    /** `mcp-elicitation`: a sheet that names the server asking. */
    MCP_ELICITATION,

    /** Integration login and anything a future server adds. */
    GENERIC,

    ;

    companion object {
        /** The kind [metadata] selects, or [GENERIC] for a kind this client does not know. */
        fun of(metadata: FormMetadata?): FormKind = when (rawKindOf(metadata)) {
            "question" -> QUESTION
            "websearch.provider" -> WEBSEARCH_CONSENT
            "mcp-elicitation" -> MCP_ELICITATION
            else -> GENERIC
        }

        /** The raw `metadata.kind`, which an unknown kind still has to be nameable. */
        fun rawKindOf(metadata: FormMetadata?): String? =
            (metadata?.get("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** The `mcp-elicitation` server name, which the sheet has to name. */
val FormInfo.mcpServer: String? get() = metadata.stringValue("server")

/** The `mcp-elicitation` message, which the sheet shows as context. */
val FormInfo.mcpMessage: String? get() = metadata.stringValue("message")

/** The `question` tool call a form belongs to, as `{messageID, id}` (schema `Form.Metadata.tool`). */
val FormInfo.questionTool: QuestionToolRef?
    get() {
        val tool = metadata?.get("tool") as? JsonObject ?: return null
        val id = (tool["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return QuestionToolRef((tool["messageID"] as? JsonPrimitive)?.takeIf { it.isString }?.content, id)
    }

private fun FormMetadata?.stringValue(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The tool part a `question` form answers. */
data class QuestionToolRef(val messageID: String?, val id: String)

/**
 * One field of a form (schema `Form.Field`).
 *
 * The union is discriminated by `type`, and every variant carries the common attributes, because a
 * renderer asks "should this field show and is its answer valid" before it asks "how is it edited".
 */
@Serializable(with = FormFieldSerializer::class)
sealed interface FormField {
    val key: String
    val title: String?
    val description: String?
    val required: Boolean
    val hidden: Boolean
    val conditions: List<FormWhen>

    @Serializable
    data class StringField(
        override val key: String,
        override val title: String? = null,
        override val description: String? = null,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        @SerialName("when") override val conditions: List<FormWhen> = emptyList(),
        /** `email`, `uri`, `date` or `date-time`; compared against [StringFormat] constants. */
        val format: String? = null,
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val pattern: String? = null,
        val placeholder: String? = null,
        val default: String? = null,
        val options: List<FormOption> = emptyList(),
        /** True when the user may type something the options do not list. */
        val custom: Boolean = false,
    ) : FormField

    @Serializable
    data class NumberField(
        override val key: String,
        override val title: String? = null,
        override val description: String? = null,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        @SerialName("when") override val conditions: List<FormWhen> = emptyList(),
        @Serializable(with = ExtendedNumberSerializer::class) val minimum: Double? = null,
        @Serializable(with = ExtendedNumberSerializer::class) val maximum: Double? = null,
        @Serializable(with = ExtendedNumberSerializer::class) val default: Double? = null,
        /** True for an `integer` field, which rejects a fractional answer. */
        val integer: Boolean = false,
    ) : FormField

    @Serializable
    data class BooleanField(
        override val key: String,
        override val title: String? = null,
        override val description: String? = null,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        @SerialName("when") override val conditions: List<FormWhen> = emptyList(),
        val default: Boolean? = null,
    ) : FormField

    @Serializable
    data class MultiselectField(
        override val key: String,
        override val title: String? = null,
        override val description: String? = null,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        @SerialName("when") override val conditions: List<FormWhen> = emptyList(),
        val options: List<FormOption> = emptyList(),
        val minItems: Int? = null,
        val maxItems: Int? = null,
        val custom: Boolean = false,
        val default: List<String> = emptyList(),
    ) : FormField

    @Serializable
    data class ExternalField(
        override val key: String,
        val url: String,
        override val title: String? = null,
        override val description: String? = null,
    ) : FormField {
        override val required: Boolean get() = false
        override val hidden: Boolean get() = false
        override val conditions: List<FormWhen> get() = emptyList()
    }

    /** A field type this client does not know; the raw JSON is kept so nothing is lost. */
    data class Unknown(
        val declaredType: String?,
        override val key: String,
        override val raw: JsonObject,
    ) : FormField,
        UnknownVariant {
        override val discriminator: String? get() = declaredType
        override val title: String? = null
        override val description: String? = null
        override val required: Boolean get() = false
        override val hidden: Boolean get() = false
        override val conditions: List<FormWhen> get() = emptyList()
    }

    /** The label a renderer shows; the key is the fallback, because a field is addressed by it. */
    fun labelOrKey(): String = title?.takeIf(String::isNotBlank) ?: key
}

internal object FormFieldSerializer : DiscriminatedUnionSerializer<FormField>(
    serialName = "dev.opencode.android.FormField",
    unknown = { declaredType, raw ->
        FormField.Unknown(
            declaredType = declaredType,
            key = (raw["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
            raw = raw,
        )
    },
    variants = listOf(
        variant("string", FormField.StringField.serializer()),
        variant("number", NumericFormFieldSerializer(integer = false)),
        variant("integer", NumericFormFieldSerializer(integer = true)),
        variant("boolean", FormField.BooleanField.serializer()),
        variant("multiselect", FormField.MultiselectField.serializer()),
        variant("external", FormField.ExternalField.serializer()),
    ),
)

/**
 * `number` and `integer` have the same shape, so one serializer decodes both.
 *
 * The union strips `type` before handing the object to a variant, so the declared kind is not
 * readable here; instead each discriminator value gets its own instance, which is the only way
 * [FormField.NumberField.integer] can be set correctly after decoding.
 */
private class NumericFormFieldSerializer(private val integer: Boolean) : KSerializer<FormField.NumberField> {
    override val descriptor: SerialDescriptor get() = FormField.NumberField.serializer().descriptor

    override fun deserialize(decoder: Decoder): FormField.NumberField {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("A form field can only be decoded from JSON")
        return input.json.decodeFromJsonElement(FormField.NumberField.serializer(), input.decodeJsonElement())
            .copy(integer = integer)
    }

    override fun serialize(encoder: Encoder, value: FormField.NumberField) {
        throw SerializationException("A numeric form field is only ever decoded; the server owns these shapes")
    }
}

/** One choice of a `string` or `multiselect` field (schema `Form.Option`). */
@Serializable
data class FormOption(
    val value: String,
    val label: String,
    val description: String? = null,
)

/** A conditional-visibility rule: a field shows when every one of its conditions holds. */
@Serializable
data class FormWhen(
    val key: String,
    /** `eq` or `neq`; compared against [WhenOp] constants. */
    val op: String,
    val value: JsonElement,
) {
    object WhenOp {
        const val EQ = "eq"
        const val NEQ = "neq"
    }
}

/** The answer map of a reply (schema `Form.Answer`): field key to value. */
typealias FormAnswer = Map<String, JsonElement>

/** `POST …/form/{id}/reply` (schema `Form.Reply`). */
@Serializable
data class FormReplyPayload(val answer: FormAnswer)

/** One entry of `GET /api/fs/list` (schema `FileSystem.Entry`). */
@Serializable
data class FileSystemEntry(
    val path: String,
    /** `file` or `directory`; compared against [EntryType] constants. */
    val type: String,
) {
    object EntryType {
        const val FILE = "file"
        const val DIRECTORY = "directory"
    }

    val isDirectory: Boolean get() = type == EntryType.DIRECTORY

    /** The last path segment, which is what a browser row shows. */
    val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
}

/**
 * The answer values the forms engine builds.
 *
 * JSON has no literal for a non-finite number, so the server sends `"Infinity"`, `"-Infinity"` and
 * `"NaN"` as strings; [number] writes them back in that form rather than losing the answer.
 */
object FormValues {
    fun string(value: String): JsonElement = JsonPrimitive(value)

    fun boolean(value: Boolean): JsonElement = JsonPrimitive(value)

    fun number(value: Double): JsonElement = when {
        value.isNaN() -> JsonPrimitive("NaN")
        value == Double.POSITIVE_INFINITY -> JsonPrimitive("Infinity")
        value == Double.NEGATIVE_INFINITY -> JsonPrimitive("-Infinity")
        value == Math.rint(value) && kotlin.math.abs(value) < MAX_SAFE_INTEGER -> JsonPrimitive(value.toLong())
        else -> JsonPrimitive(value)
    }

    fun strings(values: List<String>): JsonElement = JsonArray(values.map(::JsonPrimitive))

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_992.0
}
