package com.taostudio.tapaccounting.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretEnvelopeTest {
    private val iv = ByteArray(SecretEnvelope.IV_BYTES) { (it + 1).toByte() }
    private val ciphertext = ByteArray(32) { (it * 3).toByte() }

    @Test
    fun `pack then unpack round-trips iv and ciphertext`() {
        val packed = SecretEnvelope.pack(iv, ciphertext)
        assertTrue(SecretEnvelope.isEncrypted(packed))

        val unpacked = SecretEnvelope.unpack(packed)
        requireNotNull(unpacked)
        assertArrayEquals(iv, unpacked.first)
        assertArrayEquals(ciphertext, unpacked.second)
    }

    @Test
    fun `plaintext values are not treated as encrypted`() {
        assertFalse(SecretEnvelope.isEncrypted("plain-password"))
        assertFalse(SecretEnvelope.isEncrypted(""))
        assertFalse(SecretEnvelope.isEncrypted(null))
        assertNull(SecretEnvelope.unpack("plain-password"))
    }

    @Test
    fun `rejects invalid iv length and empty ciphertext`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SecretEnvelope.pack(ByteArray(8), ciphertext)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SecretEnvelope.pack(iv, ByteArray(0))
        }
    }

    @Test
    fun `malformed payloads unpack to null`() {
        assertNull(SecretEnvelope.unpack(SecretEnvelope.MARKER + "!!!not-base64!!!"))
        assertNull(SecretEnvelope.unpack(SecretEnvelope.MARKER))
        // IV only, no ciphertext body.
        val ivOnly = SecretEnvelope.MARKER +
            java.util.Base64.getEncoder().encodeToString(ByteArray(SecretEnvelope.IV_BYTES))
        assertNull(SecretEnvelope.unpack(ivOnly))
    }

    @Test
    fun `marker distinguishes ciphertext from leftover plaintext`() {
        val packed = SecretEnvelope.pack(iv, ciphertext)
        assertTrue(SecretEnvelope.isEncrypted(packed))
        assertFalse(SecretEnvelope.isEncrypted(packed.removePrefix(SecretEnvelope.MARKER)))
        assertEquals(SecretEnvelope.MARKER, packed.take(SecretEnvelope.MARKER.length))
    }
}
