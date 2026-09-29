package dev.opencode.android.buildlogic

import org.gradle.api.Project
import java.io.File
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalStateException("Missing version '$alias' in libs.versions.toml") }.requiredVersion

internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalStateException("Missing library '$alias' in libs.versions.toml") }

internal fun VersionCatalog.pluginId(alias: String): String =
    findPlugin(alias).orElseThrow { IllegalStateException("Missing plugin '$alias' in libs.versions.toml") }.get().pluginId

/**
 * The repository root, from any module.
 *
 * A shared configuration file has to be named by an absolute path, because each module resolves a
 * relative one against its own directory and `config/detekt/detekt.yml` would then be looked for
 * thirteen times in thirteen places.
 */
internal fun Project.projectRoot(): File = rootDir
