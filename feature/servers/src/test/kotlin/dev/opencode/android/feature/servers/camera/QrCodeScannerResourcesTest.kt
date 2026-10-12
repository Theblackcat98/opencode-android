package dev.opencode.android.feature.servers.camera

import androidx.camera.core.ImageProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrCodeScannerResourcesTest {
    @Test
    fun disposeUnbindsBeforeShutdownAndClosesDecoderOnlyOnce() {
        val events = mutableListOf<String>()
        val executor = Executors.newSingleThreadExecutor()
        val decoder = RecordingDecoder(events)
        val resources = QrCodeScannerResources(executor, decoder) {
            events += "unbind"
        }

        resources.dispose()
        resources.dispose()

        assertEquals(listOf("unbind", "close"), events)
        assertTrue(resources.disposed.get())
        assertTrue(executor.isShutdown)
    }

    private class RecordingDecoder(
        private val events: MutableList<String>,
    ) : QrCodeDecoder {
        override val name: String = "test"

        override fun decode(image: ImageProxy, onResult: (String) -> Unit) = Unit

        override fun close() {
            events += "close"
        }
    }
}
