package dev.opencode.android.core.data.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extra-keys row's bytes (plan §6, "PTY terminals").
 *
 * **The assertions are on bytes, not on the row.** What a `Ctrl`-`C` sends is the protocol, and a test
 * that checked "the row has a Ctrl button" would pass with a row that sent the letter `c`.
 */
class TerminalInputTest {

    @Test
    fun `escape is ESC`() {
        assertEquals("\u001B", TerminalInput.encode(TerminalKey.Escape))
    }

    @Test
    fun `tab and enter and backspace are the terminal's own control codes`() {
        assertEquals("\t", TerminalInput.encode(TerminalKey.Tab))
        // A terminal expects CR for Enter, not LF: LF is what a program prints, and a line discipline
        // that submits on LF submits twice.
        assertEquals("\r", TerminalInput.encode(TerminalKey.Enter))
        // DEL, which is what a terminal deletes with.
        assertEquals("\u007F", TerminalInput.encode(TerminalKey.Backspace))
    }

    @Test
    fun `arrows are the ANSI sequences`() {
        assertEquals("\u001B[A", TerminalInput.encode(TerminalKey.Arrow(TerminalKeyDirection.UP)))
        assertEquals("\u001B[B", TerminalInput.encode(TerminalKey.Arrow(TerminalKeyDirection.DOWN)))
        assertEquals("\u001B[C", TerminalInput.encode(TerminalKey.Arrow(TerminalKeyDirection.RIGHT)))
        assertEquals("\u001B[D", TerminalInput.encode(TerminalKey.Arrow(TerminalKeyDirection.LEFT)))
    }

    @Test
    fun `control with a letter is the C0 code`() {
        assertEquals(TerminalInput.CTRL_C, TerminalInput.encode(listOf(TerminalKey.Control, TerminalKey.Literal("c"))))
        assertEquals(TerminalInput.CTRL_D, TerminalInput.encode(listOf(TerminalKey.Control, TerminalKey.Literal("d"))))
        assertEquals("\u0001", TerminalInput.encode(listOf(TerminalKey.Control, TerminalKey.Literal("a"))))
    }

    @Test
    fun `the control latch persists until it is pressed again`() {
        // Termux's row behaves this way, and it is what lets a user stop a build with Ctrl C Ctrl C.
        val sequence = listOf(
            TerminalKey.Control,
            TerminalKey.Literal("c"),
            TerminalKey.Literal("d"),
        )
        assertEquals("\u0003\u0004", TerminalInput.encode(sequence))
        // Pressing it again disarms it, so the key after that is a letter again.
        assertEquals(
            "\u0003\u0004c",
            TerminalInput.encode(sequence + TerminalKey.Control + TerminalKey.Literal("c")),
        )
    }

    @Test
    fun `control with punctuation uses the C0 range too`() {
        // `@`..`_` maps to `0x00`..`0x1f`, and `[` is 0x5b, so `Ctrl`-`[` is 0x1b — which is exactly
        // what `vim` reads as "leave insert mode", and why the row has an Escape key of its own.
        assertEquals(
            "\u001B",
            TerminalInput.encode(listOf(TerminalKey.Control, TerminalKey.Literal("["))),
        )
        assertEquals(
            "\u0000",
            TerminalInput.encode(listOf(TerminalKey.Control, TerminalKey.Literal("@"))),
        )
    }

    @Test
    fun `a character with no control code is sent as itself`() {
        // There is no `Ctrl`-`1` in the C0 range, and inventing a byte for it would put something in
        // the terminal's input that no key press could have produced.
        assertEquals("1", TerminalInput.encode(listOf(TerminalKey.Control, TerminalKey.Literal("1"))))
    }

    @Test
    fun `control-with is the held form`() {
        assertEquals(TerminalInput.CTRL_C, TerminalInput.encode(TerminalKey.ControlWith("c")))
        assertEquals("1", TerminalInput.encode(TerminalKey.ControlWith("1")))
    }

    @Test
    fun `meta sends escape then the character`() {
        assertEquals("\u001Bb", TerminalInput.encode(listOf(TerminalKey.Meta, TerminalKey.Literal("b"))))
    }

    @Test
    fun `the meta latch persists like the control one`() {
        assertEquals(
            "\u001Bb\u001Bf",
            TerminalInput.encode(
                listOf(TerminalKey.Meta, TerminalKey.Literal("b"), TerminalKey.Literal("f")),
            ),
        )
    }

    @Test
    fun `a run of keys is encoded in order as one string`() {
        assertEquals(
            "git status\r",
            TerminalInput.encode(
                listOf(
                    TerminalKey.Literal("g"),
                    TerminalKey.Literal("i"),
                    TerminalKey.Literal("t"),
                    TerminalKey.Literal(" "),
                    TerminalKey.Literal("s"),
                    TerminalKey.Literal("t"),
                    TerminalKey.Literal("a"),
                    TerminalKey.Literal("t"),
                    TerminalKey.Literal("u"),
                    TerminalKey.Literal("s"),
                    TerminalKey.Enter,
                ),
            ),
        )
    }

    @Test
    fun `an empty run and an unknown character produce something total`() {
        assertEquals("", TerminalInput.encode(emptyList()))
        assertEquals("", TerminalInput.encode(TerminalKey.Literal("")))
    }

    @Test
    fun `the row is the plan's, in the plan's order`() {
        assertEquals(
            listOf("ESC", "TAB", "CTRL", "ALT", "←", "↓", "↑", "→", "|", "~", "/"),
            ExtraKeys.row.map(ExtraKeys::label),
        )
    }

    @Test
    fun `only the two latches are modifiers`() {
        assertTrue(ExtraKeys.isModifier(TerminalKey.Control))
        assertTrue(ExtraKeys.isModifier(TerminalKey.Meta))
        assertTrue(ExtraKeys.row.filter(ExtraKeys::isModifier).size == 2)
    }

    @Test
    fun `controlCode answers null for a character outside the C0 range`() {
        assertEquals("\u0001", TerminalInput.controlCode('a'))
        assertEquals("\u0000", TerminalInput.controlCode(' '))
        assertNull(TerminalInput.controlCode('1'))
        assertNull(TerminalInput.controlCode('é'))
    }
}
