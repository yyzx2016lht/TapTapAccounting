package com.taostudio.tapaccounting

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程存活遥测。
 *
 * 这个应用是常驻后台的手势检测服务，排查"为什么服务没了"时最需要区分两件事：
 *  1. 进程自己崩了（Java 异常 / 原生 SIGSEGV）
 *  2. 被系统或 ROM 直接 force-stop 掉了
 *
 * Java 崩溃有全局 handler 兜底写进 crash_logs.txt；**原生崩溃（SIGSEGV）绕过整个
 * Java 层**，所以额外扫 logcat 的 crash buffer。除此之外，这里还维护一份带时间戳的
 * 心跳流水，让"进程在哪一刻消失、消失了多久"可以被逐条回溯——不依赖电脑侧 adb 常连。
 */
object ProcessExitLogger {
    private const val TAG = "ProcessExitLogger"
    private const val PREFS_NAME = "flip_prefs"
    private const val PREFS_KEY_LAST_HEARTBEAT = "last_overlay_heartbeat_ms"
    private const val PREFS_KEY_NATIVE_SIG = "last_native_crash_sig"
    private const val PREFS_KEY_DEATH_COUNT = "overlay_death_count"

    private const val LOGCAT_CRASH_TAIL_LINES = 400
    private const val CRASH_SCAN_OUTPUT = "native_crash_scan.txt"
    private const val HEARTBEAT_FILE = "keepalive_heartbeat.txt"
    private const val HEARTBEAT_MAX_BYTES = 4 * 1024 * 1024L

    /** 心跳写盘间隔：60s。太密只会撑大文件，太疏则死亡时刻定位不准 */
    private const val HEARTBEAT_INTERVAL_MS = 60_000L

    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var lastHeartbeatWriteAtMs = 0L

    fun onAppCreate(app: Application) {
        logStartupReport(app)
    }

    private fun logStartupReport(app: Application) {
        val prefs = app.getSharedPreferences(PREFS_NAME, 0)
        val lastHeartbeat = prefs.getLong(PREFS_KEY_LAST_HEARTBEAT, 0L)
        val gapSeconds = if (lastHeartbeat > 0L) {
            (System.currentTimeMillis() - lastHeartbeat) / 1000L
        } else {
            -1L
        }

        // 一次进程启动只可能由两件事造成：被系统重启，或上一次进程死亡后的外部复活。
        // 不管哪种，记录"距离上次心跳断了多久"就能标出死亡时刻。
        if (gapSeconds >= 0) {
            prefs.edit()
                .putInt(PREFS_KEY_DEATH_COUNT, deathCount(app) + 1)
                .apply()
        }
        val gapText = if (gapSeconds >= 0) "${gapSeconds}s" else "n/a"
        appendHeartbeat(app, "START gap=${gapText} deaths=${deathCount(app)}")
        Logger.d(app, TAG, "process start: heartbeat gap=$gapText deaths=${deathCount(app)}")

        detectNativeCrash(app, prefs)
    }

    /**
     * 按固定间隔记录一次心跳。调用方（看门狗）频率高于该间隔也没关系，内部会限流。
     *
     * 单行 append，即使进程在写入过程中被打断，也最多丢失最后一行，
     * 不会破坏已有历史。
     */
    fun recordHeartbeat(app: Application) {
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatWriteAtMs < HEARTBEAT_INTERVAL_MS) return
        lastHeartbeatWriteAtMs = now
        app.getSharedPreferences(PREFS_NAME, 0)
            .edit()
            .putLong(PREFS_KEY_LAST_HEARTBEAT, now)
            .apply()
        appendHeartbeat(app, "ALIVE")
    }

    fun deathCount(app: Application): Int =
        app.getSharedPreferences(PREFS_NAME, 0).getInt(PREFS_KEY_DEATH_COUNT, 0)

    fun getHeartbeatFile(app: Application): File =
        File(app.getExternalFilesDir(null) ?: app.filesDir, HEARTBEAT_FILE)

    private fun appendHeartbeat(app: Application, kind: String) {
        try {
            val file = getHeartbeatFile(app)
            if (file.exists() && file.length() > HEARTBEAT_MAX_BYTES) {
                file.delete()
            }
            val line = "${stampFormat.format(Date())} $kind\n"
            file.appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "appendHeartbeat failed: ${e.message}")
        }
    }

    /**
     * 扫描 logcat crash buffer 里属于本进程的原生崩溃。
     *
     * 崩溃行形如：
     *   F libc : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xc in tid 27410 (TapSensorThread), pid 25029 (o.tapaccounting)
     * 括号里的进程名是截断后的包名，所以用包名后缀匹配。
     *
     * 输出重定向到应用私有文件：不要用管道读子进程 stdout，在某些受限环境下管道不可用。
     */
    private fun detectNativeCrash(app: Application, prefs: android.content.SharedPreferences) {
        val signature = readLatestNativeCrashSignature(app) ?: return
        val key = signatureKey(signature)
        if (prefs.getString(PREFS_KEY_NATIVE_SIG, null) == key) return
        prefs.edit().putString(PREFS_KEY_NATIVE_SIG, key).apply()
        appendHeartbeat(app, "NATIVE-CRASH $signature")
        Logger.d(app, TAG, "NATIVE CRASH detected (bypassed Java handler): $signature")
    }

    /** 只比对崩溃时间戳与信号，避免同一次崩溃因内存地址变化被重复上报 */
    private fun signatureKey(line: String): String {
        val timestamp = line.substringBefore(' ').trim()
        val signal = Regex("""Fatal signal \d+ \((\w+)\)""").find(line)?.value ?: "unknown"
        return "$timestamp|$signal"
    }

    private fun readLatestNativeCrashSignature(app: Application): String? {
        val shortPackage = app.packageName.substringAfterLast('.')
        val outputFile = File(app.cacheDir, CRASH_SCAN_OUTPUT)
        return try {
            outputFile.delete()
            val process = ProcessBuilder(
                "logcat", "-d", "-b", "crash", "-t", LOGCAT_CRASH_TAIL_LINES.toString(), "-v", "threadtime"
            ).redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()
            process.waitFor()
            if (!outputFile.exists() || outputFile.length() == 0L) return null

            var latest: String? = null
            outputFile.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.contains("Fatal signal") && line.contains("($shortPackage")) {
                        latest = line.trim().take(300)
                    }
                }
            }
            latest
        } catch (e: Exception) {
            Log.w(TAG, "readLatestNativeCrashSignature failed: ${e.message}")
            null
        } finally {
            outputFile.delete()
        }
    }
}
