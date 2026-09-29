package dev.opencode.android.core.data.integrations

import dev.opencode.android.core.model.CommandAttemptStatus
import dev.opencode.android.core.model.OAuthAttemptStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The states a login attempt goes through, for both OAuth and command attempts.
 *
 * **One state machine for both, because the server's two unions are the same four states** plus a
 * cancel this client adds. Writing it once means the `mode=auto` polling loop, the device-code
 * screen and the command-output screen cannot disagree about what "done" is — and the earlier
 * phases found the cost of that disagreement (a terminal whose input never reached the socket,
 * because a `when` had an `else` that did nothing).
 */
sealed interface ConnectAttemptState {

    /** Nothing started yet, or the sheet was dismissed. */
    data object Idle : ConnectAttemptState

    /** The server is waiting on the user or the provider. */
    data object Pending : ConnectAttemptState

    /**
     * The provider granted access.
     *
     * Terminal, and the moment the OAuth-completion notification is published. It is *not* a signal
     * that a credential exists: only the re-read `integration.list` says that, which is why the
     * caller syncs the catalog rather than adding a row itself (plan §4.2, "the client never
     * guesses").
     */
    data object Complete : ConnectAttemptState

    /** The provider or the server refused, with the reason to show. Terminal. */
    data class Failed(val message: String) : ConnectAttemptState

    /** The attempt timed out on the server. Terminal. */
    data object Expired : ConnectAttemptState

    /**
     * The user cancelled, or the sheet was dismissed.
     *
     * Terminal, and tracked separately from [Failed] because the two are opposites in the UI: a
     * failure offers "try again", a cancel offers nothing.
     */
    data object Cancelled : ConnectAttemptState

    /**
     * The attempt could not be polled.
     *
     * **Not terminal, and not a failure.** A phone loses Wi-Fi mid-login constantly, and reporting
     * "the login failed" for a dropped connection would tell the user to start over for a reason
     * that has nothing to do with the provider. The loop keeps polling and the screen says it is
     * reconnecting.
     */
    data class Unreachable(val message: String) : ConnectAttemptState

    /** Whether the flow is over, whichever way it ended. */
    val isTerminal: Boolean
        get() = this is Complete || this is Failed || this is Expired || this is Cancelled

    /** Whether polling should continue. */
    val isRunning: Boolean get() = this == Pending || this is Unreachable
}

/** What the poller has collected, beyond the state. */
data class ConnectAttemptProgress(
    val attemptID: String? = null,
    val state: ConnectAttemptState = ConnectAttemptState.Idle,
    /**
     * The output a command attempt has produced, accumulated.
     *
     * **Accumulated, not replaced.** A device-code login prints a code and then waits for the user
     * to type it into another device; replacing the text each poll would make the code disappear
     * exactly when they need to read it. Bounded by [MAX_OUTPUT_CHARS] for the same reason the shell
     * panel is.
     */
    val output: String = "",
    /** How many polls have been made, which the diagnostics line shows. */
    val polls: Int = 0,
) {
    val isTerminal: Boolean get() = state.isTerminal

    companion object {
        const val MAX_OUTPUT_CHARS: Int = 8 * 1024
    }
}

/**
 * The polling loop for a `mode=auto` OAuth attempt and for a command attempt (plan §6, "Mode=auto
 * polls the status until complete, failed or expired").
 *
 * **The server decides; the redirect says nothing (plan §2 finding 5, §4.2).** A Custom Tab that
 * returns to the app has told us the *browser* finished, which is not the same as the provider having
 * granted access — the user may have closed the tab, or the provider may have shown an error page
 * that never reached the callback. So the only thing that ends an attempt is
 * [dev.opencode.android.core.network.ServerApi.getOauthAttemptStatus] saying so, and this loop is
 * what asks.
 *
 * **Every exit is bounded.** The loop stops on the first terminal state, on cancellation, and after
 * [maxPolls] — the last one because an attempt the server never resolves would otherwise be a
 * request every [intervalMillis] for as long as the screen is open, which on a phone is a battery
 * complaint rather than a login. [maxPolls] is generous enough for a multi-minute consent screen.
 *
 * **A failed poll does not end the attempt.** See [ConnectAttemptState.Unreachable]. It is reported
 * so the screen can say "reconnecting", and the loop tries again; the total is still bounded by
 * [maxPolls] so a server that is down cannot produce an infinite loop either.
 */
class ConnectAttemptPoller(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val maxPolls: Int = DEFAULT_MAX_POLLS,
) {
    private var job: Job? = null

    /** The latest progress, which a test reads after the loop has been given time to finish. */
    var progress: ConnectAttemptProgress = ConnectAttemptProgress()
        private set

    /**
     * Polls [read] until it reports a terminal state or the attempt is cancelled.
     *
     * [read] answers the attempt's current status, or `null` when the server no longer has it — a
     * `404` on an attempt that was never created, or that has aged out. `null` ends the flow as
     * [ConnectAttemptState.Expired] rather than looping, because an attempt the server has forgotten
     * will not come back.
     */
    fun startOauth(
        scope: CoroutineScope,
        attemptID: String,
        read: suspend (String) -> OAuthAttemptStatus?,
        onState: (ConnectAttemptProgress) -> Unit = {},
    ) {
        start(
            scope = scope,
            attemptID = attemptID,
            read = { read(it) },
            fold = { status, current -> oauthFold(status, current) },
            onState = onState,
        )
    }

    /** The same loop for a command attempt, whose `pending` status carries its output. */
    fun startCommand(
        scope: CoroutineScope,
        attemptID: String,
        read: suspend (String) -> CommandAttemptStatus?,
        onState: (ConnectAttemptProgress) -> Unit = {},
    ) {
        start(
            scope = scope,
            attemptID = attemptID,
            read = { read(it) },
            fold = { status, current -> commandFold(status, current) },
            onState = onState,
        )
    }

    /**
     * The loop, generic over the two status unions.
     *
     * [pollOnce] returns `null` for a server that no longer has the attempt and a [Read] failure for
     * a transport error, which are opposites: the first ends the flow, the second does not.
     */
    private class Read<out T>(val value: T?, val error: Throwable?)

    private fun <S> start(
        scope: CoroutineScope,
        attemptID: String,
        read: suspend (String) -> S?,
        fold: (S?, ConnectAttemptProgress) -> ConnectAttemptProgress,
        onState: (ConnectAttemptProgress) -> Unit,
    ) {
        stop()
        progress = ConnectAttemptProgress(attemptID = attemptID, state = ConnectAttemptState.Pending)
        onState(progress)
        job = scope.launch {
            var polls = 0
            while (isActive && polls < maxPolls) {
                polls++
                val outcome: Read<S> = try {
                    Read(value = read(attemptID), error = null)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    Read(value = null, error = error)
                }
                progress = if (outcome.error != null) {
                    progress.copy(
                        state = ConnectAttemptState.Unreachable(
                            outcome.error.message ?: "The server could not be reached",
                        ),
                    )
                } else {
                    fold(outcome.value, progress)
                }.copy(polls = polls)
                onState(progress)
                if (progress.isTerminal) break
                delay(intervalMillis)
            }
            // The loop can also leave on the poll budget rather than on a terminal state. Saying
            // "expired" is the honest reading: the server never said it was done, and continuing to
            // poll in the background would be a request a minute for an attempt nobody is watching.
            if (!progress.isTerminal) {
                progress = progress.copy(state = ConnectAttemptState.Expired)
                onState(progress)
            }
        }
    }

    /**
     * Records a user cancel, and stops polling.
     *
     * **Local only.** The server is told through
     * [dev.opencode.android.core.network.ServerApi.cancelOauthAttempt] by the caller, because that
     * is a network call and this class is not a repository; the state is set here first so the UI
     * responds immediately rather than after a round trip that may fail.
     */
    fun cancel() {
        job?.cancel()
        job = null
        if (!progress.isTerminal) {
            progress = progress.copy(state = ConnectAttemptState.Cancelled)
        }
    }

    /** Stops polling without changing the state, for a screen that is being disposed. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun oauthFold(
        status: OAuthAttemptStatus?,
        current: ConnectAttemptProgress,
    ): ConnectAttemptProgress = when (status) {
        null -> current.copy(state = ConnectAttemptState.Expired)

        is OAuthAttemptStatus.Pending -> current.copy(state = ConnectAttemptState.Pending)

        is OAuthAttemptStatus.Complete -> current.copy(state = ConnectAttemptState.Complete)

        is OAuthAttemptStatus.Failed -> current.copy(state = ConnectAttemptState.Failed(status.message))

        is OAuthAttemptStatus.Expired -> current.copy(state = ConnectAttemptState.Expired)

        // An unknown status is not a terminal state. Treating it as one would end a login because a
        // server added a status this build has not heard of; treating it as pending costs one more
        // poll and the attempt's own expiry still ends it.
        is OAuthAttemptStatus.Unknown -> current.copy(state = ConnectAttemptState.Pending)
    }

    private fun commandFold(
        status: CommandAttemptStatus?,
        current: ConnectAttemptProgress,
    ): ConnectAttemptProgress = when (status) {
        null -> current.copy(state = ConnectAttemptState.Expired)

        is CommandAttemptStatus.Pending -> current.copy(
            state = ConnectAttemptState.Pending,
            output = append(current.output, status.message),
        )

        is CommandAttemptStatus.Complete -> current.copy(
            state = ConnectAttemptState.Complete,
        )

        is CommandAttemptStatus.Failed -> current.copy(
            state = ConnectAttemptState.Failed(status.message),
            output = append(current.output, status.message),
        )

        is CommandAttemptStatus.Expired -> current.copy(state = ConnectAttemptState.Expired)

        is CommandAttemptStatus.Unknown -> current.copy(state = ConnectAttemptState.Pending)
    }

    private fun append(existing: String, addition: String?): String {
        if (addition.isNullOrEmpty()) return existing
        // A device-code command prints the same line on every poll while it waits, so an identical
        // repeat is dropped rather than filling the panel with sixty copies of one code.
        if (existing.lines().lastOrNull() == addition.trimEnd()) return existing
        val merged = if (existing.isEmpty()) addition else existing.trimEnd('\n', ' ') + "\n" + addition
        return if (merged.length > ConnectAttemptProgress.MAX_OUTPUT_CHARS) {
            merged.takeLast(ConnectAttemptProgress.MAX_OUTPUT_CHARS)
        } else {
            merged
        }
    }

    companion object {
        /**
         * How long to wait between polls.
         *
         * Two seconds: a consent screen takes a human to read and approve, so a faster poll buys
         * nothing, and every poll is a request on a phone that may be on a metered connection.
         */
        const val DEFAULT_INTERVAL_MILLIS: Long = 2_000L

        /**
         * How many polls before the attempt is called expired.
         *
         * At the default interval this is a little over 33 minutes, which covers the slowest consent
         * flow in common use (an SSH-key check plus a browser sign-in) with room to spare, and is
         * far enough beyond it that a normal login never hits the budget.
         */
        const val DEFAULT_MAX_POLLS: Int = 1_000
    }
}

/**
 * What a poll means for the sheet, decided in one place.
 *
 * **This exists because a `when` inside a view model is where a login silently dies.** Phase 7
 * shipped a terminal whose `when` had an `else` that did nothing, so input never reached the socket
 * and the test that covered it still passed. The lesson is not "write a better `when`", it is that
 * the decision has to be *somewhere a test can reach*. So it is here, total, and every
 * [ConnectAttemptState] — including the two that are not terminal — has a stated consequence.
 *
 * **A success re-reads the catalog rather than adding a credential.** Plan §4.2: the client never
 * guesses. The new credential exists only once `integration.list` says so, so [REFRESH_AND_CLOSE]
 * is the whole of what a success does.
 */
enum class ConnectOutcome {
    /** Keep polling, keep the sheet open, and say nothing yet. */
    KEEP_WAITING,

    /** The login worked: re-read the credential list and close the sheet. */
    REFRESH_AND_CLOSE,

    /** The sheet stays open with the reason, because "try again" is the useful next action. */
    SHOW_FAILURE,

    /** The attempt timed out server-side. Same handling, different wording, hence a separate value. */
    SHOW_EXPIRY,

    /** The user cancelled. Nothing to report and nothing to refresh. */
    STAY_CLOSED,
    ;

    /** Whether the attempt is over, whichever value this is. */
    val isTerminal: Boolean get() = this != KEEP_WAITING
}

/** The consequence of the state the poller last reported. */
fun ConnectAttemptState.outcome(): ConnectOutcome = when (this) {
    ConnectAttemptState.Idle, ConnectAttemptState.Pending, is ConnectAttemptState.Unreachable ->
        ConnectOutcome.KEEP_WAITING

    ConnectAttemptState.Complete -> ConnectOutcome.REFRESH_AND_CLOSE

    is ConnectAttemptState.Failed -> ConnectOutcome.SHOW_FAILURE

    ConnectAttemptState.Expired -> ConnectOutcome.SHOW_EXPIRY

    ConnectAttemptState.Cancelled -> ConnectOutcome.STAY_CLOSED
}
