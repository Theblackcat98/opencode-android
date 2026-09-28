plugins {
    alias(libs.plugins.opencode.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// OkHttp, auth, Retrofit APIs, EventStreamClient (SSE) and PtySocket arrive in P1 and P7.
dependencies {
    api(projects.core.model)

    testImplementation(projects.core.testing)
}
