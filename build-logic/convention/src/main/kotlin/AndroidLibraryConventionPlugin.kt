import com.android.build.api.dsl.LibraryExtension
import dev.opencode.android.buildlogic.configureKotlinAndroid
import dev.opencode.android.buildlogic.configureTests
import dev.opencode.android.buildlogic.defaultNamespace
import dev.opencode.android.buildlogic.libs
import dev.opencode.android.buildlogic.library
import dev.opencode.android.buildlogic.registerTestLifecycleTasks
import dev.opencode.android.buildlogic.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** An Android library without Compose. Kotlin support is built into AGP 9. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            namespace = defaultNamespace
            configureKotlinAndroid(this)
            testOptions.targetSdk = libs.version("targetSdk").toInt()
            lint.targetSdk = libs.version("targetSdk").toInt()
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        configureTests()
        dependencies {
            add("testImplementation", libs.library("junit4"))
            add("testImplementation", libs.library("kotlin-test"))
        }
        registerTestLifecycleTasks("testDebugUnitTest")
        Unit
    }
}
