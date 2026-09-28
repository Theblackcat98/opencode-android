import dev.opencode.android.buildlogic.libs
import dev.opencode.android.buildlogic.library
import dev.opencode.android.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Hilt dependency injection, with annotation processing through KSP. */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("ksp"))
        pluginManager.apply(libs.pluginId("hilt"))
        dependencies {
            add("implementation", libs.library("hilt-android"))
            add("ksp", libs.library("hilt-compiler"))
            add("testImplementation", libs.library("hilt-android-testing"))
            add("kspTest", libs.library("hilt-compiler"))
        }
        Unit
    }
}
