package dev.opencode.android.feature.integrations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import dev.opencode.android.core.model.IntegrationMethod
import dev.opencode.android.core.model.McpServer

/**
 * The tags the Phase 8 screens carry, so a UI test addresses a row by its identity and not by its
 * position.
 *
 * **A tag is derived from the thing it names, never from an index.** The integration list is
 * rebuilt on every event, and an addressable test that used `items[1]` would silently start testing
 * a different integration the moment the order changed.
 */
object IntegrationsTags {
    /** The API key field, in every flow that has one. */
    const val KEY_FIELD: String = "connect:key"

    /** The device-code field of a `mode=code` attempt. */
    const val CODE_FIELD: String = "connect:code"

    /** The button that opens the provider in a Custom Tab. Rendered only for a checked URL. */
    const val OPEN_OAUTH: String = "connect:open-oauth"

    /** The copy button for a device code found in a command's output. */
    const val COPY_CODE: String = "connect:copy-code"

    /** Cancels a running attempt, available in both flows and in both modes. */
    const val CANCEL_ATTEMPT: String = "connect:cancel-attempt"

    fun method(method: IntegrationMethod): String = when (method) {
        is IntegrationMethod.Key -> "connect:method:key:${method.labelOrNull.orEmpty()}"
        is IntegrationMethod.OAuth -> "connect:method:oauth:${method.id}"
        is IntegrationMethod.Command -> "connect:method:command:${method.id}"
        is IntegrationMethod.Env -> "connect:method:env:${method.names.joinToString(",")}"
        is IntegrationMethod.Unknown -> "connect:method:unknown:${method.declaredType}"
    }

    fun formField(key: String): String = "connect:field:$key"

    fun credential(id: String): String = "connect:credential:$id"

    fun server(name: String): String = "mcp:server:$name"

    fun plugin(key: String): String = "plugin:$key"

    fun provider(id: String): String = "provider:$id"
}

/**
 * Makes the window this composition is in refuse to be captured.
 *
 * **This is the one mitigation Compose can offer on its own, and it is applied for the lifetime of
 * the sheet that has a key in it.** A password field's visual transformation hides the characters
 * from the *user*; it does nothing about the task switcher, which snapshots the window, or about a
 * screen recording, or about a screenshot the OS takes. `FLAG_SECURE` is what the platform provides,
 * and the platform is the only layer that can refuse.
 *
 * **It is a window flag, not a view flag, so it is set and cleared in a `DisposableEffect` keyed on
 * the composition.** Leaving it set would blank the recents thumbnail for the rest of the app's
 * life, which is a worse trade than the one screen it protects — so the previous value is restored
 * rather than the flag being blindly cleared, because a host may have set it for its own reasons.
 *
 * **It costs the user their own screenshot ability on this screen.** That is the right way round:
 * the thing being protected is a credential that, once photographed, is in a photo library the
 * server has no way to revoke.
 */
@Composable
fun SecureWindowEffect(enabled: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = view.context.findActivity()?.window
        if (!enabled || window == null) return@DisposableEffect onDispose { }
        val previous = window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (previous == 0) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

/**
 * The activity a composable's context belongs to, or `null` off-device.
 *
 * A `ContextWrapper` chain rather than a cast, because a composable in a dialog, a bottom sheet or a
 * preview is not in the activity directly, and an unhandled cast in a `DisposableEffect` would take
 * the composition down rather than skip the protection.
 */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var context: android.content.Context? = this
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) return context
        context = context.baseContext
    }
    return context as? android.app.Activity
}
