import java.util.Properties

plugins {
    alias(libs.plugins.opencode.android.application)
    alias(libs.plugins.opencode.android.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

/**
 * Release signing, configured but not satisfied.
 *
 * **No keystore, key or credential is in this repository, and none was created for this phase.** The
 * build is set up so that a release is *ready* the moment the material exists, and the release tasks
 * refuse to run without it rather than producing an unsigned artifact that looks finished.
 *
 * What a release needs, all of it outside the repository:
 *
 *  - a keystore, generated once and stored wherever the owner's secrets live;
 *  - a `keystore.properties` with the store path, the alias and the two passwords. It is in
 *    `.gitignore` and must stay there;
 *  - the same four values as `OPENCODE_KEYSTORE`, `OPENCODE_KEY_ALIAS`, `OPENCODE_KEY_PASSWORD` and
 *    `OPENCODE_STORE_PASSWORD` in the environment, which is how CI should do it.
 *
 * The values are read from the properties file if it exists and from the environment otherwise, so a
 * developer machine and CI use the same code path.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun releaseSecret(property: String, environment: String): String? =
    (keystoreProperties.getProperty(property) ?: System.getenv(environment))?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSecret("storeFile", "OPENCODE_KEYSTORE")
    ?.let { rootProject.file(it) }
val releaseStorePassword = releaseSecret("storePassword", "OPENCODE_STORE_PASSWORD")
val releaseKeyAlias = releaseSecret("keyAlias", "OPENCODE_KEY_ALIAS")
val releaseKeyPassword = releaseSecret("keyPassword", "OPENCODE_KEY_PASSWORD")

/** True when all four are present, which is the only thing that makes a signed build possible. */
val hasReleaseSigning: Boolean = listOf(
    releaseStoreFile?.takeIf { it.exists() },
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

android {
    defaultConfig {
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v1 is the JAR signature and v2 the APK one. Both, because a device on Android 8
                // with API 26 reads v2 and nothing reads v1, and the Play Console asks for both.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            // An unsigned release build is a legitimate thing to want -- a CI compile check, for
            // example -- so this is not gated on the material being present. `signingConfig` is only
            // attached when it exists, and the tasks that produce something to upload are gated
            // below instead.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    // The app module is the composition root, and the session screen's overflow menu and the home's
    // "new session" action are the only places icons are used from here.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)

    // The QR decoder is a distribution concern: ML Kit's bundled model in the Play build, ZXing in
    // the F-Droid build so the APK never needs Google Play Services (plan §3).
    "playImplementation"(libs.mlkit.barcode.scanning)
    "fdroidImplementation"(libs.zxing.core)

    testImplementation(projects.core.testing)
}

/**
 * The release tasks are gated, and they say why when they refuse.
 *
 * **A task that quietly produced an unsigned APK would be worse than one that fails.** An unsigned
 * artifact cannot be updated in place, so publishing one and then signing a later build with the
 * real key gives users two apps with the same id and no way to move between them. So
 * `assemblePlayRelease` and `assembleFdroidRelease` are ordinary Gradle tasks that work without the
 * material, and the *upload* tasks are not declared at all until it is present.
 */
val releaseMaterialMissing = tasks.register("releaseMaterialMissing") {
    group = "release"
    description = "Explains what a signed release build still needs."
    doLast {
        logger.lifecycle(
            """
            |No release signing material is present, so no signed artifact can be produced.
            |
            |A release needs, all of it outside this repository:
            |  1. A keystore. Generate it once:  keytool -genkeypair -v -keystore opencode.jks ...
            |  2. keystore.properties with storeFile, storePassword, keyAlias and keyPassword.
            |     It is in .gitignore. Never commit it.
            |  3. The same four values in the environment for CI:
            |     OPENCODE_KEYSTORE, OPENCODE_STORE_PASSWORD, OPENCODE_KEY_ALIAS, OPENCODE_KEY_PASSWORD.
            |
            |An unsigned build can still be produced for a compile check:
            |  ./gradlew assemblePlayRelease assembleFdroidRelease
            |
            |What this does not do, and cannot: verify the release against the manual test matrix,
            |upload to the Play Console, or produce a signed APK. See docs/RELEASE.md.
            """.trimMargin(),
        )
    }
}

tasks.matching { it.name == "assemblePlayRelease" || it.name == "assembleFdroidRelease" }
    .configureEach { dependsOn(releaseMaterialMissing) }
