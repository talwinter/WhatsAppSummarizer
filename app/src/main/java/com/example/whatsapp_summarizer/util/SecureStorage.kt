package com.example.whatsapp_summarizer.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "whatsapp_summarizer_api_key"
        private const val PREFS_NAME = "secure_prefs"
        private const val PREFS_KEY_API_KEY = "encrypted_api_key"
        private const val PREFS_KEY_IV = "api_key_iv"
        private const val GCM_TAG_LENGTH = 128
    }

    init {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateKey()
        }
    }

    private fun generateKey() {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        keyGenerator.generateKey()
    }

    private fun getSecretKey(): SecretKey {
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun storeApiKey(apiKey: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        
        val iv = cipher.iv
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        
        prefs.edit()
            .putString(PREFS_KEY_API_KEY, Base64.encodeToString(encrypted, Base64.DEFAULT))
            .putString(PREFS_KEY_IV, Base64.encodeToString(iv, Base64.DEFAULT))
            .apply()
    }

    fun getApiKey(): String? {
        val encryptedKey = prefs.getString(PREFS_KEY_API_KEY, null) ?: return null
        val ivString = prefs.getString(PREFS_KEY_IV, null) ?: return null
        
        return try {
            val iv = Base64.decode(ivString, Base64.DEFAULT)
            val encrypted = Base64.decode(encryptedKey, Base64.DEFAULT)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("SecureStorage", "Failed to decrypt API key. Key may be corrupted or device changed.", e)
            // Clear corrupted key
            clearApiKey()
            null
        }
    }

    fun hasApiKey(): Boolean {
        return prefs.getString(PREFS_KEY_API_KEY, null) != null
    }

    fun clearApiKey() {
        prefs.edit()
            .remove(PREFS_KEY_API_KEY)
            .remove(PREFS_KEY_IV)
            .apply()
    }
}
