package dev.opencode.android.core.data.timeline

import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.json.JsonObject

/** How a replayed timeline differs from the server's own projection of the same session. */
enum class DivergenceKind {
    /** The event stream produced a message the server's projection does not have. */
    MISSING_IN_PROJECTION,

    /** The server's projection has a message the event stream never produced. */
    MISSING_IN_REDUCER,

    /** Both have the message, but the content differs. */
    CONTENT_DIFFERS,

    /** The same messages, in a different order. */
    ORDER_DIFFERS,
}

/** One difference, small enough to put in a log line. */
data class TimelineDivergence(
    val kind: DivergenceKind,
    val messageId: String,
    val detail: String,
) {
    override fun toString(): String = "$kind $messageId: $detail"
}

/**
 * Compares a replayed timeline with the server's own projection of the same session.
 *
 * This is the check behind the in-app self-check and behind the golden tests, so what CI proves
 * and what a developer sees on a device are the same comparison.
 *
 * The server is authoritative, so a difference is a bug in the client rather than a race to be
 * tolerated — except for one: the two sides are compared as a *set* of messages, because the
 * reducer appends in event order while the projection is ordered by the server's own clock, and a
 * subagent or a background shell can be projected slightly out of stream order. Ordering is
 * therefore checked separately, and only as the sequence of ids, so a genuinely reordered
 * transcript is still reported.
 */
object TimelineConvergence {

    fun compare(
        reduced: List<SessionMessage>,
        projected: List<SessionMessage>,
    ): List<TimelineDivergence> {
        val projectedById = projected.associateBy { it.id }
        val matched = HashSet<String>(projected.size)
        val divergences = mutableListOf<TimelineDivergence>()

        for (message in reduced) {
            val other = projectedById[message.id]
            if (other == null) {
                divergences.add(
                    TimelineDivergence(
                        kind = DivergenceKind.MISSING_IN_PROJECTION,
                        messageId = message.id,
                        detail = "type=${message.typeName()}",
                    ),
                )
                continue
            }
            matched.add(message.id)
            if (canonical(message) != canonical(other)) {
                divergences.add(
                    TimelineDivergence(
                        kind = DivergenceKind.CONTENT_DIFFERS,
                        messageId = message.id,
                        detail = "reduced=${canonical(message).abbreviate()} " +
                            "projected=${canonical(other).abbreviate()}",
                    ),
                )
            }
        }
        for (message in projected) {
            if (message.id !in matched) {
                divergences.add(
                    TimelineDivergence(
                        kind = DivergenceKind.MISSING_IN_REDUCER,
                        messageId = message.id,
                        detail = "type=${message.typeName()}",
                    ),
                )
            }
        }
        if (divergences.isEmpty()) {
            val left = reduced.map { it.id }
            val right = projected.map { it.id }
            if (left != right && left.sorted() == right.sorted()) {
                val at = left.indices.firstOrNull { left[it] != right.getOrNull(it) } ?: -1
                divergences.add(
                    TimelineDivergence(
                        kind = DivergenceKind.ORDER_DIFFERS,
                        messageId = left.getOrNull(at) ?: right.getOrNull(at) ?: "-",
                        detail = "reduced=${left.joinToString(
                            ",",
                        ).abbreviate()} projected=${right.joinToString(",").abbreviate()}",
                    ),
                )
            }
        }
        return divergences
    }

    private fun canonical(message: SessionMessage): String {
        val json = OpenCodeJson.encodeToJsonElement(SessionMessage.serializer(), message)
        return (json as? JsonObject)?.toString() ?: json.toString()
    }

    private fun String.abbreviate(): String = if (length <= 240) this else take(240) + "…"
}

private fun SessionMessage.typeName(): String = when (this) {
    is SessionMessage.User -> "user"
    is SessionMessage.Synthetic -> "synthetic"
    is SessionMessage.System -> "system"
    is SessionMessage.Skill -> "skill"
    is SessionMessage.Shell -> "shell"
    is SessionMessage.Assistant -> "assistant"
    is SessionMessage.Compaction -> "compaction"
    is SessionMessage.Idle -> "idle"
    is SessionMessage.AgentSwitched -> "agent-switched"
    is SessionMessage.ModelSwitched -> "model-switched"
    is SessionMessage.LocationSwitched -> "location-switched"
    is SessionMessage.Unknown -> "unknown"
}
