plugins {
    alias(libs.plugins.opencode.android.feature)
}

dependencies {
    // The MCP resource picker is composed by the app module alongside the composer's, and a feature
    // may not import another feature, so this module takes the same test-only arrangement Phase 3
    // and Phase 6 used.
    testImplementation(projects.core.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
