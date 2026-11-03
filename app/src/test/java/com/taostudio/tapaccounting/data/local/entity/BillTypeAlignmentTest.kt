package com.taostudio.tapaccounting.data.local.entity

import com.taostudio.tapaccounting.capture.contract.LocalBillType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 契约层的类型常量必须和 app 的 [Bill] 保持一致。
 *
 * 任何一边改值（尤其是还款 2/1 vs 旧值 3）都会在这里失败。
 */
class BillTypeAlignmentTest {

    @Test
    fun `local bill type constants match app bill constants`() {
        assertEquals(Bill.TYPE_EXPENSE, LocalBillType.TYPE_EXPENSE)
        assertEquals(Bill.TYPE_INCOME, LocalBillType.TYPE_INCOME)
        assertEquals(Bill.TYPE_TRANSFER, LocalBillType.TYPE_TRANSFER)
        assertEquals(Bill.TYPE_REPAYMENT, LocalBillType.TYPE_REPAYMENT)

        assertEquals(Bill.SUBTYPE_NORMAL, LocalBillType.SUBTYPE_NORMAL)
        assertEquals(Bill.SUBTYPE_REPAYMENT, LocalBillType.SUBTYPE_REPAYMENT)
        assertEquals(Bill.SUBTYPE_REFUND, LocalBillType.SUBTYPE_REFUND)
    }
}
