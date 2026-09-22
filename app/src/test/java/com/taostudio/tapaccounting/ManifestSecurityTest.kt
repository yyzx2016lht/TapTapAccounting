package com.taostudio.tapaccounting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Manifest security posture checks (P1-22). Parses the source Manifest so regressions
 * are caught in unit tests without an emulator.
 */
class ManifestSecurityTest {
    private val manifestText: String by lazy {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("../src/main/AndroidManifest.xml")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        file.readText()
    }

    /** Slice from a component's android:name through the end of its element. */
    private fun componentBlock(name: String): String {
        val marker = "android:name=\"$name\""
        val start = manifestText.indexOf(marker)
        if (start < 0) return ""
        val endActivity = manifestText.indexOf("</activity>", start)
        val endReceiver = manifestText.indexOf("</receiver>", start)
        val endSelf = manifestText.indexOf("/>", start)
        val end = listOf(endActivity, endReceiver, endSelf)
            .filter { it >= 0 }
            .minOrNull() ?: manifestText.length
        return manifestText.substring(start, end)
    }

    private fun isExportedTrue(name: String): Boolean =
        Regex("""android:exported="true"""").containsMatchIn(componentBlock(name))

    @Test
    fun `cleartext traffic is disabled`() {
        assertFalse(
            "usesCleartextTraffic must not be true",
            manifestText.contains("usesCleartextTraffic=\"true\"")
        )
    }

    @Test
    fun `custom restart broadcast is not exported`() {
        assertFalse(
            "RESTART_SERVICE must not be in any intent-filter",
            manifestText.contains("com.taostudio.tapaccounting.RESTART_SERVICE")
        )
    }

    @Test
    fun `backup activity is not exported to other apps`() {
        assertFalse("BackupActivity must not be exported", isExportedTrue(".BackupActivity"))
        assertFalse(
            "BackupActivity must not claim VIEW .bak from other apps",
            componentBlock(".BackupActivity").contains("pathPattern")
        )
    }

    @Test
    fun `quick start activity is not exported`() {
        assertFalse("QuickStartActivity must not be exported", isExportedTrue(".QuickStartActivity"))
    }

    @Test
    fun `boot receiver keeps system boot actions only`() {
        assertTrue(manifestText.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(manifestText.contains("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(
            "RESTART_SERVICE must not be registered",
            componentBlock(".BootReceiver").contains("RESTART_SERVICE")
        )
    }
}
