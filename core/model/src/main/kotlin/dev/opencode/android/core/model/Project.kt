package dev.opencode.android.core.model

import kotlinx.serialization.Serializable

/** A project: the locations that share a repository (schema `Project`). */
@Serializable
data class Project(
    val id: String,
    val canonical: String,
    val vcs: String? = null,
    val name: String? = null,
    val icon: Icon? = null,
    val commands: Commands? = null,
    val time: Time,
    /** Worktree directories. */
    val sandboxes: List<String>,
) {
    @Serializable
    data class Icon(
        val url: String? = null,
        val override: String? = null,
        val color: String? = null,
    )

    @Serializable
    data class Commands(val start: String? = null)

    @Serializable
    data class Time(
        val created: Long,
        val updated: Long,
        val active: Long,
    )
}
