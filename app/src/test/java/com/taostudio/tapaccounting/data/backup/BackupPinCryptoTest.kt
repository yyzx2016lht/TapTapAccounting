package com.taostudio.tapaccounting.data.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPinCryptoTest {
    @Test
    fun `new encryption requires 6 to 8 digit pin`() {
        listOf("12345", "123456789", "12345a", "abcdef").forEach { invalid ->
            assertThrows(invalid, IllegalArgumentException::class.java) {
                BackupPinCrypto.encryptApiKeyInSettings(JSONObject().put("ai_api_key_v1", "sk-x"), invalid)
            }
        }
        val ok = BackupPinCrypto.encryptApiKeyInSettings(
            JSONObject().put("ai_api_key_v1", "sk-secret"),
            "123456"
        )
        assertFalse(ok.has("ai_api_key_v1"))
        assertTrue(ok.has("ai_api_key_enc_v1"))
    }

    @Test
    fun `new payloads use 200k iterations`() {
        val settings = BackupPinCrypto.encryptApiKeyInSettings(
            JSONObject().put("ai_api_key_v1", "sk-secret"),
            "654321"
        )
        val payload = settings.getJSONObject("ai_api_key_enc_v1")
        assertEquals(BackupPinCrypto.DEFAULT_ITERATIONS, payload.getInt("iter"))
        assertEquals(200_000, payload.getInt("iter"))
        assertEquals(2, payload.getInt("v"))
    }

    @Test
    fun `decrypt accepts legacy 4 digit pin`() {
        val pin = "1234"
        val plain = "sk-legacy"
        val salt = ByteArray(16) { 1 }
        val iv = ByteArray(12) { 2 }
        val keyFactory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keySpec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)
        val key = javax.crypto.spec.SecretKeySpec(keyFactory.generateSecret(keySpec).encoded, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray())
        val payload = JSONObject()
            .put("v", 1)
            .put("kdf", "PBKDF2WithHmacSHA256")
            .put("iter", 60_000)
            .put("salt", java.util.Base64.getEncoder().encodeToString(salt))
            .put("iv", java.util.Base64.getEncoder().encodeToString(iv))
            .put("ct", java.util.Base64.getEncoder().encodeToString(ct))
        val settings = JSONObject().put("ai_api_key_enc_v1", payload)

        assertTrue(BackupPinCrypto.hasEncryptedApi(settings))
        val restored = BackupPinCrypto.decryptApiKeyInSettings(settings, pin)
        assertEquals(plain, restored.getString("ai_api_key_v1"))
    }

    @Test
    fun `wrong pin is rejected`() {
        val settings = BackupPinCrypto.encryptApiKeyInSettings(
            JSONObject().put("ai_api_key_v1", "sk-secret"),
            "123456"
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupPinCrypto.decryptApiKeyInSettings(settings, "999999")
        }
    }

    @Test
    fun `round trip with new pin`() {
        val source = JSONObject()
            .put("ai_api_key_v1", "sk-round")
            .put("ai_provider_keys_v1", """{"p":"k"}""")
        val encrypted = BackupPinCrypto.encryptApiKeyInSettings(source, "876543")
        assertFalse(encrypted.toString().contains("sk-round"))
        val decrypted = BackupPinCrypto.decryptApiKeyInSettings(encrypted, "876543")
        assertEquals("sk-round", decrypted.getString("ai_api_key_v1"))
        assertEquals("""{"p":"k"}""", decrypted.getString("ai_provider_keys_v1"))
    }
}
