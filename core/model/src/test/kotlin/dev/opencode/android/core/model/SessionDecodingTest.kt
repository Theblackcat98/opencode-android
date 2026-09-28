package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDecodingTest {

    @Test
    fun decodesEmptySessionsList() {
        val raw = Fixtures.raw("sessions-empty.json")
        val paged = OpenCodeJson.decodeFromString<Paged<SessionInfo>>(raw)

        assertTrue(paged.data.isEmpty())
        assertNull(paged.cursor.previous)
        assertNull(paged.cursor.next)
    }

    @Test
    fun decodesPopulatedSessionsList() {
        val raw = Fixtures.raw("sessions.json")
        val paged = OpenCodeJson.decodeFromString<Paged<SessionInfo>>(raw)

        assertEquals(9, paged.data.size)
        assertNotNull(paged.cursor.previous)
        assertNotNull(paged.cursor.next)

        val first = paged.data.first()
        assertEquals("ses_fixture_misc", first.id)
        assertEquals("c42aa3ccc1e04f703386feb3e4f519d0bcd1d408", first.projectID)
        assertEquals("reasoning", first.model?.id)
        assertEquals("fake", first.model?.providerID)
        assertEquals("default", first.model?.variant)
        assertEquals(Outcome.Succeeded, first.outcome)
        assertEquals("fixture misc", first.title)
    }

    @Test
    fun decodesAllSessionDetailScenarios() {
        val scenarios = listOf(
            "text",
            "reasoning",
            "shell",
            "edit",
            "question",
            "subagent",
            "error",
            "long",
            "misc",
        )

        for (scenario in scenarios) {
            val raw = Fixtures.raw("session-$scenario.json")
            val response = OpenCodeJson.decodeFromString<DataResponse<SessionInfo>>(raw)
            val session = response.data

            assertEquals("ses_fixture_$scenario", session.id)
            assertNotNull(session.projectID)
            assertNotNull(session.time)
            assertTrue(session.time.created > 0)
            assertTrue(session.time.updated > 0)

            // Test round-trip encoding
            val encoded = OpenCodeJson.encodeToString(
                DataResponse.serializer(SessionInfo.serializer()),
                response,
            )
            val decodedAgain = OpenCodeJson.decodeFromString<DataResponse<SessionInfo>>(encoded)
            assertEquals(response, decodedAgain)
        }
    }

    @Test
    fun verifiesIsUnreadComputation() {
        val unreadSession = SessionInfo(
            id = "ses_unread",
            projectID = "proj_1",
            cost = 0.0,
            tokens = TokenUsage.Zero,
            time = SessionInfo.Time(created = 100, updated = 200, idle = 200, viewed = 100),
            location = LocationPublicRef(directory = "/test"),
        )
        assertTrue(unreadSession.isUnread)

        val readSession = SessionInfo(
            id = "ses_read",
            projectID = "proj_1",
            cost = 0.0,
            tokens = TokenUsage.Zero,
            time = SessionInfo.Time(created = 100, updated = 200, idle = 200, viewed = 250),
            location = LocationPublicRef(directory = "/test"),
        )
        assertFalse(readSession.isUnread)

        val activeSession = SessionInfo(
            id = "ses_active",
            projectID = "proj_1",
            cost = 0.0,
            tokens = TokenUsage.Zero,
            time = SessionInfo.Time(created = 100, updated = 200, idle = null, viewed = 250),
            location = LocationPublicRef(directory = "/test"),
        )
        assertFalse(activeSession.isUnread)
    }
}
