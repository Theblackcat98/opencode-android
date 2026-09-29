package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.ExtendedNumberSerializer
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredErrorAndExtendedNumberTest {

    @Test
    fun decodesAndEncodesStructuredError() {
        val withStatusJson = """{"type":"provider.auth","message":"Key invalid","status":403}"""
        val errorWithStatus = OpenCodeJson.decodeFromString<StructuredError>(withStatusJson)
        assertEquals("provider.auth", errorWithStatus.type)
        assertEquals("Key invalid", errorWithStatus.message)
        assertEquals(403, errorWithStatus.status)

        val encoded = OpenCodeJson.encodeToString(StructuredError.serializer(), errorWithStatus)
        val decodedAgain = OpenCodeJson.decodeFromString<StructuredError>(encoded)
        assertEquals(errorWithStatus, decodedAgain)

        val withoutStatusJson = """{"type":"tool.timeout","message":"Exceeded 30s"}"""
        val errorWithoutStatus = OpenCodeJson.decodeFromString<StructuredError>(withoutStatusJson)
        assertEquals("tool.timeout", errorWithoutStatus.type)
        assertNull(errorWithoutStatus.status)
    }

    @Serializable
    private data class NumberWrapper(
        @Serializable(with = ExtendedNumberSerializer::class) val value: Double,
    )

    @Test
    fun extendedNumberHandlesSpecialValues() {
        val infJson = """{"value":"Infinity"}"""
        val inf = OpenCodeJson.decodeFromString<NumberWrapper>(infJson)
        assertEquals(Double.POSITIVE_INFINITY, inf.value, 0.0)
        assertEquals(infJson, OpenCodeJson.encodeToString(NumberWrapper.serializer(), inf))

        val negInfJson = """{"value":"-Infinity"}"""
        val negInf = OpenCodeJson.decodeFromString<NumberWrapper>(negInfJson)
        assertEquals(Double.NEGATIVE_INFINITY, negInf.value, 0.0)
        assertEquals(negInfJson, OpenCodeJson.encodeToString(NumberWrapper.serializer(), negInf))

        val nanJson = """{"value":"NaN"}"""
        val nan = OpenCodeJson.decodeFromString<NumberWrapper>(nanJson)
        assertTrue(nan.value.isNaN())
        assertEquals(nanJson, OpenCodeJson.encodeToString(NumberWrapper.serializer(), nan))
    }

    @Test
    fun extendedNumberEncodesWholeNumbersWithoutDecimals() {
        val zero = NumberWrapper(0.0)
        val encodedZero = OpenCodeJson.encodeToString(NumberWrapper.serializer(), zero)
        assertEquals("""{"value":0}""", encodedZero)

        val fortyTwo = NumberWrapper(42.0)
        val encodedFortyTwo = OpenCodeJson.encodeToString(NumberWrapper.serializer(), fortyTwo)
        assertEquals("""{"value":42}""", encodedFortyTwo)

        val fractional = NumberWrapper(3.14)
        val encodedFractional = OpenCodeJson.encodeToString(NumberWrapper.serializer(), fractional)
        assertEquals("""{"value":3.14}""", encodedFractional)
    }

    @Test
    fun modelRefFormatting() {
        val refWithoutVariant = ModelRef(id = "placeholder-model-1", providerID = "placeholder-provider")
        assertEquals("placeholder-provider/placeholder-model-1", refWithoutVariant.toString())

        val refWithVariant = ModelRef(id = "placeholder-variant-1", providerID = "placeholder-provider", variant = "high")
        assertEquals("placeholder-provider/placeholder-variant-1#high", refWithVariant.toString())
    }

    @Test
    fun tokenUsageDecodingAndDefaults() {
        val emptyUsage = TokenUsage.Zero
        assertEquals(0L, emptyUsage.input)
        assertEquals(0L, emptyUsage.output)
        assertEquals(0L, emptyUsage.reasoning)
        assertEquals(0L, emptyUsage.cache.read)
        assertEquals(0L, emptyUsage.cache.write)
        assertEquals(0L, emptyUsage.total)

        val raw = """{"input":100,"output":50,"reasoning":20,"cache":{"read":10,"write":5}}"""
        val decoded = OpenCodeJson.decodeFromString<TokenUsage>(raw)
        assertEquals(100L, decoded.input)
        assertEquals(50L, decoded.output)
        assertEquals(20L, decoded.reasoning)
        assertEquals(10L, decoded.cache.read)
        assertEquals(5L, decoded.cache.write)
        assertEquals(185L, decoded.total)
    }
}
