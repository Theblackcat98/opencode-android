plugins {
    alias(libs.plugins.opencode.android.library)
    alias(libs.plugins.opencode.android.hilt)
}

// ServerConnection, EventDispatcher, SyncedResource stores, TimelineReducer and RequestCenter
// arrive in P1 to P3.
dependencies {
    api(projects.core.model)
    api(projects.core.network)
    api(libs.okhttp)
    implementation(projects.core.database)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(projects.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.robolectric)
}
