plugins {
    alias(libs.plugins.opencode.android.feature)
}

// ProcessLifecycleOwner, so the connection service and its launcher can tell whether the app is in
// the foreground — which is the exemption Android 12+ requires before a background service may start.
dependencies {
    implementation(libs.androidx.lifecycle.process)
}
