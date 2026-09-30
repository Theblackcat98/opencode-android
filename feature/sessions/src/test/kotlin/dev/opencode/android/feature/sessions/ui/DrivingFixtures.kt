package dev.opencode.android.feature.sessions.ui

import dev.opencode.android.core.data.server.PendingRequest
import dev.opencode.android.core.data.timeline.PendingInboxItem
import dev.opencode.android.core.model.AgentInfo
import dev.opencode.android.core.model.Delivery
import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.FormOption
import dev.opencode.android.core.model.InboxItem
import dev.opencode.android.core.model.ModelInfo
import dev.opencode.android.core.model.ModelRef
import dev.opencode.android.core.model.PermissionRequest
import dev.opencode.android.core.model.PermissionSource
import dev.opencode.android.core.model.UserPromptPayload
import dev.opencode.android.feature.composer.ui.ComposerUiState
import dev.opencode.android.feature.composer.ui.LocationChoice
import dev.opencode.android.feature.composer.ui.NewSessionUiState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Phase 3 shapes the screenshots draw: the composer, a request dock with a question and a
 * permission, the two other form presentations, a retry banner with a provider's call to action, and
 * the new-session form.
 *
 * Separate from [TimelineFixtures] because these come from the *write* side of the protocol, and
 * mixing them into the transcript fixtures would make it unclear which object a screenshot is
 * showing. Built from the same models the server decodes into, as those are.
 */
object DrivingFixtures {

    /** A composer with text, queue mode and a running turn: the busy case. */
    fun composerState(): ComposerUiState = ComposerUiState(
        sessionID = "ses_1",
        text = "And then explain why the reconnect dropped it.",
        delivery = Delivery.Queue,
        agent = "build",
        model = ModelRef("text", "fake", "default"),
        // The catalogs, so the baseline shows the cycle buttons the plan asks for: without an
        // agent and a model to cycle through there is nothing for them to do.
        agents = listOf(
            AgentInfo(
                id = "build",
                name = "build",
                description = "Implements and verifies a change end to end.",
                mode = "primary",
                color = "#3B82F6",
            ),
            AgentInfo(
                id = "plan",
                name = "plan",
                description = "Researches a task and writes an implementation plan.",
                mode = "primary",
                color = "#10B981",
            ),
        ),
        models = listOf(
            ModelInfo(
                id = "text",
                modelID = "text",
                providerID = "fake",
                name = "Fake text",
                variants = listOf(
                    ModelInfo.Variant("low"),
                    ModelInfo.Variant("default"),
                    ModelInfo.Variant("high"),
                ),
                cost = listOf(ModelInfo.Cost(input = 3.0, output = 12.0)),
                limit = ModelInfo.Limit(context = 200_000, output = 4_096),
            ),
        ),
        hasAnyModel = true,
        busy = true,
        pending = listOf(
            PendingInboxItem(
                id = "msg_pending",
                created = 1_700_000_000_000L,
                item = InboxItem.User(
                    UserPromptPayload("And then explain why the reconnect dropped it."),
                    Delivery.Queue,
                ),
            ),
        ),
        directory = "/home/dev/opencode-android",
    )

    /** A shell permission as the request center holds it, with the patterns "always" would store. */
    fun permissionRequest(): PermissionRequest = PermissionRequest(
        id = "per_1",
        sessionID = "ses_1",
        action = "shell",
        resources = listOf("git push --force origin main"),
        save = listOf("git push *"),
        source = PermissionSource(PermissionSource.TOOL, "msg_assistant", "call_shell_1"),
        message = "The agent wants to push a branch to the remote.",
    )

    /**
     * A permission request raised through `session.permission.create` by a plugin: no `source`, and the title
     * the caller gave it. This is what the dev server answered in manual test H11.
     */
    fun pluginPermissionRequest(): PermissionRequest = PermissionRequest(
        id = "per_2",
        sessionID = "ses_1",
        action = "external_directory",
        resources = listOf("/etc/hosts"),
        save = listOf("/etc/*"),
        metadata = JsonObject(mapOf("title" to JsonPrimitive("Read the hosts file for a network check"))),
    )

    /** A `question` form exactly as the server sends it: one field per question, custom allowed. */
    fun questionForm(): FormInfo = FormInfo(
        id = "frm_1",
        sessionID = "ses_1",
        title = "Questions",
        metadata = mapOf(
            "kind" to JsonPrimitive("question"),
            "tool" to JsonObject(
                mapOf(
                    "messageID" to JsonPrimitive("msg_assistant"),
                    "id" to JsonPrimitive("call_question_1"),
                ),
            ),
        ),
        fields = listOf(
            FormField.StringField(
                key = "q0",
                title = "Shell",
                description = "Which shell should the test run under?",
                options = listOf(
                    FormOption(value = "bash", label = "bash", description = "Run bash"),
                    FormOption(value = "sh", label = "sh", description = "Run sh"),
                ),
                custom = true,
            ),
        ),
    )

    /** An MCP elicitation, which names the server and carries a field of every type at once. */
    fun elicitationForm(): FormInfo = FormInfo(
        id = "frm_2",
        sessionID = "ses_1",
        title = "MCP input",
        metadata = mapOf(
            "kind" to JsonPrimitive("mcp-elicitation"),
            "server" to JsonPrimitive("docs-server"),
            "message" to JsonPrimitive("The documentation server needs a scope to continue."),
        ),
        fields = listOf(
            FormField.StringField(
                key = "scope",
                title = "Scope",
                required = true,
                pattern = "^[a-z:]+$",
                placeholder = "docs:read",
            ),
            FormField.BooleanField(key = "remember", title = "Remember for this session", default = true),
            FormField.NumberField(
                key = "limit",
                title = "Page size",
                minimum = 1.0,
                maximum = 100.0,
                default = 20.0,
                integer = true,
            ),
            FormField.MultiselectField(
                key = "formats",
                title = "Formats",
                options = listOf(
                    FormOption(value = "markdown", label = "Markdown"),
                    FormOption(value = "html", label = "HTML"),
                ),
                minItems = 1,
            ),
            FormField.ExternalField(
                key = "docs",
                title = "Read the setup guide",
                url = "https://example.invalid/setup",
            ),
        ),
    )

    /** A web-search consent dialog, which is the third presentation of the same mechanism. */
    fun consentForm(): FormInfo = FormInfo(
        id = "frm_3",
        sessionID = "ses_1",
        title = "Web search",
        metadata = mapOf("kind" to JsonPrimitive("websearch.provider")),
        fields = listOf(
            FormField.MultiselectField(
                key = "providers",
                title = "Providers",
                required = true,
                options = listOf(
                    FormOption(value = "builtin", label = "Built-in"),
                    FormOption(value = "placeholder-search", label = "Exa"),
                ),
            ),
        ),
    )

    /** What a session's dock shows when both a question and a permission are waiting. */
    fun dockRequests(): List<PendingRequest> = listOf(
        PendingRequest.Form(questionForm()),
        PendingRequest.Permission(permissionRequest()),
    )

    /** The retry banner, with the provider's own call to action and a link. */
    fun retryBanner() = RetryUi(
        attempt = 3,
        next = 1_700_000_004_000L,
        message = "The provider rejected the request: usage exceeded for this key.",
        action = ProviderActionUi(
            reason = "usage_exceeded",
            provider = "fake",
            title = "Usage exceeded",
            message = "This key has used its monthly allowance. Add credit or use another key.",
            label = "Open billing",
            link = "https://example.invalid/billing",
        ),
    )

    /** The new-session form with a project chosen, which is the state its catalogs load into. */
    fun newSessionState(): NewSessionUiState = NewSessionUiState(
        title = "Phase 3",
        location = LocationChoice.ProjectChoice(
            "project-1",
            "opencode-android",
            "/home/dev/opencode-android",
        ),
        agent = "build",
        model = ModelRef("text", "fake"),
        models = listOf(
            ModelInfo(
                id = "text",
                modelID = "text",
                providerID = "fake",
                name = "Fake text",
                capabilities = ModelInfo.Capabilities(tools = true, input = listOf("text", "image")),
                variants = listOf(
                    ModelInfo.Variant("low"),
                    ModelInfo.Variant("default"),
                    ModelInfo.Variant("high"),
                ),
                cost = listOf(ModelInfo.Cost(input = 3.0, output = 12.0)),
                limit = ModelInfo.Limit(context = 200_000, output = 4_096),
            ),
            ModelInfo(
                id = "text-large",
                modelID = "text-large",
                providerID = "fake",
                name = "Fake text large",
                capabilities = ModelInfo.Capabilities(tools = true),
                cost = listOf(ModelInfo.Cost(input = 15.0, output = 60.0)),
                limit = ModelInfo.Limit(context = 400_000, output = 8_192),
            ),
            ModelInfo(
                id = "other",
                modelID = "other",
                providerID = "placeholder-provider",
                name = "Other provider model",
                capabilities = ModelInfo.Capabilities(tools = false),
                cost = listOf(ModelInfo.Cost()),
                limit = ModelInfo.Limit(context = 32_000, output = 4_096),
            ),
        ),
        agents = listOf(
            AgentInfo(
                id = "build",
                name = "build",
                description = "Implements and verifies a change end to end.",
                mode = "primary",
                color = "#3B82F6",
            ),
            AgentInfo(
                id = "plan",
                name = "plan",
                description = "Researches a task and writes an implementation plan.",
                mode = "primary",
                color = "#10B981",
            ),
            AgentInfo(
                id = "general",
                name = "general",
                description = "A subagent the agent invokes itself.",
                mode = "subagent",
            ),
        ),
        projects = TimelineFixtures.projects(),
        recentDirectories = listOf("/home/dev/opencode-android", "/home/dev/docs"),
    )
}
