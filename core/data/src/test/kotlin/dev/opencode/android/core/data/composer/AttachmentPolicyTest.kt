package dev.opencode.android.core.data.composer

import dev.opencode.android.core.model.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which attachments may be sent, and which need the user to agree.
 *
 * The two cases are different in kind: a format the model is never sent has no confirmation that
 * makes it arrive, so it is blocked; a model that declares no image input might still handle the
 * picture, so the phone says what it knows and asks (plan §5.2).
 */
class AttachmentPolicyTest {

    @Test
    fun `an image a model takes needs nothing`() {
        assertEquals(AttachmentVerdict.Ok, AttachmentPolicy.verify(picture(100), model(images = true)))
    }

    @Test
    fun `an image a model does not take asks first`() {
        assertEquals(
            AttachmentVerdict.NeedsConfirmation(AttachmentProblem.MODEL_TAKES_NO_IMAGES),
            AttachmentPolicy.verify(picture(100), model(images = false)),
        )
    }

    @Test
    fun `an unknown model blocks nothing`() {
        // The catalog may not have loaded. Guessing that a picture is unacceptable on the strength
        // of missing information is the worse failure.
        assertEquals(AttachmentVerdict.Ok, AttachmentPolicy.verify(picture(100), null))
    }

    @Test
    fun `a format the model is never sent is blocked whatever the model is`() {
        val pdf = AttachmentDraft(
            id = "p",
            label = "spec.pdf",
            uri = "file:///work/spec.pdf",
            kind = AttachmentKind.BINARY,
            sizeBytes = 10,
            mime = "application/pdf",
        )
        assertEquals(
            AttachmentVerdict.Blocked(AttachmentProblem.NOT_MODEL_READABLE, "application/pdf"),
            AttachmentPolicy.verify(pdf, model(images = true)),
        )
    }

    @Test
    fun `an http url is refused because the api does not accept one`() {
        val remote = AttachmentDraft(
            id = "r",
            label = "page",
            uri = "https://example.com/a.ts",
            kind = AttachmentKind.TEXT,
        )
        assertEquals(
            AttachmentVerdict.Blocked(AttachmentProblem.UNSUPPORTED_SCHEME),
            AttachmentPolicy.verify(remote, null),
        )
    }

    @Test
    fun `a uri with no scheme is malformed`() {
        val broken = AttachmentDraft("b", "x", "/work/a.ts", AttachmentKind.TEXT)
        assertEquals(
            AttachmentVerdict.Blocked(AttachmentProblem.MALFORMED_URI),
            AttachmentPolicy.verify(broken, null),
        )
    }

    @Test
    fun `a directory is always allowed and is never a picture`() {
        val directory = AttachmentDraft("d", "src", "file:///work/src", AttachmentKind.DIRECTORY)
        assertEquals(AttachmentVerdict.Ok, AttachmentPolicy.verify(directory, model(images = false)))
    }

    @Test
    fun `the size limit counts decoded bytes and is exact at the boundary`() {
        assertTrue(
            AttachmentPolicy.verify(picture(AttachmentPolicy.MAX_DECODED_BYTES), null) is AttachmentVerdict.Ok,
        )
        val verdict = AttachmentPolicy.verify(picture(AttachmentPolicy.MAX_DECODED_BYTES + 1), null)
        assertTrue(verdict is AttachmentVerdict.Blocked)
        assertEquals(AttachmentProblem.TOO_LARGE, (verdict as AttachmentVerdict.Blocked).problem)
    }

    @Test
    fun `a server file with an unknown size is not assumed to be too large`() {
        // `sizeBytes` is 0 when the client does not know the size, and inventing a size would block
        // a file the server can read perfectly well.
        val unknown = AttachmentDraft("u", "big.log", "file:///work/big.log", AttachmentKind.TEXT, sizeBytes = 0)
        assertEquals(AttachmentVerdict.Ok, AttachmentPolicy.verify(unknown, null))
    }

    @Test
    fun `the formats a model reads as pictures are exactly the four the server sends`() {
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.classify("image/png"))
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.classify("image/jpeg"))
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.classify("image/gif"))
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.classify("image/webp"))
    }

    @Test
    fun `svg is text to a model`() {
        assertEquals(AttachmentKind.TEXT, AttachmentPolicy.classify("image/svg+xml"))
    }

    @Test
    fun `source and configuration are text even when the type does not say so`() {
        assertEquals(AttachmentKind.TEXT, AttachmentPolicy.classify("text/plain"))
        assertEquals(AttachmentKind.TEXT, AttachmentPolicy.classify("text/markdown; charset=utf-8"))
        assertEquals(AttachmentKind.TEXT, AttachmentPolicy.classify("application/json"))
        assertEquals(AttachmentKind.TEXT, AttachmentPolicy.classify("application/x-sh"))
    }

    @Test
    fun `media and archives are not`() {
        assertEquals(AttachmentKind.BINARY, AttachmentPolicy.classify("application/pdf"))
        assertEquals(AttachmentKind.BINARY, AttachmentPolicy.classify("video/mp4"))
        assertEquals(AttachmentKind.BINARY, AttachmentPolicy.classify("audio/mpeg"))
        assertEquals(AttachmentKind.BINARY, AttachmentPolicy.classify("application/zip"))
        assertEquals(AttachmentKind.BINARY, AttachmentPolicy.classify(null))
    }

    @Test
    fun `a draft describes itself with what it knows`() {
        val draft = picture(2048).copy(range = LineRange(1, 2))
        assertEquals("shot.png · #1-2 · 2048 B", draft.describe { "${it} B" })
    }

    private fun picture(bytes: Long) = AttachmentDraft(
        id = "img",
        label = "shot.png",
        uri = "data:image/png;base64,AAAA",
        kind = AttachmentKind.IMAGE,
        sizeBytes = bytes,
        mime = "image/png",
    )

    private fun model(images: Boolean) = ModelInfo(
        id = "m",
        modelID = "m",
        providerID = "p",
        name = "M",
        capabilities = ModelInfo.Capabilities(tools = true, input = if (images) listOf("image") else emptyList()),
        limit = ModelInfo.Limit(context = 1000),
    )
}
