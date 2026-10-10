package dev.opencode.android.navigation

import androidx.compose.material3.SnackbarDuration
import dev.opencode.android.core.data.tui.TuiControl
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a TUI toast says and how long it stays, as plain functions with no Compose or Android behind them. */
class TuiToastsTest {

    private fun toast(title: String?, message: String, level: TuiControl.ToastLevel = TuiControl.ToastLevel.INFO) =
        TuiControl.Toast(title = title, message = message, level = level, directory = null)

    @Test
    fun `a title and a message are joined by a colon and a space`() {
        assertEquals("Saved: Config written", toastText(toast("Saved", "Config written")))
    }

    @Test
    fun `a toast without a title shows its message alone`() {
        assertEquals("Config written", toastText(toast(null, "Config written")))
    }

    @Test
    fun `a blank title is treated as absent, so no separator leads the message`() {
        assertEquals("Config written", toastText(toast("   ", "Config written")))
    }

    @Test
    fun `a toast with a title and an empty message shows the title alone`() {
        assertEquals("Saved", toastText(toast("Saved", "")))
    }

    @Test
    fun `an error stays up longer than any other level`() {
        assertEquals(SnackbarDuration.Long, toastDuration(TuiControl.ToastLevel.ERROR))
    }

    @Test
    fun `every other level is brief`() {
        listOf(TuiControl.ToastLevel.INFO, TuiControl.ToastLevel.SUCCESS, TuiControl.ToastLevel.WARNING)
            .forEach { level -> assertEquals(level.name, SnackbarDuration.Short, toastDuration(level)) }
    }
}
