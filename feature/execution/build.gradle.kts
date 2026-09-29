plugins {
    alias(libs.plugins.opencode.android.feature)
}

android {
    sourceSets {
        // The terminal page and xterm.js are assets of this module, so they ship in the AAR and in the
        // APK alike. They are declared here rather than discovered, because an asset this module does
        // not own — a leftover from a previous build — must not be able to reach a WebView.
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    // The composer's subagent strip and the timeline's subagent card are composed by the app module,
    // and a feature may not import another feature (see the feature convention plugin), so this module
    // takes the same test-only arrangement Phase 3 and Phase 6 used.
    testImplementation(projects.core.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
