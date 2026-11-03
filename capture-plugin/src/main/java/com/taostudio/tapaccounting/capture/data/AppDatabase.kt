package com.taostudio.tapaccounting.capture.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PaymentCapture::class, CaptureEvent::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun paymentCaptureDao(): PaymentCaptureDao
    abstract fun captureEventDao(): CaptureEventDao

    companion object {
        /**
         * v1 -> v2：账单类型本地化字段。
         *
         * 现有行保持 `billType/subType = NULL`、`needsRefundLink = 0`，不重算、不丢数据。
         * 旧行在 UI 上显示为“待分类”；当前没有自动回填，后续可一次性重跑规则或手动选类型。
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

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "capture.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
