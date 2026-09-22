package com.taostudio.tapaccounting.data.backup

import android.content.Context
import android.content.SharedPreferences
import com.taostudio.tapaccounting.data.crypto.KeystoreSecretBox
import com.taostudio.tapaccounting.data.crypto.SecretEnvelope

/**
 * Cloud-backup WebDAV password kept in Android Keystore-encrypted prefs.
 *
 * Historical builds stored the password in plaintext under [LEGACY_KEY_PASS].
 * [loadPassword] migrates that value on first read and never leaves plaintext behind.
 */
object CloudBackupCredentials {
    const val PREFS_NAME = "tap_cloud_backup_prefs"
    const val KEY_PASS_ENC = "webdav_pass_enc"
    const val LEGACY_KEY_PASS = "webdav_pass"
    private const val ALIAS = "flip_cloud_backup"

    fun savePassword(context: Context, password: String) {
        val editor = prefs(context).edit()
        if (password.isEmpty()) {
            editor.remove(KEY_PASS_ENC).remove(LEGACY_KEY_PASS).apply()
            return
        }
        editor
            .putString(KEY_PASS_ENC, KeystoreSecretBox.encrypt(ALIAS, password))
            .remove(LEGACY_KEY_PASS)
            .apply()
    }

    fun loadPassword(context: Context): String? {
        val sp = prefs(context)
        val encrypted = sp.getString(KEY_PASS_ENC, null)
        if (SecretEnvelope.isEncrypted(encrypted)) {
            val decrypted = KeystoreSecretBox.decrypt(ALIAS, encrypted!!)
            if (decrypted != null) return decrypted
        }
        val legacy = sp.getString(LEGACY_KEY_PASS, null)
        if (legacy.isNullOrEmpty()) return null
        // Auto-migrate plaintext left by older builds.
        savePassword(context, legacy)
        return legacy
    }

    /** Drops any leftover plaintext password without touching the encrypted copy. */
    fun clearLegacyPlaintext(context: Context) {
        prefs(context).edit().remove(LEGACY_KEY_PASS).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
