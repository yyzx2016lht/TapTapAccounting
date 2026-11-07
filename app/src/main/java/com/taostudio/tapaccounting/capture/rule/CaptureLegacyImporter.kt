package com.taostudio.tapaccounting.capture.rule

import android.content.Context
import android.net.Uri
import android.util.Log
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.data.local.AppDatabase
import java.io.File

/**
 * 把旧插件 `capture.db` 整库并入主应用主库。
 *
 * 实现方式：插件 Provider.openFile 导出 `capture.db` 的只读副本，主应用复制到 cache 后
 * 用 SQLite `ATTACH DATABASE` + `INSERT OR REPLACE INTO main.x SELECT * FROM legacy.x`
 * 一次搬完 5 张表。两边的表结构由同一份 Room schema 生成，列顺序一致；不经过 Binder
 * 逐行序列化，数据量大也不会撑爆 1MB 事务限制。
 *
 * 旧插件数据保留不动，迁移失败也不会删除源库；成功后写 [CapturePrefs.isLegacyImported]。
 */
object CaptureLegacyImporter {

    private const val TAG = "TapCaptureLegacy"
    /**
     * 旧插件 DB 的列顺序和主应用 Room v42 的列顺序不同（例如 categorySource 在旧库末尾、
     * 主库在中间）。因此不能 `SELECT *`，必须显式列名，让 SQLite 按名字映射。
     */
    private val TABLE_COLUMNS: LinkedHashMap<String, List<String>> = linkedMapOf(
        "payment_captures" to listOf(
            "id", "source", "sourceTxnId", "amountCents", "currency", "merchant",
            "categoryName", "categorySource", "paymentMethodRaw", "assetId", "assetName",
            "toAssetName", "paidAt", "capturedAt", "bookName", "note", "billType",
            "subType", "needsRefundLink", "status", "splitRequested", "excludeFromStats",
            "needsAmount", "rawJson", "ruleVersion", "sourceAppVersion", "groupId",
            "channel", "createdAt", "updatedAt",
        ),
        "capture_events" to listOf(
            "id", "source", "rawType", "rawJson", "receivedAt", "matched", "ruleName",
            "autoType", "localType", "localSubType", "amountCents", "merchant",
            "categoryName", "channel", "rawHash",
        ),
        "asset_mapping_rules" to listOf(
            "id", "pattern", "targetAssetName", "isRegex", "sort", "enabled", "createdAt",
        ),
        "category_rules" to listOf(
            "id", "keyword", "matchField", "isRegex", "targetCategoryName",
            "targetBookName", "sort", "enabled", "createdAt", "source", "learnedAt",
        ),
        "rule_learning_tasks" to listOf(
            "id", "captureId", "note", "categoryName", "billType", "attempts", "createdAt",
        ),
    )

    @Synchronized
    fun importIfNeeded(context: Context, force: Boolean = false): Boolean {
        val app = context.applicationContext ?: context
        if (!force && CapturePrefs.isLegacyImported(app)) return true
        if (!isPluginInstalled(app)) {
            Log.i(TAG, "plugin not installed; skip legacy DB import")
            return false
        }

        val temp = File(app.cacheDir, "legacy_capture.db")
        return try {
            val uri = Uri.parse(
                "content://${CaptureContract.INGEST_AUTHORITY}/${CaptureContract.LEGACY_DB_PATH}"
            )
            val input = app.contentResolver.openInputStream(uri)
                ?: return false
            input.use { source ->
                temp.outputStream().use { target -> source.copyTo(target) }
            }
            if (temp.length() <= 0L) {
                Log.w(TAG, "legacy capture.db is empty")
                return false
            }

            val db = AppDatabase.getDatabase(app)
            val support = db.openHelper.writableDatabase
            var importedTables = 0
            var paymentCapturesImported = false
            // 必须先 ATTACH 再开事务：SQLite 不允许在事务进行中切换附加库的 WAL 模式。
            support.execSQL("ATTACH DATABASE ? AS legacy", arrayOf(temp.absolutePath))
            try {
                // 旧版本插件曾经允许 capturedAt 等字段为空；主库 Entity 是 NOT NULL。
                // 在附加库上先补齐，再整表 INSERT SELECT，避免一行脏数据拖垮整张待记表。
                runCatching {
                    support.execSQL(
                        """
                        UPDATE legacy.payment_captures SET
                            capturedAt = COALESCE(capturedAt, paidAt, createdAt, 0),
                            paidAt = COALESCE(paidAt, capturedAt, createdAt, 0),
                            createdAt = COALESCE(createdAt, paidAt, capturedAt, 0),
                            updatedAt = COALESCE(updatedAt, createdAt, paidAt, 0),
                            needsAmount = COALESCE(needsAmount, 1),
                            needsRefundLink = COALESCE(needsRefundLink, 0),
                            splitRequested = COALESCE(splitRequested, 0),
                            excludeFromStats = COALESCE(excludeFromStats, 0),
                            currency = COALESCE(currency, 'CNY'),
                            status = COALESCE(status, 'PENDING')
                        """.trimIndent()
                    )
                }.onFailure {
                    Log.w(TAG, "legacy payment_captures normalize failed: ${it.message}")
                }

                support.beginTransaction()
                try {
                    TABLE_COLUMNS.forEach { (table, columns) ->
                        try {
                            val columnList = columns.joinToString(", ") { "`$it`" }
                            support.execSQL(
                                "INSERT OR REPLACE INTO main.`$table` ($columnList) " +
                                    "SELECT $columnList FROM legacy.`$table`"
                            )
                            importedTables++
                            if (table == "payment_captures") paymentCapturesImported = true
                            Log.i(TAG, "legacy table imported: $table")
                        } catch (t: Throwable) {
                            Log.w(TAG, "legacy table skipped: $table (${t.message})")
                        }
                    }
                    // 统一规则表：旧 category_rules 导入后映射到 accounting_rules，否则新引擎读不到。
                    runCatching {
                        support.execSQL(
                            """
                            INSERT INTO main.accounting_rules (
                                keyword, isRegex, matchField, matchMode, targetType, targetCategory,
                                targetBook, targetAccount1, targetAccount2, sort, enabled, source,
                                learnedAt, createdAt, updatedAt
                            )
                            SELECT c.keyword, c.isRegex, c.matchField, 'SUBSTRING', NULL,
                                   c.targetCategoryName, c.targetBookName, NULL, NULL,
                                   c.sort, c.enabled, c.source, c.learnedAt, c.createdAt, c.createdAt
                            FROM main.category_rules c
                            WHERE NOT EXISTS (
                                SELECT 1 FROM main.accounting_rules a
                                WHERE a.keyword = c.keyword AND a.matchField = c.matchField
                            )
                            """.trimIndent()
                        )
                    }.onFailure {
                        Log.w(TAG, "legacy category_rules -> accounting_rules failed: ${it.message}")
                    }
                    support.setTransactionSuccessful()
                } finally {
                    support.endTransaction()
                }
            } finally {
                runCatching { support.execSQL("DETACH DATABASE legacy") }
            }
            if (!paymentCapturesImported) {
                // 待记主表没迁成功不能置完成标记；打开插件会再次触发 force 迁移。
                Log.w(TAG, "payment_captures not imported; keep legacy flag false")
                return false
            }
            CapturePrefs.setLegacyImported(app, true)
            Log.i(TAG, "legacy capture.db imported: tables=$importedTables")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "legacy capture.db import failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun isPluginInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(CaptureContract.PLUGIN_PACKAGE, 0) != null
    }.getOrDefault(false)
}
