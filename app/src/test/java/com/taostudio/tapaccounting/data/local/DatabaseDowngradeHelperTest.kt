package com.taostudio.tapaccounting.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager

/**
 * 降级备份与恢复单元测试：
 * 1. 降级检测：dbVersion > currentCodeVersion 识别为降级；
 * 2. 真实完整性与账单数据量校验：源库有账单而备份账单为 0 时判定失败，阻断清库；
 * 3. 避免空备份顶替/淘汰有效备份：新备份账单为 0 时严禁淘汰 >0 账单的有效备份；
 * 4. VACUUM INTO 原子热备及 fallback 容灾测试；
 * 5. 恢复时单文件无 WAL 干净还原。
 */
class DatabaseDowngradeHelperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val originalOperator = DatabaseDowngradeHelper.databaseOperator

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        DatabaseDowngradeHelper.databaseOperator = JdbcDatabaseOperator()
    }

    @After
    fun tearDown() {
        DatabaseDowngradeHelper.databaseOperator = originalOperator
    }

    private fun createSqliteDb(
        file: File,
        version: Int = 43,
        billsCount: Int = 0,
        withBillsTable: Boolean = true
    ): File {
        file.parentFile?.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA user_version = $version")
                if (withBillsTable) {
                    stmt.execute("CREATE TABLE bills (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL, remark TEXT)")
                    for (i in 1..billsCount) {
                        stmt.execute("INSERT INTO bills (amount, remark) VALUES ($i.0, 'bill-$i')")
                    }
                }
            }
        }
        return file
    }

    @Test
    fun readDbVersion_readsUserVersionFromHeader() {
        val dbFile = createSqliteDb(tmp.newFile("test_v43.db"), version = 43)
        val version = DatabaseDowngradeHelper.readDbVersion(dbFile)
        assertEquals(43, version)
    }

    @Test
    fun readDbVersion_returnsNegativeForCorruptedFile() {
        val dummy = tmp.newFile("dummy.db").apply { writeBytes(ByteArray(50) { 0 }) }
        val version = DatabaseDowngradeHelper.readDbVersion(dummy)
        assertEquals(-1, version)
    }

    @Test
    fun isBackupComplete_falseWhenSourceOrBackupMissingOrZeroLength() {
        val src = tmp.newFile("src.db")
        val bak = tmp.newFile("bak.db")
        assertFalse(DatabaseDowngradeHelper.isBackupComplete(src, bak))

        createSqliteDb(src, billsCount = 5)
        assertFalse(DatabaseDowngradeHelper.isBackupComplete(src, bak)) // bak is 0 bytes
    }

    @Test
    fun isBackupComplete_falseWhenBackupIsCorrupted() {
        val src = createSqliteDb(tmp.newFile("src.db"), billsCount = 10)
        val bak = tmp.newFile("bak.db").apply { writeBytes(ByteArray(1024) { 0x7F }) }
        assertFalse(DatabaseDowngradeHelper.isBackupComplete(src, bak))
    }

    @Test
    fun isBackupComplete_falseWhenSourceHasBillsButBackupHasZeroBills() {
        // 核心生产事故复现：源库有账单，备份中 bills 却为 0
        val src = createSqliteDb(tmp.newFile("src.db"), billsCount = 37)
        val bak = createSqliteDb(tmp.newFile("bak.db"), billsCount = 0)
        assertFalse(DatabaseDowngradeHelper.isBackupComplete(src, bak))
    }

    @Test
    fun isBackupComplete_trueWhenBothValidAndBillsMatch() {
        val src = createSqliteDb(tmp.newFile("src.db"), billsCount = 37)
        val bak = createSqliteDb(tmp.newFile("bak.db"), billsCount = 37)
        assertTrue(DatabaseDowngradeHelper.isBackupComplete(src, bak))
    }

    @Test
    fun createBackup_usesVacuumIntoAndGeneratesCleanStandaloneFile() {
        val src = createSqliteDb(tmp.newFile("src_v43.db"), version = 43, billsCount = 20)
        val backupDir = tmp.newFolder("backups")

        val backup = DatabaseDowngradeHelper.createBackup(backupDir, src, 43)
        assertNotNull(backup)
        assertTrue(backup!!.exists())
        assertTrue(backup.length() > 0L)
        // VACUUM INTO 生成独立干净单文件，无额外 wal/shm
        assertFalse(File(backup.path + "-wal").exists())
        assertFalse(File(backup.path + "-shm").exists())

        assertEquals(20L, DatabaseDowngradeHelper.databaseOperator.getBillsCount(backup))
    }

    @Test
    fun createBackup_fallsBackToCopyWhenVacuumIntoFails() {
        val src = createSqliteDb(tmp.newFile("src_v43.db"), version = 43, billsCount = 15)
        val backupDir = tmp.newFolder("backups")

        // 模拟 VACUUM INTO 异常（如老版本 SQLite 不支持），验证 fallback 自动生效
        val throwingOperator = object : DatabaseDowngradeHelper.DatabaseOperator by DatabaseDowngradeHelper.databaseOperator {
            override fun vacuumInto(sourceFile: File, targetFile: File) {
                throw UnsupportedOperationException("VACUUM INTO not supported on this SQLite engine")
            }
        }
        DatabaseDowngradeHelper.databaseOperator = throwingOperator

        val backup = DatabaseDowngradeHelper.createBackup(backupDir, src, 43)
        assertNotNull(backup)
        assertTrue(backup!!.exists())
        assertEquals(15L, DatabaseDowngradeHelper.databaseOperator.getBillsCount(backup))
    }

    @Test
    fun cleanupOldBackups_protectsValidBackupsFromBeingEvictedByEmptyBackup() {
        val backupDir = tmp.newFolder("cleanup_backups")

        // 创建 3 个含有效账单的旧备份
        val valid1 = createSqliteDb(File(backupDir, "TapAccount_backup_v43_20261101_100000.db"), billsCount = 50)
        valid1.setLastModified(1000L)
        val valid2 = createSqliteDb(File(backupDir, "TapAccount_backup_v43_20261102_100000.db"), billsCount = 60)
        valid2.setLastModified(2000L)
        val valid3 = createSqliteDb(File(backupDir, "TapAccount_backup_v43_20261103_100000.db"), billsCount = 70)
        valid3.setLastModified(3000L)

        // 加入第 4 个备份，账单为 0（空备份）
        val empty4 = createSqliteDb(File(backupDir, "TapAccount_backup_v43_20261104_100000.db"), billsCount = 0)
        empty4.setLastModified(4000L)

        // 当新备份为 0 账单时执行清理：绝不能淘汰 valid1/valid2/valid3！
        DatabaseDowngradeHelper.cleanupOldBackups(backupDir, newBackupBills = 0L)

        assertTrue("含 50 条账单的旧备份必须保留", valid1.exists())
        assertTrue("含 60 条账单的旧备份必须保留", valid2.exists())
        assertTrue("含 70 条账单的旧备份必须保留", valid3.exists())
        assertFalse("超额的空备份应当被删除", empty4.exists())
    }

    @Test
    fun cleanupOldBackups_evictsOldestWhenAllNonEmpty() {
        val backupDir = tmp.newFolder("cleanup_backups_fifo")

        val b1 = createSqliteDb(File(backupDir, "TapAccount_backup_v43_20261101_100000.db"), billsCount = 10)
        b1.setLastModified(1000L)
        val b2 = createSqliteDb(File(backupDir, "TapAccount_backup_v43_20261102_100000.db"), billsCount = 20)
        b2.setLastModified(2000L)
        val b3 = createSqliteDb(File(backupDir, "TapAccount_backup_v43_20261103_100000.db"), billsCount = 30)
        b3.setLastModified(3000L)
        val b4 = createSqliteDb(File(backupDir, "TapAccount_backup_v43_20261104_100000.db"), billsCount = 40)
        b4.setLastModified(4000L)

        // 新备份为非空，按 FIFO 淘汰最老的一份
        DatabaseDowngradeHelper.cleanupOldBackups(backupDir, newBackupBills = 40L)

        assertFalse("最老的 b1 应当被淘汰", b1.exists())
        assertTrue(b2.exists())
        assertTrue(b3.exists())
        assertTrue(b4.exists())
    }

    @Test
    fun backupIfDowngrade_detectsDowngradeAndBlocksWhenBackupIncomplete() {
        val context = FakeTestContext(tmp.newFolder("app_context"))
        val dbFile = context.getDatabasePath("TapAccount_database")
        createSqliteDb(dbFile, version = 43, billsCount = 100)

        // 模拟备份生成时账单丢失（返回 0 账单）
        val buggyOperator = object : DatabaseDowngradeHelper.DatabaseOperator by DatabaseDowngradeHelper.databaseOperator {
            override fun vacuumInto(sourceFile: File, targetFile: File) {
                // 生成 0 账单的损坏备份
                createSqliteDb(targetFile, version = 43, billsCount = 0)
            }
            override fun copyFallback(sourceFile: File, targetFile: File) {
                createSqliteDb(targetFile, version = 43, billsCount = 0)
            }
        }
        DatabaseDowngradeHelper.databaseOperator = buggyOperator

        try {
            // 代码版本 42，数据库版本 43（降级场景）
            DatabaseDowngradeHelper.backupIfDowngrade(context, "TapAccount_database", currentCodeVersion = 42)
            fail("降级备份丢失账单数据时必须抛出 IllegalStateException 阻断清库")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("降级备份失败或不完整"))
        }
    }

    @Test
    fun backupIfDowngrade_doesNotTriggerWhenVersionNotDowngraded() {
        val context = FakeTestContext(tmp.newFolder("app_context_upgrade"))
        val dbFile = context.getDatabasePath("TapAccount_database")
        createSqliteDb(dbFile, version = 42, billsCount = 10)

        // 代码版本 43，数据库版本 42（升级场景，不应触发降级备份）
        DatabaseDowngradeHelper.backupIfDowngrade(context, "TapAccount_database", currentCodeVersion = 43)
        assertFalse(DatabaseDowngradeHelper.hasPendingDowngradeBackup(context))
    }

    @Test
    fun backupIfDowngrade_doesNotOverwritePrefWhenNewBackupHasZeroBillsAndExistingHasBills() {
        val context = FakeTestContext(tmp.newFolder("app_context_pref"))
        val dbFile = context.getDatabasePath("TapAccount_database")
        val backupDir = File(context.filesDir, DatabaseDowngradeHelper.BACKUP_DIR).apply { mkdirs() }

        // 已有一个含 50 条账单的旧备份
        val validOld = createSqliteDb(File(backupDir, "TapAccount_backup_v43_old.db"), billsCount = 50)
        context.prefs.map["last_backup_path"] = validOld.absolutePath
        context.prefs.map["last_backup_version"] = 43

        // 当前库是个 0 账单的降级库
        createSqliteDb(dbFile, version = 43, billsCount = 0)

        DatabaseDowngradeHelper.backupIfDowngrade(context, "TapAccount_database", currentCodeVersion = 42)

        // 验证：SharedPreferences 依然指向含 50 条账单的 validOld，没有被 0 账单的新备份覆盖！
        val (path, version) = DatabaseDowngradeHelper.getLastBackupInfo(context)!!
        assertEquals(validOld.absolutePath, path)
        assertEquals(43, version)
    }

    @Test
    fun restoreFromDowngradeBackup_wipesExistingWalAndShmCleanly() {
        val context = FakeTestContext(tmp.newFolder("app_context_restore"))
        val dbFile = context.getDatabasePath("TapAccount_database")

        // 模拟恢复前目标侧存在残留的 -wal 与 -shm 文件
        val walFile = File(dbFile.path + "-wal").apply { writeBytes(ByteArray(256) { 1 }) }
        val shmFile = File(dbFile.path + "-shm").apply { writeBytes(ByteArray(128) { 2 }) }
        assertTrue(walFile.exists())
        assertTrue(shmFile.exists())

        // 准备一个独立的 VACUUM INTO 备份文件（无 -wal / -shm）
        val backupFile = createSqliteDb(tmp.newFile("restore_bak.db"), version = 42, billsCount = 25)
        context.prefs.map["last_backup_path"] = backupFile.absolutePath
        context.prefs.map["last_backup_version"] = 42

        val result = DatabaseDowngradeHelper.restoreFromDowngradeBackup(
            context, "TapAccount_database", currentCodeVersion = 42
        )
        assertTrue(result.success)
        // 目标侧残留的 -wal 与 -shm 必须被彻底清除，避免旧 WAL 重放
        assertFalse("恢复后目标侧的 -wal 必须已被清除", walFile.exists())
        assertFalse("恢复后目标侧的 -shm 必须已被清除", shmFile.exists())
        assertEquals(25L, DatabaseDowngradeHelper.databaseOperator.getBillsCount(dbFile))
    }

    private class JdbcDatabaseOperator : DatabaseDowngradeHelper.DatabaseOperator {
        override fun vacuumInto(sourceFile: File, targetFile: File) {
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) targetFile.delete()
            File(targetFile.path + "-wal").delete()
            File(targetFile.path + "-shm").delete()
            DriverManager.getConnection("jdbc:sqlite:${sourceFile.absolutePath}").use { conn ->
                conn.createStatement().use { stmt ->
                    val escaped = targetFile.absolutePath.replace("'", "''")
                    stmt.execute("VACUUM INTO '$escaped'")
                }
            }
        }

        override fun walCheckpoint(sourceFile: File) {
            DriverManager.getConnection("jdbc:sqlite:${sourceFile.absolutePath}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("PRAGMA wal_checkpoint(FULL)")
                }
            }
        }

        override fun isIntegrityOk(dbFile: File): Boolean {
            if (!dbFile.exists() || dbFile.length() == 0L) return false
            return try {
                DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("PRAGMA quick_check").use { rs ->
                            rs.next() && rs.getString(1).equals("ok", ignoreCase = true)
                        }
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        override fun getBillsCount(dbFile: File): Long? {
            if (!dbFile.exists() || dbFile.length() == 0L) return null
            return try {
                DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                    conn.createStatement().use { stmt ->
                        val tableExists = stmt.executeQuery(
                            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='bills'"
                        ).use { it.next() }
                        if (!tableExists) return null

                        stmt.executeQuery("SELECT COUNT(*) FROM bills").use { rs ->
                            if (rs.next()) rs.getLong(1) else 0L
                        }
                    }
                }
            } catch (e: Exception) {
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

    private class FakeTestContext(private val baseDir: File) : ContextWrapper(null) {
        val prefs = FakeSharedPreferences()
        override fun getDatabasePath(name: String): File = File(baseDir, name)
        override fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    private class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any>()

        override fun getAll(): Map<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = (map[key] as? Set<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val sp: FakeSharedPreferences) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                if (value != null) temp[key] = value else removed.add(key)
            }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply {
                if (values != null) temp[key] = values else removed.add(key)
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { temp[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { removed.add(key) }
            override fun clear(): SharedPreferences.Editor = apply { temp.clear(); sp.map.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                removed.forEach { sp.map.remove(it) }
                temp.forEach { (k, v) ->
                    if (v != null) sp.map[k] = v else sp.map.remove(k)
                }
            }
        }
    }
}
