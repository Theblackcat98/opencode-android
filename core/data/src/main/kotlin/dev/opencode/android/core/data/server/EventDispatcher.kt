package dev.opencode.android.core.data.server

import dev.opencode.android.core.model.event.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The single place events are applied (plan §4.2, "Never block the reader").
 *
 * The SSE reader parses a frame and does nothing else. Everything downstream — stores, Room, the
 * self-check — runs on one loop that drains the buffer about once per frame, so a burst of a
 * thousand `text.delta` frames inside one turn becomes a handful of state publications instead of a
 * thousand, and the reader never waits for a store, a database or a composition.
 *
 * [submit] never blocks and never suspends: it appends to a buffer under a lock and returns.
 *
 * **Nothing is dropped.** The server disconnects a consumer whose queue passes 4,096 frames, so
 * dropping here would silently lose a turn. The buffer is therefore unbounded, and the per-frame
 * cap only decides how much is *applied* at a time; a busy turn simply takes a few more frames to
 * catch up. What a frame cannot keep up with, the display cannot show anyway.
 */
class EventDispatcher(
    private val scope: CoroutineScope,
    private val frameMillis: Long = DEFAULT_FRAME_MILLIS,
    private val maxBatch: Int = DEFAULT_MAX_BATCH,
) {
    private val buffer = ArrayDeque<Event>()
    private val lock = Any()
    private var draining = false

    /**
     * The published batches.
     *
     * A channel and not a `SharedFlow`, because a `SharedFlow` with no subscriber drops whatever
     * is emitted, and the reader must not lose a frame just because a store is not attached at
     * that instant.
     */
    private val published = Channel<List<Event>>(Channel.UNLIMITED)

    /** One list per frame, in arrival order. */
    val batches: Flow<List<Event>> = published.receiveAsFlow()

    private var drainJob: Job? = null

    /** How many events are waiting for the next frame. */
    val bufferedCount: Int get() = synchronized(lock) { buffer.size }

    /** Queues one event. Never blocks and never suspends the caller. */
    fun submit(event: Event) {
        val start = synchronized(lock) {
            buffer.addLast(event)
            if (draining) {
                false
            } else {
                draining = true
                true
            }
        }
        if (start) startDraining()
    }

    /** Queues many events, for a replay or a test. */
    fun submit(events: List<Event>) = events.forEach(::submit)

    private fun startDraining() {
        drainJob = scope.launch {
            while (true) {
                delay(frameMillis)
                val batch = takeBatch() ?: return@launch
                published.trySend(batch)
            }
        }
    }

    /** One frame's worth, or `null` when the buffer ran dry, which ends the loop. */
    private fun takeBatch(): List<Event>? = synchronized(lock) {
        if (buffer.isEmpty()) {
            draining = false
            return null
        }
        val batch = ArrayList<Event>(minOf(buffer.size, maxBatch))
        while (batch.size < maxBatch && buffer.isNotEmpty()) batch.add(buffer.removeFirst())
        batch
    }

    companion object {
        /** One frame at 60 Hz. */
        const val DEFAULT_FRAME_MILLIS = 16L

        /**
         * Events applied per frame. Above this a frame would outrun the display; the rest wait for
         * the next one, which is what "batched per frame" is for.
         */
        const val DEFAULT_MAX_BATCH = 256
    }
}
