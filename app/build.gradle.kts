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
 * assemble tasks remain available as compile checks. The explicitly named `verifyPublishableRelease`
 * task refuses to run without the material and verifies the generated APK signatures.
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
            // attached when it exists; compile-only assemble tasks remain usable without release keys.
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
 * `assemblePlayRelease` and `assembleFdroidRelease` are compile-only when signing material is absent.
 * `verifyPublishableRelease` is the explicit fail-closed gate for signed APKs. It verifies both
 * signatures and never uploads or publishes.
 */
val releaseMaterialMissing = tasks.register("releaseMaterialMissing") {
    group = "release"
    description = "Explains why unsigned release assembly is compile-only."
    doLast {
        if (!hasReleaseSigning) {
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
}

if (!hasReleaseSigning) {
    tasks.matching { it.name == "assemblePlayRelease" || it.name == "assembleFdroidRelease" }
        .configureEach { dependsOn(releaseMaterialMissing) }
}

val verifyPublishableReleaseRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') == "verifyPublishableRelease"
}
if (verifyPublishableReleaseRequested && !hasReleaseSigning) {
    throw org.gradle.api.GradleException(
        "verifyPublishableRelease requires complete release signing material. " +
            "Set OPENCODE_KEYSTORE, OPENCODE_STORE_PASSWORD, OPENCODE_KEY_ALIAS, and " +
            "OPENCODE_KEY_PASSWORD, or provide the matching keystore.properties values.",
    )
}

val verifyPublishableRelease = tasks.register("verifyPublishableRelease") {
    group = "release"
    description = "Assembles and verifies signed Play and F-Droid release APKs; does not publish them."
    dependsOn("assemblePlayRelease", "assembleFdroidRelease")
    doLast {
        if (!hasReleaseSigning) {
            throw org.gradle.api.GradleException("verifyPublishableRelease requires complete release signing material.")
        }

        val localProperties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.isFile) {
            localPropertiesFile.inputStream().use { localProperties.load(it) }
        }
        val sdkPath = localProperties.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: throw org.gradle.api.GradleException("Android SDK location is required to run apksigner.")
        val sdkRoot = project.rootProject.file(sdkPath)
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val apksignerName = if (isWindows) "apksigner.bat" else "apksigner"
        val apksigner = sdkRoot.resolve("build-tools").listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedByDescending { it.name }
            .map { it.resolve(apksignerName) }
            .firstOrNull { it.isFile }
            ?: throw org.gradle.api.GradleException(
                "Could not find $apksignerName under ${sdkRoot.resolve("build-tools")}.",
            )

        val signedApks = listOf(
            project.layout.buildDirectory.file("outputs/apk/play/release/app-play-release.apk").get().asFile,
            project.layout.buildDirectory.file("outputs/apk/fdroid/release/app-fdroid-release.apk").get().asFile,
        )
        signedApks.forEach { apk ->
            if (!apk.isFile) {
                throw org.gradle.api.GradleException("Signed release APK was not produced: $apk")
            }
            val command = if (isWindows) {
                listOf("cmd", "/c", apksigner.absolutePath, "verify", "--verbose", "--print-certs", apk.absolutePath)
            } else {
                listOf(apksigner.absolutePath, "verify", "--verbose", "--print-certs", apk.absolutePath)
            }
            val exitCode = ProcessBuilder(command).inheritIO().start().waitFor()
            if (exitCode != 0) {
                throw org.gradle.api.GradleException("apksigner rejected $apk")
            }
        }
    }
}

// ------------------------------------------------------------------ the bundled changelog
//
// The in-app changelog is the repository's `docs/CHANGELOG.md`. It is **copied** into this module's assets as
// `changelog.md`, the same way `core:data` copies the config schema, so the screen shows the bytes the release notes
// were written in and there is no second checked-in copy to fall out of date.
val syncChangelog = tasks.register<SyncVendoredAssetsTask>("syncChangelog") {
    label.set("changelog")
    from.set(rootProject.layout.projectDirectory.dir("docs"))
    include.set(setOf("CHANGELOG.md"))
    rename.set(mapOf("CHANGELOG.md" to "changelog.md"))
    into.set(layout.buildDirectory.dir("generated/changelogAsset"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncChangelog, SyncVendoredAssetsTask::into)
    }
}
