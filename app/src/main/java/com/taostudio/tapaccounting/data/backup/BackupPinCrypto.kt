package com.taostudio.tapaccounting.data.backup

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份中 AI 凭据的 PIN 保护。
 * - 使用 4 位数字 PIN
 * - PBKDF2 派生密钥 + AES-GCM 加密
 */
object BackupPinCrypto {
    private const val FIELD_API_KEY = "ai_api_key_v1"
    private const val FIELD_API_KEY_ENC = "ai_api_key_enc_v1"
    private const val FIELD_PROVIDER_KEYS = "ai_provider_keys_v1"
    private const val FIELD_PROVIDER_KEYS_ENC = "ai_provider_keys_enc_v1"

    private const val ITERATIONS = 60_000
    private const val KEY_BITS = 256
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12

    private val pinRegex = Regex("^\\d{4}$")

    fun hasEncryptedApi(settings: JSONObject): Boolean {
        return settings.has(FIELD_API_KEY_ENC) || settings.has(FIELD_PROVIDER_KEYS_ENC)
    }

    /**
     * 将 settings 中的明文 AI 凭据替换为加密字段。
     */
    fun encryptApiKeyInSettings(settings: JSONObject, pin: String): JSONObject {
        requireValidPin(pin)
        encryptFieldIfPresent(settings, pin, FIELD_API_KEY, FIELD_API_KEY_ENC)
        encryptFieldIfPresent(settings, pin, FIELD_PROVIDER_KEYS, FIELD_PROVIDER_KEYS_ENC)
        return settings
    }

    /**
     * 若 settings 中存在加密 AI 凭据，则尝试用 PIN 解密恢复明文字段。
     */
    fun decryptApiKeyInSettings(settings: JSONObject, pin: String): JSONObject {
        requireValidPin(pin)
        decryptFieldIfPresent(settings, pin, FIELD_API_KEY, FIELD_API_KEY_ENC, "API Key")
        decryptFieldIfPresent(settings, pin, FIELD_PROVIDER_KEYS, FIELD_PROVIDER_KEYS_ENC, "提供商 API Key")
        return settings
    }

    private fun encryptFieldIfPresent(
        settings: JSONObject,
        pin: String,
        plainField: String,
        encField: String
    ) {
        val plain = settings.optString(plainField, "")
        if (plain.isBlank()) return

        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(pin, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))

        val payload = JSONObject()
            .put("v", 1)
            .put("kdf", "PBKDF2WithHmacSHA256")
            .put("iter", ITERATIONS)
            .put("salt", Base64.getEncoder().encodeToString(salt))
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .put("ct", Base64.getEncoder().encodeToString(cipherText))

        settings.remove(plainField)
        settings.put(encField, payload)
    }

    private fun decryptFieldIfPresent(
        settings: JSONObject,
        pin: String,
        plainField: String,
        encField: String,
        labelForError: String
    ) {
        if (!settings.has(encField)) return

        val payloadAny = settings.get(encField)
        val payload = when (payloadAny) {
            is JSONObject -> payloadAny
            is String -> JSONObject(payloadAny)
            else -> throw IllegalArgumentException("备份中的加密 $labelForError 格式不受支持")
        }

        val iter = payload.optInt("iter", ITERATIONS)
        val salt = Base64.getDecoder().decode(payload.getString("salt"))
        val iv = Base64.getDecoder().decode(payload.getString("iv"))
        val ct = Base64.getDecoder().decode(payload.getString("ct"))

        try {
            val key = deriveKey(pin, salt, iter)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(ct)
            val value = String(plain, StandardCharsets.UTF_8)

            settings.remove(encField)
            settings.put(plainField, value)
        } catch (_: Exception) {
            throw IllegalArgumentException("PIN错误，无法解密备份中的 $labelForError")
        }
    }

    private fun deriveKey(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val encoded = factory.generateSecret(spec).encoded
        return SecretKeySpec(encoded, "AES")
    }

    private fun requireValidPin(pin: String) {
        require(pinRegex.matches(pin)) { "PIN 必须是 4 位数字" }
    }
}
