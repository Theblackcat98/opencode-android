plugins {
    alias(libs.plugins.opencode.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Fixtures recorded from real servers (src/main/resources/fixtures), their loader, and the
// live-server harness used by integration tests. Test-only: never an implementation dependency.
dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.junit4)
    implementation(libs.okhttp)

    testImplementation(projects.core.model)
}
