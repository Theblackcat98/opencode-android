package dev.opencode.android.core.network

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The CA certificates the user installed on the device ("user CAs").
 *
 * Android keeps them out of an app's trust store on purpose: a network security config without
 * `<certificates src="user"/>` ignores them. A self-hosted HTTPS server behind a private CA needs
 * them, so the app can opt a server in.
 */
fun interface UserCertificateSource {
    fun certificates(): List<X509Certificate>
}

/**
 * Optional trust for user-installed CAs (plan §2, finding 4).
 *
 * The platform trust manager deliberately rejects user CAs. When a server is configured to trust
 * them, its connections validate against the platform trust store *or* the user's CAs, so a
 * self-signed private CA works without weakening any other server's connection.
 */
object UserCaTrust {

    /** The platform trust manager, which is the one that ignores user-installed CAs. */
    fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("The platform has no X509 trust manager")
    }

    /**
     * A trust manager that accepts the platform's chains and, when [userCertificates] is not
     * empty, chains that only a user-installed CA vouches for.
     */
    fun trustManager(
        platform: X509TrustManager = platformTrustManager(),
        userCertificates: List<X509Certificate> = emptyList(),
    ): X509TrustManager = when {
        userCertificates.isEmpty() -> platform
        else -> UserAnchoredTrustManager(platform, userAnchorManager(userCertificates))
    }

    /** An [SSLSocketFactory] for [trustManager], for `OkHttpClient.Builder.sslSocketFactory`. */
    fun socketFactory(trustManager: X509TrustManager): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }.socketFactory

    /** A trust manager that trusts exactly the given CA certificates and nothing else. */
    fun userAnchorManager(userCertificates: List<X509Certificate>): X509TrustManager? {
        if (userCertificates.isEmpty()) return null
        return runCatching {
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            userCertificates.forEachIndexed { index, certificate ->
                keyStore.setCertificateEntry("user-ca-$index", certificate)
            }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(keyStore)
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }.getOrNull()
    }
}

/**
 * Accepts a server chain when the platform accepts it, and otherwise when one of the user's
 * installed CAs does. Both failures are reported, with the platform's first, so the error a user
 * sees names the reason the chain failed the stricter check.
 */
class UserAnchoredTrustManager(
    private val platform: X509TrustManager,
    private val userAnchors: X509TrustManager?,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        platform.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            platform.checkServerTrusted(chain, authType)
        } catch (platformFailure: CertificateException) {
            val anchors = userAnchors ?: throw platformFailure
            try {
                anchors.checkServerTrusted(chain, authType)
            } catch (userFailure: CertificateException) {
                platformFailure.addSuppressed(userFailure)
                throw platformFailure
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}

/**
 * Reads the user CAs from `AndroidCAStore`, the keystore Android exposes to apps.
 *
 * The store lists user-installed certificates under `user:` aliases and the built-in ones under
 * `system:`. Only the `user:` entries are returned, because the platform trust manager already
 * covers the rest. The directory these used to be read from, `/data/misc/keychain/cacerts-added`,
 * is absent on current releases, and the user store under `/data/misc/user` is not readable by an
 * app. A store that cannot be opened means there are no extra anchors, so the platform trust
 * manager stays in charge.
 */
class AndroidUserCertificateSource(
    private val openStore: () -> KeyStore = { KeyStore.getInstance(ANDROID_CA_STORE).apply { load(null, null) } },
) : UserCertificateSource {

    override fun certificates(): List<X509Certificate> = runCatching {
        val store = openStore()
        Collections.list(store.aliases())
            .filter { it.startsWith(USER_ALIAS_PREFIX) }
            .mapNotNull { store.getCertificate(it) as? X509Certificate }
    }.getOrDefault(emptyList())

    private companion object {
        const val ANDROID_CA_STORE = "AndroidCAStore"
        const val USER_ALIAS_PREFIX = "user:"
    }
}
