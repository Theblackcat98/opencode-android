package dev.opencode.android.navigation

import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView

/** How long Ctrl+X waits for its letter. The letter must arrive no later than this many milliseconds after Ctrl+X. */
const val LEADER_TIMEOUT_MILLIS = 1_500L

/**
 * One leader mnemonic: the key that follows Ctrl+X, the letter the palette shows for it, and the command it runs.
 */
private class Mnemonic(val key: Key, val letter: Char, val commandId: String)

/**
 * The leader mnemonics. Each palette command has one letter, so the palette shows the same key the leader reads.
 */
private val MNEMONICS = listOf(
    Mnemonic(Key.N, 'N', PaletteCommandIds.NEW_SESSION),
    Mnemonic(Key.U, 'U', PaletteCommandIds.USAGE),
    Mnemonic(Key.Q, 'Q', PaletteCommandIds.QUICK_ASK),
    Mnemonic(Key.R, 'R', PaletteCommandIds.RPC_CONSOLE),
    Mnemonic(Key.P, 'P', PaletteCommandIds.PAIR_DEVICE),
    Mnemonic(Key.S, 'S', PaletteCommandIds.SERVERS),
    Mnemonic(Key.C, 'C', PaletteCommandIds.CHANGELOG),
    Mnemonic(Key.E, 'E', PaletteCommandIds.EVENT_INSPECTOR),
)

/** The letter that runs [commandId] after Ctrl+X, shown after the command in the palette, or `null` if it has none. */
fun leaderLetterOf(commandId: String): Char? = MNEMONICS.firstOrNull { it.commandId == commandId }?.letter

/** What one key-down did to the leader sequence. */
sealed interface LeaderStep {
    /** The key is not part of a leader sequence. The caller handles it as it would without leader keys. */
    data object Ignored : LeaderStep

    /** Ctrl+X was pressed, and the leader now waits for its letter. The key is consumed. */
    data object Armed : LeaderStep

    /** The letter named [command], typed within the timeout. The key is consumed and the caller runs the command. */
    data class Run(val command: PaletteCommand) : LeaderStep
}

/**
 * The leader-key state machine: Ctrl+X, then one letter within [LEADER_TIMEOUT_MILLIS], runs the palette command
 * whose mnemonic that letter is.
 *
 * It is fed every key event and holds only whether Ctrl+X is waiting and when it was pressed. The clock is passed in
 * on each call, so the timing is tested with plain numbers and no delay.
 *
 * **The machine knows nothing about focus.** Whether it may see a key is the caller's decision: [leaderKeys] stands aside
 * while text or a native control has focus, so this class is only the sequence, Ctrl+X and then a letter in time.
 *
 * **A key that is not the next step cancels the leader and is not consumed.** That covers an unknown letter, a letter
 * the palette does not offer for the current state, a letter held with Ctrl (so Ctrl+P still opens the palette), a
 * letter after the timeout, and Escape. Each leader is used once: a run clears it, and Ctrl+X pressed again while
 * waiting restarts the timeout.
 *
 * Only key-downs count. A key-up is ignored without changing the state, so the release of Ctrl+X does not cancel it.
 */
class LeaderKeyMachine(private val timeoutMillis: Long = LEADER_TIMEOUT_MILLIS) {
    private var armedAtMillis: Long? = null

    private val _armed = mutableStateOf(false)

    /**
     * Whether the leader is waiting for its letter. The machine sets it; the caller reads it to show
     * the armed indicator, and clears it through [cancel] when the wait times out.
     */
    val armed: State<Boolean> = _armed

    /**
     * Feeds one key event and says what it did.
     *
     * [altOrMeta] is whether Alt or Meta is held: a chord with either is not part of the sequence. [offered] is the list
     * the palette shows for the current state, and a mnemonic whose command is not in it is not run.
     */
    fun onKeyEvent(
        key: Key,
        type: KeyEventType,
        ctrl: Boolean,
        altOrMeta: Boolean,
        nowMillis: Long,
        offered: List<PaletteCommand>,
    ): LeaderStep {
        if (type != KeyEventType.KeyDown) return LeaderStep.Ignored
        val step = nextStep(key, ctrl, altOrMeta, nowMillis, offered)
        _armed.value = step == LeaderStep.Armed
        return step
    }

    private fun nextStep(
        key: Key,
        ctrl: Boolean,
        altOrMeta: Boolean,
        nowMillis: Long,
        offered: List<PaletteCommand>,
    ): LeaderStep {
        val armedAt = armedAtMillis
        armedAtMillis = null
        if (altOrMeta) return LeaderStep.Ignored
        if (key == Key.X && ctrl) {
            armedAtMillis = nowMillis
            return LeaderStep.Armed
        }
        if (armedAt == null || ctrl || nowMillis - armedAt > timeoutMillis) return LeaderStep.Ignored
        val commandId = MNEMONICS.firstOrNull { it.key == key }?.commandId ?: return LeaderStep.Ignored
        val command = offered.firstOrNull { it.id == commandId } ?: return LeaderStep.Ignored
        return LeaderStep.Run(command)
    }

    /** Drops a waiting leader, for a key the caller handled itself, such as Ctrl+P opening the palette. */
    fun cancel() {
        armedAtMillis = null
        _armed.value = false
    }
}

/**
 * Reads the leader keys from this node's key events, before the focused control, and runs the command each sequence
 * names.
 *
 * **The leader never runs while a text editor has focus, and the guard does not depend on the editor consuming keys.**
 * A leader that read the keys only after the focused field had them fired in the composer on the emulator: a letter
 * went into the draft and Usage still opened (matrix J5). So the leader reads the key first and asks the window whether
 * it may act. Compose answers [android.view.View.onCheckIsTextEditor] from its text input session, which a Compose text
 * field starts when it takes focus and stops when it loses it. While that is true the key is left to the field and any
 * waiting sequence is cancelled, so Ctrl+X still cuts in a field and a letter typed there is never a command.
 *
 * **A native view with focus is treated the same way.** The terminal is an AndroidView over a WebView, and a key it
 * takes is its own (Ctrl+X is a shell key), so the leader stands aside while any view inside the app that is not this
 * one has focus.
 *
 * The guard is read at key time, not when the modifier is composed, so a field that gains focus after the leader is
 * armed still stops the sequence.
 */
@Composable
fun Modifier.leaderKeys(
    leader: LeaderKeyMachine,
    offered: List<PaletteCommand>,
    onRun: (PaletteCommand) -> Unit,
): Modifier {
    val view = LocalView.current
    return onPreviewKeyEvent { event ->
        if (view.leaderIsBlocked()) {
            leader.cancel()
            return@onPreviewKeyEvent false
        }
        val step = leader.onKeyEvent(
            key = event.key,
            type = event.type,
            ctrl = event.isCtrlPressed,
            altOrMeta = event.isAltPressed || event.isMetaPressed,
            nowMillis = SystemClock.uptimeMillis(),
            offered = offered,
        )
        if (step is LeaderStep.Run) onRun(step.command)
        step != LeaderStep.Ignored
    }
}

/**
 * Whether the leader must stand aside because text or a native control has focus: a text editor in Compose, or any view
 * below this one that holds focus. With nothing focused, or with only Compose content focused, it is false.
 */
private fun View.leaderIsBlocked(): Boolean {
    if (onCheckIsTextEditor()) return true
    val focused = findFocus()
    return focused != null && focused !== this
}
