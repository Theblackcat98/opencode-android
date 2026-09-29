package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.ExtendedNumberSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A shell command run by the server (schema `Shell.Info`). */
@Serializable
data class ShellInfo(
    val id: String,
    val status: ShellStatus,
    val command: String,
    val cwd: String,
    val shell: String,
    val file: String,
    val pid: Long? = null,
    val exit: Int? = null,
    val signal: String? = null,
    val metadata: Map<String, JsonElement>,
    val time: Time,
) {
    @Serializable
    data class Time(val started: Long, val completed: Long? = null)
}

/** A page of shell output (`GET /api/shell/{id}/output`, `session.shell.ended`). */
@Serializable
data class ShellOutput(
    val output: String,
    val cursor: Long,
    val size: Long,
    val truncated: Boolean,
)

/** Exit code as sent in `shell` messages, where it may be a non-finite number string. */
typealias ExtendedNumber =
    @Serializable(with = ExtendedNumberSerializer::class)
    Double
