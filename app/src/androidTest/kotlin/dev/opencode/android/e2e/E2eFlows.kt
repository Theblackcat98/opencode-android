package dev.opencode.android.e2e

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import dev.opencode.android.feature.servers.R as ServersR
import dev.opencode.android.feature.servers.ui.AddServerTags
import dev.opencode.android.feature.servers.ui.ServersTags
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A server name that cannot collide with anything else on the device. */
fun uniqueServerName(prefix: String = "e2e"): String =
    "$prefix-${UUID.randomUUID().toString().take(8)}"

fun targetString(@StringRes id: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

/** Matches nodes whose test tag starts with [prefix] (server items are tagged `server_item_<id>`). */
fun hasTestTagPrefix(prefix: String): SemanticsMatcher =
    SemanticsMatcher("testTag starts with '$prefix'") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

fun ComposeTestRule.closeKeyboard() {
    Espresso.closeSoftKeyboard()
}

/**
 * Mints a single-use pairing code with `POST /api/pair`, the route the CLI's `opencode pair`
 * also uses. Mirrors `DevServerHarness.createPairingCode` (a JVM-test helper that the
 * instrumentation APK cannot depend on).
 */
fun mintPairingCode(baseUrl: String, password: String): String {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder()
        .url("${baseUrl.trimEnd('/')}/api/pair")
        .header("Authorization", Credentials.basic("opencode", password))
        .post("{}".toRequestBody("application/json".toMediaType()))
        .build()
    client.newCall(request).execute().use { response ->
        check(response.code == 200) { "POST /api/pair failed with HTTP ${response.code}" }
        val body = response.body.string()
        return Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: error("No pairing code in POST /api/pair response: $body")
    }
}

/** Waits until the add-server screen has popped back to the servers list. */
fun ComposeTestRule.waitForAddScreenGone(timeoutMillis: Long = 30_000) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithTag(AddServerTags.SCREEN).fetchSemanticsNodes().isEmpty()
    }
}

/** Waits until at least one server row is rendered in the list. */
fun ComposeTestRule.waitForServerListNotEmpty(timeoutMillis: Long = 30_000) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodes(hasTestTagPrefix("server_item_")).fetchSemanticsNodes().isNotEmpty()
    }
}

/**
 * Adds a server through the manual tab (matrix B2) and asserts it lands on the servers list.
 * The app navigates back on success; the caller's server name is then visible in the list.
 */
fun ComposeTestRule.addServerManually(name: String, url: String, password: String) {
    onNodeWithTag(ServersTags.ADD_BUTTON).performClick()
    onNodeWithTag(AddServerTags.SCREEN).assertIsDisplayed()
    onNodeWithTag("tab_manual").performClick()
    onNodeWithTag(AddServerTags.MANUAL_URL_INPUT).performScrollTo().performTextInput(url)
    closeKeyboard()
    onNodeWithTag(AddServerTags.MANUAL_NAME_INPUT).performScrollTo().performTextInput(name)
    closeKeyboard()
    onNodeWithTag(AddServerTags.MANUAL_PASSWORD_INPUT).performScrollTo().performTextInput(password)
    closeKeyboard()
    onNodeWithTag(AddServerTags.MANUAL_CONNECT_BUTTON).performScrollTo().performClick()
    waitForAddScreenGone()
    onNodeWithText(name).assertIsDisplayed()
}

/**
 * Deletes the named server through its overflow menu (matrix B11) and asserts it is gone.
 * Assumes the servers list holds exactly this one server (true after [ClearDataRule]).
 */
fun ComposeTestRule.deleteServer(name: String) {
    onNodeWithText(name).assertIsDisplayed()
    onNodeWithContentDescription(targetString(ServersR.string.servers_options)).performClick()
    onNodeWithText(targetString(ServersR.string.servers_delete)).performClick()
    // The confirm dialog. Its confirm button shares its text with the menu item, which is
    // gone by the time the dialog is up — assert the dialog first, then tap.
    onNodeWithText(targetString(ServersR.string.servers_delete_confirm_title)).assertIsDisplayed()
    onNodeWithText(targetString(ServersR.string.servers_delete)).performClick()
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText(name).fetchSemanticsNodes().isEmpty()
    }
}

/** The first (only) server row in the list, for navigation into it. */
fun ComposeTestRule.firstServerItem(): SemanticsNodeInteractionCollection =
    onAllNodes(hasTestTagPrefix("server_item_"))
