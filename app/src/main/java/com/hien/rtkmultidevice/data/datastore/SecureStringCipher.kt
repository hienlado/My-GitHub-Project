package com.hien.rtkmultidevice.data.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small settings values before they are persisted in DataStore.
 *
 * DataStore remains the storage layer, while the AES key is non-exportable and
 * owned by Android Keystore.
 */
class SecureStringCipher {
    private val keyAlias = "rtk_field_settings_key"
    private val keyStoreType = "AndroidKeyStore"
    private val transformation = "AES/GCM/NoPadding"
    private val encryptedPrefix = "ks1:"

    fun encrypt(value: String): String {
        if (value.isEmpty()) return ""

        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return encryptedPrefix +
            Base64.encodeToString(iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        if (!value.startsWith(encryptedPrefix)) return value

        return runCatching {
            val payload = value.removePrefix(encryptedPrefix)
            val parts = payload.split(":", limit = 2)
            require(parts.size == 2)

            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(keyStoreType).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreType)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
