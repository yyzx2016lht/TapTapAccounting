package com.taostudio.tapaccounting.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * APK 降级时的**自动**整库备份（与用户手动导出的 `.bak` 不是同一套东西）。
 *
 * ## 触发时机
 * - 在 [AppDatabase.getDatabase] 里、`Room.databaseBuilder().build()` **之前**调用 [backupIfDowngrade]。
 * - 检测 SQLite 文件头 `user_version`：若 **库版本 > 当前代码版本**，判定为「用户装了旧版 APK」。
 * - 随后 Room 的 `fallbackToDestructiveMigrationOnDowngrade()` 会清库重建；本类负责在清库前留一份 `.db` 副本。
 *
 * ## 不是什么
 * - **不是**用户去备份页手动导出；用户侧请用 [com.taostudio.tapaccounting.data.backup.BackupManager]。
 * - **不是**闪退后再备份；目的是**避免**因无法向下 Migration 而闪退。
 * - **不能**让旧版 APK 直接读新 schema；旧版打开后看到的是清库后的空库，完整数据在备份文件里。
 *
 * ## 生效前提
 * - 所安装的**旧版 APK 也必须包含本类 +** `fallbackToDestructiveMigrationOnDowngrade()`。
 * - 若降到更古老、没有这套逻辑的版本：**仍会闪退，且不会自动备份**。
 *
 * ## 存储与恢复
 * - 目录：`context.filesDir/db_downgrade_backups/`，卸载 App 会丢失。
 * - [listBackups] / [getLastBackupInfo] 供 UI 提示恢复。
 * - [restoreFromDowngradeBackup] 将备份 `.db` 拷回数据库路径（仅当 `backupVersion <= currentCodeVersion` 时允许）。
 * - [dismissPendingBackup] 清除待处理标记，不再提示。
 * - 备份前自动执行 WAL checkpoint，确保 WAL 中的写入已合并到主库。
 *
 * ## 改代码时注意
 * - 不要删掉 `backupIfDowngrade` 却保留 `fallbackToDestructiveMigrationOnDowngrade()`，否则降级只会无声丢数据。
 * - 不要为实现降级而加 `fallbackToDestructiveMigration()`（会误伤**升级**路径）。
 */
object DatabaseDowngradeHelper {

    private const val TAG = "DbDowngradeHelper"
    private const val PREF_NAME = "db_downgrade_prefs"
    private const val PREF_LAST_BACKUP_PATH = "last_backup_path"
    private const val PREF_LAST_BACKUP_VERSION = "last_backup_version"
    private const val BACKUP_DIR = "db_downgrade_backups"
    private const val MAX_BACKUPS = 3

    /**
     * 若检测到降级，将主库及 `-wal` / `-shm` 复制到 [BACKUP_DIR]。
     *
     * **P0-4**：检测到降级且备份失败时抛出 [IllegalStateException]，阻断后续 Room
     * `fallbackToDestructiveMigrationOnDowngrade()` 清库，避免「备份失败仍清空数据」。
     * 非降级路径的检查失败只打日志，不阻止打开。
     */
    fun backupIfDowngrade(context: Context, dbName: String, currentCodeVersion: Int) {
        try {
            val dbFile = context.getDatabasePath(dbName)
            if (!dbFile.exists()) return

            val dbVersion = readDbVersion(dbFile)
            if (dbVersion <= 0) return

            if (dbVersion > currentCodeVersion) {
                Log.w(TAG, "检测到降级: 数据库版本=$dbVersion, 代码版本=$currentCodeVersion, 开始备份...")
                walCheckpoint(context, dbName)
                val backupFile = createBackup(context, dbFile, dbVersion)
                if (backupFile == null || !isBackupComplete(dbFile, backupFile)) {
                    throw IllegalStateException(
                        "降级备份失败或不完整（db v$dbVersion → code v$currentCodeVersion），" +
                            "已阻止打开以免 destructive 清库丢数据"
                    )
                }
                Log.i(TAG, "备份完成: ${backupFile.absolutePath}")
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                    .putString(PREF_LAST_BACKUP_PATH, backupFile.absolutePath)
                    .putInt(PREF_LAST_BACKUP_VERSION, dbVersion)
                    .apply()
                cleanupOldBackups(context)
            }
        } catch (e: IllegalStateException) {
            // 备份失败必须阻断清库：向上抛出，禁止 Room 打开
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "降级备份检查失败", e)
        }
    }

    /** 备份完整性：文件存在且与源库字节数一致（主库已 checkpoint 时长度应相同）。 */
    fun isBackupComplete(source: File, backup: File): Boolean {
        if (!source.exists() || !backup.exists()) return false
        val sourceLen = source.length()
        return sourceLen > 0L && backup.length() == sourceLen
    }

    /** 读 SQLite 文件头偏移 60 处的 `user_version`（大端 4 字节），避免为读版本单独开库连接。 */
    private fun readDbVersion(dbFile: File): Int {
        return try {
            dbFile.inputStream().use { stream ->
                val header = ByteArray(100)
                val read = stream.read(header)
                if (read < 100) return -1

                val magic = String(header, 0, 16).trimEnd('\u0000')
                if (!magic.startsWith("SQLite format 3")) return -1

                ((header[60].toInt() and 0xFF) shl 24) or
                    ((header[61].toInt() and 0xFF) shl 16) or
                    ((header[62].toInt() and 0xFF) shl 8) or
                    (header[63].toInt() and 0xFF)
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取数据库版本失败", e)
            -1
        }
    }

    private fun createBackup(context: Context, dbFile: File, version: Int): File? {
        return try {
            val backupDir = File(context.filesDir, BACKUP_DIR)
            if (!backupDir.exists()) backupDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backupFile = File(backupDir, "TapAccount_backup_v${version}_$timestamp.db")

            dbFile.copyTo(backupFile, overwrite = true)

            val walFile = File(dbFile.path + "-wal")
            if (walFile.exists()) {
                walFile.copyTo(File(backupFile.path + "-wal"), overwrite = true)
            }

            val shmFile = File(dbFile.path + "-shm")
            if (shmFile.exists()) {
                shmFile.copyTo(File(backupFile.path + "-shm"), overwrite = true)
            }

            if (!isBackupComplete(dbFile, backupFile)) {
                Log.e(TAG, "备份文件不完整，删除残件")
                backupFile.delete()
                File(backupFile.path + "-wal").delete()
                File(backupFile.path + "-shm").delete()
                return null
            }

            backupFile
        } catch (e: Exception) {
            Log.e(TAG, "创建备份失败", e)
            null
        }
    }

    private fun cleanupOldBackups(context: Context) {
        try {
            val backupDir = File(context.filesDir, BACKUP_DIR)
            if (!backupDir.exists()) return

            val backups = backupDir.listFiles { file ->
                file.name.startsWith("TapAccount_backup_") && file.name.endsWith(".db")
            }?.sortedByDescending { it.lastModified() } ?: return

            if (backups.size > MAX_BACKUPS) {
                backups.drop(MAX_BACKUPS).forEach { old ->
                    old.delete()
                    File(old.path + "-wal").delete()
                    File(old.path + "-shm").delete()
                    Log.d(TAG, "清理旧备份: ${old.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理旧备份失败", e)
        }
    }

    /** 最近一次降级备份路径与库版本；无记录时返回 null。 */
    fun getLastBackupInfo(context: Context): Pair<String, Int>? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(PREF_LAST_BACKUP_PATH, null) ?: return null
        val version = prefs.getInt(PREF_LAST_BACKUP_VERSION, 0)
        return path to version
    }

    fun listBackups(context: Context): List<File> {
        val backupDir = File(context.filesDir, BACKUP_DIR)
        if (!backupDir.exists()) return emptyList()
        return backupDir.listFiles { file ->
            file.name.startsWith("TapAccount_backup_") && file.name.endsWith(".db")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** 是否存在尚未处理的降级备份（供 UI 层检测后弹出提示）。 */
    fun hasPendingDowngradeBackup(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(PREF_LAST_BACKUP_PATH, null) ?: return false
        return File(path).exists()
    }

    /**
     * 将降级备份恢复到主库位置。
     *
     * **前提**：`backupVersion <= currentCodeVersion`，否则旧版代码读新版 schema 会再次触发降级清库。
     * 调用方应在恢复成功后提示用户重启 App。
     *
     * @return [RestoreResult] 描述成功/失败及原因
     */
    fun restoreFromDowngradeBackup(
        context: Context,
        dbName: String,
        currentCodeVersion: Int
    ): RestoreResult {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val backupPath = prefs.getString(PREF_LAST_BACKUP_PATH, null)
            ?: return RestoreResult(false, "没有待恢复的降级备份")
        val backupVersion = prefs.getInt(PREF_LAST_BACKUP_VERSION, 0)
        val backupFile = File(backupPath)

        if (!backupFile.exists()) {
            prefs.edit().remove(PREF_LAST_BACKUP_PATH).remove(PREF_LAST_BACKUP_VERSION).apply()
            return RestoreResult(false, "备份文件已被删除")
        }

        if (backupVersion > currentCodeVersion) {
            return RestoreResult(
                false,
                "备份来自更高版本（v$backupVersion），当前代码版本 v$currentCodeVersion，请先升级再恢复",
                needUpgrade = true,
                backupVersion = backupVersion
            )
        }

        return try {
            // 清除 Room 单例，避免恢复后拿到旧连接
            AppDatabase.clearInstanceForRestore()

            val dbFile = context.getDatabasePath(dbName)
            dbFile.parentFile?.mkdirs()

            backupFile.copyTo(dbFile, overwrite = true)

            val walBackup = File(backupPath + "-wal")
            val shmBackup = File(backupPath + "-shm")
            val walTarget = File(dbFile.path + "-wal")
            val shmTarget = File(dbFile.path + "-shm")

            // 清理目标侧可能残留的 WAL/SHM
            walTarget.delete()
            shmTarget.delete()

            if (walBackup.exists()) walBackup.copyTo(walTarget, overwrite = true)
            if (shmBackup.exists()) shmBackup.copyTo(shmTarget, overwrite = true)

            prefs.edit().remove(PREF_LAST_BACKUP_PATH).remove(PREF_LAST_BACKUP_VERSION).apply()
            Log.i(TAG, "降级备份已恢复: ${backupFile.absolutePath}")
            RestoreResult(true, "恢复成功")
        } catch (e: Exception) {
            Log.e(TAG, "恢复降级备份失败", e)
            RestoreResult(false, "恢复失败: ${e.message}")
        }
    }

    /** 用户选择忽略降级备份提示时调用，清除待处理标记。 */
    fun dismissPendingBackup(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .remove(PREF_LAST_BACKUP_PATH)
            .remove(PREF_LAST_BACKUP_VERSION)
            .apply()
    }

    /**
     * 备份前执行 WAL checkpoint，将 WAL 中的已提交事务合并到主库文件。
     * 避免备份的 `.db` 文件遗漏 WAL 中的数据。
     */
    private fun walCheckpoint(context: Context, dbName: String) {
        try {
            val dbFile = context.getDatabasePath(dbName)
            if (!dbFile.exists()) return
            SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            }
            Log.d(TAG, "WAL checkpoint 完成")
        } catch (e: Exception) {
            // checkpoint 失败不阻断备份流程，WAL 文件会一起拷走
            Log.w(TAG, "WAL checkpoint 失败（继续备份 WAL 文件）", e)
        }
    }

    data class RestoreResult(
        val success: Boolean,
        val message: String,
        val needUpgrade: Boolean = false,
        val backupVersion: Int = 0
    )
}
