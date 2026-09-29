package dev.opencode.android.core.model

import dev.opencode.android.core.model.json.DiscriminatedUnionSerializer
import dev.opencode.android.core.model.json.ExtendedNumberSerializer
import dev.opencode.android.core.model.json.UnknownVariant
import dev.opencode.android.core.model.json.variant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An integration and the ways it can be authenticated (features doc §10; schema `Integration.Info`).
 *
 * **`methods` and `connections` are two different lists, and confusing them is the whole bug this
 * type exists to prevent.** A *method* is a way in — a key, an OAuth flow, a command to run, a set of
 * environment variables — and it is what the "connect" button offers. A *connection* is a login that
 * already exists: a credential the user added, or an environment variable the host has set. So an
 * integration with two credentials and one key method has three rows and one way to add a fourth
 * entry, and a client that drove its UI off the wrong list would offer to "activate" a method.
 */
@Serializable
data class IntegrationInfo(
    val id: String,
    val name: String,
    val metadata: Map<String, JsonElement>? = null,
    val methods: List<IntegrationMethod> = emptyList(),
    val connections: List<ConnectionInfo> = emptyList(),
) {
    /** The credentials stored for this integration, which is what can be renamed or removed. */
    val credentials: List<ConnectionInfo.Credential> get() = connections.filterIsInstance<ConnectionInfo.Credential>()

    /** The environment variables the host has set, which are read-only (features doc §10). */
    val environment: List<ConnectionInfo.Env> get() = connections.filterIsInstance<ConnectionInfo.Env>()

    /** Whether the user has any stored login for this integration. */
    val isConnected: Boolean get() = credentials.isNotEmpty()
}

/**
 * One way to authenticate against an integration (schema `Integration.Method`).
 *
 * **Four shapes, and the `env` one has no way to complete.** An environment connection can only be
 * changed on the server (`opencode service set env`), so the app shows it and explains where to set
 * it; the other three each have a flow, and [IntegrationFlow.of] is the one place that decides
 * which — which is what makes "which flow does this method open" a test rather than a `when` spread
 * across a screen.
 */
@Serializable(with = IntegrationMethodSerializer::class)
sealed interface IntegrationMethod {

    /** The server's label for the method, which the button shows. */
    val label: String

    /**
     * A `key` method.
     *
     * The schema makes `label` optional, so the property is `labelOrNull` and [label] supplies the
     * fallback. It cannot be a constructor property called `label`, because the interface declares
     * `label` as a non-null `String` and two properties with that name cannot coexist.
     */
    @Serializable
    data class Key(
        @SerialName("label") val labelOrNull: String? = null,
        val form: List<FormField> = emptyList(),
    ) : IntegrationMethod {
        override val label: String get() = labelOrNull?.takeIf(String::isNotBlank) ?: "API key"
    }

    /**
     * An `oauth` method.
     *
     * [id] is the `methodID` `integration.oauth.connect` and `integration.oauth.complete` take, so
     * it is not optional here: a method without one cannot be started. [labelText] is the wire's
     * `label`; it carries a different Kotlin name because the interface already declares [label] and
     * the same pattern is used for every variant so there is one convention rather than four.
     */
    @Serializable
    data class OAuth(
        val id: String,
        @SerialName("label") val labelText: String,
        val form: List<FormField> = emptyList(),
    ) : IntegrationMethod {
        override val label: String get() = labelText
    }

    /**
     * A `command` method: a command the *host* runs, whose output the app polls.
     *
     * The command is shown to the user before it is started, because it runs on their machine and
     * not on the server.
     */
    @Serializable
    data class Command(
        val id: String,
        @SerialName("label") val labelText: String,
        val command: List<String> = emptyList(),
    ) : IntegrationMethod {
        override val label: String get() = labelText
    }

    /**
     * An `env` method: read-only.
     *
     * The schema gives it no `label` and no `id`, so [label] names the variables, which is the only
     * thing the app has to show.
     */
    @Serializable
    data class Env(
        val names: List<String> = emptyList(),
    ) : IntegrationMethod {
        override val label: String get() = names.joinToString(", ").ifEmpty { "Environment" }
    }

    /** A method type this client does not know. It cannot be started, only listed. */
    data class Unknown(
        val declaredType: String?,
        override val raw: JsonObject,
    ) : IntegrationMethod,
        UnknownVariant {
        override val discriminator: String? get() = declaredType
        override val label: String get() = declaredType ?: "Unknown method"
    }
}

internal object IntegrationMethodSerializer : DiscriminatedUnionSerializer<IntegrationMethod>(
    serialName = "dev.opencode.android.IntegrationMethod",
    unknown = IntegrationMethod::Unknown,
    variants = listOf(
        variant("key", IntegrationMethod.Key.serializer()),
        variant("oauth", IntegrationMethod.OAuth.serializer()),
        variant("command", IntegrationMethod.Command.serializer()),
        variant("env", IntegrationMethod.Env.serializer()),
    ),
)

/** An existing login on an integration (schema `Connection.Info`). */
@Serializable(with = ConnectionInfoSerializer::class)
sealed interface ConnectionInfo {

    /**
     * A stored credential (schema `Connection.CredentialInfo`).
     *
     * [method] is `key` or `oauth`, which is what the row's icon and the "signed in with a key"
     * line are derived from. The credential's *secret* is never in this object: the server holds it,
     * and the app only ever has the id it can activate, rename or remove.
     */
    @Serializable
    data class Credential(
        val id: String,
        val label: String,
        val method: String,
    ) : ConnectionInfo {
        val isKey: Boolean get() = method == "key"
        val isOAuth: Boolean get() = method == "oauth"
    }

    /** A connection the host provides through the environment. Read-only (features doc §10). */
    @Serializable
    data class Env(val name: String) : ConnectionInfo

    data class Unknown(
        val declaredType: String?,
        override val raw: JsonObject,
    ) : ConnectionInfo,
        UnknownVariant {
        override val discriminator: String? get() = declaredType
    }
}

internal object ConnectionInfoSerializer : DiscriminatedUnionSerializer<ConnectionInfo>(
    serialName = "dev.opencode.android.ConnectionInfo",
    unknown = ConnectionInfo::Unknown,
    variants = listOf(
        variant("credential", ConnectionInfo.Credential.serializer()),
        variant("env", ConnectionInfo.Env.serializer()),
    ),
)

/** When an attempt was created and when it stops being valid (schema `Integration.AttemptEncoded.time`). */
@Serializable
data class AttemptWindow(
    @Serializable(with = ExtendedNumberSerializer::class) val created: Double = 0.0,
    @Serializable(with = ExtendedNumberSerializer::class) val expires: Double = 0.0,
) {
    companion object {
        /** A window that never expires, which is what a server sends for an attempt it does not time out. */
        val Never = AttemptWindow(created = 0.0, expires = Double.POSITIVE_INFINITY)
    }
}

/**
 * An OAuth attempt the server has started (schema `Integration.AttemptEncoded`).
 *
 * **[url] is server-supplied and is opened in a Custom Tab, so it is a navigation into input this
 * client did not author.** [safeUrl] is the only form of it the UI may use, and it is `null` for
 * anything that is not http or https — a `javascript:`, `intent:` or `file:` URL from a compromised
 * or merely misconfigured server would otherwise be one tap away.
 */
@Serializable
data class OAuthAttempt(
    val attemptID: String,
    val url: String,
    val instructions: String,
    val mode: String,
    val time: AttemptWindow = AttemptWindow(),
) {
    val isAuto: Boolean get() = mode == OAuthMode.AUTO
    val isCode: Boolean get() = mode == OAuthMode.CODE

    /** The URL the app is allowed to open, or `null` when the scheme is not http or https. */
    val safeUrl: String? get() = SafeNavigationUrl.parse(url)

    object OAuthMode {
        const val AUTO = "auto"
        const val CODE = "code"
    }
}

/**
 * Only http and https are ever opened.
 *
 * **This is a security boundary, so it is a pure function with its own tests.** The `url` on an
 * OAuth attempt is the one piece of server input this client navigates to, and a Custom Tab will
 * happily hand an `intent://` or `javascript:` URL to whatever app claims it. Parsing here rather
 * than at the call site means every caller gets the same answer and there is exactly one place to
 * audit.
 */
object SafeNavigationUrl {
    private val ALLOWED = setOf("http", "https")

    /**
     * [raw] when it is an absolute http or https URL with a host, or `null`.
     *
     * `java.net.URI` is used rather than a prefix test because a prefix test is defeated by
     * `"https:/\evil.example"`, by a leading control character, and by a URL whose scheme is
     * spelled with mixed case. The parsed scheme is compared lower-cased, and the host must be
     * non-empty: `https:///path` parses cleanly and goes nowhere.
     */
    fun parse(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        if (!uri.isAbsolute) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in ALLOWED) return null
        if (uri.host.isNullOrBlank()) return null
        return trimmed
    }

    /** Whether [raw] may be opened, which is what a test asserts on rather than on a re-parse. */
    fun isAllowed(raw: String): Boolean = parse(raw) != null
}

/** The status of an OAuth attempt (schema `Integration.AttemptStatus`). */
@Serializable(with = OAuthAttemptStatusSerializer::class)
sealed interface OAuthAttemptStatus {
    val time: AttemptWindow

    /** Still waiting on the user or the provider. */
    @Serializable
    data class Pending(override val time: AttemptWindow) : OAuthAttemptStatus

    /** The provider granted access. Terminal. */
    @Serializable
    data class Complete(override val time: AttemptWindow) : OAuthAttemptStatus

    /** The provider or the server refused, with [message] as the reason. Terminal. */
    @Serializable
    data class Failed(
        val message: String,
        override val time: AttemptWindow,
    ) : OAuthAttemptStatus

    /** The attempt timed out on the server. Terminal. */
    @Serializable
    data class Expired(override val time: AttemptWindow) : OAuthAttemptStatus

    data class Unknown(
        val declaredStatus: String?,
        override val raw: JsonObject,
    ) : OAuthAttemptStatus,
        UnknownVariant {
        override val discriminator: String? get() = declaredStatus
        override val time: AttemptWindow get() = AttemptWindow()
    }
}

internal object OAuthAttemptStatusSerializer : DiscriminatedUnionSerializer<OAuthAttemptStatus>(
    serialName = "dev.opencode.android.OAuthAttemptStatus",
    discriminator = "status",
    unknown = OAuthAttemptStatus::Unknown,
    variants = listOf(
        variant("pending", OAuthAttemptStatus.Pending.serializer()),
        variant("complete", OAuthAttemptStatus.Complete.serializer()),
        variant("failed", OAuthAttemptStatus.Failed.serializer()),
        variant("expired", OAuthAttemptStatus.Expired.serializer()),
    ),
)

/** A command attempt the server has started (schema `Integration.CommandAttempt`). */
@Serializable
data class CommandAttempt(
    val attemptID: String,
    val time: AttemptWindow = AttemptWindow(),
)

/**
 * The status of a command attempt (schema `Integration.CommandAttemptStatus`).
 *
 * **Same four states as OAuth, and one difference that matters.** `pending` carries a [message]: the
 * host command's own output so far, which is what the plan's "show the polled output" means. It is
 * accumulated by the poller rather than replaced, because a device-code login prints a URL and then
 * waits, and replacing the line would hide the part the user has to act on.
 */
@Serializable(with = CommandAttemptStatusSerializer::class)
sealed interface CommandAttemptStatus {
    val time: AttemptWindow

    /** Still running, with the output it has produced so far. */
    @Serializable
    data class Pending(
        val message: String? = null,
        override val time: AttemptWindow,
    ) : CommandAttemptStatus

    @Serializable
    data class Complete(override val time: AttemptWindow) : CommandAttemptStatus

    @Serializable
    data class Failed(
        val message: String,
        override val time: AttemptWindow,
    ) : CommandAttemptStatus

    @Serializable
    data class Expired(override val time: AttemptWindow) : CommandAttemptStatus

    data class Unknown(
        val declaredStatus: String?,
        override val raw: JsonObject,
    ) : CommandAttemptStatus,
        UnknownVariant {
        override val discriminator: String? get() = declaredStatus
        override val time: AttemptWindow get() = AttemptWindow()
    }
}

internal object CommandAttemptStatusSerializer : DiscriminatedUnionSerializer<CommandAttemptStatus>(
    serialName = "dev.opencode.android.CommandAttemptStatus",
    discriminator = "status",
    unknown = CommandAttemptStatus::Unknown,
    variants = listOf(
        variant("pending", CommandAttemptStatus.Pending.serializer()),
        variant("complete", CommandAttemptStatus.Complete.serializer()),
        variant("failed", CommandAttemptStatus.Failed.serializer()),
        variant("expired", CommandAttemptStatus.Expired.serializer()),
    ),
)

// ------------------------------------------------------------------------------- write payloads

/**
 * `POST …/connect/key` (schema `Connect.Key`).
 *
 * [key] is a [Secret], not a `String`, and that is the point. A `String` here would be printed by
 * this data class's own `toString`, which is what a failing assertion, a debugger or a crash
 * reporter calls; the wrapper's `toString` says only how long it is. The wrapper still *serializes*
 * to the plain JSON string the wire needs, so nothing about the request changes.
 */
@Serializable
data class ConnectKeyRequest(
    val key: Secret,
    val answer: FormAnswer? = null,
    val label: String? = null,
)

/** `POST …/connect/oauth` (schema `Connect.OAuth`). */
@Serializable
data class ConnectOAuthRequest(
    val methodID: String,
    val answer: FormAnswer? = null,
    val label: String? = null,
)

/** `POST …/connect/oauth/{attemptID}/complete` (schema `Connect.OAuth.Complete`). */
@Serializable
data class ConnectOAuthCompleteRequest(val code: String? = null)

/** `POST …/connect/command` (schema `Connect.Command`). */
@Serializable
data class ConnectCommandRequest(
    val methodID: String,
    val label: String? = null,
)

/** `PATCH /api/credential/{id}` (schema `Credential.Update`). */
@Serializable
data class CredentialUpdateRequest(val label: String)

/** `POST /api/experimental/integration/wellknown` (schema `Wellknown.Add`). */
@Serializable
data class WellknownSourceRequest(val url: String)

/** `POST /api/plugin/check` (schema `Plugin.Check`). */
@Serializable
data class PluginCheckRequest(val target: String? = null)

/** `POST /api/plugin/update` (schema `Plugin.Update`). */
@Serializable
data class PluginUpdateRequest(val targets: List<String>)

/** `POST /api/websearch` (schema `WebSearch.Query`). */
@Serializable
data class WebSearchQueryRequest(
    val query: String,
    val providerID: String? = null,
)

/**
 * A web-search result (schema `WebSearch.Result`).
 *
 * [title] and [content] are provider-supplied prose from a web page, so a renderer treats them as
 * untrusted text: never a link the app opens without a scheme check, never markup.
 */
@Serializable
data class WebSearchResult(
    val url: String,
    val title: String? = null,
    val content: String? = null,
    val time: Time = Time(),
) {
    @Serializable
    data class Time(
        @Serializable(with = ExtendedNumberSerializer::class) val published: Double? = null,
    )
}

/** `POST /api/websearch` (schema `WebSearch.ResponseEncoded`). */
@Serializable
data class WebSearchResponse(
    val providerID: String,
    val results: List<WebSearchResult> = emptyList(),
)

/** One entry of `GET /api/websearch/provider` (schema `WebSearch.Provider`). */
@Serializable
data class WebSearchProviderInfo(
    val id: String,
    val name: String,
)

/**
 * A value the app must never write anywhere a human or a log file can read it.
 *
 * **A wrapper, not a convention.** A `String` holding an API key is one careless `Log.d`, one
 * `data class` `toString`, or one assertion message away from being in a bug report, and the
 * compiler says nothing about any of the three. Wrapping it means the only way to read the value is
 * [reveal], which is deliberately awkward and greppable, and [toString] cannot leak because it
 * prints a length rather than the characters.
 *
 * The wrapper is **not** encrypted at rest, and does not need to be. The server holds the credential;
 * the app only ever holds the key in memory between the user typing it and the request carrying it,
 * so a secret that never touches disk cannot be recovered from a backup. What the wrapper buys is the
 * narrower guarantee that matters more: it cannot be logged by accident.
 *
 * It still serializes to a plain JSON string, because that is what the wire needs and the body is
 * built in exactly one place.
 */
@Serializable(with = SecretSerializer::class)
class Secret private constructor(private val value: String) {

    /** The characters, for the one place they are needed: the request body. */
    fun reveal(): String = value

    val isBlank: Boolean get() = value.isBlank()

    /**
     * A description safe to log.
     *
     * The length is included because a request that sent an empty key is a bug worth being able to
     * see, and the length does not narrow the search space usefully.
     */
    override fun toString(): String = "Secret(length=${value.length})"

    /**
     * Equality on the value, so a test can assert the right secret reached the wire.
     *
     * Value equality is what makes [ConnectKeyRequest] comparable, which is how a test checks the
     * payload without a stringly-typed `assertEquals(key, request.key.reveal())` that would be the
     * first place the secret is written into a failure message.
     */
    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** Wraps [value]. Blank is allowed so a validation error can be reported before use. */
        fun of(value: String): Secret = Secret(value)

        /** A secret that is empty, used as the initial value of a key field. */
        val Empty: Secret = Secret("")
    }
}

/**
 * Writes the secret as a plain JSON string.
 *
 * **Deserialization exists only so a recorded request body round-trips in a test.** The app never
 * reads a credential back, and the alternative — not implementing `deserialize` — would make every
 * contract test that decodes a recorded `connect.key` body fail for the wrong reason.
 */
internal object SecretSerializer : KSerializer<Secret> {
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.opencode.android.Secret", JsonPrimitive.serializer().descriptor)

    override fun deserialize(decoder: Decoder): Secret {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("A secret can only be decoded from JSON")
        val element = input.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("A secret is a JSON string")
        return Secret.of(element.content)
    }

    override fun serialize(encoder: Encoder, value: Secret) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("A secret can only be encoded to JSON")
        output.encodeJsonElement(JsonPrimitive(value.reveal()))
    }
}
