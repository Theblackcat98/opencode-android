plugins {
    alias(libs.plugins.opencode.android.library)
    alias(libs.plugins.opencode.android.room)
    alias(libs.plugins.opencode.android.hilt)
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(projects.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
}
