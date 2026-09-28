package dev.opencode.android.buildlogic

import com.android.build.api.dsl.CommonExtension
import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

internal fun Project.configureCompose(extension: CommonExtension) {
    extension.buildFeatures.compose = true
    pluginManager.apply(libs.pluginId("roborazzi"))

    dependencies {
        val bom = libs.library("androidx-compose-bom")
        add("implementation", platform(bom))
        add("testImplementation", platform(bom))
        add("androidTestImplementation", platform(bom))
        add("implementation", libs.library("androidx-compose-ui-tooling-preview"))
        add("debugImplementation", libs.library("androidx-compose-ui-tooling"))

        add("testImplementation", libs.library("robolectric"))
        add("testImplementation", libs.library("roborazzi"))
        add("testImplementation", libs.library("roborazzi-compose"))
        add("testImplementation", libs.library("roborazzi-junit-rule"))
        add("testImplementation", libs.library("androidx-compose-ui-test-junit4"))
        add("testImplementation", libs.library("androidx-test-core"))
        add("testImplementation", libs.library("androidx-test-ext-junit"))
        add("debugImplementation", libs.library("androidx-compose-ui-test-manifest"))
    }

    extensions.configure<RoborazziExtension> {
        outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
    }

    tasks.withType(Test::class.java).configureEach {
        // Deterministic software rendering for screenshots.
        systemProperty("robolectric.graphicsMode", "NATIVE")
        systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    }
}
