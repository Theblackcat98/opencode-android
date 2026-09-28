package dev.opencode.android.core.network

import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
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
 * Reads the user CAs from the platform's user-certificate store.
 *
 * Android keeps them in `/data/misc/keychain/cacerts-added` as DER files. Reading that directory
 * is not guaranteed on every release, so an unreadable or empty store simply means there are no
 * extra anchors and the platform trust manager stays in charge.
 */
class AndroidUserCertificateSource(
    private val directory: File = File(USER_CA_DIRECTORY),
) : UserCertificateSource {

    override fun certificates(): List<X509Certificate> {
        val files = runCatching { directory.listFiles() }.getOrNull() ?: return emptyList()
        val factory = CertificateFactory.getInstance(CERTIFICATE_TYPE)
        return files
            .asSequence()
            .filter { it.isFile && it.length() > 0L }
            .sortedBy { it.name }
            .flatMap { file -> runCatching { read(factory, file) }.getOrDefault(emptyList()).asSequence() }
            .toList()
    }

    private fun read(factory: CertificateFactory, file: File): Collection<X509Certificate> {
        val stream: InputStream = file.inputStream()
        return stream.use { factory.generateCertificates(it).filterIsInstance<X509Certificate>() }
    }

    private companion object {
        const val USER_CA_DIRECTORY = "/data/misc/keychain/cacerts-added"
        const val CERTIFICATE_TYPE = "X.509"
    }
}
