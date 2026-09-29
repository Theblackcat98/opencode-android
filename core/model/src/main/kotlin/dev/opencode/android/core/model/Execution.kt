package dev.opencode.android.core.model

import dev.opencode.android.core.model.event.PersistentPtyInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The execution surface's wire shapes: shells, terminals, worktrees, and the two writes that move a
 * session or reconfigure a project (features doc §29–§32).
 *
 * **The PTY response types are the event payload types.** `Pty` (schema) and `PtyInfo` (the
 * `pty.created` / `pty.updated` payload) are the same fields, and `PersistentPty.Info` and
 * `PersistentPtyInfo` are the same fields again. Two types for one shape is a second thing to keep in
 * step, and a terminal that `pty.list` renders with a different title from the one `pty.updated`
 * carries is exactly the bug that comes from it, so [PtyInfo] and [PersistentPtyInfo] are used for
 * both the route answers and the events.
 */

// ------------------------------------------------------------------------------- shells

/**
 * `POST /api/shell` (schema `Shell.create`).
 *
 * [command] is the only required field. [timeout] is milliseconds on the wire even though the schema
 * calls it an integer, and [metadata] is free-form: the client writes nothing, and a shell the agent
 * started carries the agent's own metadata so a panel can tell "the user's command" from "the
 * agent's build" without guessing from the command text.
 */
@Serializable
data class ShellCreateRequest(
    val command: String,
    val cwd: String? = null,
    val timeout: Long? = null,
    val metadata: Map<String, JsonElement>? = null,
)

// ------------------------------------------------------------------------------- terminals

/**
 * `POST /api/pty` (schema `Pty.create`).
 *
 * Every field is optional: the server then runs the location's configured shell. This client always
 * sends [command] and [args] when the user picked a shell from `config.shell`, because the picker
 * knows the path and the server's default is a config value the user may never have looked at.
 */
@Serializable
data class PtyCreateRequest(
    val command: String? = null,
    val args: List<String>? = null,
    val cwd: String? = null,
    val title: String? = null,
    val env: Map<String, String>? = null,
)

/**
 * `PUT /api/pty/{id}` (schema `Pty.update`): rename, resize, or both.
 *
 * Resizing goes over REST rather than over the socket because the WebSocket frames are terminal
 * bytes and a JSON frame in that stream would be output the client has to guess at (features doc
 * §31). A field left out is not changed, so a rename does not resize and a layout change does not
 * rename.
 */
@Serializable
data class PtyUpdateRequest(
    val title: String? = null,
    val size: PtySize? = null,
)

/** A terminal's character grid. Both are positive; the server refuses zero. */
@Serializable
data class PtySize(val rows: Int, val cols: Int)

/**
 * `POST /api/pty/{id}/connect-token` (schema `PtyTicket.ConnectToken`).
 *
 * A browser cannot set an `Authorization` header on a WebSocket, so a page the WebView loads asks
 * for a single-use ticket and passes it as a query parameter. This client connects from OkHttp with
 * Basic auth and does not need one; the shape is here because the WebView fallback and the ticket
 * flow are the same route, and a request that omits the `x-opencode-ticket: 1` header answers
 * `403` rather than `404`, so the header is part of calling it (see `PtyTicketRequest.HEADER`).
 */
@Serializable
data class PtyTicketToken(
    val ticket: String,
    /** Seconds the ticket is valid for. It is single use, not reusable. */
    @SerialName("expires_in") val expiresInSeconds: Long,
) {
    companion object {
        /**
         * The header that asks for a ticket at all.
         *
         * The route refuses without it, and refuses with `403` rather than `404`, which means a
         * client that forgets the header is told "forbidden" about a route that exists — a message
         * that would send capability detection in the wrong direction.
         */
        const val HEADER: String = "x-opencode-ticket"

        /** The value the header carries; the features doc spells it as the literal `1`. */
        const val HEADER_VALUE: String = "1"
    }
}

/**
 * The `0x00`-prefixed control frame's JSON body.
 *
 * The protocol has exactly one control message: after the replay the server sends `{"cursor": n}`,
 * and that cursor is the only thing a client needs to resume with `?cursor=n`. The decoder treats a
 * control frame it cannot read as output rather than failing the connection, because the stream it
 * is interleaved with is terminal output and losing it is worse than showing a byte.
 */
@Serializable
data class PtyCursor(val cursor: Long)

// ------------------------------------------------------------ persistent (session) terminals

/**
 * `POST /api/experimental/session/{id}/terminal` (schema `PersistentPty.create`).
 *
 * [args], [title] and [env] are required by the schema, so unlike [PtyCreateRequest] they are not
 * nullable here: the route would reject the body otherwise, and a request this client builds always
 * means all three.
 */
@Serializable
data class SessionTerminalCreateRequest(
    val command: String? = null,
    val args: List<String>,
    val cwd: String? = null,
    val title: String,
    val env: Map<String, String>,
    val size: PtySize? = null,
)

/** `PUT /api/experimental/persistent-pty/{id}` (schema `PersistentPty.update`). */
@Serializable
data class SessionTerminalUpdateRequest(
    val attachmentID: String? = null,
    val size: PtySize,
)

/** The cursor of a snapshot's text, in cells. */
@Serializable
data class TerminalCursor(val x: Int, val y: Int)

/**
 * `GET /api/experimental/persistent-pty/{id}/snapshot` (schema `PersistentPty.Snapshot`).
 *
 * [checkpoint] is base64 (the schema says `format: byte`). A client cannot feed it to a terminal
 * that is not already attached — the bytes of a VT stream are not the same thing as the screen they
 * produce — so the snapshot is for a *read-only* rendering, which is what a session's terminal pane
 * shows when the experimental route exists but the WebSocket does not.
 */
@Serializable
data class SessionTerminalSnapshot(
    val info: PersistentPtyInfo,
    val text: String,
    val checkpoint: String,
    val cursor: TerminalCursor,
)

/**
 * `GET /api/experimental/session/{id}/terminal/read` (schema `PersistentPty.ReadResult`).
 *
 * The screen of the session's most recently controlled terminal: [text] as the user would read it,
 * the grid it was rendered on, and where the cursor sits.
 *
 * **A nullable object, not a sealed union.** The route's `data` is nullable and `null` means "this
 * session has no terminal yet", which is a normal state and not a variant to switch on; a sealed type
 * here would need a serializer for an envelope the server does not declare, and would turn the one
 * honest answer the route gives into a decode failure. [dev.opencode.android.core.data.execution.SessionTerminalStore]
 * turns it into the client's own union once it has decoded.
 */
@Serializable
data class PersistentPtyScreen(
    val ptyID: String,
    val title: String,
    val cwd: String,
    val foregroundProcess: String? = null,
    val screen: Screen,
) {
    @Serializable
    data class Screen(
        val text: String,
        val cols: Int,
        val rows: Int,
        val cursor: TerminalCursor,
    )
}

/**
 * What `terminal/read` means, as this client holds it.
 *
 * **A union rather than a nullable screen**, so a screen and "no terminal" cannot be confused at a
 * call site, and not serialisable because the wire shape is [PersistentPtyScreen] and only that one is.
 */
sealed interface SessionTerminalRead {
    /** The session has a terminal and this is its screen. */
    data class Screen(
        val ptyID: String,
        val title: String,
        val cwd: String,
        val foregroundProcess: String?,
        val text: String,
        val cols: Int,
        val rows: Int,
        val cursor: TerminalCursor,
    ) : SessionTerminalRead

    /** The session has no terminal, or the route answered `data: null`. */
    data object None : SessionTerminalRead
}

/** `POST /api/experimental/persistent-pty/handoff` (schema `PersistentPty.Handoff`). */
@Serializable
data class SessionTerminalHandoff(
    val directory: String,
    val instanceID: String,
    val ticket: String,
    val expiresAt: ExtendedNumber,
) {
    @Serializable
    data class Wrapper(val handoff: SessionTerminalHandoff? = null)
}

// ------------------------------------------------------------------------------- worktrees

/**
 * `GET /api/worktree` (schema `Worktree.Directory`).
 *
 * [strategy] is absent unless a plugin supplies one; the server tells the client which strategy
 * created the worktree, and this client does not act on it — it is shown so a user can tell a
 * plugin's worktree from a plain one.
 */
@Serializable
data class WorktreeDirectory(
    val directory: String,
    val strategy: String? = null,
)

/**
 * `POST /api/worktree` (schema `Worktree.create`).
 *
 * Only [projectID] is required; [from] is the ref to branch from, [branch] the branch name,
 * [directory] an explicit path and [name] a name the server turns into one. The client sends the
 * fields the user filled in and omits the rest, so the server's own defaults decide the path.
 * Creating a worktree runs the project's setup script, which is why the confirmation says so.
 */
@Serializable
data class WorktreeCreateRequest(
    val projectID: String,
    val from: String? = null,
    val branch: String? = null,
    val directory: String? = null,
    val name: String? = null,
)

/**
 * `DELETE /api/worktree` (schema `Worktree.remove`).
 *
 * All three are required, [force] included. A worktree with uncommitted work answers `400` with a
 * `WorktreeError` whose `forceRequired` is true, and forcing deletes it anyway — so the two-step
 * confirmation plan §5.2 asks for is a *client* decision, and this type is what makes it possible to
 * ask at all.
 */
@Serializable
data class WorktreeRemoveRequest(
    val projectID: String,
    val directory: String,
    val force: Boolean,
)

/** `POST /api/worktree/refresh` (schema `Worktree.refresh`). */
@Serializable
data class WorktreeRefreshRequest(val projectID: String)

/**
 * The `WorktreeError` a failed worktree call answers with.
 *
 * It does not use the `_tag` discriminator every other error uses: the field is `name` and the
 * payload is under `data`, so this is decoded by its own serializer and a `400` that carries this
 * shape is not confused with an `InvalidRequestError` (features doc §29; spec `WorktreeErrorEncoded`).
 */
@Serializable
data class WorktreeFailure(
    val name: String = "WorktreeError",
    val data: Data = Data(),
) {
    @Serializable
    data class Data(val message: String = "", val forceRequired: Boolean? = null)
}

// --------------------------------------------------------------------- session and project

/**
 * `POST /api/session/{id}/move` (schema `Session.move`).
 *
 * [delivery] is `steer` or `queue` and says how work already queued for the session is delivered once
 * it has moved. It is the same delivery the composer sends, so a move does not have its own mode: a
 * move that parked a queue behind a directory the user did not ask for would be a surprise, and
 * `queue` is the safer default to send.
 */
@Serializable
data class SessionMoveRequest(
    val directory: String,
    val delivery: Delivery? = null,
)

/**
 * `PATCH /api/project/{id}` (schema `Project.update`).
 *
 * A field left out is not changed. [icon] is replaced whole rather than merged, so clearing the
 * override and setting a colour in one call is not expressible — the client sends the icon as it was
 * edited, which is the whole of what the user saw on screen.
 */
@Serializable
data class ProjectUpdateRequest(
    val canonical: String? = null,
    val name: String? = null,
    val icon: Project.Icon? = null,
    val commands: Project.Commands? = null,
)

/** `GET /api/config/shell` (schema `ConfigShell.Option`): the shells the server found. */
@Serializable
data class ShellOption(
    val path: String,
    val name: String,
    /**
     * False when the server found the shell but will not run it — a config that names a binary which
     * is not there. A picker shows those greyed out rather than offering a terminal that cannot open.
     */
    val acceptable: Boolean,
)
