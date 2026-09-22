package com.taostudio.tapaccounting.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-GCM box for small secrets (passwords, API keys, derived keys).
 *
 * Values produced by [encrypt] are device-bound and portable only as ciphertext.
 */
object KeystoreSecretBox {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun encrypt(alias: String, plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return SecretEnvelope.pack(cipher.iv, encrypted)
    }

    fun decrypt(alias: String, packed: String): String? {
        if (packed.isEmpty()) return ""
        val (iv, ciphertext) = SecretEnvelope.unpack(packed) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    fun encryptBytes(alias: String, plain: ByteArray): String {
        require(plain.isNotEmpty()) { "明文不能为空" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        return SecretEnvelope.pack(cipher.iv, cipher.doFinal(plain))
    }

    fun decryptBytes(alias: String, packed: String): ByteArray? {
        val (iv, ciphertext) = SecretEnvelope.unpack(packed) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    private fun key(alias: String): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }
}
