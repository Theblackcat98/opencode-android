package dev.opencode.android.feature.execution

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
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
class TerminalChannel {

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

    fun detach() {
        webView = null
        ready = false
        buffer.setLength(0)
    }

    /** Called when the page has mounted its terminal; flushes whatever arrived first. */
    fun onPageReady() {
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

    private fun evaluate(method: String, message: TerminalHostMessage) {
        val json = TerminalBridgeCodec.encode(message)
        _written.tryEmit(json)
        webView?.evaluateJavascript("window.__terminal && window.__terminal.$method($json);", null)
    }

    private fun trim() {
        if (buffer.length <= BUFFER_LIMIT) return
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
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalWebView(
    channel: TerminalChannel,
    onMessage: (TerminalBridgeMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bridge = remember { TerminalBridge(onMessage) }

    AndroidView(
        modifier = modifier.testTag(TerminalTags.VIEW),
        factory = { viewContext ->
            WebView(viewContext).apply {
                harden(settings)
                webViewClient = LockedNavigationClient()
                addJavascriptInterface(bridge, TerminalBridgeCodec.INTERFACE_NAME)
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                loadUrl(LockedNavigationClient.PAGE_URL)
            }.also { channel.attach(it) }
        },
    )

    DisposableEffect(context, channel) {
        onDispose { channel.detach() }
    }
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
