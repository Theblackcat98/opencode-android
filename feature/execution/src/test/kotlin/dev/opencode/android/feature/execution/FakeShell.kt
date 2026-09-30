package dev.opencode.android.feature.execution

import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream

/**
 * One command as `@opencode/cli` 2.0.18 keeps it, served through [ExecutionServer.handlers].
 *
 * **The route's behaviour is copied from the shipped binary, not from the schema.** `shell.output` reads
 * the captured file from the byte `cursor` for at most `limit` bytes (65536 when none is sent) and answers
 * `cursor` as the byte *after* what it returned; a cursor at or past the end is answered with an empty
 * page whose cursor is the size; and `truncated` is `false` on every page. Only the bytes the command has
 * written so far exist, so a poll that runs ahead of the command gets an empty page — which is exactly the
 * page the app used to take for "finished".
 *
 * The answers are wrapped in `{location, data}` the way the real ones are: the pages this class serves
 * are byte-for-byte the shape recorded from a live 2.0.18 (`{"location":{"directory":"…"},"data":
 * {"output":"one\ntwo\nthree\n","cursor":14,"size":14,"truncated":false}}`).
 */
class FakeShell(
    val id: String = "sh_1",
    private val directory: String = "/work/app",
    private val command: String = "echo one && sleep 3 && echo two && sleep 3 && echo three",
) {
    private val captured = ByteArrayOutputStream()
    private var exitCode: Int? = null

    /** How many pages were asked for, whatever they answered. */
    @Volatile
    var outputRequests: Int = 0
        private set

    /** The command writes [text] to its output. */
    @Synchronized
    fun print(text: String) {
        captured.write(text.toByteArray(Charsets.UTF_8))
    }

    /** The command ends with [code]; `shell.get` says so from then on. */
    @Synchronized
    fun finish(code: Int) {
        exitCode = code
    }

    /** `Shell.Info`, running or exited; a command that has just been created is always running. */
    @Synchronized
    fun info(justCreated: Boolean = false): String {
        val ended = if (justCreated) null else exitCode
        val status = if (ended == null) "running" else "exited"
        val exit = if (ended == null) "" else ""","exit":$ended"""
        val time = if (ended == null) """{"started":1}""" else """{"started":1,"completed":7}"""
        return """{"id":"$id","status":"$status","command":${JsonPrimitive(command)},"cwd":"$directory",""" +
            """"shell":"/usr/bin/zsh","file":"$directory/.out/$id.out","pid":4242$exit,"metadata":{},"time":$time}"""
    }

    /** `shell.get` answers this. */
    fun infoBody(): String = wrap(info())

    /** `shell.output` for the page starting at [cursor], as 2.0.18 answers it. */
    @Synchronized
    fun pageBody(cursor: Long, limit: Long = DEFAULT_LIMIT): String {
        outputRequests++
        val bytes = captured.toByteArray()
        val size = bytes.size.toLong()
        val page = if (cursor >= size) {
            page("", size, size)
        } else {
            val length = minOf(limit, size - cursor).toInt()
            page(String(bytes, cursor.toInt(), length, Charsets.UTF_8), cursor + length, size)
        }
        return wrap(page)
    }

    private fun page(output: String, cursor: Long, size: Long) =
        """{"output":${JsonPrimitive(output)},"cursor":$cursor,"size":$size,"truncated":false}"""

    private fun wrap(data: String) = """{"location":{"directory":"$directory"},"data":$data}"""

    /** Registers this command's routes on [server]. */
    fun serveOn(server: ExecutionServer) {
        server.handlers["GET /api/shell/$id/output"] = { request ->
            val cursor = request.url.queryParameter("cursor")?.toLong() ?: 0L
            val limit = request.url.queryParameter("limit")?.toLong() ?: DEFAULT_LIMIT
            ExecutionServer.Reply(pageBody(cursor, limit))
        }
        server.handlers["GET /api/shell/$id"] = { ExecutionServer.Reply(infoBody()) }
        server.handlers["POST /api/shell"] = { ExecutionServer.Reply(wrap(info(justCreated = true))) }
    }

    private companion object {
        const val DEFAULT_LIMIT = 65536L
    }
}
