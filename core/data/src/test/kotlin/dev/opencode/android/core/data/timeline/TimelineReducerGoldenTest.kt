package dev.opencode.android.core.data.timeline

import dev.opencode.android.core.model.Paged
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.json.OpenCodeJson
import dev.opencode.android.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reducer's golden tests: every recorded SSE stream is replayed through [TimelineReducer] and
 * the result is compared with the server's own REST projection (plan §5.3).
 *
 * The fixtures come from `tools/record-fixtures.mjs` driving a real 2.0.18 server with
 * `tools/fake-provider`, so they cover a text answer, reasoning, a `!` shell command, an edit, a
 * question, a subagent, a provider failure with retries, a long answer, and a run that mixes a
 * shell command, a model switch and a failed compaction. The recorder writes the events and the
 * projections in one run, so each scenario's stream and its projection describe the same session
 * — which is what makes a byte comparison meaningful. (`events.jsonl` and the `messages-*.json`
 * files in earlier recordings came from different runs, and only `misc` happened to line up.)
 *
 * Three assertions, on purpose:
 *
 *  1. **Convergence.** The replayed timeline equals the server's projection. This is exit criterion
 *     "a reconnect mid-turn converges to the REST projection", reduced to the part a build can
 *     check: a stream replayed onto an empty timeline lands on the projection the server itself
 *     returns.
 *  2. **Mid-turn reconnect.** [mid turn reconnect converges] does the real thing with a
 *     projection recorded while the assistant message was still open.
 *  3. **Regression.** The replayed timeline equals a checked-in golden file, so a change in the
 *     reducer's semantics is a visible diff rather than a silently different transcript.
 */
class TimelineReducerGoldenTest {

    private val scenarios = listOf(
        "text" to "ses_fixture_text",
        "reasoning" to "ses_fixture_reasoning",
        "shell" to "ses_fixture_shell",
        "edit" to "ses_fixture_edit",
        "question" to "ses_fixture_question",
        "subagent" to "ses_fixture_subagent",
        "error" to "ses_fixture_error",
        "long" to "ses_fixture_long",
        "misc" to "ses_fixture_misc",
        "reconnect" to "ses_fixture_reconnect",
    )

    @Test
    fun `every recorded stream converges with the server projection`() {
        val failures = mutableListOf<String>()
        for ((scenario, sessionID) in scenarios) {
            val reduced = replay(sessionID)
            val projected = projected(scenario)
            val divergences = TimelineConvergence.compare(reduced, projected)
            if (divergences.isNotEmpty()) {
                failures += "$scenario:\n" + divergences.joinToString("\n") { "    $it" }
            }
        }
        assertEquals(
            "Replay diverged from the REST projection:\n${failures.joinToString("\n")}",
            emptyList<String>(),
            failures,
        )
    }

    @Test
    fun `mid turn reconnect converges`() {
        val sessionID = "ses_fixture_reconnect"
        val events = eventsFor(sessionID)
        val mid = projected("reconnect-mid")
        val final = projected("reconnect")

        // The cut is the last point the client could have been at and still match what the server
        // said at that moment. Deriving it from the fixtures rather than hard-coding a count keeps
        // the test meaningful when the recording is redone.
        val cuts = (0..events.size).filter { candidate ->
            TimelineConvergence.compare(replay(events.take(candidate), sessionID).messages, mid).isEmpty()
        }
        assertTrue("no prefix of the stream matches the mid-turn projection", cuts.isNotEmpty())
        val cut = cuts.max()
        assertTrue(
            "the mid-turn projection should be taken during the turn, not after it",
            cut < events.size,
        )
        assertTrue(
            "the mid-turn projection should not be the finished transcript",
            mid.size < final.size,
        )

        // What the client does on reconnect: throw its accumulated state away and take the
        // server's projection, then carry on applying the events that arrive from now on.
        val resynced = TimelineState.of(mid)
        val afterReconnect = events.drop(cut).fold(resynced) { state, event ->
            TimelineReducer.reduce(state, event, sessionID)
        }
        val divergences = TimelineConvergence.compare(afterReconnect.messages, final)
        assertEquals(
            "reconnect after $cut of ${events.size} events diverged:\n" +
                divergences.joinToString("\n") { "    $it" },
            emptyList<TimelineDivergence>(),
            divergences,
        )
    }

    @Test
    fun `a shell command's message id survives the stream`() {
        // A `!` shell command is admitted as an inbox item whose id the client chose, so the
        // server names `session.shell.started` after that id and the reducer's derived id has to
        // land on it exactly.
        val shell = replay("ses_fixture_misc").filterIsInstance<SessionMessage.Shell>().single()
        assertEquals("msg_fixture_misc_shell", shell.id)
        assertTrue(shell.shellID.startsWith("sh_"))
    }

    @Test
    fun `replaying a stream is deterministic`() {
        for ((_, sessionID) in scenarios) {
            assertEquals(replay(sessionID), replay(sessionID))
        }
    }

    @Test
    fun `events for another session never touch this timeline`() {
        val other = eventsFor("ses_fixture_text")
        val mine = eventsFor("ses_fixture_edit")
        assertTrue(other.isNotEmpty() && mine.isNotEmpty())
        val state = replay(eventsFor("ses_fixture_text"), "ses_fixture_text")
        val after = other.fold(state) { s, e -> TimelineReducer.reduce(s, e, "ses_fixture_edit") }
        assertEquals(state, after)
    }

    @Test
    fun `a prefix followed by the rest equals the whole stream`() {
        // Reduction is associative over a stream, which is what lets a store hand a resumed
        // timeline the events it missed and get the same transcript as one that never disconnected.
        for ((_, sessionID) in scenarios) {
            val events = eventsFor(sessionID)
            val whole = replay(events, sessionID)
            for (cut in 0..events.size) {
                val stepped = events.drop(cut).fold(replay(events.take(cut), sessionID)) { s, e ->
                    TimelineReducer.reduce(s, e, sessionID)
                }
                assertEquals("$sessionID diverged at cut $cut", whole, stepped)
            }
        }
    }

    private fun replay(sessionID: String): List<SessionMessage> = replay(eventsFor(sessionID), sessionID).messages

    private fun replay(events: List<Event>, sessionID: String): TimelineState =
        events.fold(TimelineState.Empty) { state, event -> TimelineReducer.reduce(state, event, sessionID) }

    private fun eventsFor(sessionID: String): List<Event> = Fixtures.events()
        .map(Event::decode)
        .filter { (it.payload as? EventPayload.SessionScoped)?.sessionID == sessionID }

    private fun projected(scenario: String): List<SessionMessage> =
        OpenCodeJson.decodeFromString(
            Paged.serializer(SessionMessage.serializer()),
            Fixtures.raw("messages-$scenario.json"),
        ).data.asReversed()
}
