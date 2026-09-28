package dev.opencode.android.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

/**
 * Integration tests live in any package segment named `integration` and run against a real
 * `opencode serve` started by `scripts/dev-server.sh`. They are excluded from ordinary test runs
 * and are the only tests run when the build is invoked with an `integrationTest` task
 * (or `-Popencode.integration=true`).
 */
internal val Project.integrationMode: Boolean
    get() = providers.gradleProperty("opencode.integration").map(String::toBoolean).getOrElse(false) ||
        gradle.startParameter.taskNames.any { it.substringAfterLast(':') == INTEGRATION_TASK }

internal const val INTEGRATION_TASK = "integrationTest"
private const val INTEGRATION_PATTERN = "*.integration.*"

/**
 * Environment written by scripts/dev-server.sh, forwarded to integration tests as system properties.
 *
 * A Gradle property of the same name wins, because a long-lived daemon does not reliably see the
 * environment of a client that started after it: without the property, a local integration run
 * silently skips instead of failing. CI sets the environment and keeps working; a developer on the
 * command line passes `-Popencode.it.url=... -Popencode.it.password=...` and is sure of the run.
 */
private val INTEGRATION_ENV = mapOf(
    "OPENCODE_URL" to "opencode.it.url",
    "OPENCODE_PASSWORD" to "opencode.it.password",
    "OPENCODE_DIRECTORY" to "opencode.it.directory",
    "OPENCODE_VERSION" to "opencode.it.version",
    "FAKE_PROVIDER_URL" to "opencode.it.fakeProviderUrl",
)

internal fun Project.configureTests() {
    val integration = integrationMode
    tasks.withType(Test::class.java).configureEach {
        testLogging {
            events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
            exceptionFormat = TestExceptionFormat.FULL
            showStackTraces = true
        }
        maxHeapSize = "2g"
        jvmArgs(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        )
        filter.isFailOnNoMatchingTests = false
        if (integration) {
            filter.includeTestsMatching(INTEGRATION_PATTERN)
            INTEGRATION_ENV.forEach { (env, property) ->
                val fromProperty = providers.gradleProperty(property).orNull
                val value = fromProperty ?: providers.environmentVariable(env).orNull
                if (value != null) systemProperty(property, value)
            }
            // A live server is not a cacheable input.
            outputs.upToDateWhen { false }
        } else {
            filter.excludeTestsMatching(INTEGRATION_PATTERN)
        }
    }
}

internal const val UNIT_TEST_TASK = "unitTest"

/**
 * `unitTest` runs one variant's JVM tests per module (debug for libraries, both flavors' debug for
 * the app), which is what CI and developers normally want. `integrationTest` runs the same tasks in
 * integration mode.
 */
internal fun Project.registerTestLifecycleTasks(vararg testTasks: String) {
    tasks.register(UNIT_TEST_TASK) {
        group = "verification"
        description = "Runs the JVM unit tests (Robolectric included) of the debug variant."
        dependsOn(*testTasks)
    }
    tasks.register(INTEGRATION_TASK) {
        group = "verification"
        description = "Runs integration tests against the server started by scripts/dev-server.sh."
        dependsOn(*testTasks)
    }
}
