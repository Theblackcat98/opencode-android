import com.diffplug.gradle.spotless.SpotlessExtension
import dev.opencode.android.buildlogic.libs
import dev.opencode.android.buildlogic.projectRoot
import dev.opencode.android.buildlogic.version
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/**
 * ktlint (through Spotless) and detekt, applied to every module (plan §3, Quality).
 *
 * **They were in the version catalog and applied to no module for three phases.** A linter nobody
 * runs is worse than no linter, because the plan claims a check that does not exist; this plugin is
 * what makes plan §3's Quality row true, and it is applied from the library and application
 * convention plugins so a new module cannot forget.
 *
 * The configuration is deliberately narrow, because a release phase cannot afford to spend itself on
 * a reformat that is not about correctness:
 *
 *  - **ktlint's standard ruleset only**, with the project already written against it. The
 *    `ktlint_official` code style is *not* enabled: it disagrees with this codebase about wildcard
 *    imports and blank lines in ways that would be a several-thousand-line reformat of code that
 *    works. What is enforced is what the plan named.
 *  - **detekt's complexity and style rules at the default threshold**, with no baseline. A baseline
 *    is how a "zero findings" claim is faked; every finding here is either fixed or the rule is
 *    turned off in the config below with a reason.
 *
 * `ktlintCheck` and `detekt` both run as part of `check`, so `./gradlew check` fails on them.
 */
class QualityConventionPlugin : org.gradle.api.Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.diffplug.spotless")
        pluginManager.apply("dev.detekt")

        configure<SpotlessExtension> {
            val editorConfig = projectRoot().resolve(".editorconfig")
            // Spotless does not carry ktlint's rule properties across from the editorconfig — it
            // builds the rule set itself and merges only what `editorConfigOverride` supplies, so
            // anything not in this map is not in effect however it is written in `.editorconfig`.
            // That is why the rules are declared here as well as in the editorconfig, and why the
            // editorconfig is written twice over (`[*.kt]` and `[*.kts]`) rather than as
            // `[*.{kt,kts}]`, which is a pattern that matches nothing.
            //
            // Every entry says what it is for. A linter configuration nobody can explain is one
            // nobody dares to change, and a linter nobody dares to change is how it stops being
            // enforced — which is the state the plan's Quality row was in for three phases.
            val rules = mapOf(
                "ktlint_code_style" to "intellij_idea",
                // 140 rather than 120, because at 120 the violations are almost all single-line
                // JSON fixtures in tests and wrapping a fixture makes it harder to check against
                // the spec. Production sources are held to this.
                "max_line_length" to "140",
                // The code is written with one-line data classes and short signatures on purpose; see
                // the same notes in `.editorconfig`.
                "ktlint_standard_class-signature" to "disabled",
                "ktlint_standard_function-signature" to "disabled",
                "ktlint_standard_parameter-list-wrapping" to "disabled",
                // Composable functions are PascalCase by Compose's own convention.
                "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
            )
            kotlin {
                target("src/**/*.kt")
                targetExclude("**/build/**")
                ktlint(libs.version("ktlint"))
                    .setEditorConfigPath(editorConfig)
                    .editorConfigOverride(rules)
            }
            kotlinGradle {
                target("*.gradle.kts")
                target("build-logic/**/*.gradle.kts")
                ktlint(libs.version("ktlint"))
                    .setEditorConfigPath(editorConfig)
                    .editorConfigOverride(rules)
            }
        }

        configure<DetektExtension> {
            buildUponDefaultConfig.set(true)
            allRules.set(false)
            // Findings fail the build. `ignoreFailures` is the switch that turns a linter into a
            // report, and a report nobody reads is the state this phase found the quality row in.
            ignoreFailures.set(false)
            parallel.set(true)
            source.setFrom(files("src/main/java", "src/main/kotlin", "src/test/java", "src/test/kotlin"))
            config.setFrom(
                provider {
                    val root = projectRoot()
                    root.resolve("config/detekt/detekt.yml").takeIf { it.exists() }?.let { files(it) }
                },
            )
        }

        // Detekt parses sources on its own and does not need them compiled, so a Java 21 target is
        // all it has to agree with.
        tasks.withType<Detekt>().configureEach {
            jvmTarget.set("21")
        }
    }
}
