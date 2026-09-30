package com.taostudio.tapaccounting

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 分层日志。
 *
 * 落盘策略（app_logs.txt）：
 * - 默认（仅开启「运行日志」）：只写 I/W/E，够排查「发生了什么、哪里坏了」
 * - 完整日志（开发者模式）：额外写 D，以及脱敏/未脱敏的隐私细节
 * - 崩溃永远写 crash_logs.txt，不依赖任何开关
 *
 * 文件行格式（LogViewer 解析用，勿随意改）：
 *   2026-06-12 10:23:45.123|I|Tag|message
 * 续行（堆栈等）以空白开头，不带前缀。
 */
object Logger {
    enum class Level(val code: Char, val label: String, val priority: Int) {
        ERROR('E', "错误", 6),
        WARN('W', "警告", 5),
        INFO('I', "信息", 4),
        DEBUG('D', "调试", 3);

        companion object {
            fun fromCode(code: Char): Level? = values().firstOrNull { it.code == code }
        }
    }

    private const val LOG_FILE_NAME = "app_logs.txt"
    private const val CRASH_FILE_NAME = "crash_logs.txt"
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun isDebuggable(ctx: Context): Boolean {
        return (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun appContext(): Context? {
        return runCatching { TapApplication.app() }.getOrNull()
    }

    // --- 公开 API：带 Context（可写文件）---

    fun e(ctx: Context, tag: String, message: String, throwable: Throwable? = null) {
        log(ctx, Level.ERROR, tag, message, throwable)
    }

    fun w(ctx: Context, tag: String, message: String) {
        log(ctx, Level.WARN, tag, message, null)
    }

    fun i(ctx: Context, tag: String, message: String) {
        log(ctx, Level.INFO, tag, message, null)
    }

    /** 诊断细节：仅完整日志模式落盘；debug 包仍打 logcat */
    fun d(ctx: Context, tag: String, message: String) {
        log(ctx, Level.DEBUG, tag, message, null)
    }

    /**
     * 隐私相关细节。safeMessage 始终按当前级别策略写出；
     * debugDetail 仅在完整日志时写出，debug 包下会做脱敏。
     */
    fun dPriv(ctx: Context, tag: String, safeMessage: String, debugDetail: String) {
        d(ctx, tag, safeMessage)
        if (!Prefs.isPrivacyDebugLoggingEnabled(ctx)) return
        if (Prefs.isDeveloperFullLoggingEnabled(ctx)) {
            d(ctx, tag, "[privacy-full] ${debugDetail.take(2000)}")
            return
        }
        if (!isDebuggable(ctx)) return
        d(ctx, tag, "[privacy] ${maskSensitiveDebugDetail(debugDetail)}")
    }

    /** 崩溃——不依赖 Prefs，必须记录 */
    fun crash(ctx: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val entry = "[CRASH] Thread=${thread.name}\n$sw"
        Log.e("Crash", entry)
        writeLog(ctx, getCrashFile(ctx), entry)
    }

    // --- 兼容重载：无 Context（尽量从 Application 取）---

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val ctx = appContext() ?: run {
            Log.e(tag, message, throwable)
            return
        }
        e(ctx, tag, message, throwable)
    }

    fun w(tag: String, message: String) {
        val ctx = appContext() ?: run {
            Log.w(tag, message)
            return
        }
        w(ctx, tag, message)
    }

    fun i(tag: String, message: String) {
        val ctx = appContext() ?: run {
            Log.i(tag, message)
            return
        }
        i(ctx, tag, message)
    }

    fun d(tag: String, message: String) {
        val ctx = appContext() ?: run {
            Log.d(tag, message)
            return
        }
        d(ctx, tag, message)
    }

    // --- 内部 ---

    private fun log(ctx: Context, level: Level, tag: String, message: String, throwable: Throwable?) {
        val debuggable = isDebuggable(ctx)
        val fileLogging = Prefs.isLoggingEnabled(ctx)
        val fullLogging = Prefs.isDeveloperFullLoggingEnabled(ctx)

        // logcat：debug 包全量；正式包只保留有用级别，避免刷屏
        val toLogcat = when {
            debuggable -> true
            level.priority >= Level.WARN.priority -> true
            level == Level.INFO && fileLogging -> true
            else -> false
        }
        if (toLogcat) {
            val logcatMsg = if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message
            when (level) {
                Level.ERROR -> Log.e(tag, logcatMsg)
                Level.WARN -> Log.w(tag, logcatMsg)
                Level.INFO -> Log.i(tag, logcatMsg)
                Level.DEBUG -> Log.d(tag, logcatMsg)
            }
        }

        if (!fileLogging) return
        if (level == Level.DEBUG && !fullLogging) return

        val body = buildString {
            append(message)
            if (throwable != null) {
                append('\n')
                append(Log.getStackTraceString(throwable).trimEnd())
            }
        }
        val line = formatLine(level, tag, body)
        writeLog(ctx, getLogFile(ctx), line)
    }

    private fun formatLine(level: Level, tag: String, message: String): String {
        val time = timeFormat.format(Date())
        val first = "$time|${level.code}|$tag|${message.lineSequence().firstOrNull().orEmpty()}"
        val rest = message.lineSequence().drop(1).joinToString("\n") { line ->
            if (line.isEmpty()) line else "  $line"
        }
        return if (rest.isEmpty()) first else "$first\n$rest"
    }

    private fun writeLog(ctx: Context, file: File, content: String) {
        synchronized(this) {
            try {
                if (file.exists() && file.length() > MAX_LOG_SIZE) {
                    val bak = File(file.parent, file.name + ".bak")
                    bak.delete()
                    file.renameTo(bak)
                }
                file.appendText(content + "\n")
            } catch (e: Exception) {
                Log.e("Logger", "writeLog failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun maskSensitiveDebugDetail(raw: String): String {
        if (raw.isBlank()) return raw
        var text = raw
        text = text.replace(Regex("(?i)data:[^\\s]+;base64,[A-Za-z0-9+/=]+"), "data:<redacted-base64>")
        text = text.replace(Regex("[A-Za-z0-9+/=]{80,}"), "<redacted-base64>")
        text = text.replace(Regex("([A-Za-z]:\\\\[^\\s]+)|(/[^\\s]+)+"), "<redacted-path>")
        text = text.replace(Regex("(?i)(remark|remarks|merchant|shop|store)\\s*[=:]\\s*[^,;\\n]+"), "$1=<redacted>")
        text = text.replace(Regex("(?i)(amount|fee|price|total)\\s*[=:]\\s*-?\\d+(?:\\.\\d+)?"), "$1=<redacted>")
        return text.take(800)
    }

    fun getLogFile(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(dir, LOG_FILE_NAME)
    }

    fun getCrashFile(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(dir, CRASH_FILE_NAME)
    }

    fun clearLogs(ctx: Context) {
        getLogFile(ctx).delete()
        getLogFile(ctx).let { File(it.parent, it.name + ".bak").delete() }
        getCrashFile(ctx).delete()
    }
}
