package com.taostudio.tapaccounting.data.local

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.Logger
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.local.entity.AiRule
import com.taostudio.tapaccounting.logic.CategoryNameNormalizer
import com.taostudio.tapaccounting.logic.DuplicateCategoryMerger
import com.taostudio.tapaccounting.logic.UncategorizedBillRelinker
import java.text.SimpleDateFormat
import java.util.Locale

object MigrationManager {

    private const val PREF_KEY_MIGRATED = "has_migrated_to_room"
    private const val PREF_KEY_CATEGORY_NAME_NORMALIZED = "has_normalized_category_name_storage_v2"
    private const val PREF_KEY_BALANCE_SNAPSHOT_BACKFILLED = "balance_snapshot_backfilled_v1"
    private const val PREF_KEY_AI_RULES_MIGRATED = "has_migrated_ai_rules_to_room"
    // v2：v1 的回填漏掉了退款账单（名字带着「退款：」前缀，取叶子名前没剥掉，永远匹配不到分类），
    // 升版本让存量数据再回填一次。回填是幂等的（只处理 categoryId IS NULL）。
    private const val PREF_KEY_CATEGORY_ID_BACKFILLED = "category_id_backfilled_v2"
    private const val PREF_KEY_CATEGORY_ALIAS_MERGED = "category_alias_merged_v1"

    internal fun normalizeLegacyBillTypeAndSubtype(legacyType: Int): Pair<Int, Int> {
        return when (legacyType) {
            Bill.TYPE_EXPENSE,
            Bill.TYPE_INCOME,
            Bill.TYPE_TRANSFER -> legacyType to Bill.SUBTYPE_NORMAL
            Bill.TYPE_REPAYMENT -> Bill.TYPE_TRANSFER to Bill.SUBTYPE_REPAYMENT
            else -> Bill.TYPE_EXPENSE to Bill.SUBTYPE_NORMAL
        }
    }

    suspend fun migrateIfNecessary(context: Context, database: AppDatabase) {
        val sharedPrefs = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)

        if (!sharedPrefs.getBoolean(PREF_KEY_MIGRATED, false)) {
            withContext(Dispatchers.IO) {
                try {
                    Log.d("Migration", "start migrating legacy data to Room")

                    database.withTransaction {
                        val assetMap = mutableMapOf<String, Long>()
                        val categoryMap = mutableMapOf<String, Long>()

                        val oldAssets = Prefs.getAssets(context)
                        for (oldAsset in oldAssets) {
                            val assetType = when (oldAsset.type) {
                                "资金" -> "资金"
                                "信用" -> "信用"
                                "投资" -> "投资"
                                else -> oldAsset.type.ifEmpty { "其他" }
                            }
                            val newAsset = Asset(
                                name = oldAsset.name,
                                type = assetType,
                                currency = oldAsset.currency,
                                icon = oldAsset.icon,
                                balance = 0.0,
                                initialBalance = 0.0,
                                includeInNetAsset = true
                            )
                            val id = database.assetDao().insertAsset(newAsset)
                            assetMap[oldAsset.name] = id
                        }

                        val oldExpenseCats = Prefs.getCategories(context, Prefs.TYPE_EXPENSE)
                        migrateCategories(database, oldExpenseCats, 0, null, categoryMap)

                        val oldIncomeCats = Prefs.getCategories(context, Prefs.TYPE_INCOME)
                        migrateCategories(database, oldIncomeCats, 1, null, categoryMap)

                        val oldBills = Prefs.getBills(context)
                        val newBills = mutableListOf<Bill>()
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        val sdfShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

                        for (oldBill in oldBills) {
                            var timeMillis = System.currentTimeMillis()
                            try {
                                val date = sdf.parse(oldBill.time) ?: sdfShort.parse(oldBill.time)
                                if (date != null) timeMillis = date.time
                            } catch (_: Exception) {
                                Log.e("Migration", "failed to parse time: ${oldBill.time}")
                            }

                            val (type, subType) = normalizeLegacyBillTypeAndSubtype(oldBill.type)
                            val normalizedCategoryName = CategoryNameNormalizer.normalizeForStorage(oldBill.categoryName)
                            val bill = Bill(
                                type = type,
                                subType = subType,
                                amount = oldBill.amount,
                                originalAmount = oldBill.amount,
                                currency = "CNY",
                                exchangeRate = 1.0,
                                categoryId = categoryMap[oldBill.categoryName],
                                accountId = assetMap[oldBill.assetName],
                                toAccountId = null,
                                categoryName = normalizedCategoryName,
                                accountName = oldBill.assetName,
                                time = timeMillis,
                                remark = oldBill.remarks,
                                isSynced = true
                            )
                            newBills.add(bill)
                        }

                        if (newBills.isNotEmpty()) {
                            database.billDao().insertBills(newBills)
                        }
                    }

                    sharedPrefs.edit().putBoolean(PREF_KEY_MIGRATED, true).apply()
                    Log.d("Migration", "legacy migration done")
                } catch (e: Exception) {
                    Log.e("Migration", "legacy migration failed", e)
                }
            }
        }

        normalizeStoredCategoryNamesIfNeeded(context, database)
        // 必须排在回填之前：先把重复分类合并掉，回填才不会把账单挂到一个马上要被删掉的分类上
        mergeDuplicateCategoriesIfNeeded(context, database)
        backfillMissingCategoryIdsIfNeeded(context, database)
        backfillBalanceSnapshotsIfNeeded(context, database)
        migrateAiRulesToRoomIfNeeded(context, database)
    }

    /**
     * 一次性合并语义重复的分类（目前只有「其他」/「其它」这一对）。
     *
     * 内置分类库 `category.json` 里这两个名字是**同一个图标的两个条目**（都是 `cateic_other.png`），
     * 而初始种子 `default_category.json` 只给「其它」。手动加过「其他」的账本会同时拥有两个，
     * 各自挂着一批账单，在统计页和分类预算里看起来像两个分类。
     *
     * **只有两个同时存在才动手**——只有一个的账本（绝大多数）在这里是空操作。
     * 合并方向与保守规则见 [DuplicateCategoryMerger.plan]。
     *
     * 失败时不置位开关，留待下次启动重试。
     */
    private suspend fun mergeDuplicateCategoriesIfNeeded(context: Context, database: AppDatabase) {
        val sharedPrefs = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
        if (sharedPrefs.getBoolean(PREF_KEY_CATEGORY_ALIAS_MERGED, false)) {
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val plan = DuplicateCategoryMerger.plan(database.categoryDao().getAllCategoriesList())
                if (plan.isEmpty) {
                    sharedPrefs.edit().putBoolean(PREF_KEY_CATEGORY_ALIAS_MERGED, true).apply()
                    Logger.i(context, "Migration", "category alias merge: nothing to do")
                    return@withContext
                }

                val now = System.currentTimeMillis()
                database.withTransaction {
                    plan.actions.forEach { action ->
                        // 1) 已经挂着 categoryId 的账单/回收站/周期模式：直接改挂到保留的那个
                        database.billDao().migrateCategoryId(action.sourceId, action.targetId)
                        database.deletedBillDao().migrateCategoryId(action.sourceId, action.targetId)
                        database.recurringPatternDao().migrateCategoryId(action.sourceId, action.targetId)
                        // 2) 只有名字、没有 categoryId 的历史账单：只把名字改成保留的那个。
                        //    **不要**在这里按名字改挂 categoryId —— `migrateCategoryByName` 不区分
                        //    账单类型，会把收入账单挂到支出分类上（真机上「其它」的 13 条里 10 条是收入）。
                        //    改名之后，紧随其后的 backfillMissingCategoryIdsIfNeeded 会按
                        //    「叶子名 + 类型」正确认领，退款账单按原支出分类处理——那本来就是它的职责。
                        database.billDao().syncCategoryNameByOldName(action.sourceName, action.targetName)
                        // 3) 把名字快照刷成保留的那个（退款前缀由 SQL 保留，回收站里的也一起刷）
                        database.billDao().syncCategoryNameByCategoryId(action.targetId, action.targetName)
                        database.recurringPatternDao()
                            .syncCategoryNameByCategoryId(action.targetId, action.targetName, now)
                        // 4) 删掉被并入的那个分类
                        database.categoryDao().deleteById(action.sourceId)
                    }
                }

                sharedPrefs.edit().putBoolean(PREF_KEY_CATEGORY_ALIAS_MERGED, true).apply()
                Logger.i(
                    context,
                    "Migration",
                    "category alias merge done: " + plan.actions.joinToString {
                        "${it.sourceName}(type=${it.type}) -> ${it.targetName}"
                    }
                )
                // 跳过项必须留痕，否则用户会以为漏了
                if (plan.skips.isNotEmpty()) {
                    Logger.w(
                        context,
                        "Migration",
                        "category alias merge skipped: " + plan.skips.joinToString {
                            "${it.name}(type=${it.type}): ${it.reason}"
                        }
                    )
                }
            } catch (e: Exception) {
                Logger.e(context, "Migration", "category alias merge failed, will retry next launch", e)
            }
        }
    }

    /**
     * 一次性回填：把「有分类名、没有 categoryId」的账单重新挂回分类。
     *
     * 分类预算完全按 `categoryId` 聚合，这类账单对它隐形——已用金额恒为 0，且不报错。
     * 历史数据里占比可能很大，所以升级时必须补一次。匹配规则见 [UncategorizedBillRelinker]。
     *
     * 失败时不置位开关，留待下次启动重试。
     */
    private suspend fun backfillMissingCategoryIdsIfNeeded(context: Context, database: AppDatabase) {
        val sharedPrefs = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
        if (sharedPrefs.getBoolean(PREF_KEY_CATEGORY_ID_BACKFILLED, false)) {
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val report = UncategorizedBillRelinker.relink(database)
                sharedPrefs.edit().putBoolean(PREF_KEY_CATEGORY_ID_BACKFILLED, true).apply()
                Logger.i(
                    context,
                    "Migration",
                    "category id backfill done: relinked=${report.relinkedBills} of ${report.scannedNames} name groups"
                )
                // 跳过的明细必须记下来：这些账单仍然对分类预算隐形，是唯一需要人工确认的部分。
                if (report.ambiguousLeaves.isNotEmpty()) {
                    Logger.w(
                        context,
                        "Migration",
                        "category id backfill skipped, same name+type category exists twice: ${report.ambiguousLeaves}"
                    )
                }
                if (report.unknownLeaves.isNotEmpty()) {
                    Logger.w(
                        context,
                        "Migration",
                        "category id backfill skipped, category no longer exists: ${report.unknownLeaves}"
                    )
                }
            } catch (e: Exception) {
                Logger.e(context, "Migration", "category id backfill failed, will retry next launch", e)
            }
        }
    }

    private suspend fun backfillBalanceSnapshotsIfNeeded(context: Context, @Suppress("UNUSED_PARAMETER") database: AppDatabase) {
        val sharedPrefs = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
        if (sharedPrefs.getBoolean(PREF_KEY_BALANCE_SNAPSHOT_BACKFILLED, false)) {
            return
        }
        // Asset detail shows balances derived from current balance + bills (not DB snapshot columns).
        sharedPrefs.edit().putBoolean(PREF_KEY_BALANCE_SNAPSHOT_BACKFILLED, true).apply()
        Log.d("Migration", "balance snapshot backfill skipped (display uses backward derivation)")
    }

    private suspend fun migrateAiRulesToRoomIfNeeded(context: Context, database: AppDatabase) {
        val sharedPrefs = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
        if (sharedPrefs.getBoolean(PREF_KEY_AI_RULES_MIGRATED, false)) return

        withContext(Dispatchers.IO) {
            try {
                val legacyRules = Prefs.getAiRules(context)
                if (legacyRules.isEmpty()) {
                    sharedPrefs.edit().putBoolean(PREF_KEY_AI_RULES_MIGRATED, true).apply()
                    Log.d("Migration", "no legacy AI rules to migrate")
                    return@withContext
                }

                val existingRules = database.aiRuleDao().getAllRulesList()
                val existingKeys = existingRules.map { rule ->
                    keyOf(rule.keyword, rule.targetType, rule.targetCategory, rule.targetAccount1, rule.targetAccount2)
                }.toSet()

                var inserted = 0
                database.withTransaction {
                    legacyRules.forEach { legacy ->
                        val key = keyOf(legacy.keyword, legacy.targetType, legacy.targetCategory, legacy.targetAccount1, legacy.targetAccount2)
                        if (key !in existingKeys) {
                            database.aiRuleDao().insertRule(
                                AiRule(
                                    keyword = legacy.keyword,
                                    targetType = legacy.targetType,
                                    targetCategory = legacy.targetCategory,
                                    targetAccount1 = legacy.targetAccount1,
                                    targetAccount2 = legacy.targetAccount2,
                                    isEnabled = legacy.isEnabled
                                )
                            )
                            inserted++
                        }
                    }
                }

                // 清除 SharedPreferences 中的旧规则
                Prefs.saveAiRules(context, emptyList())
                sharedPrefs.edit().putBoolean(PREF_KEY_AI_RULES_MIGRATED, true).apply()
                Log.d("Migration", "AI rules migrated to Room, inserted=$inserted, skipped=${legacyRules.size - inserted}")
            } catch (e: Exception) {
                Log.e("Migration", "AI rules migration failed", e)
            }
        }
    }

    private fun keyOf(keyword: String?, targetType: Int?, targetCategory: String?, targetAccount1: String?, targetAccount2: String?): String {
        return listOf(
            keyword?.trim().orEmpty(),
            targetType?.toString().orEmpty(),
            targetCategory.orEmpty(),
            targetAccount1.orEmpty(),
            targetAccount2.orEmpty()
        ).joinToString("|")
    }

    private suspend fun normalizeStoredCategoryNamesIfNeeded(context: Context, database: AppDatabase) {
        val sharedPrefs = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
        if (sharedPrefs.getBoolean(PREF_KEY_CATEGORY_NAME_NORMALIZED, false)) {
            return
        }

        withContext(Dispatchers.IO) {
            try {
                var updatedCount = 0
                database.withTransaction {
                    val allBills = database.billDao().getAllBillsList()
                    allBills.forEach { bill ->
                        val normalized = CategoryNameNormalizer.normalizeForStorage(bill.categoryName)
                        if (normalized != bill.categoryName) {
                            database.billDao().updateBill(bill.copy(categoryName = normalized))
                            updatedCount += 1
                        }
                    }
                }
                sharedPrefs.edit().putBoolean(PREF_KEY_CATEGORY_NAME_NORMALIZED, true).apply()
                Log.d("Migration", "category name normalization done, updated=$updatedCount")
            } catch (e: Exception) {
                Log.e("Migration", "category name normalization failed", e)
            }
        }
    }

    private suspend fun migrateCategories(
        database: AppDatabase,
        oldNodes: List<com.taostudio.tapaccounting.CategoryNode>,
        type: Int,
        parentId: Long?,
        categoryMap: MutableMap<String, Long>
    ) {
        for (node in oldNodes) {
            val category = Category(
                name = node.name,
                type = type,
                parentId = parentId,
                iconId = node.icon
            )
            val id = database.categoryDao().insertCategory(category)
            categoryMap[node.name] = id

            if (node.subs.isNotEmpty()) {
                migrateCategories(database, node.subs, type, id, categoryMap)
            }
        }
    }
}

