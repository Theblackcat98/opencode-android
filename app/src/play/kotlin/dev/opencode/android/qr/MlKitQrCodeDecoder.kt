package dev.opencode.android.qr

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.opencode.android.feature.servers.camera.QrCodeDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Play build's QR decoder: ML Kit with its bundled barcode model (plan §3).
 *
 * The bundled model is deliberate: pairing must work on a device with no Play Services and no
 * network, because the server it pairs with is usually on the local network only.
 */
@Singleton
class MlKitQrCodeDecoder @Inject constructor() : QrCodeDecoder {

    override val name: String = "ML Kit"

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )

    override fun decode(image: ImageProxy, onResult: (String) -> Unit) {
        val mediaImage = image.image ?: return
        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { barcode -> barcode.rawValue }
                    ?.takeIf { it.isNotBlank() }
                    ?.let(onResult)
            }
    }

    override fun close() {
        scanner.close()
    }
}
