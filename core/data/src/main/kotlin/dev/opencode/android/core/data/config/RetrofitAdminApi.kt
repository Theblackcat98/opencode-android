package dev.opencode.android.core.data.config

import dev.opencode.android.core.model.ConfigEntry
import dev.opencode.android.core.model.ConfigPatchRequest
import dev.opencode.android.core.model.InstructionEntry
import dev.opencode.android.core.model.InstructionEntryRequest
import dev.opencode.android.core.model.LoadedLocation
import dev.opencode.android.core.model.MigrationStatus
import dev.opencode.android.core.model.SavedPermission
import dev.opencode.android.core.network.ServerApi

/**
 * [AdminApi] over a [ServerApi].
 *
 * **Every method here is a wrapper for the same reason the interface exists:** a call site that reached
 * for the retrofit method directly would be one refactor away from dropping the `location[directory]`
 * parameter, and a configuration request without one is answered about the server's working directory
 * — a different checkout with a different configuration.
 *
 * The instruction-entry value is parsed here rather than in a screen, because the spec's body accepts
 * any JSON and a screen's text field produces a string: `{"value": "ask"}` for `ask` and
 * `{"value": {"a": 1}}` for an object both have to come out of one field.
 */
class RetrofitAdminApi(private val api: ServerApi) : AdminApi {

    override suspend fun getConfig(directory: String?): List<ConfigEntry> = api.getConfig(directory)

    override suspend fun patchConfig(shell: String): Unit = api.updateConfig(ConfigPatchRequest(shell))

    override suspend fun reloadLocations(): Unit = api.reloadLocations()

    override suspend fun listSavedPermissions(projectID: String?): List<SavedPermission> =
        api.listSavedPermissions(projectID).data

    override suspend fun removeSavedPermission(id: String): Unit = api.removeSavedPermission(id)

    override suspend fun listInstructionEntries(sessionID: String): List<InstructionEntry> =
        api.listInstructionEntries(sessionID).data

    override suspend fun putInstructionEntry(sessionID: String, key: String, value: String): Unit =
        api.putInstructionEntry(
            sessionID = sessionID,
            key = key,
            body = InstructionEntryRequest(ConfigValues.parse(value)),
        )

    override suspend fun removeInstructionEntry(sessionID: String, key: String): Unit =
        api.removeInstructionEntry(sessionID, key)

    override suspend fun listLoadedLocations(): List<LoadedLocation> = api.listLoadedLocations()

    override suspend fun evictLocation(directory: String): Unit = api.evictLocation(directory)

    override suspend fun migrationStatus(): MigrationStatus = api.getMigrationStatus()
}

/**
 * How a text field's contents become an instruction entry's JSON value.
 *
 * **A bare word is a string, and anything that parses is used as parsed.** The spec's body is
 * `{"value": …}` with no type, and an entry is useful with an object or a list ("these tools are
 * disabled for this session"), which a phone keyboard cannot type into a typed field. So the field
 * takes text and [parse] decides: valid JSON is used verbatim, and anything else — including a bare
 * `ask` — is a string. A user who types `ask` gets the string `"ask"`, and one who types
 * `{"ask": true}` gets the object, which is the useful reading of both.
 */
object ConfigValues {

    /** The JSON value for [text]. */
    fun parse(text: String) = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(text)
    }.getOrElse { kotlinx.serialization.json.JsonPrimitive(text) }

    /** Whether [text] parses as JSON, so a screen can say so before a `413` or a `400`. */
    fun isJson(text: String): Boolean = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(text)
    }.isSuccess

    /**
     * A JSON value as text, for a field to show.
     *
     * **A string comes back bare, not quoted.** `"ask"` shown as `"ask"` in a text field invites the
     * user to add a second pair of quotes, and the result is a string that contains quotes.
     */
    fun toText(value: kotlinx.serialization.json.JsonElement): String =
        if (value is kotlinx.serialization.json.JsonPrimitive && value.isString) value.content else value.toString()
}
