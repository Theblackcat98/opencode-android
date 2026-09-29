import com.android.build.api.dsl.ApplicationExtension
import dev.opencode.android.buildlogic.NAMESPACE_ROOT
import dev.opencode.android.buildlogic.configureCompose
import dev.opencode.android.buildlogic.configureKotlinAndroid
import dev.opencode.android.buildlogic.configureTests
import dev.opencode.android.buildlogic.libs
import dev.opencode.android.buildlogic.library
import dev.opencode.android.buildlogic.pluginId
import dev.opencode.android.buildlogic.registerTestLifecycleTasks
import dev.opencode.android.buildlogic.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * The application module: Compose, the `distribution` flavor dimension (`play` and `fdroid`),
 * and debug/release build types.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply(libs.pluginId("kotlin-compose"))
        pluginManager.apply("opencode.android.quality")
        extensions.configure<ApplicationExtension> {
            namespace = NAMESPACE_ROOT
            configureKotlinAndroid(this)
            configureCompose(this)
            defaultConfig.apply {
                applicationId = NAMESPACE_ROOT
                targetSdk = libs.version("targetSdk").toInt()
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                vectorDrawables.useSupportLibrary = true
            }
            lint.targetSdk = libs.version("targetSdk").toInt()
            buildFeatures.buildConfig = true

            flavorDimensions += DISTRIBUTION
            productFlavors.apply {
                create("play") {
                    dimension = DISTRIBUTION
                    buildConfigField("String", "DISTRIBUTION", "\"play\"")
                }
                create("fdroid") {
                    dimension = DISTRIBUTION
                    buildConfigField("String", "DISTRIBUTION", "\"fdroid\"")
                }
            }

            buildTypes.apply {
                getByName("debug") {
                    applicationIdSuffix = ".debug"
                    versionNameSuffix = "-debug"
                }
                getByName("release") {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                }
            }
            packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
        }
        configureTests()
        dependencies {
            add("testImplementation", libs.library("junit4"))
            add("testImplementation", libs.library("kotlin-test"))
        }
        registerTestLifecycleTasks("testPlayDebugUnitTest", "testFdroidDebugUnitTest")
        Unit
    }

    private companion object {
        const val DISTRIBUTION = "distribution"
    }
}
