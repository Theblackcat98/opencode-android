pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's mirror of Maven Central, tried first: Maven Central rate-limits bursts of
        // parallel requests (HTTP 429), which fails cold builds in CI and cloud containers.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "MavenCentralMirror"
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's mirror of Maven Central, tried first: Maven Central rate-limits bursts of
        // parallel requests (HTTP 429), which fails cold builds in CI and cloud containers.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "MavenCentralMirror"
        }
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "opencode-android"

include(":app")

include(":core:model")
include(":core:network")
include(":core:data")
include(":core:database")
include(":core:designsystem")
include(":core:testing")

include(":feature:servers")
include(":feature:sessions")
include(":feature:composer")
include(":feature:requests")
include(":feature:review")
include(":feature:execution")
include(":feature:integrations")
include(":feature:admin")
include(":feature:insights")
