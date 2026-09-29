package dev.opencode.android.core.network

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLException

/**
 * Optional trust for user-installed CAs, exercised through a real TLS handshake against a server
 * whose certificate chains to a private CA, the way a self-hosted HTTPS deployment looks.
 */
class UserCaTrustTest {

    private lateinit var privateCa: HeldCertificate
    private lateinit var serverCertificates: HandshakeCertificates
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        privateCa = HeldCertificate.Builder()
            .certificateAuthority(1)
            .commonName("Private CA")
            .build()
        val serverCertificate = HeldCertificate.Builder()
            .signedBy(privateCa)
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun aServerBehindAPrivateCaIsRejectedByDefault() {
        val client = OkHttpClient()

        val failure = runCatching { call(client) }.exceptionOrNull()

        assertTrue("Expected a TLS failure, got $failure", failure is SSLException)
    }

    @Test
    fun trustingUserCertificatesAcceptsTheServersCertificate() {
        val trustManager = UserCaTrust.trustManager(userCertificates = listOf(privateCa.certificate))
        val client = OkHttpClient.Builder()
            .sslSocketFactory(UserCaTrust.socketFactory(trustManager), trustManager)
            .build()

        val code = call(client)

        assertEquals(200, code)
    }

    @Test
    fun theTrustManagerIsThePlatformOneWhenThereAreNoUserCertificates() {
        val platform = UserCaTrust.platformTrustManager()

        assertSame(platform, UserCaTrust.trustManager(platform = platform, userCertificates = emptyList()))
    }

    @Test
    fun aChainNoUserCaSignedIsStillRejected() {
        val otherCa = HeldCertificate.Builder().certificateAuthority(2).commonName("Other").build()
        val trustManager = UserCaTrust.trustManager(userCertificates = listOf(otherCa.certificate))
        val client = OkHttpClient.Builder()
            .sslSocketFactory(UserCaTrust.socketFactory(trustManager), trustManager)
            .build()

        val failure = runCatching { call(client) }.exceptionOrNull()

        assertTrue("Expected a TLS failure, got $failure", failure is SSLException)
    }

    @Test
    fun serverTlsOnlyDerivesAClientWhenTheServerOptsIn() {
        val base = OkHttpClient()
        val serverTls = ServerTls(base, UserCertificateSource { listOf(privateCa.certificate) })

        assertEquals(base, serverTls.clientFor(trustUserCertificates = false))
        assertTrue(serverTls.clientFor(trustUserCertificates = true) !== base)
    }

    @Test
    fun serverTlsKeepsTheSharedClientWhenTheDeviceHasNoUserCertificates() {
        val base = OkHttpClient()
        val serverTls = ServerTls(base, UserCertificateSource { emptyList() })

        assertEquals(base, serverTls.clientFor(trustUserCertificates = true))
    }

    @Test
    fun theTrustedClientTalksToTheServerThroughTheOkHttpStack() = runBlocking {
        val base = OkHttpClient()
        val serverTls = ServerTls(base, UserCertificateSource { listOf(privateCa.certificate) })
        val api = ServerApiFactory(base, serverTls).create(
            baseUrl = "https://127.0.0.1:${server.port}/",
            trustUserCertificates = true,
        )
        server.enqueue(MockResponse.Builder().code(200).body(SERVER_INFO_JSON).build())

        val info = api.getServerInfo()

        assertEquals("2.0.18", info.version)
    }

    private fun call(client: OkHttpClient): Int {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        return client.newCall(Request.Builder().url(serverUrl()).build()).execute().use { it.code }
    }

    /**
     * The address is spelled with the literal IP: `localhost` can resolve to an IPv6 address this
     * test host does not listen on.
     */
    private fun serverUrl(): String = "https://127.0.0.1:${server.port}/api/info"

    private companion object {
        val SERVER_INFO_JSON =
            """{"version":"2.0.18","pid":1,"urls":["https://box.example.com"],"paths":{"tmp":"/tmp"}}"""
    }
}
