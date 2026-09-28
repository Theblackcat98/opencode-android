package dev.opencode.android.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/vcs` (schema `Vcs.Info`): the provider and the branch the working copy is on.
 *
 * [branch] is required and both of its fields are optional, which is the server saying that a
 * detached HEAD has neither a current nor a default branch to report. That is a real state (a
 * rebase in progress, a fresh clone checked out at a tag) and the header has to be able to say so
 * rather than invent a branch name.
 */
@Serializable
data class VcsInfo(
    /** `git`, or whatever the server detected; absent when the directory is not a repository. */
    val provider: String? = null,
    val branch: VcsBranch = VcsBranch(),
)

/** Schema `Vcs.Branch`: the current and the default branch. Both may be absent. */
@Serializable
data class VcsBranch(
    val current: String? = null,
    val default: String? = null,
)

/**
 * `GET /api/vcs/base` (schema `Vcs.Base`): the ref the review diffs are taken against.
 *
 * [source] says where the answer came from, and it is worth keeping: `reflog` means the server
 * inferred it from the branch's history and `default` means it used the repository's default
 * branch, and a user comparing two projects needs to know which of the two they are looking at.
 */
@Serializable
data class VcsBase(
    val name: String,
    val ref: String,
    val source: VcsBaseSource,
)

/** Where `vcs.base` inferred the ref from (schema `Vcs.Base.source`). */
@Serializable
@JvmInline
value class VcsBaseSource(val value: String) {
    companion object {
        val Reflog = VcsBaseSource("reflog")
        val Default = VcsBaseSource("default")
    }
}

/**
 * `GET /api/vcs/status` (schema `Vcs.FileStatus`), one entry per file the working copy has changed.
 *
 * The counts are the server's, computed from its own diff, and they are what a file tree shows
 * before the patch is fetched. They are therefore kept beside the file rather than recomputed from
 * the patch: a fetch that has not happened yet still has to label the file.
 */
@Serializable
data class VcsFileStatus(
    val file: String,
    val additions: Int,
    val deletions: Int,
    val status: FileDiffStatus,
)

/**
 * `GET /api/experimental/session/{id}/export` (schema `SessionTransfer.Data`): a session and its
 * whole transcript, which is also exactly what the import route takes back.
 */
@Serializable
data class SessionTransfer(
    val info: SessionInfo,
    val messages: List<SessionMessage>,
)

/**
 * `POST /api/experimental/session/import` (schema `SessionImportRequest`).
 *
 * Parents before children, and a session the server already knows is rejected, so an import
 * appends to the server's history rather than merging into it. [location] decides which project the
 * imported transcript belongs to when its own `location.directory` is not usable on this server.
 */
@Serializable
data class SessionImportRequest(
    val info: SessionInfo,
    val messages: List<SessionMessage>,
    val location: LocationPublicRef? = null,
)

/** `POST /api/experimental/fs/write` (schema `FileSystem.Write`): the path that was written. */
@Serializable
data class FileSystemWrite(
    val path: String,
)
