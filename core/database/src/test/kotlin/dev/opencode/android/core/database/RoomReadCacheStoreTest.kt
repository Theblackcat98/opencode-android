package dev.opencode.android.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opencode.android.core.database.cache.ReadCacheStore
import dev.opencode.android.core.database.cache.RoomReadCacheStore
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.PromptFileAttachment
import dev.opencode.android.core.model.PromptFileSource
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.TokenUsage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The offline cache, on a real SQLite database.
 *
 * The properties that matter are that a round trip is lossless (a cached message must decode to
 * the value it was written from, or a self-check against a cached transcript would be
 * meaningless), that the cache is bounded, and that dropping a location leaves the others alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomReadCacheStoreTest {

    private lateinit var database: OpenCodeDatabase
    private lateinit var store: ReadCacheStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenCodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomReadCacheStore(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a session round trips without losing a field`() = runTest {
        val session = session(id = "ses_a", title = "with attachments and cost")
        store.writeSession("server-1", "ses_a", session)
        assertEquals(session, store.readSession("server-1", "ses_a"))
    }

    @Test
    fun `sessions are read back most recently updated first`() = runTest {
        store.writeSessions(
            "server-1",
            "/work",
            listOf(
                session(id = "ses_old", updated = 10, title = "old"),
                session(id = "ses_new", updated = 99, title = "new"),
            ),
        )
        assertEquals(
            listOf("ses_new", "ses_old"),
            store.readSessions("server-1", "/work").map { it.id },
        )
    }

    @Test
    fun `sessions are scoped by directory`() = runTest {
        store.writeSessions("server-1", "/work", listOf(session(id = "ses_work")))
        store.writeSessions("server-1", "/other", listOf(session(id = "ses_other")))

        assertEquals(listOf("ses_work"), store.readSessions("server-1", "/work").map { it.id })
        assertEquals(listOf("ses_other"), store.readSessions("server-1", "/other").map { it.id })
    }

    @Test
    fun `the session list is bounded and the newest survive`() = runTest {
        val many = (1..ReadCacheStore.SESSION_LIMIT + 20).map {
            session(id = "ses_$it", updated = it.toLong())
        }
        store.writeSessions("server-1", "/work", many)

        val kept = store.readSessions("server-1", "/work", limit = ReadCacheStore.SESSION_LIMIT)
        assertEquals(ReadCacheStore.SESSION_LIMIT, kept.size)
        assertEquals("the newest sessions are the ones that survive", "ses_220", kept.first().id)
    }

    @Test
    fun `messages round trip in order and keep every field`() = runTest {
        val messages = listOf(
            userMessage("msg_1", "first"),
            assistantMessage("msg_2"),
            SessionMessage.Idle(
                id = "msg_3",
                time = SessionMessage.CreatedTime(30),
                outcome = dev.opencode.android.core.model.Outcome.Succeeded,
            ),
        )
        store.writeMessages("server-1", "ses_a", messages)

        assertEquals(messages, store.readMessages("server-1", "ses_a", limit = 10))
    }

    @Test
    fun `a write replaces the window and stays bounded`() = runTest {
        val first = (1..10).map { userMessage("msg_$it") }
        store.writeMessages("server-1", "ses_a", first)
        assertEquals(10, store.readMessages("server-1", "ses_a", limit = 100).size)

        // "Load older" hands over the whole window the store holds, so the table is replaced, never
        // appended to, which is what keeps a long transcript from filling the device.
        val window = (1..5).map { userMessage("msg_$it") }
        store.writeMessages("server-1", "ses_a", window)
        assertEquals(window, store.readMessages("server-1", "ses_a", limit = 100))
    }

    @Test
    fun `only the newest messages are kept when the window is longer than the limit`() = runTest {
        val many = (1..ReadCacheStore.MESSAGE_LIMIT + 50).map { userMessage("msg_$it") }
        store.writeMessages("server-1", "ses_a", many)

        val kept = store.readMessages("server-1", "ses_a", limit = ReadCacheStore.MESSAGE_LIMIT)
        assertEquals(ReadCacheStore.MESSAGE_LIMIT, kept.size)
        assertEquals("msg_350", kept.last().id)
    }

    @Test
    fun `dropping a location leaves the others alone`() = runTest {
        store.writeSessions("server-1", "/work", listOf(session(id = "ses_work")))
        store.writeMessages("server-1", "ses_work", listOf(userMessage("msg_1")))
        store.writeSessions("server-1", "/other", listOf(session(id = "ses_other")))
        store.writeMessages("server-1", "ses_other", listOf(userMessage("msg_2")))

        store.dropLocation("server-1", "/work")

        assertTrue(store.readSessions("server-1", "/work").isEmpty())
        assertTrue(store.readMessages("server-1", "ses_work", 10).isEmpty())
        assertEquals(listOf("ses_other"), store.readSessions("server-1", "/other").map { it.id })
        assertEquals(1, store.readMessages("server-1", "ses_other", 10).size)
    }

    @Test
    fun `dropping a server empties everything it owns`() = runTest {
        store.writeSessions("server-1", "/work", listOf(session(id = "ses_work")))
        store.writeSessions("server-2", "/work", listOf(session(id = "ses_other_server")))

        store.dropServer("server-1")

        assertTrue(store.readSessions("server-1", "/work").isEmpty())
        assertEquals(1, store.readSessions("server-2", "/work").size)
    }

    @Test
    fun `deleting a session takes its messages with it`() = runTest {
        store.writeSessions("server-1", "/work", listOf(session(id = "ses_a")))
        store.writeMessages("server-1", "ses_a", listOf(userMessage("msg_1")))

        store.deleteSession("server-1", "ses_a")

        assertNull(store.readSession("server-1", "ses_a"))
        assertTrue(store.readMessages("server-1", "ses_a", 10).isEmpty())
    }

    private fun session(id: String, updated: Long = 1, title: String? = "a session") = SessionInfo(
        id = id,
        projectID = "project-1",
        cost = 1.5,
        tokens = TokenUsage(10, 5, 0, TokenUsage.Cache(1, 0)),
        time = SessionInfo.Time(created = 0, updated = updated, idle = 5, viewed = 2),
        title = title,
        location = LocationPublicRef("/work"),
    )

    private fun userMessage(id: String, text: String = "hello") = SessionMessage.User(
        id = id,
        time = SessionMessage.CreatedTime(1),
        text = text,
        files = listOf(
            PromptFileAttachment(
                data = "aGk=",
                mime = "image/png",
                source = PromptFileSource.Inline,
                name = "shot.png",
            ),
        ),
    )

    private fun assistantMessage(id: String) = SessionMessage.Assistant(
        id = id,
        time = SessionMessage.Assistant.Time(created = 2, streamed = 3, completed = 4),
        agent = "build",
        model = dev.opencode.android.core.model.ModelRef("m", "p", "v"),
        content = listOf(
            dev.opencode.android.core.model.AssistantContent.Text("hello"),
            dev.opencode.android.core.model.AssistantContent.Tool(
                id = "tool_1",
                name = "read",
                state = dev.opencode.android.core.model.ToolState.Completed(
                    input = mapOf("path" to kotlinx.serialization.json.JsonPrimitive("a.kt")),
                    content = listOf(
                        dev.opencode.android.core.model.ToolContent.Text("file body"),
                        dev.opencode.android.core.model.ToolContent.File("file:///a.kt", "text/x-kotlin", "a.kt"),
                    ),
                    metadata = mapOf("bytes" to kotlinx.serialization.json.JsonPrimitive(9)),
                ),
                time = dev.opencode.android.core.model.AssistantContent.Tool.Time(created = 2, ran = 2, completed = 3),
            ),
        ),
        snapshot = SessionMessage.Assistant.Snapshot(start = "abc", end = "def", files = listOf("a.kt")),
        finish = dev.opencode.android.core.model.FinishReason.Stop,
        cost = 0.25,
        tokens = TokenUsage(1, 2, 3, TokenUsage.Cache(4, 5)),
    )
}
