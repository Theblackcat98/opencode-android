package dev.opencode.android.feature.servers.camera

import androidx.camera.core.ImageProxy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Decodes QR payloads out of a camera frame.
 *
 * The implementation is a distribution concern, not a feature one (plan §3): the Play build uses
 * ML Kit's bundled barcode model, and the F-Droid build uses ZXing so the app ships without Google
 * Play Services. Both live in the `app` module's flavor source sets and are bound in its Hilt
 * graph; this feature module only sees the interface, so it can never pull in either library.
 */
interface QrCodeDecoder {

    /** Identifies the decoder in the developer log, for example "ML Kit" or "ZXing". */
    val name: String

    /**
     * Decodes one frame. [onResult] is called at most once, with the first payload found, and may
     * be called on a worker thread. [close] on the frame stays the caller's responsibility: the
     * camera analyzer owns the frame's lifetime.
     */
    fun decode(image: ImageProxy, onResult: (String) -> Unit)

    /** Releases whatever the decoder holds. The decoder cannot be used afterwards. */
    fun close()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface QrCodeDecoderEntryPoint {
    fun qrCodeDecoder(): QrCodeDecoder
}

/** The decoder the active distribution ships, resolved once per composition. */
@Composable
fun rememberQrCodeDecoder(): QrCodeDecoder {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(context, QrCodeDecoderEntryPoint::class.java).qrCodeDecoder()
    }
}
