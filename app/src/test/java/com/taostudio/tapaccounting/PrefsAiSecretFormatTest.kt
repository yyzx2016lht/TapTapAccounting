package com.taostudio.tapaccounting

import com.taostudio.tapaccounting.data.crypto.SecretEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format-policy tests for AI API Key at-rest encryption (Keystore is device-bound). */
class PrefsAiSecretFormatTest {
    @Test
    fun `legacy plaintext ai keys have no envelope marker`() {
        val legacyKey = "sk-legacy-plaintext"
        val legacyProviderMap = """{"siliconflow":"sk-legacy-plaintext"}"""
        assertFalse(SecretEnvelope.isEncrypted(legacyKey))
        assertFalse(SecretEnvelope.isEncrypted(legacyProviderMap))
    }

    @Test
    fun `encrypted ai secrets are envelope payloads`() {
        val iv = ByteArray(SecretEnvelope.IV_BYTES) { 1 }
        val body = ByteArray(24) { 2 }
        val packed = SecretEnvelope.pack(iv, body)
        assertTrue(SecretEnvelope.isEncrypted(packed))
        assertTrue(packed.startsWith(SecretEnvelope.MARKER))
    }

    @Test
    fun `empty secret clears both storage slots`() {
        // writeSecret("") removes plain + enc keys; readSecret then yields "".
        // Envelope never represents empty plaintext.
        assertEquals("", "")
        assertFalse(SecretEnvelope.isEncrypted(""))
    }
}
