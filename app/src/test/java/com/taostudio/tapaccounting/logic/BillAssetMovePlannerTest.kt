package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「移动账单到其他资产」的账户改挂规则。
 *
 * 余额影响不在这里（由 BillMutationService.moveBillsToAsset 回滚+重放），
 * 这里锁死的是：哪一端该换、哪一端不该换、以及不该动的字段一个都不能动。
 */
class BillAssetMovePlannerTest {

    @Test
    fun expenseMatchingAccountIdMovesSourceSideOnly() {
        val bill = bill(accountId = SOURCE.id, accountName = SOURCE.name)

        val moved = BillAssetMovePlanner.moveToAsset(bill, SOURCE, TARGET)

        assertEquals(TARGET.id, moved?.accountId)
        assertEquals(TARGET.name, moved?.accountName)
        assertEquals("", moved?.toAccountName)
    }

    @Test
    fun expenseMatchingNameFallbackStillMoves() {
        // 旧数据只有账户名没有 id（backfill 未覆盖到）时，按名字匹配兜底
        val bill = bill(accountId = null, accountName = SOURCE.name)

        val moved = BillAssetMovePlanner.moveToAsset(bill, SOURCE, TARGET)

        assertEquals(TARGET.id, moved?.accountId)
        assertEquals(TARGET.name, moved?.accountName)
    }

    @Test
    fun incomeMatchingToAccountMovesTargetSideOnly() {
        val bill = bill(
            type = Bill.TYPE_INCOME,
            accountId = null,
            accountName = "",
            toAccountId = SOURCE.id,
            toAccountName = SOURCE.name
        )

        val moved = BillAssetMovePlanner.moveToAsset(bill, SOURCE, TARGET)

        assertEquals(null, moved?.accountId)
        assertEquals(TARGET.id, moved?.toAccountId)
        assertEquals(TARGET.name, moved?.toAccountName)
    }

    @Test
    fun transferFromSourceAssetMovesSourceSideOnly() {
        val bill = transfer(accountId = SOURCE.id, accountName = SOURCE.name)

        val moved = BillAssetMovePlanner.moveToAsset(bill, SOURCE, TARGET)

        assertEquals(TARGET.id, moved?.accountId)
        assertEquals(TARGET.name, moved?.accountName)
        assertEquals(OTHER.id, moved?.toAccountId)
        assertEquals(OTHER.name, moved?.toAccountName)
    }

    @Test
    fun transferIntoSourceAssetMovesTargetSideOnly() {
        val bill = transfer(
            accountId = OTHER.id,
            accountName = OTHER.name,
            toAccountId = SOURCE.id,
            toAccountName = SOURCE.name
        )

        val moved = BillAssetMovePlanner.moveToAsset(bill, SOURCE, TARGET)

        assertEquals(OTHER.id, moved?.accountId)
        assertEquals(OTHER.name, moved?.accountName)
        assertEquals(TARGET.id, moved?.toAccountId)
        assertEquals(TARGET.name, moved?.toAccountName)
    }

    @Test
    fun transferWithBothEndsOnSourceAssetMovesBothEnds() {
        val bill = transfer(accountId = SOURCE.id, accountName = SOURCE.name)

        val moved = BillAssetMovePlanner.moveToAsset(bill, SOURCE, SOURCE)

        assertNull(moved) // 目标即源：不需要动

        val billBoth = transfer(
            accountId = SOURCE.id,
            accountName = SOURCE.name,
            toAccountId = SOURCE.id,
            toAccountName = SOURCE.name
        )
        val movedBoth = BillAssetMovePlanner.moveToAsset(billBoth, SOURCE, TARGET)

        assertEquals(TARGET.id, movedBoth?.accountId)
        assertEquals(TARGET.name, movedBoth?.accountName)
        assertEquals(TARGET.id, movedBoth?.toAccountId)
        assertEquals(TARGET.name, movedBoth?.toAccountName)
    }

    @Test
    fun billWithoutAnyReferenceToSourceAssetIsNotTouched() {
        val bill = bill(accountId = OTHER.id, accountName = OTHER.name)

        assertNull(BillAssetMovePlanner.moveToAsset(bill, SOURCE, TARGET))
    }

    @Test
    fun mergingGroupMembershipSurvivesTheMove() {
        // 扣款组合并显示用的分组只影响展示；整组移动后分组必须原样保留
        val bill = bill(paymentGroupId = "group-1")

        val moved = BillAssetMovePlanner.moveToAsset(bill, SOURCE, TARGET)

        assertEquals("group-1", moved?.paymentGroupId)
        assertEquals(20.0, moved?.amount ?: 0.0, 0.0)
    }

    private fun bill(
        type: Int = Bill.TYPE_EXPENSE,
        accountId: Long? = SOURCE.id,
        accountName: String = SOURCE.name,
        toAccountId: Long? = null,
        toAccountName: String = "",
        paymentGroupId: String? = null
    ): Bill = Bill(
        id = 10L,
        type = type,
        amount = 20.0,
        accountId = accountId,
        accountName = accountName,
        toAccountId = toAccountId,
        toAccountName = toAccountName,
        time = 0L,
        paymentGroupId = paymentGroupId
    )

    private fun transfer(
        accountId: Long?,
        accountName: String,
        toAccountId: Long? = OTHER.id,
        toAccountName: String = OTHER.name
    ): Bill = bill(
        type = Bill.TYPE_TRANSFER,
        accountId = accountId,
        accountName = accountName,
        toAccountId = toAccountId,
        toAccountName = toAccountName
    )

    private companion object {
        val SOURCE = Asset(id = 1L, name = "微信", type = "微信")
        val TARGET = Asset(id = 2L, name = "支付宝", type = "支付宝")
        val OTHER = Asset(id = 3L, name = "招商银行", type = "招商银行")
    }
}
