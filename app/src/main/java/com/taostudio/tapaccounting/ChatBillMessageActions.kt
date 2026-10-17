package com.taostudio.tapaccounting

import androidx.room.withTransaction
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDeleteHelper
import com.taostudio.tapaccounting.logic.BillMutationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

object ChatBillMessageActions {

    data class DeleteResult(
        val deletedBillIds: List<Long>,
        val allDeprecated: Boolean
    )

    suspend fun deleteBillsFromMessage(
        db: AppDatabase,
        displayMessages: MutableList<ChatDisplayItem>,
        messageDbId: Long,
        billsToDelete: List<Bill>,
        formatTime: (Long) -> String
    ): DeleteResult? {
        if (billsToDelete.isEmpty()) return null
        val msgIdx = displayMessages.indexOfFirst { it.dbId == messageDbId }
        if (msgIdx < 0) return null

        val deletedIds = mutableListOf<Long>()
        var deleteFailed = false
        withContext(Dispatchers.IO) {
            for (bill in billsToDelete) {
                if (bill.id > 0L) {
                    if (!com.taostudio.tapaccounting.logic.deleteBillAndRevertBalanceSafe(db, bill)) {
                        deleteFailed = true
                        return@withContext
                    }
                    deletedIds += bill.id
                }
            }
        }
        if (deleteFailed) return null

        return markBillsDeletedFromMessage(
            db = db,
            displayMessages = displayMessages,
            messageDbId = messageDbId,
            deletedBillIds = deletedIds,
            formatTime = formatTime
        )
    }

    suspend fun markBillsDeletedFromMessage(
        db: AppDatabase,
        displayMessages: MutableList<ChatDisplayItem>,
        messageDbId: Long,
        deletedBillIds: Collection<Long>,
        formatTime: (Long) -> String
    ): DeleteResult? {
        val validDeletedIds = deletedBillIds.filter { it > 0L }.toSet()
        if (validDeletedIds.isEmpty()) return null
        val msgIdx = displayMessages.indexOfFirst { it.dbId == messageDbId }
        if (msgIdx < 0) return null

        val currentItem = displayMessages[msgIdx]
        val updatedDeprecatedIds = currentItem.deprecatedBillIds.toMutableSet().apply {
            addAll(validDeletedIds)
        }
        val allDeprecated = currentItem.bills.all {
            updatedDeprecatedIds.contains(it.id) || currentItem.editedBillIds.contains(it.id)
        }
        displayMessages[msgIdx] = currentItem.copy(
            bills = currentItem.bills.toMutableList(),
            isDeprecated = allDeprecated,
            deprecatedBillIds = updatedDeprecatedIds
        )
        persistBillMessage(
            db = db,
            msgId = messageDbId,
            bills = displayMessages[msgIdx].bills,
            deprecatedBillIds = updatedDeprecatedIds,
            editedBillIds = currentItem.editedBillIds,
            markEntireMessageDeprecated = allDeprecated,
            formatTime = formatTime
        )
        return DeleteResult(deletedBillIds = validDeletedIds.toList(), allDeprecated = allDeprecated)
    }

    suspend fun confirmBillsInMessage(
        db: AppDatabase,
        displayMessages: MutableList<ChatDisplayItem>,
        messageDbId: Long,
        formatTime: (Long) -> String
    ): Int {
        val msgIdx = displayMessages.indexOfFirst { it.dbId == messageDbId }
        if (msgIdx < 0) return 0
        val currentItem = displayMessages[msgIdx]
        val indicesToConfirm = currentItem.bills.mapIndexedNotNull { index, bill ->
            if (ChatBillUiHelper.isBillConfirmable(currentItem, bill)) index else null
        }
        if (indicesToConfirm.isEmpty()) return 0

        val pendingBills = indicesToConfirm.map { currentItem.bills[it] }
        val savedBills = withContext(Dispatchers.IO) {
            db.withTransaction {
                pendingBills.map { bill ->
                    BillMutationService.insertBillWithinActiveTransaction(db, bill)
                }
            }
        }

        val updatedBills = currentItem.bills.toMutableList()
        indicesToConfirm.forEachIndexed { savedIndex, rowIdx ->
            updatedBills[rowIdx] = savedBills[savedIndex]
        }
        val billIdsJson = JSONArray(updatedBills.map { it.id.toString() }).toString()
        displayMessages[msgIdx] = currentItem.copy(
            bills = updatedBills,
            isDeprecated = false
        )
        withContext(Dispatchers.IO) {
            db.chatMessageDao().getById(messageDbId)?.let { oldMsg ->
                db.chatMessageDao().update(
                    oldMsg.copy(
                        billIds = billIdsJson,
                        content = ChatBillMessageParser.buildBillMessageContent(
                            bills = updatedBills,
                            formatTime = formatTime,
                            deprecatedBillIds = currentItem.deprecatedBillIds,
                            editedBillIds = currentItem.editedBillIds,
                            snapshotOnly = false
                        )
                    )
                )
            }
        }
        return savedBills.size
    }

    private suspend fun persistBillMessage(
        db: AppDatabase,
        msgId: Long,
        bills: List<Bill>,
        deprecatedBillIds: Set<Long>,
        editedBillIds: Set<Long>,
        markEntireMessageDeprecated: Boolean,
        formatTime: (Long) -> String
    ) {
        if (msgId <= 0L) return
        withContext(Dispatchers.IO) {
            db.chatMessageDao().getById(msgId)?.let { oldMsg ->
                db.chatMessageDao().update(
                    oldMsg.copy(
                        billIds = if (markEntireMessageDeprecated) {
                            ChatBillMessageParser.markBillIdsAsDeprecated(oldMsg.billIds)
                        } else {
                            oldMsg.billIds
                        },
                        content = ChatBillMessageParser.buildBillMessageContent(
                            bills = bills,
                            formatTime = formatTime,
                            deprecatedBillIds = deprecatedBillIds,
                            editedBillIds = editedBillIds
                        )
                    )
                )
            }
        }
    }
}
