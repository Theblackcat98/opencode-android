plugins {
    alias(libs.plugins.opencode.android.feature)
}

dependencies {
    // CameraX only: which QR decoder is used is a distribution decision made in the app module's
    // flavor source sets, so this module never links against ML Kit or ZXing.
    // `camera-core` is `api` because QrCodeDecoder's signature exposes ImageProxy to the decoders
    // the app module contributes.
    api(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
}
