plugins {
    alias(libs.plugins.opencode.android.feature)
}

dependencies {
    // Draws the pairing QR code (PairingQr.kt). This module has no flavor source sets, so one
    // implementation line reaches both play and fdroid; ZXing is pure Java and needs no Play Services.
    implementation(libs.zxing.core)
}
