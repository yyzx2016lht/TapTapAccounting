package com.taostudio.tapaccounting.data.backup

import android.content.Context
import android.util.Base64
import com.taostudio.tapaccounting.data.crypto.KeystoreSecretBox
import com.taostudio.tapaccounting.data.crypto.SecretEnvelope

class BackupPasswordKeyUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Remembers only the PIN-derived key, encrypted with Android Keystore.
 *
 * The original PIN is never stored. Older builds wrote the derived key as plaintext
 * Base64; [load] migrates that value to ciphertext on first read.
 */
object BackupPasswordKeyStore {
    private const val PREFS = "backup_password_key"
    private const val KEY_DERIVED = "derived_key_v2"
    private const val KEY_SALT = "kdf_salt_v2"
    private const val KEY_ITERATIONS = "kdf_iterations_v2"
    private const val ALIAS = "flip_backup_password_key"

    fun isConfigured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_DERIVED)

    fun configure(context: Context, pin: String): BackupPasswordKeyMaterial {
        val material = BackupPasswordCrypto.create(pin)
        store(context, material)
        return material
    }

    fun load(context: Context): BackupPasswordKeyMaterial? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keyRaw = prefs.getString(KEY_DERIVED, null) ?: return null
        val saltRaw = prefs.getString(KEY_SALT, null)
            ?: throw BackupPasswordKeyUnavailableException("本机备份密钥缺少盐值，请重新设置备份密码")
        return try {
            val keyBytes = decodeStoredKey(context, keyRaw)
            BackupPasswordKeyMaterial(
                keyBytes = keyBytes,
                parameters = BackupPasswordKdfParameters(
                    salt = Base64.decode(saltRaw, Base64.NO_WRAP),
                    iterations = prefs.getInt(KEY_ITERATIONS, 0)
                )
            ).also(BackupPasswordKeyMaterial::requireValid)
        } catch (error: BackupPasswordKeyUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw BackupPasswordKeyUnavailableException("无法读取本机备份密钥，请重新设置备份密码", error)
        }
    }

    /** Stores a derived key; callers must only call this after PIN authentication succeeded. */
    fun store(context: Context, material: BackupPasswordKeyMaterial) {
        material.requireValid()
        val encryptedKey = try {
            KeystoreSecretBox.encryptBytes(ALIAS, material.keyBytes)
        } catch (error: Exception) {
            throw BackupPasswordKeyUnavailableException("无法加密本机备份密钥", error)
        }
        val committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DERIVED, encryptedKey)
            .putString(KEY_SALT, Base64.encodeToString(material.parameters.salt, Base64.NO_WRAP))
            .putInt(KEY_ITERATIONS, material.parameters.iterations)
            // Remove incomplete fields left by the abandoned Android Keystore implementation.
            .remove("wrapped_key_v1")
            .remove("nonce_v1")
            .remove("kdf_salt_v1")
            .remove("kdf_iterations_v1")
            .commit()
        if (!committed) throw BackupPasswordKeyUnavailableException("无法保存本机备份密钥")
    }

    /**
     * Accepts ciphertext envelopes and legacy plaintext Base64 keys.
     * Legacy values are re-encrypted in place so plaintext does not linger.
     */
    internal fun decodeStoredKey(context: Context, keyRaw: String): ByteArray {
        if (SecretEnvelope.isEncrypted(keyRaw)) {
            return KeystoreSecretBox.decryptBytes(ALIAS, keyRaw)
                ?: throw BackupPasswordKeyUnavailableException("无法解密本机备份密钥，请重新设置备份密码")
        }
        val legacyBytes = try {
            Base64.decode(keyRaw, Base64.NO_WRAP)
        } catch (error: Exception) {
            throw BackupPasswordKeyUnavailableException("本机备份密钥格式无效，请重新设置备份密码", error)
        }
        if (legacyBytes.isEmpty()) {
            throw BackupPasswordKeyUnavailableException("本机备份密钥为空，请重新设置备份密码")
        }
        runCatching {
            KeystoreSecretBox.encryptBytes(ALIAS, legacyBytes)
        }.onSuccess { encrypted ->
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DERIVED, encrypted)
                .commit()
        }
        return legacyBytes
    }
}
