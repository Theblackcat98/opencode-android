package dev.opencode.android.navigation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The leader keys: Ctrl+X, then a mnemonic letter within the timeout. The clock is a number passed in on each key, so
 * the timing is tested with timestamps and no delay, and each test feeds the machine the same events a key handler
 * would.
 */
class LeaderKeysTest {

    /** The list the palette offers with an active server, which is the widest set a leader key can run from. */
    private val withServer = paletteCommands(hasActiveServer = true)

    /** The list with no active server, where the commands that act on a server are not offered. */
    private val withoutServer = paletteCommands(hasActiveServer = false)

    private fun LeaderKeyMachine.press(
        key: Key,
        at: Long,
        ctrl: Boolean = false,
        altOrMeta: Boolean = false,
        type: KeyEventType = KeyEventType.KeyDown,
        offered: List<PaletteCommand> = withServer,
    ): LeaderStep = onKeyEvent(
        key = key,
        type = type,
        ctrl = ctrl,
        altOrMeta = altOrMeta,
        nowMillis = at,
        offered = offered,
    )

    private fun runOf(step: LeaderStep): String? = (step as? LeaderStep.Run)?.command?.id

    @Test
    fun `Ctrl+X arms the leader and the key is consumed`() {
        assertEquals(LeaderStep.Armed, LeaderKeyMachine().press(Key.X, at = 0, ctrl = true))
    }

    @Test
    fun `the timeout is one and a half seconds`() {
        assertEquals(1_500L, LEADER_TIMEOUT_MILLIS)
    }

    @Test
    fun `a mnemonic typed after Ctrl+X runs the command it names`() {
        val machine = LeaderKeyMachine()
        machine.press(Key.X, at = 0, ctrl = true)
        assertEquals(PaletteCommandIds.NEW_SESSION, runOf(machine.press(Key.N, at = 400)))
    }

    @Test
    fun `each mnemonic runs the command the palette shows it for`() {
        val expected = listOf(
            Key.N to PaletteCommandIds.NEW_SESSION,
            Key.U to PaletteCommandIds.USAGE,
            Key.Q to PaletteCommandIds.QUICK_ASK,
            Key.R to PaletteCommandIds.RPC_CONSOLE,
            Key.P to PaletteCommandIds.PAIR_DEVICE,
            Key.S to PaletteCommandIds.SERVERS,
            Key.C to PaletteCommandIds.CHANGELOG,
            Key.E to PaletteCommandIds.EVENT_INSPECTOR,
        )
        for ((key, id) in expected) {
            val machine = LeaderKeyMachine()
            machine.press(Key.X, at = 0, ctrl = true)
            assertEquals("the letter for $id", id, runOf(machine.press(key, at = 10)))
        }
    }

    @Test
    fun `the palette shows the letters the leader reads, one per command`() {
        val letters = withServer.associate { it.id to leaderLetterOf(it.id) }
        assertEquals(
            mapOf(
                PaletteCommandIds.SERVERS to 'S',
                PaletteCommandIds.NEW_SESSION to 'N',
                PaletteCommandIds.USAGE to 'U',
                PaletteCommandIds.QUICK_ASK to 'Q',
                PaletteCommandIds.RPC_CONSOLE to 'R',
                PaletteCommandIds.PAIR_DEVICE to 'P',
                PaletteCommandIds.CHANGELOG to 'C',
                PaletteCommandIds.EVENT_INSPECTOR to 'E',
            ),
            letters,
        )
        assertNull(leaderLetterOf("no_such_command"))
    }

    @Test
    fun `a command the palette does not offer is not run`() {
        val machine = LeaderKeyMachine()
        machine.press(Key.X, at = 0, ctrl = true)
        // Usage needs an active server, so with none it is not offered and the letter does nothing.
        assertEquals(LeaderStep.Ignored, machine.press(Key.U, at = 10, offered = withoutServer))
    }

    @Test
    fun `a command the palette does offer is run when no server is active`() {
        val machine = LeaderKeyMachine()
        machine.press(Key.X, at = 0, ctrl = true)
        assertEquals(PaletteCommandIds.SERVERS, runOf(machine.press(Key.S, at = 10, offered = withoutServer)))
    }

    @Test
    fun `an unknown key cancels the leader, is not consumed, and the next letter runs nothing`() {
        val machine = LeaderKeyMachine()
        machine.press(Key.X, at = 0, ctrl = true)
        assertEquals(LeaderStep.Ignored, machine.press(Key.Z, at = 10))
        assertEquals(LeaderStep.Ignored, machine.press(Key.N, at = 20))
    }

    @Test
    fun `a letter exactly at the timeout still runs its command`() {
        val machine = LeaderKeyMachine()
        machine.press(Key.X, at = 1_000, ctrl = true)
        assertEquals(PaletteCommandIds.NEW_SESSION, runOf(machine.press(Key.N, at = 2_500)))
    }

    @Test
    fun `a letter one millisecond after the timeout cancels the leader`() {
        val machine = LeaderKeyMachine()
        machine.press(Key.X, at = 1_000, ctrl = true)
        assertEquals(LeaderStep.Ignored, machine.press(Key.N, at = 2_501))
    }

    @Test
    fun `a second Ctrl+X restarts the timeout from that press`() {
        val machine = LeaderKeyMachine()
        machine.press(Key.X, at = 0, ctrl = true)
        assertEquals(LeaderStep.Armed, machine.press(Key.X, at = 1_000, ctrl = true))
        // 2400 is more than 1500 after the first press, but only 1400 after the second, which restarted it.
        assertEquals(PaletteCommandIds.USAGE, runOf(machine.press(Key.U, at = 2_400)))
    }

    @Test
    fun `a run uses up the leader, so the next letter runs nothing`() {
        val machine = LeaderKeyMachine()
        machine.press(Key.X, at = 0, ctrl = true)
        assertEquals(PaletteCommandIds.NEW_SESSION, runOf(machine.press(Key.N, at = 10)))
        assertEquals(LeaderStep.Ignored, machine.press(Key.U, at = 20))
    }

    @Test
    fun `a letter without Ctrl+X before it runs nothing`() {
        assertEquals(LeaderStep.Ignored, LeaderKeyMachine().press(Key.N, at = 0))
    }

    @Test
    fun `cancel drops a waiting leader, so a letter after it runs nothing`() {
        val machine = LeaderKeyMachine()
        machine.press(Key.X, at = 0, ctrl = true)
        machine.cancel()
        assertEquals(LeaderStep.Ignored, machine.press(Key.N, at = 10))
    }

    @Test
    fun `a letter held with Ctrl cancels the leader, so Ctrl+P still reaches the palette`() {
        val machine = LeaderKeyMachine()
        machine.press(Key.X, at = 0, ctrl = true)
        assertEquals(LeaderStep.Ignored, machine.press(Key.P, at = 10, ctrl = true))
        assertTrue(isPaletteShortcut(Key.P, ctrl = true, type = KeyEventType.KeyDown))
        assertEquals(LeaderStep.Ignored, machine.press(Key.N, at = 20))
    }

    @Test
    fun `Ctrl+X with Alt or Meta held does not arm, and a letter with Alt cancels`() {
        val machine = LeaderKeyMachine()
        assertEquals(LeaderStep.Ignored, machine.press(Key.X, at = 0, ctrl = true, altOrMeta = true))
        assertEquals(LeaderStep.Ignored, machine.press(Key.N, at = 10))

        machine.press(Key.X, at = 20, ctrl = true)
        assertEquals(LeaderStep.Ignored, machine.press(Key.N, at = 30, altOrMeta = true))
        assertEquals(LeaderStep.Ignored, machine.press(Key.U, at = 40))
    }

    @Test
    fun `a key-up neither arms the leader nor cancels it`() {
        val machine = LeaderKeyMachine()
        assertEquals(LeaderStep.Ignored, machine.press(Key.X, at = 0, ctrl = true, type = KeyEventType.KeyUp))
        assertEquals(LeaderStep.Ignored, machine.press(Key.N, at = 10))

        machine.press(Key.X, at = 20, ctrl = true)
        assertEquals(LeaderStep.Ignored, machine.press(Key.N, at = 25, type = KeyEventType.KeyUp))
        assertEquals(PaletteCommandIds.NEW_SESSION, runOf(machine.press(Key.N, at = 30)))
    }
}
