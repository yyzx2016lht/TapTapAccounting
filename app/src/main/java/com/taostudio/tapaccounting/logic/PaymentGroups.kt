package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill
import java.util.UUID

/**
 * 扣款组：同一笔实际扣款（如一张小票）拆成多条分类账单后，用 [Bill.paymentGroupId] 串起来，
 * 资产详情把它们折叠成一行显示合计，方便与银行流水逐笔对账。分类统计仍按单条账单。
 *
 * 组成员不做强一致维护：显示时只把「当前列表里可见、可合并」的同组账单收拢，
 * 不足 2 笔就按普通账单显示，所以移动/删除其中一条不会留下坏数据。
 */
object PaymentGroups {

    sealed interface Item {
        data class Single(val bill: Bill) : Item
        data class Group(val groupId: String, val bills: List<Bill>) : Item
    }

    enum class Action { MERGE, SPLIT }

    enum class MergeBlock { TOO_FEW, NOT_INCOME_EXPENSE, OTHER_ASSET }

    fun newGroupId(): String = UUID.randomUUID().toString()

    fun isGroupable(bill: Bill): Boolean =
        (bill.type == Bill.TYPE_EXPENSE || bill.type == Bill.TYPE_INCOME) &&
            bill.subType == Bill.SUBTYPE_NORMAL

    /** 保持 [bills] 原有顺序，把同组成员收拢到该组第一个成员出现的位置。 */
    fun arrange(bills: List<Bill>): List<Item> {
        val groups = bills
            .filter { isGroupable(it) && !it.paymentGroupId.isNullOrBlank() }
            .groupBy { it.paymentGroupId!! }
            .filterValues { it.size >= 2 }
        val emitted = mutableSetOf<String>()
        return bills.mapNotNull { bill ->
            val groupId = bill.paymentGroupId?.takeIf { isGroupable(bill) && it in groups }
            when {
                groupId == null -> Item.Single(bill)
                emitted.add(groupId) -> Item.Group(groupId, groups.getValue(groupId))
                else -> null
            }
        }
    }

    /** 选中的账单全部属于同一个组时是「拆开」，否则是「合并」。 */
    fun resolveAction(selected: Collection<Bill>): Action {
        val groupId = selected.map { it.paymentGroupId }.distinct().singleOrNull()
        return if (selected.isNotEmpty() && !groupId.isNullOrBlank()) Action.SPLIT else Action.MERGE
    }

    fun mergeBlock(selected: Collection<Bill>, assetId: Long): MergeBlock? = when {
        selected.size < 2 -> MergeBlock.TOO_FEW
        !selected.all(::isGroupable) -> MergeBlock.NOT_INCOME_EXPENSE
        !selected.all { it.accountId == assetId } -> MergeBlock.OTHER_ASSET
        else -> null
    }
}
