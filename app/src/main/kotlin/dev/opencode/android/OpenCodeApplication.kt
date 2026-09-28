package dev.opencode.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The application, and where the debug-only behaviour switches are set.
 *
 * [dev.opencode.android.core.data.server.DebugFlags.selfCheckLogs] is read lazily by the Hilt
 * provider, so it has to be set before the first injection happens: this constructor runs before
 * any component, and there is no earlier hook. A release build leaves it off, which is what keeps a
 * transcript out of logcat in a shipped app.
 */
@HiltAndroidApp
class OpenCodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        dev.opencode.android.core.data.server.DebugFlags.selfCheckLogs = BuildConfig.DEBUG
    }
}
