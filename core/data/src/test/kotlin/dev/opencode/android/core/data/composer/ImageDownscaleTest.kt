package dev.opencode.android.core.data.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sizing a picture so the server accepts it.
 *
 * The rules are the server's defaults (2000 px, 5 MiB of Base64) and the properties are the ones a
 * camera can break: an image that is already small must not be blown up, a very large one must come
 * down, and the result must be a size the encoder can actually produce within the budget. The
 * encoder itself is the one injected function, so every branch of the decision is reachable here.
 */
class ImageDownscaleTest {

    /** Stands in for the platform encoder: roughly two bytes per pixel, as a photo compresses. */
    private fun jpegish(width: Int, height: Int): Long = (width.toLong() * height * 2) + 1024

    @Test
    fun `a small image is sent as it is`() {
        val plan = ImageDownscale.plan(800, 600, encodedBytes = ::jpegish)
        assertEquals(800, plan.width)
        assertEquals(600, plan.height)
        assertEquals(0, plan.steps)
        assertFalse(plan.scaled)
        assertTrue(plan.fits)
    }

    @Test
    fun `a small image is never enlarged`() {
        // Sending a 400 px screenshot as a 2000 px image costs tokens and adds nothing.
        val plan = ImageDownscale.plan(400, 300, encodedBytes = { _, _ -> 0L })
        assertEquals(400, plan.width)
        assertEquals(300, plan.height)
        assertEquals(0, plan.steps)
    }

    @Test
    fun `a long edge is brought to the maximum`() {
        val plan = ImageDownscale.plan(4000, 3000, encodedBytes = { _, _ -> 0L })
        assertEquals(2000, plan.width)
        assertEquals(1500, plan.height)
        assertEquals(1, plan.steps)
    }

    @Test
    fun `the aspect ratio is preserved when the long edge is fitted`() {
        val plan = ImageDownscale.plan(4000, 2250, encodedBytes = { _, _ -> 0L })
        // 16:9 in, 16:9 out, to within a pixel of rounding.
        assertEquals(plan.width * 9, plan.height * 16)
    }

    @Test
    fun `the other dimension is fitted when it is the long one`() {
        val plan = ImageDownscale.plan(1000, 6000, encodedBytes = { _, _ -> 0L })
        assertEquals(2000, plan.height)
        assertEquals(333, plan.width)
    }

    @Test
    fun `an image that still does not fit the budget is halved until it does`() {
        // Even at 2000 px this encoder produces far too much, so the plan has to give up the edge.
        val plan = ImageDownscale.plan(3000, 2000, encodedBytes = { w, h -> w.toLong() * h * 40 })
        assertTrue("the plan must come under the budget", plan.fits)
        assertTrue("it must have been reduced", plan.width < 2000)
    }

    @Test
    fun `the halving stops at the floor and says so when it does not fit`() {
        val plan = ImageDownscale.plan(4000, 3000, encodedBytes = { _, _ -> Long.MAX_VALUE / 2 })
        assertFalse(plan.fits)
        assertTrue(plan.width <= ImageDownscale.MIN_EDGE_PX)
        assertTrue(plan.height <= ImageDownscale.MIN_EDGE_PX)
    }

    @Test
    fun `the loop terminates for every starting size`() {
        // A pathological encoder must not make this loop forever.
        (1..40).forEach { edge ->
            val plan = ImageDownscale.plan(edge, edge, encodedBytes = { w, h -> w.toLong() * h * 1_000 })
            assertTrue("edge $edge", plan.width >= 1)
            assertTrue("edge $edge", plan.height >= 1)
        }
    }

    @Test
    fun `a one pixel image is not resized to nothing`() {
        val plan = ImageDownscale.plan(1, 1, encodedBytes = { _, _ -> Long.MAX_VALUE })
        assertEquals(1, plan.width)
        assertEquals(1, plan.height)
    }

    @Test
    fun `a size of zero is a programming error, not an image`() {
        val failure = runCatching { ImageDownscale.plan(0, 10, encodedBytes = { _, _ -> 0L }) }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `a budget that fits exactly is a fit`() {
        val plan = ImageDownscale.plan(2000, 2000, encodedBytes = { _, _ -> 100L }, budget = 100L)
        assertTrue(plan.fits)
    }

    @Test
    fun `the base64 length is the one the encoder will produce`() {
        assertEquals(4L, ImageDownscale.base64Length(1))
        assertEquals(4L, ImageDownscale.base64Length(3))
        assertEquals(8L, ImageDownscale.base64Length(4))
        assertEquals(8L, ImageDownscale.base64Length(6))
    }

    @Test
    fun `a megapixel of photo is inside the budget and four are not`() {
        // Two bytes a pixel: a 1000 px square is about 2 MB encoded, four of them about 8 MB.
        assertTrue(ImageDownscale.plan(1000, 1000, encodedBytes = ::jpegish).fits)
        val four = ImageDownscale.plan(2000, 2000, encodedBytes = ::jpegish)
        assertTrue("the plan reduces rather than refuses", four.width < 2000)
        assertTrue(four.fits)
    }

    @Test
    fun `a real camera frame is brought under the budget rather than refused`() {
        // 4032 x 3024 is a phone photo, and the whole point is that it is sent, smaller.
        val plan = ImageDownscale.plan(4032, 3024, encodedBytes = ::jpegish)
        assertTrue(plan.fits)
        assertTrue(plan.width <= ImageDownscale.MAX_EDGE_PX)
        assertTrue(plan.scaled)
    }
}
