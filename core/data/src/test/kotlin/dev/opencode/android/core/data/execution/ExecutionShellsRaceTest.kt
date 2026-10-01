package dev.opencode.android.core.data.execution

import dev.opencode.android.core.model.LocationRef
import dev.opencode.android.core.model.ShellInfo
import dev.opencode.android.core.model.ShellStatus
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.event.PtyCreated
import dev.opencode.android.core.model.event.PtyDeleted
import dev.opencode.android.core.model.event.PtyInfo
import dev.opencode.android.core.model.event.PtyStatus
import dev.opencode.android.core.model.event.ShellCreated
import dev.opencode.android.core.network.ServerApiFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A `shell.created` that arrives while the first `shell.list` is still in flight (manual test F1's list).
 *
 * **The bug this file exists for, and it is a defect rather than a flake.** `ExecutionLocationStore` inserts
 * a row from a `shell.created` event straight into `_shells`, and the `shell.list` load publishes into the
 * same field through `onValue = { value -> _shells.value = value }`. Whichever lands second wins, so an
 * event that arrived first is *erased* by a list the server had already answered before that command
 * existed. The KDoc on `shellList` claims "published into [shells] as well so an event and a refetch cannot
 * disagree"; on a slow server they do, and the command the user has just started leaves the list until
 * something else refetches it.
 *
 * **This is the intermittent `ExecutionStoreTest` failure, and the machine was not the cause.**
 * `runTest(UnconfinedTestDispatcher())` makes the *test* eager, but the load is answered from an OkHttp
 * thread, so both orders are reachable and one of them loses the row. That test asserts the row is there
 * immediately after applying the event, which is only true when the list has not landed yet — so it passes
 * or fails on which thread won, and had been failing intermittently on a clean tree for that reason.
 *
 * The latch is what makes the order certain: the list is held open until the event has been applied and
 * checked, so this test can only fail, and never passes by winning the race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionShellsRaceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `an event applied while the first list is in flight is not erased when the list lands`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    // The list the server had already answered before this command existed: it does not
                    // contain it, and it is held back so the event provably lands first.
                    SHELL_LIST -> {
                        gate.await(AWAIT_SECONDS, TimeUnit.SECONDS)
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body("""{"location":{"directory":"/a"},"data":[]}""")
                            .build()
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
            val api = ServerApiFactory(OkHttpClient()).createForReads(server.url("/").toString())
            val store = ExecutionSurface("srv", api, backgroundScope).at("/a")

            // The event lands while the list is in flight, and it shows, which is the behaviour every
            // caller of `apply` relies on and the one this whole change is about.
            assertTrue(store.apply(shellCreated("/a", "sh_1", "npm test")))
            assertEquals(listOf("sh_1"), store.shells.value.map { it.id })

            // Now let the list through, and give it room to be published.
            gate.countDown()
            waitUntil("the shell list to be answered") { server.requestCount >= 1 }
            Thread.sleep(PUBLISH_MILLIS)

            assertEquals(
                "a list answered before this command existed must not erase it",
                listOf("sh_1"),
                store.shells.value.map { it.id },
            )
            assertEquals("npm test", store.shells.value.single().command)
        }

    @Test
    fun `a terminal the server killed is not put back by a list that was already in flight`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    // The list as it was *before* the terminal existed, held back so the create and the
                    // delete both provably land first. It contains the terminal, because this is the same
                    // answer a `pty.list` returns when it was requested a moment earlier.
                    PTY_LIST -> {
                        gate.await(AWAIT_SECONDS, TimeUnit.SECONDS)
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/json")
                            .body(
                                """{"location":{"directory":"/a"},"data":""" +
                                    """[{"id":"pty_1","title":"shell","command":"/bin/bash","args":[],""" +
                                    """"cwd":"/a","status":"running","pid":1}]}""",
                            )
                            .build()
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
            val api = ServerApiFactory(OkHttpClient()).createForReads(server.url("/").toString())
            val store = ExecutionSurface("srv", api, backgroundScope).at("/a")

            assertTrue(store.apply(ptyCreated("/a", pty("pty_1"))))
            // The server has now killed it, and says so.
            assertTrue(store.apply(event("pty.deleted", "/a", PtyDeleted("pty_1"))))
            assertTrue("the delete is immediate", store.ptys.value.isEmpty())

            gate.countDown()
            waitUntil("the terminal list to be answered") { server.requestCount >= 1 }
            Thread.sleep(PUBLISH_MILLIS)

            assertTrue(
                "a list requested before the delete must not bring a killed terminal back",
                store.ptys.value.isEmpty(),
            )
        }

    private fun shellCreated(directory: String, id: String, command: String) = Event(
        id = "e_shell.created",
        type = "shell.created",
        created = 1_000L,
        location = LocationRef(directory),
        payload = ShellCreated(
            ShellInfo(
                id = id,
                status = ShellStatus.Running,
                command = command,
                cwd = directory,
                shell = "/bin/bash",
                file = "/tmp/out",
                metadata = emptyMap(),
                time = ShellInfo.Time(started = 1_000L),
            ),
        ),
    )

    private fun ptyCreated(directory: String, info: PtyInfo) = Event(
        id = "e_pty.created",
        type = "pty.created",
        created = 1_000L,
        location = LocationRef(directory),
        payload = PtyCreated(info),
    )

    private fun event(type: String, directory: String, payload: EventPayload) = Event(
        id = "e_$type",
        type = type,
        created = 1_000L,
        location = LocationRef(directory),
        payload = payload,
    )

    private fun pty(id: String) = PtyInfo(
        id = id,
        title = "shell",
        command = "/bin/bash",
        args = emptyList(),
        cwd = "/a",
        status = PtyStatus.Running,
        pid = 1,
    )

    /** On the wall clock, because the publishing happens on an OkHttp thread and not in virtual time. */
    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * NANOS_PER_MILLI
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val SHELL_LIST = "/api/shell"
        const val PTY_LIST = "/api/pty"
        const val AWAIT_SECONDS = 10L
        const val TIMEOUT_MILLIS = 5_000L
        const val PUBLISH_MILLIS = 300L
        const val POLL_MILLIS = 5L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
