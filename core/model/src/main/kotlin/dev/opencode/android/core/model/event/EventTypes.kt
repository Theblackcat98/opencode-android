package dev.opencode.android.core.model.event

import kotlin.reflect.KClass
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Chooses the [EventPayload] for an envelope's `type` string. Covers every type in
 * `api/opencode-2.0.x/events.json`: unknown types decode to [EventPayload.Unknown] and
 * `rpc.*` to [EventPayload.Rpc].
 *
 * A test asserts [knownTypes] still matches `events.json`, so a regenerated event list
 * without a matching registry entry fails the build instead of silently degrading to
 * `Unknown`.
 */
internal object EventTypes {
    private data class Binding<T : EventPayload>(
        val type: String,
        val clazz: KClass<T>,
        val serializer: KSerializer<T>,
    )

    private val bindings: List<Binding<*>> = listOf(
        Binding("agent.updated", EventPayload.AgentUpdated::class, EventPayload.AgentUpdated.serializer()),
        Binding("command.updated", EventPayload.CommandUpdated::class, EventPayload.CommandUpdated.serializer()),
        Binding("config.updated", EventPayload.ConfigUpdated::class, EventPayload.ConfigUpdated.serializer()),
        Binding("credential.switched", CredentialSwitched::class, CredentialSwitched.serializer()),
        Binding("credential.updated", EventPayload.CredentialUpdated::class, EventPayload.CredentialUpdated.serializer()),
        Binding("filesystem.changed", FilesystemChanged::class, FilesystemChanged.serializer()),
        Binding("form.cancelled", FormCancelled::class, FormCancelled.serializer()),
        Binding("form.created", FormCreated::class, FormCreated.serializer()),
        Binding("form.replied", FormReplied::class, FormReplied.serializer()),
        Binding("installation.update-available", InstallationUpdateAvailable::class, InstallationUpdateAvailable.serializer()),
        Binding("installation.updated", InstallationUpdated::class, InstallationUpdated.serializer()),
        Binding("integration.updated", EventPayload.IntegrationUpdated::class, EventPayload.IntegrationUpdated.serializer()),
        Binding("location.shutdown", EventPayload.LocationShutdown::class, EventPayload.LocationShutdown.serializer()),
        Binding("mcp.resources.changed", McpResourcesChanged::class, McpResourcesChanged.serializer()),
        Binding("mcp.status.changed", McpStatusChanged::class, McpStatusChanged.serializer()),
        Binding("model.updated", EventPayload.ModelUpdated::class, EventPayload.ModelUpdated.serializer()),
        Binding("models-dev.refreshed", EventPayload.ModelsDevRefreshed::class, EventPayload.ModelsDevRefreshed.serializer()),
        Binding("permission.asked", PermissionAsked::class, PermissionAsked.serializer()),
        Binding("permission.replied", PermissionReplied::class, PermissionReplied.serializer()),
        Binding("persistent-pty.added", PersistentPtyAdded::class, PersistentPtyAdded.serializer()),
        Binding("persistent-pty.removed", PersistentPtyRemoved::class, PersistentPtyRemoved.serializer()),
        Binding("plugin.updated", EventPayload.PluginUpdated::class, EventPayload.PluginUpdated.serializer()),
        Binding("project.updated", ProjectUpdated::class, ProjectUpdated.serializer()),
        Binding("provider.updated", EventPayload.ProviderUpdated::class, EventPayload.ProviderUpdated.serializer()),
        Binding("pty.created", PtyCreated::class, PtyCreated.serializer()),
        Binding("pty.deleted", PtyDeleted::class, PtyDeleted.serializer()),
        Binding("pty.exited", PtyExited::class, PtyExited.serializer()),
        Binding("pty.updated", PtyUpdated::class, PtyUpdated.serializer()),
        Binding("reference.updated", EventPayload.ReferenceUpdated::class, EventPayload.ReferenceUpdated.serializer()),
        Binding("server.connected", EventPayload.ServerConnected::class, EventPayload.ServerConnected.serializer()),
        Binding("session.agent.selected", SessionAgentSelected::class, SessionAgentSelected.serializer()),
        Binding("session.compaction.delta", SessionCompactionDelta::class, SessionCompactionDelta.serializer()),
        Binding("session.compaction.ended", SessionCompactionEnded::class, SessionCompactionEnded.serializer()),
        Binding("session.compaction.failed", SessionCompactionFailed::class, SessionCompactionFailed.serializer()),
        Binding("session.compaction.started", SessionCompactionStarted::class, SessionCompactionStarted.serializer()),
        Binding("session.created", SessionCreated::class, SessionCreated.serializer()),
        Binding("session.deleted", SessionDeleted::class, SessionDeleted.serializer()),
        Binding("session.execution.failed", SessionExecutionFailed::class, SessionExecutionFailed.serializer()),
        Binding("session.execution.interrupted", SessionExecutionInterrupted::class, SessionExecutionInterrupted.serializer()),
        Binding("session.execution.started", SessionExecutionStarted::class, SessionExecutionStarted.serializer()),
        Binding("session.execution.succeeded", SessionExecutionSucceeded::class, SessionExecutionSucceeded.serializer()),
        Binding("session.forked", SessionForked::class, SessionForked.serializer()),
        Binding("session.idle", SessionIdle::class, SessionIdle.serializer()),
        Binding("session.inbox.cancelled", SessionInboxCancelled::class, SessionInboxCancelled.serializer()),
        Binding("session.inbox.delivered", SessionInboxDelivered::class, SessionInboxDelivered.serializer()),
        Binding("session.inbox.delivery.changed", SessionInboxDeliveryChanged::class, SessionInboxDeliveryChanged.serializer()),
        Binding("session.inbox.enqueued", SessionInboxEnqueued::class, SessionInboxEnqueued.serializer()),
        Binding("session.instructions.updated", SessionInstructionsUpdated::class, SessionInstructionsUpdated.serializer()),
        Binding("session.metadata.updated", SessionMetadataUpdated::class, SessionMetadataUpdated.serializer()),
        Binding("session.model.selected", SessionModelSelected::class, SessionModelSelected.serializer()),
        Binding("session.moved", SessionMoved::class, SessionMoved.serializer()),
        Binding("session.permissions", SessionPermissions::class, SessionPermissions.serializer()),
        Binding("session.reasoning.delta", SessionReasoningDelta::class, SessionReasoningDelta.serializer()),
        Binding("session.reasoning.ended", SessionReasoningEnded::class, SessionReasoningEnded.serializer()),
        Binding("session.reasoning.started", SessionReasoningStarted::class, SessionReasoningStarted.serializer()),
        Binding("session.renamed", SessionRenamed::class, SessionRenamed.serializer()),
        Binding("session.retry.scheduled", SessionRetryScheduled::class, SessionRetryScheduled.serializer()),
        Binding("session.revert.cleared", SessionRevertCleared::class, SessionRevertCleared.serializer()),
        Binding("session.revert.committed", SessionRevertCommitted::class, SessionRevertCommitted.serializer()),
        Binding("session.revert.staged", SessionRevertStaged::class, SessionRevertStaged.serializer()),
        Binding("session.shell.ended", SessionShellEnded::class, SessionShellEnded.serializer()),
        Binding("session.shell.started", SessionShellStarted::class, SessionShellStarted.serializer()),
        Binding("session.skill.activated", SessionSkillActivated::class, SessionSkillActivated.serializer()),
        Binding("session.status", SessionStatusUpdated::class, SessionStatusUpdated.serializer()),
        Binding("session.step.ended", SessionStepEnded::class, SessionStepEnded.serializer()),
        Binding("session.step.failed", SessionStepFailed::class, SessionStepFailed.serializer()),
        Binding("session.step.started", SessionStepStarted::class, SessionStepStarted.serializer()),
        Binding("session.step.streamed", SessionStepStreamed::class, SessionStepStreamed.serializer()),
        Binding("session.synthetic", SessionSynthetic::class, SessionSynthetic.serializer()),
        Binding("session.text.delta", SessionTextDelta::class, SessionTextDelta.serializer()),
        Binding("session.text.ended", SessionTextEnded::class, SessionTextEnded.serializer()),
        Binding("session.text.started", SessionTextStarted::class, SessionTextStarted.serializer()),
        Binding("session.tool.called", SessionToolCalled::class, SessionToolCalled.serializer()),
        Binding("session.tool.failed", SessionToolFailed::class, SessionToolFailed.serializer()),
        Binding("session.tool.input.delta", SessionToolInputDelta::class, SessionToolInputDelta.serializer()),
        Binding("session.tool.input.ended", SessionToolInputEnded::class, SessionToolInputEnded.serializer()),
        Binding("session.tool.input.started", SessionToolInputStarted::class, SessionToolInputStarted.serializer()),
        Binding("session.tool.progress", SessionToolProgress::class, SessionToolProgress.serializer()),
        Binding("session.tool.success", SessionToolSuccess::class, SessionToolSuccess.serializer()),
        Binding("session.usage.updated", SessionUsageUpdated::class, SessionUsageUpdated.serializer()),
        Binding("session.viewed", SessionViewed::class, SessionViewed.serializer()),
        Binding("shell.created", ShellCreated::class, ShellCreated.serializer()),
        Binding("shell.deleted", ShellDeleted::class, ShellDeleted.serializer()),
        Binding("shell.exited", ShellExited::class, ShellExited.serializer()),
        Binding("skill.updated", EventPayload.SkillUpdated::class, EventPayload.SkillUpdated.serializer()),
        Binding("tui.command.execute", TuiCommandExecute::class, TuiCommandExecute.serializer()),
        Binding("tui.prompt.append", TuiPromptAppend::class, TuiPromptAppend.serializer()),
        Binding("tui.session.select", TuiSessionSelect::class, TuiSessionSelect.serializer()),
        Binding("tui.toast.show", TuiToastShow::class, TuiToastShow.serializer()),
        Binding("vcs.branch.updated", VcsBranchUpdated::class, VcsBranchUpdated.serializer()),
        Binding("websearch.updated", EventPayload.WebsearchUpdated::class, EventPayload.WebsearchUpdated.serializer()),
        Binding("worktree.resolved", WorktreeResolved::class, WorktreeResolved.serializer()),
        Binding("worktree.updated", WorktreeUpdated::class, WorktreeUpdated.serializer()),
    )

    private val byType: Map<String, Binding<*>> = bindings.associateBy { it.type }
    private val byClass: Map<KClass<*>, Binding<*>> = bindings.associateBy { it.clazz }

    init {
        require(bindings.size == 93) { "EventTypes covers ${bindings.size} types, expected 93" }
        require(byType.size == bindings.size) { "EventTypes has duplicate type strings" }
        require(byClass.size == bindings.size) { "EventTypes has duplicate payload classes" }
    }

    /** Every `type` with a typed payload, plus the `rpc.*` family marker. */
    val knownTypes: Set<String> = byType.keys + RPC_FAMILY

    fun decodePayload(json: Json, type: String, data: JsonElement): EventPayload {
        if (type.startsWith("rpc.")) return decodeRpc(type, data)
        val binding = byType[type] ?: return EventPayload.Unknown(type, data.asDataObject())
        @Suppress("UNCHECKED_CAST")
        return json.decodeFromJsonElement(binding.serializer as KSerializer<EventPayload>, data)
    }

    fun encodePayload(json: Json, payload: EventPayload): JsonElement = when (payload) {
        is EventPayload.Unknown -> payload.raw
        is EventPayload.Rpc -> payload.data
        else -> {
            val binding = byClass[payload::class]
                ?: throw SerializationException("No event type registered for ${payload::class}")
            @Suppress("UNCHECKED_CAST")
            json.encodeToJsonElement(binding.serializer as KSerializer<EventPayload>, payload)
        }
    }

    private fun decodeRpc(type: String, data: JsonElement): EventPayload.Rpc {
        val rest = type.removePrefix("rpc.")
        return EventPayload.Rpc(
            rpcID = rest.substringBefore('.'),
            event = rest.substringAfter('.', ""),
            data = data.asDataObject(),
        )
    }

    private fun JsonElement.asDataObject(): JsonObject =
        this as? JsonObject ?: JsonObject(mapOf("value" to this))

    private const val RPC_FAMILY = "rpc.*"
}
