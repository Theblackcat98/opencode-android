package dev.opencode.android.qr

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.opencode.android.feature.servers.camera.QrCodeDecoder
import java.nio.ByteBuffer
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The F-Droid build's QR decoder: ZXing on the camera frame's luminance plane (plan §3).
 *
 * This flavor must not depend on Google Play Services, so the bundled ML Kit model is replaced by
 * ZXing, which is a plain library. Only the Y plane is read, because a QR code is a contrast
 * pattern: the chroma planes carry nothing a decoder needs.
 */
@Singleton
class ZxingQrCodeDecoder @Inject constructor() : QrCodeDecoder {

    override val name: String = "ZXing"

    private val reader = QRCodeReader()
    private val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
        put(DecodeHintType.TRY_HARDER, true)
    }

    override fun decode(image: ImageProxy, onResult: (String) -> Unit) {
        val luminance = image.tightlyPackedLuminance() ?: return
        val side = luminance.size
        val source = PlanarYUVLuminanceSource(luminance, side, side, 0, 0, side, side, false)
        // A frame without a code is the normal case, not a failure, so it is simply not reported.
        val text = runCatching {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        }.getOrNull()
        text?.takeIf { it.isNotBlank() }?.let(onResult)
    }

    override fun close() {
        reader.reset()
    }

    /**
     * The Y plane of a `YUV_420_888` frame, tightly packed.
     *
     * The plane's `rowStride` is usually wider than its width and its `pixelStride` is usually more
     * than one byte, so the plane is compacted here. A frame whose buffer is too short for the
     * dimensions returns `null` and is left to the next one.
     */
    private fun ImageProxy.tightlyPackedLuminance(): ByteArray? {
        if (format != ImageFormat.YUV_420_888) return null
        val plane = planes.firstOrNull() ?: return null
        val width = plane.rowStride
        val rows = height
        if (width <= 0 || rows <= 0) return null

        val buffer: ByteBuffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride
        val out = ByteArray(width * rows)
        val row = ByteArray(width)

        for (y in 0 until rows) {
            val start = y * plane.rowStride
            if (start + (width - 1) * pixelStride >= buffer.limit()) return null
            var written = 0
            var index = start
            while (written < width) {
                row[written++] = buffer.get(index)
                index += pixelStride
            }
            System.arraycopy(row, 0, out, y * width, width)
        }
        return out
    }
}
