package dev.opencode.android.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/** Root package for every module's namespace. Provisional until the app identity is decided (plan §10). */
internal const val NAMESPACE_ROOT = "dev.opencode.android"

/** Namespace derived from the Gradle path: `:core:model` -> `dev.opencode.android.core.model`. */
internal val Project.defaultNamespace: String
    get() = NAMESPACE_ROOT + path.replace(':', '.').replace('-', '_')

internal fun Project.configureKotlinAndroid(extension: CommonExtension) {
    val jvmTarget = libs.version("jvmTarget")
    extension.apply {
        compileSdk = libs.version("compileSdk").toInt()
        defaultConfig.minSdk = libs.version("minSdk").toInt()
        compileOptions.sourceCompatibility = JavaVersion.toVersion(jvmTarget)
        compileOptions.targetCompatibility = JavaVersion.toVersion(jvmTarget)
        testOptions.unitTests.isIncludeAndroidResources = true
        testOptions.unitTests.isReturnDefaultValues = false
        lint.apply {
            warningsAsErrors = false
            abortOnError = true
            checkDependencies = false
            xmlReport = true
            sarifReport = true
            disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        }
    }
    configureKotlinCompiler(jvmTarget)
}

internal fun Project.configureKotlinJvm() {
    val jvmTarget = libs.version("jvmTarget")
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.toVersion(jvmTarget)
        targetCompatibility = JavaVersion.toVersion(jvmTarget)
    }
    configureKotlinCompiler(jvmTarget)
}

private fun Project.configureKotlinCompiler(jvmTarget: String) {
    tasks.withType(KotlinCompilationTask::class.java).configureEach {
        compilerOptions {
            if (this is KotlinJvmCompilerOptions) {
                this.jvmTarget.set(JvmTarget.fromTarget(jvmTarget))
            }
            allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map(String::toBoolean).orElse(false))
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }
}
