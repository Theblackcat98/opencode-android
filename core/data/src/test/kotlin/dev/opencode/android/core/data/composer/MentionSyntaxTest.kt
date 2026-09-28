package dev.opencode.android.core.data.composer

import dev.opencode.android.core.model.PromptMention
import dev.opencode.android.core.model.dataUrlParts
import dev.opencode.android.core.model.toPreviewAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the text of a prompt says it carries.
 *
 * The scanner's job is to be right about the negative cases: an address, a `#` in a file name and a
 * token that only looks like a path must all end up as nothing, because each of them would otherwise
 * become an attachment the user never asked for.
 */
class MentionSyntaxTest {

    @Test
    fun `a plain mention is found with its range`() {
        val tokens = MentionScanner.scan("please read @src/a.ts")
        assertEquals(1, tokens.size)
        val token = tokens.single()
        assertEquals("@src/a.ts", token.raw)
        assertEquals("src/a.ts", token.target)
        assertNull(token.range)
        assertEquals("please read @src/a.ts".substring(token.start, token.end), token.raw)
    }

    @Test
    fun `a line range becomes a range, not part of the name`() {
        val token = MentionScanner.scan("@src/a.ts#20-45").single()
        assertEquals("src/a.ts", token.target)
        assertEquals(LineRange(20, 45), token.range)
    }

    @Test
    fun `a start-only range is a range`() {
        val token = MentionScanner.scan("@src/a.ts#20").single()
        assertEquals(LineRange(20, null), token.range)
    }

    @Test
    fun `a hash that is not a range stays part of the file name`() {
        // `a#b.ts` is a legal name, and dropping the fragment would attach the wrong file.
        val token = MentionScanner.scan("@src/a#b.ts").single()
        assertEquals("src/a#b.ts", token.target)
        assertNull(token.range)
    }

    @Test
    fun `a backwards range is not a range, and the fragment stays part of the name`() {
        // Otherwise the client would attach `src/a.ts` when the user named `src/a.ts#45-20`.
        val token = MentionScanner.scan("@src/a.ts#45-20").single()
        assertEquals("src/a.ts#45-20", token.target)
        assertNull(token.range)
    }

    @Test
    fun `line numbers are one-based, so zero is not a line`() {
        assertNull(MentionScanner.parseRange("0"))
        assertNull(MentionScanner.parseRange("-5"))
        assertEquals(LineRange(1, null), MentionScanner.parseRange("1"))
    }

    @Test
    fun `every mention in a line is found, in order`() {
        val tokens = MentionScanner.scan("@a.ts and @b/c.ts#3-4 and @plan")
        assertEquals(listOf("a.ts", "b/c.ts", "plan"), tokens.map { it.target })
        assertEquals(LineRange(3, 4), tokens[1].range)
    }

    @Test
    fun `an address is not a mention`() {
        assertEquals(emptyList<MentionToken>(), MentionScanner.scan("write to nick@example.com"))
    }

    @Test
    fun `a mention range is a half-open span of the original text`() {
        val text = "hi @a.ts#1-2 there"
        val token = MentionScanner.scan(text).single()
        val mention: PromptMention = token.toMention()
        assertEquals("@a.ts#1-2", text.substring(mention.start, mention.end))
        assertEquals(token.raw, mention.text)
    }

    @Test
    fun `a range spells itself both ways`() {
        assertEquals("?start=20&end=45", LineRange(20, 45).toQuery())
        assertEquals("?start=20", LineRange(20, null).toQuery())
        assertEquals("#20-45", LineRange(20, 45).toSuffix())
        assertEquals("#20", LineRange(20, null).toSuffix())
    }

    // --- ServerPath -------------------------------------------------------------------------

    @Test
    fun `a server path becomes a file uri`() {
        assertEquals("file:///work/src/a.ts", ServerPath.toUri("/work/src/a.ts", "/work"))
    }

    @Test
    fun `a relative path resolves against the location`() {
        assertEquals("file:///work/src/a.ts", ServerPath.toUri("src/a.ts", "/work"))
        assertEquals("file:///work/a.ts", ServerPath.toUri("./a.ts", "/work"))
        assertEquals("file:///work/a.ts", ServerPath.toUri("../a.ts", "/work/src"))
    }

    @Test
    fun `an absolute path is the server's own spelling, unnormalized`() {
        assertEquals("file:///work/./src/../a.ts", ServerPath.toUri("/work/./src/../a.ts", "/work"))
    }

    @Test
    fun `a line range rides on the uri`() {
        assertEquals(
            "file:///work/src/a.ts?start=20&end=45",
            ServerPath.toUri("/work/src/a.ts", "/work", LineRange(20, 45)),
        )
    }

    @Test
    fun `a path with characters a url cannot carry is encoded`() {
        assertEquals("file:///work/a%20b/c%23d.ts", ServerPath.toUri("/work/a b/c#d.ts", "/work"))
    }

    @Test
    fun `a non-ascii path is encoded as utf-8`() {
        assertEquals("file:///work/%C3%A4.ts", ServerPath.toUri("/work/ä.ts", "/work"))
    }

    @Test
    fun `a mention shows the path relative to the location`() {
        assertEquals("src/a.ts", ServerPath.toMentionText("/work/src/a.ts", "/work"))
        assertEquals("/elsewhere/a.ts", ServerPath.toMentionText("/elsewhere/a.ts", "/work"))
        assertEquals("a.ts", ServerPath.toMentionText("a.ts", null))
    }

    @Test
    fun `a mention and its uri round trip through the location`() {
        val text = MentionScanner.scan("@src/a.ts").single()
        val uri = ServerPath.toUri(text.target, "/work", text.range)
        val decoded = uri.removePrefix("file://")
        assertEquals("/work/src/a.ts", decoded)
    }

    @Test
    fun `a label is the last segment, and a directory keeps its name`() {
        assertEquals("a.ts", ServerPath.label("/work/src/a.ts"))
        assertEquals("src", ServerPath.label("/work/src/"))
        assertEquals("work", ServerPath.label("/work"))
    }

    // --- the request shape --------------------------------------------------------------------

    @Test
    fun `a data url is split into its type and payload`() {
        val (mime, payload) = dataUrlParts("data:image/png;base64,AAAA")!!
        assertEquals("image/png", mime)
        assertEquals("AAAA", payload)
    }

    @Test
    fun `a data url without a type reports an empty one rather than guessing`() {
        val (mime, payload) = dataUrlParts("data:,hello")!!
        assertEquals("", mime)
        assertEquals("hello", payload)
    }

    @Test
    fun `a preview attachment keeps the source and the name and not the bytes`() {
        val preview = dev.opencode.android.core.model.PromptFileInput(
            uri = "data:image/png;base64,AAAA",
            name = "shot.png",
        ).toPreviewAttachment()

        assertEquals("image/png", preview.mime)
        assertEquals("shot.png", preview.name)
        assertEquals(
            dev.opencode.android.core.model.PromptFileSource.Inline,
            preview.source,
        )
        assertTrue("the optimistic copy must not hold the payload", preview.data.isEmpty())
    }

    @Test
    fun `a file preview drops the range from the uri it keeps`() {
        val preview = dev.opencode.android.core.model.PromptFileInput(
            uri = "file:///work/a.ts?start=1&end=2",
            name = "a.ts",
        ).toPreviewAttachment()

        assertEquals("file:///work/a.ts", (preview.source as dev.opencode.android.core.model.PromptFileSource.Uri).uri)
    }
}
