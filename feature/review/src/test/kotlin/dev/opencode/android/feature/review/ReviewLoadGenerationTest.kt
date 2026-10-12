package dev.opencode.android.feature.review

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewLoadGenerationTest {
    @Test
    fun `older review response is rejected when a newer request starts`() {
        val generations = ReviewLoadGeneration()
        val older = generations.next()
        val newer = generations.next()

        assertFalse(generations.isCurrent(older))
        assertTrue(generations.isCurrent(newer))
    }
}
