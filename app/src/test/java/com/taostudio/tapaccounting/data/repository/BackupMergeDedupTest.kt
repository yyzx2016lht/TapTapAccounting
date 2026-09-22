package com.taostudio.tapaccounting.data.repository

import com.taostudio.tapaccounting.data.local.entity.Bill
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-8: merge-restore dedup must not collide across books. */
class BackupMergeDedupTest {
    private fun bill(
        time: Long = 100L,
        amount: Double = 12.5,
        type: Int = 0,
        accountName: String = "现金",
        bookName: String = "日常"
    ) = Bill(
        id = 0,
        type = type,
        amount = amount,
        time = time,
        categoryName = "餐饮",
        accountId = 1,
        accountName = accountName,
        bookName = bookName
    )

    @Test
    fun `same signature in different books is not a duplicate`() {
        val a = bill(bookName = "日常")
        val b = bill(bookName = "旅行")
        assertFalse(BackupRepository.isSameBillForMerge(a, b))
    }

    @Test
    fun `same book and signature is a duplicate`() {
        val a = bill(bookName = "日常")
        val b = bill(bookName = "日常")
        assertTrue(BackupRepository.isSameBillForMerge(a, b))
    }

    @Test
    fun `different amount account or time is not a duplicate`() {
        val base = bill()
        assertFalse(BackupRepository.isSameBillForMerge(base, base.copy(amount = 12.6)))
        assertFalse(BackupRepository.isSameBillForMerge(base, base.copy(accountName = "银行卡")))
        assertFalse(BackupRepository.isSameBillForMerge(base, base.copy(time = 101)))
    }
}
