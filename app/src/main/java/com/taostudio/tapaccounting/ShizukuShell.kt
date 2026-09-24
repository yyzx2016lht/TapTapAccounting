package com.taostudio.tapaccounting

import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuShell {

    fun exec(cmd: String): String {
        if (!ShizukuSafe.isBinderAlive()) return ""

        return try {
            val commandArray = arrayOf("sh", "-c", cmd)

            // --- 核心修改：通过 Java 助手类来创建进程 ---
            val process = ShizukuHelper.createProcess(commandArray, null, null)

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.use { it.readText() }
            process.waitFor()
            result
        } catch (e: Exception) {
            android.util.Log.e("ShizukuShell", "Shell执行异常: ${e.message}")
            ""
        }
    }

    fun execBytes(cmd: String): ByteArray? {
        if (!ShizukuSafe.isBinderAlive()) return null

        return try {
            val commandArray = arrayOf("sh", "-c", cmd)
            val process = ShizukuHelper.createProcess(commandArray, null, null) ?: return null
            val bytes = process.inputStream.use { it.readBytes() }
            process.waitFor()
            bytes
        } catch (e: Exception) {
            android.util.Log.e("ShizukuShell", "Shell二进制执行异常: ${e.message}")
            null
        }
    }

    /**
     * 获取当前最顶层（Resumed）的应用包名
     */
    fun getForegroundApp(): String? {
        val output = exec("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity'")
        val regex = Regex("""([a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+)/""", RegexOption.IGNORE_CASE)
        return output.lineSequence()
            .mapNotNull { line -> regex.find(line)?.groupValues?.get(1) }
            .firstOrNull { it.contains('.') && it != "android" }
    }

    /**
     * 通过 Shizuku 执行深度保活：解除系统对本应用的所有后台限制
     */
    fun applyAggressivePersistence(pkg: String) {
        if (!ShizukuSafe.isBinderAlive()) return
        Thread {
            try {
                exec("dumpsys deviceidle whitelist +$pkg")
                exec("am set-standby-bucket $pkg active")
                exec("cmd appops set $pkg RUN_IN_BACKGROUND allow")
                exec("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
                exec("cmd appops set $pkg HIGH_SAMPLING_RATE_SENSORS allow")
                exec("cmd appops set $pkg android:access_background_sensor allow")
                exec("cmd appops set $pkg WAKE_LOCK allow")
                exec("cmd appops set $pkg SYSTEM_ALERT_WINDOW allow")
                // 关闭 Android 12+ 幽灵进程杀手（如果系统支持）
                // P2-22: 勿改全局 max_phantom_processes，影响整机
            } catch (e: Exception) {
                android.util.Log.e("ShizukuShell", "保活执行异常: ${e.message}")
            }
        }.start()
    }
}

