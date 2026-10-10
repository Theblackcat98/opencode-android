package dev.opencode.android.feature.sessions.ui.timeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentImageTest {

    @Test
    fun aBareBase64PayloadDecodesToItsBytes() {
        assertArrayEquals("hi".toByteArray(), attachmentBytes("aGk="))
    }

    @Test
    fun aDataUrlDecodesToTheSameBytesAsItsPayload() {
        assertArrayEquals("hi".toByteArray(), attachmentBytes("data:image/png;base64,aGk="))
    }

    @Test
    fun aPayloadThatCannotBeBase64IsNull() {
        assertNull(attachmentBytes("a"))
    }
}
