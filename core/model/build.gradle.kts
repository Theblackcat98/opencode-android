plugins {
    alias(libs.plugins.opencode.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(projects.core.testing)
}
