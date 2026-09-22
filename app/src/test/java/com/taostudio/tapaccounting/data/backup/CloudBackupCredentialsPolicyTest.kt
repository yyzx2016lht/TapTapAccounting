package com.taostudio.tapaccounting.data.backup

import com.taostudio.tapaccounting.data.crypto.SecretEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure migration-policy tests for [CloudBackupCredentials] storage layout.
 * Keystore crypto itself is device-bound and covered by on-device verification.
 */
class CloudBackupCredentialsPolicyTest {
    @Test
    fun `legacy and encrypted keys are distinct`() {
        assertTrue(CloudBackupCredentials.KEY_PASS_ENC != CloudBackupCredentials.LEGACY_KEY_PASS)
        assertEquals("webdav_pass", CloudBackupCredentials.LEGACY_KEY_PASS)
        assertEquals("webdav_pass_enc", CloudBackupCredentials.KEY_PASS_ENC)
        assertEquals("tap_cloud_backup_prefs", CloudBackupCredentials.PREFS_NAME)
    }

    @Test
    fun `encrypted marker is required for the encrypted key`() {
        // A leftover plaintext value under the encrypted key must not be trusted.
        assertFalse(SecretEnvelope.isEncrypted("plain-password"))
        assertTrue(SecretEnvelope.isEncrypted(SecretEnvelope.MARKER + "AAAA"))
        assertNull(SecretEnvelope.unpack("plain-password"))
    }

    @Test
    fun `envelope layout is iv-ciphertext under marker`() {
        val iv = ByteArray(SecretEnvelope.IV_BYTES) { 7 }
        val body = ByteArray(16) { 9 }
        val packed = SecretEnvelope.pack(iv, body)
        assertTrue(packed.startsWith(CloudBackupCredentials.KEY_PASS_ENC.let { SecretEnvelope.MARKER }))
        val unpacked = requireNotNull(SecretEnvelope.unpack(packed))
        assertEquals(SecretEnvelope.IV_BYTES, unpacked.first.size)
        assertEquals(16, unpacked.second.size)
    }
}
