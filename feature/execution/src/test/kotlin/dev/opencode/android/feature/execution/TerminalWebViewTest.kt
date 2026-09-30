package dev.opencode.android.feature.execution

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dev.opencode.android.core.data.terminal.TerminalBridgeMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the terminal's `WebView` is given, and how the channel behaves when the page it holds changes.
 *
 * **A WebView cannot draw here, but it can be asked what it was handed.** The terminal opened, said "Live",
 * and drew nothing, because the view Compose put on screen was `WRAP_CONTENT` and Chromium then gives the
 * page a layout viewport of no height: the page measured a terminal of one row and clipped it. The pixels
 * cannot be photographed on a JVM; the layout parameters that caused it can be read, and are.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xhdpi")
class TerminalWebViewTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------------------ what the view is told

    @Test
    fun `the WebView Compose puts on screen is told to fill its parent, not to wrap its content`() {
        var root: View? = null
        compose.setContent {
            root = LocalView.current
            TerminalWebView(channel = TerminalChannel(), onMessage = {}, modifier = Modifier)
        }
        compose.waitForIdle()

        val webView = root?.rootView?.findWebView()
        assertNotNull("the composition holds a WebView", webView)
        // `WRAP_CONTENT` is what a view added without layout parameters gets, and what this used to be. On a
        // device Chromium reads it as "the content decides my height" and gives the page a viewport of zero.
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView!!.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView.layoutParams.height)
    }

    @Test
    fun `the factory itself asks for both dimensions to fill`() {
        val view = terminalWebView(context, TerminalBridge(), TerminalChannel())

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.layoutParams.height)
    }

    // ------------------------------------------------------------------ the page changing under the channel

    @Test
    fun `a page that is no longer the channel's cannot detach the page that replaced it`() = runTest {
        val channel = TerminalChannel()
        val old = WebView(context)
        val replacement = WebView(context)
        channel.attach(old)
        // A terminal given a fresh page attaches the new WebView while Compose is still releasing the old
        // one, and the release arrives afterwards.
        channel.attach(replacement)
        channel.write("held for the new page")

        channel.detach(old)

        channel.written.test {
            channel.onPageReady()
            assertEquals("""{"type":"output","data":"held for the new page"}""", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the page the channel holds can detach itself`() = runTest {
        val channel = TerminalChannel()
        val page = WebView(context)
        channel.attach(page)
        channel.write("for a page that is going")

        channel.detach(page)

        channel.written.test {
            channel.onPageReady()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ------------------------------------------------------------------ reset

    @Test
    fun `a reset before the page is ready drops what was queued for it`() = runTest {
        val channel = TerminalChannel()
        channel.written.test {
            channel.write("output of the socket that was replaced")
            channel.reset()
            channel.onPageReady()
            // A page that has not loaded is empty already; only the queue needs emptying.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a reset on a ready page empties it before whatever is written next`() = runTest {
        val channel = TerminalChannel()
        channel.written.test {
            channel.onPageReady()
            channel.write("old screen")
            channel.reset()
            channel.write("replay")
            assertEquals("""{"type":"output","data":"old screen"}""", awaitItem())
            assertEquals("""{"type":"reset"}""", awaitItem())
            assertEquals("""{"type":"output","data":"replay"}""", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ------------------------------------------------------------------ the thread the page speaks on

    @Test
    fun `a message the page posts is handled on the main thread, not the one it arrived on`() {
        val handled = mutableListOf<Pair<Thread, TerminalBridgeMessage>>()
        val bridge = bridgeOnMain { { message -> handled += Thread.currentThread() to message } }

        // `@JavascriptInterface` methods run on the WebView's own thread.
        val webViewThread = Thread { bridge.post("""{"type":"ready"}""") }.apply { start() }
        webViewThread.join()

        assertTrue("nothing was handled on the thread the page posted from", handled.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf<TerminalBridgeMessage>(TerminalBridgeMessage.Ready), handled.map { it.second })
        assertSame(Looper.getMainLooper().thread, handled.single().first)
    }

    @Test
    fun `the callback in force when the message is handled is the one that runs`() {
        var current: (TerminalBridgeMessage) -> Unit = { error("the stale callback ran") }
        val seen = mutableListOf<TerminalBridgeMessage>()
        val bridge = bridgeOnMain { current }

        bridge.post("""{"type":"ready"}""")
        // A recomposition replaces the callback between the post and the main thread getting to it.
        current = { seen += it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf<TerminalBridgeMessage>(TerminalBridgeMessage.Ready), seen)
    }

    private fun View.findWebView(): WebView? {
        if (this is WebView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) getChildAt(index).findWebView()?.let { return it }
        return null
    }
}
