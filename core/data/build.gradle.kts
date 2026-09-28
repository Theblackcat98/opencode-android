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
    // Retrofit is on core:data's classpath because core:network's public API is a Retrofit
    // interface, and the data layer is where a transport failure becomes a domain `ActionError`.
    api(libs.retrofit)
    implementation(projects.core.database)
    implementation(libs.kotlinx.coroutines.core)
    // The model picker's recents and favorites are the one piece of model state the client owns
    // (features doc §8), and it is a small string-per-server map rather than a table.
    implementation(libs.androidx.datastore.preferences)
    // ProcessLifecycleOwner, so a connection follows the app's foreground state without every
    // screen having to report its own.
    implementation(libs.androidx.lifecycle.process)

    testImplementation(projects.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.robolectric)
}
