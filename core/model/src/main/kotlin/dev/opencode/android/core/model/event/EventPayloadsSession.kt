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
    val sessionID: String,
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
) : EventPayload

@Serializable
data class SessionAgentSelected(
    val sessionID: String,
    val agent: String,
    val previous: String? = null,
) : EventPayload

@Serializable
data class SessionModelSelected(
    val sessionID: String,
    val model: ModelRef,
    val previous: ModelRef? = null,
) : EventPayload

@Serializable
data class SessionMoved(
    val sessionID: String,
    val location: LocationRef,
    val projectID: String,
    val subpath: String? = null,
) : EventPayload

@Serializable
data class SessionRenamed(
    val sessionID: String,
    val title: String,
) : EventPayload

@Serializable
data class SessionMetadataUpdated(
    val sessionID: String,
    val metadata: SessionMetadata,
) : EventPayload

@Serializable
data class SessionPermissions(
    val sessionID: String,
    val permissions: List<PermissionRule>,
) : EventPayload

@Serializable
data class SessionViewed(
    val sessionID: String,
    val idle: Long,
) : EventPayload

@Serializable
data class SessionUsageUpdated(
    val sessionID: String,
    val cost: Double,
    val tokens: TokenUsage,
) : EventPayload

@Serializable
data class SessionDeleted(val sessionID: String) : EventPayload

@Serializable
data class SessionForked(
    val sessionID: String,
    val parentID: String,
    val boundary: ForkBoundary,
    val instructions: Map<String, String>? = null,
    val instructionEntries: List<InstructionEntry>? = null,
) : EventPayload

/** One entry of `session.forked`'s instruction snapshot. */
@Serializable
data class InstructionEntry(
    val key: String,
    val value: JsonElement,
    val removed: Boolean,
)

@Serializable
data class SessionInboxDelivered(
    val sessionID: String,
    val inboxID: String,
) : EventPayload

@Serializable
data class SessionInboxEnqueued(
    val sessionID: String,
    val inboxID: String,
    val item: InboxItem,
) : EventPayload

@Serializable
data class SessionInboxCancelled(
    val sessionID: String,
    val inboxID: String,
) : EventPayload

@Serializable
data class SessionInboxDeliveryChanged(
    val sessionID: String,
    val inboxID: String,
    val delivery: Delivery,
) : EventPayload

@Serializable
data class SessionExecutionStarted(val sessionID: String) : EventPayload

@Serializable
data class SessionExecutionSucceeded(val sessionID: String) : EventPayload

@Serializable
data class SessionExecutionFailed(
    val sessionID: String,
    val error: StructuredError,
) : EventPayload

@Serializable
data class SessionExecutionInterrupted(
    val sessionID: String,
    val reason: InterruptReason,
) : EventPayload

@Serializable
data class SessionInstructionsUpdated(
    val sessionID: String,
    val delta: Map<String, String>,
    val text: String? = null,
) : EventPayload

@Serializable
data class SessionSynthetic(
    val sessionID: String,
    val text: String,
    val description: String? = null,
    val metadata: JsonObject? = null,
) : EventPayload

@Serializable
data class SessionSkillActivated(
    val sessionID: String,
    val id: String,
    val name: String,
    val text: String,
) : EventPayload

@Serializable
data class SessionShellStarted(
    val sessionID: String,
    val shell: ShellInfo,
) : EventPayload

@Serializable
data class SessionShellEnded(
    val sessionID: String,
    val shell: ShellInfo,
    val output: ShellOutput,
) : EventPayload

@Serializable
data class SessionStepStarted(
    val sessionID: String,
    val assistantMessageID: String,
    val agent: String,
    val model: ModelRef,
    val snapshot: String? = null,
    val started: Long,
) : EventPayload

@Serializable
data class SessionStepStreamed(
    val sessionID: String,
    val assistantMessageID: String,
) : EventPayload

@Serializable
data class SessionStepEnded(
    val sessionID: String,
    val assistantMessageID: String,
    val finish: FinishReason,
    val rawFinish: String? = null,
    val providerState: JsonObject? = null,
    val cost: Double,
    val tokens: TokenUsage,
    val snapshot: String? = null,
    val files: List<String>? = null,
) : EventPayload

@Serializable
data class SessionStepFailed(
    val sessionID: String,
    val assistantMessageID: String,
    val error: StructuredError,
    val finish: FinishReason? = null,
    val rawFinish: String? = null,
    val providerState: JsonObject? = null,
    val cost: Double? = null,
    val tokens: TokenUsage? = null,
    val snapshot: String? = null,
    val files: List<String>? = null,
) : EventPayload

@Serializable
data class SessionTextStarted(
    val sessionID: String,
    val assistantMessageID: String,
    val ordinal: Long,
) : EventPayload

@Serializable
data class SessionTextDelta(
    val sessionID: String,
    val assistantMessageID: String,
    val ordinal: Long,
    val delta: String,
) : EventPayload

@Serializable
data class SessionTextEnded(
    val sessionID: String,
    val assistantMessageID: String,
    val ordinal: Long,
    val text: String,
    val state: JsonObject? = null,
) : EventPayload

@Serializable
data class SessionReasoningStarted(
    val sessionID: String,
    val assistantMessageID: String,
    val ordinal: Long,
    val state: JsonObject? = null,
) : EventPayload

@Serializable
data class SessionReasoningDelta(
    val sessionID: String,
    val assistantMessageID: String,
    val ordinal: Long,
    val delta: String,
) : EventPayload

@Serializable
data class SessionReasoningEnded(
    val sessionID: String,
    val assistantMessageID: String,
    val ordinal: Long,
    val text: String,
    val state: JsonObject? = null,
) : EventPayload

@Serializable
data class SessionToolInputStarted(
    val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val name: String,
) : EventPayload

@Serializable
data class SessionToolInputDelta(
    val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val delta: String,
) : EventPayload

@Serializable
data class SessionToolInputEnded(
    val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val text: String,
) : EventPayload

@Serializable
data class SessionToolCalled(
    val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val input: Map<String, JsonElement>,
    val executed: Boolean,
    val state: JsonObject? = null,
) : EventPayload

@Serializable
data class SessionToolProgress(
    val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val metadata: Map<String, JsonElement>,
) : EventPayload

@Serializable
data class SessionToolSuccess(
    val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val content: List<ToolContent>,
    val metadata: Map<String, JsonElement>? = null,
    val executed: Boolean,
    val resultState: JsonObject? = null,
) : EventPayload

@Serializable
data class SessionToolFailed(
    val sessionID: String,
    val assistantMessageID: String,
    val id: String,
    val error: StructuredError,
    val content: List<ToolContent>? = null,
    val metadata: Map<String, JsonElement>? = null,
    val executed: Boolean,
    val resultState: JsonObject? = null,
) : EventPayload

@Serializable
data class SessionRetryScheduled(
    val sessionID: String,
    val assistantMessageID: String,
    val attempt: Long,
    val at: Long,
    val error: StructuredError,
) : EventPayload

@Serializable
data class SessionCompactionStarted(
    val sessionID: String,
    val reason: CompactionReason,
    val recent: String,
    val inputID: String? = null,
) : EventPayload

@Serializable
data class SessionCompactionDelta(
    val sessionID: String,
    val text: String,
) : EventPayload

@Serializable
data class SessionCompactionEnded(
    val sessionID: String,
    val reason: CompactionReason,
    val model: ModelRef? = null,
    val providerState: JsonObject? = null,
    val providerContext: ProviderContext? = null,
    val text: String,
    val recent: String,
    val cost: Double? = null,
    val tokens: TokenUsage? = null,
) : EventPayload

@Serializable
data class SessionCompactionFailed(
    val sessionID: String,
    val reason: CompactionReason,
    val error: StructuredError,
    val inputID: String? = null,
    val cost: Double? = null,
    val tokens: TokenUsage? = null,
) : EventPayload

@Serializable
data class SessionRevertStaged(
    val sessionID: String,
    val revert: SessionRevert,
) : EventPayload

@Serializable
data class SessionRevertCleared(val sessionID: String) : EventPayload

@Serializable
data class SessionRevertCommitted(
    val sessionID: String,
    val to: String,
) : EventPayload

@Serializable
data class SessionStatusUpdated(
    val sessionID: String,
    val status: SessionStatus,
) : EventPayload

@Serializable
data class SessionIdle(val sessionID: String) : EventPayload
