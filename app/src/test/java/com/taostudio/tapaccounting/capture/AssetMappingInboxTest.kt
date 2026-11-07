package com.taostudio.tapaccounting.capture

import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.data.AssetMappingRule
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import com.taostudio.tapaccounting.capture.data.PaymentCaptureDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssetMappingInboxTest {

    private class FakePaymentCaptureDao : PaymentCaptureDao {
        val items = mutableMapOf<String, PaymentCapture>()

        override suspend fun insert(capture: PaymentCapture): Long {
            if (items.containsKey(capture.id)) return -1L
            items[capture.id] = capture
            return 1L
        }

        override suspend fun update(capture: PaymentCapture): Int {
            items[capture.id] = capture
            return 1
        }

        override suspend fun listRecent(limit: Int): List<PaymentCapture> =
            items.values.sortedByDescending { it.paidAt }.take(limit)

        override suspend fun listByStatuses(statuses: List<String>, limit: Int): List<PaymentCapture> =
            items.values.filter { it.status in statuses }.sortedByDescending { it.paidAt }.take(limit)

        override suspend fun listByStatusesPage(
            statuses: List<String>,
            limit: Int,
            offset: Int,
        ): List<PaymentCapture> =
            items.values.filter { it.status in statuses }.sortedByDescending { it.paidAt }.drop(offset).take(limit)

        override suspend fun findById(id: String): PaymentCapture? = items[id]

        override suspend fun findBySourceTxn(source: String, sourceTxnId: String): PaymentCapture? =
            items.values.firstOrNull { it.source == source && it.sourceTxnId == sourceTxnId }

        override suspend fun countByStatus(statuses: List<String>): Int =
            items.values.count { it.status in statuses }

        override suspend fun updateStatus(id: String, status: String, updatedAt: Long): Int {
            val cur = items[id] ?: return 0
            items[id] = cur.copy(status = status, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updateNote(id: String, note: String?, updatedAt: Long): Int {
            val cur = items[id] ?: return 0
            items[id] = cur.copy(note = note, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updateAssetName(id: String, assetName: String?, updatedAt: Long): Int {
            val cur = items[id] ?: return 0
            items[id] = cur.copy(assetName = assetName, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updateCategoryName(id: String, categoryName: String?, updatedAt: Long): Int {
            val cur = items[id] ?: return 0
            items[id] = cur.copy(categoryName = categoryName, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updateDraftFields(
            id: String,
            note: String?,
            assetName: String?,
            categoryName: String?,
            updatedAt: Long,
        ): Int {
            val cur = items[id] ?: return 0
            items[id] = cur.copy(
                note = note,
                assetName = assetName,
                categoryName = categoryName,
                updatedAt = updatedAt,
            )
            return 1
        }

        override suspend fun updateSplitRequested(id: String, splitRequested: Boolean, updatedAt: Long): Int {
            val cur = items[id] ?: return 0
            items[id] = cur.copy(splitRequested = splitRequested, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updateBookName(id: String, bookName: String?, updatedAt: Long): Int {
            val cur = items[id] ?: return 0
            items[id] = cur.copy(bookName = bookName, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updateExcludeFromStats(id: String, excludeFromStats: Boolean, updatedAt: Long): Int {
            val cur = items[id] ?: return 0
            items[id] = cur.copy(excludeFromStats = excludeFromStats, updatedAt = updatedAt)
            return 1
        }

        override suspend fun deleteById(id: String): Int = if (items.remove(id) != null) 1 else 0

        override suspend fun deleteByStatusesOlderThan(statuses: List<String>, cutoff: Long): Int {
            val toRemove = items.values.filter { it.status in statuses && it.paidAt < cutoff }.map { it.id }
            toRemove.forEach { items.remove(it) }
            return toRemove.size
        }

        override suspend fun deleteByStatuses(statuses: List<String>): Int {
            val toRemove = items.values.filter { it.status in statuses }.map { it.id }
            toRemove.forEach { items.remove(it) }
            return toRemove.size
        }

        override suspend fun listMergedChildren(groupId: String): List<PaymentCapture> =
            items.values.filter { it.groupId == groupId }

        override suspend fun unmerge(id: String, status: String, updatedAt: Long): Int {
            val cur = items[id] ?: return 0
            items[id] = cur.copy(status = status, groupId = null, updatedAt = updatedAt)
            return 1
        }

        override suspend fun findPotentialDuplicates(
            amountCents: Long,
            startTime: Long,
            endTime: Long,
            statuses: List<String>,
        ): List<PaymentCapture> =
            items.values.filter {
                it.amountCents == amountCents &&
                    it.paidAt in startTime..endTime &&
                    it.status in statuses
            }
    }

    private fun sampleCapture(
        id: String,
        channel: String?,
        asset: String? = null,
        status: String = CaptureStatus.PENDING,
    ): PaymentCapture {
        val now = System.currentTimeMillis()
        return PaymentCapture(
            id = id,
            source = CaptureSource.ALIPAY,
            sourceTxnId = "txn-$id",
            amountCents = 1000L,
            paymentMethodRaw = channel,
            assetName = asset,
            paidAt = now,
            capturedAt = now,
            createdAt = now,
            updatedAt = now,
            status = status,
        )
    }

    @Test
    fun testReapplyAssetMappingForRule_exactAndContainsMatch() = runBlocking {
        val fakeDao = FakePaymentCaptureDao()
        val repo = CaptureRepository(fakeDao)

        fakeDao.insert(sampleCapture("1", "招商银行储蓄卡(4852)", asset = null))
        fakeDao.insert(sampleCapture("2", "招商银行信用卡(1234)", asset = "旧账户"))
        fakeDao.insert(sampleCapture("3", "微信零钱", asset = "微信"))
        fakeDao.insert(sampleCapture("4", "招商银行储蓄卡(4852)", asset = null, status = CaptureStatus.RECORDED))

        val rule = AssetMappingRule(
            id = 1L,
            pattern = "招商银行",
            targetAssetName = "招商银行卡",
            isRegex = false,
            enabled = true,
        )

        val updatedCount = repo.reapplyAssetMappingForRule(rule)
        assertEquals(2, updatedCount) // items 1 and 2 updated; item 4 is RECORDED (skipped); item 3 is WeChat

        assertEquals("招商银行卡", fakeDao.findById("1")?.assetName)
        assertEquals("招商银行卡", fakeDao.findById("2")?.assetName)
        assertEquals("微信", fakeDao.findById("3")?.assetName)
        assertNull(fakeDao.findById("4")?.assetName) // unchanged
    }

    @Test
    fun testReapplyAssetMappingForRule_regexMatch() = runBlocking {
        val fakeDao = FakePaymentCaptureDao()
        val repo = CaptureRepository(fakeDao)

        fakeDao.insert(sampleCapture("1", "建设银行储蓄卡(9988)", asset = null))
        fakeDao.insert(sampleCapture("2", "工商银行(1122)", asset = null))

        val rule = AssetMappingRule(
            id = 2L,
            pattern = "建设银行.*\\d+",
            targetAssetName = "建行",
            isRegex = true,
            enabled = true,
        )

        val updatedCount = repo.reapplyAssetMappingForRule(rule)
        assertEquals(1, updatedCount)
        assertEquals("建行", fakeDao.findById("1")?.assetName)
        assertNull(fakeDao.findById("2")?.assetName)
    }

    @Test
    fun testMissingAssetGuardScan() {
        val ready1 = sampleCapture("1", "支付宝", asset = "支付宝")
        val ready2 = sampleCapture("2", "招行", asset = "")
        val ready3 = sampleCapture("3", "微信", asset = null)

        val readyItems = listOf(ready1, ready2, ready3)
        val unmapped = readyItems.filter { it.assetName.isNullOrBlank() }

        assertEquals(2, unmapped.size)
        assertEquals(listOf("2", "3"), unmapped.map { it.id })
    }

    @Test
    fun testCategorySorting_placesOtherAtEnd() {
        data class SimpleCat(val name: String)
        val list = listOf(
            SimpleCat("餐饮"),
            SimpleCat("其他"),
            SimpleCat("交通"),
            SimpleCat("其他支出"),
        )
        val sorted = list.sortedWith(compareBy<SimpleCat> { if (it.name.trim() == "其他") 1 else 0 })

        assertEquals("餐饮", sorted[0].name)
        assertEquals("交通", sorted[1].name)
        assertEquals("其他支出", sorted[2].name)
        assertEquals("其他", sorted[3].name)
    }
}
