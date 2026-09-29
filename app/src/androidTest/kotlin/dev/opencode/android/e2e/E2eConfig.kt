package dev.opencode.android.e2e

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Configuration for the on-device E2E tests, delivered as instrumentation arguments so the same
 * test APK runs against any `opencode serve` without rebuilding.
 *
 * From Gradle: `-Pandroid.testInstrumentationRunnerArguments.<name>=<value>`.
 * From adb: `adb shell am instrument -e <name> <value> -w <test-package>/<runner>`.
 *
 * In CI these are wired by `.github/workflows/instrumented-tests.yml`, which starts
 * `scripts/dev-server.sh` (HTTP) and `scripts/e2e-https.sh` (the same server behind a
 * self-signed TLS terminator) on the runner; the emulator reaches the host at 10.0.2.2.
 */
object E2eConfig {
    /** Plain-HTTP server, e.g. `http://10.0.2.2:4096`. */
    val serverUrl: String get() = requiredArg("serverUrl")

    /** The server's password (Basic auth user is always `opencode`). */
    val serverPassword: String get() = requiredArg("serverPassword")

    /** The same server over HTTPS with a self-signed certificate, e.g. `https://10.0.2.2:4443`. */
    val httpsUrl: String get() = requiredArg("httpsUrl")

    /**
     * True when the self-signed CA was placed in the emulator's user-CA directory
     * (`/data/misc/keychain/cacerts-added`), the on-disk form of what Settings installs.
     * The B5 test runs only then; B6 (refusal without trust) runs only when it is false.
     * The app's network security config trusts system CAs only, so a user CA reaches a
     * server exclusively through its per-server "trust user CAs" toggle.
     */
    val caInstalled: Boolean
        get() = InstrumentationRegistry.getArguments().getString("caInstalled") == "true"

    private fun requiredArg(name: String): String =
        InstrumentationRegistry.getArguments().getString(name)
            ?: error(
                "Missing instrumentation argument '$name'. Run with " +
                    "-Pandroid.testInstrumentationRunnerArguments.$name=<value>."
            )
}
