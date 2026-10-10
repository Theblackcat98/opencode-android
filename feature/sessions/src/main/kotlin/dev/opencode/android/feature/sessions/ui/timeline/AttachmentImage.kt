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

/** The attachment as a bitmap, or null when it is not an image this device can decode. */
internal fun decodeAttachmentImage(data: String): ImageBitmap? {
    val bytes = attachmentBytes(data) ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
