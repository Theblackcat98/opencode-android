package dev.opencode.android.core.data.config

import dev.opencode.android.core.model.ConfigEntry
import dev.opencode.android.core.model.ConfigPatchRequest
import dev.opencode.android.core.model.InstructionEntry
import dev.opencode.android.core.model.InstructionEntryRequest
import dev.opencode.android.core.model.LoadedLocation
import dev.opencode.android.core.model.MigrationStatus
import dev.opencode.android.core.model.SavedPermission

/**
 * The reads and writes of `feature/admin`, in one place.
 *
 * **Every method is a thin, named wrapper rather than a `ServerApi` call at a call site.** A screen
 * that reached for `api.getConfig` directly would be one refactor away from dropping the location
 * parameter, and a configuration request without `location[directory]` is answered about the server's
 * working directory — a different checkout, with a different configuration.
 */
interface AdminApi {

    /** `config.get`: the documents and discovery sources for a location, lowest precedence first. */
    suspend fun getConfig(directory: String?): List<ConfigEntry>

    /** `experimental.config.update`: sets the global `shell`. */
    suspend fun patchConfig(shell: String): Unit

    /** `location.reload`: shuts down and rebuilds every loaded location. */
    suspend fun reloadLocations(): Unit

    /** `permission.saved.list`, optionally narrowed to one project. */
    suspend fun listSavedPermissions(projectID: String?): List<SavedPermission>

    /** `permission.saved.remove`. */
    suspend fun removeSavedPermission(id: String): Unit

    /** `experimental.session.instructions.entry.list`. */
    suspend fun listInstructionEntries(sessionID: String): List<InstructionEntry>

    /** `experimental.session.instructions.entry.put`. */
    suspend fun putInstructionEntry(sessionID: String, key: String, value: String): Unit

    /** `experimental.session.instructions.entry.remove`. */
    suspend fun removeInstructionEntry(sessionID: String, key: String): Unit

    /** `debug.location.list`: the locations the server currently holds. */
    suspend fun listLoadedLocations(): List<LoadedLocation>

    /** `debug.location.evict`: drops one location's cached services. */
    suspend fun evictLocation(directory: String): Unit

    /** `experimental.migration.v1.status`. */
    suspend fun migrationStatus(): MigrationStatus
}
