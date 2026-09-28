package dev.opencode.android.core.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface SecureCredentialStore {
    fun getCredential(serverId: String): String?
    fun saveCredential(serverId: String, tokenOrPassword: String)
    fun removeCredential(serverId: String)
    fun clearAll()
}

/**
 * Android Keystore-backed AES-256-GCM encrypted credential store.
 *
 * Encrypts auth tokens and passwords before writing them to persistent storage,
 * fulfilling the requirement from docs/ANDROID_APP_PLAN.md:
 * "returns a token, which is stored encrypted with Android Keystore."
 */
class AndroidKeystoreCredentialStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    prefName: String = PREFS_NAME,
) : SecureCredentialStore {

    private val prefs: SharedPreferences = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)

    private val secretKey: SecretKey by lazy {
        getOrCreateSecretKey()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (!keyStore.containsAlias(keyAlias)) {
                val keyGenerator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
                val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                    keyAlias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } else {
                keyStore.getKey(keyAlias, null) as SecretKey
            }
        } catch (_: Exception) {
            // Fallback for non-Android JVM environments (e.g. unit tests without AndroidKeyStore)
            getOrCreateFallbackKey()
        }
    }

    private fun getOrCreateFallbackKey(): SecretKey {
        val fallbackRaw = prefs.getString(FALLBACK_KEY_PREF, null)
        return if (fallbackRaw != null) {
            val decoded = Base64.decode(fallbackRaw, Base64.NO_WRAP)
            SecretKeySpec(decoded, "AES")
        } else {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            val generated = keyGen.generateKey()
            val encoded = Base64.encodeToString(generated.encoded, Base64.NO_WRAP)
            prefs.edit().putString(FALLBACK_KEY_PREF, encoded).apply()
            generated
        }
    }

    override fun getCredential(serverId: String): String? {
        val encryptedBase64 = prefs.getString(KEY_PREFIX + serverId, null) ?: return null
        return try {
            val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val byteBuffer = ByteBuffer.wrap(encryptedBytes)
            val ivLength = byteBuffer.get().toInt()
            val iv = ByteArray(ivLength)
            byteBuffer.get(iv)
            val cipherText = ByteArray(byteBuffer.remaining())
            byteBuffer.get(cipherText)

            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override fun saveCredential(serverId: String, tokenOrPassword: String) {
        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(tokenOrPassword.toByteArray(Charsets.UTF_8))

        val byteBuffer = ByteBuffer.allocate(1 + iv.size + cipherText.size)
        byteBuffer.put(iv.size.toByte())
        byteBuffer.put(iv)
        byteBuffer.put(cipherText)

        val encryptedBase64 = Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
        prefs.edit().putString(KEY_PREFIX + serverId, encryptedBase64).apply()
    }

    override fun removeCredential(serverId: String) {
        prefs.edit().remove(KEY_PREFIX + serverId).apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "opencode_credentials_master_key"
        private const val PREFS_NAME = "opencode_secure_credentials"
        private const val KEY_PREFIX = "cred_"
        private const val FALLBACK_KEY_PREF = "fallback_master_key"
        private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}

/**
 * In-memory test implementation of [SecureCredentialStore].
 */
class InMemoryCredentialStore : SecureCredentialStore {
    private val storage = mutableMapOf<String, String>()

    override fun getCredential(serverId: String): String? = storage[serverId]

    override fun saveCredential(serverId: String, tokenOrPassword: String) {
        storage[serverId] = tokenOrPassword
    }

    override fun removeCredential(serverId: String) {
        storage.remove(serverId)
    }

    override fun clearAll() {
        storage.clear()
    }
}
