package com.taostudio.tapaccounting.data.backup

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSecretPolicyProductionTest {
    @Test
    fun `prepareEncryptedModule strips plaintext api keys without pin`() {
        val source = """
            {"ai_api_key_v1":"sk-secret","ai_provider_keys_v1":"{\"a\":\"b\"}","ai_api_url_v1":"https://api.example.test/","ai_text_model_v1":"model"}
        """.trimIndent()
        val prepared = BackupSecretPolicy.prepareEncryptedModule("settings_ai_core", source, apiPin = null)
        assertFalse(prepared.contains("sk-secret"))
        assertFalse(prepared.contains("ai_api_key_v1"))
        assertTrue(prepared.contains("ai_text_model_v1"))
        BackupSecretPolicy.requireSecretFree(mapOf("settings_ai_core" to prepared))
    }

    @Test
    fun `prepareEncryptedModule strips webdav password`() {
        val source = """
            {"cloud_webdav_url_v1":"https://dav.example.test/dav/","cloud_webdav_user_v1":"user","cloud_webdav_pass_v1":"pw"}
        """.trimIndent()
        val prepared = BackupSecretPolicy.prepareEncryptedModule("settings_general_cloud", source, apiPin = null)
        assertFalse(prepared.contains("pw"))
        assertFalse(prepared.contains("cloud_webdav_pass_v1"))
        assertTrue(prepared.contains("cloud_webdav_user_v1"))
        BackupSecretPolicy.requireSecretFree(mapOf("settings_general_cloud" to prepared))
    }

    @Test
    fun `prepareEncryptedModule pin-encrypts api keys when pin is valid`() {
        val source = """
            {"ai_api_key_v1":"sk-secret","ai_api_url_v1":"https://api.example.test/"}
        """.trimIndent()
        val prepared = BackupSecretPolicy.prepareEncryptedModule("settings_ai_core", source, apiPin = "123456")
        assertFalse(prepared.contains("sk-secret"))
        assertFalse(prepared.contains("\"ai_api_key_v1\""))
        assertTrue(prepared.contains("ai_api_key_enc_v1"))
        // PIN-encrypted fields are allowed only in the encrypted-archive settings module.
        BackupSecretPolicy.requireSecretFree(
            mapOf("settings_ai_core" to prepared),
            allowedSecretModules = setOf("settings_ai_core")
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupSecretPolicy.requireSecretFree(mapOf("settings_ai_core" to prepared))
        }
    }

    @Test
    fun `invalid pin falls back to stripping secrets`() {
        val source = """{"ai_api_key_v1":"sk-secret"}"""
        val prepared = BackupSecretPolicy.prepareEncryptedModule("settings_ai_core", source, apiPin = "12")
        assertFalse(prepared.contains("sk-secret"))
        BackupSecretPolicy.requireSecretFree(mapOf("settings_ai_core" to prepared))
    }

    @Test
    fun `stripSecretValues removes nested forbidden keys`() {
        val source = """{"nested":{"password":"x","keep":1},"ai_api_key_v1":"y"}"""
        val stripped = BackupSecretPolicy.stripSecretValues("fixture", source)
        assertFalse(stripped.contains("password"))
        assertFalse(stripped.contains("ai_api_key_v1"))
        assertTrue(stripped.contains("keep"))
    }

    @Test
    fun `requireSecretFree allows listed secret modules`() {
        BackupSecretPolicy.requireSecretFree(
            mapOf(BackupModuleId.SHARED_SECRETS to "[]"),
            allowedSecretModules = setOf(BackupModuleId.SHARED_SECRETS)
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupSecretPolicy.requireSecretFree(mapOf(BackupModuleId.SHARED_SECRETS to "[]"))
        }
    }
}
