package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.variant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Usage statistics for a date range (`GET /api/experimental/session/stats`, features doc §4.2).
 *
 * **The range and the time zone are the server's to interpret.** `from` and `to` are epoch
 * milliseconds and `timezone` is an IANA name, and the daily [activity] buckets are cut in that
 * zone, so a dashboard that re-buckets them locally would show a different heatmap than
 * `opencode stats` prints for the same query. The client sends the range it wants and shows the
 * server's answer.
 */
@Serializable
data class SessionStats(
    val range: Range,
    val sessions: Long,
    /** Sessions started as a subagent of another session. */
    val subagents: Long,
    val prompts: Long,
    val steps: Long,
    val tokens: TokenUsage,
    /** US dollars. A plain number, so it is shown as currency and never summed as a token count. */
    val cost: Double,
    val tools: SessionStatsTools,
    val activeDays: Long,
    /** Consecutive active days ending at the last one in [activity]. */
    val streak: Long,
    val activity: List<Activity>,
    val models: List<ModelUsage>,
) {
    @Serializable
    data class Range(val from: Long, val to: Long)

    /** One day of the heatmap. [date] is `YYYY-MM-DD` in the queried time zone. */
    @Serializable
    data class Activity(val date: String, val steps: Long)

    @Serializable
    data class ModelUsage(
        val model: ModelRef,
        val steps: Long,
        val tokens: TokenUsage,
        val cost: Double,
    )
}

/**
 * Tool reliability, in the three shapes the `tools` query parameter selects.
 *
 * The union is on `mode` rather than on which fields are present, so [TotalsOnly], [Summary] and
 * [Detail] are told apart by the value the server sent and not by guessing from an empty list. A
 * `detail` query with no tool calls yet returns [Summary] with zero totals, and showing "no tools"
 * for that would be wrong: no tool ran, but the server did answer the question.
 */
@Serializable(with = SessionStatsToolsSerializer::class)
sealed interface SessionStatsTools {
    /** The tool column is off (`tools=none`): the server did not compute it. */
    @Serializable
    data object None : SessionStatsTools

    /** `tools=summary`: every tool's counters rolled up. */
    @Serializable
    data class Summary(val totals: ToolTotals) : SessionStatsTools

    /** `tools=detail`: the same totals plus a row per tool, with a p50 duration. */
    @Serializable
    data class Detail(val totals: ToolTotals, val usage: List<ToolUsage>) : SessionStatsTools

    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        SessionStatsTools,
        UnknownVariant
}

@Serializable
data class ToolTotals(
    val calls: Long,
    val succeeded: Long,
    val failed: Long,
    /** Called and still running when the range was cut. */
    val unfinished: Long,
) {
    /**
     * Share of calls that succeeded, or `null` when nothing ran.
     *
     * A ratio of 0 over 0 is not a reliability figure, and a dashboard that shows "0% reliable"
     * because the user has not run a tool yet is telling them something false.
     */
    val successRate: Double? get() = if (calls == 0L) null else succeeded.toDouble() / calls
}

@Serializable
data class ToolUsage(
    val name: String,
    val calls: Long,
    val succeeded: Long,
    val failed: Long,
    val unfinished: Long,
    /** Median duration in milliseconds. Absent when the server had no completed call to time. */
    val durationP50: Double? = null,
) {
    val successRate: Double? get() = if (calls == 0L) null else succeeded.toDouble() / calls
}

internal object SessionStatsToolsSerializer : DiscriminatedUnionSerializer<SessionStatsTools>(
    serialName = "dev.opencode.android.SessionStatsTools",
    // The discriminator is `mode`, not `type`. Every other union in this package keys on `type`,
    // which is the point worth writing down: this schema names its tag `mode`, and a serializer that
    // defaulted to `type` would read every answer as `Unknown` and a dashboard would show no tool
    // data at all on a server that sent plenty.
    discriminator = "mode",
    unknown = SessionStatsTools::Unknown,
    variants = listOf(
        variant("none", SessionStatsTools.None.serializer()),
        variant("summary", SessionStatsTools.Summary.serializer()),
        variant("detail", SessionStatsTools.Detail.serializer()),
    ),
)

/**
 * The `tools` query parameter of the stats route.
 *
 * The wire form is a string, and the server picks a default when it is absent, so [None] here means
 * "do not send the parameter" rather than "ask for no tool data" — the two are different requests
 * with different answers.
 */
@Serializable(with = ToolDetailModeSerializer::class)
sealed interface ToolDetailMode {
    /** The exact query value. */
    val wire: String

    @Serializable
    data object None : ToolDetailMode {
        override val wire: String get() = "none"
    }

    @Serializable
    data object Summary : ToolDetailMode {
        override val wire: String get() = "summary"
    }

    @Serializable
    data object Detail : ToolDetailMode {
        override val wire: String get() = "detail"
    }

    data class Unknown(override val discriminator: String?, override val raw: JsonObject) :
        ToolDetailMode,
        UnknownVariant {
        /** A mode this build has never heard of is sent as the server spelled it, or not at all. */
        override val wire: String get() = discriminator.orEmpty()
    }
}

internal object ToolDetailModeSerializer : DiscriminatedUnionSerializer<ToolDetailMode>(
    serialName = "dev.opencode.android.ToolDetailMode",
    // `mode`, like the answer it selects. See [SessionStatsToolsSerializer].
    discriminator = "mode",
    unknown = ToolDetailMode::Unknown,
    variants = listOf(
        variant("none", ToolDetailMode.None.serializer()),
        variant("summary", ToolDetailMode.Summary.serializer()),
        variant("detail", ToolDetailMode.Detail.serializer()),
    ),
)
