package com.taostudio.tapaccounting.data.crypto

import java.util.Base64

/**
 * Ciphertext envelope shared by Keystore-backed secret stores.
 *
 * Layout: Base64(iv || ciphertext). Prefix [MARKER] so callers can tell an
 * encrypted value from leftover plaintext without attempting decryption.
 */
object SecretEnvelope {
    const val MARKER = "ks1:"
    const val IV_BYTES = 12

    fun isEncrypted(value: String?): Boolean = value != null && value.startsWith(MARKER)

    fun pack(iv: ByteArray, ciphertext: ByteArray): String {
        require(iv.size == IV_BYTES) { "GCM IV 长度无效" }
        require(ciphertext.isNotEmpty()) { "密文不能为空" }
        val raw = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, raw, 0, iv.size)
        System.arraycopy(ciphertext, 0, raw, iv.size, ciphertext.size)
        return MARKER + Base64.getEncoder().encodeToString(raw)
    }

    /** Returns (iv, ciphertext) or null when the payload is not a valid envelope. */
    fun unpack(packed: String): Pair<ByteArray, ByteArray>? {
        if (!isEncrypted(packed)) return null
        val raw = runCatching {
            Base64.getDecoder().decode(packed.substring(MARKER.length))
        }.getOrNull() ?: return null
        if (raw.size <= IV_BYTES) return null
        return raw.copyOfRange(0, IV_BYTES) to raw.copyOfRange(IV_BYTES, raw.size)
    }
}
