plugins {
    alias(libs.plugins.opencode.android.feature)
}

dependencies {
    // The agent, command, skill and reference catalogs are composed by the app module alongside this
    // module's screens, and a feature may not import another feature, so the composition happens in
    // `app` (see `AdminHost`).
    //
    // The view-model tests drive a real `ServerDataSet` over a MockWebServer, and the set's on-device cache is
    // `core:database`'s interface, which the data layer keeps off a feature's compile classpath.
    testImplementation(projects.core.database)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
