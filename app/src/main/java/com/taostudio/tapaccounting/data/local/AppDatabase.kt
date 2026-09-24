package com.taostudio.tapaccounting.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.taostudio.tapaccounting.data.local.dao.AiRuleDao
import com.taostudio.tapaccounting.data.local.dao.AssetDao
import com.taostudio.tapaccounting.data.local.dao.BillDao
import com.taostudio.tapaccounting.data.local.dao.BookDao
import com.taostudio.tapaccounting.data.local.dao.BookScopeDao
import com.taostudio.tapaccounting.data.local.dao.BudgetDao
import com.taostudio.tapaccounting.data.local.dao.CategoryDao
import com.taostudio.tapaccounting.data.local.dao.ChatMessageDao
import com.taostudio.tapaccounting.data.local.dao.DeletedBillDao
import com.taostudio.tapaccounting.data.local.dao.InvestmentLotDao
import com.taostudio.tapaccounting.data.local.dao.RecurringPatternDao
import com.taostudio.tapaccounting.data.local.dao.SharedLedgerDao
import com.taostudio.tapaccounting.data.local.dao.SharedMemberDao
import com.taostudio.tapaccounting.data.local.dao.SyncOperationDao
import com.taostudio.tapaccounting.data.local.dao.SyncQueueDao
import com.taostudio.tapaccounting.data.local.dao.SyncStateDao
import com.taostudio.tapaccounting.data.local.dao.SyncedRemoteFileDao
import com.taostudio.tapaccounting.data.local.entity.AiRule
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Book
import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import com.taostudio.tapaccounting.data.local.entity.DeletedBill
import com.taostudio.tapaccounting.data.local.entity.InvestmentLot
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.SharedLedger
import com.taostudio.tapaccounting.data.local.entity.SharedMember
import com.taostudio.tapaccounting.data.local.entity.SyncOperation
import com.taostudio.tapaccounting.data.local.entity.SyncQueue
import com.taostudio.tapaccounting.data.local.entity.SyncState
import com.taostudio.tapaccounting.data.local.entity.SyncedRemoteFile
import com.taostudio.tapaccounting.logic.InvestmentInterestService

/** 与 backupIfDowngrade 第三个参数保持同步。 */
private const val DB_VERSION = 38

/**
 * Room 主库。改 schema 前请先读本节，避免误用破坏性迁移或漏改版本号。
 *
 * ## 升级（旧 APK → 新 APK，必须保留数据）
 * - 每次改 [version]：新增 `MIGRATION_{旧}_{新}`，并加入 [getDatabase] 的 `.addMigrations(...)`。
 * - 优先 `ALTER TABLE … ADD COLUMN … DEFAULT`；大改表用「建新表 → 拷数据 → 换名」。
 * - **禁止** `.fallbackToDestructiveMigration()`：缺迁移应崩溃，不要静默清库。
 * - **禁止** squash 历史迁移（不要 `fallbackToDestructiveMigrationFrom(5..30)`），除非接受全员丢数据。
 * - 发版前：旧 APK 造数据 → 覆盖装新 APK → 验证账单/资产仍在。
 *
 * ## 降级（新 APK → 旧 APK，无法向前兼容 schema）
 * - Room 不支持向下 Migration。策略：先备份整库 → 再清库重建，保证不闪退。
 * - [DatabaseDowngradeHelper.backupIfDowngrade] 必须在 `Room.databaseBuilder().build()` **之前**调用。
 * - `.fallbackToDestructiveMigrationOnDowngrade()` 会清空当前库；数据靠降级备份或用户导出的 `.bak` 恢复。
 * - 降级备份**自动**、在内部存储；**仅当所装旧版 APK 也含本套逻辑时生效**。更老的 APK 仍会闪退且无自动备份。
 *
 * ## 版本号同步
 * - 只改文件顶部的 [DB_VERSION] 一处即可（`@Database` 与 `backupIfDowngrade` 共用）。
 *
 * ## 与用户备份的关系
 * - 用户手动 `.bak`（[com.taostudio.tapaccounting.data.backup.BackupManager]）可导出到文件/云盘，卸载后仍在。
 * - 降级自动备份是另一套机制，二者互补；新增 entity 时需同时考虑 Migration 与 `.bak` 导出（见 [BackupRepository]）。
 */
@Database(
    entities = [
        Bill::class, Asset::class, Category::class, AiRule::class,
        ChatMessage::class, InvestmentLot::class, DeletedBill::class,
        Budget::class, RecurringPattern::class, Book::class,
        SharedLedger::class, SharedMember::class, SyncOperation::class,
        SyncQueue::class, SyncState::class, SyncedRemoteFile::class
    ],
    version = DB_VERSION,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun billDao(): BillDao
    abstract fun bookDao(): BookDao
    abstract fun bookScopeDao(): BookScopeDao
    abstract fun assetDao(): AssetDao
    abstract fun categoryDao(): CategoryDao
    abstract fun aiRuleDao(): AiRuleDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun investmentLotDao(): InvestmentLotDao
    abstract fun deletedBillDao(): DeletedBillDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringPatternDao(): RecurringPatternDao
    abstract fun sharedLedgerDao(): SharedLedgerDao
    abstract fun sharedMemberDao(): SharedMemberDao
    abstract fun syncOperationDao(): SyncOperationDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun syncedRemoteFileDao(): SyncedRemoteFileDao

    companion object {
        /** 对外暴露的数据库版本号（与 [DB_VERSION] 相同）。 */
        const val CODE_VERSION = DB_VERSION

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** 降级恢复时调用：清除缓存的 Room 实例，下次 [getDatabase] 会重新打开。 */
        fun clearInstanceForRestore() {
            INSTANCE?.close()
            INSTANCE = null
        }

        // 迁移链从 v5 起保留；v1–v4 已无用户，可按需 .fallbackToDestructiveMigrationFrom(1,2,3,4)。

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // P2-17: 表名与 Room entity 对齐为 bills
                database.execSQL("ALTER TABLE bills ADD COLUMN fee REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE assets ADD COLUMN creditLimit REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE bills ADD COLUMN relatedBillId INTEGER")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bills_relatedBillId ON bills(relatedBillId)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE assets ADD COLUMN billingDay INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE assets ADD COLUMN pickerSortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `msgType` INTEGER NOT NULL,
                        `content` TEXT NOT NULL DEFAULT '',
                        `imageUri` TEXT NOT NULL DEFAULT '',
                        `timestamp` INTEGER NOT NULL,
                        `billIds` TEXT NOT NULL DEFAULT '',
                        `modelName` TEXT NOT NULL DEFAULT ''
                    )"""
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Rebuild table to match Room schema exactly:
                // 1) no SQL DEFAULT on text columns
                // 2) no extra indices
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `chat_messages_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `msgType` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `imageUri` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `billIds` TEXT NOT NULL,
                        `modelName` TEXT NOT NULL,
                        `bookName` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL
                    )"""
                )
                database.execSQL(
                    """INSERT INTO `chat_messages_new`
                       (`id`,`msgType`,`content`,`imageUri`,`timestamp`,`billIds`,`modelName`,`bookName`,`conversationId`)
                       SELECT
                         `id`,
                         `msgType`,
                         `content`,
                         `imageUri`,
                         `timestamp`,
                         `billIds`,
                         `modelName`,
                         '日常账本',
                         'legacy'
                       FROM `chat_messages`"""
                )
                database.execSQL("DROP TABLE `chat_messages`")
                database.execSQL("ALTER TABLE `chat_messages_new` RENAME TO `chat_messages`")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                database.execSQL("ALTER TABLE assets ADD COLUMN annualInterestRate REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE assets ADD COLUMN interestLastSettledAt INTEGER NOT NULL DEFAULT $now")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `investment_lots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `assetId` INTEGER NOT NULL,
                        `sourceBillId` INTEGER,
                        `principalAmount` REAL NOT NULL,
                        `remainingPrincipal` REAL NOT NULL,
                        `currency` TEXT NOT NULL,
                        `startEarningAt` INTEGER NOT NULL,
                        `firstPayoutAt` INTEGER NOT NULL,
                        `lastSettledAt` INTEGER NOT NULL,
                        `createTime` INTEGER NOT NULL,
                        FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`sourceBillId`) REFERENCES `bills`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )"""
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_lots_assetId` ON `investment_lots` (`assetId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_investment_lots_sourceBillId` ON `investment_lots` (`sourceBillId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_lots_lastSettledAt` ON `investment_lots` (`lastSettledAt`)")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP INDEX IF EXISTS `index_investment_lots_assetId`")
                database.execSQL("DROP INDEX IF EXISTS `index_investment_lots_sourceBillId`")
                database.execSQL("DROP INDEX IF EXISTS `index_investment_lots_lastSettledAt`")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `investment_lots_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `assetId` INTEGER NOT NULL,
                        `sourceBillId` INTEGER,
                        `principalAmount` REAL NOT NULL,
                        `remainingPrincipal` REAL NOT NULL,
                        `currency` TEXT NOT NULL,
                        `startEarningAt` INTEGER NOT NULL,
                        `firstPayoutAt` INTEGER NOT NULL,
                        `lastSettledAt` INTEGER NOT NULL,
                        `createTime` INTEGER NOT NULL,
                        FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`sourceBillId`) REFERENCES `bills`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )"""
                )
                database.execSQL(
                    """INSERT INTO `investment_lots_new`
                       (`id`,`assetId`,`sourceBillId`,`principalAmount`,`remainingPrincipal`,`currency`,`startEarningAt`,`firstPayoutAt`,`lastSettledAt`,`createTime`)
                       SELECT `id`,`assetId`,`sourceBillId`,`principalAmount`,`remainingPrincipal`,`currency`,`startEarningAt`,`firstPayoutAt`,`lastSettledAt`,`createTime`
                       FROM `investment_lots`"""
                )
                database.execSQL("DROP TABLE `investment_lots`")
                database.execSQL("ALTER TABLE `investment_lots_new` RENAME TO `investment_lots`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_lots_assetId` ON `investment_lots` (`assetId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_investment_lots_sourceBillId` ON `investment_lots` (`sourceBillId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_lots_lastSettledAt` ON `investment_lots` (`lastSettledAt`)")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE bills ADD COLUMN excludeFromStats INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `deleted_bills` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `originalBillId` INTEGER NOT NULL,
                        `type` INTEGER NOT NULL,
                        `subType` INTEGER NOT NULL DEFAULT 0,
                        `amount` REAL NOT NULL,
                        `originalAmount` REAL NOT NULL,
                        `currency` TEXT NOT NULL DEFAULT 'CNY',
                        `exchangeRate` REAL NOT NULL DEFAULT 1.0,
                        `categoryId` INTEGER,
                        `accountId` INTEGER,
                        `toAccountId` INTEGER,
                        `categoryName` TEXT NOT NULL DEFAULT '',
                        `accountName` TEXT NOT NULL DEFAULT '',
                        `toAccountName` TEXT NOT NULL DEFAULT '',
                        `time` INTEGER NOT NULL,
                        `remark` TEXT NOT NULL DEFAULT '',
                        `fee` REAL NOT NULL DEFAULT 0.0,
                        `bookName` TEXT NOT NULL DEFAULT '日常账本',
                        `relatedBillId` INTEGER,
                        `excludeFromStats` INTEGER NOT NULL DEFAULT 0,
                        `deletedAt` INTEGER NOT NULL
                    )"""
                )
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 普通差额平账(计入统计)转为普通账单（排除备注以"换币平账"开头的记录）
                database.execSQL("UPDATE bills SET subType = 0 WHERE subType = 3 AND remark NOT LIKE '换币平账%'")
                // 普通差额平账(不计入统计)转为普通账单，并设置 excludeFromStats = 1（排除备注以"换币平账"开头的记录）
                database.execSQL("UPDATE bills SET subType = 0, excludeFromStats = 1 WHERE subType = 4 AND remark NOT LIKE '换币平账%'")
                // 换币平账(计入统计)保留旧 subtype，但设置 excludeFromStats = 1（因为换币平账不应计入统计）
                database.execSQL("UPDATE bills SET excludeFromStats = 1 WHERE subType = 3 AND remark LIKE '换币平账%'")
                // 换币平账(不计入统计)保留旧 subtype，确保 excludeFromStats = 1
                database.execSQL("UPDATE bills SET excludeFromStats = 1 WHERE subType = 4 AND remark LIKE '换币平账%'")

                // 同步处理已删除账单表
                database.execSQL("UPDATE deleted_bills SET subType = 0 WHERE subType = 3 AND remark NOT LIKE '换币平账%'")
                database.execSQL("UPDATE deleted_bills SET subType = 0, excludeFromStats = 1 WHERE subType = 4 AND remark NOT LIKE '换币平账%'")
                database.execSQL("UPDATE deleted_bills SET excludeFromStats = 1 WHERE subType = 3 AND remark LIKE '换币平账%'")
                database.execSQL("UPDATE deleted_bills SET excludeFromStats = 1 WHERE subType = 4 AND remark LIKE '换币平账%'")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE assets ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("UPDATE assets SET includeInNetAsset = 0 WHERE isArchived = 1")
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Asset columns (may already exist if user ran the v21 build previously)
                try {
                    database.execSQL("ALTER TABLE assets ADD COLUMN billBalanceFromTime INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    // P2-17: 仅容忍列已存在，其它异常必须抛出
                    if (e.message?.contains("duplicate column", ignoreCase = true) != true) throw e
                }
                try {
                    database.execSQL("ALTER TABLE assets ADD COLUMN showBillBalanceAfter INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    // P2-17: 仅容忍列已存在，其它异常必须抛出
                    if (e.message?.contains("duplicate column", ignoreCase = true) != true) throw e
                }
                // Bill columns
                database.execSQL("ALTER TABLE bills ADD COLUMN accountBalanceAfter REAL")
                database.execSQL("ALTER TABLE bills ADD COLUMN toAccountBalanceAfter REAL")
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Fix default: showBillBalanceAfter should be 1 (true) for existing assets
                database.execSQL("UPDATE assets SET showBillBalanceAfter = 1 WHERE showBillBalanceAfter = 0")
                // Backfill billBalanceFromTime from createTime
                database.execSQL(
                    "UPDATE assets SET billBalanceFromTime = createTime WHERE billBalanceFromTime = 0"
                )
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE assets ADD COLUMN includeInNetBeforeArchive INTEGER NOT NULL DEFAULT 1"
                )
                database.execSQL(
                    "UPDATE assets SET includeInNetBeforeArchive = includeInNetAsset WHERE isArchived = 0"
                )
                // Archived rows already have includeInNetAsset=0 from archive; default restore to included.
                database.execSQL(
                    "UPDATE assets SET includeInNetBeforeArchive = 1 WHERE isArchived = 1"
                )
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_bookName_time` ON `bills` (`bookName`, `time`)")
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val cursor = database.query(
                    """
                    SELECT a.id, a.balance, a.currency, a.createTime
                    FROM assets a
                    WHERE a.assetCategory = 'INVESTMENT'
                      AND a.annualInterestRate != 0.0
                      AND a.balance > 0.0
                      AND NOT EXISTS (
                        SELECT 1 FROM investment_lots l WHERE l.assetId = a.id
                      )
                    """.trimIndent()
                )
                cursor.use {
                    while (it.moveToNext()) {
                        val assetId = it.getLong(0)
                        val balance = it.getDouble(1)
                        val currency = it.getString(2) ?: "CNY"
                        val createTime = it.getLong(3)
                        val startEarningAt = InvestmentInterestService.startOfDay(createTime)
                        val firstPayoutAt = InvestmentInterestService.plusDays(startEarningAt, 1)
                        database.execSQL(
                            """
                            INSERT INTO investment_lots (
                                assetId, sourceBillId, principalAmount, remainingPrincipal, currency,
                                startEarningAt, firstPayoutAt, lastSettledAt, createTime
                            ) VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf(
                                assetId,
                                balance,
                                balance,
                                currency,
                                startEarningAt,
                                firstPayoutAt,
                                startEarningAt,
                                createTime
                            )
                        )
                    }
                }
                database.execSQL(
                    """
                    UPDATE assets
                    SET interestLastSettledAt = createTime
                    WHERE assetCategory = 'INVESTMENT'
                      AND annualInterestRate != 0.0
                      AND interestLastSettledAt > createTime + 86400000
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Fix balances start date that was copied from a too-new asset.createTime.
                database.execSQL(
                    """
                    UPDATE assets
                    SET billBalanceFromTime = (
                        SELECT MIN(b.time)
                        FROM bills b
                        WHERE b.accountId = assets.id
                           OR b.toAccountId = assets.id
                           OR (assets.name != '' AND b.accountName = assets.name)
                           OR (assets.name != '' AND b.toAccountName = assets.name)
                    )
                    WHERE billBalanceFromTime = createTime
                      AND createTime > (
                        SELECT MIN(b.time)
                        FROM bills b
                        WHERE b.accountId = assets.id
                           OR b.toAccountId = assets.id
                           OR (assets.name != '' AND b.accountName = assets.name)
                           OR (assets.name != '' AND b.toAccountName = assets.name)
                      )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `budgets` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookName` TEXT NOT NULL,
                        `categoryId` INTEGER,
                        `categoryName` TEXT,
                        `yearMonth` TEXT NOT NULL,
                        `amount` REAL NOT NULL,
                        `currency` TEXT NOT NULL DEFAULT 'CNY',
                        `alertThreshold` REAL NOT NULL DEFAULT 0.8,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )"""
                )
            }
        }

        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `recurring_patterns` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `merchantKey` TEXT NOT NULL,
                        `categoryId` INTEGER,
                        `categoryName` TEXT,
                        `accountName` TEXT,
                        `bookName` TEXT NOT NULL,
                        `amountApprox` REAL NOT NULL,
                        `amountTolerance` REAL NOT NULL,
                        `frequency` TEXT NOT NULL,
                        `dayOfMonthHint` INTEGER,
                        `lastSeenAt` INTEGER NOT NULL,
                        `nextExpectedAt` INTEGER,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )"""
                )
            }
        }

        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE assets ADD COLUMN statementDay INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE assets ADD COLUMN dueDay INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `accounting_drafts` (
                        `id` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `sourceMessageId` TEXT,
                        `bookName` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `naturalSummary` TEXT,
                        `riskFlagsJson` TEXT,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `confirmedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )"""
                )
            }
        }

        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS `accounting_drafts`")
            }
        }

        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE recurring_patterns ADD COLUMN toAccountName TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE recurring_patterns ADD COLUMN billType INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE recurring_patterns ADD COLUMN billSubType INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE investment_lots ADD COLUMN annualInterestRate REAL NOT NULL DEFAULT 0.0")
                database.execSQL(
                    """
                    UPDATE investment_lots
                    SET annualInterestRate = COALESCE((
                        SELECT annualInterestRate
                        FROM assets
                        WHERE assets.id = investment_lots.assetId
                    ), 0.0)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(database: SupportSQLiteDatabase) {
                com.taostudio.tapaccounting.BookAccountManager
                    .rawAliases(com.taostudio.tapaccounting.BookAccountManager.DEFAULT_BOOK)
                    .filter { it.isNotBlank() && it != com.taostudio.tapaccounting.BookAccountManager.DEFAULT_BOOK }
                    .forEach { alias ->
                        database.execSQL(
                            "UPDATE budgets SET bookName = ? WHERE bookName = ?",
                            arrayOf<Any>(com.taostudio.tapaccounting.BookAccountManager.DEFAULT_BOOK, alias)
                        )
                    }
                database.execSQL(
                    "UPDATE budgets SET bookName = '' WHERE bookName = ?",
                    arrayOf<Any>(com.taostudio.tapaccounting.BookAccountManager.ALL_BOOK)
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `books` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL
                    )"""
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_name` ON `books` (`name`)")
                database.execSQL(
                    """INSERT OR IGNORE INTO books(name)
                       SELECT DISTINCT bookName FROM budgets WHERE bookName != ''"""
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `budgets_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` INTEGER NOT NULL,
                        `bookName` TEXT NOT NULL,
                        `categoryId` INTEGER,
                        `categoryKey` INTEGER NOT NULL,
                        `categoryName` TEXT,
                        `yearMonth` TEXT NOT NULL,
                        `amount` REAL NOT NULL,
                        `currency` TEXT NOT NULL,
                        `alertThreshold` REAL NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )"""
                )
                database.execSQL(
                    """INSERT INTO budgets_new(
                        id, bookId, bookName, categoryId, categoryKey, categoryName,
                        yearMonth, amount, currency, alertThreshold, createdAt, updatedAt
                    )
                    SELECT b.id,
                           CASE WHEN b.bookName = '' THEN 0
                                ELSE COALESCE((SELECT id FROM books WHERE name = b.bookName), 0)
                           END,
                           b.bookName,
                           b.categoryId,
                           IFNULL(b.categoryId, 0),
                           b.categoryName,
                           b.yearMonth,
                           b.amount,
                           b.currency,
                           b.alertThreshold,
                           b.createdAt,
                           b.updatedAt
                    FROM budgets b
                    WHERE NOT EXISTS (
                        SELECT 1 FROM budgets newer
                        WHERE newer.bookName = b.bookName
                          AND newer.yearMonth = b.yearMonth
                          AND IFNULL(newer.categoryId, 0) = IFNULL(b.categoryId, 0)
                          AND (
                              newer.updatedAt > b.updatedAt
                              OR (newer.updatedAt = b.updatedAt AND newer.id > b.id)
                          )
                    )"""
                )
                database.execSQL("DROP TABLE budgets")
                database.execSQL("ALTER TABLE budgets_new RENAME TO budgets")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_budgets_bookId_yearMonth_categoryKey` " +
                        "ON `budgets` (`bookId`, `yearMonth`, `categoryKey`)"
                )
            }
        }

        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE bills ADD COLUMN sharedId TEXT")
                database.execSQL("ALTER TABLE bills ADD COLUMN memberId TEXT")
                database.execSQL("ALTER TABLE bills ADD COLUMN isShared INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE bills ADD COLUMN cateIcon TEXT")
                database.execSQL("ALTER TABLE bills ADD COLUMN sharedRevision INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE bills ADD COLUMN sharedDeviceId TEXT")
                database.execSQL("ALTER TABLE bills ADD COLUMN relatedSharedId TEXT")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bills_sharedId ON bills(sharedId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bills_isShared ON bills(isShared)")

                database.execSQL("ALTER TABLE budgets ADD COLUMN sharedId TEXT")
                database.execSQL("ALTER TABLE budgets ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE budgets ADD COLUMN isShared INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE budgets ADD COLUMN sharedDeviceId TEXT")

                database.execSQL("""CREATE TABLE IF NOT EXISTS shared_ledger (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, bookId INTEGER NOT NULL,
                    name TEXT NOT NULL, webdavUrl TEXT NOT NULL, webdavUser TEXT NOT NULL, remotePath TEXT NOT NULL,
                    localMemberId TEXT NOT NULL, createdAt INTEGER NOT NULL,
                    FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE)""")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_shared_ledger_uuid ON shared_ledger(uuid)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_shared_ledger_bookId ON shared_ledger(bookId)")
                database.execSQL("""CREATE TABLE IF NOT EXISTS shared_member (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ledgerId INTEGER NOT NULL, memberId TEXT NOT NULL,
                    displayName TEXT NOT NULL, joinOrder INTEGER NOT NULL, isLocal INTEGER NOT NULL,
                    FOREIGN KEY(ledgerId) REFERENCES shared_ledger(id) ON DELETE CASCADE)""")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_shared_member_ledgerId ON shared_member(ledgerId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_shared_member_ledgerId_memberId ON shared_member(ledgerId,memberId)")
                database.execSQL("""CREATE TABLE IF NOT EXISTS sync_operation (
                    operationId TEXT PRIMARY KEY NOT NULL, ledgerId INTEGER NOT NULL, entityType TEXT NOT NULL,
                    entityId TEXT NOT NULL, action TEXT NOT NULL, revision INTEGER NOT NULL, deviceId TEXT NOT NULL,
                    memberId TEXT NOT NULL, payload TEXT, appliedAt INTEGER NOT NULL,
                    FOREIGN KEY(ledgerId) REFERENCES shared_ledger(id) ON DELETE CASCADE)""")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_operation_ledgerId ON sync_operation(ledgerId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_operation_ledgerId_entityType_entityId_revision_deviceId ON sync_operation(ledgerId,entityType,entityId,revision,deviceId)")
                database.execSQL("""CREATE TABLE IF NOT EXISTS sync_queue (
                    operationId TEXT PRIMARY KEY NOT NULL, ledgerId INTEGER NOT NULL, operationJson TEXT NOT NULL,
                    remotePath TEXT NOT NULL, createdAt INTEGER NOT NULL, retryCount INTEGER NOT NULL DEFAULT 0,
                    lastError TEXT, FOREIGN KEY(ledgerId) REFERENCES shared_ledger(id) ON DELETE CASCADE)""")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_queue_ledgerId ON sync_queue(ledgerId)")
                database.execSQL("""CREATE TABLE IF NOT EXISTS sync_state (
                    ledgerId INTEGER PRIMARY KEY NOT NULL, deviceId TEXT NOT NULL, lastSyncTime INTEGER NOT NULL DEFAULT 0,
                    lastError TEXT, isSyncing INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(ledgerId) REFERENCES shared_ledger(id) ON DELETE CASCADE)""")
            }
        }

        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sync_remote_file` (
                        `ledgerId` INTEGER NOT NULL,
                        `remotePath` TEXT NOT NULL,
                        `processedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`ledgerId`, `remotePath`),
                        FOREIGN KEY(`ledgerId`) REFERENCES `shared_ledger`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_remote_file_ledgerId` ON `sync_remote_file` (`ledgerId`)"
                )
            }
        }

        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE budgets ADD COLUMN memberBudgetAllocations TEXT")
            }
        }

        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE investment_lots ADD COLUMN settlementCycle INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE investment_lots ADD COLUMN settlementInterval INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE investment_lots ADD COLUMN interestCarry REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE investment_lots ADD COLUMN status INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appCtx = context.applicationContext

                // 降级防护（须在 build 之前）：db 文件版本 > 代码版本 → 自动拷整库，再交给下面的 OnDowngrade 清库。
                // 不是用户手动 .bak，也不是闪退后备份。详见 DatabaseDowngradeHelper。
                DatabaseDowngradeHelper.backupIfDowngrade(
                    appCtx, "TapAccount_database", CODE_VERSION
                )

                val instance = Room.databaseBuilder(
                    appCtx,
                    AppDatabase::class.java,
                    "TapAccount_database"
                )
                    .addMigrations(
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27,
                        MIGRATION_27_28,
                        MIGRATION_28_29,
                        MIGRATION_29_30,
                        MIGRATION_30_31,
                        MIGRATION_31_32,
                        MIGRATION_32_33,
                        MIGRATION_33_34,
                        MIGRATION_34_35,
                        MIGRATION_35_36,
                        MIGRATION_36_37,
                        MIGRATION_37_38
                    )
                    // 仅处理降级：清库并按当前代码 schema 重建。升级缺迁移时仍应抛异常，不要改成 fallbackToDestructiveMigration()。
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
