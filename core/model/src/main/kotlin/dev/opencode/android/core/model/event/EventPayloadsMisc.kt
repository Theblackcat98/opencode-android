package dev.opencode.android.core.model.event

import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.ShellInfo
import dev.opencode.android.core.model.ShellStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Payloads outside the `session.*` family. Field-for-field with the `V2Event*` declarations. */
@Serializable
data class CredentialSwitched(
    val integrationID: String,
    val credentialID: String? = null,
) : EventPayload

@Serializable
data class FilesystemChanged(
    val file: String,
    val event: FilesystemChange,
) : EventPayload

/** What happened to a watched file. A value class so newer kinds decode instead of failing. */
@Serializable
@JvmInline
value class FilesystemChange(val value: String) {
    companion object {
        val Add = FilesystemChange("add")
        val Change = FilesystemChange("change")
        val Unlink = FilesystemChange("unlink")
    }
}

@Serializable
data class FormCreated(val form: EventFormInfo) : EventPayload

@Serializable
data class FormReplied(
    val id: String,
    val sessionID: String,
    /** Answers by field key: string, number, boolean or string array. */
    val answer: Map<String, JsonElement>,
) : EventPayload

@Serializable
data class FormCancelled(
    val id: String,
    val sessionID: String,
) : EventPayload

/**
 * A form awaiting an answer (schema `Form.Info`). The field list stays raw JSON: later
 * phases model the field union for the forms engine. Nothing is lost; it re-encodes
 * byte-identically (modulo number formatting).
 */
@Serializable
data class EventFormInfo(
    val id: String,
    val sessionID: String,
    val title: String,
    val metadata: JsonObject? = null,
    val fields: JsonElement,
)

@Serializable
data class InstallationUpdated(val version: String) : EventPayload

@Serializable
data class InstallationUpdateAvailable(val version: String) : EventPayload

@Serializable
data class McpStatusChanged(val server: String) : EventPayload

@Serializable
data class McpResourcesChanged(val server: String) : EventPayload

@Serializable
data class PermissionAsked(
    val id: String,
    val sessionID: String,
    val action: String,
    val resources: List<String>,
    val save: List<String>? = null,
    val metadata: JsonObject? = null,
    val source: PermissionSource? = null,
    val message: String? = null,
) : EventPayload

/** Where a permission request came from. */
@Serializable
data class PermissionSource(
    val type: String,
    val messageID: String,
    val id: String,
) {
    companion object {
        const val TOOL = "tool"
    }
}

@Serializable
data class PermissionReplied(
    val sessionID: String,
    val requestID: String,
    val reply: PermissionReply,
) : EventPayload

@Serializable
data class ProjectUpdated(
    val id: String,
    val canonical: String,
    val vcs: String? = null,
    val name: String? = null,
    val icon: Project.Icon? = null,
    val commands: Project.Commands? = null,
    val time: Project.Time,
    val sandboxes: List<String>,
) : EventPayload

@Serializable
data class PtyCreated(val info: PtyInfo) : EventPayload

@Serializable
data class PtyUpdated(val info: PtyInfo) : EventPayload

@Serializable
data class PtyDeleted(val id: String) : EventPayload

@Serializable
data class PtyExited(
    val id: String,
    val exitCode: Int,
) : EventPayload

/** A PTY session (schema `Pty.Info`). */
@Serializable
data class PtyInfo(
    val id: String,
    val title: String,
    val command: String,
    val args: List<String>,
    val cwd: String,
    val status: PtyStatus,
    val pid: Long,
    val exitCode: Int? = null,
)

@Serializable
@JvmInline
value class PtyStatus(val value: String) {
    companion object {
        val Running = PtyStatus("running")
        val Exited = PtyStatus("exited")
    }
}

@Serializable
data class PersistentPtyAdded(
    val sessionID: String,
    val terminal: PersistentPtyInfo,
) : EventPayload

@Serializable
data class PersistentPtyRemoved(
    val sessionID: String,
    val ptyID: String,
) : EventPayload

/** A persistent session terminal (experimental schema). */
@Serializable
data class PersistentPtyInfo(
    val id: String,
    val title: String,
    val command: String,
    val args: List<String>,
    val cwd: String,
    val status: PtyStatus,
    val pid: Long,
    val exitCode: Int? = null,
    val sessionID: String,
    val foregroundProcess: String? = null,
    val size: Size,
    val output: Output,
) {
    @Serializable
    data class Size(
        val cols: Int,
        val rows: Int,
    )

    @Serializable
    data class Output(
        val head: Long,
        val tail: Long,
    )
}

@Serializable
data class ShellCreated(val info: ShellInfo) : EventPayload

@Serializable
data class ShellDeleted(val id: String) : EventPayload

@Serializable
data class ShellExited(
    val id: String,
    val exit: Int? = null,
    val status: ShellStatus,
) : EventPayload

@Serializable
data class TuiPromptAppend(val text: String) : EventPayload

@Serializable
data class TuiCommandExecute(val command: String) : EventPayload

@Serializable
data class TuiSessionSelect(val sessionID: String) : EventPayload

@Serializable
data class TuiToastShow(
    val title: String? = null,
    val message: String,
    val variant: ToastVariant,
    val duration: Double? = null,
) : EventPayload

@Serializable
@JvmInline
value class ToastVariant(val value: String) {
    companion object {
        val Info = ToastVariant("info")
        val Success = ToastVariant("success")
        val Warning = ToastVariant("warning")
        val Error = ToastVariant("error")
    }
}

@Serializable
data class VcsBranchUpdated(val branch: String? = null) : EventPayload

@Serializable
data class WorktreeUpdated(val projectID: String) : EventPayload

@Serializable
data class WorktreeResolved(
    val projectID: String,
    val directory: String,
    val previous: String,
    val adopted: List<String>? = null,
) : EventPayload
