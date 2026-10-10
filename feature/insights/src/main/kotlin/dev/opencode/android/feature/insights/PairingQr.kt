package dev.opencode.android.feature.insights

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The quiet zone the QR specification asks for, in modules, on every side. */
private const val QUIET_ZONE_MODULES = 4

/** Each module is this many pixels, so the picture is sharp when it is drawn without filtering. */
private const val PIXELS_PER_MODULE = 8

/** ARGB black and white, written as the Int values rather than as `Color`s so no Compose type is needed here. */
private const val BLACK_ARGB = -0x1000000
private const val WHITE_ARGB = -0x1

/**
 * The QR matrix for [text], with `true` for a dark module, and no quiet zone.
 *
 * The margin is zero on purpose: the quiet zone is added when the picture is drawn, so the finder patterns
 * sit exactly in the matrix's corners and the matrix can be checked against the specification directly.
 * Error correction is M, which tolerates a scuffed or glared code, and a phone scanning a screen needs that.
 *
 * Throws ZXing's `WriterException` (or an `IllegalArgumentException` for a text too long to encode), which the
 * caller turns into the text fallback rather than a blank square.
 */
internal fun qrMatrix(text: String): Array<BooleanArray> {
    val bits = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        ),
    )
    return Array(bits.height) { y -> BooleanArray(bits.width) { x -> bits.get(x, y) } }
}

/**
 * The QR code for [text] as a black-on-white picture with its quiet zone.
 *
 * Black and white regardless of the theme, because a scanner reads contrast and a dark-theme QR code with
 * light modules on a dark ground is one most cameras refuse. Call this off the main thread: it encodes and
 * fills about a hundred thousand pixels for a link of typical length.
 */
internal fun qrPicture(text: String): ImageBitmap {
    val matrix = qrMatrix(text)
    val side = (matrix.size + 2 * QUIET_ZONE_MODULES) * PIXELS_PER_MODULE
    val pixels = IntArray(side * side) { WHITE_ARGB }
    for (py in 0 until side) {
        val my = py / PIXELS_PER_MODULE - QUIET_ZONE_MODULES
        if (my !in matrix.indices) continue
        val row = matrix[my]
        for (px in 0 until side) {
            val mx = px / PIXELS_PER_MODULE - QUIET_ZONE_MODULES
            if (mx in row.indices && row[mx]) pixels[py * side + px] = BLACK_ARGB
        }
    }
    return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
}
