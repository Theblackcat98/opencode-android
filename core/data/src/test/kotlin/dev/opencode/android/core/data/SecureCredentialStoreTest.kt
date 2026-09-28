package dev.opencode.android.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.android.core.data.security.AndroidKeystoreCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureCredentialStoreTest {

    private lateinit var store: AndroidKeystoreCredentialStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = AndroidKeystoreCredentialStore(context, prefName = "test_creds_" + System.currentTimeMillis())
        store.clearAll()
    }

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
}
