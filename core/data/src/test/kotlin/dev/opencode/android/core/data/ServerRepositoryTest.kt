package dev.opencode.android.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.opencode.android.core.data.repository.DefaultServerRepository
import dev.opencode.android.core.data.repository.ServerHealth
import dev.opencode.android.core.data.repository.ServerRepository
import dev.opencode.android.core.data.security.InMemoryCredentialStore
import dev.opencode.android.core.database.OpenCodeDatabase
import dev.opencode.android.core.network.PairingLink
import dev.opencode.android.core.network.ServerValidator
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var serverValidator: ServerValidator
    private lateinit var repository: ServerRepository
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OpenCodeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        credentialStore = InMemoryCredentialStore()
        okHttpClient = OkHttpClient()
        serverValidator = ServerValidator(okHttpClient = okHttpClient)
        mockWebServer = MockWebServer()
        mockWebServer.start()

        repository = DefaultServerRepository(
            serverDao = db.serverDao(),
            credentialStore = credentialStore,
            serverValidator = serverValidator,
            okHttpClient = okHttpClient,
        )
    }

    @After
    fun tearDown() {
        db.close()
        mockWebServer.close()
    }

    @Test
    fun firstServerAddedBecomesDefaultAutomatically() = runTest {
        val id = repository.addServer(
            name = "Dev Server",
            baseUrl = "http://localhost:4096",
            credential = "secret-pass",
            isDefault = false,
        )

        val server = repository.getServer(id)
        assertNotNull(server)
        assertTrue(server!!.isDefault)
        assertEquals("secret-pass", repository.getCredential(id))
        assertTrue(server.hasCredential)

        val defaultServer = repository.observeDefaultServer()
        defaultServer.test {
            val current = awaitItem()
            assertNotNull(current)
            assertEquals(id, current!!.id)
        }
    }

    @Test
    fun secondServerDoesNotStealDefaultUnlessExplicit() = runTest {
        val id1 = repository.addServer("S1", "http://localhost:4096", null)
        val id2 = repository.addServer("S2", "http://localhost:4097", null)

        val s1 = repository.getServer(id1)
        val s2 = repository.getServer(id2)

        assertTrue(s1!!.isDefault)
        assertEquals(false, s2!!.isDefault)

        repository.setDefaultServer(id2)
        val updatedS1 = repository.getServer(id1)
        val updatedS2 = repository.getServer(id2)

        assertEquals(false, updatedS1!!.isDefault)
        assertTrue(updatedS2!!.isDefault)
    }

    @Test
    fun removeServerDeletesEntityAndCredentials() = runTest {
        val id = repository.addServer("S1", "http://localhost:4096", "pass123")
        assertEquals("pass123", repository.getCredential(id))

        repository.removeServer(id)
        assertNull(repository.getServer(id))
        assertNull(repository.getCredential(id))
    }

    @Test
    fun updateServerModifiesFieldsAndCredentials() = runTest {
        val id = repository.addServer("Old Name", "http://localhost:4096", "old-pass")

        repository.updateServer(
            id = id,
            name = "New Name",
            baseUrl = "http://localhost:4098",
            credential = "new-pass",
            isDefault = false,
        )

        val updated = repository.getServer(id)
        assertNotNull(updated)
        assertEquals("New Name", updated!!.name)
        assertEquals("http://localhost:4098", updated.baseUrl)
        assertEquals("new-pass", repository.getCredential(id))
    }

    @Test
    fun redeemAndAddPairingLinkFlow() = runTest {
        val baseUrl = mockWebServer.url("/").toString().trimEnd('/')
        val code = "test-pair-code-123"
        val pairingLink = PairingLink(baseUrl = baseUrl, code = code)

        // 1. Enqueue response for GET /auth/connect/{code}
        mockWebServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"token":"session-token-999"}""")
                .build(),
        )

        // 2. Enqueue response for GET /api/info
        mockWebServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"version":"2.0.18","pid":4321,"urls":["$baseUrl"],"paths":{"tmp":"/tmp"}}""")
                .build(),
        )

        val result = repository.redeemAndAddPairingLink(pairingLink, serverName = "Pairing Test Server")
        assertTrue("Expected successful pairing, got $result", result.isSuccess)

        val profile = result.getOrNull()
        assertNotNull(profile)
        assertEquals("Pairing Test Server", profile!!.name)
        assertEquals(baseUrl, profile.baseUrl)
        assertEquals(ServerHealth.CONNECTED, profile.health)

        val storedCred = repository.getCredential(profile.id)
        assertEquals("session-token-999", storedCred)
    }
}
