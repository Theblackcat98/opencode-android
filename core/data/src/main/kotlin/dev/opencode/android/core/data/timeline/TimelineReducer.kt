package dev.opencode.android.core.data.timeline

import dev.opencode.android.core.model.AssistantContent
import dev.opencode.android.core.model.FinishReason
import dev.opencode.android.core.model.InboxItem
import dev.opencode.android.core.model.InterruptReason
import dev.opencode.android.core.model.Outcome
import dev.opencode.android.core.model.SessionMessage
import dev.opencode.android.core.model.StructuredError
import dev.opencode.android.core.model.ToolContent
import dev.opencode.android.core.model.ToolState
import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.event.SessionCompactionDelta
import dev.opencode.android.core.model.event.SessionCompactionEnded
import dev.opencode.android.core.model.event.SessionCompactionFailed
import dev.opencode.android.core.model.event.SessionCompactionStarted
import dev.opencode.android.core.model.event.SessionExecutionFailed
import dev.opencode.android.core.model.event.SessionExecutionInterrupted
import dev.opencode.android.core.model.event.SessionExecutionStarted
import dev.opencode.android.core.model.event.SessionExecutionSucceeded
import dev.opencode.android.core.model.event.SessionInboxCancelled
import dev.opencode.android.core.model.event.SessionInboxDelivered
import dev.opencode.android.core.model.event.SessionInboxEnqueued
import dev.opencode.android.core.model.event.SessionModelSelected
import dev.opencode.android.core.model.event.SessionReasoningDelta
import dev.opencode.android.core.model.event.SessionReasoningEnded
import dev.opencode.android.core.model.event.SessionReasoningStarted
import dev.opencode.android.core.model.event.SessionRevertCommitted
import dev.opencode.android.core.model.event.SessionRetryScheduled
import dev.opencode.android.core.model.event.SessionShellEnded
import dev.opencode.android.core.model.event.SessionShellStarted
import dev.opencode.android.core.model.event.SessionStepEnded
import dev.opencode.android.core.model.event.SessionStepFailed
import dev.opencode.android.core.model.event.SessionStepStarted
import dev.opencode.android.core.model.event.SessionStepStreamed
import dev.opencode.android.core.model.event.SessionSynthetic
import dev.opencode.android.core.model.event.SessionTextDelta
import dev.opencode.android.core.model.event.SessionTextEnded
import dev.opencode.android.core.model.event.SessionTextStarted
import dev.opencode.android.core.model.event.SessionToolCalled
import dev.opencode.android.core.model.event.SessionToolFailed
import dev.opencode.android.core.model.event.SessionToolInputDelta
import dev.opencode.android.core.model.event.SessionToolInputEnded
import dev.opencode.android.core.model.event.SessionToolInputStarted
import dev.opencode.android.core.model.event.SessionToolProgress
import dev.opencode.android.core.model.event.SessionToolSuccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A pending inbox item as the timeline shows it while it waits to be delivered.
 *
 * Phase 2 only reads these (`session.inbox.list` and the `session.inbox.*` events); Phase 3 adds
 * the optimistic path, in which the same record is created locally and reconciled by
 * `session.inbox.enqueued`.
 */
data class PendingInboxItem(
    val id: String,
    val created: Long,
    val item: InboxItem,
)

/**
 * One session's projected timeline, oldest message first.
 *
 * The list is the projection the server returns plus everything the event stream has added since.
 * [index] maps a message id to its position so that a streaming event costs O(1) to apply: a
 * session with a thousand messages receives thousands of `text.delta` frames per turn, and a
 * linear scan per frame is what makes a live timeline drop frames.
 *
 * [pending] is the durable inbox as the events have revealed it. It is part of the state rather
 * than a separate store because a delivered item and its message share an id, and the two have to
 * move together.
 *
 * All three fields move together: [TimelineReducer] is the only writer, and [of] is how the REST
 * load path and the tests build a state, so the index can never disagree with the list.
 */
data class TimelineState(
    val messages: List<SessionMessage>,
    val index: Map<String, Int>,
    val pending: List<PendingInboxItem> = emptyList(),
) {
    /** The message with [id], or `null` when the timeline does not hold it. */
    operator fun get(id: String): SessionMessage? = index[id]?.let(messages::getOrNull)

    /** The last assistant message that has not completed, which is the one still streaming. */
    val activeAssistant: SessionMessage.Assistant?
        get() {
            for (message in messages.asReversed()) {
                if (message is SessionMessage.Assistant && message.time.completed == null) return message
            }
            return null
        }

    /** True while any tool of any assistant message is still streaming or running. */
    val hasUnfinishedTool: Boolean
        get() = messages.any { message ->
            message is SessionMessage.Assistant &&
                message.content.any { part ->
                    part is AssistantContent.Tool && part.state.isPending
                }
        }

    companion object {
        val Empty = TimelineState(emptyList(), emptyMap())

        /** Builds a state from [messages], oldest first, with a matching index. */
        fun of(
            messages: List<SessionMessage>,
            pending: List<PendingInboxItem> = emptyList(),
        ): TimelineState {
            val index = HashMap<String, Int>(messages.size)
            messages.forEachIndexed { position, message -> index.putIfAbsent(message.id, position) }
            return TimelineState(messages, index, pending)
        }
    }
}

/**
 * The transcript is a projection, and this is the projector (plan §2.7).
 *
 * REST returns projected messages; the event stream mutates that projection, keyed by assistant
 * message id plus ordinal or tool id. The reducer is a pure `(state, event) -> state`: it never
 * reads a clock, never performs I/O and never mutates [state], so a recorded stream replays to the
 * same result every time and a divergence from the REST projection is reproducible.
 *
 * The semantics are ported from the reference client (`@opencode/client` `solid/data.ts`), the
 * only other implementation of them. Three rules there look like oversights and are not:
 *
 *  - `text`, `reasoning` and `tool` events address the **last** matching part, not the part named
 *    by `ordinal`. A part is created by its `*.started` event, which always appends, so the last
 *    one is the only one that can still be streaming; a late `*.ended` for an earlier ordinal
 *    cannot corrupt a newer part.
 *  - `session.step.started` for a message that already exists **resets** it. That is the retry
 *    path: a failed step is re-run under the same assistant message id.
 *  - A tool that has already completed ignores `tool.success`, and one that has already failed
 *    ignores `tool.failed`. Out-of-order frames are therefore harmless.
 *
 * Where the reference client performs I/O to finish a transition, this reducer only folds what the
 * event carries and the store schedules the refetch. The one place that matters is
 * `session.model.selected`, where the reference re-reads the marker message to learn `previous`;
 * the payload already carries it, so the reducer uses it and the two agree.
 */
object TimelineReducer {

    /** The message id the server would give the message that [eventId] announces. */
    fun messageIdFromEvent(eventId: String): String =
        if (eventId.startsWith(EVENT_PREFIX)) MESSAGE_PREFIX + eventId.substring(EVENT_PREFIX.length) else eventId

    /**
     * Applies [event] to [state].
     *
     * [sessionID] is the timeline's own session. An event for another session returns [state]
     * unchanged, so one dispatcher can feed every open timeline without pre-filtering.
     */
    fun reduce(state: TimelineState, event: Event, sessionID: String): TimelineState {
        if ((event.payload as? EventPayload.SessionScoped)?.sessionID != sessionID) return state
        val draft = Draft(state)
        when (val payload = event.payload) {
            is SessionStepStarted -> draft.stepStarted(event, payload)
            is SessionStepStreamed -> draft.editAssistant(payload.assistantMessageID) {
                it.copy(time = it.time.copy(streamed = event.created ?: it.time.streamed))
            }

            is SessionStepEnded -> draft.editAssistant(payload.assistantMessageID) { assistant ->
                assistant.copy(
                    time = assistant.time.copy(completed = event.created),
                    finish = payload.finish,
                    rawFinish = payload.rawFinish,
                    providerState = payload.providerState,
                    cost = payload.cost,
                    tokens = payload.tokens,
                    snapshot = assistant.snapshot.merged(payload.snapshot, payload.files),
                )
            }

            is SessionStepFailed -> draft.editAssistant(payload.assistantMessageID) { assistant ->
                assistant.copy(
                    time = assistant.time.copy(completed = event.created),
                    finish = payload.finish ?: FinishReason.Error,
                    rawFinish = payload.rawFinish,
                    providerState = payload.providerState,
                    error = payload.error,
                    retry = null,
                    cost = payload.cost ?: assistant.cost,
                    tokens = payload.tokens ?: assistant.tokens,
                    snapshot = assistant.snapshot.merged(payload.snapshot, payload.files),
                )
            }

            is SessionTextStarted -> draft.editAssistant(payload.assistantMessageID) { assistant ->
                assistant.copy(content = assistant.content + AssistantContent.Text(""))
            }

            is SessionTextDelta -> draft.editLast(AssistantContent::isText, payload.assistantMessageID) {
                @Suppress("UNCHECKED_CAST")
                (it as AssistantContent.Text).copy(text = it.text + payload.delta)
            }

            is SessionTextEnded -> draft.editLast(AssistantContent::isText, payload.assistantMessageID) {
                @Suppress("UNCHECKED_CAST")
                (it as AssistantContent.Text).copy(text = payload.text)
            }

            is SessionReasoningStarted -> draft.editAssistant(payload.assistantMessageID) { assistant ->
                assistant.copy(
                    content = assistant.content + AssistantContent.Reasoning(
                        text = "",
                        state = payload.state,
                        time = AssistantContent.Reasoning.Time(created = event.created ?: 0L),
                    ),
                )
            }

            is SessionReasoningDelta -> draft.editLast(AssistantContent::isOpenReasoning, payload.assistantMessageID) {
                @Suppress("UNCHECKED_CAST")
                (it as AssistantContent.Reasoning).copy(text = it.text + payload.delta)
            }

            is SessionReasoningEnded -> draft.editLast(AssistantContent::isOpenReasoning, payload.assistantMessageID) {
                @Suppress("UNCHECKED_CAST")
                val reasoning = it as AssistantContent.Reasoning
                reasoning.copy(
                    text = payload.text,
                    state = payload.state ?: reasoning.state,
                    time = AssistantContent.Reasoning.Time(
                        created = reasoning.time?.created ?: (event.created ?: 0L),
                        completed = event.created,
                    ),
                )
            }

            is SessionToolInputStarted -> draft.editAssistant(payload.assistantMessageID) { assistant ->
                assistant.copy(
                    content = assistant.content + AssistantContent.Tool(
                        id = payload.id,
                        name = payload.name,
                        state = ToolState.Streaming(""),
                        time = AssistantContent.Tool.Time(created = event.created ?: 0L),
                    ),
                )
            }

            is SessionToolInputDelta -> draft.editTool(payload.assistantMessageID, payload.id) { tool ->
                val streaming = tool.state as? ToolState.Streaming
                    ?: return@editTool tool
                tool.copy(state = streaming.copy(input = streaming.input + payload.delta))
            }

            is SessionToolInputEnded -> draft.editTool(payload.assistantMessageID, payload.id) { tool ->
                val streaming = tool.state as? ToolState.Streaming
                    ?: return@editTool tool
                tool.copy(state = streaming.copy(input = payload.text))
            }

            is SessionToolCalled -> draft.editTool(payload.assistantMessageID, payload.id) { tool ->
                tool.copy(
                    time = tool.time.copy(ran = event.created),
                    executed = payload.executed,
                    providerState = payload.state,
                    state = ToolState.Running(input = payload.input, metadata = emptyMap()),
                )
            }

            is SessionToolProgress -> draft.editTool(payload.assistantMessageID, payload.id) { tool ->
                val running = tool.state as? ToolState.Running
                    ?: return@editTool tool
                tool.copy(state = running.copy(metadata = payload.metadata))
            }

            is SessionToolSuccess -> draft.editTool(payload.assistantMessageID, payload.id) { tool ->
                val running = tool.state as? ToolState.Running ?: return@editTool tool
                tool.copy(
                    state = ToolState.Completed(
                        input = running.input,
                        content = payload.content,
                        metadata = payload.metadata,
                    ),
                    executed = payload.executed || tool.executed == true,
                    providerResultState = payload.resultState,
                    time = tool.time.copy(completed = event.created),
                )
            }

            is SessionToolFailed -> draft.editTool(payload.assistantMessageID, payload.id) { tool ->
                // Only a tool that is still in flight can fail; a late failure must not overwrite
                // a result that already arrived. A streaming tool never had parsed arguments.
                val input: Map<String, JsonElement> = when (val inFlight = tool.state) {
                    is ToolState.Running -> inFlight.input
                    is ToolState.Streaming -> emptyMap()
                    else -> return@editTool tool
                }
                tool.copy(
                    state = ToolState.Error(
                        input = input,
                        error = payload.error,
                        content = payload.content,
                        metadata = payload.metadata,
                    ),
                    executed = payload.executed || tool.executed == true,
                    providerResultState = payload.resultState,
                    time = tool.time.copy(completed = event.created),
                )
            }

            is SessionRetryScheduled -> draft.editAssistant(payload.assistantMessageID) { assistant ->
                assistant.copy(
                    retry = SessionMessage.Assistant.Retry(
                        attempt = payload.attempt.toInt(),
                        at = payload.at,
                        error = payload.error,
                    ),
                )
            }

            is SessionSynthetic -> draft.append(
                SessionMessage.Synthetic(
                    id = messageIdFromEvent(event.id),
                    metadata = payload.metadata,
                    time = SessionMessage.CreatedTime(event.created ?: 0L),
                    text = payload.text,
                    description = payload.description,
                ),
            )

            is SessionShellStarted -> draft.append(shellMessage(event, payload))

            is SessionShellEnded -> {
                draft.updateLastShell(payload.shell.id) { shell ->
                    shell.copy(
                        status = payload.shell.status,
                        exit = payload.shell.exit?.toDouble(),
                        output = payload.output,
                        time = shell.time.copy(completed = event.created),
                    )
                }
            }

            // The input id names a pending inbox item, which the delivery already dropped; the
            // message is materialized here for the first time.
            is SessionCompactionStarted -> {
                draft.append(
                    SessionMessage.Compaction(
                        id = payload.inputID ?: TimelineReducer.messageIdFromEvent(event.id),
                        time = SessionMessage.CreatedTime(event.created ?: 0L),
                        status = SessionMessage.Compaction.RUNNING,
                        reason = payload.reason,
                        summary = "",
                        recent = payload.recent,
                    ),
                )
            }

            is SessionCompactionDelta -> draft.updateRunningCompaction { compaction ->
                compaction.copy(summary = compaction.summary.orEmpty() + payload.text)
            }

            is SessionCompactionEnded -> draft.updateRunningCompaction { compaction ->
                compaction.copy(
                    status = SessionMessage.Compaction.COMPLETED,
                    reason = payload.reason,
                    model = payload.model,
                    providerState = payload.providerState,
                    providerContext = payload.providerContext,
                    summary = payload.text,
                    recent = payload.recent,
                    cost = payload.cost,
                    tokens = payload.tokens,
                )
            } ?: draft.append(
                SessionMessage.Compaction(
                    id = messageIdFromEvent(event.id),
                    time = SessionMessage.CreatedTime(event.created ?: 0L),
                    status = SessionMessage.Compaction.COMPLETED,
                    reason = payload.reason,
                    summary = payload.text,
                    recent = payload.recent,
                    model = payload.model,
                    providerState = payload.providerState,
                    providerContext = payload.providerContext,
                    cost = payload.cost,
                    tokens = payload.tokens,
                ),
            )

            // A failed compaction keeps the running block's id and time but loses its summary:
            // the partial text is what failed, and the server does not project it.
            is SessionCompactionFailed -> draft.finishRunningCompaction { current ->
                SessionMessage.Compaction(
                    id = current?.id ?: payload.inputID ?: TimelineReducer.messageIdFromEvent(event.id),
                    metadata = current?.metadata,
                    time = current?.time ?: SessionMessage.CreatedTime(event.created ?: 0L),
                    status = SessionMessage.Compaction.FAILED,
                    reason = payload.reason,
                    error = payload.error,
                    cost = payload.cost,
                    tokens = payload.tokens,
                )
            }

            is SessionInboxEnqueued -> {
                // A compaction is materialized by session.compaction.started, which carries the
                // summary as it streams; every other payload has a message shape of its own.
                if (payload.item !is InboxItem.Compaction) {
                    materializeInboxItem(payload.inboxID, event.created ?: 0L, payload.item)
                        ?.let(draft::append)
                }
                draft.upsertPending(PendingInboxItem(payload.inboxID, event.created ?: 0L, payload.item))
            }

            is SessionInboxDelivered -> {
                draft.dropPending(payload.inboxID)
                // Delivery re-stamps the message's creation time, which is what puts a delivered
                // prompt after everything the agent did with it.
                draft.touch(payload.inboxID, event.created)
            }

            is SessionInboxCancelled -> {
                draft.dropPending(payload.inboxID)
                draft.remove(payload.inboxID)
            }

            is SessionModelSelected -> draft.append(
                SessionMessage.ModelSwitched(
                    id = messageIdFromEvent(event.id),
                    time = SessionMessage.CreatedTime(event.created ?: 0L),
                    model = payload.model,
                    previous = payload.previous,
                ),
            )

            is SessionExecutionStarted -> draft.clearRetryOnActiveAssistant()

            is SessionExecutionSucceeded -> draft.turnFinished(Outcome.Succeeded, event)

            is SessionExecutionFailed -> draft.turnFinished(Outcome.Failed, event)

            is SessionExecutionInterrupted -> {
                // A shutdown is the server going away, not a turn the user interrupted: the
                // reference client records no outcome for it, and neither does this.
                if (payload.reason != InterruptReason.Shutdown) {
                    draft.turnFinished(Outcome.Interrupted, event)
                }
            }

            is SessionRevertCommitted -> draft.dropFrom(payload.to)

            // Everything else is catalog, connection or session metadata; the session store and
            // the SyncedResource catalogs own those.
            else -> Unit
        }
        return draft.commit()
    }

    private const val EVENT_PREFIX = "evt_"
    private const val MESSAGE_PREFIX = "msg_"

    private fun shellMessage(event: Event, payload: SessionShellStarted): SessionMessage.Shell =
        SessionMessage.Shell(
            id = messageIdFromEvent(event.id),
            // The server marks a `!` shell command as background work. The envelope metadata
            // carries the rest of the context, so both fold into one object, as in the reference.
            metadata = if (payload.shell.metadata["background"] == JsonPrimitive(true)) {
                buildMap {
                    event.metadata?.forEach { (key, value) -> put(key, value) }
                    put("background", JsonPrimitive(true))
                }
            } else {
                event.metadata?.toMap()
            },
            time = SessionMessage.Shell.Time(created = event.created ?: payload.shell.time.started),
            shellID = payload.shell.id,
            command = payload.shell.command,
            status = payload.shell.status,
            exit = payload.shell.exit?.toDouble(),
        )

    /**
     * The message a pending inbox item projects to, or `null` for a compaction and a move, which
     * the server materializes itself.
     */
    private fun materializeInboxItem(
        id: String,
        created: Long,
        item: InboxItem,
    ): SessionMessage? = when (item) {
        is InboxItem.User -> SessionMessage.User(
            id = id,
            metadata = item.payload.metadata,
            time = SessionMessage.CreatedTime(created),
            text = item.payload.text,
            files = item.payload.files,
            agents = item.payload.agents,
            skills = item.payload.skills,
        )

        is InboxItem.Synthetic -> SessionMessage.Synthetic(
            id = id,
            metadata = item.payload.metadata,
            time = SessionMessage.CreatedTime(created),
            text = item.payload.text,
            description = item.payload.description,
        )

        is InboxItem.Compaction, is InboxItem.Move, is InboxItem.Unknown -> null
    }
}

/**
 * Folds a step's git snapshot into the message's.
 *
 * The reference client only copies `end` and drops `files`, which the server does project; taking
 * the whole snapshot from the event is what keeps the replayed transcript byte-identical to the
 * REST projection, and the golden test is what caught the omission.
 */
private fun SessionMessage.Assistant.Snapshot?.merged(
    end: String?,
    files: List<String>?,
): SessionMessage.Assistant.Snapshot? {
    if (end == null && files == null) return this
    val base = this ?: SessionMessage.Assistant.Snapshot()
    return base.copy(end = end ?: base.end, files = files ?: base.files)
}

private fun SessionMessage.Assistant.Snapshot?.mergedStart(
    start: String?,
): SessionMessage.Assistant.Snapshot? {
    if (start == null) return this
    val base = this ?: SessionMessage.Assistant.Snapshot()
    return base.copy(start = start)
}

private fun AssistantContent.isText(): Boolean = this is AssistantContent.Text

private fun AssistantContent.isOpenReasoning(): Boolean = this is AssistantContent.Reasoning && time?.completed == null

/** True while a tool call is still streaming its arguments or running. */
val ToolState.isPending: Boolean
    get() = this is ToolState.Streaming || this is ToolState.Running

/** The error of a failed tool, or `null`. */
val ToolState.failureOrNull: StructuredError?
    get() = (this as? ToolState.Error)?.error

/** Every text part of a completed tool, which is what a shell or search card shows. */
val ToolState.Completed.textOutput: String
    get() = content.filterIsInstance<ToolContent.Text>().joinToString("\n") { it.text }

/** The session an event belongs to, or `null` for an envelope that names no session. */
val Event.sessionIDOrNull: String? get() = (payload as? EventPayload.SessionScoped)?.sessionID

/**
 * The mutable working copy a reduce pass writes into.
 *
 * One array copy per event keeps the per-frame cost to a raw array copy plus the edit itself, and
 * the caller's [TimelineState] is never touched. `commit` returns the original state when nothing
 * changed, so an event the reducer does not apply costs the store no recomposition.
 */
private class Draft(private val state: TimelineState) {

    private val messages = ArrayList(state.messages)
    private val index = HashMap(state.index)
    private var pending: List<PendingInboxItem>? = null

    fun editAssistant(messageID: String, fn: (SessionMessage.Assistant) -> SessionMessage.Assistant) {
        val position = index[messageID] ?: return
        val message = messages.getOrNull(position) as? SessionMessage.Assistant ?: return
        messages[position] = fn(message)
    }

    /** Applies [fn] to the last part of the assistant message that [predicate] accepts. */
    fun editLast(
        predicate: (AssistantContent) -> Boolean,
        messageID: String,
        fn: (AssistantContent) -> AssistantContent,
    ) {
        val position = index[messageID] ?: return
        val message = messages.getOrNull(position) as? SessionMessage.Assistant ?: return
        val part = message.content.indexOfLast(predicate)
        if (part < 0) return
        val content = message.content.toMutableList()
        content[part] = fn(content[part])
        messages[position] = message.copy(content = content)
    }

    fun editTool(messageID: String, toolID: String, fn: (AssistantContent.Tool) -> AssistantContent.Tool) {
        editLast({ it is AssistantContent.Tool && it.id == toolID }, messageID) { part ->
            @Suppress("UNCHECKED_CAST")
            fn(part as AssistantContent.Tool)
        }
    }

    fun append(message: SessionMessage) {
        if (index.containsKey(message.id)) return
        index[message.id] = messages.size
        messages.add(message)
    }

    fun remove(id: String) {
        val position = index.remove(id) ?: return
        messages.removeAt(position)
        reindexFrom(position)
    }

    /** Re-stamps a message's creation time and moves it to the end, which is what delivery does. */
    fun touch(id: String, created: Long?) {
        val position = index[id] ?: return
        val message = messages[position]
        val restamped = when (message) {
            is SessionMessage.User -> message.copy(
                time = SessionMessage.CreatedTime(created ?: message.created),
            )

            is SessionMessage.Synthetic -> message.copy(
                time = SessionMessage.CreatedTime(created ?: message.created),
            )

            else -> return
        }
        messages.removeAt(position)
        messages.add(restamped)
        reindexFrom(position)
    }

    /** Removes every message at or after [fromId], which is how a committed revert truncates. */
    fun dropFrom(fromId: String) {
        val start = messages.indexOfFirst { it.id >= fromId }
        if (start < 0) return
        for (position in start until messages.size) index.remove(messages[position].id)
        messages.subList(start, messages.size).clear()
    }

    private fun reindexFrom(start: Int) {
        for (position in start until messages.size) {
            index[messages[position].id] = position
        }
    }

    fun stepStarted(event: Event, payload: SessionStepStarted) {
        val existing = index[payload.assistantMessageID]?.let { messages.getOrNull(it) }
        if (existing is SessionMessage.Assistant) {
            // A retry re-runs the same assistant message id, so the step restarts from scratch.
            messages[index.getValue(payload.assistantMessageID)] = existing.copy(
                agent = payload.agent,
                model = payload.model,
                retry = null,
                error = null,
                finish = null,
                rawFinish = null,
                providerState = null,
                snapshot = existing.snapshot.mergedStart(payload.snapshot),
                time = SessionMessage.Assistant.Time(created = payload.started),
            )
            return
        }
        // A new step closes the previous one, so a timeline never shows two live assistants.
        val open = messages.indexOfLast { it is SessionMessage.Assistant && it.time.completed == null }
        if (open >= 0) {
            val message = messages[open] as SessionMessage.Assistant
            messages[open] = message.copy(
                retry = null,
                time = message.time.copy(completed = event.created),
            )
        }
        append(
            SessionMessage.Assistant(
                id = payload.assistantMessageID,
                metadata = event.metadata,
                time = SessionMessage.Assistant.Time(created = payload.started),
                agent = payload.agent,
                model = payload.model,
                content = emptyList(),
                snapshot = SessionMessage.Assistant.Snapshot(start = payload.snapshot),
            ),
        )
    }

    fun clearRetryOnActiveAssistant() {
        val open = messages.indexOfLast { it is SessionMessage.Assistant && it.time.completed == null }
        if (open < 0) return
        val message = messages[open] as SessionMessage.Assistant
        messages[open] = message.copy(retry = null)
    }

    fun turnFinished(outcome: Outcome, event: Event) {
        clearRetryOnActiveAssistant()
        append(
            SessionMessage.Idle(
                id = TimelineReducer.messageIdFromEvent(event.id),
                time = SessionMessage.CreatedTime(event.created ?: 0L),
                outcome = outcome,
            ),
        )
    }

    fun updateLastShell(shellID: String, fn: (SessionMessage.Shell) -> SessionMessage.Shell) {
        val position = messages.indexOfLast { it is SessionMessage.Shell && it.shellID == shellID }
        if (position < 0) return
        messages[position] = fn(messages[position] as SessionMessage.Shell)
    }

    /**
     * Folds into the compaction that is still running, in place.
     *
     * The `null` arm of the caller's `?:` is the "no compaction was running" case, which appends a
     * finished one instead: the server can end a compaction whose start this client never saw,
     * for example after a reconnect.
     */
    fun updateRunningCompaction(
        fn: (SessionMessage.Compaction) -> SessionMessage.Compaction,
    ): SessionMessage.Compaction? {
        val position = runningCompaction()
        if (position < 0) return null
        val updated = fn(messages[position] as SessionMessage.Compaction)
        messages[position] = updated
        return updated
    }

    /**
     * Replaces the compaction that is still running with [fn]'s result, keeping its position.
     *
     * Unlike [updateRunningCompaction] this builds a new message rather than editing fields, which
     * is what a failure needs: a failed compaction must not keep the summary it accumulated.
     */
    fun finishRunningCompaction(fn: (SessionMessage.Compaction?) -> SessionMessage.Compaction) {
        val position = runningCompaction()
        if (position < 0) {
            append(fn(null))
            return
        }
        messages[position] = fn(messages[position] as SessionMessage.Compaction)
    }

    private fun runningCompaction(): Int = messages.indexOfLast {
        it is SessionMessage.Compaction && it.status == SessionMessage.Compaction.RUNNING
    }

    fun upsertPending(item: PendingInboxItem) {
        val current = pending ?: state.pending
        pending = current.filterNot { it.id == item.id } + item
    }

    fun dropPending(id: String) {
        val current = pending ?: state.pending
        if (current.none { it.id == id }) return
        pending = current.filterNot { it.id == id }
    }

    /** The new state, or the old one when the event changed nothing. */
    fun commit(): TimelineState {
        if (messages.size == state.messages.size && index.size == state.index.size && pending == null) {
            if (messages.indices.all { messages[it] === state.messages[it] }) return state
        }
        return TimelineState(messages, index, pending ?: state.pending)
    }
}
