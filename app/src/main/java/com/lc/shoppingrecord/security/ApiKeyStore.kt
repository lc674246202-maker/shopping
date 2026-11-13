package com.lc.shoppingrecord.security

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class ApiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(): String? {
        val encrypted = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(
                cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun hasKey(): Boolean = !load().isNullOrBlank()

    fun clear() {
        preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFERENCES = "secure_local_settings"
        private const val KEY_CIPHERTEXT = "deepseek_key_ciphertext"
        private const val KEY_IV = "deepseek_key_iv"
        private const val KEY_ALIAS = "shopping_record_deepseek_key"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
