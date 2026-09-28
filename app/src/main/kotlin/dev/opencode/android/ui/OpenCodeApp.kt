package dev.opencode.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.opencode.android.navigation.HomeRoute

/** The navigation host. P0 ships the empty shell; P1 adds onboarding and the server registry. */
@Composable
fun OpenCodeApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeRoute, modifier = modifier) {
        composable<HomeRoute> {
            HomeScreen()
        }
    }
}
