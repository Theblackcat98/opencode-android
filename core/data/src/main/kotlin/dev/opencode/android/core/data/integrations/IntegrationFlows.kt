package dev.opencode.android.core.data.integrations

import dev.opencode.android.core.data.forms.FieldProblem
import dev.opencode.android.core.data.forms.FormEngine
import dev.opencode.android.core.model.ConnectionInfo
import dev.opencode.android.core.model.FormAnswer
import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.IntegrationMethod

/**
 * Which screen a method opens, and what that screen is allowed to do.
 *
 * **The dispatch is a pure function, and that is the whole point of this file.** The plan's table
 * gives four methods and four flows; the bug this prevents is a method of one kind quietly opening
 * the screen of another — a `command` method that shows a key field, or an `env` method that offers
 * a "connect" button which can never succeed. If the mapping is data, a test enumerates every
 * method type and asserts the flow; if it is a `when` inside a composable, the same bug is invisible
 * until a user taps it.
 *
 * **`[Env]` is a flow with no action.** An environment connection can only be changed on the host
 * (`opencode service set env`, features doc §10), so the app shows which variables are involved and
 * the command to run, and sends nothing. Calling it a "flow" rather than a disabled button is
 * deliberate: the user *can* do something about it, just not here.
 */
enum class IntegrationFlow {
    /** A key: a secret field, the method's form, and a label. */
    KEY,

    /** OAuth: open a URL, then poll or enter a code. */
    OAUTH,

    /** A host command: start it, read its output, cancel it. */
    COMMAND,

    /** Environment variables the host has set. Read-only, with instructions. */
    ENVIRONMENT,

    /** A method type this client does not know. Listed, but not startable. */
    UNSUPPORTED,
    ;

    /** Whether this flow has a button that starts something. */
    val isStartable: Boolean get() = this == KEY || this == OAUTH || this == COMMAND

    /**
     * Whether starting this flow shows a form before the request.
     *
     * True when the method declares fields, which is how an Azure resource name or a GHE domain
     * reaches the server alongside the key. The form itself is rendered by [FormEngine]; this only
     * says whether there is one to render.
     */
    fun showsForm(method: IntegrationMethod): Boolean = when (method) {
        is IntegrationMethod.Key -> method.form.isNotEmpty()
        is IntegrationMethod.OAuth -> method.form.isNotEmpty()
        else -> false
    }
}

/**
 * The fields a flow has to fill in, and the answers a submission builds.
 *
 * **The form is the engine's, not a second one.** An integration login's `form` is the same
 * `Form.Field` list as a `question` tool's, so the same [FormEngine] computes its visibility, its
 * problems and its answer map. A hand-rolled second implementation is how an Azure login ends up
 * sending a `resourceName` the server rejects as a missing required field while the app showed the
 * user a green button.
 */
object IntegrationForm {

    /** The fields a method declares, or an empty list for the flows that have none. */
    fun fieldsOf(method: IntegrationMethod): List<FormField> = when (method) {
        is IntegrationMethod.Key -> method.form
        is IntegrationMethod.OAuth -> method.form
        is IntegrationMethod.Command -> emptyList()
        is IntegrationMethod.Env, is IntegrationMethod.Unknown -> emptyList()
    }

    /** The answers a flow starts with, which is every declared default. */
    fun defaultsOf(method: IntegrationMethod): FormAnswer = FormEngine.defaults(fieldsOf(method))

    /**
     * Whether the answers may be sent.
     *
     * A method with no form is always ready, so a plain API key is one tap and a method with an
     * optional form is too — only a *required* unanswered field blocks.
     */
    fun isReady(method: IntegrationMethod, answers: FormAnswer): Boolean =
        FormEngine.canSubmit(fieldsOf(method), answers)

    /**
     * The `answer` body for `connect.key` and `connect.oauth`.
     *
     * `null` rather than an empty object when there is nothing to say: the schema allows either, and
     * an empty `{}` on a method with no form is noise the server has to special-case.
     */
    fun answerOf(method: IntegrationMethod, answers: FormAnswer): FormAnswer? =
        FormEngine.toAnswer(fieldsOf(method), answers).takeIf { it.isNotEmpty() }

    /** The problems keyed by field, for the UI to mark fields with. */
    fun problemsOf(method: IntegrationMethod, answers: FormAnswer): Map<String, FieldProblem> =
        FormEngine.validate(fieldsOf(method), answers)
}

/**
 * The dispatch itself.
 *
 * **An object rather than a `when` in a view model** so the feature module, the app module and a
 * test all ask the same question. `IntegrationFlow.of` is total: every [IntegrationMethod] variant
 * maps to exactly one flow, including the unknown one, and a `when` without an `else` would make a
 * new server method a crash rather than a row that says "not supported".
 */
object IntegrationFlows {

    fun of(method: IntegrationMethod): IntegrationFlow = when (method) {
        is IntegrationMethod.Key -> IntegrationFlow.KEY
        is IntegrationMethod.OAuth -> IntegrationFlow.OAUTH
        is IntegrationMethod.Command -> IntegrationFlow.COMMAND
        is IntegrationMethod.Env -> IntegrationFlow.ENVIRONMENT
        is IntegrationMethod.Unknown -> IntegrationFlow.UNSUPPORTED
    }

    /**
     * The flows a connection row offers, which is not always the method list.
     *
     * A server that lists an `env` method and already has every variable set has nothing to
     * connect, and offering the button anyway produces a dead control.
     */
    fun flowsFor(method: IntegrationMethod): List<IntegrationFlow> =
        listOf(of(method)).filter { it != IntegrationFlow.ENVIRONMENT || method is IntegrationMethod.Env }
}

/**
 * What a credential row can do.
 *
 * **Activate and remove are two-step actions, for the same reason plan §5.2 gives for a
 * "dangerous action".** Activating silently changes which account every later request uses, and
 * removing logs the server out of a provider with no undo. Both are held in the UI state until the
 * user answers, which is why the actions are named here rather than being `onClick` lambdas.
 */
enum class CredentialAction {
    RENAME,
    ACTIVATE,
    REMOVE,
}

/** Whether a connection row is a credential the app can act on, and how. */
val ConnectionInfo.actions: List<CredentialAction>
    get() = when (this) {
        is ConnectionInfo.Credential -> listOf(
            CredentialAction.RENAME,
            CredentialAction.ACTIVATE,
            CredentialAction.REMOVE,
        )

        is ConnectionInfo.Env, is ConnectionInfo.Unknown -> emptyList()
    }
