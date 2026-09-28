plugins {
    alias(libs.plugins.opencode.android.library.compose)
}

dependencies {
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.text)
    implementation(libs.androidx.core.ktx)
}
