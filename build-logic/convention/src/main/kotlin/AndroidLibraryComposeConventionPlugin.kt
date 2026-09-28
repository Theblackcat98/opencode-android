import com.android.build.api.dsl.LibraryExtension
import dev.opencode.android.buildlogic.configureCompose
import dev.opencode.android.buildlogic.libs
import dev.opencode.android.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** An Android library with Jetpack Compose, Robolectric and Roborazzi screenshot tests. */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("opencode.android.library")
        pluginManager.apply(libs.pluginId("kotlin-compose"))
        extensions.configure<LibraryExtension> {
            configureCompose(this)
        }
        Unit
    }
}
