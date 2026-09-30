package dev.opencode.android.core.data.server

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.composer.AttachmentKind
import dev.opencode.android.core.data.composer.AttachmentPolicy
import dev.opencode.android.core.data.composer.FileContentKind
import dev.opencode.android.core.data.composer.FileReadResult
import dev.opencode.android.core.model.FileSystemEntry
import dev.opencode.android.core.model.FileSystemWrite
import dev.opencode.android.core.network.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer
import retrofit2.Call
import retrofit2.HttpException

/**
 * Reading and writing the server's filesystem (`fs.read`, `fs.write`), and the navigation above it.
 *
 * **The bytes come from the server and the phone's job is only to classify them.** `fs.read` is
 * `application/octet-stream` (features doc §27), so a body is text, a picture or something the
 * phone can only offer to share, and that decision — which is the difference between a viewer and a
 * download button — is made once, off the main thread, by a pure function over the bytes.
 *
 * **A `NUL` byte or an invalid UTF-8 sequence means binary, and no amount of extension guessing
 * overrules it.** A `.png` that contains text is a `.png` a text viewer would mangle, so the bytes
 * decide and the extension only picks the language for a body that is text.
 *
 * **A read is bounded, because a file is not.** The route answers with the whole file and the
 * server's `FileSystem.Entry` carries no size to check first, so the phone cannot know a file is
 * enormous until it is already downloading it. [read] therefore streams: it takes at most
 * [textPreviewBytes] of a file (at most [imagePreviewBytes] of a picture), classifies that prefix,
 * and closes the connection so the rest is never fetched. The result says it was cut
 * ([FileReadResult.truncated]) and how large the file is when the server said
 * ([FileReadResult.totalBytes], from `Content-Length`). Reading the whole body first and deciding
 * afterwards is what this class did before, and a 572 MB file killed the app with an
 * `OutOfMemoryError` on an OkHttp thread.
 *
 * **Writing is a dangerous action and is behind a capability probe and a setting** (plan §5.2,
 * "experimental file writes"). [write] therefore does not decide whether it may run — the caller
 * does, through [dev.opencode.android.core.data.capability.CapabilityPolicy] — and it always
 * re-reads the file it wrote, because a write that reported success and left different bytes on
 * disk is exactly the case the user needs to be told about.
 *
 * @param ioDispatcher where the body is read. A streamed body is a blocking socket read, so it is
 *   never done on the caller's thread, which for a screen is the main one.
 * @param textPreviewBytes the most of a text or binary file that is read; see [TEXT_PREVIEW_MAX_BYTES].
 * @param imagePreviewBytes the most of a picture that is read; see [IMAGE_PREVIEW_MAX_BYTES].
 */
class FileReader(
    private val api: ServerApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val textPreviewBytes: Long = TEXT_PREVIEW_MAX_BYTES,
    private val imagePreviewBytes: Long = IMAGE_PREVIEW_MAX_BYTES,
) {
    private val _state = MutableStateFlow(FileBrowserState())
    val state: StateFlow<FileBrowserState> = _state.asStateFlow()

    /** `fs.list` for [path] inside [directory], keeping the server's spelling of both. */
    suspend fun list(directory: String, path: String? = null): ActionError? = listAndWait(directory, path)

    /**
     * The same call, awaited, which is what a test and a screen that navigates by awaiting need.
     *
     * A `null` [path] lists the location itself, and a failure keeps the previous listing and records
     * the error: a directory the user cannot read is a normal outcome and an empty browser with no
     * explanation is not a useful one.
     */
    suspend fun listAndWait(directory: String, path: String? = null): ActionError? {
        _state.value = _state.value.copy(directory = directory, path = path, loading = true, error = null)
        return try {
            val entries = api.listDirectory(directory, path).data
            _state.value = FileBrowserState(directory = directory, path = path, entries = entries)
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val failure = error.toActionError()
            _state.value = _state.value.copy(loading = false, error = failure.message)
            failure
        }
    }

    /** `fs.find`: the quick-open search, the same call the composer's `@` uses. */
    suspend fun find(
        directory: String,
        query: String,
        type: String? = null,
        limit: String? = "50",
    ): Result<List<FileSystemEntry>> =
        guarded { api.findFiles(directory = directory, query = query, type = type, limit = limit).data }

    /**
     * `fs.read`: the start of one file, and whether that is all of it.
     *
     * [path] is the server's own spelling, absolute or relative to the location. A relative path is
     * resolved against the location by the caller, because this method is the one place that knows
     * which location it was asked about and guessing a base here would be the P2 rule broken.
     *
     * **At most a cap is read, and the connection is dropped on the way out.** The cap is
     * [textPreviewBytes], or [imagePreviewBytes] when the server's own `Content-Type` says the body
     * is a picture. A picture whose `Content-Length` is already over its cap is not read at all —
     * the answer is known from the headers, and a byte of it would be a byte spent on a preview that
     * cannot be shown. Everything past the cap is left on the server: the call is *cancelled*, which
     * closes the socket, rather than the body being closed, which makes OkHttp try to drain what is
     * left for up to 100 ms first.
     *
     * The body is read on [ioDispatcher], and cancelling the caller cancels the call, so a read that
     * is waiting on a slow server is abandoned at once rather than when the server next speaks.
     */
    suspend fun read(directory: String?, path: String): Result<FileReadResult> = guarded {
        val call = api.readFile(url = readUrl(path), directory = directory)
        coroutineScope {
            val fetching = async(ioDispatcher) { fetchPrefix(path, call) }
            try {
                fetching.await()
            } catch (gaveUp: CancellationException) {
                // A blocking socket read does not see a coroutine's cancellation, but it does see its
                // call being cancelled, and that is what makes the worker thread return.
                call.cancel()
                throw gaveUp
            }
        }
    }

    /**
     * `fs.read`, as the file viewer opens a file: the same bounded read, published in [state].
     *
     * **This is what puts the content on the screen.** [read] is also the configuration editor's
     * read, and an editor's read must not change what the file browser shows, so publishing is a
     * separate call. [FileBrowserState.reading] is the path being read (a row shows a spinner for
     * it), and [FileBrowserState.content] the answer. A later tap or a navigation supersedes an
     * earlier read: its answer is dropped rather than shown over the newer choice.
     */
    suspend fun open(directory: String?, path: String): Result<FileReadResult> {
        _state.update { it.copy(reading = path, content = null, readError = null) }
        val result = read(directory, path)
        _state.update { current ->
            when {
                current.reading != path -> current
                result.isSuccess -> current.copy(reading = null, content = result.getOrThrow())
                else -> current.copy(reading = null, readError = result.exceptionOrNull()?.describe())
            }
        }
        return result
    }

    /**
     * Shows the read-back of a write in the viewer, when the viewer is showing that file.
     *
     * A write is the viewer's own action, so what it read back replaces the content it was
     * showing; a write made by the configuration editor never calls this.
     */
    fun showReadBack(file: FileReadResult) {
        _state.update { current ->
            if (current.content?.path == file.path) current.copy(content = file) else current
        }
    }

    /**
     * `experimental.fs.write`: writes [text] to [path] and reads it back.
     *
     * The path is the query parameter, because that is what the route takes, and the body is the
     * raw content. The read-back is the point: the server answers with the path it wrote, and a
     * confirmation that the bytes are there is stronger than a confirmation that a call returned.
     *
     * **The read-back is a [read], so it is bounded by the same cap, and a write larger than the cap
     * reads back as a prefix.** A write is text the user typed or edited, which is small, but the
     * route takes any body and the cap does not bend for it: the result then has
     * [FileReadResult.truncated] set and [FileReadResult.sizeBytes] is the size the *server* now
     * holds. That is the value to compare with what was sent — the count of bytes held would be the
     * cap, whatever was written. The prefix that did come back is comparable with the start of
     * [text], and nothing here compares the rest, because the rest was never downloaded.
     */
    suspend fun write(directory: String?, path: String, text: String): Result<FileReadResult> {
        // `text/plain` is a `415` from this route: verified against a live 2.0.18 server, which
        // accepts `application/octet-stream` and nothing else. The bytes are UTF-8 and the client is
        // the only thing that knows that, so the encoding is chosen here rather than asked for.
        val mediaType = api.OCTET_STREAM.toMediaTypeOrNull()
        val written: Result<FileSystemWrite> = guarded {
            api.writeFile(
                path = path,
                directory = directory,
                body = text.toByteArray(Charsets.UTF_8).toRequestBody(mediaType),
            ).data
        }
        if (written.isFailure) return Result.failure(written.exceptionOrNull()!!)
        return read(directory, path)
    }

    /**
     * Runs [call] and takes the start of its body. Blocking: call on [ioDispatcher].
     *
     * A non-2xx answer is raised as the [HttpException] a `suspend` Retrofit call would have raised,
     * so [toActionError] classifies a `404` on a listed file exactly as before. The body is closed on
     * every path, and the call is cancelled when the file was cut: that is what stops the transfer.
     */
    private fun fetchPrefix(path: String, call: Call<ResponseBody>): FileReadResult {
        val response = call.execute()
        if (!response.isSuccessful) throw HttpException(response)
        // A `204` has no body, and a file with no bytes is an empty text file rather than a failure.
        val body = response.body() ?: return classify(path = path, bytes = ByteArray(0), mime = null)
        try {
            val result = readPrefix(path, body)
            if (result.truncated) call.cancel()
            return result
        } finally {
            body.close()
        }
    }

    /**
     * Takes the cap's worth of [body], and decides what it is.
     *
     * The cap is chosen from the `Content-Type` before a byte is read, because that is what the
     * server said the body is. It is never exceeded: the prefix is read with `read(sink, byteCount)`,
     * which returns at most what it is asked for, into a buffer that is copied out once.
     */
    private fun readPrefix(path: String, body: ResponseBody): FileReadResult {
        val contentType = body.contentType()
        val mime = if (contentType == null) null else "${contentType.type}/${contentType.subtype}"
        val isImage = mime != null && AttachmentPolicy.classify(mime) == AttachmentKind.IMAGE
        val cap = if (isImage) imagePreviewBytes else textPreviewBytes
        val declared = body.contentLength().takeIf { it >= 0 }

        if (isImage && declared != null && declared > cap) {
            // Known from the headers alone: nothing is read, and cancelling the call ends the transfer.
            return classify(path = path, bytes = ByteArray(0), mime = mime, truncated = true, totalBytes = declared)
        }

        val source = body.source()
        val held = Buffer()
        var remaining = minOf(cap, declared ?: cap)
        while (remaining > 0) {
            val read = source.read(held, remaining)
            if (read == -1L) break
            remaining -= read
        }
        val bytes = held.readByteArray()
        // With a length the answer is arithmetic. Without one the only way to know whether the file
        // ended exactly at the cap is to ask the stream, which blocks for at most one more byte.
        val truncated = if (declared != null) declared > bytes.size else !source.exhausted()
        return classify(path = path, bytes = bytes, mime = mime, truncated = truncated, totalBytes = declared)
    }

    /** Closes the browser, which is what leaving the screen does. */
    fun clear() {
        _state.value = FileBrowserState()
    }

    /** The message [guarded] classified a failure with, which is the server's own text when it gave one. */
    private fun Throwable.describe(): String = ((this as? ActionFailure)?.error ?: toActionError()).message

    private suspend inline fun <T> guarded(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(ActionFailure(error.toActionError()))
    }

    companion object {
        /**
         * The most of a text or binary file that is read: 2 MiB.
         *
         * **Why this number.** The viewer draws at most 2,000 lines, so on ordinary source it shows
         * a few hundred KiB at most; 2 MiB is headroom for long lines (minified scripts, JSON) and
         * for a log whose interesting part is its start, without being a download the user waits for
         * on a mobile connection — a couple of seconds, and nothing at all for a file that is smaller.
         * It is also what the phone can afford to hold *several times over*: the bytes, the decoded
         * `String`, the strict-decoder's `CharBuffer` and the lines are all bounded multiples of it, so
         * the worst a single read costs is on the order of ten MiB, against the hundreds the process
         * is allowed. The 572 MB file this cap exists for cost the app its life at the first copy.
         */
        const val TEXT_PREVIEW_MAX_BYTES: Long = 2L * 1024 * 1024

        /**
         * The most of a picture that is read: 8 MiB.
         *
         * A picture is all-or-nothing — half of a PNG is not a preview — so its cap is not "how much
         * to show" but "how big a picture is worth decoding on a phone", and a preview is a place for
         * a screenshot or a photo, not a print. Phone photos are 2 to 6 MiB and screenshots under 3,
         * so 8 MiB covers them with room; the decoded bitmap is many times the file, which is why the
         * cap is higher than the text one and no higher. Over it the picture is *not read*: its
         * `Content-Length` decides, and the viewer says it is too large to preview.
         */
        const val IMAGE_PREVIEW_MAX_BYTES: Long = 8L * 1024 * 1024

        /**
         * The URL `fs.read` is asked with, from the server's own spelling of the path.
         *
         * An absolute path keeps its leading `/`, which after the route's own separator is the
         * double slash the server's wildcard needs to see an absolute path rather than a relative
         * one; a relative path is sent as it stands and resolves against the location. The path is
         * percent-encoded because a file with a space in its name is a file a reviewer will open.
         */
        fun readUrl(path: String): String =
            "api/fs/read/" + dev.opencode.android.core.data.composer.ServerPath.encodePath(path)

        /**
         * What [bytes] are, as a pure function of the content and the name.
         *
         * **The type the server sent decides, and only then the bytes.** An image is an image
         * because the server said so; its bytes are not UTF-8 by definition, so a `NUL` or a strict
         * UTF-8 test run first would classify every picture as a binary and the image branch below
         * would be unreachable — which is exactly what it was before this ordering was fixed, and
         * exactly the kind of thing a test that passed a text body through it would not find.
         *
         * Once the server has not said "image", the bytes decide: a `NUL` byte or a malformed UTF-8
         * sequence is binary whatever the extension says, a body that decodes and has no control
         * characters is text, and the extension only picks the language for one. An empty file is
         * text, because a viewer that shows nothing is better than a download button for a file
         * with no bytes.
         *
         * **[bytes] may be only the start of the file** ([truncated]), and then two things change.
         * A cut can land in the middle of a multi-byte character, and a strict decoder would call
         * that malformed and turn a text file into a "binary" for no reason other than where the
         * cap fell, so an incomplete character at the very end is dropped first ([totalBytes] still
         * counts it). And nothing is kept that cannot be shown: a truncated picture or binary holds
         * no bytes, only the fact of its size. A truncated *complete-looking but invalid* body is
         * still binary — only a trailing partial character is forgiven, and only when the file
         * really was cut.
         *
         * @param totalBytes the file's size when the server said; when it did not and the body was
         *   read to its end, the size is the body's.
         */
        fun classify(
            path: String,
            bytes: ByteArray,
            mime: String?,
            truncated: Boolean = false,
            totalBytes: Long? = null,
        ): FileReadResult {
            val type = mime?.substringBefore(';')?.trim()?.lowercase()
            val isImage = type != null && AttachmentPolicy.classify(type) == AttachmentKind.IMAGE
            val size = totalBytes ?: if (truncated) null else bytes.size.toLong()
            if (truncated && isImage) {
                return FileReadResult(
                    path = path,
                    bytes = ByteArray(0),
                    kind = FileContentKind.IMAGE,
                    mime = type,
                    truncated = true,
                    totalBytes = size,
                )
            }
            val whole = if (truncated) withoutPartialCharacter(bytes) else bytes
            var decoded: String? = null
            val kind = when {
                whole.isEmpty() -> FileContentKind.TEXT

                isImage -> FileContentKind.IMAGE

                whole.any { it == 0.toByte() } -> FileContentKind.BINARY

                else -> {
                    decoded = decodeStrict(whole)
                    when {
                        decoded == null -> FileContentKind.BINARY

                        AttachmentPolicy.classify(type) == AttachmentKind.BINARY && looksBinary(whole) ->
                            FileContentKind.BINARY

                        else -> FileContentKind.TEXT
                    }
                }
            }
            val text = if (kind == FileContentKind.TEXT) decoded ?: String(whole, Charsets.UTF_8) else null
            return FileReadResult(
                path = path,
                bytes = if (truncated && kind != FileContentKind.TEXT) ByteArray(0) else whole,
                kind = kind,
                mime = type,
                text = text,
                truncated = truncated,
                totalBytes = size,
            )
        }

        /**
         * [bytes] without a UTF-8 character that the end of the array cuts in two.
         *
         * A character of `n` bytes is a lead byte followed by `n - 1` continuation bytes (`10xxxxxx`),
         * so an incomplete one at the end has at most three bytes and its lead is within the last
         * three. Only a *valid* lead with too few bytes after it is dropped; a stray continuation
         * byte, an impossible lead or a complete character is left for the strict decoder to judge,
         * because forgiving those would call a corrupt file text.
         */
        internal fun withoutPartialCharacter(bytes: ByteArray): ByteArray {
            var index = bytes.size - 1
            val floor = maxOf(0, bytes.size - 3)
            while (index >= floor) {
                val value = bytes[index].toInt() and 0xFF
                if (value and 0xC0 != 0x80) {
                    val length = when (value) {
                        in 0xC2..0xDF -> 2
                        in 0xE0..0xEF -> 3
                        in 0xF0..0xF4 -> 4
                        else -> 1
                    }
                    return if (bytes.size - index < length) bytes.copyOf(index) else bytes
                }
                index--
            }
            return bytes
        }

        /**
         * [bytes] decoded as UTF-8, or `null` when they are not.
         *
         * A strict decoder, because a lenient one would replace the bad bytes with `U+FFFD` and the
         * viewer would show a file that looks readable and is not the file. It decodes once and
         * returns the text, so a read does not decode the same two megabytes twice.
         */
        private fun decodeStrict(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }

        /** The heuristic for a type that says nothing: a control character that is not a tab. */
        private fun looksBinary(bytes: ByteArray): Boolean {
            var index = 0
            val limit = minOf(bytes.size, 8_192)
            while (index < limit) {
                val value = bytes[index].toInt() and 0xFF
                if (value < 0x09 || (value > 0x0D && value < 0x20)) return true
                index++
            }
            return false
        }
    }
}

/**
 * What the file browser shows right now.
 *
 * The entries keep the server's spelling ([FileSystemEntry.path]) and the row shows the label, which
 * is why navigation never joins paths locally: the next `fs.list` is asked with a path the server
 * itself wrote.
 */
data class FileBrowserState(
    val directory: String? = null,
    val path: String? = null,
    val entries: List<FileSystemEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** The file being read, and its content once it arrives. */
    val reading: String? = null,
    val content: FileReadResult? = null,
    val readError: String? = null,
) {
    /** Directories first, then files, each by name: what a file browser is expected to do. */
    val sorted: List<FileSystemEntry>
        get() = entries.sortedWith(
            compareByDescending<FileSystemEntry> { it.isDirectory }.thenBy { it.name.lowercase() },
        )

    /** The path one directory up, or `null` at the filesystem root. */
    val parent: String?
        get() {
            val current = path ?: return null
            val trimmed = current.trimEnd('/')
            val cut = trimmed.lastIndexOf('/')
            return if (cut <= 0) null else trimmed.substring(0, cut)
        }

    /** Whether the browser is showing the location's own root. */
    val atLocationRoot: Boolean get() = path.isNullOrBlank()
}
