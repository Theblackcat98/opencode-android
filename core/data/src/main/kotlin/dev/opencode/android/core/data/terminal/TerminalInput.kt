package dev.opencode.android.core.data.terminal

/**
 * The extra-keys row's keys, and the bytes they become.
 *
 * **A phone keyboard has no control characters.** Esc, Tab, Ctrl, Alt and the arrows are what make a
 * terminal usable on a touchscreen, and none of them can be typed on the soft keyboard, so they are
 * a row of buttons. What a button press becomes is protocol, not UI: `vim` reads `0x1b` as "leave
 * insert mode" and a literal `Escape` character would do nothing at all, so the encoding is a pure
 * function and the tests assert the bytes rather than the row.
 *
 * **The latches persist until they are pressed again.** `CTRL` arms, the next key is sent as a
 * control code, and the latch stays armed for the key after that — which is what lets someone stop a
 * runaway process with `Ctrl` `C` `Ctrl` `C` or walk out of a `vim` with `Ctrl` `[`. A latch that
 * cleared itself after one key would make that two extra taps per key, and tapping `Ctrl` twice by
 * accident would then leave a modifier permanently on and a terminal nobody can type into.
 */
enum class TerminalKeyDirection(val sequence: String) {
    UP("A"),
    DOWN("B"),
    RIGHT("C"),
    LEFT("D"),
}

/** One thing the extra-keys row sent. */
sealed interface TerminalKey {
    /** A literal character, which is what the printable keys of the row are. */
    data class Literal(val value: String) : TerminalKey

    /** `Escape` (`0x1b`). */
    data object Escape : TerminalKey

    /** `Tab` (`0x09`). */
    data object Tab : TerminalKey

    /** `Enter` (`0x0d`), which a terminal expects as CR. */
    data object Enter : TerminalKey

    /** `Backspace` (`0x7f`): the DEL a terminal deletes with, not the BS a line discipline would. */
    data object Backspace : TerminalKey

    /** An arrow, as `ESC [ A`..`ESC [ D`. */
    data class Arrow(val direction: TerminalKeyDirection) : TerminalKey

    /** Arms or disarms the control modifier. */
    data object Control : TerminalKey

    /** Arms or disarms the meta modifier, sent as `ESC` before the next key. */
    data object Meta : TerminalKey

    /** `Ctrl` held together with a key, which is the `Ctrl`-`C` that stops a build. */
    data class ControlWith(val value: String) : TerminalKey
}

/**
 * Turns key presses into the bytes a terminal reads.
 *
 * **Total by construction, like every parser in this client.** Every [TerminalKey] produces bytes,
 * an empty list produces an empty string, and no input can make it throw: a screen that loses a key
 * press to an exception is a screen with a dead keyboard, and the one thing it must not do is
 * crash the app it is running in.
 */
object TerminalInput {

    /** `ESC` introduces a control sequence and the meta modifier. */
    const val ESCAPE: Char = '\u001B'

    /** The control code for `Ctrl`-`C`, the one that interrupts. */
    const val CTRL_C: String = "\u0003"

    /** The control code for `Ctrl`-`D`, the one that ends input. */
    const val CTRL_D: String = "\u0004"

    /**
     * Encodes a run of key presses in the order they happened.
     *
     * The whole run is encoded at once so the modifier latches are a fold over the sequence rather
     * than mutable state a test has to drive: `listOf(Control, Literal("c"))` is the same object
     * whether it came from two taps or from one paste, and it encodes to the same bytes.
     */
    fun encode(keys: List<TerminalKey>): String {
        var control = false
        var meta = false
        val out = StringBuilder()
        for (key in keys) {
            when (key) {
                TerminalKey.Control -> control = !control
                TerminalKey.Meta -> meta = !meta

                is TerminalKey.ControlWith -> {
                    // A value with no control code is sent as itself, which is what a `Ctrl` held with
                    // a digit should do: there is no such control character, and inventing one would
                    // put a byte in the terminal's input that no key press could have produced.
                    val character = key.value.firstOrNull()
                    val code = character?.let(::controlCode)
                    if (code != null) out.append(code) else out.append(key.value)
                }

                is TerminalKey.Literal -> {
                    key.value.forEach { character ->
                        val code = controlCode(character)
                        when {
                            // The latches are **not** cleared here. They persist until they are pressed
                            // again, which is what lets a user stop a runaway build with Ctrl C Ctrl C
                            // and walk out of a `vim` with Ctrl [.
                            control && code != null -> out.append(code)
                            meta -> out.append(ESCAPE).append(character)
                            else -> out.append(character)
                        }
                    }
                }

                TerminalKey.Escape -> out.append(ESCAPE)
                TerminalKey.Tab -> out.append('\t')
                // CR, not LF: a line discipline that submits on LF submits twice.
                TerminalKey.Enter -> out.append('\r')
                // DEL, which is what a terminal deletes with rather than the BS a printer would.
                TerminalKey.Backspace -> out.append('\u007F')
                is TerminalKey.Arrow -> out.append(ESCAPE).append('[').append(key.direction.sequence)
            }
        }
        return out.toString()
    }

    /** Encodes one key, for the caller that presses one at a time. */
    fun encode(key: TerminalKey): String = encode(listOf(key))

    /**
     * The control code for a character, or `null` when there is none.
     *
     * `Ctrl` is the C0 range, which is how a terminal defines it: `a`..`z` map to `0x01`..`0x1a` and
     * `@`..`_` map to `0x00`..`0x1f`. A character outside those ranges has no control code, and this
     * returns `null` rather than sending the character with the modifier silently dropped — which is
     * what a caller does with the `null`, and the difference is visible in a test.
     */
    fun controlCode(character: Char): String? = when (character) {
        in 'a'..'z' -> (character.code - 'a'.code + 1).toChar().toString()
        in 'A'..'Z' -> (character.code - 'A'.code + 1).toChar().toString()
        in '@'..'_' -> (character.code - '@'.code).toChar().toString()
        ' ' -> "\u0000"
        else -> null
    }

    /** Whether a control modifier is armed, which is what the row's own pressed state shows. */
    fun isControl(key: TerminalKey): Boolean = key == TerminalKey.Control

    /** Whether a meta modifier is armed. */
    fun isMeta(key: TerminalKey): Boolean = key == TerminalKey.Meta
}

/**
 * The keys the extra-keys row offers, in the order a Termux-style row puts them.
 *
 * The set is the plan's: Esc, Tab, Ctrl, Alt, the arrows, and the three characters a phone keyboard
 * makes hard to reach — `|`, `~` and `/`. It is data rather than layout so the row's contents are
 * assertable, and a screenshot can be taken of a row whose keys are named.
 */
object ExtraKeys {

    /** The row, left to right. */
    val row: List<TerminalKey> = listOf(
        TerminalKey.Escape,
        TerminalKey.Tab,
        TerminalKey.Control,
        TerminalKey.Meta,
        TerminalKey.Arrow(TerminalKeyDirection.LEFT),
        TerminalKey.Arrow(TerminalKeyDirection.DOWN),
        TerminalKey.Arrow(TerminalKeyDirection.UP),
        TerminalKey.Arrow(TerminalKeyDirection.RIGHT),
        TerminalKey.Literal("|"),
        TerminalKey.Literal("~"),
        TerminalKey.Literal("/"),
    )

    /**
     * The label a key is drawn with, as the plan names it.
     *
     * A label is data here rather than a string resource because the extra-keys row is a fixed set of
     * terminal keys and each one's name is the *protocol's* name — `ESC` is what every terminal
     * manual calls it. The strings that need translating are the row's own description and the
     * TalkBack labels that go with the state, not the key's glyph.
     */
    fun label(key: TerminalKey): String = when (key) {
        TerminalKey.Escape -> "ESC"
        TerminalKey.Tab -> "TAB"
        TerminalKey.Control -> "CTRL"
        TerminalKey.Meta -> "ALT"
        TerminalKey.Enter -> "⏎"
        TerminalKey.Backspace -> "⌫"
        is TerminalKey.Literal -> key.value
        is TerminalKey.Arrow -> when (key.direction) {
            TerminalKeyDirection.UP -> "↑"
            TerminalKeyDirection.DOWN -> "↓"
            TerminalKeyDirection.LEFT -> "←"
            TerminalKeyDirection.RIGHT -> "→"
        }

        is TerminalKey.ControlWith -> "^${key.value.uppercase()}"
    }

    /** Whether a key is a modifier, which is what makes its own button stay pressed. */
    fun isModifier(key: TerminalKey): Boolean = key == TerminalKey.Control || key == TerminalKey.Meta
}
