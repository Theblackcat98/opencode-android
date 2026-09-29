package dev.opencode.android.feature.execution

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The host side of the page's channel, and the WebView's hardening (plan §5.2).
 *
 * **A WebView cannot be exercised on a JVM, so the two things that matter are tested apart from it.**
 * What the host *evaluates in the page* is observable without a browser and is where the injection risk
 * lives; what the WebView is *configured to allow* is observable through `WebSettings` under Robolectric
 * and is where the network and file-access risk lives. Together they are the claim; a screenshot of a
 * WebView would be a blank rectangle and prove neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalChannelTest {

    @Test
    fun `output is buffered until the page is ready and then flushed once, in order`() = runTest {
        val channel = TerminalChannel()
        channel.written.test {
            channel.write("first ")
            channel.write("second")
            // A WebView loads its assets asynchronously; a socket that starts producing before
            // `index.html` has run would otherwise lose the banner, the prompt and everything since.
            expectNoEvents()

            channel.onPageReady()
            assertEquals("""{"type":"output","data":"first second"}""", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the channel writes straight through once the page is ready`() = runTest {
        val channel = TerminalChannel()
        channel.written.test {
            channel.onPageReady()
            channel.write("a")
            channel.write("b")
            assertEquals("""{"type":"output","data":"a"}""", awaitItem())
            assertEquals("""{"type":"output","data":"b"}""", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty write is not a write`() = runTest {
        val channel = TerminalChannel()
        channel.written.test {
            channel.onPageReady()
            channel.write("")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `detaching drops what was buffered, because the page it was for is gone`() = runTest {
        val channel = TerminalChannel()
        channel.written.test {
            channel.write("replay")
            channel.detach()
            channel.onPageReady()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the cursor and the state reach the page`() = runTest {
        val channel = TerminalChannel()
        channel.written.test {
            channel.onPageReady()
            channel.publishCursor(4_096)
            channel.publishState("reconnecting", 4_096)
            assertEquals("""{"type":"cursor","cursor":4096}""", awaitItem())
            assertEquals("""{"type":"state","state":"reconnecting","cursor":4096}""", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `output carrying quotes and a backslash is still one JSON literal`() = runTest {
        // This is the reason a host message is a JSON object and not a concatenation: the payload is
        // terminal output, and the page's parser must not be able to decide where it ends.
        val channel = TerminalChannel()
        channel.written.test {
            channel.onPageReady()
            channel.write("""he said "hi" \ then left""")
            val written = awaitItem()
            assertEquals(true, written.startsWith("""{"type":"output","data":""""))
            assertEquals(true, written.endsWith("""}"""))
            assertEquals(-1, written.indexOf("\";"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the WebView has javascript and nothing else it could reach with it`() {
        // A real WebView's own settings, hardened by the function the composable calls. Creating a
        // `WebSettings` directly is impossible — it is abstract — which is also why the hardening takes
        // the settings a WebView already has rather than building a fresh object to configure.
        val settings = harden(android.webkit.WebView(org.robolectric.RuntimeEnvironment.getApplication()).settings)

        // The one permissive setting, and it is there because the plan asks for xterm.js.
        assertEquals(true, settings.javaScriptEnabled)
        assertEquals(false, settings.allowFileAccess)
        assertEquals(false, settings.allowContentAccess)
        // Network loads are blocked outright. `file:///android_asset` is not a network load, so the
        // page still gets its four files, and the WebView cannot open a socket even if the navigation
        // client were wrong.
        assertEquals(true, settings.blockNetworkLoads)
        assertEquals(true, settings.blockNetworkImage)
        assertEquals(false, settings.domStorageEnabled)
        assertEquals(false, settings.databaseEnabled)
        assertEquals(false, settings.javaScriptCanOpenWindowsAutomatically)
        assertEquals(false, settings.supportMultipleWindows())
        assertEquals(true, settings.mediaPlaybackRequiresUserGesture)
        // `setGeolocationEnabled(false)` is a deprecated setter with no getter in the public SDK, so
        // the call is asserted by compiling and by the fact that `harden` is the only place it happens.
        assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
        assertEquals(android.webkit.WebSettings.LOAD_NO_CACHE, settings.cacheMode)
        // The two that decide whether a page can read another local file, asserted here because they
        // are the settings a real WebView inherits as *enabled* and they are deprecated away.
        @Suppress("DEPRECATION")
        assertEquals(false, settings.allowFileAccessFromFileURLs)
        @Suppress("DEPRECATION")
        assertEquals(false, settings.allowUniversalAccessFromFileURLs)
        // Zoom is off at the WebView level and the page does its own pinch as a font-size change, so a
        // pinch cannot cut a line off the right-hand side.
        assertEquals(false, settings.supportZoom())
        assertEquals(false, settings.displayZoomControls)
        assertEquals(false, settings.builtInZoomControls)
    }

    @Test
    fun `hardening is not a no-op on a WebView that starts permissive`() {
        val settings = android.webkit.WebView(androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()).settings
        // Everything this test asserts is false by default in a Robolectric WebView, so hardening a
        // default one would pass without `harden` running at all. Turning the permissive settings on
        // first is what makes the call observable.
        @Suppress("DEPRECATION")
        run {
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.blockNetworkLoads = false
            harden(settings)
            assertEquals(false, settings.allowFileAccess)
            assertEquals(false, settings.allowContentAccess)
            assertEquals(false, settings.allowFileAccessFromFileURLs)
            assertEquals(false, settings.allowUniversalAccessFromFileURLs)
            assertEquals(false, settings.domStorageEnabled)
            assertEquals(false, settings.databaseEnabled)
            assertEquals(false, settings.javaScriptCanOpenWindowsAutomatically)
            assertEquals(true, settings.blockNetworkLoads)
            assertEquals(true, settings.javaScriptEnabled)
        }
    }

    @Test
    fun `the navigation client allows the app's own four files and refuses everything else`() {
        val allowed = LockedNavigationClient.ALLOWED_PATHS
        assertEquals(4, allowed.size)
        assertEquals(true, "/android_asset/terminal/index.html" in allowed)
        assertEquals(true, "/android_asset/terminal/xterm.js" in allowed)
        assertEquals(true, "/android_asset/terminal/xterm.css" in allowed)
        assertEquals(true, "/android_asset/terminal/addon-fit.js" in allowed)
        assertEquals(false, "https://example.com/x.js" in allowed)
    }
}
