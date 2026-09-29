package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.timeline.PendingInboxItem
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.InboxItem
import dev.opencode.android.core.model.InboxUpdateRequest
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.PermissionRule
import dev.opencode.android.core.model.PromptAgentAttachment
import dev.opencode.android.core.model.PromptFileInput
import dev.opencode.android.core.model.PromptRequest
import dev.opencode.android.core.model.PromptSkillAttachment
import dev.opencode.android.core.model.PromptSkillInput
import dev.opencode.android.core.model.SessionCommandRequest
import dev.opencode.android.core.model.SessionCompactRequest
import dev.opencode.android.core.model.SessionCreateRequest
import dev.opencode.android.core.model.SessionEnvironmentRequest
import dev.opencode.android.core.model.SessionGenerateRequest
import dev.opencode.android.core.model.SessionInboxInfo
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SessionMetadata
import dev.opencode.android.core.model.SessionShellRequest
import dev.opencode.android.core.model.SessionUpdateRequest
import dev.opencode.android.core.model.SessionViewRequest
import dev.opencode.android.core.model.SkillActivationRequest
import dev.opencode.android.core.model.SwitchAgentRequest
import dev.opencode.android.core.model.SwitchModelRequest
import dev.opencode.android.core.model.UserPromptPayload
import dev.opencode.android.core.model.toPreviewAttachment
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicLong

/**
 * Every write a session needs, and the ids that make them retry-safe.
 *
 * **A write is a request, not an edit.** Nothing here changes a store. The server emits the event
 * that changes the state and the P2 stores apply it, so the same action from two clients, or a
 * retry after a dropped response, converges to the same projection. The single exception is the
 * optimistic inbox item, which is the plan's one permitted piece of optimism and is reconciled by
 * the `session.inbox.enqueued` event under the same id (plan §4.2).
 *
 * **Ids are generated before the call, never after.** [nextMessageID] and [nextSessionID] produce a
 * `msg_…`/`ses_…` id up front, which is what lets [prompt] be retried on a flaky mobile network: the
 * server treats a repeated id with the same payload as the same request and a repeated id with a
 * different payload as a conflict, so a retry is safe and a mistake is visible. The counter is local
 * and the random suffix is not, which is why a second device cannot collide with this one.
 *
 * Every call answers with the failure class the UI renders, or `null` for success, rather than
 * throwing: a user action that fails has to produce a message, and a `Result` at every call site
 * would be noise.
 */
class SessionCommands(
    private val api: ServerApi,
    private val timeline: (String) -> TimelineStore? = { null },
    private val ids: IdGenerator = IdGenerator(),
) {

    /**
     * `session.create`.
     *
     * The location, the agent and the model are all optional on the wire, and each is left out when
     * the user did not choose it, so the server's own default decides. The answer is the created
     * session, which is also what `session.created` will carry; the caller's store picks that up from
     * the event like any other.
     */
    suspend fun create(
        title: String? = null,
        agent: String? = null,
        model: ModelRef? = null,
        directory: String? = null,
        metadata: SessionMetadata? = null,
        permissions: List<PermissionRule>? = null,
        id: String? = null,
    ): Result<SessionInfo> = call {
        api.createSession(
            SessionCreateRequest(
                id = id,
                title = title?.takeIf { it.isNotBlank() },
                agent = agent,
                model = model,
                location = directory?.let(::LocationPublicRef),
                metadata = metadata,
                permissions = permissions,
            ),
        ).data
    }

    /**
     * `session.prompt`.
     *
     * Returns the inbox item the server enqueued, whose id is the client-generated `msg_…` this call
     * used. The text is mirrored into the timeline as a pending item first, so the prompt is on
     * screen immediately; the server's `session.inbox.enqueued` event reconciles it under the same id.
     *
     * [files] travels in the `PromptInput.FileAttachment` shape — an `uri`, not the stored message's
     * base64 and mime — because that is what the route accepts. The optimistic item renders the
     * same chips the server's echo will by decoding that URI back into a
     * [PromptFileAttachment], so the pending message and the confirmed one look the same.
     */
    suspend fun prompt(
        sessionID: String,
        text: String,
        delivery: Delivery = Delivery.Steer,
        resume: Boolean? = null,
        files: List<PromptFileInput>? = null,
        agents: List<PromptAgentAttachment>? = null,
        skills: List<PromptSkillInput>? = null,
        metadata: Map<String, JsonElement>? = null,
    ): Result<SessionInboxInfo> {
        val messageID = ids.nextMessageID()
        val payload = UserPromptPayload(
            text = text,
            files = files?.map { it.toPreviewAttachment() },
            agents = agents,
            skills = skills?.map { PromptSkillAttachment(id = it.id, name = it.id, mention = it.mention) },
            metadata = metadata,
        )
        // The optimistic item. The id matches the request, so the event that confirms it replaces
        // this entry rather than adding a second one.
        timeline(sessionID)?.showPending(
            PendingInboxItem(
                id = messageID,
                created = ids.now(),
                item = InboxItem.User(payload, delivery),
            ),
        )
        return call {
            api.prompt(
                sessionID = sessionID,
                body = PromptRequest(
                    id = messageID,
                    text = text,
                    files = files,
                    agents = agents,
                    skills = skills,
                    metadata = metadata,
                    delivery = delivery,
                    resume = resume,
                ),
            ).data
        }.onFailure {
            // A prompt that was not accepted is not pending anywhere, and the server emits nothing
            // for it, so the optimistic item has to go or the transcript would show a message that
            // will never be sent. The composer keeps the text and offers the send again.
            timeline(sessionID)?.dropPending(messageID)
        }
    }

    /**
     * `session.command`: runs a command template.
     *
     * `204`; the echo is the inbox event the server emits, so there is nothing to read back. The id
     * is generated the same way a prompt's is and the same pending item is shown, because from the
     * user's point of view a command is a message with a different way of getting there.
     */
    suspend fun runCommand(
        sessionID: String,
        name: String,
        text: String,
        delivery: Delivery = Delivery.Steer,
        files: List<PromptFileInput>? = null,
        agents: List<PromptAgentAttachment>? = null,
        skills: List<PromptSkillInput>? = null,
        metadata: Map<String, JsonElement>? = null,
    ): Result<Unit> {
        val messageID = ids.nextMessageID()
        timeline(sessionID)?.showPending(
            PendingInboxItem(
                id = messageID,
                created = ids.now(),
                item = InboxItem.User(
                    UserPromptPayload(
                        text = text,
                        files = files?.map { it.toPreviewAttachment() },
                        agents = agents,
                        skills = skills?.map { PromptSkillAttachment(id = it.id, name = it.id, mention = it.mention) },
                        metadata = metadata,
                    ),
                    delivery,
                ),
            ),
        )
        return call {
            api.runCommand(
                sessionID = sessionID,
                body = SessionCommandRequest(
                    name = name,
                    text = text,
                    files = files,
                    agents = agents,
                    skills = skills,
                    metadata = metadata,
                    delivery = delivery,
                ),
            )
        }.onFailure { timeline(sessionID)?.dropPending(messageID) }
    }

    /**
     * `session.shell`: the composer's `!command` mode.
     *
     * `204`; `session.shell.started` and `session.shell.ended {output}` put the result in the
     * timeline. The client-generated id is what makes a retry not run the command twice, which for a
     * command with a side effect is the whole point.
     */
    suspend fun runShell(
        sessionID: String,
        command: String,
    ): Result<Unit> = call {
        api.runShell(sessionID, SessionShellRequest(id = ids.nextMessageID(), command = command))
    }

    /**
     * `session.compact`: manual compaction (`/compact`).
     *
     * Answers with the inbox item it enqueued, and a busy session is a `409` the caller reports
     * rather than retries.
     */
    suspend fun compact(
        sessionID: String,
        delivery: Delivery = Delivery.Steer,
    ): Result<SessionInboxInfo> = call {
        api.compact(sessionID, SessionCompactRequest(id = ids.nextMessageID(), delivery = delivery)).data
    }

    /**
     * `session.generate`: a side question about the session's context (`/btw`).
     *
     * The only driving call that answers with a body rather than an event, because the answer is
     * for the user and has nowhere else to go. It does not enter the timeline.
     */
    suspend fun generate(sessionID: String, prompt: String): Result<String> = call {
        api.generate(sessionID, SessionGenerateRequest(prompt)).data.text
    }

    /** `session.environment`: the variables this session's tools run with. `PUT`, so the whole map. */
    suspend fun setEnvironment(
        sessionID: String,
        variables: Map<String, String>,
    ): Result<Unit> = call {
        api.setSessionEnvironment(sessionID, SessionEnvironmentRequest(variables))
    }

    /**
     * `experimental.session.skill`: activates a skill in the running session.
     *
     * Experimental, so a `404` here is the expected answer on a server without the route and the
     * composer falls back to attaching the skill on the next prompt.
     */
    suspend fun activateSkill(
        sessionID: String,
        skill: String,
        resume: Boolean? = null,
    ): Result<Unit> = call {
        api.activateSkill(sessionID, SkillActivationRequest(id = skill, resume = resume))
    }

    /**
     * `session.interrupt`.
     *
     * [resume] true resumes pending steering input and leaves queued prompts parked (features
     * doc §4.2), which is the difference between stopping a turn and stopping it while continuing
     * from what the user had already typed.
     */
    suspend fun interrupt(sessionID: String, resume: Boolean? = null): Result<Boolean> = call {
        api.interrupt(sessionID, resume).interrupted
    }

    /** `session.background`: moves blocking tools out of the way so the turn can finish. */
    suspend fun background(sessionID: String): Result<Unit> = call {
        api.background(sessionID)
    }

    /** `session.switchAgent`, confirmed by `session.agent.selected`. */
    suspend fun switchAgent(sessionID: String, agent: String): Result<Unit> = call {
        api.switchAgent(sessionID, SwitchAgentRequest(agent))
    }

    /** `session.switchModel`, confirmed by `session.model.selected`. The variant rides in the ref. */
    suspend fun switchModel(sessionID: String, model: ModelRef): Result<Unit> = call {
        api.switchModel(sessionID, SwitchModelRequest(model))
    }

    /** `session.update`: a rename, a metadata edit, or session permission rules. */
    suspend fun update(
        sessionID: String,
        title: String? = null,
        metadata: SessionMetadata? = null,
        permissions: List<PermissionRule>? = null,
    ): Result<Unit> = call {
        api.updateSession(sessionID, SessionUpdateRequest(title, metadata, permissions))
    }

    /**
     * `session.remove`: deletes a session and its children.
     *
     * The caller is expected to have confirmed first (plan §5.2): the server deletes a whole subtree
     * with no undo, and the child count is the thing the user cannot see from a list row.
     */
    suspend fun remove(sessionID: String): Result<Unit> = call {
        api.removeSession(sessionID)
    }

    /** `session.inbox.cancel`, confirmed by `session.inbox.cancelled`. */
    suspend fun cancelInboxItem(sessionID: String, inboxID: String): Result<Unit> = call {
        api.cancelInboxItem(sessionID, inboxID)
    }

    /** `session.inbox.update`: switches a pending item between queue and steer. */
    suspend fun setInboxDelivery(
        sessionID: String,
        inboxID: String,
        delivery: Delivery,
    ): Result<Unit> = call {
        api.updateInboxItem(sessionID, inboxID, InboxUpdateRequest(delivery))
    }

    /** `session.view`: records that the user has seen this session's idle transition. */
    suspend fun markViewed(sessionID: String, idleAtMillis: Long): Result<Unit> = call {
        api.viewSession(sessionID, SessionViewRequest(idle = idleAtMillis))
    }

    /** A fresh `msg_…` id, exposed so a caller can show it or key a draft by it. */
    fun nextMessageID(): String = ids.nextMessageID()

    /** A fresh `ses_…` id, so a retried create is the same session rather than a second one. */
    fun nextSessionID(): String = ids.nextSessionID()

    private suspend inline fun <T> call(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(ActionFailure(error.toActionError()))
    }
}

/** A failed driving action, carried as a [Throwable] so it rides in a [Result]. */
/** The one [ActionFailure], in the action package; see its note for why. */
typealias ActionFailure = dev.opencode.android.core.data.action.ActionFailure

/** The [ActionError] of a failed result, or `null` when it succeeded. */
val Result<*>.actionErrorOrNull: ActionError? get() = (exceptionOrNull() as? ActionFailure)?.error

/**
 * Client-generated ids.
 *
 * A monotonic counter keeps them unique inside the process, a timestamp keeps them unique across
 * processes, and random bytes keep them unique across devices — the server rejects a reused session
 * id and a reused message id with a different payload, so guessing is not an option.
 */
class IdGenerator(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> String = { java.lang.Long.toString(java.util.UUID.randomUUID().mostSignificantBits, 36) },
    private val counter: AtomicLong = AtomicLong(0),
) {
    fun nextMessageID(): String = "msg_${suffix()}"

    fun nextSessionID(): String = "ses_${suffix()}"

    /** The wall clock, exposed so an optimistic item and its id are stamped consistently. */
    fun now(): Long = clock()

    private fun suffix(): String {
        val n = counter.incrementAndGet()
        return "${clock().toString(36)}${random().take(8)}${n.toString(36)}"
    }
}
