package dev.opencode.android.feature.execution

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import dev.opencode.android.core.data.terminal.TerminalBridgeCodec
import dev.opencode.android.core.data.terminal.TerminalBridgeMessage
import dev.opencode.android.core.data.terminal.TerminalHostMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayInputStream

/**
 * The JavaScript bridge the terminal page talks through (plan §5.2).
 *
 * **Every method here validates before it speaks.** [post] is the page's only entry point, it decodes
 * with [TerminalBridgeCodec] — a pure function whose four rules are tested — and it publishes a *typed*
 * message. Nothing downstream ever sees a string from the page, and a message the codec refuses is
 * counted rather than acted on.
 *
 * **It is annotated [JavascriptInterface] and that is the whole reason the codec is strict.** Any
 * method on an object added with `addJavascriptInterface` is reachable from the page, so the surface
 * is one method taking one parameter, and the parameter is not trusted.
 */
class TerminalBridge(private val onMessage: (TerminalBridgeMessage) -> Unit = {}) {

    private val _messages = MutableSharedFlow<TerminalBridgeMessage>(
        replay = 0,
        extraBufferCapacity = BRIDGE_BUFFER,
    )

    /** The validated messages, for a caller that would rather collect than be called back. */
    val messages: SharedFlow<TerminalBridgeMessage> = _messages.asSharedFlow()

    /**
     * The page's one method.
     *
     * `null`, an empty string, a JSON scalar, a message of an unknown kind, an over-long payload and an
     * out-of-range grid are all refused by [TerminalBridgeCodec.decode]; this method only has to hand it
     * over and count the refusals.
     */
    @JavascriptInterface
    fun post(raw: String?) {
        val decoded = TerminalBridgeCodec.decode(raw)
        if (decoded == null) {
            TerminalBridgeCodec.noteRefused()
            return
        }
        _messages.tryEmit(decoded)
        onMessage(decoded)
    }

    private companion object {
        /** A page that posts faster than the host consumes is a page that is not ours. */
        const val BRIDGE_BUFFER = 64
    }
}

/**
 * The host's side of the channel: what goes *into* the page.
 *
 * **Output is buffered until the page says it is ready.** A WebView loads its assets asynchronously, so
 * a socket that starts producing before `index.html` has run would otherwise lose the first — and for a
 * terminal that is the banner, the prompt and everything typed since. The buffer is flushed on `ready`
 * and in order, because a terminal whose replay arrives out of order is worse than one that is late.
 *
 * **The payload is a JSON literal, never a concatenation.** The text being written is terminal output,
 * which contains quotes, backslashes, newlines and — in a `grep --color` result — escape sequences;
 * building the call by hand would let the page's parser decide where the string ends, which is a
 * code-injection route through the terminal's own output. [TerminalBridgeCodec.encode] produces the
 * JSON object and the whole call is one string literal, so nothing inside it can escape.
 *
 * [written] publishes everything that went out, which is what a test asserts: a JVM test cannot run a
 * WebView, but it can assert exactly what would have been evaluated.
 */
open class TerminalChannel {

    private val _written = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = WRITE_BUFFER)

    /** Every string this channel evaluated in the page, in order. */
    val written: SharedFlow<String> = _written.asSharedFlow()

    private var webView: WebView? = null
    private var ready = false
    private val buffer = StringBuilder()

    fun attach(view: WebView) {
        webView = view
        ready = false
    }

    /**
     * Lets go of the page, and of what was held for it.
     *
     * **[view] names the page being released, and a page that is no longer the channel's is ignored.** A
     * terminal that is given a fresh page (a reconnect, another terminal) attaches the new `WebView`
     * while Compose is still releasing the old one, and the release arrives *after* the attach. Detaching
     * unconditionally would then detach the page that had just been attached, and the new terminal would
     * be written into nothing.
     */
    fun detach(view: WebView? = null) {
        if (view != null && webView !== view) return
        webView = null
        ready = false
        buffer.setLength(0)
    }

    /** Called when the page has mounted its terminal; flushes whatever arrived first. */
    open fun onPageReady() {
        ready = true
        flush()
    }

    /** Queues terminal output. */
    fun write(text: String) {
        if (text.isEmpty()) return
        if (!ready) {
            buffer.append(text)
            trim()
            return
        }
        evaluate("write", TerminalHostMessage.Output(text))
    }

    /**
     * Empties the page's screen, because what is about to be written starts from the beginning.
     *
     * A new socket replays the server's whole retained buffer, so writing it onto a screen that already
     * shows the terminal would draw every line twice. A page that is not ready yet is empty by
     * construction; all that is left to drop is what was queued for it.
     */
    fun reset() {
        buffer.setLength(0)
        if (ready) evaluate("reset", TerminalHostMessage.Reset)
    }

    /** Publishes the server's cursor, so a reconnect can resume from it. */
    fun publishCursor(cursor: Long) {
        evaluate("cursor", TerminalHostMessage.Cursor(cursor))
    }

    /** Tells the page what the stream is doing, so it can show a "reconnecting" of its own. */
    fun publishState(state: String, cursor: Long?) {
        evaluate("state", TerminalHostMessage.State(state, cursor))
    }

    private fun flush() {
        if (buffer.isEmpty()) return
        val chunk = buffer.toString()
        buffer.setLength(0)
        evaluate("write", TerminalHostMessage.Output(chunk))
    }

    /**
     * Evaluates one host message in the page.
     *
     * **The method name travels as a string, and the JSON travels with it.** The call is assembled as
     * `window.__terminal.<method>(<json>)` with the method named by the channel itself, never by the
     * page, and the JSON produced by [TerminalBridgeCodec.encode] — so the text being written is a
     * string literal the page's own parser cannot be talked out of. A `$method` interpolated into the
     * payload would be the injection route, and there is none: the only variable in this string is the
     * method this file wrote.
     */
    private fun evaluate(method: String, message: TerminalHostMessage) {
        val json = TerminalBridgeCodec.encode(message)
        _written.tryEmit(json)
        evaluateInPage(method, json)
    }

    /**
     * The one call that needs a `WebView`, open so a test can observe what would be evaluated.
     *
     * **Open for one reason, and it is a narrow one.** A `WebView` cannot be constructed or evaluated in
     * a JVM test at all — there is no renderer, so a screenshot of one is an empty rectangle and a
     * `WebView` in a test is a mock. Overriding this single method lets a test substitute a list for the
     * page while everything that decides *what* is evaluated and in what order stays the production
     * code's. `TerminalSurfaceTest` is that test.
     *
     * [method] and [json] are separate parameters rather than one assembled string so the assembly
     * happens here, once, where it can be read — and so a test observes the JSON rather than having to
     * parse the call out of a statement. Neither value is ever concatenated into anything the page
     * evaluates other than as a call and a literal.
     */
    protected open fun evaluateInPage(method: String, json: String) {
        webView?.evaluateJavascript("window.__terminal && window.__terminal.$method($json);", null)
    }

    private fun trim() {
        if (buffer.length <= BUFFER_LIMIT) return
        // Exactly [BUFFER_LIMIT] and no more: the last write may have pushed it past by up to its own
        // length, and a bound that leaves the overshoot in place is a bound that drifts by a chunk.
        buffer.delete(0, buffer.length - BUFFER_LIMIT)
    }

    private companion object {
        /**
         * How much output is held before the page is ready: 256 KiB of characters.
         *
         * A page that is mounted takes milliseconds; a buffer larger than this is a page that never
         * will be, and holding more of it would trade a phone's memory for output nobody will read.
         */
        const val BUFFER_LIMIT = 256 * 1024

        const val WRITE_BUFFER = 32
    }
}

/**
 * The hardened WebView the terminal lives in (plan §5.2, "Harden the WebView").
 *
 * **`@SuppressLint("JavascriptInterface")` is a false positive being silenced on purpose.** The check
 * wants a method on the added object to carry `@JavascriptInterface`, and [TerminalBridge.post] does —
 * it is annotated, and `TerminalSurfaceTest` asserts that every kind of message the page posts decodes.
 * Lint misses it because the annotation is on a Kotlin method whose parameter is nullable and whose
 * class also holds a `SharedFlow`; the suppression is on this composable rather than on the bridge so
 * that removing the annotation from `post` still shows up as a lint error in the file that owns it.
 *
 * **Local assets only, no file access, and one bridge.** The page is loaded from
 * `file:///android_asset/terminal/index.html` and [LockedNavigationClient] refuses every request that is
 * not one of the four files this app ships; `blockNetworkLoads` is on, so even a bug in that client
 * could not reach the network, and the credential the WebSocket needs never enters the page at all —
 * OkHttp holds the socket and the page is handed output.
 *
 * **`javaScriptEnabled = true` is the one permissive setting, and it is unavoidable**: the plan asks for
 * xterm.js, which is JavaScript. Everything else that JavaScript could reach is closed —
 *
 *  - `allowFileAccess = false` and `allowContentAccess = false`: no `file://` and no `content://`.
 *  - `allowFileAccessFromFileURLs = false` and `allowUniversalAccessFromFileURLs = false`: the page
 *    cannot read another local file even if it got a reference to one.
 *  - `domStorageEnabled = false` and `databaseEnabled = false`: nothing is persisted in the page, so
 *    there is nothing left behind after the terminal closes.
 *  - `javaScriptCanOpenWindowsAutomatically = false` and `setSupportMultipleWindows(false)`: a
 *    `window.open` goes nowhere.
 *  - `mediaPlaybackRequiresUserGesture = true`, `setGeolocationEnabled(false)`.
 *  - `mixedContentMode = NEVER_ALLOW`, `LOAD_NO_CACHE`.
 *
 * A Roborazzi screenshot of a WebView photographs nothing, which is why the terminal's *chrome* is a
 * separate composable the baselines draw — P6 learned that a screenshot of a sheet is an empty rectangle
 * for the same reason.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun TerminalWebView(
    channel: TerminalChannel,
    onMessage: (TerminalBridgeMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    // **The page's messages arrive on the WebView's own `JavaBridge` thread, and everything they reach
    // belongs to the main one.** The channel's buffer, the view model's state and `evaluateJavascript`
    // are all main-thread; a `ready` handled where it arrived flushed the channel and rewrote the state
    // from a second thread, racing the socket's collector for the same `StateFlow`.
    val latest by rememberUpdatedState(onMessage)
    val bridge = remember { bridgeOnMain { latest } }

    AndroidView(
        modifier = modifier.testTag(TerminalTags.VIEW),
        factory = { viewContext -> terminalWebView(viewContext, bridge, channel) },
        onRelease = { view ->
            channel.detach(view)
            view.destroy()
        },
    )
}

/**
 * A bridge whose messages are handled on the main thread, whichever thread the page posted them on.
 *
 * `@JavascriptInterface` methods run on the WebView's own thread. [latest] is read when the message is
 * handled rather than when it arrived, so a recomposition that replaced the callback is not bypassed.
 */
internal fun bridgeOnMain(latest: () -> (TerminalBridgeMessage) -> Unit): TerminalBridge {
    val main = Handler(Looper.getMainLooper())
    return TerminalBridge { message -> main.post { latest()(message) } }
}

/**
 * The `WebView` the terminal page runs in, hardened and attached to [channel].
 *
 * **It is told to fill its parent, and that is not decoration.** A `WebView` that is added to a view
 * group without layout parameters gets `WRAP_CONTENT`, which is what Compose's `AndroidView` does to
 * anything a factory returns. Chromium reads that: a `WebView` whose height is `WRAP_CONTENT` is one
 * whose *content* decides its height, so the page's layout viewport is given a height of **zero** no
 * matter how tall the view is measured — `window.innerHeight` says 506 while `100%`, `100vh` and
 * `matchMedia("(min-height: 400px)")` all say 0. The terminal page sizes itself with `height: 100%`,
 * so xterm's fit addon measured a container of no height, asked for a grid of one row, and the one row
 * it had was clipped away: a terminal that was connected, resized to `52x1` and drew nothing.
 * `MATCH_PARENT` in both directions is what makes the layout viewport the view's own size.
 *
 * Extracted so a test can assert what the composed view is given rather than photograph a `WebView`.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
internal fun terminalWebView(context: Context, bridge: TerminalBridge, channel: TerminalChannel): WebView =
    WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        harden(settings)
        webViewClient = LockedNavigationClient()
        addJavascriptInterface(bridge, TerminalBridgeCodec.INTERFACE_NAME)
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = WebView.OVER_SCROLL_NEVER
        // Attached before the page can say `ready`, so a `ready` that beats the end of this function
        // cannot be the one the channel then forgets.
        channel.attach(this)
        loadUrl(LockedNavigationClient.PAGE_URL)
    }

/**
 * Applies every restriction the terminal's WebView needs, in one place so the hardening is auditable.
 *
 * **It takes the settings a WebView already has rather than a context.** `WebSettings` is abstract, so
 * a `WebSettings(context)` would not compile; more usefully, hardening a fresh object rather than a
 * real WebView's is the way a test ends up asserting on something the app never uses.
 */
@SuppressLint("SetJavaScriptEnabled")
fun harden(settings: WebSettings): WebSettings = settings.apply {
    javaScriptEnabled = true
    allowFileAccess = false
    allowContentAccess = false
    @Suppress("DEPRECATION")
    allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    allowUniversalAccessFromFileURLs = false
    blockNetworkLoads = true
    blockNetworkImage = true
    domStorageEnabled = false
    databaseEnabled = false
    javaScriptCanOpenWindowsAutomatically = false
    setSupportMultipleWindows(false)
    mediaPlaybackRequiresUserGesture = true
    setGeolocationEnabled(false)
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    cacheMode = WebSettings.LOAD_NO_CACHE
    setBuiltInZoomControls(false)
    setDisplayZoomControls(false)
    setSupportZoom(false)
    loadsImagesAutomatically = false
    useWideViewPort = false
    loadWithOverviewMode = false
}

/**
 * A `WebViewClient` that answers the app's own four files and refuses everything else.
 *
 * **Refused means "not fetched", not "fetched and hidden".** Returning `null` would let the request
 * continue; this returns an empty response for anything outside the allow-list, so a page that asked
 * for `https://example.com` gets nothing at all. `blockNetworkLoads` is the belt to this braces: even if
 * this class were wrong, the WebView could not open a socket.
 */
class LockedNavigationClient(
    private val allowed: Set<String> = ALLOWED_PATHS,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? = if (request.url.path in allowed) null else empty()

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.path !in allowed

    private fun empty() = WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        /** The four files the page is made of, and nothing else. */
        val ALLOWED_PATHS: Set<String> = setOf(
            "/android_asset/terminal/index.html",
            "/android_asset/terminal/xterm.js",
            "/android_asset/terminal/xterm.css",
            "/android_asset/terminal/addon-fit.js",
        )

        /** The page's own address. */
        const val PAGE_URL: String = "file:///android_asset/terminal/index.html"
    }
}
