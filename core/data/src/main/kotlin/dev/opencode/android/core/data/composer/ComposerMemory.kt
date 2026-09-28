package dev.opencode.android.core.data.composer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable

private val Context.composerPreferences: DataStore<Preferences> by preferencesDataStore(name = "composer")

/** One stashed prompt: something the user set aside to come back to. */
@Serializable
data class StashEntry(
    val id: String,
    val text: String,
    val created: Long,
)

/**
 * Where the composer is in the prompt history, and the text that belongs where it is.
 *
 * Index `0` is the live draft and index `n` is the `n`-th most recent prompt, which is the
 * direction a "older" button walks. [draft] holds the live text while the cursor is away from it,
 * so walking back and forward returns the user to what they were writing rather than to an empty
 * box — which is the behaviour that makes history usable at all.
 */
data class HistoryCursor(
    val index: Int = 0,
    val draft: String = "",
) {
    /** The text this position shows. */
    fun text(history: List<String>): String =
        if (index <= 0) draft else history.getOrNull(index - 1).orEmpty()
}

/**
 * Prompt history and stash, the two pieces of composer state the server knows nothing about.
 *
 * **Both are the client's, deliberately.** The server has no concept of a draft or a stash, and
 * inventing one server-side would mean a state the TUI cannot see. History is per server because a
 * prompt is a fact about a project's way of working; a draft is per session because that is where
 * the user is typing it.
 */
object PromptHistory {

    /** How many past prompts are kept. Far more than anyone scrolls, few enough to stay a string. */
    const val LIMIT = 50

    /** Newest first, with the prompt just sent moved to the front and duplicates collapsed. */
    fun record(history: List<String>, text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return history
        return (listOf(trimmed) + history.filterNot { it == trimmed }).take(LIMIT)
    }

    /** One step towards older prompts, remembering the live text on the way out. */
    fun older(history: List<String>, cursor: HistoryCursor): HistoryCursor {
        if (cursor.index >= history.size) return cursor
        val draft = if (cursor.index == 0) cursor.text(history) else cursor.draft
        return cursor.copy(index = cursor.index + 1, draft = draft)
    }

    /** One step towards the live draft. */
    fun newer(history: List<String>, cursor: HistoryCursor): HistoryCursor =
        if (cursor.index <= 0) cursor else cursor.copy(index = cursor.index - 1)
}

/**
 * The stash: prompts set aside, and put back.
 *
 * Newest first, so "pop" is the most recently stashed thing, which is what someone who just stashed
 * something expects. The same text is not stashed twice: stashing, sending and stashing again is a
 * loop a person falls into, and two identical entries in the list is a way of losing one of them.
 */
object Stash {

    /** How many stashed prompts are kept before the oldest is dropped. */
    const val LIMIT = 20

    fun push(entries: List<StashEntry>, entry: StashEntry): List<StashEntry> {
        if (entry.text.isBlank()) return entries
        return (listOf(entry) + entries.filterNot { it.text == entry.text }).take(LIMIT)
    }

    /** Removes and returns the most recent entry, or `null` when the stash is empty. */
    fun pop(entries: List<StashEntry>): Pair<List<StashEntry>, StashEntry?> {
        val head = entries.firstOrNull() ?: return entries to null
        return entries.drop(1) to head
    }

    fun drop(entries: List<StashEntry>, id: String): List<StashEntry> = entries.filterNot { it.id == id }
}

/**
 * The composer's client-side memory: per-session drafts, per-server history and the stash.
 *
 * The same shape as [dev.opencode.android.core.data.attention.AttentionPreferences] and for the same
 * reason: a handful of strings per server and per session, never queried, never synced, and not
 * worth a table in what is otherwise a cache of the server's projection.
 *
 * A value this build cannot read is dropped rather than guessed at. The file outlives the build that
 * wrote it, so a format change has to be survivable, and a stash entry that no longer parses is
 * better lost than restored as something the user did not write.
 */
interface ComposerMemory {
    /** The sent prompts of one server, newest first. */
    fun history(serverId: String): Flow<List<String>>

    /** The stashed prompts of one server, newest first. */
    fun stash(serverId: String): Flow<List<StashEntry>>

    /** The unsent text of one session, which is what survives leaving the screen. */
    fun draft(serverId: String, sessionId: String): Flow<String>

    suspend fun setDraft(serverId: String, sessionId: String, text: String)

    /** Puts a sent prompt at the front of this server's history. */
    suspend fun record(serverId: String, text: String)

    suspend fun pushStash(serverId: String, entry: StashEntry)

    /** Takes the most recent stashed prompt out of the stash and returns it. */
    suspend fun popStash(serverId: String): StashEntry?

    suspend fun dropStash(serverId: String, id: String)
}

/** The DataStore-backed store the app uses. */
@Singleton
class DataStoreComposerMemory @Inject constructor(
    private val context: Context,
) : ComposerMemory {

    override fun history(serverId: String): Flow<List<String>> =
        context.composerPreferences.data.map { preferences ->
            preferences[historyKey(serverId)]?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        }

    override fun stash(serverId: String): Flow<List<StashEntry>> =
        context.composerPreferences.data.map { preferences ->
            preferences[stashKey(serverId)]?.let(::decodeStash) ?: emptyList()
        }

    override fun draft(serverId: String, sessionId: String): Flow<String> =
        context.composerPreferences.data.map { it[draftKey(serverId, sessionId)] ?: "" }

    override suspend fun setDraft(serverId: String, sessionId: String, text: String) {
        val key = draftKey(serverId, sessionId)
        context.composerPreferences.edit { preferences ->
            if (text.isEmpty()) preferences.remove(key) else preferences[key] = text
        }
    }

    override suspend fun record(serverId: String, text: String) {
        val key = historyKey(serverId)
        context.composerPreferences.edit { preferences ->
            val current = preferences[key]?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            val updated = PromptHistory.record(current, text)
            if (updated.isEmpty()) preferences.remove(key) else preferences[key] = updated.joinToString("\n")
        }
    }

    override suspend fun pushStash(serverId: String, entry: StashEntry) {
        val key = stashKey(serverId)
        context.composerPreferences.edit { preferences ->
            val updated = Stash.push(preferences[key]?.let(::decodeStash).orEmpty(), entry)
            preferences[key] = encodeStash(updated)
        }
    }

    override suspend fun popStash(serverId: String): StashEntry? {
        val key = stashKey(serverId)
        var popped: StashEntry? = null
        context.composerPreferences.edit { preferences ->
            val (remaining, head) = Stash.pop(preferences[key]?.let(::decodeStash).orEmpty())
            popped = head
            if (remaining.isEmpty()) preferences.remove(key) else preferences[key] = encodeStash(remaining)
        }
        return popped
    }

    override suspend fun dropStash(serverId: String, id: String) {
        val key = stashKey(serverId)
        context.composerPreferences.edit { preferences ->
            val remaining = Stash.drop(preferences[key]?.let(::decodeStash).orEmpty(), id)
            if (remaining.isEmpty()) preferences.remove(key) else preferences[key] = encodeStash(remaining)
        }
    }

    private fun historyKey(serverId: String): Preferences.Key<String> = stringPreferencesKey("history_$serverId")

    private fun stashKey(serverId: String): Preferences.Key<String> = stringPreferencesKey("stash_$serverId")

    private fun draftKey(serverId: String, sessionId: String): Preferences.Key<String> =
        stringPreferencesKey("draft_$serverId:$sessionId")

    private fun encodeStash(entries: List<StashEntry>): String =
        OpenCodeJson.encodeToString(StashListSerializer, StashList(entries))

    private fun decodeStash(raw: String): List<StashEntry> =
        runCatching { OpenCodeJson.decodeFromString(StashListSerializer, raw).entries }
            // A stash that cannot be read is a stash the user cannot get back anyway, and refusing
            // to decode it must not take the composer's other settings with it.
            .getOrDefault(emptyList())
}

@Serializable
internal data class StashList(val entries: List<StashEntry> = emptyList())

internal val StashListSerializer = StashList.serializer()
