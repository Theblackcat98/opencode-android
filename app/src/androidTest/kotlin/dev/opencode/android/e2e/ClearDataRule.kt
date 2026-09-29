package dev.opencode.android.e2e

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Wipes the target app's data before each test, so every E2E test starts from a cold,
 * server-less install with no shared state.
 *
 * Declare before the compose rule so it runs first:
 *
 *     @get:Rule(order = 0) val clearData = ClearDataRule()
 *     @get:Rule(order = 1) val compose = createComposeRule()
 *
 * `pm clear` kills the app process; the tests run in their own instrumentation process and are
 * unaffected. The pipe is drained so this returns only after the clear has completed.
 */
class ClearDataRule : TestWatcher() {
    override fun starting(description: Description) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        val pfd: ParcelFileDescriptor =
            instrumentation.uiAutomation.executeShellCommand("pm clear $pkg")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        } catch (_: Exception) {
            // The stream can close early once the package is gone; the clear still happened.
        }
        // Let the package manager settle before the activity launches.
        Thread.sleep(1_000)
    }
}
