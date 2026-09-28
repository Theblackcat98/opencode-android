package dev.opencode.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import dev.opencode.android.core.designsystem.theme.OpenCodeTheme
import dev.opencode.android.ui.OpenCodeApp

/**
 * The single activity.
 *
 * A pairing link arrives two ways: as a deep link into `…/auth/connect/<code>`, or as shared text.
 * Both are reduced to one payload, which the add-server screen treats identically, because from
 * there they are the same thing: a string to pair with.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var sharedPayload by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        sharedPayload = sharedPayloadOf(intent)

        setContent {
            OpenCodeTheme {
                OpenCodeApp(sharedPayload = sharedPayload)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedPayload = sharedPayloadOf(intent)
    }

    private fun sharedPayloadOf(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        else -> null
    }?.takeIf { it.isNotBlank() }
}
