plugins {
    alias(libs.plugins.opencode.android.library)
}

// Room (servers, cached sessions and messages, drafts, favorites, stash) arrives with its first
// table in P1; the `opencode.android.room` convention plugin is ready for it.
dependencies {
    api(projects.core.model)
}
