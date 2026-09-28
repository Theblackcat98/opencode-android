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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var initialUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        initialUrl = extractInitialUrl(intent)

        setContent {
            OpenCodeTheme {
                OpenCodeApp(initialUrl = initialUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newUrl = extractInitialUrl(intent)
        if (!newUrl.isNullOrBlank()) {
            initialUrl = newUrl
        }
    }

    private fun extractInitialUrl(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
    }
}
