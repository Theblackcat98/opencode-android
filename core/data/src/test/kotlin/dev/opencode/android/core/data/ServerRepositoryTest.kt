package dev.opencode.android.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.opencode.android.core.data.repository.AddServerErrorType
import dev.opencode.android.core.data.repository.AddServerOutcome
import dev.opencode.android.core.data.repository.DefaultServerRepository
import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.data.security.InMemoryCredentialStore
import dev.opencode.android.core.database.OpenCodeDatabase
import dev.opencode.android.core.network.PairingClient
import dev.opencode.android.core.network.PairingLink
import dev.opencode.android.core.network.ServerApiFactory
import dev.opencode.android.core.network.ServerCredentialCache
import dev.opencode.android.core.network.ServerValidator
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerRepositoryTest {

    private lateinit var db: OpenCodeDatabase
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var credentialCache: ServerCredentialCache
    private lateinit var repository: ServerRepository
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OpenCodeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        credentialStore = InMemoryCredentialStore()
        credentialCache = ServerCredentialCache()
        val client = OkHttpClient()
        val apiFactory = ServerApiFactory(client)
        repository = DefaultServerRepository(
            serverDao = db.serverDao(),
            credentialStore = credentialStore,
            serverValidator = ServerValidator(apiFactory),
            pairingClient = PairingClient(apiFactory),
            credentialCache = credentialCache,
        )
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    @Test
    fun firstServerAddedBecomesDefaultAutomatically() = runTest {
        val id = repository.addServer("Dev Server", "http://localhost:4096", "secret-pass")

        val server = repository.getServer(id)
        assertNotNull(server)
        assertTrue(server!!.isDefault)
        assertEquals("secret-pass", repository.getCredential(id))
        assertTrue(server.hasCredential)

        repository.observeDefaultServer().test {
            val current = awaitItem()
            assertNotNull(current)
            assertEquals(id, current!!.id)
        }
    }

    @Test
    fun aSecondServerDoesNotStealTheDefaultUnlessAsked() = runTest {
        val first = repository.addServer("S1", "http://localhost:4096", null)
        val second = repository.addServer("S2", "http://localhost:4097", null)

        assertTrue(repository.getServer(first)!!.isDefault)
        assertFalse(repository.getServer(second)!!.isDefault)

        repository.setDefaultServer(second)
        assertFalse(repository.getServer(first)!!.isDefault)
        assertTrue(repository.getServer(second)!!.isDefault)
    }

    @Test
    fun removingTheDefaultPromotesAnotherServer() = runTest {
        val first = repository.addServer("S1", "http://localhost:4096", null)
        val second = repository.addServer("S2", "http://localhost:4097", null)
        val third = repository.addServer("S3", "http://localhost:4098", null)
        assertTrue(repository.getServer(first)!!.isDefault)

        repository.removeServer(first)

        val defaults = repository.getAllServers().filter { it.isDefault }
        assertEquals("Exactly one server must be the default", 1, defaults.size)
        assertTrue(
            "The promoted server must be one of the survivors",
            defaults.single().id in setOf(second, third),
        )
    }

    @Test
    fun theFirstServerOnAFreshInstallBecomesTheDefault() = runTest {
        val only = repository.addServer("Only", "http://localhost:4096", null)
        assertTrue(repository.getServer(only)!!.isDefault)
    }

    @Test
    fun removeServerDeletesTheEntityTheCredentialAndTheCachedToken() = runTest {
        val id = repository.addServer("S1", "http://localhost:4096", "pass123")
        assertTrue(credentialCache.holds("http://localhost:4096"))

        repository.removeServer(id)

        assertNull(repository.getServer(id))
        assertNull(repository.getCredential(id))
        assertFalse(credentialCache.holds("http://localhost:4096"))
    }

    @Test
    fun editingCanRenameChangeTheAddressAndReplaceTheCredential() = runTest {
        val id = repository.addServer("Old Name", "http://localhost:4096", "old-pass")

        repository.updateServer(
            id = id,
            name = "New Name",
            baseUrl = "http://localhost:4098",
            credential = "new-pass",
            isDefault = false,
            trustUserCertificates = true,
        )

        val updated = repository.getServer(id)!!
        assertEquals("New Name", updated.name)
        assertEquals("http://localhost:4098", updated.baseUrl)
        assertEquals("new-pass", repository.getCredential(id))
        assertTrue(updated.trustUserCertificates)
        assertTrue("The new address must be the one that authenticates", credentialCache.holds("http://localhost:4098"))
        assertFalse("The old address must not keep the token", credentialCache.holds("http://localhost:4096"))
    }

    @Test
    fun editingWithNoCredentialKeepsTheStoredOne() = runTest {
        val id = repository.addServer("S1", "http://localhost:4096", "keep-me")

        repository.updateServer(
            id = id,
            name = "S1 renamed",
            baseUrl = "http://localhost:4096",
            credential = null,
            isDefault = true,
            trustUserCertificates = false,
        )

        assertEquals("keep-me", repository.getCredential(id))
        assertTrue(repository.getServer(id)!!.hasCredential)
    }

    @Test
    fun editingWithABlankCredentialClearsIt() = runTest {
        val id = repository.addServer("S1", "http://localhost:4096", "remove-me")

        repository.updateServer(
            id = id,
            name = "S1",
            baseUrl = "http://localhost:4096",
            credential = "",
            isDefault = true,
            trustUserCertificates = false,
        )

        assertNull(repository.getCredential(id))
        assertFalse(repository.getServer(id)!!.hasCredential)
        assertFalse(credentialCache.holds("http://localhost:4096"))
    }

    @Test
    fun editingCanMoveTheDefaultFlagBothWays() = runTest {
        val first = repository.addServer("S1", "http://localhost:4096", null)
        val second = repository.addServer("S2", "http://localhost:4097", null)

        repository.updateServer(second, "S2", "http://localhost:4097", null, isDefault = true, trustUserCertificates = false)
        assertTrue(repository.getServer(second)!!.isDefault)
        assertFalse(repository.getServer(first)!!.isDefault)

        repository.updateServer(first, "S1", "http://localhost:4096", null, isDefault = true, trustUserCertificates = false)
        assertTrue(repository.getServer(first)!!.isDefault)
        assertFalse(repository.getServer(second)!!.isDefault)
    }

    @Test
    fun anAddedAddressIsNormalizedToABaseUrl() = runTest {
        val id = repository.addServer("S1", "  localhost:4096/prefix/  ", null)
        assertEquals("http://localhost:4096/prefix", repository.getServer(id)!!.baseUrl)
    }

    @Test
    fun healthIsObservedAndLastSeenIsRecorded() = runTest {
        val id = repository.addServer("S1", "http://localhost:4096", null)

        repository.updateHealth(id, ServerHealth.REAUTH_REQUIRED)
        assertEquals(ServerHealth.REAUTH_REQUIRED, repository.getServer(id)!!.health)

        repository.observeServers().test {
            assertEquals(ServerHealth.REAUTH_REQUIRED, awaitItem().single().health)
            repository.updateHealth(id, ServerHealth.CONNECTED)
            assertEquals(ServerHealth.CONNECTED, awaitItem().single().health)
        }

        repository.updateLastSeen(id, 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, repository.getServer(id)!!.lastSeenAt)
    }

    @Test
    fun pairingRedeemsTheCodeStoresTheTokenAndValidatesTheServer() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse.Builder().code(200).body("""{"token":"session-token-999"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body(serverInfoJson(baseUrl)).build())

        val outcome = repository.addPairedServer(
            pairingLink = PairingLink(baseUrl = baseUrl, code = "pair-code-123"),
            serverName = "Pairing Test Server",
        )

        assertTrue("Expected success, got $outcome", outcome is AddServerOutcome.Success)
        val profile = (outcome as AddServerOutcome.Success).profile
        assertEquals("Pairing Test Server", profile.name)
        assertEquals(baseUrl, profile.baseUrl)
        assertEquals(ServerHealth.CONNECTED, profile.health)
        assertEquals("session-token-999", repository.getCredential(profile.id))
        assertTrue(profile.hasCredential)
        assertTrue(credentialCache.holds(baseUrl))
    }

    @Test
    fun pairingSendsNoCredentialAndAsksForJson() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse.Builder().code(200).body("""{"token":"t"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body(serverInfoJson(baseUrl)).build())

        repository.addPairedServer(PairingLink(baseUrl = baseUrl, code = "abc123_XYZ"))

        val redeem = server.takeRequest()
        assertEquals("/auth/connect/abc123_XYZ", redeem.url.encodedPath)
        assertEquals("application/json", redeem.headers["Accept"])
        assertNull("The redemption route needs no credential", redeem.headers["Authorization"])
    }

    @Test
    fun aUsedOrExpiredPairingCodeIsReportedAndNothingIsSaved() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse.Builder().code(401).body("""{"_tag":"UnauthorizedError"}""").build())

        val outcome = repository.addPairedServer(PairingLink(baseUrl = baseUrl, code = "used"))

        assertEquals(
            AddServerErrorType.PAIRING_CODE_REJECTED,
            (outcome as AddServerOutcome.Failure).errorType,
        )
        assertTrue(repository.getAllServers().isEmpty())
    }

    @Test
    fun aRejectedTokenIsReportedAsUnauthorizedAndNothingIsSaved() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse.Builder().code(200).body("""{"token":"rotated-out"}""").build())
        server.enqueue(
            MockResponse.Builder().code(401).body("""{"_tag":"UnauthorizedError"}""").build(),
        )

        val outcome = repository.addPairedServer(PairingLink(baseUrl = baseUrl, code = "code"))

        assertEquals(AddServerErrorType.UNAUTHORIZED, (outcome as AddServerOutcome.Failure).errorType)
        assertTrue(repository.getAllServers().isEmpty())
    }

    @Test
    fun aServerThatAnswersNothingIsReportedAsUnreachable() = runTest {
        val closed = MockWebServer().apply { start() }
        val baseUrl = closed.url("/").toString().trimEnd('/')
        closed.close()

        val outcome = repository.addManualServer("Nothing", baseUrl, null)

        assertEquals(AddServerErrorType.UNREACHABLE, (outcome as AddServerOutcome.Failure).errorType)
        assertTrue(repository.getAllServers().isEmpty())
    }

    @Test
    fun anAddressWithoutASchemeIsAcceptedAndTreatedAsHttp() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse.Builder().code(200).body(serverInfoJson(baseUrl)).build())

        val outcome = repository.addManualServer("Box", baseUrl.substringAfter("://"), "pw")

        assertTrue("Expected success, got $outcome", outcome is AddServerOutcome.Success)
        assertEquals("pw", repository.getCredential((outcome as AddServerOutcome.Success).profile.id))
    }

    @Test
    fun aManualEntryWithTheWrongPasswordIsReportedAsUnauthorized() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder()
                .code(401)
                .body("""{"_tag":"UnauthorizedError","message":"Authentication required"}""")
                .build()
        }

        val outcome = repository.addManualServer("Box", baseUrl, "wrong")

        assertEquals(AddServerErrorType.UNAUTHORIZED, (outcome as AddServerOutcome.Failure).errorType)
        assertTrue(repository.getAllServers().isEmpty())
    }

    @Test
    fun anUnusableManualAddressIsReportedWithoutTellingTheServer() = runTest {
        val outcome = repository.addManualServer("Box", "not a url", null)

        assertEquals(AddServerErrorType.UNREACHABLE, (outcome as AddServerOutcome.Failure).errorType)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun editingValidatesBeforeSavingAndKeepsTheOldValuesOnFailure() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val id = repository.addServer("Original", baseUrl, "original-pass")

        server.enqueue(MockResponse.Builder().code(401).body("""{"_tag":"UnauthorizedError"}""").build())
        val outcome = repository.editAndValidateServer(
            id = id,
            name = "Renamed",
            baseUrl = baseUrl,
            password = "wrong",
            isDefault = true,
            trustUserCertificates = false,
        )

        assertEquals(AddServerErrorType.UNAUTHORIZED, (outcome as AddServerOutcome.Failure).errorType)
        val unchanged = repository.getServer(id)!!
        assertEquals("Original", unchanged.name)
        assertEquals("original-pass", repository.getCredential(id))
    }

    @Test
    fun editingWithABlankPasswordKeepsTheStoredCredentialAndSaves() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse.Builder().code(200).body(serverInfoJson(baseUrl)).build())
        val id = repository.addServer("Original", baseUrl, "original-pass")

        server.enqueue(MockResponse.Builder().code(200).body(serverInfoJson(baseUrl)).build())
        val outcome = repository.editAndValidateServer(
            id = id,
            name = "Renamed",
            baseUrl = baseUrl,
            password = "",
            isDefault = true,
            trustUserCertificates = false,
        )

        assertTrue("Expected success, got $outcome", outcome is AddServerOutcome.Success)
        assertEquals("Renamed", repository.getServer(id)!!.name)
        assertEquals("original-pass", repository.getCredential(id))
    }

    private fun serverInfoJson(baseUrl: String) =
        """{"version":"2.0.18","pid":4321,"urls":["$baseUrl"],"paths":{"tmp":"/tmp"}}"""
}
