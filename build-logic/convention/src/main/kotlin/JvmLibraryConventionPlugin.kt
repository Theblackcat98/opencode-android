import dev.opencode.android.buildlogic.configureKotlinJvm
import dev.opencode.android.buildlogic.configureTests
import dev.opencode.android.buildlogic.libs
import dev.opencode.android.buildlogic.library
import dev.opencode.android.buildlogic.pluginId
import dev.opencode.android.buildlogic.registerTestLifecycleTasks
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** A pure Kotlin/JVM library (no Android dependencies), e.g. `core:model`. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("kotlin-jvm"))
        pluginManager.apply("opencode.android.quality")
        configureKotlinJvm()
        configureTests()
        dependencies {
            add("testImplementation", libs.library("junit4"))
            add("testImplementation", libs.library("kotlin-test"))
        }
        registerTestLifecycleTasks("test")
        Unit
    }
}
