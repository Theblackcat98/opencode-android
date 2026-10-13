plugins {
    alias(libs.plugins.opencode.android.feature)
}

dependencies {
    // Draws the pairing QR code (PairingQr.kt). This module has no flavor source sets, so one
    // implementation line reaches both play and fdroid; ZXing is pure Java and needs no Play Services.
    implementation(libs.zxing.core)

    // The view-model test drives a real `ServerDataSet` over a MockWebServer, as the admin and execution modules
    // do. The set's on-device cache is `core:database`'s interface, which the data layer keeps off this classpath.
    testImplementation(projects.core.database)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}
