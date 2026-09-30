package dev.opencode.android.core.data.execution

import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.model.ShellOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a command's output panel is doing.
 *
 * **Three facts and no more, because the route gives three answers.** `shell.output` returns a page,
 * the command's *status* is a separate fact that arrives as `shell.exited` or as a read of `shell.get`,
 * and a failure to read is neither. A panel that showed "finished" because its last page was empty would
 * be wrong exactly when a user watching a build cares: output has stopped and the process has not.
 */
data class ShellOutputState(
    /** The output read so far, appended page by page. */
    val text: String = "",
    /** The byte cursor the next read starts at, which is only ever what a page returned. */
    val cursor: Long = 0L,
    /** The server's own output size, which is what "more to come" is measured against. */
    val size: Long = 0L,
    /**
     * True once the server has said it dropped output. Sticky: a later, empty page says `false` again, and
     * taking the note back would be a claim that the output on screen is whole.
     */
    val truncated: Boolean = false,
    /** Whether the command's end has been read, from `shell.exited` or `shell.get`. */
    val exited: Boolean = false,
    val status: String? = null,
    val exitCode: Int? = null,
    /** Why reading stopped, when it did: several polls in a row failed. `null` while reading works. */
    val error: String? = null,
) {
    /** Whether the cursor has caught up with the size the server last reported. */
    val caughtUp: Boolean get() = cursor >= size
}

/** How a command ended, as `shell.get` reports it. */
data class ShellExit(val status: String, val code: Int?)

/**
 * Streaming a command's output by byte cursor (features doc §30; plan §6, "Streaming output").
 *
 * **There is no event stream for output.** `shell.output` is a cursor-paged route and the only way
 * to see output as it appears is to ask for the part after the last one, which is what this does.
 * The interesting parts are all about *when to stop asking*, because the way this went wrong was
 * stopping:
 *
 *  - **An empty page is not the end.** A command that has not printed yet, or is between two lines
 *    (`echo one; sleep 3; echo two`), answers a page with nothing in it and a cursor equal to the size.
 *    That says the client has caught up, and says nothing about the command. The loop ends when the
 *    command has *ended* and one read after that came back caught up, not before.
 *  - **The end is read before the last page, not after.** Once an exit is known, the read that follows
 *    sees every byte the command wrote, so a caught-up answer to *that* read really is the end. Reading
 *    the page first and the status second would lose the lines written in between.
 *  - **The end is learned two ways.** [exit] is called when the store's `shell.exited` event arrives,
 *    and the loop asks [start]'s `status` itself whenever a page brings nothing new, so an event the
 *    client missed (a dropped stream, a phone in a pocket) delays the stop and cannot prevent it.
 *  - **A page that moved the cursor is followed at once, and a page that did not is waited out.** The
 *    server reads at most 64 KiB per page, so a command that printed a megabyte is a run of pages with no
 *    pause between them; but the server can also count bytes it has not written to disk yet, so
 *    `cursor < size` with nothing returned is an answer to try again *later*, not in a tight loop.
 *  - **The cursor is the server's.** Every poll sends the cursor the last page ended at. Advancing it
 *    locally would skip output on the next poll, and the gap is invisible.
 *  - **A failure is retried, a `404` is not.** One dropped request is not the end of a stream; several in
 *    a row stop it and say so in [ShellOutputState.error]. A command the server no longer has ends the
 *    stream quietly, with the last page left on screen.
 *  - **Terminal escape sequences are not interpreted here.** The output is raw bytes the server captured
 *    from a non-interactive shell, and the panel shows it as monospaced text. The only honest rendering of
 *    a byte stream is the bytes.
 *  - **The text is bounded.** A build that prints a hundred megabytes would otherwise be held in a
 *    `String` on a phone. [MAX_CHARS] keeps the tail, which is the end of the output.
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

    /** Whether a polling loop is running, so a caller that learns of an exit knows whether to start one. */
    val isPolling: Boolean get() = job?.isActive == true

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
            truncated = state.truncated || page.truncated,
            error = null,
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

    /**
     * Whether the server has bytes this client has not read: the cursor is short of the size.
     *
     * **This says nothing about whether the command is done.** It is the question "is there a page to
     * fetch right now", which is true for an exited command whose tail was not read yet, and false for a
     * running command that has printed nothing.
     */
    fun hasMore(): Boolean = state.cursor < state.size

    /**
     * Polls until the command has ended and its last page was read.
     *
     * [page] is asked for the output after [ShellOutputPoller.state]'s cursor. It answers `null` when the
     * command is gone — a `404` on a command the server has already removed is the end of the stream, not a
     * failure to show — and throws when the read failed. [status] answers how the command ended, or `null`
     * while it is running or the answer could not be had. [onState] is told after every change.
     */
    fun start(
        scope: CoroutineScope,
        page: suspend (cursor: Long) -> ShellOutput?,
        status: suspend () -> ShellExit? = { null },
        onState: (ShellOutputState) -> Unit,
    ) {
        stop()
        onState(state)
        job = scope.launch {
            poll(page, status, onState)
            onState(state)
        }
    }

    private suspend fun poll(
        page: suspend (cursor: Long) -> ShellOutput?,
        status: suspend () -> ShellExit?,
        onState: (ShellOutputState) -> Unit,
    ) {
        var idle = 0
        var failures = 0
        while (true) {
            // Read before the page, not after: an exit known now means this read sees every byte written.
            val endKnown = state.exited
            val answer = try {
                page(state.cursor)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failures++
                if (failures >= MAX_FAILURES) {
                    fail(error.toActionError().message)
                    return
                }
                delay(intervalMillis * failures)
                continue
            }
            failures = 0
            // The command is no longer there to read. The last page stays on screen: it is the output the
            // user came for, and dropping it on a `404` would be a worse lie than showing a command whose
            // final status is unknown.
            if (answer == null) return
            val before = state.cursor
            accept(answer)
            onState(state)
            val progressed = state.cursor > before
            idle = if (progressed) 0 else idle + 1
            if (progressed && hasMore()) continue
            if (endKnown) {
                if (!hasMore() || idle >= FINAL_IDLE_LIMIT) return
            } else if (!progressed) {
                val ended = asked(status)
                if (ended != null) {
                    exit(ended.status, ended.code)
                    onState(state)
                    continue
                }
            }
            delay(intervalMillis * idle.coerceIn(1, MAX_BACKOFF_STEPS))
        }
    }

    /** `shell.get`, where a failure is the same as no answer: the next page will try again anyway. */
    private suspend fun asked(status: suspend () -> ShellExit?): ShellExit? = try {
        status()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
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
         * How many times the interval a quiet command is waited out for: 1x, 2x, then 3x. A build that
         * has gone silent for a while is asked about less often; the first line it prints ends the wait.
         */
        const val MAX_BACKOFF_STEPS: Int = 3

        /** Reads in a row that fail before the stream is given up on. */
        const val MAX_FAILURES: Int = 5

        /** Reads after an exit that bring nothing while the size says there is more, before giving up. */
        const val FINAL_IDLE_LIMIT: Int = 3

        /**
         * How much output a panel holds: 512 KiB of characters.
         *
         * A `go build` of a large project is under it; a verbose test run is over it, and the tail is
         * the part that says what happened.
         */
        const val MAX_CHARS: Int = 512 * 1024
    }
}
