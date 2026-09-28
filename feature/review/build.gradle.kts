plugins {
    alias(libs.plugins.opencode.android.feature)
}

dependencies {
    // The composer's comment chips and the session screen's staged-revert banner live in other
    // features, and a feature may not import another feature (see the feature convention plugin), so
    // the review module takes the same test-only arrangement Phase 3 used for the session screen.
    testImplementation(projects.feature.composer)
    testImplementation(projects.feature.sessions)
    testImplementation(projects.core.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
