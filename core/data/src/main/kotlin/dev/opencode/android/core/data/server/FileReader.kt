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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

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
 * **Writing is a dangerous action and is behind a capability probe and a setting** (plan §5.2,
 * "experimental file writes"). [write] therefore does not decide whether it may run — the caller
 * does, through [dev.opencode.android.core.data.capability.CapabilityPolicy] — and it always
 * re-reads the file it wrote, because a write that reported success and left different bytes on
 * disk is exactly the case the user needs to be told about.
 */
class FileReader(
    private val api: ServerApi,
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
    suspend fun find(directory: String, query: String, type: String? = null, limit: String? = "50"): Result<List<FileSystemEntry>> =
        guarded { api.findFiles(directory = directory, query = query, type = type, limit = limit).data }

    /**
     * `fs.read`: the bytes of one file.
     *
     * [path] is the server's own spelling, absolute or relative to the location. A relative path is
     * resolved against the location by the caller, because this method is the one place that knows
     * which location it was asked about and guessing a base here would be the P2 rule broken.
     */
    suspend fun read(directory: String?, path: String): Result<FileReadResult> = guarded {
        val body = api.readFile(url = readUrl(path), directory = directory)
        val bytes = body.bytes()
        val contentType = body.contentType()
        val mime = if (contentType == null) null else "${contentType.type}/${contentType.subtype}"
        classify(path = path, bytes = bytes, mime = mime)
    }

    /**
     * `experimental.fs.write`: writes [text] to [path] and reads it back.
     *
     * The path is the query parameter, because that is what the route takes, and the body is the
     * raw content. The read-back is the point: the server answers with the path it wrote, and a
     * confirmation that the bytes are there is stronger than a confirmation that a call returned.
     */
    suspend fun write(directory: String?, path: String, text: String): Result<FileReadResult> {
        // `text/plain` is a `415` from this route: verified against a live 2.0.18 server, which
        // accepts `application/octet-stream` and nothing else. The bytes are UTF-8 and the client is
        // the only thing that knows that, so the encoding is chosen here rather than asked for.
        val mediaType = api.OCTET_STREAM.toMediaTypeOrNull()
        val written: Result<FileSystemWrite> = guarded {
            api.writeFile(path = path, directory = directory, body = text.toByteArray(Charsets.UTF_8).toRequestBody(mediaType)).data
        }
        if (written.isFailure) return Result.failure(written.exceptionOrNull()!!)
        return read(directory, path)
    }

    /** Closes the browser, which is what leaving the screen does. */
    fun clear() {
        _state.value = FileBrowserState()
    }

    private suspend inline fun <T> guarded(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(ActionFailure(error.toActionError()))
    }

    companion object {
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
         * A NUL byte or a malformed UTF-8 sequence is binary, whatever the extension says; a body
         * that decodes and has no control characters is text; the image types are pictures. An
         * empty file is text, because a viewer that shows nothing is better than a download button
         * for a file with no bytes.
         */
        fun classify(path: String, bytes: ByteArray, mime: String?): FileReadResult {
            val type = mime?.substringBefore(';')?.trim()?.lowercase()
            val kind = when {
                bytes.isEmpty() -> FileContentKind.TEXT
                bytes.any { it == 0.toByte() } -> FileContentKind.BINARY
                !isValidUtf8(bytes) -> FileContentKind.BINARY
                type != null && AttachmentPolicy.classify(type) == AttachmentKind.IMAGE -> FileContentKind.IMAGE
                AttachmentPolicy.classify(type) == AttachmentKind.BINARY && looksBinary(bytes) -> FileContentKind.BINARY
                else -> FileContentKind.TEXT
            }
            val text = if (kind == FileContentKind.TEXT) String(bytes, Charsets.UTF_8) else null
            return FileReadResult(path = path, bytes = bytes, kind = kind, mime = type, text = text)
        }

        /**
         * Whether [bytes] decode as UTF-8.
         *
         * A strict decoder, because a lenient one would replace the bad bytes with `U+FFFD` and the
         * viewer would show a file that looks readable and is not the file.
         */
        private fun isValidUtf8(bytes: ByteArray): Boolean = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
            true
        }.getOrDefault(false)

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
