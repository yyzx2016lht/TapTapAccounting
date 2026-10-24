package com.taostudio.tapaccounting.logic

import android.content.Context
import com.taostudio.tapaccounting.AiAssistant
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.Utils
import com.taostudio.tapaccounting.resolveAccountingBookForSave
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「一键全部入账」的落库通道：整批账单直接写入数据库，**不经过记账表单**。
 *
 * 之前这段逻辑长在 AccountingFormController 里，导致宿主必须先建出悬浮窗才能保存，
 * 一键入账时会看到表单闪一下。抽出来之后 OverlayManager 可以在没有任何界面的情况下
 * 完成入账。
 */
object BatchBillInserter {

    /**
     * 整批落库，返回已插入且带 id 的账单。
     *
     * @param fallbackBookName 账本兜底名：单条和整批都没指定账本时用
     * @param isAssetFeatureEnabled 资产功能开关；关闭时忽略资产、且只保留支出/收入
     */
    suspend fun insertBills(
        ctx: Context,
        json: JSONObject,
        billsArray: JSONArray,
        fallbackBookName: String,
        isAssetFeatureEnabled: Boolean
    ): List<Bill> {
        val db = AppDatabase.getDatabase(ctx)
        val availableBooks = BookAccountManager.getBookAccounts(ctx)
        val batchBookName = json.optString("book_name", "")
        val savedBills = mutableListOf<Bill>()

        for (i in 0 until billsArray.length()) {
            val obj = billsArray.getJSONObject(i)
            val resolvedBook = resolveAccountingBookForSave(
                billBookName = obj.optString("book_name", ""),
                batchBookName = batchBookName,
                availableBooks = availableBooks,
                fallbackBookName = fallbackBookName
            )

            var typeIndex = obj.optInt("type", 0)
            if (!isAssetFeatureEnabled) {
                typeIndex = if (typeIndex == 1) 1 else 0
            } else if (typeIndex > 2) {
                typeIndex = 2
            }
            val amt = obj.optDouble("amount", 0.0)
            val asset1 = if (isAssetFeatureEnabled) obj.optString("asset_name", "") else ""
            val asset2Name = if (isAssetFeatureEnabled) obj.optString("to_asset_name", "") else ""
            val a1 = asset1.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
            val a2ByName = asset2Name.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
            var cat = CategoryNameNormalizer.normalizeForStorage(obj.optString("category_name", ""))
            var toAssetId: Long? = null
            var toAssetNm = ""
            if (isAssetFeatureEnabled && typeIndex == 2) {
                // 仅当转入/转出都为现有资产时，才允许落为转账
                if (a1 != null && a2ByName != null) {
                    cat = "转账"
                    toAssetNm = asset2Name
                    toAssetId = a2ByName.id
                } else {
                    typeIndex = Bill.TYPE_EXPENSE
                    if (cat.isBlank() || cat == "转账") cat = "其他"
                }
            }

            val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val timeStr = obj.optString("time", timeFormatter.format(Date()))
            val parsedTime = try {
                timeFormatter.parse(timeStr)?.time ?: System.currentTimeMillis()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
            val remark = obj.optString("remarks", "")
            val currency = obj.optString("currency", "CNY").ifBlank { "CNY" }
            val fee = obj.optDouble("fee", 0.0).coerceAtLeast(0.0)

            val a2 = if (toAssetId != null) db.assetDao().getAssetById(toAssetId) else null
            val exchangeRate = when {
                typeIndex == Bill.TYPE_TRANSFER && a2 != null && amt > 0.0 ->
                    BillAssetImpactService.estimateExchangeRateToTarget(amt, currency, a2.currency)
                currency == "CNY" -> 1.0
                else -> {
                    val rateToCurrency = CurrencyManager.getRate(currency) ?: 1.0
                    if (rateToCurrency != 0.0) 1.0 / rateToCurrency else 1.0
                }
            }

            val categoryId = if (typeIndex == 2 || cat.isBlank()) {
                null
            } else {
                val categoryDbType = if (typeIndex == 1) 1 else 0
                val leaf = cat.substringAfterLast(" - ").substringAfterLast(" > ").trim()
                db.categoryDao().getCategoriesListByType(categoryDbType)
                    .firstOrNull { it.name == leaf }?.id
            }

            val bill = Bill(
                amount = amt,
                type = typeIndex,
                currency = currency,
                exchangeRate = exchangeRate,
                fee = fee,
                accountName = asset1,
                accountId = a1?.id,
                toAccountId = toAssetId,
                toAccountName = toAssetNm,
                categoryId = categoryId,
                categoryName = cat,
                time = parsedTime,
                remark = remark,
                bookName = resolvedBook
            )
            savedBills += BillMutationService.insertBillAndApplyImpact(db, bill)
        }
        return savedBills
    }

    /**
     * 整批入库后，判断要不要问用户「是否合并账单」。
     *
     * 依据是 AI 对**画面性质**的判断（single_payment：这张图是不是一张小票 / 一次消费的凭证），
     * 不是让 AI 猜哪几条属于同一笔——后者没有可靠信号，猜错就是静默错账。
     * 缺这个字段时退回「图片来源」这条已在用的保守规则；拿不准一律不合并。
     *
     * 另外还要满足：至少 2 条、全是普通收支（转账/还款/退款不参与）、扣款资产相同。
     */
    fun canMergeAsOnePayment(json: JSONObject, savedBills: List<Bill>): Boolean {
        if (savedBills.size < 2) return false
        val singlePayment = if (json.has(AiAssistant.KEY_SINGLE_PAYMENT)) {
            json.optBoolean(AiAssistant.KEY_SINGLE_PAYMENT, false)
        } else {
            // 老格式没有该字段：只对图片/截屏来源沿用逐条模式那条「整张票 = 一次付款」规则
            val sourceKind = json.optString("source_kind", "")
            sourceKind == "screen_capture" || sourceKind == "receipt_image" || sourceKind == "chat_image"
        }
        if (!singlePayment) return false
        return savedBills.all { PaymentGroups.isGroupable(it) } &&
            savedBills.distinctBy { paymentAssetKey(it) }.size == 1
    }

    private fun paymentAssetKey(bill: Bill): String =
        bill.accountId?.let { "id:$it" } ?: "name:${bill.accountName.trim()}"

    /** 给这一批账单打上同一个扣款组 id，完成后 toast 并回调。 */
    fun mergeAsOnePayment(ctx: Context, savedBills: List<Bill>, onFinished: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(ctx)
            runCatching {
                BillMutationService.setPaymentGroupBatch(db, savedBills, PaymentGroups.newGroupId())
            }
            withContext(Dispatchers.Main) {
                Utils.toast(ctx, ctx.getString(R.string.toast_bills_merged))
                onFinished()
            }
        }
    }
}
