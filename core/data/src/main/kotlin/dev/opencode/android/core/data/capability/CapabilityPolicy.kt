package dev.opencode.android.core.data.capability

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind

/**
 * The three experimental routes this phase uses (plan §4.2, "Detect capabilities"; features doc
 * §13).
 *
 * **They are named, not guessed.** A route's absence is discovered by calling it and reading the
 * `404`/`405`, so the only honest record of "does this server have it" is a call the app made. This
 * enum is that record: one value per route, and a value per server, so two servers on the same
 * phone can differ — which they do, because the experimental surface moves fast and a user can be
 * talking to two servers at once.
 */
enum class ExperimentalRoute {
    /** `POST /api/experimental/fs/write` — writing to the server's filesystem. */
    FS_WRITE,

    /** `GET /api/experimental/session/{id}/export` — exporting a transcript. */
    SESSION_EXPORT,

    /** `POST /api/experimental/session/import` — importing a transcript. */
    SESSION_IMPORT,

    /**
     * The eleven `api/experimental/…/persistent-pty/…` routes — session terminals.
     *
     * **One switch for eleven routes, deliberately.** They are one feature that lives or dies
     * together: the host lifecycle routes answer `503` when the persistent-PTY host is not running,
     * and a client that probed them separately would show a terminal picker for a feature whose
     * *host* it had not established. Probing the list route is what tells the client the service is
     * there, and the answer is kept as one value so the eleven cannot disagree.
     */
    PERSISTENT_PTY,

    /**
     * The four `api/experimental/mcp/…` routes — connect, disconnect, add and remove.
     *
     * **One switch for four routes, for the same reason as [PERSISTENT_PTY].** They are one feature:
     * the routes write to the running server's MCP table, a server that has three of the four is not
     * a server this client can manage MCP on, and a panel that could add a server but not remove one
     * would be worse than no panel. Probing [MCP_RUNTIME] means one of them, and the answer is kept
     * as one value so the four cannot disagree.
     */
    MCP_RUNTIME,

    /**
     * `POST /api/experimental/integration/wellknown` — adding an integration source by URL.
     *
     * Behind its own switch rather than [MCP_RUNTIME]'s because it is a different kind of write: it
     * makes the *server* fetch a URL, which can add an integration the user did not configure and
     * which then offers its own methods. A user willing to let the app manage MCP servers has not
     * said they want it fetching URLs.
     */
    WELLKNOWN_INTEGRATION,

    /**
     * `PATCH /api/experimental/config` — the global `shell` setting.
     *
     * **Its own switch, and separate from [FS_WRITE] on purpose.** Both write configuration; only one
     * of them changes how the agent behaves. `shell` decides which program runs for the `bash` tool and
     * for every terminal, and [FS_WRITE] can write anything at all, so a switch that covered both
     * would either be too broad for a shell picker or too narrow for a file editor. Two switches, two
     * confirmations, two different words in the dialog.
     */
    CONFIG_UPDATE,

    /**
     * `GET`/`PUT`/`DELETE` on `api/experimental/session/{id}/instructions/entries`.
     *
     * **One switch for three routes because they are one feature**: an entry can only be removed if it
     * was put and only be put if the list can be read, and a panel that could add an instruction but
     * not take it away would be worse than no panel.
     */
    SESSION_INSTRUCTIONS,

    /**
     * `GET /api/experimental/session/stats` — the usage dashboard.
     *
     * Its own switch because it is the only experimental route that is purely a read of aggregate
     * numbers, and hiding the dashboard because the file editor is switched off would be wrong.
     */
    SESSION_STATS,

    /**
     * `POST /api/experimental/generate` and `POST /api/experimental/session/{id}/wait`.
     *
     * **One switch for two routes, because both are "ask the model something outside a session".**
     * The quick-ask widget and the wait-until-idle button are the same capability — a stateless
     * call to the server's configured provider — and a user who has switched one off has said they
     * do not want the app making model calls on its own.
     */
    GENERATE_AND_WAIT,

    /**
     * `GET /api/experimental/session/{id}/log` — the durable session log.
     *
     * A read of a stream whose shape is not in the spec, so it is gated separately from the
     * dashboard: a server can have the route and still answer with something this build cannot
     * show, and an event-history viewer that shows nothing is worse than one that is not offered.
     */
    SESSION_LOG,

    /**
     * `POST /api/pair` — pairing another device.
     *
     * **The only route here that is not in the published spec at all.** It is in the server source
     * and may be removed in any 2.0.x release, so it is probed and hidden rather than offered and
     * left to fail. The app's own pairing path, redeeming a code from `opencode pair`, is
     * unaffected: that is a different route and it is documented.
     */
    PAIR_DEVICE,
    ;

    /** The label the settings switch shows, which `strings.xml` supplies. */
    val id: String get() = name.lowercase()
}

/**
 * How one route is available, which is a *three*-way answer and not a boolean.
 *
 * The third state is the one that matters and the one a boolean cannot hold. `Unknown` means the
 * probe has not run; `Present` means a call succeeded; `Absent` means a call came back `404` or
 * `405`. A switch that only knew "on and off" would either show every experimental feature before
 * it had been tried, or hide one that works, and both are worse than saying "not checked yet".
 */
sealed interface RouteAvailability {
    /** No call has been made from this process yet. The feature is shown as unconfirmed. */
    data object Unknown : RouteAvailability

    /** A call succeeded, so the feature is on. */
    data object Present : RouteAvailability

    /**
     * A call came back `404` or `405`.
     *
     * [httpStatus] is kept because the two mean different things to whoever is debugging: `404` is a
     * route this server does not have, `405` is a route it has but refuses on this method.
     */
    data class Absent(val httpStatus: Int) : RouteAvailability

    /** Whether a call should be made at all. */
    val callable: Boolean get() = this !is Absent
}

/** The status the server answers with when a route exists but not for this method. */
const val HTTP_METHOD_NOT_ALLOWED: Int = 405

/** The status the server answers with when it does not have the route at all. */
const val HTTP_NOT_FOUND: Int = 404

/**
 * Decides what a failed call means for a route's availability.
 *
 * **Only `404` and `405` hide a feature.** A `500` is a server fault and a `409` is a conflict
 * (a busy session refusing a revert), and both prove the route *is* there. Treating either as
 * "absent" would switch a working feature off because of a transient error, and the user's next
 * action — write a file, export a session — would disappear for no reason they can see.
 */
object CapabilityPolicy {

    /** The availability a call's outcome implies, or `null` when the outcome says nothing. */
    fun from(status: Int): RouteAvailability? = when (status) {
        HTTP_NOT_FOUND, HTTP_METHOD_NOT_ALLOWED -> RouteAvailability.Absent(status)
        else -> null
    }

    /** The availability a reported failure kind implies, or `null` when it says nothing. */
    fun from(error: ActionErrorKind?): RouteAvailability? = when (error) {
        ActionErrorKind.NOT_FOUND -> RouteAvailability.Absent(HTTP_NOT_FOUND)
        else -> null
    }

    /**
     * The availability a reported [ActionError] implies, or `null` when it says nothing.
     *
     * **The status is read first, because the kind cannot express a `405`.** A `405` comes back with
     * an empty body and no `_tag`, so [dev.opencode.android.core.data.action.toActionError] has
     * nothing to classify it by and it lands on [ActionErrorKind.SERVER] — the same class a `500`
     * gets. Reading the status is what keeps "the server has this route but not this method" from
     * being read as "the server is busy", and the [Absent.httpStatus] it records is the one thing
     * that tells the two apart for whoever is debugging.
     */
    fun from(error: ActionError): RouteAvailability? =
        error.httpStatus?.let(::from) ?: from(error.kind)

    /**
     * Whether a feature whose last answer was [last] may be offered.
     *
     * A feature that was absent stays hidden for this process, because a route that answers `404`
     * will keep answering `404` and re-trying it on every screen would be a request per screen.
     * The settings switch is what makes the user try again.
     */
    fun isUsable(last: RouteAvailability): Boolean = when (last) {
        RouteAvailability.Present -> true
        RouteAvailability.Unknown -> true
        is RouteAvailability.Absent -> false
    }
}
