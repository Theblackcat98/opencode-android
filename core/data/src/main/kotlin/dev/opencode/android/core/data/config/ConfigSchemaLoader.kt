package dev.opencode.android.core.data.config

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the vendored `config.schema.json` out of the app's assets.
 *
 * **The file is vendored, not fetched, and that is the point.** Validating a configuration edit needs
 * the schema; fetching it would mean asking a server on a LAN for a URL on the internet, which fails
 * on exactly the network this app is built for, and it would mean validating against whatever the
 * server felt like sending rather than against the release this client was built for. The build
 * copies `api/opencode-2.0.x/config.schema.json` into `core/data`'s assets, so the bytes are the
 * repository's, and `ConfigSchemaPackagingTest` proves the packaged copy is that same file.
 *
 * **Parsing happens once and the result is shared.** The file is 39 KB of JSON with 1,385 local
 * references; re-parsing it per keystroke of a template would be the single most expensive thing the
 * editor does, and nothing about it changes while the process lives.
 */
interface ConfigSchemaLoader {
    /** The parsed schema. Throws if the asset is missing or unreadable. */
    fun load(): ConfigSchema
}

/** The asset-backed loader the app uses. */
@Singleton
class AssetConfigSchemaLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConfigSchemaLoader {

    override fun load(): ConfigSchema {
        val text = context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        return ConfigSchema.parse(text)
    }

    companion object {
        /** The asset name, which is the vendored file's own name. */
        const val ASSET = "config.schema.json"
    }
}

/**
 * A loader that answers the schema it is given, which is what the tests substitute.
 *
 * **It exists so the validator and the explorer can be tested against the real vendored file without
 * a `Context`**, and so a test can prove the asset loader and this one produce the same key list.
 */
class FixedConfigSchemaLoader(private val schema: ConfigSchema) : ConfigSchemaLoader {
    override fun load(): ConfigSchema = schema
}

@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    /**
     * The asset loader behind [ConfigSchemaLoader].
     *
     * A `@Provides` rather than a `@Binds`, because the interface is a seam the tests substitute: a
     * JVM test cannot construct an `AssetManager`, and a seam that needs an `AssetManager` is not a
     * seam. Everything downstream depends on the interface, so replacing this one binding replaces the
     * schema for the whole graph.
     */
    @Provides
    @Singleton
    fun provideConfigSchemaLoader(loader: AssetConfigSchemaLoader): ConfigSchemaLoader = loader

    /**
     * The vendored schema, parsed once.
     *
     **Provided as a singleton so every screen in `feature/admin` reads the same key list.** The
     * alternative — each screen constructing one — would mean a hot asset change could give two
     * screens different rows for the same key, and the tests substitute the loader rather than the
     * schema precisely so there is one seam.
     */
    @Provides
    @Singleton
    fun provideConfigSchema(loader: ConfigSchemaLoader): ConfigSchema = loader.load()
}
