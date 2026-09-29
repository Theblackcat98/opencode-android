package dev.opencode.android.core.data.integrations

import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.FormValues
import dev.opencode.android.core.model.IntegrationMethod
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which flow each method opens, and what the engine says about the form it carries.
 *
 * **The dispatch is the claim; this is the proof.** A `when` inside a composable would compile, would
 * look right, and would open the wrong sheet for one method type — which is the same class of defect
 * a previous phase shipped as a Fork button that was never wired. Here every variant of the union,
 * including the one a future server adds, has a stated flow, and the flows that have no action say so
 * themselves.
 */
class IntegrationFlowsTest {

    private val azureField = FormField.StringField(
        key = "resourceName",
        title = "Resource name",
        required = true,
    )

    @Test
    fun `each method type opens its own flow`() {
        assertEquals(
            IntegrationFlow.KEY,
            IntegrationFlows.of(IntegrationMethod.Key(labelOrNull = "API key")),
        )
        assertEquals(
            IntegrationFlow.OAUTH,
            IntegrationFlows.of(IntegrationMethod.OAuth(id = "oauth-default", labelText = "Sign in")),
        )
        assertEquals(
            IntegrationFlow.COMMAND,
            IntegrationFlows.of(IntegrationMethod.Command(id = "cli", labelText = "Use the CLI")),
        )
        assertEquals(
            IntegrationFlow.ENVIRONMENT,
            IntegrationFlows.of(IntegrationMethod.Env(names = listOf("ANTHROPIC_API_KEY"))),
        )
    }

    @Test
    fun `a method type this client does not know is listed but not startable`() {
        // The `Unknown` variant is what a server that adds a fifth method type produces. A `when`
        // without an `else` would crash there; the requirement is that the row still renders.
        val unknown = IntegrationMethod.Unknown("passkey", JsonObject(emptyMap()))
        assertEquals(IntegrationFlow.UNSUPPORTED, IntegrationFlows.of(unknown))
        assertFalse(IntegrationFlow.UNSUPPORTED.isStartable)
        assertTrue(unknown.label.isNotBlank())
    }

    @Test
    fun `exactly the three completable flows are startable`() {
        // A button on an env or unknown method is a control that cannot do anything, which is the
        // dead-button defect. This asserts the property the screen's `onClick` set is built from.
        val startable = listOf(
            IntegrationFlow.KEY, IntegrationFlow.OAUTH, IntegrationFlow.COMMAND,
            IntegrationFlow.ENVIRONMENT, IntegrationFlow.UNSUPPORTED,
        ).filter { it.isStartable }
        assertEquals(listOf(IntegrationFlow.KEY, IntegrationFlow.OAUTH, IntegrationFlow.COMMAND), startable)
    }

    @Test
    fun `only the flows that can carry a form say they show one`() {
        assertTrue(IntegrationFlow.KEY.showsForm(IntegrationMethod.Key(form = listOf(azureField))))
        assertFalse("a key method with no form has nothing to show", IntegrationFlow.KEY.showsForm(IntegrationMethod.Key()))
        assertTrue(IntegrationFlow.OAUTH.showsForm(IntegrationMethod.OAuth("m", "l", form = listOf(azureField))))
        // A command method has no form in the schema, and a form the engine cannot find fields for
        // would render an empty sheet.
        assertFalse(IntegrationFlow.COMMAND.showsForm(IntegrationMethod.Command("cli", "CLI")))
        assertFalse(IntegrationFlow.ENVIRONMENT.showsForm(IntegrationMethod.Env()))
    }

    @Test
    fun `the engine's required-field rule blocks a key login until the form is answered`() {
        val method = IntegrationMethod.Key(form = listOf(azureField))

        assertFalse("a required field with no answer must block", IntegrationForm.isReady(method, emptyMap()))
        assertTrue(
            "answering it must unblock",
            IntegrationForm.isReady(method, mapOf("resourceName" to FormValues.string("team-a"))),
        )
    }

    @Test
    fun `a key method with no form is ready with nothing answered`() {
        assertTrue(IntegrationForm.isReady(IntegrationMethod.Key(), emptyMap()))
    }

    @Test
    fun `the answer sent is the engine's, and it omits an unanswered optional field`() {
        val method = IntegrationMethod.Key(
            form = listOf(
                azureField,
                FormField.StringField(key = "tenant", title = "Tenant", required = false),
            ),
        )
        val answers = mapOf("resourceName" to FormValues.string("team-a"))

        val answer = IntegrationForm.answerOf(method, answers)!!
        assertEquals(setOf("resourceName"), answer.keys)
        // A null is not a value in Form.Value, so sending one would be an invalid answer.
        assertFalse(answer.containsKey("tenant"))
    }

    @Test
    fun `a method with nothing to say sends no answer object at all`() {
        // The schema allows both `{}` and absent; absent is the honest one, and an empty object on
        // every plain API key login is noise the server would have to special-case.
        assertNull(IntegrationForm.answerOf(IntegrationMethod.Key(), emptyMap()))
    }

    @Test
    fun `a conditional field is only answered while it is visible`() {
        val method = IntegrationMethod.Key(
            form = listOf(
                FormField.StringField(key = "kind", required = true),
                FormField.StringField(
                    key = "detail",
                    required = true,
                    conditions = listOf(dev.opencode.android.core.model.FormWhen("kind", "eq", FormValues.string("custom"))),
                ),
            ),
        )
        val hidden = mapOf("kind" to FormValues.string("standard"), "detail" to FormValues.string("leftover"))
        // The engine drops the hidden field, so a value the user cannot see never reaches the server
        // even if it is still in the state.
        assertEquals(setOf("kind"), IntegrationForm.answerOf(method, hidden)!!.keys)

        val shown = mapOf("kind" to FormValues.string("custom"), "detail" to FormValues.string("azure-1"))
        assertEquals(setOf("kind", "detail"), IntegrationForm.answerOf(method, shown)!!.keys)
    }

    @Test
    fun `a form's declared default is the answer a login starts with`() {
        val method = IntegrationMethod.Key(
            form = listOf(FormField.StringField(key = "region", default = "eu-west-1")),
        )
        assertEquals(
            mapOf("region" to FormValues.string("eu-west-1")),
            IntegrationForm.defaultsOf(method),
        )
        assertTrue(IntegrationForm.isReady(method, IntegrationForm.defaultsOf(method)))
    }

    @Test
    fun `a method decodes from the wire into the flow that matches its type`() {
        // Decoded rather than constructed, because the discriminator is what a server sends and the
        // mapping has to hold for the decoded value, not only for a hand-built one. A `when` written
        // against constructors would pass every other test in this file and still mis-dispatch the
        // one shape the server actually produces.
        assertEquals(IntegrationFlow.KEY, IntegrationFlows.of(decode(Fixtures.keyJson)))
        assertEquals(IntegrationFlow.OAUTH, IntegrationFlows.of(decode(Fixtures.oauthJson)))
        assertEquals(IntegrationFlow.COMMAND, IntegrationFlows.of(decode(Fixtures.commandJson)))
        assertEquals(IntegrationFlow.ENVIRONMENT, IntegrationFlows.of(decode(Fixtures.envJson)))
    }

    @Test
    fun `a method type the wire names and this build does not know is not startable`() {
        val future = OpenCodeJson.decodeFromString<IntegrationMethod>(
            """{"type":"passkey","label":"Use a security key"}""",
        )
        assertEquals(IntegrationFlow.UNSUPPORTED, IntegrationFlows.of(future))
        assertFalse(IntegrationFlows.of(future).isStartable)
    }

    @Test
    fun `the key method's label is optional on the wire and falls back to one this client owns`() {
        // The schema requires only `type`, and a string in the app is not localizable, so the
        // fallback is a stable English default rather than an empty button.
        val bare = OpenCodeJson.decodeFromString<IntegrationMethod>("""{"type":"key"}""")
        assertEquals("API key", bare.label)
        val named = OpenCodeJson.decodeFromString<IntegrationMethod>(Fixtures.keyJson)
        assertEquals("API key", named.label)
    }

    @Test
    fun `an oauth method decodes with the id the complete route needs`() {
        val oauth = OpenCodeJson.decodeFromString<IntegrationMethod>(Fixtures.oauthJson) as IntegrationMethod.OAuth
        assertEquals("oauth-default", oauth.id)
        assertEquals("Sign in with Microsoft", oauth.label)
    }

    @Test
    fun `a command method decodes with the command the host will run`() {
        val command = OpenCodeJson.decodeFromString<IntegrationMethod>(Fixtures.commandJson) as IntegrationMethod.Command
        // The user is shown this before it runs on their own machine, so the decoded list is the
        // thing the confirmation dialog must render rather than a re-formatting of it.
        assertEquals(listOf("gh", "auth", "login"), command.command)
    }

    @Test
    fun `an env connection offers no actions at all`() {
        // The schema gives `env` no id, so there is nothing to rename, activate or remove, and a
        // menu with three dead entries is worse than none.
        val env = dev.opencode.android.core.model.ConnectionInfo.Env("ANTHROPIC_API_KEY")
        assertTrue(env.actions.isEmpty())
        val credential = dev.opencode.android.core.model.ConnectionInfo.Credential("cred_1", "work", "key")
        assertEquals(
            listOf(CredentialAction.RENAME, CredentialAction.ACTIVATE, CredentialAction.REMOVE),
            credential.actions,
        )
    }

    @Test
    fun `a credential's method decides how the row reads`() {
        val key = dev.opencode.android.core.model.ConnectionInfo.Credential("c1", "l", "key")
        val oauth = dev.opencode.android.core.model.ConnectionInfo.Credential("c2", "l", "oauth")
        assertTrue(key.isKey)
        assertFalse(key.isOAuth)
        assertTrue(oauth.isOAuth)
    }
}

/** Decodes one method from the wire, which is how a server's discriminator reaches the dispatch. */
private fun decode(json: String): IntegrationMethod = OpenCodeJson.decodeFromString(json)

/** Named fixtures, kept out of the test body so the assertions read as claims. */
internal object Fixtures {
    const val keyJson = """{"type":"key","label":"API key","form":[{"key":"resourceName","type":"string","required":true}]}"""
    const val oauthJson = """{"type":"oauth","id":"oauth-default","label":"Sign in with Microsoft"}"""
    const val commandJson = """{"type":"command","id":"cli","label":"Use the CLI","command":["gh","auth","login"]}"""
    const val envJson = """{"type":"env","names":["ANTHROPIC_API_KEY"]}"""
}
