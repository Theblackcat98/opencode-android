plugins {
    alias(libs.plugins.opencode.android.feature)
}

dependencies {
    // The agent, command, skill and reference catalogs are composed by the app module alongside this
    // module's screens, and a feature may not import another feature, so the composition happens in
    // `app` (see `AdminHost`).
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
