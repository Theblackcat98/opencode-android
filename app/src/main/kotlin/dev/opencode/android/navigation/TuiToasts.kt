package dev.opencode.android.navigation

import androidx.compose.material3.SnackbarDuration
import dev.opencode.android.core.data.tui.TuiControl

/**
 * The text a TUI toast shows: its title and message joined by `": "` when both exist, otherwise whichever one does.
 *
 * The title and message are the server's words, so they are shown as they arrived and are not app strings. A blank
 * title counts as absent, so that an empty title does not put a stray separator in front of the message.
 */
internal fun toastText(toast: TuiControl.Toast): String {
    val title = toast.title?.takeIf { it.isNotBlank() }
    val message = toast.message
    return when {
        title == null -> message
        message.isBlank() -> title
        else -> "$title: $message"
    }
}

/** An error stays up longer, because it is the toast the user most needs to read. Every other level is brief. */
internal fun toastDuration(level: TuiControl.ToastLevel): SnackbarDuration =
    if (level == TuiControl.ToastLevel.ERROR) SnackbarDuration.Long else SnackbarDuration.Short
