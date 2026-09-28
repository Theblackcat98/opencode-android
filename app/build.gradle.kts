plugins {
    alias(libs.plugins.opencode.android.application)
    alias(libs.plugins.opencode.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig {
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)

    implementation(projects.feature.servers)
    implementation(projects.feature.sessions)
    implementation(projects.feature.composer)
    implementation(projects.feature.requests)
    implementation(projects.feature.review)
    implementation(projects.feature.execution)
    implementation(projects.feature.integrations)
    implementation(projects.feature.admin)
    implementation(projects.feature.insights)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)

    // The QR decoder is a distribution concern: ML Kit's bundled model in the Play build, ZXing in
    // the F-Droid build so the APK never needs Google Play Services (plan §3).
    "playImplementation"(libs.mlkit.barcode.scanning)
    "fdroidImplementation"(libs.zxing.core)

    testImplementation(projects.core.testing)
}
