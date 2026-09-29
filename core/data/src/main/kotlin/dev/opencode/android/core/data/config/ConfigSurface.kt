package dev.opencode.android.core.data.config

import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.action.toActionError
import dev.opencode.android.core.data.capability.CapabilityPolicy
import dev.opencode.android.core.data.capability.ExperimentalRoute
import dev.opencode.android.core.data.capability.RouteAvailability
import dev.opencode.android.core.data.integrations.ActionFailure
import dev.opencode.android.core.data.server.FileReader
import dev.opencode.android.core.data.server.ServerDataSet
import dev.opencode.android.core.model.ConfigEntry
import dev.opencode.android.core.model.InstructionEntry
import dev.opencode.android.core.model.LoadedLocation
import dev.opencode.android.core.model.MigrationStatus
import dev.opencode.android.core.model.SavedPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

/** One thing the phase reports, with the path or name it happened to. */
data class AdminFailure(
    val kind: ActionErrorKind,
    /** The server's own message, or a short explanation of the class. Never a document's content. */
    val message: String,
    val route: ExperimentalRoute? = null,
) {
    val needsRepair: Boolean get() = kind == ActionErrorKind.UNAUTHORIZED
}

/** What one write did, so the screen can say what changed rather than "saved". */
data class WriteOutcome(
    val target: String,
    val summary: String,
    val diagnostics: List<SchemaDiagnostic> = emptyList(),
    /** The rows that changed, for the "what is different now" list. */
    val changedKeys: List<String> = emptyList(),
)

/**
 * One server's configuration, saved approvals, instruction entries, loaded locations and migration
 * status.
 *
 * **This phase writes to the server's own filesystem, so the surface is built around three
 * rules.**
 *
 *  - **Nothing is written without a confirmation that names the file and the change.** The surface
 *    exposes `plan*` methods that *describe* a write and a `commit` method that performs it, so a
 *    screen physically cannot call the write without having built a plan first. [WritePlan] carries
 *    the path, the consequence and whether it is a privilege change, which is what the dialog renders
 *    and what `ConfirmedWriteTest` asserts on.
 *  - **A write is followed by a reload and by diagnostics from the server, not by a guess.** The
 *    features doc's own advice (§33.5) is to call `location.reload` and then read `config.get` again,
 *    and that is exactly what [writeAndReload] does: it writes, reloads, re-reads, and diffs the
 *    projections so a change that did not take effect is reported as such rather than as a success.
 *  - **Experimental availability is the server's answer, not the switch's.** [configUpdateUsable] and
 *    [instructionsUsable] each need both, which is plan §4.2's "detect capabilities with graceful
 *    degradation" and the reason a `404` here disables a button instead of showing an error.
 */
class ConfigSurface(
    private val admin: AdminApi,
    private val files: FileReader,
    private val schema: ConfigSchema,
) {

    // ------------------------------------------------------------------------------ capability

    private val configUpdateState = MutableStateFlow<RouteAvailability>(RouteAvailability.Unknown)
    private val instructionsState = MutableStateFlow<RouteAvailability>(RouteAvailability.Unknown)
    private val fsWriteState = MutableStateFlow<RouteAvailability>(RouteAvailability.Unknown)

    /** Whether this server has `experimental.config.update`. */
    val configUpdate: StateFlow<RouteAvailability> = configUpdateState.asStateFlow()

    /** Whether this server has `experimental.session.instructions.entry.*`. */
    val instructions: StateFlow<RouteAvailability> = instructionsState.asStateFlow()

    /**
     * Whether this server has `experimental.fs.write`.
     *
     * **Its own record, for the same reason P6 gated the review editor on the route and this phase
     * gates the definition editor on it.** The write is the one call in this phase that can put any
     * bytes anywhere, and a `404` from a server that has never heard of the route should grey the save
     * button rather than produce a failure after the user has typed a document.
     */
    val fsWrite: StateFlow<RouteAvailability> = fsWriteState.asStateFlow()

    /** Whether the shell setting may be offered: the user's switch and the route's answer. */
    fun configUpdateUsable(allowedBySetting: Boolean): Boolean =
        allowedBySetting && CapabilityPolicy.isUsable(configUpdateState.value)

    /** Whether the instruction-entry list may be offered. */
    fun instructionsUsable(allowedBySetting: Boolean): Boolean =
        allowedBySetting && CapabilityPolicy.isUsable(instructionsState.value)

    /** Whether a file write may be offered: the user's switch and the route's answer. */
    fun fsUsable(allowedBySetting: Boolean): Boolean =
        allowedBySetting && CapabilityPolicy.isUsable(fsWriteState.value)

    /**
     * Records what a failed experimental call said about its route.
     *
     * **Only a `404`/`405` changes the answer** — [CapabilityPolicy]'s rule, not this function's. A
     * `500` proves the route is there, and treating it as absence would grey out a working feature
     * because of a transient fault.
     */
    fun recordFailure(route: ExperimentalRoute, error: ActionError) {
        val availability = CapabilityPolicy.from(error) ?: return
        when (route) {
            ExperimentalRoute.CONFIG_UPDATE -> configUpdateState.value = availability
            ExperimentalRoute.SESSION_INSTRUCTIONS -> instructionsState.value = availability
            ExperimentalRoute.FS_WRITE -> fsWriteState.value = availability
            else -> Unit
        }
    }

    private fun recordSuccess(route: ExperimentalRoute) {
        val present = RouteAvailability.Present
        when (route) {
            ExperimentalRoute.CONFIG_UPDATE -> configUpdateState.value = present
            ExperimentalRoute.SESSION_INSTRUCTIONS -> instructionsState.value = present
            ExperimentalRoute.FS_WRITE -> fsWriteState.value = present
            else -> Unit
        }
    }

    // ------------------------------------------------------------------------------ reads

    /**
     * `config.get` for [directory], and the rows it produces.
     *
     * [facts] upgrades the rows from "reported by these documents" to "set by these documents", and
     * it is passed rather than read here because reading a file is a separate `fs.read` call with a
     * separate failure: a configuration file the app cannot read must not blank the explorer, which
     * the projections alone still answer completely for every key the server reports.
     */
    suspend fun documents(
        directory: String?,
        facts: Map<Int, ConfigFileFacts> = emptyMap(),
    ): ConfigDocuments = guarded { admin.getConfig(directory) }
        .map { ConfigDocuments(it, ConfigExplorer.rows(schema, it, facts)) }
        .getOrElse { ConfigDocuments(emptyList(), emptyList(), failure = it.classified()) }

    /**
     * The [ActionError] for a throwable that came out of a call.
     *
     * [ActionFailure] carries the classification every screen reports, and anything else is
     * classified from what it is — which is the same rule `IntegrationSurface.write` follows.
     */
    private fun Throwable.classified(): ActionFailure =
        (this as? ActionFailure) ?: ActionFailure(toActionError())

    /** The vendored schema, which every screen reads its key list and its validator from. */
    fun schema(): ConfigSchema = schema

    /**
     * Reads a configuration file's own text, for the editor and for a document's source facts.
     *
     * **A file that is not there is not an error.** A project's `opencode.jsonc` may simply not
     * exist, and the editor's job is to offer to create it; [ConfigEditorState.missing] is that
     * answer. Every other failure is reported, because a file that exists and cannot be read is a
     * different situation the user needs to know about.
     */
    suspend fun readFile(directory: String?, path: String): ConfigFileRead {
        val result = files.read(directory, path)
        val failure = result.exceptionOrNull()
        if (failure == null) return ConfigFileRead.Found(result.getOrThrow())
        val error = failure.classified().error
        return if (error.kind == ActionErrorKind.NOT_FOUND) {
            ConfigFileRead.Missing(error)
        } else {
            ConfigFileRead.Failed(error)
        }
    }

    // ------------------------------------------------------------------------------ shell setting

    /**
     * `experimental.config.update`, which writes the **global** `shell`.
     *
     * **A blank shell is refused rather than sent.** The route's body is `{"shell": "…"}` with
     * `shell` required and no `null` branch, so an empty string is the only way to send "unset" and
     * the server would store a configuration with an empty shell — which the terminal and the `bash`
     * tool would then try to run. Clearing the setting from a phone is a text edit of the global file,
     * which is what the editor is for.
     */
    suspend fun setShell(shell: String): Result<Unit> {
        val trimmed = shell.trim()
        if (trimmed.isEmpty()) {
            return failure(
                ActionError(ActionErrorKind.INVALID_REQUEST, "A shell path is required"),
            )
        }
        return experimental(ExperimentalRoute.CONFIG_UPDATE) {
            admin.patchConfig(trimmed)
        }
    }

    // ------------------------------------------------------------------------------ maintenance

    /** `location.reload`, and then the same `config.get` the caller already has, for a diff. */
    suspend fun reloadLocations(): Result<Unit> = write { admin.reloadLocations() }

    /** `debug.location.list`. */
    suspend fun loadedLocations(): Result<List<LoadedLocation>> = write { admin.listLoadedLocations() }

    /**
     * `debug.location.evict`.
     *
     * **The caller confirms first, naming the directory.** Evicting drops the location's cached
     * services; they are rebuilt on next use, so nothing is lost, but work in flight for that
     * location is interrupted, and a user who taps the wrong row has no undo. [AdminFailure] carries
     * the route so a `404` is a capability answer rather than an error banner.
     */
    suspend fun evictLocation(directory: String): Result<Unit> = write { admin.evictLocation(directory) }

    /** `experimental.migration.v1.status`. */
    suspend fun migrationStatus(): Result<MigrationStatus> = write { admin.migrationStatus() }

    // ------------------------------------------------------------------------------ saved permissions

    /** `permission.saved.list`, optionally narrowed to one project. */
    suspend fun savedPermissions(projectID: String? = null): Result<List<SavedPermission>> =
        write { admin.listSavedPermissions(projectID) }

    /**
     * `permission.saved.remove`, after the caller has confirmed.
     *
     * **Removing is a privilege change in the other direction**, so it is called only from a confirmed
     * action: the tool that was allowed once will ask again for that exact action and resource.
     */
    suspend fun removeSavedPermission(id: String): Result<Unit> = write { admin.removeSavedPermission(id) }

    // ------------------------------------------------------------------------------ instructions

    /** `experimental.session.instructions.entry.list`. */
    suspend fun instructionEntries(sessionID: String): Result<List<InstructionEntry>> =
        experimental(ExperimentalRoute.SESSION_INSTRUCTIONS) { admin.listInstructionEntries(sessionID) }

    /** `experimental.session.instructions.entry.put`. */
    suspend fun putInstructionEntry(sessionID: String, key: String, value: String): Result<Unit> =
        experimental(ExperimentalRoute.SESSION_INSTRUCTIONS) {
            admin.putInstructionEntry(sessionID, key, value)
        }

    /** `experimental.session.instructions.entry.remove`. */
    suspend fun removeInstructionEntry(sessionID: String, key: String): Result<Unit> =
        experimental(ExperimentalRoute.SESSION_INSTRUCTIONS) {
            admin.removeInstructionEntry(sessionID, key)
        }

    // ------------------------------------------------------------------------------ file writes

    /**
     * The plan for a change the **server** makes rather than one the app writes.
     *
     * **`shell` is the only such setting** (`Config.Patch` is `additionalProperties: false`), and its
     * target is the global configuration file rather than the location's — which is why the plan is
     * built with an explicit [target] and cannot default to the editor's path. The shape is a
     * [WritePlan] so the confirmation dialog is one composable: a target, a consequence and the flag
     * that leads the dialog when the change is a privilege one.
     */
    fun planSetting(
        target: String,
        consequence: String,
        isPrivilegeChange: Boolean,
    ): WritePlan = WritePlan(
        target = target,
        // No document, deliberately. A setting the *server* applies has no bytes for this app to write,
        // and the confirmation's sentence already names the new value — so a plan that carried one would
        // be a value with no use and one more place a credential could be printed from.
        text = "",
        consequence = consequence,
        isPrivilegeChange = isPrivilegeChange,
        bytes = 0,
        previousBytes = null,
    )

    /**
     * Builds the plan for writing [text] to [path], without writing it.
     *
     * **The plan is where the confirmation's words come from.** It carries the path, the new text's
     * byte length, what the change does and whether it is a privilege change, and it validates the
     * document first so the dialog cannot be reached with a file the server will refuse. A caller
     * that gets `null` has nothing to confirm.
     *
     * @param validate a validator for this kind of file. `null` for a Markdown definition, whose
     *   validity is the server's business rather than a schema's.
     */
    fun planFileWrite(
        path: String,
        text: String,
        consequence: String,
        isPrivilegeChange: Boolean,
        existing: String?,
        validate: ((JsonElement) -> List<SchemaDiagnostic>)? = null,
    ): WritePlan? {
        val problems = validate?.let { validator ->
            when (val parsed = ConfigDocument.parse(text)) {
                is ParsedDocument.Failed -> listOf(
                    SchemaDiagnostic(
                        path = "",
                        keyword = "syntax",
                        expected = "a readable JSON document",
                        found = parsed.failure.reason,
                        line = parsed.failure.line,
                        column = parsed.failure.column,
                    ),
                )

                is ParsedDocument.Parsed -> validator(parsed.document)
            }
        }.orEmpty()
        if (problems.isNotEmpty()) return null
        return WritePlan(
            target = path,
            text = text,
            consequence = consequence,
            isPrivilegeChange = isPrivilegeChange,
            bytes = text.toByteArray(Charsets.UTF_8).size,
            previousBytes = existing?.toByteArray(Charsets.UTF_8)?.size,
            diagnostics = problems,
        )
    }

    /**
     * Performs [plan] and then reads the server's own answer back.
     *
     * **Write, read back, reload, read again — in that order, and each step is load-bearing.**
     * The read-back is [FileReader.write]'s own behaviour and catches a write that reported success and
     * left different bytes; the reload makes the server re-read the file rather than waiting for its
     * watcher, so what the user sees next is the change they made; the second `config.get` is the
     * diagnostic the plan asks for, because features doc §33.5 says to show `config.get` diagnostics
     * after a write and the server's answer is the only trustworthy one.
     *
     * @param keys the top-level keys [plan] touched, for the "is it effective" diagnostic.
     */
    suspend fun commit(
        plan: WritePlan,
        directory: String?,
        keys: Set<String> = emptySet(),
        reload: Boolean = true,
    ): Result<WriteOutcome> {
        val written = files.write(directory, plan.target, plan.text)
        val readFailure = written.exceptionOrNull()
        if (readFailure != null) {
            val error = readFailure.classified().error
            CapabilityPolicy.from(error)?.let { fsWriteState.value = it }
            return failure(error)
        }
        fsWriteState.value = RouteAvailability.Present
        val readBack = written.getOrThrow()
        if (reload) reloadLocations()
        val report = reloadReport(directory, plan.target, keys)
        val bytes = plan.text.toByteArray(Charsets.UTF_8).size
        return Result.success(
            WriteOutcome(
                target = plan.target,
                summary = when {
                    !report.asked -> "${plan.target} was written and read back, but the server could not be asked whether it is using it"
                    report.diagnostics.any { it.keyword == "source" } ->
                        "${plan.target} was written, but the server is not reading it"
                    readBack.bytes.size != bytes ->
                        "${plan.target} was written, and the server returned ${readBack.bytes.size} of $bytes bytes"
                    else -> "${plan.target} was written and the server is using it"
                },
                diagnostics = report.diagnostics,
                changedKeys = keys.toList(),
            ),
        )
    }

    /**
     * What the server thinks of the configuration after a reload.
     *
     * **`config.get` is asked again and the answer is compared with the write.** The three questions
     * that matter are asked in order of how often they are the answer:
     *
     *  1. **Is the file the app wrote one of the files the server reads?** A path outside the
     *     precedence chain — `.opencode/opencode.jsonc` in the wrong directory, a home-relative path
     *     the server does not resolve — is written successfully and then ignored, and no error says so.
     *     This is the diagnostic that catches it, and it is why the write report is a `config.get`
     *     comparison and not just "saved".
     *  2. **Does the server report any document at all?** An empty list means the whole file is
     *     invisible, which is the same problem in a stronger form.
     *  3. **Is the key that was set in the effective projection?** A key the server does not project
     *     is not a failure — eleven of the thirty-six keys are unprojected by design — so this is only
     *     asked for a key the projection does report.
     */
    suspend fun reloadReport(directory: String?, writtenTo: String, keys: Set<String>): ReloadReport {
        val asked = guarded { admin.getConfig(directory) }
        val failure = asked.exceptionOrNull()
        if (failure != null) return ReloadReport(
            asked = false,
            diagnostics = listOf(
                SchemaDiagnostic(
                    path = "",
                    keyword = "reload",
                    expected = "the server to re-read its configuration",
                    found = "it could not be asked",
                ),
            ),
        )
        val entries = asked.getOrThrow()
        val documents = entries.filterIsInstance<ConfigEntry.Document>()
        val effective = documents.lastOrNull()?.info
        val problems = mutableListOf<SchemaDiagnostic>()

        if (documents.isEmpty()) {
            problems += SchemaDiagnostic(
                path = "",
                keyword = "reload",
                expected = "at least one configuration document",
                found = "the server is reading none",
            )
        } else if (documents.none { sameFile(it.path, writtenTo) }) {
            problems += SchemaDiagnostic(
                path = "",
                keyword = "source",
                expected = "a configuration document at $writtenTo",
                found = "the server reads ${documents.mapNotNull { it.path }.joinToString(", ").ifEmpty { "no path" }}",
            )
        }

        keys.forEach { key ->
            if (effective != null && effective.projects(key) && effective.projected(key) == null) {
                problems += SchemaDiagnostic(
                    path = "/$key",
                    keyword = "effective",
                    expected = "the server to report a value for $key",
                    found = "it reports the key as unset",
                )
            }
        }
        return ReloadReport(asked = true, diagnostics = problems)
    }

    // ------------------------------------------------------------------------------ internals

    /**
     * Whether two paths name the same file.
     *
     * **Compared by suffix, because the two sides are spelled differently.** `config.get` reports the
     * paths the server resolved — absolute, with its own home substituted — while the app writes with
     * a path built from the location's directory. Requiring exact equality would report "your file is
     * not read" for a file that is, which is worse than useless on a screen whose whole job is to say
     * whether the change took.
     */
    private fun sameFile(reported: String?, written: String): Boolean {
        if (reported == null) return false
        if (reported == written) return true
        val right = written.trimEnd('/').substringAfterLast('/')
        return reported == right || reported.endsWith("/$right")
    }

    private suspend inline fun <T> guarded(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        failure(error.toActionError())
    }

    /** A call that also records what it said about its experimental route. */
    private suspend inline fun <T> experimental(
        route: ExperimentalRoute,
        crossinline block: suspend () -> T,
    ): Result<T> {
        val result = guarded { block() }
        result.exceptionOrNull()?.let { error ->
            recordFailure(route, (error as? ActionFailure)?.error ?: error.toActionError())
        } ?: recordSuccess(route)
        return result
    }

    private suspend inline fun <T> write(crossinline block: suspend () -> T): Result<T> =
        guarded { block() }

    private fun <T> failure(error: ActionError): Result<T> = Result.failure(ActionFailure(error))

    companion object {
        /** Only so the tests can name it; nothing reads it. */
        internal const val SERVER_ID_PLACEHOLDER = ""
    }
}

/** What the server said when it was asked to re-read its configuration. */
data class ReloadReport(
    /** Whether the server answered at all. A `false` here is itself the diagnostic. */
    val asked: Boolean,
    val diagnostics: List<SchemaDiagnostic>,
)

/** What `config.get` returned, and the rows it produces. */
data class ConfigDocuments(
    val entries: List<ConfigEntry>,
    val rows: List<ConfigRow>,
    /** Set when the read failed; the rows are then empty and the screen shows the class. */
    val failure: ActionFailure? = null,
) {
    val documents: List<ConfigEntry.Document> get() = entries.filterIsInstance<ConfigEntry.Document>()

    /** The directories the server searched, which is how a wrong file location is explained. */
    val searched: List<String> get() = entries.mapNotNull { (it as? ConfigEntry.Directory)?.pathOrNull }

    /** The rows that set anything or that the server reports. */
    val configured: List<ConfigRow> get() = ConfigExplorer.configured(rows)
}

/** The result of reading one configuration or definition file. */
sealed interface ConfigFileRead {
    /** The bytes, classified by [FileReader] as text for every file this phase edits. */
    data class Found(val file: dev.opencode.android.core.data.composer.FileReadResult) : ConfigFileRead

    /** The file does not exist, which is the editor's "create it" case rather than a failure. */
    data class Missing(val error: ActionError) : ConfigFileRead

    /** The file exists and could not be read. */
    data class Failed(val error: ActionError) : ConfigFileRead

    /** The text, or `null` when there is none. */
    val text: String? get() = (this as? Found)?.file?.text
}

/**
 * A write that has been described and validated and is waiting for a confirmation.
 *
 * **Everything a confirmation needs and nothing else**, so the dialog cannot render a write it does
 * not fully understand: the path, the size before and after, what changes, whether it changes what
 * the agent may do, and the diagnostics — which are empty, because [ConfigSurface.planFileWrite]
 * returns `null` when there are any.
 */
data class WritePlan(
    val target: String,
    val text: String,
    val consequence: String,
    val isPrivilegeChange: Boolean,
    val bytes: Int,
    val previousBytes: Int?,
    val diagnostics: List<SchemaDiagnostic> = emptyList(),
) {
    /** Whether the file did not exist before, which the confirmation says outright. */
    val isNewFile: Boolean get() = previousBytes == null
}

/** The one failure type this phase reports, so a screen never renders a raw exception. */
fun Throwable.toAdminFailure(route: ExperimentalRoute? = null): AdminFailure {
    val error = toActionError()
    return AdminFailure(kind = error.kind, message = error.message, route = route)
}
