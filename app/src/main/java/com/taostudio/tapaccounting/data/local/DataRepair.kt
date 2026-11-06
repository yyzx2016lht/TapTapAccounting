package com.taostudio.tapaccounting.data.local

import android.content.Context
import android.util.Log
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.data.local.entity.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 一次性数据修复：把历史空分类账单回填到「其他」。
 *
 * 只跑一次，用 [CapturePrefs.hasBackfilledEmptyCategories] 标记；执行前做一份轻量 DB 备份。
 * 只处理支出/收入（type 0/1），转账/还款不动。
 */
object DataRepair {

    private const val TAG = "TapDataRepair"
    private const val DB_NAME = "TapAccount_database"
    private const val OTHER = "其他"

    @Volatile
    private var running = false

    fun runIfNeeded(context: Context) {
        val app = context.applicationContext ?: context
        if (CapturePrefs.hasBackfilledEmptyCategories(app) || running) return
        running = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(app)
                backupDatabase(app, db)
                val categories = db.categoryDao().getAllCategoriesList().toMutableList()
                var updated = 0
                for (type in 0..1) {
                    var other = categories.firstOrNull { it.type == type && it.name == OTHER }
                    if (other == null) {
                        val id = db.categoryDao().insertCategory(
                            Category(name = OTHER, type = type, sortOrder = Int.MAX_VALUE)
                        )
                        other = Category(id = id, name = OTHER, type = type, sortOrder = Int.MAX_VALUE)
                        categories += other
                    }
                    updated += db.billDao().backfillEmptyCategory(type, other.name, other.id)
                }
                CapturePrefs.setBackfilledEmptyCategories(app, true)
                if (updated > 0) {
                    Log.i(TAG, "backfilled $updated bills with empty category to $OTHER")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "backfill empty category failed", t)
            } finally {
                running = false
            }
        }
    }

    private fun backupDatabase(app: Context, db: AppDatabase) {
        runCatching {
            // 尽量把 WAL 写回主库，再做文件级备份；失败不阻断回填。
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            val dbFile = app.getDatabasePath(DB_NAME)
            if (!dbFile.isFile) return
            val dir = File(app.filesDir, "db_repairs").apply { mkdirs() }
            val target = File(dir, "TapAccount_before_empty_category_${System.currentTimeMillis()}.db")
            dbFile.copyTo(target, overwrite = true)
        }.onFailure {
            Log.w(TAG, "backup before empty-category repair failed", it)
        }
    }
}
