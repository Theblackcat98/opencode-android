package dev.opencode.android.feature.sessions.ui.timeline

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AttachmentImageTest {

    @Test
    fun aBareBase64PayloadDecodesToItsBytes() {
        assertArrayEquals("hi".toByteArray(), attachmentBytes("aGk="))
    }

    @Test
    fun aDataUrlDecodesToTheSameBytesAsItsPayload() {
        assertArrayEquals("hi".toByteArray(), attachmentBytes("data:image/png;base64,aGk="))
    }

    @Test
    fun aPayloadThatCannotBeBase64IsNull() {
        assertNull(attachmentBytes("a"))
    }

    /**
     * A photo-sized image is decoded at a sample size that keeps the larger side within the limit, so
     * the full-size pixels are never held. The assertion is on the decoded bitmap, not on the sample
     * size, because that is what the timeline draws.
     */
    @Test
    fun aLargeImageIsDecodedToAtMostTheAttachmentSide() {
        val image = decodeAttachmentImage(pngBase64(4096, 4096))

        assertNotNull(image)
        val larger = maxOf(image!!.width, image.height)
        assertTrue("the larger side was $larger", larger > 0 && larger <= MAX_ATTACHMENT_SIDE)
    }

    @Test
    fun aTinyImageKeepsItsSize() {
        val image = decodeAttachmentImage(pngBase64(64, 40))

        assertNotNull(image)
        assertEquals(64, image!!.width)
        assertEquals(40, image.height)
    }

    @Test
    fun aNonImagePayloadDecodesToNull() {
        assertNull(decodeAttachmentImage(Base64.getEncoder().encodeToString("not a picture".toByteArray())))
    }

    @Test
    fun theSampleSizeIsTheSmallestPowerOfTwoThatFitsTheLargerSide() {
        assertEquals(1, attachmentSampleSize(2048, 100))
        assertEquals(2, attachmentSampleSize(2049, 100))
        assertEquals(2, attachmentSampleSize(4096, 4096))
        assertEquals(4, attachmentSampleSize(4097, 10))
        assertEquals(1, attachmentSampleSize(-1, -1))
    }

    private fun pngBase64(width: Int, height: Int): String {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(200, 80, 40))
        val out = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.PNG, 100, out)
        source.recycle()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
}
