package dev.opencode.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SseParserTest {

    private lateinit var parser: SseParser

    @Before
    fun setUp() {
        parser = SseParser()
    }

    @Test
    fun parsesSimpleDataFrame() {
        assertNull(parser.parseLine("data: {\"type\":\"server.connected\"}"))
        val message = parser.parseLine("")
        assertTrue(message is SseMessage.Data)
        assertEquals("{\"type\":\"server.connected\"}", (message as SseMessage.Data).payload)
    }

    @Test
    fun parsesMultiLineDataFrameJoinedByNewlines() {
        assertNull(parser.parseLine("data: {\"line1\": 1,"))
        assertNull(parser.parseLine("data:  \"line2\": 2}"))
        val message = parser.parseLine("")
        assertTrue(message is SseMessage.Data)
        assertEquals("{\"line1\": 1,\n \"line2\": 2}", (message as SseMessage.Data).payload)
    }

    @Test
    fun parsesHeartbeatComment() {
        val message = parser.parseLine(": heartbeat")
        assertEquals(SseMessage.Heartbeat, message)
    }

    @Test
    fun parsesHeartbeatWithoutSpace() {
        val message = parser.parseLine(":heartbeat")
        assertEquals(SseMessage.Heartbeat, message)
    }

    @Test
    fun parsesHeartbeatCaseInsensitive() {
        val message = parser.parseLine(": Heartbeat")
        assertEquals(SseMessage.Heartbeat, message)
    }

    @Test
    fun parsesGenericComment() {
        val message = parser.parseLine(": some other comment")
        assertTrue(message is SseMessage.Comment)
        assertEquals("some other comment", (message as SseMessage.Comment).text)
    }

    @Test
    fun ignoresEmptyLinesWhenBufferIsEmpty() {
        assertNull(parser.parseLine(""))
        assertNull(parser.parseLine(""))
    }

    @Test
    fun parsesMultipleEventsSequentially() {
        assertNull(parser.parseLine("data: first"))
        val msg1 = parser.parseLine("")
        assertEquals(SseMessage.Data("first"), msg1)

        val hb = parser.parseLine(": heartbeat")
        assertEquals(SseMessage.Heartbeat, hb)

        assertNull(parser.parseLine("data: second"))
        val msg2 = parser.parseLine("")
        assertEquals(SseMessage.Data("second"), msg2)
    }
}
