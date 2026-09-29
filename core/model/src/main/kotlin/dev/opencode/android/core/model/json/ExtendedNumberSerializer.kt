package dev.opencode.android.core.model.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Serializer for the server's `number | "Infinity" | "-Infinity" | "NaN"` fields (for example a
 * shell's `exit`). JSON has no literal for non-finite numbers, so the server sends them as strings.
 * Whole numbers are written back without a fraction, so `0` round-trips as `0`, not `0.0`.
 */
object ExtendedNumberSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.opencode.android.ExtendedNumber", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double {
        val input = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        val primitive = input.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Expected a number or a non-finite number string")
        if (primitive.isString) {
            return when (primitive.content) {
                "Infinity" -> Double.POSITIVE_INFINITY
                "-Infinity" -> Double.NEGATIVE_INFINITY
                "NaN" -> Double.NaN
                else -> throw SerializationException("Unexpected number string '${primitive.content}'")
            }
        }
        return primitive.doubleOrNull ?: throw SerializationException("Expected a number, got '${primitive.content}'")
    }

    override fun serialize(encoder: Encoder, value: Double) {
        val output = encoder as? JsonEncoder ?: return encoder.encodeDouble(value)
        val element = when {
            value.isNaN() -> JsonPrimitive("NaN")
            value == Double.POSITIVE_INFINITY -> JsonPrimitive("Infinity")
            value == Double.NEGATIVE_INFINITY -> JsonPrimitive("-Infinity")
            value == Math.rint(value) && kotlin.math.abs(value) < MAX_SAFE_INTEGER -> JsonPrimitive(value.toLong())
            else -> JsonPrimitive(value)
        }
        output.encodeJsonElement(element)
    }

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_992.0
}
