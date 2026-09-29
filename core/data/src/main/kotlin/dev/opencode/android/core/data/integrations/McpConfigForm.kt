package dev.opencode.android.core.data.integrations

import dev.opencode.android.core.model.McpProtocol
import dev.opencode.android.core.model.McpServerConfig
import dev.opencode.android.core.model.McpTimeout
import dev.opencode.android.core.model.SafeNavigationUrl

/**
 * What the "add an MCP server" form is collecting.
 *
 * **A flat value type, and the reason it is not the wire type.** The form is driven by a single
 * text field that switches between "command" and "URL" as the kind changes, and by three timeout
 * boxes that are blank when the user leaves them alone. The wire type wants a `List<String>` command,
 * an `int` timeout and no "unset" state, so the conversion has to live somewhere — and putting it
 * here means the union that goes on the wire can only be built by code that has been through the
 * validation rules below.
 */
data class McpServerDraft(
    val name: String = "",
    val kind: Kind = Kind.LOCAL,
    /** The command line for a local server, split on whitespace by [toConfig]. */
    val command: String = "",
    val cwd: String = "",
    /** `KEY=value` per line, which is the only way a phone keyboard can enter a map. */
    val environment: String = "",
    val url: String = "",
    /** `KEY=value` per line, the same shape as [environment]. */
    val headers: String = "",
    val startupTimeout: String = "",
    val catalogTimeout: String = "",
    val executionTimeout: String = "",
    val codemode: Boolean = false,
    val protocol: String = McpProtocol.LEGACY,
) {
    enum class Kind { LOCAL, REMOTE }
}

/** Why a draft cannot be sent, which the form marks a field with. */
enum class McpConfigProblem(val field: String) {
    NAME_REQUIRED("name"),
    COMMAND_REQUIRED("command"),
    URL_REQUIRED("url"),
    URL_NOT_HTTP("url"),
    NOT_A_POSITIVE_INTEGER("timeout"),
}

/**
 * Validates a draft and turns it into the wire union (plan §6, "Add a runtime server through a
 * form").
 *
 * **A `local` server runs a command on the user's machine, so the two rules that matter most are
 * that a command must be there and that a remote URL must be http or https.** The URL rule is the
 * same one OAuth navigation uses ([SafeNavigationUrl]): the *server* will fetch it, so an
 * `http://`/`https://` URL with a host is the only thing this form can produce. A form that let
 * `file:///etc/passwd` through would make the MCP client read a local file on the user's own
 * machine, with the user having typed it into what looks like an address bar.
 */
object McpConfigForm {

    /** Every problem in the draft, in field order, so the first one is the one to show. */
    fun problems(draft: McpServerDraft): List<McpConfigProblem> = buildList {
        if (draft.name.isBlank()) add(McpConfigProblem.NAME_REQUIRED)
        when (draft.kind) {
            McpServerDraft.Kind.LOCAL -> {
                if (draft.command.isBlank()) add(McpConfigProblem.COMMAND_REQUIRED)
            }

            McpServerDraft.Kind.REMOTE -> when {
                draft.url.isBlank() -> add(McpConfigProblem.URL_REQUIRED)
                SafeNavigationUrl.parse(draft.url) == null -> add(McpConfigProblem.URL_NOT_HTTP)
            }
        }
        listOf(draft.startupTimeout, draft.catalogTimeout, draft.executionTimeout)
            .filter { it.isNotBlank() }
            .forEach { if (it.trim().toLongOrNull()?.let { value -> value > 0 } != true) add(McpConfigProblem.NOT_A_POSITIVE_INTEGER) }
    }

    /** Whether the draft may be sent. */
    fun isReady(draft: McpServerDraft): Boolean = problems(draft).isEmpty()

    /**
     * The wire union, or `null` when the draft is not ready.
     *
     * `null` rather than a throw: the form's submit button is disabled while this returns null, and a
     * race between the check and the tap must not crash the app.
     */
    fun toConfig(draft: McpServerDraft): McpServerConfig? {
        if (!isReady(draft)) return null
        val timeout = McpTimeout(
            startup = draft.startupTimeout.trim().toLongOrNull(),
            catalog = draft.catalogTimeout.trim().toLongOrNull(),
            execution = draft.executionTimeout.trim().toLongOrNull(),
        )
        return when (draft.kind) {
            McpServerDraft.Kind.LOCAL -> McpServerConfig.Local(
                command = splitCommand(draft.command),
                cwd = draft.cwd.trim().takeIf { it.isNotEmpty() },
                environment = parsePairs(draft.environment),
                codemode = draft.codemode,
                timeout = timeout,
                protocol = draft.protocol,
            )

            McpServerDraft.Kind.REMOTE -> McpServerConfig.Remote(
                url = SafeNavigationUrl.parse(draft.url)!!,
                headers = parsePairs(draft.headers),
                codemode = draft.codemode,
                timeout = timeout,
                protocol = draft.protocol,
            )
        }
    }

    /**
     * Splits a command line.
     *
     * **Whitespace splitting, and deliberately not a shell parser.** The server runs this with an
     * argument vector (`Mcp.LocalConfigEncoded.command` is a list, not a string), so a quoted
     * argument would be passed *with* its quotes if this pretended to understand them. Splitting
     * plainly means what the user sees is exactly what runs, which is the property that matters for
     * a command that runs on their machine.
     */
    fun splitCommand(command: String): List<String> = command.split(' ', '\t', '\n')
        .filter { it.isNotBlank() }

    /**
     * Reads `KEY=value` lines into a map.
     *
     * A line with no `=`, or with an empty key, is skipped rather than sent as a key that is an
     * empty string — a header or environment variable with an empty name is a request the server
     * cannot use, and silently dropping it is better than a `400` for the whole server.
     */
    fun parsePairs(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1)
        }
        .filter { (key, _) -> key.isNotEmpty() }
        .toMap()

    /**
     * Whether [url] may be added as a well-known integration source.
     *
     * **The same rule as an OAuth Custom Tab URL, and for the same reason.** The route makes the
     * *server* fetch whatever is sent, so only an absolute http or https URL with a host is allowed
     * through. Exposed so a form can grey out its button rather than let the user press it and be
     * told no; the store still checks, because the store is reachable without a form.
     */
    fun isWellknownUrl(url: String): Boolean = dev.opencode.android.core.model.WellknownSourceUrl.parse(url) != null

    /**
     * A device-code-looking string in a command's output, which is what the copy button offers.
     *
     * **A pattern, not a protocol.** A device-code login prints something the user must carry to
     * another device, and this client cannot know which provider's format that is, so it looks for
     * the shape every one of them shares — a group of uppercase alphanumerics separated by dashes —
     * and offers to copy *the code itself*, not the line it was printed on. Copying the line would
     * put the label into the other device's field, which is the one thing that never works.
     */
    private val CODE = Regex("[A-Z0-9]{4}(?:-[A-Z0-9]{4,6}){2,}")

    /**
     * The device code in [output], or `null`.
     *
     * **The *last* match wins**, because a login that has already printed one code and then printed
     * another (a retry, or a provider that shows an example alongside the real one) has the real one
     * last. A line with nothing that looks like a code produces `null`, and the copy button is simply
     * absent — which is honest, because the server's own instructions still say what to do.
     */
    fun deviceCodeIn(output: String): String? = output.lineSequence()
        .mapNotNull { line -> CODE.find(line)?.value }
        .lastOrNull()
}
