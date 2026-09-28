package dev.opencode.android.core.data.server

import dev.opencode.android.core.model.event.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame batcher the whole read path sits on (plan §4.2, "Never block the reader").
 *
 * The properties that matter are: a submitter is never blocked or suspended, a burst is published
 * as a handful of batches rather than one per frame event, and every event arrives exactly once
 * and in order.
 */
class EventDispatcherTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `every event arrives exactly once and in order`() = runBlocking {
        val dispatcher = EventDispatcher(scope, frameMillis = 1, maxBatch = 8)
        val received = mutableListOf<Event>()
        val ready = CompletableDeferred<Unit>()
        val collector = scope.launch {
            dispatcher.batches.collect { received.addAll(it) }
        }.also { scope.launch { delay(20); ready.complete(Unit) } }
        awaitBatches(ready)

        val expected = (1..50).map { event("ses_a", "evt_$it") }
        dispatcher.submit(expected)
        awaitCondition("only ${received.size} of 50 events arrived") { received.size >= 50 }
        collector.cancel()

        assertEquals(expected.map { it.id }, received.take(50).map { it.id })
        assertEquals("the buffer must be empty once every batch is applied", 0, dispatcher.bufferedCount)
        scope.cancel()
    }

    @Test
    fun `a burst is batched rather than published per event`() = runBlocking {
        // A 16 ms frame and a batch cap of 256: 1,000 events must not become 1,000 emissions.
        val dispatcher = EventDispatcher(scope, frameMillis = 4, maxBatch = 256)
        var emissions = 0
        val ready = CompletableDeferred<Unit>()
        val collector = scope.launch { dispatcher.batches.collect { emissions++ } }
            .also { scope.launch { delay(20); ready.complete(Unit) } }
        awaitBatches(ready)

        dispatcher.submit((1..1_000).map { event("ses_a", "evt_$it") })
        awaitCondition("the 1,000 events were never all applied") { dispatcher.bufferedCount == 0 && emissions > 0 }
        delay(200)
        collector.cancel()
        scope.cancel()

        assertTrue("1,000 events must not be published one per emission, saw $emissions", emissions < 20)
    }

    @Test
    fun `a burst larger than the batch cap is not dropped`() = runBlocking {
        val dispatcher = EventDispatcher(scope, frameMillis = 1, maxBatch = 16)
        val received = mutableListOf<String>()
        val ready = CompletableDeferred<Unit>()
        val collector = scope.launch { dispatcher.batches.collect { received.addAll(it.map(Event::id)) } }
            .also { scope.launch { delay(20); ready.complete(Unit) } }
        awaitBatches(ready)

        val count = 200
        dispatcher.submit((1..count).map { event("ses_a", "evt_$it") })
        awaitCondition("only ${received.size} of $count events arrived") { received.size >= count }
        collector.cancel()
        scope.cancel()

        assertEquals(count, received.size)
        assertEquals((1..count).map { "evt_$it" }, received)
    }

    @Test
    fun `an idle dispatcher does nothing`() = runBlocking {
        val dispatcher = EventDispatcher(scope, frameMillis = 1)
        var emissions = 0
        val collector = scope.launch { dispatcher.batches.collect { emissions++ } }
        delay(50)
        assertEquals("an idle dispatcher must not publish", 0, emissions)
        collector.cancel()
        scope.cancel()
    }

    private fun event(sessionID: String, id: String): Event = Event.decode(
        """{"id":"$id","created":1,"type":"session.viewed","data":{"sessionID":"$sessionID","idle":1}}""",
    )

    /**
     * Waits until a collector is subscribed.
     *
     * The dispatcher's queue is a channel, so nothing is lost by attaching late, but a test that
     * submits and measures emissions in the same breath would otherwise measure a race.
     */
    private suspend fun awaitBatches(ready: CompletableDeferred<Unit>) {
        withTimeout(5_000) { ready.await() }
    }

    private suspend fun awaitCondition(reason: String, condition: () -> Boolean) {
        try {
            withTimeout(5_000) {
                while (!condition()) delay(5)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out: $reason")
        }
    }
}
