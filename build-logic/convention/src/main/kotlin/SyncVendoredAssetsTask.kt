import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Copies named files out of a vendored directory into a generated asset directory.
 *
 * **This exists because AGP's generated-source-directory API needs a `DirectoryProperty` and Gradle's
 * own copy tasks only expose a `File`.** `core:data` needs the repository's vendored
 * `config.schema.json` inside its assets rather than a second checked-in copy of it, so the file the
 * app validates a configuration against is the file the repository holds — a schema update is one
 * `git pull`, and there is no pair of files that can disagree.
 *
 * The task is [CacheableTask] and the inputs are declared as a path with [PathSensitivity.RELATIVE],
 * so the copy is re-run when a vendored file's *contents* change and not when the checkout moves,
 * which is what makes the asset merge task above it correctly up-to-date across branches.
 */
@CacheableTask
abstract class SyncVendoredAssetsTask : DefaultTask() {

    /** The directory the files are copied from, normally `api/<release>`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val from: DirectoryProperty

    /** Which file names to copy. Names, not globs, so a stray file in the vendored directory is not shipped. */
    @get:Input
    abstract val include: SetProperty<String>

    /**
     * An optional map of name to name, for a file whose asset name differs from its repository name.
     *
     * Left for a caller that needs it; nothing uses it today, which is why it is not a `@get:Input`
     * with a default — a property with no value would fail validation rather than silently do nothing.
     */
    @get:Input
    abstract val rename: MapProperty<String, String>

    /** Where the files land, which AGP registers as a generated asset directory. */
    @get:OutputDirectory
    abstract val into: DirectoryProperty

    /** A human name for the task list, so the log says what it copied. */
    @get:Input
    abstract val label: Property<String>

    @TaskAction
    fun sync() {
        val destination = into.get().asFile
        destination.deleteRecursively()
        destination.mkdirs()
        val source = from.get().asFile
        val renames = rename.get()
        include.get().forEach { name ->
            val file = source.resolve(name)
            require(file.isFile) { "No vendored file $name in ${source.path}" }
            file.copyTo(destination.resolve(renames[name] ?: name), overwrite = true)
        }
        logger.lifecycle("Copied ${include.get().size} ${label.get()} file(s) into ${destination.name}")
    }
}
