package dev.opencode.android.core.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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

/** Raised when the platform Keystore cannot be used and no fallback was allowed. */
class CredentialStoreUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Android Keystore-backed AES-256-GCM credential store (plan §5.2).
 *
 * The AES key is generated inside the Keystore and never leaves it, so the stored bytes are
 * useless on a different device. Each value carries its own random IV, and a tampered record
 * fails to decrypt and reads back as no credential, which sends the app down the re-pair path
 * instead of authenticating with something the attacker chose.
 *
 * [allowInsecureFallbackKey] exists for JVM tests, where the platform Keystore does not exist. It
 * keeps the key in the same preferences file, which protects nothing, so it is off by default and
 * the app fails loudly instead: a device that cannot use the Keystore cannot hold a token.
 */
class AndroidKeystoreCredentialStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    prefName: String = PREFS_NAME,
    private val allowInsecureFallbackKey: Boolean = false,
    preferences: SharedPreferences? = null,
) : SecureCredentialStore {

    private val prefs: SharedPreferences =
        preferences ?: context.getSharedPreferences(prefName, Context.MODE_PRIVATE)

    private val secretKey: SecretKey by lazy { getOrCreateSecretKey() }

    private fun getOrCreateSecretKey(): SecretKey = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(keyAlias)) {
            keyStore.getKey(keyAlias, null) as SecretKey
        } else {
            KeyGenerator.getInstance("AES", ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(KEY_SIZE_BITS)
                        .build(),
                )
            }.generateKey()
        }
    } catch (e: Exception) {
        if (allowInsecureFallbackKey) {
            fallbackKey()
        } else {
            throw CredentialStoreUnavailableException(
                "The Android Keystore is unavailable, so credentials cannot be stored safely",
                e,
            )
        }
    }

    private fun fallbackKey(): SecretKey {
        val stored = prefs.getString(FALLBACK_KEY_PREF, null)
        if (stored != null) {
            return SecretKeySpec(Base64.decode(stored, Base64.NO_WRAP), "AES")
        }
        return KeyGenerator.getInstance("AES").apply { init(KEY_SIZE_BITS) }.generateKey().also { key ->
            prefs.edit()
                .putString(FALLBACK_KEY_PREF, Base64.encodeToString(key.encoded, Base64.NO_WRAP))
                .apply()
        }
    }

    override fun getCredential(serverId: String): String? {
        val encoded = prefs.getString(KEY_PREFIX + serverId, null) ?: return null
        return runCatching { decrypt(encoded) }.getOrNull()
    }

    override fun saveCredential(serverId: String, tokenOrPassword: String) {
        prefs.edit()
            .putString(KEY_PREFIX + serverId, encrypt(tokenOrPassword))
            .apply()
    }

    override fun removeCredential(serverId: String) {
        prefs.edit().remove(KEY_PREFIX + serverId).apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.allocate(IV_LENGTH_BYTES + iv.size + cipherText.size)
        buffer.putShort(iv.size.toShort())
        buffer.put(iv)
        buffer.put(cipherText)
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val buffer = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
        val ivLength = buffer.short.toInt() and 0xFFFF
        require(ivLength in 1..buffer.remaining()) { "The stored credential record is truncated" }
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val cipherText = ByteArray(buffer.remaining())
        buffer.get(cipherText)

        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "opencode_credentials_master_key"
        private const val PREFS_NAME = "opencode_secure_credentials"
        private const val KEY_PREFIX = "cred_"
        private const val FALLBACK_KEY_PREF = "insecure_fallback_key"
        private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val KEY_SIZE_BITS = 256
        private const val IV_LENGTH_BYTES = 2
    }
}

/** In-memory [SecureCredentialStore], for tests and for fakes. */
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
