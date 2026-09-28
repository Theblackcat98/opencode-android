package dev.opencode.android.feature.servers.camera

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.opencode.android.feature.servers.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The live camera preview that scans the `opencode pair` QR code.
 *
 * Only the first payload is reported, because one scan is one pairing attempt: reporting every
 * frame would restart the redemption the user is waiting for. The analyzer runs on its own single
 * thread and always closes the frame, so a slow decoder cannot stall the camera pipeline.
 *
 * A device without a usable back camera, or a camera that refuses to bind, calls
 * [onCameraUnavailable] so the screen can offer the pasted link instead of a black rectangle.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun QrCodeScannerView(
    onQrCodeScanned: (String) -> Unit,
    onCameraUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
    decoder: QrCodeDecoder = rememberQrCodeDecoder(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    var cameraProblem by remember { mutableStateOf(false) }
    val scannerLabel = stringResource(R.string.qr_scanner_label)

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            decoder.close()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics { contentDescription = scannerLabel },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val bound = runCatching {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(cameraExecutor) { image ->
                            analyze(image, decoder, delivered, onQrCodeScanned)
                        }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }
                    if (bound.isFailure) cameraProblem = true
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
        )

        // The framing rectangle tells the user where to hold the code, which matters on a phone
        // held at an angle over a terminal.
        Canvas(
            modifier = Modifier
                .size(260.dp)
                .align(Alignment.Center),
        ) {
            val strokeWidth = 4.dp.toPx()
            drawRoundRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = CornerRadius(24.dp.toPx()),
                style = Stroke(width = strokeWidth),
            )
        }

        if (cameraProblem) {
            Text(
                text = stringResource(R.string.qr_camera_unavailable),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
            LaunchedEffect(Unit) { onCameraUnavailable() }
        }
    }
}

/**
 * Reports to the scanner the first payload, and never a frame twice.
 *
 * The frame is closed in a `finally`, so a decoder that throws cannot leak the buffer and stall
 * the analyzer.
 */
private fun analyze(
    image: ImageProxy,
    decoder: QrCodeDecoder,
    delivered: AtomicBoolean,
    onQrCodeScanned: (String) -> Unit,
) {
    try {
        if (delivered.get()) return
        decoder.decode(image) { payload ->
            if (payload.isNotBlank() && delivered.compareAndSet(false, true)) {
                onQrCodeScanned(payload)
            }
        }
    } catch (_: Exception) {
        // A frame the decoder cannot read is skipped; the next one usually works.
    } finally {
        image.close()
    }
}
