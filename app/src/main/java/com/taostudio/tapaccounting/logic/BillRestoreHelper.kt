package com.taostudio.tapaccounting.logic

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import com.taostudio.tapaccounting.ChatBillMessageParser
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.DeletedBill
import com.taostudio.tapaccounting.data.sync.SharedMutationHooks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BillRestoreHelper {

    private val contentTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    suspend fun restoreBills(db: AppDatabase, deletedBills: List<DeletedBill>): List<Bill> {
        if (deletedBills.isEmpty()) return emptyList()

        return db.withTransaction {
            val billDao = db.billDao()
            val deletedBillDao = db.deletedBillDao()

            val restoredBills = mutableListOf<Bill>()
            val idRemapping = mutableMapOf<Long, Long>()

            val restoreOrder = deletedBills.sortedWith(
                compareBy<DeletedBill> { it.relatedBillId != null }
                    .thenBy { it.deletedAt }
            )

            for (deletedBill in restoreOrder) {
                val origId = deletedBill.originalBillId
                val existingBill = if (origId > 0L) billDao.getBillById(origId) else null

                val billToRestore = SharedMutationHooks.prepareLocalBill(
                    db,
                    deletedBill.toBill().copy(
                        relatedBillId = deletedBill.relatedBillId?.let { idRemapping[it] ?: it }
                    )
                )
                val finalBill = if (existingBill == null && origId > 0L) {
                    billToRestore.copy(id = origId)
                } else {
                    billToRestore
                }

                val insertedId = billDao.insertBill(finalBill)
                val restoredBill = finalBill.copy(id = insertedId)

                if (origId > 0L && insertedId != origId) {
                    idRemapping[origId] = insertedId
                }

                // P0-3：恢复前校验汇率；失败中止整个事务，禁止「恢复账单但余额不动」
                BillMutationService.validateRequiredRatesForBill(db, restoredBill)
                BillAssetImpactService.applyBillBalanceImpact(db, restoredBill)
                InvestmentInterestService.applyEstimateBillLotImpact(
                    db = db,
                    bill = restoredBill,
                    restoring = true
                )
                if (restoredBill.type == Bill.TYPE_TRANSFER) {
                    val targetAsset = restoredBill.toAccountId?.let { db.assetDao().getAssetById(it) }
                    if (targetAsset?.assetCategory == com.taostudio.tapaccounting.data.local.entity.Asset.CATEGORY_INVESTMENT &&
                        db.investmentLotDao().getLotBySourceBillId(restoredBill.id) == null
                    ) {
                        val start = InvestmentInterestService.plusDays(
                            InvestmentInterestService.startOfDay(restoredBill.time),
                            1
                        )
                        InvestmentInterestService.createOrReplaceLotForTransfer(
                            db = db,
                            bill = restoredBill,
                            targetAsset = targetAsset,
                            schedule = InvestmentInterestService.InvestmentSchedule(
                                startEarningAt = start,
                                firstPayoutAt = InvestmentInterestService.firstPayoutFor(
                                    start,
                                    com.taostudio.tapaccounting.data.local.entity.InvestmentLot.CYCLE_DAILY
                                ),
                                annualInterestRate = targetAsset.annualInterestRate
                            )
                        )
                    }
                }
                SharedMutationHooks.enqueueSaved(db, restoredBill)
                deletedBillDao.delete(deletedBill)
                restoredBills.add(restoredBill)
            }

            updateChatMessages(db, restoredBills, idRemapping)
            restoredBills
        }
    }

    private suspend fun updateChatMessages(
        db: AppDatabase,
        restoredBills: List<Bill>,
        idRemapping: Map<Long, Long>
    ) {
        if (restoredBills.isEmpty()) return

        val chatMessageDao = db.chatMessageDao()
        val allMessages = chatMessageDao.getAll()
        val aiBillMessages = allMessages.filter { it.msgType == 4 }

        val restoredById = restoredBills.associateBy { it.id }
        val idRemapReverse = idRemapping.entries.associate { (k, v) -> v to k }

        val allOriginalIds = restoredBills.map { bill ->
            idRemapReverse[bill.id] ?: bill.id
        }.toSet()

        for (msg in aiBillMessages) {
            val parsedBillIds = ChatBillMessageParser.parseBillIds(msg.billIds)
            if (parsedBillIds.isEmpty()) continue

            val hasRestoredBill = parsedBillIds.any { it in allOriginalIds }
            if (!hasRestoredBill) continue

            val wasDeprecated = ChatBillMessageParser.isDeprecatedBillMessage(msg.billIds)
            val deprecatedBillIds = ChatBillMessageParser.parseDeprecatedBillIdsFromContent(msg.content)

            val newBillIds = parsedBillIds.map { id -> idRemapping[id] ?: id }

            val stillDeprecated = deprecatedBillIds.filter { it !in allOriginalIds }
            val restoredIdSet = restoredBills.map { it.id }.toSet()
            val hasLiveRestoredBill = newBillIds.any { it in restoredIdSet }
            val baseBillIdsJson = JSONArray(newBillIds.map { it.toString() }).toString()
            val newBillIdsStr = if (wasDeprecated && !hasLiveRestoredBill && stillDeprecated.isNotEmpty()) {
                ChatBillMessageParser.markBillIdsAsDeprecated(baseBillIdsJson)
            } else {
                baseBillIdsJson
            }

            val newContent = updateContent(msg.content, restoredById, idRemapping, allOriginalIds)
            chatMessageDao.update(msg.copy(billIds = newBillIdsStr, content = newContent))
        }
    }

    private fun updateContent(
        content: String,
        restoredById: Map<Long, Bill>,
        idRemapping: Map<Long, Long>,
        allOriginalIds: Set<Long>
    ): String {
        if (content.isBlank()) return content
        return try {
            val root = JSONObject(content)

            val billsArr = root.optJSONArray("bills")
            if (billsArr != null) {
                val newBillsArr = JSONArray()
                for (i in 0 until billsArr.length()) {
                    val billJson = billsArr.optJSONObject(i) ?: continue
                    val billId = billJson.optLong("id", 0L)
                    val origId = idRemapping.entries.find { it.value == billId }?.key ?: billId

                    if (origId in allOriginalIds) {
                        val actualId = idRemapping[origId] ?: origId
                        val restoredBill = restoredById[actualId]
                        if (restoredBill != null) {
                            newBillsArr.put(billToJson(restoredBill))
                        } else {
                            billJson.put("id", actualId)
                            newBillsArr.put(billJson)
                        }
                    } else {
                        newBillsArr.put(billJson)
                    }
                }
                root.put("bills", newBillsArr)
            }

            val deprecatedArr = root.optJSONArray("deprecatedBillIds")
            if (deprecatedArr != null) {
                val newDeprecatedArr = JSONArray()
                for (i in 0 until deprecatedArr.length()) {
                    val depId = deprecatedArr.optLong(i, 0L)
                    if (depId !in allOriginalIds) {
                        newDeprecatedArr.put(depId)
                    }
                }
                root.put("deprecatedBillIds", newDeprecatedArr)
            }

            val editedArr = root.optJSONArray("editedBillIds")
            if (editedArr != null) {
                val newEditedArr = JSONArray()
                for (i in 0 until editedArr.length()) {
                    val editedId = editedArr.optLong(i, 0L)
                    newEditedArr.put(idRemapping[editedId] ?: editedId)
                }
                root.put("editedBillIds", newEditedArr)
            }

            root.toString()
        } catch (_: Exception) {
            content
        }
    }

    private fun billToJson(bill: Bill): JSONObject {
        return JSONObject().apply {
            put("id", bill.id)
            put("amount", bill.amount)
            put("type", if (bill.subType == Bill.SUBTYPE_REPAYMENT) 3 else bill.type)
            put("subType", bill.subType)
            put("originalAmount", bill.originalAmount)
            put("asset_name", bill.accountName)
            put("category_name", bill.categoryName.replace(" - ", "/::/"))
            put("time", contentTimeFormat.format(Date(bill.time)))
            put("remarks", bill.remark)
            put("currency", bill.currency)
            put("exchangeRate", bill.exchangeRate)
            put("to_asset_name", bill.toAccountName)
            put("fee", bill.fee)
            if (bill.relatedBillId != null) {
                put("relatedBillId", bill.relatedBillId)
            }
        }
    }

    private fun DeletedBill.toBill(): Bill {
        return Bill(
            type = type,
            subType = subType,
            amount = amount,
            originalAmount = originalAmount,
            currency = currency,
            exchangeRate = exchangeRate,
            categoryId = categoryId,
            accountId = accountId,
            toAccountId = toAccountId,
            categoryName = categoryName,
            accountName = accountName,
            toAccountName = toAccountName,
            time = time,
            remark = remark,
            fee = fee,
            bookName = bookName,
            relatedBillId = relatedBillId,
            excludeFromStats = excludeFromStats
        )
    }
}
