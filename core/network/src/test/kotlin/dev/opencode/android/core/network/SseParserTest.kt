package dev.opencode.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

    @Test
    fun dispatchesDataOnTheBlankLineThatEndsTheFrame() {
        val parser = SseParser()
        assertNull(parser.parseLine("""data: {"id":"1","type":"server.connected","data":{}}"""))
        val message = parser.parseLine("")
        assertEquals(
            SseMessage.Data("""{"id":"1","type":"server.connected","data":{}}"""),
            message,
        )
    }

    @Test
    fun joinsMultipleDataLinesWithNewlines() {
        val parser = SseParser()
        assertNull(parser.parseLine("data: first"))
        assertNull(parser.parseLine("data: second"))
        assertEquals(SseMessage.Data("first\nsecond"), parser.parseLine(""))
    }

    @Test
    fun stripsOnlyTheFirstSpaceAfterTheColon() {
        val parser = SseParser()
        assertNull(parser.parseLine("data:  two spaces"))
        assertEquals(SseMessage.Data(" two spaces"), parser.parseLine(""))
    }

    @Test
    fun handlesDataWithoutASpaceAfterTheColon() {
        val parser = SseParser()
        assertNull(parser.parseLine("data:tight"))
        assertEquals(SseMessage.Data("tight"), parser.parseLine(""))
    }

    @Test
    fun reportsHeartbeatCommentsImmediately() {
        val parser = SseParser()
        assertEquals(SseMessage.Heartbeat, parser.parseLine(": heartbeat"))
        assertEquals(SseMessage.Heartbeat, parser.parseLine(":Heartbeat"))
        assertEquals(SseMessage.Heartbeat, parser.parseLine(": heartbeat\r"))
    }

    @Test
    fun reportsOtherComments() {
        val parser = SseParser()
        assertEquals(SseMessage.Comment("proxy note"), parser.parseLine(": proxy note"))
    }

    @Test
    fun ignoresEventIdAndRetryFields() {
        val parser = SseParser()
        assertNull(parser.parseLine("event: message"))
        assertNull(parser.parseLine("id: 42"))
        assertNull(parser.parseLine("retry: 5000"))
        assertNull(parser.parseLine("data: payload"))
        assertEquals(SseMessage.Data("payload"), parser.parseLine(""))
    }

    @Test
    fun blankLineWithoutDataProducesNothing() {
        val parser = SseParser()
        assertNull(parser.parseLine(""))
        assertNull(parser.parseLine(""))
    }

    @Test
    fun flushDispatchesAFrameTheStreamCutShort() {
        val parser = SseParser()
        assertNull(parser.parseLine("""data: {"id":"1"}"""))
        assertEquals(SseMessage.Data("""{"id":"1"}"""), parser.flush())
        assertNull(parser.flush())
    }

    @Test
    fun framesStaySeparateAcrossRepeatedBlankLines() {
        val parser = SseParser()
        parser.parseLine("data: one")
        assertEquals(SseMessage.Data("one"), parser.parseLine(""))
        assertNull(parser.parseLine(""))
        parser.parseLine("data: two")
        assertEquals(SseMessage.Data("two"), parser.parseLine(""))
    }
}
