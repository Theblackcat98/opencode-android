package dev.opencode.android.navigation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

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
 * **The machine is fed only the keys that no focused control handled.** The caller reads it in the bubble phase of the
 * root, which runs after the focused control has had each key. A text field takes the letters and Ctrl+X it handles, so
 * Ctrl+X still cuts and typed letters still go into the field, and the machine never sees them. A control that does not
 * handle a key, such as a button, or nothing at all, leaves the key to the leader. No focus state is read, because the
 * root's own focus is not a reliable sign of whether a field has it.
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
    }
}
