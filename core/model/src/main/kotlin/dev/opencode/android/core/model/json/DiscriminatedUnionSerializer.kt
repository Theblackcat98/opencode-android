package dev.opencode.android.core.model.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass

/**
 * A union member that the client does not recognize. It keeps the raw JSON so that nothing is lost
 * and so that UIs can render a generic fallback.
 */
interface UnknownVariant {
    /** The discriminator value, or `null` when the discriminator was missing. */
    val discriminator: String?

    /** The complete JSON object as received, discriminator included. */
    val raw: JsonObject
}

/**
 * Serializer for a JSON union discriminated by a string field (usually `type`), with a fallback
 * for unknown discriminator values.
 *
 * Variant classes do not declare the discriminator property: it is removed before a variant is
 * decoded and written first when it is encoded. The fallback receives the whole object.
 *
 * ```
 * object MessageSerializer : DiscriminatedUnionSerializer<Message>(
 *     serialName = "Message",
 *     variants = listOf(variant("user", User.serializer()), variant("system", System.serializer())),
 *     unknown = Message::Unknown,
 * )
 * ```
 */
abstract class DiscriminatedUnionSerializer<T : Any>(
    serialName: String,
    private val variants: List<Variant<out T>>,
    private val unknown: (discriminator: String?, raw: JsonObject) -> T,
    private val discriminator: String = "type",
) : KSerializer<T> {

    class Variant<V : Any>(val tag: String, val type: KClass<V>, val serializer: KSerializer<V>)

    private val byTag: Map<String, Variant<out T>> = variants.associateBy { it.tag }
    private val byType: Map<KClass<*>, Variant<out T>> = variants.associateBy { it.type }

    init {
        require(byTag.size == variants.size) { "$serialName: duplicate discriminator values" }
    }

    override val descriptor: SerialDescriptor = SerialDescriptor(serialName, JsonObject.serializer().descriptor)

    /** Discriminator values this serializer decodes into typed variants. */
    val knownTags: Set<String> get() = byTag.keys

    override fun deserialize(decoder: Decoder): T {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("${descriptor.serialName} can only be decoded from JSON")
        val element = input.decodeJsonElement()
        // A value that is not an object cannot carry the discriminator, and the stream must survive
        // it: the fallback keeps the value under `value` so nothing is lost, which is what
        // [UnknownVariant.raw] promises. A `409` on one event must not take the connection down.
        val obj = element as? JsonObject ?: return unknown(null, JsonObject(mapOf("value" to element)))
        val tag = (obj[discriminator] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val variant = tag?.let(byTag::get) ?: return unknown(tag, obj)
        return input.json.decodeFromJsonElement(variant.serializer, JsonObject(obj - discriminator))
    }

    override fun serialize(encoder: Encoder, value: T) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("${descriptor.serialName} can only be encoded to JSON")
        if (value is UnknownVariant) {
            output.encodeJsonElement(value.raw)
            return
        }
        val variant = byType[value::class]
            ?: throw SerializationException("${descriptor.serialName}: no variant registered for ${value::class}")

        @Suppress("UNCHECKED_CAST")
        val body = output.json.encodeToJsonElement(variant.serializer as KSerializer<T>, value).jsonObject
        output.encodeJsonElement(JsonObject(mapOf(discriminator to JsonPrimitive(variant.tag)) + body))
    }
}

/** Declares one variant of a [DiscriminatedUnionSerializer]. */
inline fun <reified V : Any> variant(tag: String, serializer: KSerializer<V>): DiscriminatedUnionSerializer.Variant<V> =
    DiscriminatedUnionSerializer.Variant(tag, V::class, serializer)

/** Reads a string field of a raw object, for [UnknownVariant] implementations. */
internal fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf {
    it.isString
}?.contentOrNull

internal fun JsonObject.primitiveContent(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
