plugins {
    alias(libs.plugins.opencode.android.feature)
}

dependencies {
    // The Phase 3 screens are composed in the app module, so the sessions screenshots need the
    // composables they are composed with. Test-only: the feature itself still depends on core alone.
    testImplementation(projects.feature.composer)
    testImplementation(projects.feature.requests)
    // The view-model tests build a real `ServerDataSet`, whose on-device cache is `core:database`'s
    // interface, which the data layer keeps off a feature's compile classpath.
    testImplementation(projects.core.database)
}
