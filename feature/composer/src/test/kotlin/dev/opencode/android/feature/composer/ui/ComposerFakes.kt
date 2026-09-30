package dev.opencode.android.feature.composer.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.opencode.android.core.data.composer.ComposerMemory
import dev.opencode.android.core.data.composer.StashEntry
import dev.opencode.android.core.data.preferences.ModelPreferences
import dev.opencode.android.core.model.ModelRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

/**
 * The composer's on-device memory with nothing in it but one saved draft.
 *
 * The draft is what `open` ends with — it restores it after the catalogs have been asked for — so a test
 * that gives the box a draft can tell, from the state alone, that `open` has finished.
 */
class FakeComposerMemory(private val draft: String = "") : ComposerMemory {
    override fun history(serverId: String): Flow<List<String>> = flowOf(emptyList())

    override fun stash(serverId: String): Flow<List<StashEntry>> = flowOf(emptyList())

    override fun draft(serverId: String, sessionId: String): Flow<String> = flowOf(draft)

    override suspend fun setDraft(serverId: String, sessionId: String, text: String) = Unit

    override suspend fun record(serverId: String, text: String) = Unit

    override suspend fun pushStash(serverId: String, entry: StashEntry) = Unit

    override suspend fun popStash(serverId: String): StashEntry? = null

    override suspend fun dropStash(serverId: String, id: String) = Unit
}

/** Model recents and favorites that are always empty. */
object FakeModelPreferences : ModelPreferences {
    override fun recents(serverId: String): Flow<List<ModelRef>> = flowOf(emptyList())

    override fun favorites(serverId: String): Flow<List<ModelRef>> = flowOf(emptyList())

    override suspend fun markUsed(serverId: String, model: ModelRef) = Unit

    override suspend fun toggleFavorite(serverId: String, model: ModelRef) = Unit
}

/** A picker that has nothing to hand over: the view model is built with one, and these tests never attach. */
object NoImages : ImageSource {
    override fun open(uri: Uri): InputStream? = null

    override fun type(uri: Uri): String? = null

    override fun name(uri: Uri): String? = null
}

/**
 * The view models a test made, so that tearing the test down stops them.
 *
 * **A view model built with `ComposerViewModel(…)` is never cleared, and its `viewModelScope` outlives the
 * test that made it.** That leaked straight into the next test: a collector still running when the class's
 * `tearDown` called `Dispatchers.resetMain()` resumed on the next test's `runTest`, and
 * `UncaughtExceptionsBeforeTest` failed *that* test — so `ComposerSendTest` and
 * `NewSessionReactivityTest` each failed intermittently, and on a clean tree, over code neither touches.
 * A real `ViewModelStore` is what clears a view model on a device, so these tests now take them from one:
 * [clear] runs `onCleared`, which cancels `viewModelScope`, and the collector dies with the test that
 * started it rather than the next one.
 *
 * `feature/execution` does this for its terminal tests; this is the same arrangement for the composer.
 */
class ComposerViewModels : AutoCloseable {

    private val store = ViewModelStore()

    /** A composer of this class, held in the store so [clear] cancels it. */
    fun composer(
        server: ComposerServer,
        draft: String = "",
    ): ComposerViewModel = put("composer-${next++}", ComposerViewModel::class.java) {
        ComposerViewModel(
            active = MutableStateFlow(server.set),
            modelPreferences = FakeModelPreferences,
            memory = FakeComposerMemory(draft),
            attachmentReader = AttachmentReader(NoImages),
        )
    }

    /** The new-session sheet, held in the store for the same reason. */
    fun newSession(server: ComposerServer): NewSessionViewModel = put("new-session-${next++}", NewSessionViewModel::class.java) {
        NewSessionViewModel(MutableStateFlow(server.set), FakeModelPreferences)
    }

    /** What a test's `tearDown` calls before `Dispatchers.resetMain()`. */
    fun clear() = store.clear()

    override fun close() = clear()

    private var next = 0

    /** Through a [ViewModelProvider], which is what puts the result in the store a [clear] empties. */
    private fun <T : ViewModel> put(key: String, type: Class<T>, build: () -> T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = build() as VM
        }
        return ViewModelProvider(store, factory).get(key, type)
    }
}

/** How long a test waits for a state that is meant to arrive, so a state that never does fails rather than hangs. */
private const val AWAIT_MILLIS = 3_000L

/**
 * Waits until [predicate] holds, on the wall clock.
 *
 * The loads under test are real requests answered from another thread, so virtual time cannot say when
 * they are done: `runTest` would skip straight past a timeout that is measuring a socket. The failure
 * names what was awaited and what the state was instead, because a timeout on its own says nothing.
 */
suspend fun <T> StateFlow<T>.await(what: String, predicate: (T) -> Boolean): T =
    withContext(Dispatchers.Default) { withTimeoutOrNull(AWAIT_MILLIS) { first(predicate) } }
        ?: throw AssertionError("Timed out waiting for $what; the state stayed $value")
