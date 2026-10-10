package dev.opencode.android.feature.sessions.ui.timeline

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Base64

/**
 * The bytes of an attachment's base64 payload, or null when the payload is not base64. A `data:`
 * URL is accepted too, since the server may send either form.
 */
internal fun attachmentBytes(data: String): ByteArray? {
    val payload = if (data.startsWith("data:")) data.substringAfter(',', "") else data
    return runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull()
}

/**
 * The largest side, in pixels, an attachment is decoded to for the timeline.
 *
 * A phone screen is far smaller than this, and a full-size photo decoded whole is tens of megabytes of
 * heap for a thumbnail the user sees a few hundred pixels wide.
 */
internal const val MAX_ATTACHMENT_SIDE = 2048

/** The attachment as a bitmap, or null when it is not an image this device can decode. */
internal fun decodeAttachmentImage(data: String): ImageBitmap? {
    val bytes = attachmentBytes(data) ?: return null
    // The bounds pass reads the header only, so the full-size pixels are never allocated.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = attachmentSampleSize(bounds.outWidth, bounds.outHeight)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/**
 * The smallest power of two that brings the larger side to at most [maxSide].
 *
 * A power of two is what the decoder honours exactly. An image that already fits, or whose size is
 * unknown (the header could not be read, so the bounds are -1), gets `1`, which is a full decode and so
 * the same as before this was bounded.
 */
internal fun attachmentSampleSize(width: Int, height: Int, maxSide: Int = MAX_ATTACHMENT_SIDE): Int {
    val larger = maxOf(width, height)
    var sample = 1
    while (larger > maxSide * sample) sample *= 2
    return sample
}
