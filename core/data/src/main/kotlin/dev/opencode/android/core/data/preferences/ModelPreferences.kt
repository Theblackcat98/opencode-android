package dev.opencode.android.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.opencode.android.core.model.ModelRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.modelPreferences: DataStore<Preferences> by preferencesDataStore(name = "model_preferences")

/**
 * Recents and favorites for the model picker (features doc §8: "Recent and favorite models,
 * F2 cycling — Client. The TUI keeps these locally").
 *
 * **This is the one piece of model state that is the client's, not the server's.** Everything else
 * about a model comes from `model.list`; which ones the user reached for recently, and which ones
 * they pinned, is knowledge the server does not have and must not be asked for. It is stored per
 * server, because a favourite on one server means nothing on another.
 *
 * A [ModelRef] is stored as its own `provider/model#variant` string, the same spelling config files
 * use, so a stored reference is legible in a preferences dump and comparable as a plain string.
 */
interface ModelPreferences {
    /** The most recently used models for a server, newest first. */
    fun recents(serverId: String): Flow<List<ModelRef>>

    /** The pinned models for a server, in the order they were pinned. */
    fun favorites(serverId: String): Flow<List<ModelRef>>

    /** Records a use. Moves the model to the front and trims the list to [MAX_RECENTS]. */
    suspend fun markUsed(serverId: String, model: ModelRef)

    /** Pins a model, or unpins it when it is already pinned. */
    suspend fun toggleFavorite(serverId: String, model: ModelRef)
}

/** The DataStore-backed store the app uses. */
@Singleton
class DataStoreModelPreferences @Inject constructor(
    private val context: Context,
) : ModelPreferences {

    override fun recents(serverId: String): Flow<List<ModelRef>> =
        context.modelPreferences.data.map { it[recentsKey(serverId)].toRefs() }

    override fun favorites(serverId: String): Flow<List<ModelRef>> =
        context.modelPreferences.data.map { it[favoritesKey(serverId)].toRefs() }

    override suspend fun markUsed(serverId: String, model: ModelRef) {
        val key = recentsKey(serverId)
        context.modelPreferences.edit { preferences ->
            val next = (preferences[key].toRefs().filterNot { it == model } + model).takeLast(MAX_RECENTS)
            preferences[key] = next.joinToString(SEPARATOR) { it.toString() }
        }
    }

    override suspend fun toggleFavorite(serverId: String, model: ModelRef) {
        val key = favoritesKey(serverId)
        context.modelPreferences.edit { preferences ->
            val current = preferences[key].toRefs()
            val next = if (current.contains(model)) {
                current - model
            } else {
                current + model
            }
            preferences[key] = next.joinToString(SEPARATOR) { it.toString() }
        }
    }

    private fun String?.toRefs(): List<ModelRef> = this
        ?.split(SEPARATOR)
        ?.filter { it.isNotBlank() }
        ?.mapNotNull(::parseRef)
        .orEmpty()

    /**
     * Reads back a `provider/model#variant` string.
     *
     * A stored value this client cannot read is dropped rather than shown as a broken row: the
     * preference file survives app upgrades, so a format change has to be survivable.
     */
    private fun parseRef(text: String): ModelRef? {
        val head = text.substringBefore('#')
        val variant = text.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val providerID = head.substringBefore('/', "").takeIf { it.isNotEmpty() } ?: return null
        val id = head.substringAfter('/', "").takeIf { it.isNotEmpty() } ?: return null
        return ModelRef(id = id, providerID = providerID, variant = variant)
    }

    private fun recentsKey(serverId: String) = stringPreferencesKey("recents.$serverId")

    private fun favoritesKey(serverId: String) = stringPreferencesKey("favorites.$serverId")

    private companion object {
        /** Long enough to be useful, short enough that the picker is not a wall of entries. */
        const val MAX_RECENTS = 8

        /** Not a character a model id, a provider id or a variant can contain. */
        const val SEPARATOR = "\n"
    }
}
