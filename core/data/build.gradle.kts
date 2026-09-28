plugins {
    alias(libs.plugins.opencode.android.library)
    alias(libs.plugins.opencode.android.hilt)
}

// ServerConnection, EventDispatcher, SyncedResource stores, TimelineReducer and RequestCenter
// arrive in P1 to P3.
dependencies {
    api(projects.core.model)
    implementation(projects.core.network)
    implementation(projects.core.database)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(projects.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
