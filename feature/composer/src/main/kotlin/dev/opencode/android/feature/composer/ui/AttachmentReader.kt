package dev.opencode.android.feature.composer.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.android.core.data.composer.AttachmentDraft
import dev.opencode.android.core.data.composer.AttachmentKind
import dev.opencode.android.core.data.composer.AttachmentPolicy
import dev.opencode.android.core.data.composer.ImageDownscale
import dev.opencode.android.core.data.composer.LineRange
import dev.opencode.android.core.data.composer.ServerPath
import dev.opencode.android.core.model.FileSystemEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject

/**
 * Where an attachment's bytes and metadata come from.
 *
 * **The one platform-dependent seam in the reader.** A picked image arrives as a content URI from an
 * app this process knows nothing about, so the three things the reader needs — the bytes, the mime
 * type and the display name — all have to come from the provider rather than from a path. Naming that
 * as an interface is what lets the whole read (bounds, plan, decode, encode, `data:` URL) be exercised
 * against a real `BitmapFactory` in a Robolectric test, and a real phone is the only place the last
 * part of that can be checked.
 */
interface ImageSource {
    /** The bytes, or `null` when the provider cannot be opened. */
    fun open(uri: Uri): InputStream?

    /** The provider's own type, which is more reliable than a file extension. */
    fun type(uri: Uri): String?

    /** The name a person recognises the file by. */
    fun name(uri: Uri): String?
}

/** The [ImageSource] the app uses: the system [ContentResolver]. */
class ContentResolverImages(private val resolver: ContentResolver) : ImageSource {
    override fun open(uri: Uri): InputStream? = resolver.openInputStream(uri)

    override fun type(uri: Uri): String? = resolver.getType(uri)

    override fun name(uri: Uri): String? = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}

/**
 * Turning what the user picked on the phone into something the server accepts.
 *
 * **Everything expensive happens off the main thread** (plan §5.4): decoding a twelve-megapixel
 * photograph and re-encoding it are the two longest operations the composer ever performs, and doing
 * them on the UI thread is a dropped frame at best and an ANR at worst. The whole read is inside one
 * `withContext(ioDispatcher)`, so nothing the picker hands back is ever touched on the main thread.
 *
 * **Two decoding steps, on purpose.** The bounds are read first and the image is only decoded near
 * the size the plan asks for, because decoding a full-resolution photo only to scale it down is what
 * turns "attach a screenshot" into an out-of-memory error on a cheap phone. The *decision* about the
 * size is [ImageDownscale]'s and is pure; only the decode and the encode happen here.
 *
 * The result is a `data:` URL, which is what the API carries for content from the phone (features
 * doc §6). A server-side file never goes through this class — it is a URI the server already has, so
 * the phone neither reads nor guesses it.
 */
class AttachmentReader(
    private val images: ImageSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** The app's reader: the system [ContentResolver] is where a picked image's bytes are. */
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(ContentResolverImages(context.contentResolver))

    /**
     * Reads the image at [source] and prepares it for sending.
     *
     * [source] is whatever the photo picker, the camera or the document picker handed back. The bytes
     * are read through the [ContentResolver] rather than from a path, because a content URI from
     * another app has no path this process may read, and the display name and type come from the
     * provider's own metadata rather than from the file name.
     */
    suspend fun readImage(source: Uri): Result<AttachmentDraft> = withContext(ioDispatcher) {
        runCatching {
            val mime = images.type(source) ?: "image/jpeg"
            val name = images.name(source) ?: "image"
            val size = boundsOf(source)
            val bytes = encodeWithinBudget(source, mime, size)
            AttachmentDraft(
                id = "$source#${bytes.size}",
                label = name,
                uri = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}",
                kind = AttachmentPolicy.classify(mime),
                sizeBytes = bytes.size.toLong(),
                mime = mime,
            )
        }
    }

    /**
     * A file or directory that already lives on the server.
     *
     * **No bytes are read.** The client does not have the file and cannot know its type or its size,
     * so the draft carries the URI, the name and the type the server reported, and the size is `0` —
     * which [dev.opencode.android.core.data.composer.AttachmentPolicy] reads as "unknown", not as
     * "empty". A line range is part of the URI, which is where the API wants it.
     */
    fun serverFile(
        path: String,
        name: String,
        type: String,
        location: String?,
        range: LineRange? = null,
    ): AttachmentDraft = AttachmentDraft(
        id = "file:$path${range?.toQuery().orEmpty()}",
        label = name,
        uri = ServerPath.toUri(path, location, range),
        kind = if (type == FileSystemEntry.EntryType.DIRECTORY) AttachmentKind.DIRECTORY else AttachmentKind.TEXT,
        sizeBytes = 0,
        range = range,
    )

    /** A reference directory from `reference.list`, which attaches exactly like a directory. */
    fun reference(path: String, name: String, location: String?): AttachmentDraft = AttachmentDraft(
        id = "ref:$path",
        label = name,
        uri = ServerPath.toUri(path, location),
        kind = AttachmentKind.DIRECTORY,
    )

    /** A file chosen from the server's own listing, with a line range, for a mention. */
    fun serverFileWithRange(
        path: String,
        name: String,
        type: String,
        location: String?,
        range: LineRange,
    ): AttachmentDraft = serverFile(path, name, type, location, range)

    /**
     * Decodes and re-encodes until the Base64 is inside the server's budget.
     *
     * The plan's size comes from an estimate, because the real encoded size is only known after
     * encoding. A picture whose content compresses worse than the estimate — a screenshot of text, a
     * PNG of a flat diagram — is therefore encoded, measured, halved and encoded again, up to
     * [MAX_ATTEMPTS] times. The loop is bounded because a pathological image must not spin, and the
     * policy still checks the final bytes against the 20 MiB decoded cap, so a result that never fits
     * is blocked with a reason rather than sent.
     */
    private suspend fun encodeWithinBudget(source: Uri, mime: String, size: Size): ByteArray {
        var plan = ImageDownscale.plan(size.width, size.height, encodedBytes = { w, h -> estimatedBase64(w, h, mime) })
        var bytes = decode(source, mime, size, plan)
        var attempt = 0
        while (ImageDownscale.base64Length(bytes.size.toLong()) > ImageDownscale.MAX_BASE64_BYTES &&
            attempt < MAX_ATTEMPTS - 1
        ) {
            plan = plan.halved()
            bytes = decode(source, mime, size, plan)
            attempt++
        }
        return bytes
    }

    private fun boundsOf(source: Uri): Size {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // `decodeStream` returns null in bounds-only mode, so the stream's own nullness is the thing
        // being checked, not the decode's return value.
        val stream = images.open(source) ?: error("the picked file could not be opened")
        stream.use { BitmapFactory.decodeStream(it, null, options) }
        check(options.outWidth > 0 && options.outHeight > 0) { "the picked file is not an image" }
        return Size(options.outWidth, options.outHeight)
    }

    private fun decode(
        source: Uri,
        mime: String,
        size: Size,
        plan: ImageDownscale.Plan,
    ): ByteArray {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(size.width, size.height, plan)
        }
        val stream = images.open(source) ?: error("the picked file could not be opened")
        val bitmap = stream.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("the picked file could not be decoded")
        return try {
            compress(bitmap, mime)
        } finally {
            bitmap.recycle()
        }
    }

    /** The pixel size of the picked image, read without decoding it. */
    private data class Size(val width: Int, val height: Int)

    private companion object {
        /**
         * How many times an image may be re-encoded after the estimate turned out to be optimistic.
         * Each attempt halves, so six of them is a factor of sixty-four: past that the picture is
         * not what the user picked and blocking it is the honest answer.
         */
        const val MAX_ATTEMPTS = 6
    }

    private fun compress(bitmap: Bitmap, mime: String): ByteArray {
        val png = mime == "image/png"
        val out = ByteArrayOutputStream()
        // 85 is the usual compromise for a photograph: a model reads the image, not the artefacts.
        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 85, out)
        return out.toByteArray()
    }

    /**
     * The `inSampleSize` that decodes closest to, without going below, the planned size.
     *
     * A power of two is required by `BitmapFactory`, so the plan's exact size is approached from
     * above. Decoding a little larger than the target costs a few kilobytes; decoding smaller would
     * send a needlessly degraded picture to the model.
     */
    private fun sampleSizeFor(width: Int, height: Int, plan: ImageDownscale.Plan): Int {
        var sample = 1
        while (width / (sample * 2) >= plan.width && height / (sample * 2) >= plan.height) sample *= 2
        return sample
    }

    /**
     * A rough Base64 length for an image of this size, used only to choose the plan.
     *
     * The real length is whatever the encoder produces and the policy checks the real bytes, so this
     * exists to avoid decoding at full size in the first place. PNG is much worse per pixel than a
     * photograph, which is why it is estimated separately.
     */
    private fun estimatedBase64(width: Int, height: Int, mime: String): Long {
        val perPixel = if (mime == "image/png") 3L else 1L
        return ImageDownscale.base64Length(width.toLong() * height * perPixel)
    }
}
