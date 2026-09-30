plugins {
    alias(libs.plugins.opencode.android.feature)
}

dependencies {
    // The view-model tests drive a real `ServerDataSet` over a MockWebServer, and the set's on-device cache
    // is `core:database`'s interface, which the data layer keeps off a feature's compile classpath.
    testImplementation(projects.core.database)
    testImplementation(libs.okhttp.mockwebserver)
}
