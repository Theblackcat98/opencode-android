package dev.opencode.android.feature.servers.ui

import androidx.annotation.StringRes
import dev.opencode.android.core.data.repository.AddServerErrorType
import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.network.ConnectionState
import dev.opencode.android.core.network.DisconnectCause
import dev.opencode.android.core.network.toServerBaseUrl
import dev.opencode.android.feature.servers.R

/**
 * [String.toServerBaseUrl] as a predicate, so a bad address never throws out of a UI callback.
 * The normalized form is what a server is saved under, so this is also the acceptance test for
 * anything the user types.
 */
internal fun String.toServerBaseUrlOrNull(): String? = runCatching { toServerBaseUrl() }.getOrNull()

/**
 * The failure classes the onboarding help is written for (plan §6, Phase 1), mapped to the text a
 * user reads.
 *
 * The mapping lives in the feature module, not in `core:network`, so the transport stays free of
 * user-facing text and the wording can be changed without touching it.
 */
@StringRes
fun AddServerErrorType.messageRes(): Int = when (this) {
    // The most common first-run failure by far: the server only listens on localhost.
    AddServerErrorType.UNREACHABLE -> R.string.error_unreachable
    AddServerErrorType.UNAUTHORIZED -> R.string.error_unauthorized
    AddServerErrorType.PAIRING_CODE_REJECTED -> R.string.error_pairing_code_rejected
    AddServerErrorType.TLS_ERROR -> R.string.error_tls
    AddServerErrorType.TIMEOUT -> R.string.error_timeout
    AddServerErrorType.UNKNOWN_HOST -> R.string.error_unknown_host
    AddServerErrorType.UNSUPPORTED_VERSION -> R.string.error_unsupported_version
    AddServerErrorType.SERVER_ERROR -> R.string.error_server
}

/**
 * The extra help a class needs: the exact commands for the localhost case, and the re-pair path for
 * a rejected credential. `null` when the message alone is enough.
 */
@StringRes
fun AddServerErrorType.helpRes(): Int? = when (this) {
    AddServerErrorType.UNREACHABLE -> R.string.help_unreachable
    AddServerErrorType.PAIRING_CODE_REJECTED -> R.string.help_pairing_code_rejected
    AddServerErrorType.UNAUTHORIZED -> R.string.help_unauthorized
    else -> null
}

@StringRes
fun ServerHealth.labelRes(): Int = when (this) {
    ServerHealth.CONNECTED -> R.string.health_connected
    ServerHealth.CONNECTING -> R.string.health_connecting
    ServerHealth.DISCONNECTED -> R.string.health_disconnected
    ServerHealth.OFFLINE -> R.string.health_offline
    ServerHealth.REAUTH_REQUIRED -> R.string.health_reauth_required
}

/** The status line on the server status screen, from the live connection state. */
@StringRes
fun ConnectionState.labelRes(): Int = when (this) {
    is ConnectionState.Idle -> R.string.state_idle
    is ConnectionState.Connecting -> R.string.state_connecting
    is ConnectionState.Connected -> R.string.state_connected
    is ConnectionState.Suspended -> R.string.state_suspended
    is ConnectionState.Disconnected -> when {
        cause == DisconnectCause.AUTHORIZATION_REQUIRED -> R.string.state_reauth_required
        willRetry -> R.string.state_retrying
        else -> R.string.state_disconnected
    }
}
