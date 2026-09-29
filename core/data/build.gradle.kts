plugins {
    alias(libs.plugins.opencode.android.library)
    alias(libs.plugins.opencode.android.hilt)
    // The attention layer's action union rides in an Intent's extras as JSON, so this module's
    // serialisable types are compiled with the plugin rather than reaching for reflection.
    alias(libs.plugins.kotlin.serialization)
}

// ServerConnection, EventDispatcher, SyncedResource stores, TimelineReducer and RequestCenter
// arrive in P1 to P3.
dependencies {
    api(projects.core.model)
    api(projects.core.network)
    api(libs.okhttp)
    // Retrofit is on core:data's classpath because core:network's public API is a Retrofit
    // interface, and the data layer is where a transport failure becomes a domain `ActionError`.
    api(libs.retrofit)
    implementation(projects.core.database)
    implementation(libs.kotlinx.coroutines.core)
    // The model picker's recents and favorites are the one piece of model state the client owns
    // (features doc §8), and it is a small string-per-server map rather than a table.
    implementation(libs.androidx.datastore.preferences)
    // ProcessLifecycleOwner, so a connection follows the app's foreground state without every
    // screen having to report its own.
    implementation(libs.androidx.lifecycle.process)

    testImplementation(projects.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.robolectric)
}

// ------------------------------------------------------------------ the vendored config schema
//
// The configuration editor validates `opencode.jsonc` against `api/opencode-2.0.x/config.schema.json`,
// which Phase 0 vendored for exactly this. It is **copied** into this module's assets rather than
// duplicated as a checked-in resource, so the bytes the app validates against are the repository's
// bytes and a schema update is one `git pull` rather than two edits that can disagree.
//
// `SyncVendoredAssetsTask` is used rather than `Copy` because AGP's generated-source-directory API
// needs a `DirectoryProperty` output and Gradle's copy tasks only expose a `File`; registering the
// directory through `variant.sources.assets` is also what makes the dependency explicit, so a clean
// checkout cannot fail later at packaging with an asset-not-found that says nothing about the cause.
val syncConfigSchema = tasks.register<SyncVendoredAssetsTask>("syncConfigSchema") {
    label.set("vendored configuration schema")
    from.set(rootProject.layout.projectDirectory.dir("api/opencode-2.0.x"))
    include.set(setOf("config.schema.json"))
    rename.set(emptyMap())
    into.set(layout.buildDirectory.dir("generated/configSchema"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncConfigSchema, SyncVendoredAssetsTask::into)
    }
}
