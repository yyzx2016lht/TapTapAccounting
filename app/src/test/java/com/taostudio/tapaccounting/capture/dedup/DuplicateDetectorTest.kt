package com.taostudio.tapaccounting.capture.dedup

import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateDetectorTest {

    private fun createCapture(
        id: String,
        amountCents: Long = 1000L,
        paidAt: Long = 100000L,
        source: String = "WECHAT",
        sourceTxnId: String? = null,
        channel: String? = "微信支付",
        merchant: String? = "瑞幸咖啡",
        assetName: String? = "微信零钱",
        toAssetName: String? = null,
        paymentMethodRaw: String? = null,
        billType: Int? = LocalBillType.TYPE_EXPENSE,
        status: String = CaptureStatus.PENDING,
        groupId: String? = null,
        excludeFromStats: Boolean = false,
        note: String? = null,
    ): PaymentCapture = PaymentCapture(
        id = id,
        source = source,
        sourceTxnId = sourceTxnId,
        amountCents = amountCents,
        currency = "CNY",
        merchant = merchant,
        categoryName = "餐饮",
        paymentMethodRaw = paymentMethodRaw,
        assetName = assetName,
        toAssetName = toAssetName,
        billType = billType,
        subType = null,
        needsRefundLink = false,
        paidAt = paidAt,
        capturedAt = paidAt,
        bookName = "日常账本",
        note = note,
        status = status,
        needsAmount = false,
        rawJson = null,
        ruleVersion = null,
        sourceAppVersion = null,
        excludeFromStats = excludeFromStats,
        groupId = groupId,
        channel = channel,
        createdAt = paidAt,
        updatedAt = paidAt,
    )

    @Test
    fun `exact timestamp is always duplicate when txn id is absent`() {
        val c1 = createCapture(id = "1", paidAt = 100000L, channel = "微信支付")
        val c2 = createCapture(id = "2", paidAt = 100000L, channel = "微信支付")
        assertTrue(DuplicateDetector.isDuplicate(c1, c2))
    }

    @Test
    fun `consecutive red packets with different txn ids are NOT duplicate even if sent in same second`() {
        val packet1 = createCapture(id = "1", amountCents = 5200L, paidAt = 100000L, sourceTxnId = "wx_redpack_1001", channel = "微信支付")
        val packet2 = createCapture(id = "2", amountCents = 5200L, paidAt = 100500L, sourceTxnId = "wx_redpack_1002", channel = "微信支付") // 0.5s apart, diff txnId
        assertFalse(DuplicateDetector.isDuplicate(packet1, packet2))
    }

    @Test
    fun `same channel within 2 seconds is duplicate broadcast`() {
        val c1 = createCapture(id = "1", paidAt = 100000L, channel = "微信支付")
        val c2 = createCapture(id = "2", paidAt = 101500L, channel = "微信支付") // 1.5s difference
        assertTrue(DuplicateDetector.isDuplicate(c1, c2))
    }

    @Test
    fun `same channel over 2 seconds is consecutive payment NOT duplicate`() {
        val c1 = createCapture(id = "1", paidAt = 100000L, channel = "微信支付")
        val c2 = createCapture(id = "2", paidAt = 103000L, channel = "微信支付") // 3s difference
        assertFalse(DuplicateDetector.isDuplicate(c1, c2))
    }

    @Test
    fun `merged parent containing incoming channel over 2 seconds is NOT duplicate`() {
        val parent = createCapture(id = "1", paidAt = 100000L, channel = "微信支付 + 招商银行短信")
        val incoming = createCapture(id = "2", paidAt = 105000L, channel = "微信支付") // 5s later WeChat payment
        assertFalse(DuplicateDetector.isDuplicate(incoming, parent))
    }

    @Test
    fun `different channel within 300s window is duplicate`() {
        val wechat = createCapture(id = "1", paidAt = 100000L, channel = "微信支付", source = "WECHAT")
        val bankSms = createCapture(id = "2", paidAt = 105000L, channel = "招商银行短信", source = "SMS") // 5s difference
        assertTrue(DuplicateDetector.isDuplicate(bankSms, wechat))
    }

    @Test
    fun `different channel beyond 300s window is NOT duplicate`() {
        val wechat = createCapture(id = "1", paidAt = 100000L, channel = "微信支付", source = "WECHAT")
        val bankSms = createCapture(id = "2", paidAt = 450000L, channel = "招商银行短信", source = "SMS") // 350s difference
        assertFalse(DuplicateDetector.isDuplicate(bankSms, wechat))
    }

    @Test
    fun `different amounts are NOT duplicate`() {
        val c1 = createCapture(id = "1", amountCents = 1000L)
        val c2 = createCapture(id = "2", amountCents = 2000L)
        assertFalse(DuplicateDetector.isDuplicate(c1, c2))
    }

    @Test
    fun `different bill types are NOT duplicate`() {
        val expense = createCapture(id = "1", billType = LocalBillType.TYPE_EXPENSE)
        val income = createCapture(id = "2", billType = LocalBillType.TYPE_INCOME)
        assertFalse(DuplicateDetector.isDuplicate(expense, income))
    }

    @Test
    fun `withdrawal start and arrival with different ids and bill types are duplicate`() {
        // 真机数据（2026-11-05）：微信零钱提现先后两条报文，流水号不同、类型分别被识别成支出与转账
        val start = createCapture(
            id = "1",
            amountCents = 100L,
            paidAt = 1793884293000L,
            source = "WECHAT",
            sourceTxnId = "2038841640456576481",
            channel = "微信[微信支付-提现]",
            merchant = "零钱提现发起",
            billType = LocalBillType.TYPE_EXPENSE,
        )
        val arrived = createCapture(
            id = "2",
            amountCents = 100L,
            paidAt = 1793884294000L,
            source = "WECHAT",
            sourceTxnId = "5963871767958470803",
            channel = "微信[微信支付-零钱提现到账]",
            merchant = "零钱提现已到账",
            billType = LocalBillType.TYPE_TRANSFER,
        )
        assertTrue(DuplicateDetector.isDuplicate(arrived, start))
        assertEquals(LocalBillType.TYPE_TRANSFER, DuplicateDetector.merge(start, arrived).billType)
    }

    @Test
    fun `withdrawal start and arrival beyond 5 minutes are NOT duplicate`() {
        val start = createCapture(
            id = "1",
            sourceTxnId = "a",
            merchant = "零钱提现发起",
            billType = LocalBillType.TYPE_EXPENSE,
        )
        val arrived = createCapture(
            id = "2",
            paidAt = 100000L + DuplicateDetector.DEFAULT_WINDOW_MILLIS + 1L,
            sourceTxnId = "b",
            merchant = "零钱提现已到账",
            billType = LocalBillType.TYPE_TRANSFER,
        )
        assertFalse(DuplicateDetector.isDuplicate(arrived, start))
    }

    @Test
    fun `two real transfers to same person without stage keywords are NOT duplicate`() {
        val first = createCapture(
            id = "1",
            sourceTxnId = "t1",
            merchant = "转账-张三",
            channel = "微信[微信支付-转账]",
            billType = LocalBillType.TYPE_TRANSFER,
        )
        val second = createCapture(
            id = "2",
            paidAt = 130000L,
            sourceTxnId = "t2",
            merchant = "转账-张三",
            channel = "微信[微信支付-转账]",
            billType = LocalBillType.TYPE_TRANSFER,
        )
        assertFalse(DuplicateDetector.isDuplicate(second, first))
    }

    @Test
    fun `findDuplicate correctly picks root candidate and ignores children`() {
        val parent = createCapture(id = "1", paidAt = 100000L, channel = "微信支付")
        val child = createCapture(id = "2", paidAt = 101000L, channel = "招商银行短信", groupId = "1", status = CaptureStatus.DUPLICATE)
        val incoming = createCapture(id = "3", paidAt = 102000L, channel = "掌上生活APP")

        val candidates = listOf(child, parent)
        val found = DuplicateDetector.findDuplicate(incoming, candidates)
        assertNotNull(found)
        assertEquals("1", found!!.id)
    }

    @Test
    fun `transfer income after wechat withdrawal is duplicate`() {
        // 真机数据（2026-11-05 22:02）：微信零钱提现到招商银行，2 秒后招行再推送收款。
        val transfer = createCapture(
            id = "transfer",
            amountCents = 1000L,
            paidAt = 1793887365000L,
            source = "WECHAT",
            channel = "微信[微信支付-零钱提现到账]",
            merchant = "零钱提现已到账",
            assetName = "微信",
            toAssetName = "招商银行",
            billType = LocalBillType.TYPE_TRANSFER,
        )
        val bankIncome = createCapture(
            id = "income",
            amountCents = 1000L,
            paidAt = 1793887367023L,
            source = "cmb.pb",
            channel = "招商银行[收款]",
            merchant = "招商银行",
            assetName = "招商银行",
            billType = LocalBillType.TYPE_INCOME,
        )
        assertTrue(DuplicateDetector.isTransferIncomeDuplicate(bankIncome, transfer))
    }

    @Test
    fun `transfer income beyond configured window is NOT duplicate`() {
        val transfer = createCapture(
            id = "transfer",
            amountCents = 1000L,
            paidAt = 100000L,
            assetName = "微信",
            toAssetName = "招商银行",
            billType = LocalBillType.TYPE_TRANSFER,
        )
        val bankIncome = createCapture(
            id = "income",
            amountCents = 1000L,
            paidAt = 100000L + 301_000L,
            assetName = "招商银行",
            billType = LocalBillType.TYPE_INCOME,
        )
        assertFalse(DuplicateDetector.isTransferIncomeDuplicate(bankIncome, transfer))
    }

    @Test
    fun `income to a different asset than transfer target is NOT duplicate`() {
        val transfer = createCapture(
            id = "transfer",
            amountCents = 1000L,
            assetName = "微信",
            toAssetName = "招商银行",
            billType = LocalBillType.TYPE_TRANSFER,
        )
        val bankIncome = createCapture(
            id = "income",
            amountCents = 1000L,
            paidAt = 100500L,
            assetName = "建设银行",
            billType = LocalBillType.TYPE_INCOME,
        )
        assertFalse(DuplicateDetector.isTransferIncomeDuplicate(bankIncome, transfer))
    }

    @Test
    fun `income without matching amount is NOT duplicate`() {
        val transfer = createCapture(
            id = "transfer",
            amountCents = 1000L,
            assetName = "微信",
            toAssetName = "招商银行",
            billType = LocalBillType.TYPE_TRANSFER,
        )
        val bankIncome = createCapture(
            id = "income",
            amountCents = 1200L,
            paidAt = 100500L,
            assetName = "招商银行",
            billType = LocalBillType.TYPE_INCOME,
        )
        assertFalse(DuplicateDetector.isTransferIncomeDuplicate(bankIncome, transfer))
    }

    @Test
    fun `income to same bank with parenthesized card suffix still matches`() {
        val transfer = createCapture(
            id = "transfer",
            amountCents = 1000L,
            assetName = "微信",
            toAssetName = "招商银行(4852)",
            billType = LocalBillType.TYPE_TRANSFER,
        )
        val bankIncome = createCapture(
            id = "income",
            amountCents = 1000L,
            paidAt = 100500L,
            assetName = "招商银行",
            billType = LocalBillType.TYPE_INCOME,
        )
        assertTrue(DuplicateDetector.isTransferIncomeDuplicate(bankIncome, transfer))
    }

    @Test
    fun `income within window but transfer has no target asset is NOT duplicate`() {
        // 转账给对方个人时没有目标资产；不能因为金额相同就吞掉真实收款。
        val transfer = createCapture(
            id = "transfer",
            amountCents = 1000L,
            assetName = "微信",
            toAssetName = null,
            merchant = "转账-张三",
            billType = LocalBillType.TYPE_TRANSFER,
        )
        val income = createCapture(
            id = "income",
            amountCents = 1000L,
            paidAt = 100500L,
            assetName = "微信",
            billType = LocalBillType.TYPE_INCOME,
        )
        assertFalse(DuplicateDetector.isTransferIncomeDuplicate(income, transfer))
    }

    @Test
    fun `different mapped cards with same bank prefix are NOT duplicate`() {
        // 用户把两张卡分别映射成“招商银行1”“招商银行2”，不能因为共享前缀互判重复。
        val transferToCard1 = createCapture(
            id = "transfer",
            amountCents = 1000L,
            assetName = "微信",
            toAssetName = "招商银行1",
            billType = LocalBillType.TYPE_TRANSFER,
        )
        val card2Income = createCapture(
            id = "income",
            amountCents = 1000L,
            paidAt = 100500L,
            assetName = "招商银行2",
            billType = LocalBillType.TYPE_INCOME,
        )
        assertFalse(DuplicateDetector.isTransferIncomeDuplicate(card2Income, transferToCard1))
    }

    @Test
    fun `merge produces richer merchant, asset, channels and notes`() {
        val c1 = createCapture(
            id = "1",
            merchant = "待店员确认收款",
            assetName = "招行",
            channel = "微信支付",
            note = "咖啡",
            excludeFromStats = false,
        )
        val c2 = createCapture(
            id = "2",
            merchant = "星巴克臻选上海烘焙工坊",
            assetName = "招商银行信用卡(0888)",
            channel = "招商银行动账",
            note = "加浓缩",
            excludeFromStats = true,
        )

        val merged = DuplicateDetector.merge(c1, c2)
        assertEquals("星巴克臻选上海烘焙工坊", merged.merchant)
        assertEquals("招商银行信用卡(0888)", merged.assetName)
        assertEquals("微信支付 + 招商银行动账", merged.channel)
        assertEquals("咖啡 加浓缩", merged.note)
        assertTrue(merged.excludeFromStats)
    }

    @Test
    fun `wechat payment and bank official account with different txn ids are recognized as duplicate and merged`() {
        val wechatPay = createCapture(
            id = "wx_1",
            amountCents = 1500L,
            paidAt = 1700000000000L,
            source = "WECHAT",
            sourceTxnId = "100004990120261106001",
            channel = "微信支付",
            merchant = "瑞幸咖啡",
            assetName = null,
            note = "生椰拿铁",
            billType = LocalBillType.TYPE_EXPENSE,
        )
        val bankOfficialAccount = createCapture(
            id = "bank_1",
            amountCents = 1500L,
            paidAt = 1700000005000L, // 5s difference
            source = "WECHAT",
            sourceTxnId = "CMB_MSG_REF_998877",
            channel = "招商银行公众号",
            merchant = "招商银行信用卡",
            assetName = "招商银行信用卡(4852)",
            note = "消费人民币15.00元",
            billType = LocalBillType.TYPE_EXPENSE,
        )

        // 验证双向判定均为重复
        assertTrue(DuplicateDetector.isDuplicate(bankOfficialAccount, wechatPay))
        assertTrue(DuplicateDetector.isDuplicate(wechatPay, bankOfficialAccount))

        // 验证合并结果：真实商户优先保留、具体卡片资产被补齐、渠道与备注正确合并
        val merged = DuplicateDetector.merge(wechatPay, bankOfficialAccount)
        assertEquals("瑞幸咖啡", merged.merchant)
        assertEquals("招商银行信用卡(4852)", merged.assetName)
        assertEquals("微信支付 + 招商银行公众号", merged.channel)
        assertEquals("生椰拿铁 消费人民币15.00元", merged.note)
    }

    @Test
    fun `wechat payment and bank official account beyond 300s window are NOT duplicate`() {
        val wechatPay = createCapture(
            id = "wx_1",
            amountCents = 1500L,
            paidAt = 100000L,
            source = "WECHAT",
            sourceTxnId = "wx_txn_1",
            channel = "微信支付",
            merchant = "瑞幸咖啡",
        )
        val bankOfficialAccount = createCapture(
            id = "bank_1",
            amountCents = 1500L,
            paidAt = 100000L + DuplicateDetector.DEFAULT_WINDOW_MILLIS + 1000L,
            source = "WECHAT",
            sourceTxnId = "bank_txn_2",
            channel = "招商银行公众号",
            merchant = "招商银行信用卡",
        )
        assertFalse(DuplicateDetector.isDuplicate(bankOfficialAccount, wechatPay))
    }

    @Test
    fun `two separate bank official account notifications with different txn ids are NOT duplicate`() {
        val bankNotify1 = createCapture(
            id = "b1",
            amountCents = 1500L,
            paidAt = 100000L,
            source = "WECHAT",
            sourceTxnId = "cmb_txn_1",
            channel = "招商银行公众号",
            merchant = "招商银行信用卡",
        )
        val bankNotify2 = createCapture(
            id = "b2",
            amountCents = 1500L,
            paidAt = 105000L,
            source = "WECHAT",
            sourceTxnId = "cmb_txn_2",
            channel = "招商银行公众号",
            merchant = "招商银行信用卡",
        )
        assertFalse(DuplicateDetector.isDuplicate(bankNotify2, bankNotify1))
    }
}
