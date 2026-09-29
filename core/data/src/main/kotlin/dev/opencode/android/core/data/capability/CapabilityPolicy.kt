package dev.opencode.android.core.data.capability

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

    /** The availability a reported [ActionError] implies, or `null` when it says nothing. */
    fun from(error: ActionErrorKind?): RouteAvailability? = when (error) {
        ActionErrorKind.NOT_FOUND -> RouteAvailability.Absent(HTTP_NOT_FOUND)
        else -> null
    }

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
