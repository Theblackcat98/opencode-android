package dev.opencode.android.core.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a [SyncedResource] knows about its own freshness. */
sealed interface SyncStatus {
    /** Never loaded, and not loading. */
    data object Idle : SyncStatus

    /** A load is running. */
    data object Loading : SyncStatus

    /** A load ran and failed. The error is a transport or decoding failure, not an API error. */
    data class Failed(val error: Throwable) : SyncStatus

    /** The value is current: it came from this client's own load or from an authoritative event. */
    data object Ready : SyncStatus
}

/** One resource's observable state. */
data class SyncedState<T>(
    val value: T? = null,
    val status: SyncStatus = SyncStatus.Idle,
) {
    val isLoading: Boolean get() = status is SyncStatus.Loading
    val hasValue: Boolean get() = value != null
}

/**
 * A value the server owns, kept in step with it (plan §4.2, "Stores").
 *
 * The contract the whole read path rests on:
 *
 *  - **The client never guesses.** [value] only ever changes from a successful [loader] call or
 *    from [complete], which a catalog store calls when an authoritative event has already told
 *    it the answer.
 *  - **Invalidation is cheap and coalescing.** An empty-payload `*.updated` event marks the
 *    resource stale; the refetch is debounced by [invalidateDebounceMillis], so a burst of events
 *    costs one request. Loads are serialized by a mutex, and staleness is consumed when a load
 *    *starts* rather than when it ends, so an invalidation that lands mid-load is never swallowed.
 *  - **A `server.connected` resync is a plain invalidation**, which is why the resync path needs
 *    no special case.
 *
 * [key] is the `(server, directory)` the value belongs to. Nothing inside the resource reads it;
 * it exists so a cache and a debug log can name the resource's scope.
 */
class SyncedResource<T>(
    val key: ResourceKey,
    val name: String,
    private val scope: CoroutineScope,
    private val loader: suspend () -> T,
    private val invalidateDebounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val onValue: (T) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) {
    private val _state = MutableStateFlow(SyncedState<T>())
    val state: StateFlow<SyncedState<T>> = _state.asStateFlow()

    /** The current value, or `null` before the first successful load. */
    val value: T? get() = _state.value.value

    /** True when the server has said the value changed and this client has not refetched it. */
    val isStale: Boolean get() = stale

    private val mutex = Mutex()
    private var stale = true
    private var invalidateJob: Job? = null

    /**
     * Loads the value when it is missing or stale, and returns when the load has finished.
     *
     * [force] reloads even a value this client believes is current, which is what a resync does.
     */
    suspend fun sync(force: Boolean = false) {
        mutex.withLock { runLoad(force) }
    }

    /**
     * Marks the value stale and schedules a debounced refetch.
     *
     * Callers are event handlers, so this never blocks them: a burst of `agent.updated` events
     * while a file is being written costs one request.
     */
    fun invalidate() {
        stale = true
        invalidateJob?.cancel()
        invalidateJob = scope.launch {
            delay(invalidateDebounceMillis)
            sync()
        }
    }

    /**
     * Records that an authoritative event has already supplied the current value.
     *
     * A freshly created session is this case: the event carries the whole projection, so there is
     * nothing to fetch and a fetch would only churn the list.
     */
    fun complete(value: T) {
        stale = false
        _state.value = SyncedState(value, SyncStatus.Ready)
        onValue(value)
    }

    /** Drops the value, for example when `location.shutdown` invalidates a location. */
    fun clear() {
        stale = true
        _state.value = SyncedState()
    }

    fun stop() {
        invalidateJob?.cancel()
        invalidateJob = null
    }

    private suspend fun runLoad(force: Boolean) {
        if (!force && !stale && value != null) return
        // Consumed now, not after the load: an invalidation that arrives while this load runs must
        // still queue a successor.
        stale = false
        _state.value = _state.value.copy(status = SyncStatus.Loading)
        try {
            val loaded = loader()
            _state.value = SyncedState(loaded, SyncStatus.Ready)
            onValue(loaded)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // The value is kept, and the resource goes stale again so the next sync retries: a
            // mobile network drops, and a catalog that empties itself on one failed poll is worse
            // than one that is briefly out of date.
            stale = true
            _state.value = _state.value.copy(status = SyncStatus.Failed(error))
            onError(error)
        }
    }

    companion object {
        /**
         * Long enough to swallow a burst of `*.updated` events for one file being written, short
         * enough that a user does not notice.
         */
        const val DEFAULT_DEBOUNCE_MILLIS = 400L
    }
}

/**
 * The scope a cached value belongs to.
 *
 * Almost everything on the server is location-scoped (plan §2.6), so caches are keyed by
 * `(server, directory)` and [directory] is `null` for the resources that are not, such as the
 * project list.
 */
data class ResourceKey(val serverId: String, val directory: String? = null) {
    override fun toString(): String = "$serverId:${directory ?: "*"}"
}
