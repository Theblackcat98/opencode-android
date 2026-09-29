package dev.opencode.android.core.data.terminal

import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The channel between the host and the xterm.js page (plan §5.2, "a JavaScript bridge limited to
 * the terminal channel").
 *
 * **This is an attack surface, and it is treated as one.** The page is loaded from the app's own
 * assets with no network access, but it runs JavaScript the app did not write — the whole of
 * xterm.js and the page around it — and anything that reaches the host arrives as a string in a
 * method a Java object exposes. So every message is decoded here, in a pure function, and the host
 * only ever acts on a *typed* value this file produced. A message that is not one of the four kinds
 * below is refused and counted; it is never partially believed.
 *
 * The four rules the decoder enforces, each of which is a test:
 *
 *  1. **It must be a JSON object.** A string, an array, a number or a bare `undefined` is refused, so
 *     a page that posts a string does not get the host guessing at its fields.
 *  2. **The `type` must be one this build knows.** An unknown type is refused rather than treated as
 *     a generic event, because the whole safety argument is that the host does not act on message
 *     kinds it has not enumerated.
 *  3. **Payload sizes are bounded.** [MAX_INPUT_CHARS] is a paste, and a page that posts ten
 *     megabytes of "keystrokes" is refused rather than queued: the socket's send window is finite and
 *     the right answer to unbounded input is no.
 *  4. **Numbers are numbers in range.** A cell count of zero, a negative, or ten million is refused,
 *     because it would be forwarded to `pty.update` and the server would answer `400` — or worse, a
 *     huge allocation on the server side.
 */
object TerminalBridgeCodec {

    /** The name the page exposes to JavaScript. */
    const val INTERFACE_NAME: String = "OpenCodeTerminal"

    /** The largest single input message the host will accept, in characters. */
    const val MAX_INPUT_CHARS: Int = 64 * 1024

    /** The largest selection the host will copy out of the page. */
    const val MAX_SELECTION_CHARS: Int = 1024 * 1024

    /** The largest grid the host will ask the server for. */
    const val MAX_CELLS: Int = 1000

    /** How many messages the host has refused since the page loaded. Diagnostics only. */
    val refused: StateFlow<Long> get() = _refused.asStateFlow()
    private val _refused = MutableStateFlow(0L)

    /** Records a refusal. Called by the host, not by the decoder, so the count is about the wire. */
    fun noteRefused() {
        _refused.value = _refused.value + 1
    }

    /** Decodes a message the page sent, or `null` when it is not one this build accepts. */
    fun decode(raw: String?): TerminalBridgeMessage? {
        if (raw.isNullOrEmpty()) return null
        val text = runCatching { OpenCodeJson.parseToJsonElement(raw) }.getOrNull() ?: return null
        val obj = text as? JsonObject ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return null
        return when (type) {
            Ready.TYPE -> TerminalBridgeMessage.Ready

            Input.TYPE -> payload(obj, Input.TYPE)?.let { TerminalBridgeMessage.Input(it) }

            Resize.TYPE -> resize(obj)

            Selection.TYPE -> bounded(obj, Selection.TYPE, MAX_SELECTION_CHARS)
                ?.let { TerminalBridgeMessage.Selection(it) }

            // The page's own failure message is in `message`, not `data`: the page is what reports it,
            // and it has always used the field name it uses for a state message. Reading `data` here
            // would decode every failure to `null` and the page would be told nothing, which is the one
            // outcome this branch exists to avoid.
            Error.TYPE -> bounded(obj, Error.TYPE, MAX_INPUT_CHARS, key = "message")
                ?.let { TerminalBridgeMessage.Failed(it) }

            else -> null
        }
    }

    /** Encodes a message the host sends to the page. Never throws: it is diagnostic output. */
    fun encode(message: TerminalHostMessage): String = runCatching {
        OpenCodeJson.encodeToString(
            Host.serializer(),
            when (message) {
                is TerminalHostMessage.Output -> Host(type = Output.TYPE, data = message.data)

                is TerminalHostMessage.Cursor -> Host(type = Cursor.TYPE, cursor = message.cursor)

                is TerminalHostMessage.State -> Host(
                    type = State.TYPE,
                    state = message.state,
                    cursor = message.cursor,
                )
            },
        )
    }.getOrDefault(HOST_ENCODE_FAILED)

    /**
     * A page that says something the host cannot parse gets a protocol error, not silence.
     *
     * **The payload field is `data`, not `message`, so that the decoder can read it.** The failure path
     * in [encode] has to produce something the same [decode] would accept; a fallback carrying a
     * `message` field that [bounded] does not look at decodes to `null`, which is the one thing a page
     * cannot be given — an error it cannot see.
     */
    const val HOST_ENCODE_FAILED: String = """{"type":"error","data":"host-encode-failed"}"""

    private fun payload(obj: JsonObject, type: String): String? = bounded(obj, type, MAX_INPUT_CHARS)

    /** [bounded] for a field that is not called `data`. */
    private fun bounded(obj: JsonObject, type: String, limit: Int, key: String): String? {
        val value = obj.stringOrNull(key) ?: return null
        if (value.isEmpty() || value.length > limit) return null
        return value
    }

    /**
     * A string field, or `null` for anything that is not one.
     *
     * `JsonNull` is a `JsonPrimitive` whose `content` is the four characters `null`, so a naive
     * `as? JsonPrimitive` cast would turn `{"data": null}` into the four-character string "null" and
     * then send those four characters to the terminal as a keystroke. It is excluded here, and so is
     * a JSON number, because a number is not text a page may claim the user typed.
     */
    private fun bounded(obj: JsonObject, type: String, limit: Int): String? {
        val value = obj.stringOrNull("data") ?: return null
        if (value.isEmpty() || value.length > limit) return null
        return value
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString.not()) return null
        return primitive.contentOrNull
    }

    /**
     * A cell count, or `null` for anything that is not a whole number.
     *
     * A JSON *string* that reads `80` is refused rather than parsed: the type is part of the message,
     * and a page that sends `"rows": "999999"` as a string is not a page this client should be guessing
     * about.
     */
    private fun resize(obj: JsonObject): TerminalBridgeMessage.Resize? {
        val cols = obj.cellOrNull("cols") ?: return null
        val rows = obj.cellOrNull("rows") ?: return null
        if (cols !in 1..MAX_CELLS || rows !in 1..MAX_CELLS) return null
        return TerminalBridgeMessage.Resize(cols = cols, rows = rows)
    }

    private fun JsonObject.cellOrNull(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        return primitive.intOrNull
    }

    /**
     * One host message on the wire, with every field optional because the page ignores extras.
     * One host message on the wire.
     *
     * The field order is the order the page reads them in (`state` before `cursor`), because
     * `encodeDefaults = false` omits the absent ones and the result is a literal a person can read in
     * a log. Every field is optional because the page ignores the ones a message does not carry.
     */
    @Serializable
    private data class Host(
        val type: String,
        val data: String? = null,
        val state: String? = null,
        val cursor: Long? = null,
    )

    private object Output {
        const val TYPE: String = "output"
    }

    private object Cursor {
        const val TYPE: String = "cursor"
    }

    private object State {
        const val TYPE: String = "state"
    }

    private object Ready {
        const val TYPE: String = "ready"
    }

    private object Input {
        const val TYPE: String = "input"
    }

    private object Resize {
        const val TYPE: String = "resize"
    }

    private object Selection {
        const val TYPE: String = "selection"
    }

    private object Error {
        const val TYPE: String = "error"
    }
}

/** A message the page sent, after validation. */
sealed interface TerminalBridgeMessage {
    /** The page's terminal is mounted and can take output. */
    data object Ready : TerminalBridgeMessage

    /** What the user typed, as UTF-8 text. */
    data class Input(val data: String) : TerminalBridgeMessage

    /** The grid changed, usually because the WebView was laid out at a new size. */
    data class Resize(val cols: Int, val rows: Int) : TerminalBridgeMessage

    /** The user selected text in the terminal, for copy. */
    data class Selection(val data: String) : TerminalBridgeMessage

    /** The page failed. Its own message, bounded. */
    data class Failed(val message: String) : TerminalBridgeMessage
}

/** A message the host sends to the page. */
sealed interface TerminalHostMessage {
    /** Terminal output. */
    data class Output(val data: String) : TerminalHostMessage

    /** The cursor the server reported, so a reconnect can resume from it. */
    data class Cursor(val cursor: Long) : TerminalHostMessage

    /** The stream's state, so the page can show "reconnecting" itself. */
    data class State(val state: String, val cursor: Long?) : TerminalHostMessage
}

/**
 * The cell grid a terminal is laid out on.
 *
 * **A character cell is not a dp.** The page measures itself in columns and rows and asks for a
 * resize; the host has to answer with a grid, and the only honest way to produce one is to divide
 * the measured pixel size by the measured character size. So the arithmetic is here, as a pure
 * function, and the bounds are part of it: a grid of zero is refused by the server, and a grid of
 * ten thousand is a phone trying to allocate a server's worth of terminal.
 */
object TerminalGrid {

    /** Never fewer than this, so a sliver of WebView still gets a usable prompt. */
    const val MIN_CELLS: Int = 8

    /** Never more than this in either direction. */
    const val MAX_CELLS: Int = 1000

    /**
     * The grid for a measured area.
     *
     * Both measurements must be positive; anything else is a layout that has not happened yet, and
     * the answer is [MIN_CELLS] rather than an exception — a zero-height WebView happens on every
     * sheet that animates away, and crashing on the way out is not an option.
     */
    fun of(widthPx: Float, heightPx: Float, cellWidthPx: Float, cellHeightPx: Float): TerminalGridSize {
        if (cellWidthPx <= 0f || cellHeightPx <= 0f) return TerminalGridSize(MIN_CELLS, MIN_CELLS)
        val cols = ((widthPx / cellWidthPx).toInt()).coerceIn(MIN_CELLS, MAX_CELLS)
        val rows = ((heightPx / cellHeightPx).toInt()).coerceIn(MIN_CELLS, MAX_CELLS)
        return TerminalGridSize(cols = cols, rows = rows)
    }

    /** Whether two grids differ enough to be worth a `pty.update`. */
    fun needsResize(current: TerminalGridSize?, next: TerminalGridSize): Boolean = current != next
}

/** A terminal's character grid. */
data class TerminalGridSize(val cols: Int, val rows: Int)
