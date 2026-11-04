package com.taostudio.tapaccounting.capture.notify

import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.PaymentCapture

/**
 * 气泡“记这笔”的准入规则（纯逻辑，便于单测）。
 *
 * 只要命中任意一条，就不在气泡里直接写账，转悬浮窗细改：
 * 金额缺失、转账/还款/退款、资产未映射、可能已记、桥接不可用、归类失败。
 */
internal object BubbleRecordPolicy {

    enum class Block(val message: String) {
        BRIDGE_UNAVAILABLE("敲敲记账桥接不可用"),
        AMOUNT_MISSING("金额缺失，请在悬浮窗补金额"),
        UNSAFE_BILL_TYPE("转账/还款/退款需在悬浮窗确认"),
        ASSET_UNMAPPED("账户未映射，请在悬浮窗选择资产"),
        POSSIBLE_DUPLICATE("可能已记，请先确认是否重复"),
        MISSING_CATEGORY("还没有分类，请手动选择"),
    }

    /** 归类前就能判断的硬性拦截；命中后不必再花时间调 AI。 */
    fun preClassificationBlock(
        capture: PaymentCapture,
        bridgeAvailable: Boolean,
    ): Block? {
        if (!bridgeAvailable) return Block.BRIDGE_UNAVAILABLE
        if ((capture.amountCents ?: 0L) <= 0L) return Block.AMOUNT_MISSING
        if (capture.billType == LocalBillType.TYPE_TRANSFER ||
            capture.billType == LocalBillType.TYPE_REPAYMENT ||
            capture.subType == LocalBillType.SUBTYPE_REPAYMENT ||
            capture.subType == LocalBillType.SUBTYPE_REFUND ||
            capture.needsRefundLink
        ) {
            return Block.UNSAFE_BILL_TYPE
        }
        if (capture.assetName.isNullOrBlank()) return Block.ASSET_UNMAPPED
        if (capture.status == CaptureStatus.POSSIBLE_DUPLICATE) return Block.POSSIBLE_DUPLICATE
        return null
    }

    /** 真正入账前的完整准入；在硬性拦截之外再要求已有分类。 */
    fun importBlock(
        capture: PaymentCapture,
        bridgeAvailable: Boolean,
    ): Block? = preClassificationBlock(capture, bridgeAvailable)
        ?: if (capture.categoryName.isNullOrBlank()) Block.MISSING_CATEGORY else null
}
