package dev.opencode.android.core.data.execution

import dev.opencode.android.core.model.ShellOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a command's output panel is doing.
 *
 * **Three states and no others, because the route has three answers.** `shell.output` returns a
 * page, and the command's *status* is a separate fact that arrives as `shell.exited` or as
 * [ShellOutputPoller]'s own read of `shell.get`. A panel that showed "finished" because its last page
 * was empty would be wrong exactly once — the case where output stops but the process has not
 * exited — and that is the case a user watching a build cares about.
 */
data class ShellOutputState(
    /** The output read so far, appended page by page. */
    val text: String = "",
    /** The byte cursor the next read starts at, which is only ever what a page returned. */
    val cursor: Long = 0L,
    /** The server's own output size, which is what "more to come" is measured against. */
    val size: Long = 0L,
    /** True when the server dropped output from this page. */
    val truncated: Boolean = false,
    /** The command's status, or `null` while it is running and the status has not been read. */
    val exited: Boolean = false,
    val status: String? = null,
    val exitCode: Int? = null,
    val error: String? = null,
) {
    /** Whether the cursor has caught up with the size the server last reported. */
    val caughtUp: Boolean get() = cursor >= size
}

/**
 * Streaming a command's output by byte cursor (features doc §30; plan §6, "Streaming output").
 *
 * **There is no event stream for output.** `shell.output` is a cursor-paged route and the only way
 * to see output as it appears is to ask for the part after the last one, which is what this does.
 * So the interesting parts are all about not asking wrongly:
 *
 *  - **The cursor is the server's.** Every poll sends the cursor the last page ended at. Advancing it
 *    locally would skip output on the next poll, and the gap is invisible: the panel would show a
 *    command's output with a hole in it.
 *  - **A page that is not full means stop asking.** [hasMore] is `truncated` or a cursor short of
 *    [ShellOutputState.size]. A poll that keeps asking after the server has nothing more returns the
 *    same empty page forever, and on a phone that is a request every second for a finished build.
 *  - **Terminal escape sequences are not interpreted here.** The output is raw bytes the server
 *    captured from a non-interactive shell, and the panel shows it as monospaced text. Stripping or
 *    colouring it would be an invention: a `grep --color=always` result and a progress bar's cursor
 *    moves are both already in the text, and the only honest rendering of a byte stream is the bytes.
 *  - **The text is bounded.** A build that prints a hundred megabytes would otherwise be held in a
 *    `String` on a phone. [MAX_CHARS] keeps the tail, which is the end of the output, and says how
 *    much it dropped.
 */
class ShellOutputPoller(
    /** How long to wait before the next poll, in milliseconds. */
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    /** How much output a panel holds. The oldest is dropped past this. */
    private val maxChars: Int = MAX_CHARS,
) {
    private var state = ShellOutputState()
    private var job: Job? = null

    /** The panel's state, which a view model folds into its own. */
    fun state(): ShellOutputState = state

    /**
     * Folds one page in.
     *
     * A page whose cursor is *behind* the one already held is a server that answered a stale request;
     * its text is appended anyway, because dropping output the server chose to send is worse than
     * showing it twice, and the cursor still moves forward so the next poll is not stuck.
     */
    fun accept(page: ShellOutput): ShellOutputState {
        val merged = state.text + page.output
        state = state.copy(
            text = if (merged.length > maxChars) merged.takeLast(maxChars) else merged,
            cursor = maxOf(page.cursor, state.cursor),
            size = page.size,
            truncated = page.truncated,
        )
        return state
    }

    /** Records the command's status, which is a separate call from its output. */
    fun exit(status: String, code: Int?): ShellOutputState {
        state = state.copy(exited = true, status = status, exitCode = code)
        return state
    }

    /** Records a failure to read, which is not the same as the command failing. */
    fun fail(message: String): ShellOutputState {
        state = state.copy(error = message)
        return state
    }

    /** Starts over for another command, so a panel can be reused without a leftover cursor. */
    fun reset() {
        state = ShellOutputState()
    }

    /** Whether another page could exist: the server said there is more, or the size says so. */
    fun hasMore(): Boolean = !state.exited && (state.truncated || state.cursor < state.size)

    /**
     * Polls until the command exits.
     *
     * [page] is asked for the output after [ShellOutputPoller.state]'s cursor, and it answers
     * `null` when the command is gone — a `404` on a command the server has already removed is the
     * end of the stream, not a failure to show. The loop stops on the first `null`, on the first
     * failure, or once [hasMore] is false, so it cannot spin.
     */
    fun start(
        scope: CoroutineScope,
        page: suspend (cursor: Long) -> ShellOutput?,
        onState: (ShellOutputState) -> Unit,
    ) {
        stop()
        onState(state)
        job = scope.launch {
            while (true) {
                val answer = runCatching { page(state.cursor) }.getOrNull()
                if (answer == null) {
                    // The command is no longer there to read. The last page stays on screen: it is
                    // the output the user came for, and dropping it on a `404` would be a worse lie
                    // than showing a command whose final status is unknown.
                    state = state.copy(error = null)
                    break
                }
                accept(answer)
                onState(state)
                if (!hasMore()) break
                delay(intervalMillis)
            }
            onState(state)
        }
    }

    /** Stops polling. Safe to call when nothing is running. */
    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Long enough not to hammer, short enough to look live on a build that prints steadily. */
        const val DEFAULT_INTERVAL_MILLIS: Long = 700L

        /**
         * How much output a panel holds: 512 KiB of characters.
         *
         * A `go build` of a large project is under it; a verbose test run is over it, and the tail is
         * the part that says what happened.
         */
        const val MAX_CHARS: Int = 512 * 1024
    }
}
