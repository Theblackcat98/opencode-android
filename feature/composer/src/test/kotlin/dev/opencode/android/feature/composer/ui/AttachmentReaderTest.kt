package dev.opencode.android.feature.composer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import dev.opencode.android.core.data.composer.AttachmentKind
import dev.opencode.android.core.data.composer.AttachmentPolicy
import dev.opencode.android.core.data.composer.AttachmentProblem
import dev.opencode.android.core.data.composer.AttachmentVerdict
import dev.opencode.android.core.data.composer.ImageDownscale
import dev.opencode.android.core.data.composer.LineRange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs

/**
 * The attachment pipeline's platform half: reading a picked image, sizing it and encoding it.
 *
 * **The decision code is tested elsewhere and exhaustively** (`ImageDownscaleTest`,
 * `AttachmentPolicyTest`); what needs an Android environment is the part that exists only because
 * there is a `BitmapFactory`: that the bounds are read without decoding, that the decode is sampled
 * down rather than performed at full size, and that the result is a `data:` URL the API accepts.
 *
 * The image source is the seam, so this runs a real encoder over a real bitmap rather than a mock of
 * one. What it does not reach is a real camera, a real picker and a real content provider: those are
 * three contracts with other apps, and only a device can satisfy them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentReaderTest {

    @Test
    fun `a small image is read whole and sent as a data url`() = runTest {
        val reader = reader(bytes = png(400, 300))

        val draft = reader.readImage(URI).getOrThrow()

        assertEquals("shot.png", draft.label)
        assertEquals("image/png", draft.mime)
        assertEquals(AttachmentKind.IMAGE, draft.kind)
        assertTrue(draft.uri.startsWith("data:image/png;base64,"))
        assertTrue("the decoded size is reported", draft.sizeBytes > 0)
        assertEquals(AttachmentVerdict.Ok, AttachmentPolicy.verify(draft, model(images = true)))
    }

    @Test
    fun `a small image is not enlarged`() = runTest {
        val reader = reader(bytes = png(400, 300))

        val pixels = decode(reader.readImage(URI).getOrThrow())

        assertEquals(400, pixels.width)
        assertEquals(300, pixels.height)
    }

    @Test
    fun `a large image is decoded smaller than it is, keeping its shape`() = runTest {
        val reader = reader(bytes = png(2400, 1800))

        val pixels = decode(reader.readImage(URI).getOrThrow())

        assertTrue("a 2400 px image must not be sent as 2400 px", maxOf(pixels.width, pixels.height) <= 2000)
        val sourceRatio = 2400.0 / 1800.0
        val sentRatio = pixels.width.toDouble() / pixels.height.toDouble()
        assertTrue("the aspect ratio survives, was $sentRatio", abs(sentRatio - sourceRatio) < 0.05)
    }

    @Test
    fun `the result is either inside the base64 budget or refused with a reason`() = runTest {
        val reader = reader(bytes = png(3000, 2000))

        val draft = reader.readImage(URI).getOrThrow()
        val verdict = AttachmentPolicy.verify(draft, model(images = true))

        if (verdict !is AttachmentVerdict.Ok) {
            assertEquals(AttachmentProblem.TOO_LARGE, (verdict as AttachmentVerdict.Blocked).problem)
        }
        assertNotNull(decode(draft))
    }

    @Test
    fun `a provider with no type falls back to a type the model reads`() = runTest {
        val draft = reader(bytes = png(100, 100), type = null).readImage(URI).getOrThrow()
        assertEquals("image/jpeg", draft.mime)
    }

    @Test
    fun `a provider with no name is not a failure`() = runTest {
        val draft = reader(bytes = png(100, 100), name = null).readImage(URI).getOrThrow()
        assertEquals("image", draft.label)
    }

    @Test
    fun `a file the provider cannot open is a failure, not a crash`() = runTest {
        val result = reader(bytes = null).readImage(URI)
        assertTrue("a camera app that hands back nothing must be survivable", result.isFailure)
    }

    @Test
    fun `a file that is not an image is refused rather than sent as nonsense`() = runTest {
        val result = reader(bytes = "not a png".toByteArray()).readImage(URI)
        assertTrue(result.isFailure)
    }

    @Test
    fun `a server file is never read and never gets a made up size`() {
        val draft = reader().serverFile("/work/src/a.ts", "a.ts", "file", location = "/work")

        assertEquals("file:///work/src/a.ts", draft.uri)
        assertEquals("an unknown size is zero, and zero means unknown", 0L, draft.sizeBytes)
        assertEquals(AttachmentKind.TEXT, draft.kind)
        assertEquals(AttachmentVerdict.Ok, AttachmentPolicy.verify(draft, model(images = false)))
    }

    @Test
    fun `a server file with a line range carries it on the uri`() {
        val draft = reader().serverFile("/work/a.ts", "a.ts", "file", location = "/work", range = LineRange(20, 45))

        assertEquals("file:///work/a.ts?start=20&end=45", draft.uri)
        assertEquals(LineRange(20, 45), draft.range)
    }

    @Test
    fun `a directory and a reference are both directories`() {
        assertEquals(AttachmentKind.DIRECTORY, reader().serverFile("/work/src", "src", "directory", "/work").kind)
        val reference = reader().reference("/work/docs", "docs", "/work")
        assertEquals(AttachmentKind.DIRECTORY, reference.kind)
        assertEquals("file:///work/docs", reference.uri)
    }

    @Test
    fun `the read happens on the injected dispatcher and not on the caller's`() = runTest {
        // A picker on the main thread that decodes a photograph is a dropped frame. The contract is
        // that the whole read is inside one `withContext`, which is what this observes.
        var dispatched = false
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                dispatched = true
                block.run()
            }
        }

        AttachmentReader(FakeImages(png(50, 50), "image/png", "shot.png"), dispatcher).readImage(URI)

        assertTrue("the read must not run on the caller's thread", dispatched)
    }

    private fun reader(
        bytes: ByteArray? = png(50, 50),
        type: String? = "image/png",
        name: String? = "shot.png",
    ) = AttachmentReader(
        FakeImages(bytes, type, name),
        UnconfinedTestDispatcher(),
    )

    private fun decode(draft: dev.opencode.android.core.data.composer.AttachmentDraft): Bitmap {
        val bytes = Base64.decode(draft.uri.substringAfter("base64,"), Base64.NO_WRAP)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "not a real image" }
    }

    private fun model(images: Boolean) = dev.opencode.android.core.model.ModelInfo(
        id = "m",
        modelID = "m",
        providerID = "p",
        name = "M",
        capabilities = dev.opencode.android.core.model.ModelInfo.Capabilities(
            tools = true,
            input = if (images) listOf("image") else emptyList(),
        ),
        limit = dev.opencode.android.core.model.ModelInfo.Limit(context = 1000),
    )

    private class FakeImages(
        private val bytes: ByteArray?,
        private val type: String?,
        private val name: String?,
    ) : ImageSource {
        override fun open(uri: Uri): InputStream? = bytes?.let { ByteArrayInputStream(it) }

        override fun type(uri: Uri): String? = type

        override fun name(uri: Uri): String? = name
    }

    private companion object {
        val URI: Uri = Uri.parse("content://picked/1")

        /** A flat fill, which is what a screenshot is: big in pixels, small in bytes. */
        fun png(width: Int, height: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(120, 140, 160))
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            return out.toByteArray()
        }
    }
}
