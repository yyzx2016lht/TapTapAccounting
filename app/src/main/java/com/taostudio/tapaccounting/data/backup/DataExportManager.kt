package com.taostudio.tapaccounting.data.backup

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taostudio.tapaccounting.data.local.entity.*

object DataExportManager {
    private val gson = Gson()

    fun serialize(data: Any): String = gson.toJson(data)

    /**
     * P1-10: Gson 会绕过 Kotlin 非空/默认值，旧备份缺字段时直接 NPE。
     * 反序列化后用 copy 补默认值（对反射写出的 null 做 SENSELESS_COMPARISON 检查）。
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun sanitizeBill(raw: Bill): Bill {
        val currency = (raw.currency as String?)
        val bookName = (raw.bookName as String?)
        return raw.copy(
            originalAmount = if (raw.originalAmount == 0.0 && raw.amount != 0.0) raw.amount else raw.originalAmount,
            currency = if (currency.isNullOrBlank()) "CNY" else currency,
            categoryName = (raw.categoryName as String?) ?: "",
            accountName = (raw.accountName as String?) ?: "",
            toAccountName = (raw.toAccountName as String?) ?: "",
            remark = (raw.remark as String?) ?: "",
            bookName = if (bookName.isNullOrBlank()) "日常账本" else bookName
        )
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun sanitizeAsset(raw: Asset): Asset {
        val currency = (raw.currency as String?)
        return raw.copy(
            name = (raw.name as String?) ?: "",
            type = (raw.type as String?) ?: "",
            currency = if (currency.isNullOrBlank()) "CNY" else currency,
            remark = (raw.remark as String?) ?: "",
            assetCategory = (raw.assetCategory as String?) ?: Asset.CATEGORY_FUND
        )
    }

    fun deserializeAssets(json: String): List<Asset> {
        val list: List<Asset> = gson.fromJson(json, object : TypeToken<List<Asset>>() {}.type) ?: emptyList()
        return list.map { sanitizeAsset(it) }
    }

    fun deserializeBills(json: String): List<Bill> {
        val list: List<Bill> = gson.fromJson(json, object : TypeToken<List<Bill>>() {}.type) ?: emptyList()
        return list.map { sanitizeBill(it) }
    }

    fun deserializeDeletedBills(json: String): List<DeletedBill> {
        val list: List<DeletedBill> = gson.fromJson(json, object : TypeToken<List<DeletedBill>>() {}.type) ?: emptyList()
        return list.map { raw ->
            @Suppress("SENSELESS_COMPARISON")
            val currency = (raw.currency as String?)
            @Suppress("SENSELESS_COMPARISON")
            val bookName = (raw.bookName as String?)
            raw.copy(
                originalAmount = if (raw.originalAmount == 0.0 && raw.amount != 0.0) raw.amount else raw.originalAmount,
                currency = if (currency.isNullOrBlank()) "CNY" else currency,
                categoryName = (raw.categoryName as String?) ?: "",
                accountName = (raw.accountName as String?) ?: "",
                toAccountName = (raw.toAccountName as String?) ?: "",
                remark = (raw.remark as String?) ?: "",
                bookName = if (bookName.isNullOrBlank()) "日常账本" else bookName
            )
        }
    }

    fun deserializeInvestmentLots(json: String): List<InvestmentLot> =
        gson.fromJson(json, object : TypeToken<List<InvestmentLot>>() {}.type) ?: emptyList()

    fun deserializeCategories(json: String): List<Category> {
        val list: List<Category> = gson.fromJson(json, object : TypeToken<List<Category>>() {}.type) ?: emptyList()
        return list.map { raw ->
            @Suppress("SENSELESS_COMPARISON")
            raw.copy(
                name = (raw.name as String?) ?: "",
                iconId = (raw.iconId as String?) ?: ""
            )
        }
    }

    fun deserializeAiRules(json: String): List<AiRule> =
        gson.fromJson(json, object : TypeToken<List<AiRule>>() {}.type) ?: emptyList()

    fun deserializeChatMessages(json: String): List<ChatMessage> =
        gson.fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type) ?: emptyList()

    fun deserializeBudgets(json: String): List<Budget> =
        gson.fromJson(json, object : TypeToken<List<Budget>>() {}.type) ?: emptyList()

    fun deserializeRecurringPatterns(json: String): List<RecurringPattern> =
        gson.fromJson(json, object : TypeToken<List<RecurringPattern>>() {}.type) ?: emptyList()

    fun deserializeBooks(json: String): List<Book> =
        gson.fromJson(json, object : TypeToken<List<Book>>() {}.type) ?: emptyList()

    fun deserializeSharedLedgers(json: String): List<SharedLedgerBackup> =
        gson.fromJson(json, object : TypeToken<List<SharedLedgerBackup>>() {}.type) ?: emptyList()

    fun deserializeSharedMembers(json: String): List<SharedMemberBackup> =
        gson.fromJson(json, object : TypeToken<List<SharedMemberBackup>>() {}.type) ?: emptyList()

    fun deserializePendingSyncQueue(json: String): List<PendingSyncQueueBackup> =
        gson.fromJson(json, object : TypeToken<List<PendingSyncQueueBackup>>() {}.type) ?: emptyList()

    fun deserializePendingSyncOperations(json: String): List<PendingSyncOperationBackup> =
        gson.fromJson(json, object : TypeToken<List<PendingSyncOperationBackup>>() {}.type) ?: emptyList()
}
