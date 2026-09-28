package dev.opencode.android.core.model.event

import dev.opencode.android.core.model.CompactionReason
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.FinishReason
import dev.opencode.android.core.model.ForkBoundary
import dev.opencode.android.core.model.InboxItem
import dev.opencode.android.core.model.InterruptReason
import dev.opencode.android.core.model.LocationRef
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.PermissionRule
import dev.opencode.android.core.model.ProviderContext
import dev.opencode.android.core.model.SessionMetadata
import dev.opencode.android.core.model.SessionRevert
import dev.opencode.android.core.model.SessionStatus
import dev.opencode.android.core.model.ShellInfo
import dev.opencode.android.core.model.ShellOutput
import dev.opencode.android.core.model.StructuredError
import dev.opencode.android.core.model.TokenUsage
import dev.opencode.android.core.model.ToolContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Payloads of the `session.*` event family. Field-for-field with the `V2Event*` declarations. */
@Serializable
data class SessionCreated(
    override val sessionID: String,
    val projectID: String,
    val location: LocationRef,
    val subpath: String? = null,
    val parentID: String? = null,
    val slug: String,
    val title: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val metadata: SessionMetadata? = null,
    val permissions: List<PermissionRule>? = null,
    val version: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionAgentSelected(
    override val sessionID: String,
    val agent: String,
    val previous: String? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionModelSelected(
    override val sessionID: String,
    val model: ModelRef,
    val previous: ModelRef? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionMoved(
    override val sessionID: String,
    val location: LocationRef,
    val projectID: String,
    val subpath: String? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionRenamed(
    override val sessionID: String,
    val title: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionMetadataUpdated(
    override val sessionID: String,
    val metadata: SessionMetadata,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionPermissions(
    override val sessionID: String,
    val permissions: List<PermissionRule>,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionViewed(
    override val sessionID: String,
    val idle: Long,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionUsageUpdated(
    override val sessionID: String,
    val cost: Double,
    val tokens: TokenUsage,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionDeleted(override val sessionID: String) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionForked(
    override val sessionID: String,
    val parentID: String,
    val boundary: ForkBoundary,
    val instructions: Map<String, String>? = null,
    val instructionEntries: List<InstructionEntry>? = null,
) : EventPayload, EventPayload.SessionScoped

/** One entry of `session.forked`'s instruction snapshot. */
@Serializable
data class InstructionEntry(
    val key: String,
    val value: JsonElement,
    val removed: Boolean,
)

@Serializable
data class SessionInboxDelivered(
    override val sessionID: String,
    val inboxID: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionInboxEnqueued(
    override val sessionID: String,
    val inboxID: String,
    val item: InboxItem,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionInboxCancelled(
    override val sessionID: String,
    val inboxID: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionInboxDeliveryChanged(
    override val sessionID: String,
    val inboxID: String,
    val delivery: Delivery,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionExecutionStarted(override val sessionID: String) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionExecutionSucceeded(override val sessionID: String) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionExecutionFailed(
    override val sessionID: String,
    val error: StructuredError,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionExecutionInterrupted(
    override val sessionID: String,
    val reason: InterruptReason,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionInstructionsUpdated(
    override val sessionID: String,
    val delta: Map<String, String>,
    val text: String? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionSynthetic(
    override val sessionID: String,
    val text: String,
    val description: String? = null,
    val metadata: JsonObject? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionSkillActivated(
    override val sessionID: String,
    val id: String,
    val name: String,
    val text: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionShellStarted(
    override val sessionID: String,
    val shell: ShellInfo,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionShellEnded(
    override val sessionID: String,
    val shell: ShellInfo,
    val output: ShellOutput,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionStepStarted(
    override val sessionID: String,
    val assistantMessageID: String,
    val agent: String,
    val model: ModelRef,
    val snapshot: String? = null,
    val started: Long,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionStepStreamed(
    override val sessionID: String,
    val assistantMessageID: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionStepEnded(
    override val sessionID: String,
    val assistantMessageID: String,
    val finish: FinishReason,
    val rawFinish: String? = null,
    val providerState: JsonObject? = null,
    val cost: Double,
    val tokens: TokenUsage,
    val snapshot: String? = null,
    val files: List<String>? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionStepFailed(
    override val sessionID: String,
    val assistantMessageID: String,
    val error: StructuredError,
    val finish: FinishReason? = null,
    val rawFinish: String? = null,
    val providerState: JsonObject? = null,
    val cost: Double? = null,
    val tokens: TokenUsage? = null,
    val snapshot: String? = null,
    val files: List<String>? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionTextStarted(
    override val sessionID: String,
    val assistantMessageID: String,
    val ordinal: Long,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionTextDelta(
    override val sessionID: String,
    val assistantMessageID: String,
    val ordinal: Long,
    val delta: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionTextEnded(
    override val sessionID: String,
    val assistantMessageID: String,
    val ordinal: Long,
    val text: String,
    val state: JsonObject? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionReasoningStarted(
    override val sessionID: String,
    val assistantMessageID: String,
    val ordinal: Long,
    val state: JsonObject? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionReasoningDelta(
    override val sessionID: String,
    val assistantMessageID: String,
    val ordinal: Long,
    val delta: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionReasoningEnded(
    override val sessionID: String,
    val assistantMessageID: String,
    val ordinal: Long,
    val text: String,
    val state: JsonObject? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionToolInputStarted(
    override val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val name: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionToolInputDelta(
    override val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val delta: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionToolInputEnded(
    override val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val text: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionToolCalled(
    override val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val input: Map<String, JsonElement>,
    val executed: Boolean,
    val state: JsonObject? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionToolProgress(
    override val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val metadata: Map<String, JsonElement>,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionToolSuccess(
    override val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val content: List<ToolContent>,
    val metadata: Map<String, JsonElement>? = null,
    val executed: Boolean,
    val resultState: JsonObject? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionToolFailed(
    override val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val error: StructuredError,
    val content: List<ToolContent>? = null,
    val metadata: Map<String, JsonElement>? = null,
    val executed: Boolean,
    val resultState: JsonObject? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionRetryScheduled(
    override val sessionID: String,
    val assistantMessageID: String,
    val attempt: Long,
    val at: Long,
    val error: StructuredError,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionCompactionStarted(
    override val sessionID: String,
    val reason: CompactionReason,
    val recent: String,
    val inputID: String? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionCompactionDelta(
    override val sessionID: String,
    val text: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionCompactionEnded(
    override val sessionID: String,
    val reason: CompactionReason,
    val model: ModelRef? = null,
    val providerState: JsonObject? = null,
    val providerContext: ProviderContext? = null,
    val text: String,
    val recent: String,
    val cost: Double? = null,
    val tokens: TokenUsage? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionCompactionFailed(
    override val sessionID: String,
    val reason: CompactionReason,
    val error: StructuredError,
    val inputID: String? = null,
    val cost: Double? = null,
    val tokens: TokenUsage? = null,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionRevertStaged(
    override val sessionID: String,
    val revert: SessionRevert,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionRevertCleared(override val sessionID: String) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionRevertCommitted(
    override val sessionID: String,
    val to: String,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionStatusUpdated(
    override val sessionID: String,
    val status: SessionStatus,
) : EventPayload, EventPayload.SessionScoped

@Serializable
data class SessionIdle(override val sessionID: String) : EventPayload, EventPayload.SessionScoped