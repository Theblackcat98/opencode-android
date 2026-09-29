package dev.opencode.android.core.model

import kotlinx.serialization.Serializable

// String-valued enumerations from the V2 schema. They are value classes rather than Kotlin enums so
// that a value added by a newer server decodes (and round-trips) instead of failing; compare against
// the companion constants.

/** Result of a session's last run: `Session.Info.outcome`, the `idle` message, execution events. */
@Serializable
@JvmInline
value class Outcome(val value: String) {
    companion object {
        val Succeeded = Outcome("succeeded")
        val Failed = Outcome("failed")
        val Interrupted = Outcome("interrupted")
    }
}

/** Why a model step finished (`assistant.finish`, `session.step.ended.finish`). */
@Serializable
@JvmInline
value class FinishReason(val value: String) {
    companion object {
        val Stop = FinishReason("stop")
        val Length = FinishReason("length")
        val ToolCalls = FinishReason("tool-calls")
        val ContentFilter = FinishReason("content-filter")
        val Error = FinishReason("error")
        val Unknown = FinishReason("unknown")
    }
}

/** How inbox input is delivered while the agent is busy. */
@Serializable
@JvmInline
value class Delivery(val value: String) {
    companion object {
        /** Injected at the next step boundary. The default for prompts. */
        val Steer = Delivery("steer")

        /** Waits until the current turn finishes. */
        val Queue = Delivery("queue")
    }
}

/** Status of a shell command (`Shell.Info.status`, the `shell` message). */
@Serializable
@JvmInline
value class ShellStatus(val value: String) {
    companion object {
        val Running = ShellStatus("running")
        val Exited = ShellStatus("exited")
        val Timeout = ShellStatus("timeout")
        val Killed = ShellStatus("killed")
    }
}

/** Why a compaction ran. */
@Serializable
@JvmInline
value class CompactionReason(val value: String) {
    companion object {
        val Auto = CompactionReason("auto")
        val Manual = CompactionReason("manual")
    }
}

/** Why an execution was interrupted (`session.execution.interrupted.reason`). */
@Serializable
@JvmInline
value class InterruptReason(val value: String) {
    companion object {
        val User = InterruptReason("user")
        val Shutdown = InterruptReason("shutdown")
        val Superseded = InterruptReason("superseded")
        val Inactivity = InterruptReason("inactivity")
    }
}

/**
 * Effect of a permission rule.
 *
 * A request the standing approvals already cover comes back as `allow` even when the client asked
 * for a new one, so [needsAnswer] is what decides whether a confirmation is worth showing at all:
 * offering a decision the user cannot make, and then discarding the answer, is worse than saying
 * the permission is already granted.
 */
@Serializable
@JvmInline
value class PermissionEffect(val value: String) {
    /** The agent is blocked until the user answers. The only effect that needs a question. */
    val needsAnswer: Boolean get() = this == Ask

    companion object {
        val Allow = PermissionEffect("allow")
        val Deny = PermissionEffect("deny")
        val Ask = PermissionEffect("ask")

        /** A server that grows a fourth effect must still decode. */
        fun of(value: String): PermissionEffect = when (value) {
            "allow" -> Allow
            "deny" -> Deny
            "ask" -> Ask
            else -> PermissionEffect(value)
        }
    }
}

/** A reply to a permission request. */
@Serializable
@JvmInline
value class PermissionReply(val value: String) {
    companion object {
        val Once = PermissionReply("once")
        val Always = PermissionReply("always")
        val Reject = PermissionReply("reject")
    }
}

/** Kind of change in a `FileDiff.Info`. */
@Serializable
@JvmInline
value class FileDiffStatus(val value: String) {
    companion object {
        val Added = FileDiffStatus("added")
        val Deleted = FileDiffStatus("deleted")
        val Modified = FileDiffStatus("modified")
    }
}
