package dev.opencode.android.feature.execution

import dev.opencode.android.core.data.terminal.TerminalBridgeCodec
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The page and the channel agree on what a host message looks like.
 *
 * **The bug this file exists for.** The channel sent `window.__terminal.write({"type":"output","data":"…"})`
 * — the object [TerminalBridgeCodec] encodes — and the page's `write` took a string, so `typeof text ===
 * "string"` was false for every chunk of output and the page ignored all of it without a word. Both halves
 * had tests, and both sets passed: the channel's asserted the object, and nothing asserted the page's.
 *
 * **This reads the page as text, and that is a limit worth stating.** A WebView cannot run in this
 * environment and there is no JavaScript engine on the JVM, so nothing here *executes* `index.html`. What it
 * can do is make the two halves derive from the same thing: the methods and the fields are taken from what
 * the channel really evaluates, and the page is required to name each of them. It would have failed on the
 * page that shipped; it cannot prove the page draws correctly — the emulator run recorded in the changelog
 * is what did that.
 */
class TerminalPageContractTest {

    private val page: String = File("src/main/assets/terminal/index.html").also {
        assertTrue("the page is read from ${it.absolutePath}", it.isFile)
    }.readText()

    /** Every call the channel makes into the page, as the method it names and the JSON it passes. */
    private fun evaluations(): List<Pair<String, String>> {
        val calls = mutableListOf<Pair<String, String>>()
        val channel = object : TerminalChannel() {
            override fun evaluateInPage(method: String, json: String) {
                calls += method to json
            }
        }
        channel.onPageReady()
        channel.write("output")
        channel.publishCursor(12)
        channel.publishState("live", 12)
        channel.reset()
        return calls
    }

    @Test
    fun `the channel makes the four calls this contract is derived from`() {
        assertEquals(listOf("write", "cursor", "state", "reset"), evaluations().map { it.first })
    }

    @Test
    fun `the page has a handler for every method the channel calls`() {
        evaluations().forEach { (method, _) ->
            assertTrue("window.__terminal has no `$method`", page.contains("$method: function"))
        }
    }

    @Test
    fun `the page reads every field the channel puts in a message`() {
        evaluations().forEach { (method, json) ->
            val message = OpenCodeJson.parseToJsonElement(json) as JsonObject
            message.keys.filter { it != "type" }.forEach { field ->
                assertTrue(
                    "`$method` is sent {\"$field\": …} and the page never reads `message.$field`: $json",
                    page.contains("message.$field"),
                )
            }
        }
    }

    @Test
    fun `the page does not send the host back the messages the host sent it`() {
        // `cursor` and `state` are the host's to keep and the page's to show. Echoing them posted a message
        // of a kind the codec refuses, on every state change, and counted each as a refusal.
        assertFalse(page.contains("""post({ type: "cursor""""))
        assertFalse(page.contains("""post({ type: "state""""))
    }

    @Test
    fun `the page reaches the host through the interface the host installs`() {
        assertTrue(page.contains("window.${TerminalBridgeCodec.INTERFACE_NAME}"))
    }
}
