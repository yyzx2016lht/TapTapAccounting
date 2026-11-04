package com.taostudio.tapaccounting.capture.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PaymentCapture::class,
        CaptureEvent::class,
        AssetMappingRule::class,
        CategoryRule::class,
        RuleLearningTask::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun paymentCaptureDao(): PaymentCaptureDao
    abstract fun captureEventDao(): CaptureEventDao
    abstract fun assetMappingDao(): AssetMappingDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun ruleLearningTaskDao(): RuleLearningTaskDao

    companion object {
        /**
         * v1 -> v2：账单类型本地化字段。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE payment_captures ADD COLUMN billType INTEGER")
                db.execSQL("ALTER TABLE payment_captures ADD COLUMN subType INTEGER")
                db.execSQL(
                    "ALTER TABLE payment_captures ADD COLUMN needsRefundLink " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** v2 -> v3：转账/还款的转入资产名，供 App 预填两端资产。 */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE payment_captures ADD COLUMN toAssetName TEXT")
            }
        }

        /** v3 -> v4：AutoAccounting category.js 推断出的分类名。 */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE payment_captures ADD COLUMN categoryName TEXT")
            }
        }

        /** v4 -> v5：AutoAccounting AppDataModel 等价的原始事件历史表。 */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `capture_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `source` TEXT NOT NULL,
                        `rawType` TEXT NOT NULL,
                        `rawJson` TEXT NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `matched` INTEGER NOT NULL,
                        `ruleName` TEXT,
                        `autoType` TEXT,
                        `localType` INTEGER,
                        `localSubType` INTEGER,
                        `amountCents` INTEGER,
                        `merchant` TEXT,
                        `categoryName` TEXT,
                        `channel` TEXT,
                        `rawHash` TEXT NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_capture_events_rawHash` " +
                        "ON `capture_events` (`rawHash`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_capture_events_receivedAt` " +
                        "ON `capture_events` (`receivedAt`)"
                )
            }
        }

        /** v5 -> v6：资产映射表 + 分类规则表。 */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `asset_mapping_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `pattern` TEXT NOT NULL,
                        `targetAssetName` TEXT NOT NULL,
                        `isRegex` INTEGER NOT NULL,
                        `sort` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_asset_mapping_rules_pattern` " +
                        "ON `asset_mapping_rules` (`pattern`)"
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `category_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `keyword` TEXT NOT NULL,
                        `matchField` TEXT NOT NULL,
                        `isRegex` INTEGER NOT NULL,
                        `targetCategoryName` TEXT NOT NULL,
                        `targetBookName` TEXT,
                        `sort` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_category_rules_keyword_matchField` " +
                        "ON `category_rules` (`keyword`, `matchField`)"
                )
            }
        }

        /** v6 -> v7：分类来源（RULE / AI / MANUAL / null）。历史分类保留，不回溯清空。 */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE payment_captures ADD COLUMN categorySource TEXT")
            }
        }

        /**
         * v7 -> v8：分类规则来源 / 学习时间 + AI 规则学习任务表。
         *
         * 历史规则没有来源信息，统一标成 USER（用户自己的规则），不删除、不重写目标分类。
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE category_rules ADD COLUMN source TEXT NOT NULL DEFAULT 'USER'"
                )
                db.execSQL("ALTER TABLE category_rules ADD COLUMN learnedAt INTEGER")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `rule_learning_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `captureId` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `categoryName` TEXT NOT NULL,
                        `billType` INTEGER NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_rule_learning_tasks_captureId` " +
                        "ON `rule_learning_tasks` (`captureId`)"
                )
            }
        }

        /**
         * v8 -> v9：文字拆分记账的用户勾选状态。
         *
         * 历史记录一律默认 0（不拆分），保证升级后行为不变。
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE payment_captures ADD COLUMN splitRequested " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "capture.db",
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
