package com.taostudio.tapaccounting.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.annotation.VisibleForTesting
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
 * - 备份优先使用 SQLite 原生 `VACUUM INTO` 进行原子热备，自动合并 WAL 并整理碎片；失败时回退到 checkpoint 复制。
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
    internal const val BACKUP_DIR = "db_downgrade_backups"
    internal const val MAX_BACKUPS = 3

    /**
     * SQLite 底层操作抽象，用于原子热备、完整性校验与账单数统计。
     * Android 环境下默认使用 [SQLiteDatabase]；单元测试可注入 JDBC 实现。
     */
    interface DatabaseOperator {
        fun vacuumInto(sourceFile: File, targetFile: File)
        fun walCheckpoint(sourceFile: File)
        fun isIntegrityOk(dbFile: File): Boolean
        fun getBillsCount(dbFile: File): Long?
        fun copyFallback(sourceFile: File, targetFile: File)
    }

    internal object AndroidDatabaseOperator : DatabaseOperator {
        override fun vacuumInto(sourceFile: File, targetFile: File) {
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) targetFile.delete()
            File(targetFile.path + "-wal").delete()
            File(targetFile.path + "-shm").delete()

            SQLiteDatabase.openDatabase(
                sourceFile.path, null, SQLiteDatabase.OPEN_READWRITE
            )?.use { db ->
                val escaped = targetFile.absolutePath.replace("'", "''")
                db.execSQL("VACUUM INTO '$escaped'")
            }
        }

        override fun walCheckpoint(sourceFile: File) {
            try {
                SQLiteDatabase.openDatabase(
                    sourceFile.path, null, SQLiteDatabase.OPEN_READWRITE
                )?.use { db ->
                    try {
                        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null)?.use { cursor ->
                            cursor.moveToFirst()
                        }
                    } catch (e: Exception) {
                        db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "WAL checkpoint 失败", e)
            }
        }

        override fun isIntegrityOk(dbFile: File): Boolean {
            if (!dbFile.exists() || dbFile.length() == 0L) return false
            return try {
                SQLiteDatabase.openDatabase(
                    dbFile.path, null, SQLiteDatabase.OPEN_READONLY
                )?.use { db ->
                    db.rawQuery("PRAGMA quick_check", null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val result = cursor.getString(0)
                            result.equals("ok", ignoreCase = true)
                        } else false
                    }
                } ?: false
            } catch (e: Exception) {
                Log.e(TAG, "数据库完整性检查异常: ${dbFile.name}", e)
                false
            }
        }

        override fun getBillsCount(dbFile: File): Long? {
            if (!dbFile.exists() || dbFile.length() == 0L) return null
            return try {
                SQLiteDatabase.openDatabase(
                    dbFile.path, null, SQLiteDatabase.OPEN_READONLY
                )?.use { db ->
                    val tableExists = db.rawQuery(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='bills'", null
                    )?.use { cursor ->
                        cursor.moveToFirst()
                    } ?: false
                    if (!tableExists) return null

                    db.rawQuery("SELECT COUNT(*) FROM bills", null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getLong(0)
                        } else 0L
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "查询 bills 数量失败: ${dbFile.name}", e)
                null
            }
        }

        override fun copyFallback(sourceFile: File, targetFile: File) {
            walCheckpoint(sourceFile)
            targetFile.parentFile?.mkdirs()
            val targetWal = File(targetFile.path + "-wal")
            val targetShm = File(targetFile.path + "-shm")
            if (targetWal.exists()) targetWal.delete()
            if (targetShm.exists()) targetShm.delete()

            sourceFile.copyTo(targetFile, overwrite = true)
            val sourceWal = File(sourceFile.path + "-wal")
            if (sourceWal.exists()) sourceWal.copyTo(targetWal, overwrite = true)
            val sourceShm = File(sourceFile.path + "-shm")
            if (sourceShm.exists()) sourceShm.copyTo(targetShm, overwrite = true)
        }
    }

    @VisibleForTesting
    var databaseOperator: DatabaseOperator = AndroidDatabaseOperator

    /**
     * 若检测到降级，将主库备份到 [BACKUP_DIR]。
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
                val backupDir = File(context.filesDir, BACKUP_DIR)
                val backupFile = createBackup(backupDir, dbFile, dbVersion)
                if (backupFile == null || !isBackupComplete(dbFile, backupFile)) {
                    throw IllegalStateException(
                        "降级备份失败或不完整（db v$dbVersion → code v$currentCodeVersion），" +
                            "已阻止打开以免 destructive 清库丢数据"
                    )
                }
                Log.i(TAG, "备份完成: ${backupFile.absolutePath}")

                val newBackupBills = databaseOperator.getBillsCount(backupFile) ?: 0L
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val existingPath = prefs.getString(PREF_LAST_BACKUP_PATH, null)
                val existingFile = existingPath?.let { File(it) }
                val existingBills = if (existingFile != null && existingFile.exists()) {
                    databaseOperator.getBillsCount(existingFile) ?: 0L
                } else 0L

                // 防范空备份顶替有效备份：若新备份账单数为0，而已有备份有账单，保留有效备份作为待恢复目标
                val shouldUpdatePref = !(newBackupBills == 0L && existingBills > 0L)
                if (shouldUpdatePref) {
                    prefs.edit()
                        .putString(PREF_LAST_BACKUP_PATH, backupFile.absolutePath)
                        .putInt(PREF_LAST_BACKUP_VERSION, dbVersion)
                        .apply()
                } else {
                    Log.w(
                        TAG,
                        "新备份 ${backupFile.name} 账单数为 0，而现有备份 $existingPath 含 $existingBills 条账单，保留现有备份作为待恢复目标"
                    )
                }

                cleanupOldBackups(backupDir, newBackupBills)
            }
        } catch (e: IllegalStateException) {
            // 备份失败必须阻断清库：向上抛出，禁止 Room 打开
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "降级备份检查失败", e)
        }
    }

    /**
     * 备份完整性与关键数据校验：
     * 1. 备份文件必须存在且非空；
     * 2. SQLite 完整性检查（PRAGMA quick_check）必须通过；
     * 3. 若源库存在 bills 表且有记录，备份中的 bills 记录数必须不低于源库（防范 WAL 未合并导致备份空表的致命缺陷）。
     */
    fun isBackupComplete(source: File, backup: File): Boolean {
        if (!source.exists() || !backup.exists()) return false
        if (backup.length() == 0L) return false

        if (!databaseOperator.isIntegrityOk(backup)) {
            Log.e(TAG, "备份文件 SQLite 完整性检查失败: ${backup.name}")
            return false
        }

        val sourceBills = databaseOperator.getBillsCount(source)
        val backupBills = databaseOperator.getBillsCount(backup)

        if (sourceBills != null && sourceBills > 0L) {
            if (backupBills == null || backupBills < sourceBills) {
                Log.e(
                    TAG,
                    "备份账单数据量不匹配: sourceBills=$sourceBills, backupBills=${backupBills ?: 0}"
                )
                return false
            }
        }

        return true
    }

    /** 读 SQLite 文件头偏移 60 处的 `user_version`（大端 4 字节），避免为读版本单独开库连接。 */
    internal fun readDbVersion(dbFile: File): Int {
        return try {
            if (!dbFile.exists() || dbFile.length() < 100L) return -1
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

    internal fun createBackup(backupDir: File, dbFile: File, version: Int): File? {
        return try {
            if (!backupDir.exists()) backupDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backupFile = File(backupDir, "TapAccount_backup_v${version}_$timestamp.db")

            var backupSuccess = false

            // 1. 优先使用 VACUUM INTO 原子热备（单文件，自动 checkpoint WAL，整理碎片）
            try {
                databaseOperator.vacuumInto(dbFile, backupFile)
                if (backupFile.exists() && backupFile.length() > 0L) {
                    backupSuccess = true
                    Log.i(TAG, "VACUUM INTO 备份成功: ${backupFile.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "VACUUM INTO 失败，回退到 checkpoint 复制", e)
            }

            // 2. 若 VACUUM INTO 失败，回退到 checkpoint + 文件复制
            if (!backupSuccess) {
                try {
                    databaseOperator.copyFallback(dbFile, backupFile)
                    if (backupFile.exists() && backupFile.length() > 0L) {
                        backupSuccess = true
                        Log.i(TAG, "回退方案备份成功: ${backupFile.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "回退方案备份亦失败", e)
                }
            }

            if (!backupSuccess) {
                cleanupFailedBackup(backupFile)
                return null
            }

            // 3. 校验备份完整性及账单数据量
            if (!isBackupComplete(dbFile, backupFile)) {
                Log.e(TAG, "备份文件校验失败，清理残件")
                cleanupFailedBackup(backupFile)
                return null
            }

            backupFile
        } catch (e: Exception) {
            Log.e(TAG, "创建备份失败", e)
            null
        }
    }

    private fun cleanupFailedBackup(backupFile: File) {
        try {
            if (backupFile.exists()) backupFile.delete()
            val wal = File(backupFile.path + "-wal")
            if (wal.exists()) wal.delete()
            val shm = File(backupFile.path + "-shm")
            if (shm.exists()) shm.delete()
        } catch (e: Exception) {
            Log.w(TAG, "清理失败备份残留异常", e)
        }
    }

    internal fun cleanupOldBackups(backupDir: File, newBackupBills: Long = 0L) {
        try {
            if (!backupDir.exists()) return

            val backups = backupDir.listFiles { file ->
                file.name.startsWith("TapAccount_backup_") && file.name.endsWith(".db")
            }?.sortedByDescending { it.lastModified() } ?: return

            if (backups.size <= MAX_BACKUPS) return

            val backupBillsMap = backups.associateWith { (databaseOperator.getBillsCount(it) ?: 0L) }
            val hasNonEmptyBackups = backupBillsMap.values.any { it > 0L }

            val toDelete = mutableListOf<File>()
            val emptyBackups = backups.filter { (backupBillsMap[it] ?: 0L) == 0L }
            val nonEmptyBackups = backups.filter { (backupBillsMap[it] ?: 0L) > 0L }

            if (newBackupBills == 0L && hasNonEmptyBackups) {
                // 新备份为空（0账单），严禁淘汰任何含账单的有效备份！
                val excess = backups.size - MAX_BACKUPS
                val emptyToDelete = emptyBackups.reversed().take(excess)
                toDelete.addAll(emptyToDelete)
            } else {
                // 优先清理空备份，若超额再按时间淘汰最老的非空备份
                var remainingCount = backups.size
                for (empty in emptyBackups.reversed()) {
                    if (remainingCount <= MAX_BACKUPS) break
                    toDelete.add(empty)
                    remainingCount--
                }
                if (remainingCount > MAX_BACKUPS) {
                    for (nonEmpty in nonEmptyBackups.reversed()) {
                        if (remainingCount <= MAX_BACKUPS) break
                        toDelete.add(nonEmpty)
                        remainingCount--
                    }
                }
            }

            toDelete.forEach { old ->
                old.delete()
                File(old.path + "-wal").delete()
                File(old.path + "-shm").delete()
                Log.d(TAG, "清理旧备份: ${old.name}")
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

        if (!databaseOperator.isIntegrityOk(backupFile)) {
            return RestoreResult(false, "备份文件已损坏或不完整，无法恢复")
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
            val journalTarget = File(dbFile.path + "-journal")

            // 清理目标侧可能残留的 WAL/SHM/journal，防止旧 WAL 重放
            if (walTarget.exists()) walTarget.delete()
            if (shmTarget.exists()) shmTarget.delete()
            if (journalTarget.exists()) journalTarget.delete()

            // 若是通过 fallback 拷贝的备份，还原对应的 WAL/SHM；
            // 若是通过 VACUUM INTO 生成的干净单文件备份，目标侧保持无 WAL，防止脏日志重放
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
     * 执行 WAL checkpoint。
     */
    fun walCheckpoint(context: Context, dbName: String) {
        try {
            val dbFile = context.getDatabasePath(dbName)
            if (dbFile.exists()) {
                databaseOperator.walCheckpoint(dbFile)
            }
        } catch (e: Exception) {
            Log.w(TAG, "walCheckpoint 异常", e)
        }
    }

    data class RestoreResult(
        val success: Boolean,
        val message: String,
        val needUpgrade: Boolean = false,
        val backupVersion: Int = 0
    )
}
