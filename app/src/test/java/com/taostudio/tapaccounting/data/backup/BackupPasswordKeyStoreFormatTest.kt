package com.taostudio.tapaccounting.data.backup

import com.taostudio.tapaccounting.data.crypto.SecretEnvelope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Format-policy tests for [BackupPasswordKeyStore] derived-key storage.
 * Keystore round-trip is device-bound and verified on a real device.
 */
class BackupPasswordKeyStoreFormatTest {
    @Test
    fun `legacy derived keys are plaintext base64 without envelope marker`() {
        val legacy = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        assertFalse(SecretEnvelope.isEncrypted(legacy))
    }

    @Test
    fun `new derived keys use the keystore envelope marker`() {
        val iv = ByteArray(SecretEnvelope.IV_BYTES) { 2 }
        val body = ByteArray(48) { 3 }
        val packed = SecretEnvelope.pack(iv, body)
        assertTrue(SecretEnvelope.isEncrypted(packed))
    }

    @Test
    fun `envelope and legacy forms are mutually exclusive`() {
        val legacy = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val packed = SecretEnvelope.pack(
            ByteArray(SecretEnvelope.IV_BYTES) { 5 },
            ByteArray(40) { 6 }
        )
        assertTrue(SecretEnvelope.isEncrypted(packed))
        assertFalse(SecretEnvelope.isEncrypted(legacy))
        assertFalse(packed == legacy)
    }
}
