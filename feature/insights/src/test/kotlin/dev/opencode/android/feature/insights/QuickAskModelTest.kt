package dev.opencode.android.feature.insights

import dev.opencode.android.core.model.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickAskModelTest {

    @Test
    fun parsesProviderAndModel() {
        assertEquals(ModelRef(id = "text", providerID = "fake"), parseModelRef("fake/text"))
    }

    @Test
    fun parsesAVariantAfterTheHash() {
        assertEquals(ModelRef(id = "text", providerID = "fake", variant = "low"), parseModelRef("fake/text#low"))
    }

    @Test
    fun anEmptyVariantIsNoVariant() {
        assertEquals(ModelRef(id = "text", providerID = "fake"), parseModelRef("fake/text#"))
    }

    @Test
    fun refusesAModelWithoutAProvider() {
        assertNull(parseModelRef("text"))
        assertNull(parseModelRef("/text"))
    }

    @Test
    fun refusesAModelWithoutAnId() {
        assertNull(parseModelRef("fake/"))
        assertNull(parseModelRef("fake/#low"))
    }
}
