package com.vesper.baubuild.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.vesper.baubuild.core.auth.AuthPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun platformTokenStore(): TokenStore = KeystoreTokenStore()

/**
 * Encrypts the token with an AES-256-GCM key that never leaves the Android Keystore and stores
 * `iv:ciphertext` in private SharedPreferences (excluded from backup by being useless without the key).
 * Any decryption failure (e.g. key invalidated after a device restore) is treated as "signed out".
 */
class KeystoreTokenStore(
    private val alias: String = "baubuild_session_key",
    private val prefsName: String = "baubuild_secure_session",
) : TokenStore {
    private val prefs by lazy { AuthPlatform.context.getSharedPreferences(prefsName, Context.MODE_PRIVATE) }

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(KEY, null) ?: return@withContext null
        runCatching {
            val (iv, data) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(data).decodeToString()
        }.getOrElse {
            prefs.edit().remove(KEY).apply()
            null
        }
    }

    override suspend fun write(token: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(token.encodeToByteArray())
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
        prefs.edit().putString(KEY, value).commit()
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY).commit()
        Unit
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY = "opaque_token"
    }
}
