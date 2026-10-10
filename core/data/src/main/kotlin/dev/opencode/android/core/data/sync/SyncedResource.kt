package dev.opencode.android.core.data.sync

import dev.opencode.android.core.data.action.ActionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/** What a [SyncedResource] knows about its own freshness. */
sealed interface SyncStatus {
    /** Never loaded, and not loading. */
    data object Idle : SyncStatus

    /** A load is running. */
    data object Loading : SyncStatus

    /**
     * A load ran and failed. The error is a transport or decoding failure, not an API error. The status
     * stays Failed while an automatic retry waits to run, and becomes [Loading] when the retry starts.
     */
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
 *  - **A failed load is retried a bounded number of times.** A transport failure, a `5xx` or an
 *    undecodable body is retried after [retryDelayMillis], doubling each time, up to [maxAttempts]
 *    loads in all. A `4xx` is not retried, because the server would only repeat its answer. [value]
 *    is kept throughout, and an explicit [invalidate] or a forced [sync] starts the budget again.
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
    /** The wait before the first automatic retry of a failed load. Each later retry waits twice as long. */
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    /** Loads allowed in total for one streak of failures, the first one included. `1` turns retries off. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(retryDelayMillis >= 0) { "retryDelayMillis must not be negative" }
        require(maxAttempts >= 1) { "maxAttempts must allow the first load" }
    }

    private val _state = MutableStateFlow(SyncedState<T>())
    val state: StateFlow<SyncedState<T>> = _state.asStateFlow()

    /** The current value, or `null` before the first successful load. */
    val value: T? get() = _state.value.value

    /** True when the server has said the value changed and this client has not refetched it. */
    val isStale: Boolean get() = stale

    private val mutex = Mutex()

    /** Written by event handlers and by loads on other threads, so it is volatile rather than mutex-held. */
    @Volatile
    private var stale = true

    /**
     * Guards [invalidateJob], [retryJob] and [failedAttempts]. Event handlers, ViewModels and the retry
     * coroutine all reach them from different threads, and [mutex] cannot be taken from the non-suspending
     * [invalidate]. Nothing suspends while holding it.
     */
    private val guard = Any()
    private var invalidateJob: Job? = null

    /** A retry that is waiting to start. Cleared once it starts, so cancelling never cuts a running load short. */
    private var retryJob: Job? = null

    /** Failed loads since the last success, invalidation or forced sync. The retry budget is spent from this. */
    private var failedAttempts = 0

    /**
     * Loads the value when it is missing or stale, and returns when the load has finished.
     *
     * [force] reloads even a value this client believes is current, which is what a resync does. A
     * forced load is a fresh request, so it also cancels a waiting retry and starts the budget again.
     */
    suspend fun sync(force: Boolean = false) {
        if (force) resetRetry()
        mutex.withLock { runLoad(force) }
    }

    /**
     * Marks the value stale and schedules a debounced refetch.
     *
     * Callers are event handlers, so this never blocks them: a burst of `agent.updated` events
     * while a file is being written costs one request. It also cancels a waiting retry, because the
     * debounced refetch already asks for the same thing.
     */
    fun invalidate() {
        stale = true
        resetRetry()
        synchronized(guard) {
            invalidateJob?.cancel()
            invalidateJob = scope.launch {
                delay(invalidateDebounceMillis)
                sync()
            }
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
        resetRetry()
        _state.value = SyncedState(value, SyncStatus.Ready)
        onValue(value)
    }

    /** Drops the value, for example when `location.shutdown` invalidates a location. */
    fun clear() {
        stale = true
        resetRetry()
        _state.value = SyncedState()
    }

    fun stop() {
        synchronized(guard) {
            invalidateJob?.cancel()
            invalidateJob = null
        }
        cancelRetry()
    }

    private suspend fun runLoad(force: Boolean) {
        if (!force && !stale && value != null) return
        // Consumed now, not after the load: an invalidation that arrives while this load runs must
        // still queue a successor.
        stale = false
        _state.value = _state.value.copy(status = SyncStatus.Loading)
        try {
            val loaded = loader()
            resetRetry() // a retry still waiting is now redundant
            _state.value = SyncedState(loaded, SyncStatus.Ready)
            onValue(loaded)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // The value is kept, and the resource goes stale again so the next sync retries: a
            // mobile network drops, and a catalog that empties itself on one failed poll is worse
            // than one that is briefly out of date. The retry below makes that a bounded promise.
            stale = true
            val attempts = synchronized(guard) { ++failedAttempts }
            _state.value = _state.value.copy(status = SyncStatus.Failed(error))
            scheduleRetry(error, attempts)
            onError(error)
        }
    }

    /**
     * Schedules the next automatic retry of a failed load, or none when the error will not change on
     * a retry or the budget is spent. The status stays [SyncStatus.Failed] while the retry waits.
     */
    private fun scheduleRetry(error: Throwable, attempts: Int) {
        synchronized(guard) {
            retryJob?.cancel()
            retryJob = null
            if (attempts >= maxAttempts || !error.isRetryableLoadFailure()) return
            // Doubling per failure: the first retry waits [retryDelayMillis], the second twice that.
            val waitMillis = retryDelayMillis * (1L shl (attempts - 1))
            // Created lazily so the job is recorded before it can run, which lets it clear its own record.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val self = currentCoroutineContext()[Job]
                delay(waitMillis)
                mutex.withLock {
                    // Still cancellable while it queues for the mutex, so an invalidation or a forced sync
                    // that lands first replaces it. Once it holds the mutex it is an ordinary load. Only its
                    // own record is cleared: a newer retry may have replaced it.
                    synchronized(guard) { if (retryJob === self) retryJob = null }
                    runLoad(force = false)
                }
            }
            retryJob = job
            job.start()
        }
    }

    /** Cancels a retry that is still waiting. One that has started is left to finish. */
    private fun cancelRetry() {
        synchronized(guard) {
            retryJob?.cancel()
            retryJob = null
        }
    }

    /** Cancels a waiting retry and starts the retry budget again, as one step. */
    private fun resetRetry() {
        synchronized(guard) {
            retryJob?.cancel()
            retryJob = null
            failedAttempts = 0
        }
    }

    companion object {
        /**
         * Long enough to swallow a burst of `*.updated` events for one file being written, short
         * enough that a user does not notice.
         */
        const val DEFAULT_DEBOUNCE_MILLIS = 400L

        /**
         * The wait before the first automatic retry of a failed load, one second. A dropped connection
         * usually comes back within that, and the wait doubles so a server that is down is not polled
         * in a tight loop.
         */
        const val DEFAULT_RETRY_DELAY_MILLIS = 1_000L

        /**
         * Loads allowed for one streak of failures, the first one included: the original load, then a
         * retry after [DEFAULT_RETRY_DELAY_MILLIS] and another after twice that. A resource that still
         * fails after that stays [SyncStatus.Failed] until the next sync or invalidation asks again.
         */
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}

/**
 * Whether a failed load is worth repeating.
 *
 * Decided by the HTTP status when there is one: a `4xx` means the request, the credential or the thing
 * it names is wrong, and asking again gets the same answer. Anything without a status (a dropped
 * connection, a timeout, a body that did not decode) and every `5xx` is worth another try.
 */
private fun Throwable.isRetryableLoadFailure(): Boolean {
    val status = when (this) {
        is HttpException -> code()
        is ActionFailure -> error.httpStatus
        else -> null
    } ?: return true
    return status in 500..599
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
