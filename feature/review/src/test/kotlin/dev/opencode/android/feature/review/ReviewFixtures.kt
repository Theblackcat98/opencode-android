package dev.opencode.android.feature.review

import dev.opencode.android.core.data.review.ParsedFile
import dev.opencode.android.core.data.review.ReviewScope
import dev.opencode.android.core.model.CommandInfo
import dev.opencode.android.core.model.FileDiff
import dev.opencode.android.core.model.FileDiffStatus
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.Project
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.SkillInfo
import dev.opencode.android.core.model.TokenUsage
import dev.opencode.android.core.model.VcsBranch
import dev.opencode.android.core.model.VcsFileStatus
import dev.opencode.android.core.model.VcsInfo
import kotlinx.serialization.json.Json

/**
 * Fixtures the review's own tests share.
 *
 * Written as literal JSON rather than as model objects, because the point of most of these tests is
 * that the *wire shape* the server sends decodes into the state the screen draws. A fixture built
 * from the model would agree with the model by construction and prove nothing about the contract.
 */
object ReviewFixtures {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    const val DIRECTORY = "/home/dev/project"

    val session = SessionInfo(
        id = "ses_1",
        projectID = "prj_1",
        cost = 0.0,
        tokens = TokenUsage(0, 0, 0, TokenUsage.Cache(0, 0)),
        time = SessionInfo.Time(created = 1, updated = 2),
        location = LocationPublicRef(directory = DIRECTORY),
    )

    /** One file's patch, as `session.diff` and `vcs.diff` answer it. */
    val singleFilePatch = listOf(
        FileDiff(
            file = "$DIRECTORY/src/main/kotlin/A.kt",
            patch = "@@ -1,3 +1,4 @@\n package a\n-val x = 1\n+val x = 2\n+val y = 3\n",
            additions = 2,
            deletions = 1,
            status = FileDiffStatus.Modified,
        ),
    )

    fun diffEnvelope(files: List<FileDiff>): String =
        """{"data":${json.encodeToString(files)}}"""

    fun vcsDiffEnvelope(files: List<FileDiff>): String =
        """{"location":{"id":"loc","directory":"$DIRECTORY"},"data":${json.encodeToString(files)}}"""

    /**
     * `vcs.get`'s answer, written by hand.
     *
     * A `null` branch or default is a real state the server reports — a detached HEAD, or a directory
     * that is not a repository at all — and the header has to be able to say so rather than invent a
     * branch name, so both are constructible here.
     */
    fun vcsInfo(provider: String? = "git", current: String? = "feature/review", default: String? = "main"): String {
        val branch = "{\"current\":${current.q()},\"default\":${default.q()}}"
        val body = if (provider == null) """{"branch":$branch}""" else """{"provider":"$provider","branch":$branch}"""
        return """{"location":{"id":"loc","directory":"$DIRECTORY"},"data":$body}"""
    }

    private fun String?.q(): String = if (this == null) "null" else "\"$this\""

    fun vcsStatus(vararg entries: VcsFileStatus): String =
        """{"location":{"id":"loc","directory":"$DIRECTORY"},"data":${json.encodeToString(entries.toList())}}"""

    val status =
        VcsFileStatus(file = "src/main/kotlin/A.kt", additions = 2, deletions = 1, status = FileDiffStatus.Modified)

    /** A `FileDiff` whose patch is binary, which is a real answer for an image. */
    val binary =
        FileDiff(file = "assets/logo.png", patch = "", additions = 0, deletions = 0, status = FileDiffStatus.Added)

    /** The model types a session projection needs, kept here so the tests do not repeat them. */
    val project = Project(
        id = "prj_1",
        canonical = DIRECTORY,
        vcs = "git",
        time = Project.Time(created = 1, updated = 2, active = 3),
        sandboxes = emptyList(),
    )
    val command = CommandInfo(name = "review", description = "review the diff")
    val skill = SkillInfo(id = "review", name = "review", path = "/skills/review.md", content = "# review")
}
