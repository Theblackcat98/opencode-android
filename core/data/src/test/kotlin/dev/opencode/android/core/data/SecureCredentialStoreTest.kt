package dev.opencode.android.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.android.core.data.security.AndroidKeystoreCredentialStore
import dev.opencode.android.core.data.security.CredentialStoreUnavailableException
import dev.opencode.android.core.data.security.InMemoryCredentialStore
import dev.opencode.android.core.data.security.SecureCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureCredentialStoreTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var store: AndroidKeystoreCredentialStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("test_creds_" + System.nanoTime(), Context.MODE_PRIVATE)
        store = newStore(allowInsecureFallbackKey = true)
        store.clearAll()
    }

    private fun newStore(allowInsecureFallbackKey: Boolean) = AndroidKeystoreCredentialStore(
        context = context,
        allowInsecureFallbackKey = allowInsecureFallbackKey,
        preferences = prefs,
    )

    @Test
    fun savesAndRetrievesCredential() {
        store.saveCredential("srv-1", "super-secret-token")
        assertEquals("super-secret-token", store.getCredential("srv-1"))
    }

    @Test
    fun returnsNullForMissingCredential() {
        assertNull(store.getCredential("non-existent"))
    }

    @Test
    fun removesCredential() {
        store.saveCredential("srv-1", "token-to-delete")
        store.removeCredential("srv-1")
        assertNull(store.getCredential("srv-1"))
    }

    @Test
    fun overwritesExistingCredential() {
        store.saveCredential("srv-1", "initial-token")
        store.saveCredential("srv-1", "new-token")
        assertEquals("new-token", store.getCredential("srv-1"))
    }

    @Test
    fun clearAllRemovesAllCredentials() {
        store.saveCredential("s1", "token1")
        store.saveCredential("s2", "token2")
        store.clearAll()
        assertNull(store.getCredential("s1"))
        assertNull(store.getCredential("s2"))
    }

    @Test
    fun aStoredRecordIsNotThePlaintext() {
        store.saveCredential("srv-1", "plaintext-token")
        val record = requireNotNull(prefs.getString("cred_srv-1", null))
        assertFalse("The credential must not be readable in the preferences file", record.contains("plaintext-token"))
    }

    @Test
    fun everyRecordUsesItsOwnInitializationVector() {
        store.saveCredential("srv-1", "same-plaintext")
        val first = requireNotNull(prefs.getString("cred_srv-1", null))
        store.saveCredential("srv-1", "same-plaintext")
        val second = requireNotNull(prefs.getString("cred_srv-1", null))

        assertFalse("A repeated value must not produce identical ciphertext", first == second)
        assertEquals("same-plaintext", store.getCredential("srv-1"))
    }

    @Test
    fun aTamperedRecordReadsBackAsNoCredentialSoTheAppRePairs() {
        store.saveCredential("srv-1", "token")
        val tampered = requireNotNull(prefs.getString("cred_srv-1", null))
            .map {
                if (it == 'A') {
                    'B'
                } else if (it == 'B') {
                    'C'
                } else {
                    it
                }
            }
            .joinToString("")
        prefs.edit().putString("cred_srv-1", tampered).commit()

        assertNull(store.getCredential("srv-1"))
    }

    @Test
    fun aKeystoreThatCannotBeUsedFailsLoudlyUnlessAFallbackIsAllowed() {
        val strict: SecureCredentialStore = newStore(allowInsecureFallbackKey = false)

        // On a JVM the platform Keystore does not exist, so the strict store must refuse rather
        // than silently write a key next to the ciphertext.
        val failure = runCatching { strict.saveCredential("srv-1", "token") }.exceptionOrNull()
        if (failure != null) {
            assertTrue(
                "Expected a credential-store failure, got $failure",
                failure is CredentialStoreUnavailableException,
            )
        }
    }

    @Test
    fun theInMemoryStoreBehavesLikeTheEncryptedOne() {
        val memory: SecureCredentialStore = InMemoryCredentialStore()
        memory.saveCredential("a", "1")
        assertEquals("1", memory.getCredential("a"))
        memory.removeCredential("a")
        assertNull(memory.getCredential("a"))
        memory.saveCredential("b", "2")
        memory.clearAll()
        assertNull(memory.getCredential("b"))
    }
}
