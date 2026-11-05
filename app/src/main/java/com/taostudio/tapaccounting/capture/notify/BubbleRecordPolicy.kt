package com.taostudio.tapaccounting.capture.notify

import com.taostudio.tapaccounting.capture.contract.CaptureStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.PaymentCapture

/**
 * 气泡“记这笔”的准入规则（纯逻辑，便于单测）。
 *
 * 支出/收入：金额、资产、分类都要齐，缺一样就转悬浮窗细改。
 * 转账/还款：不需要分类，也不走智能归类；只要金额和转入/转出账户齐就能直接入账。
 * 退款：仍要求先关联原账单，气泡里不直接写。
 */
internal object BubbleRecordPolicy {

    enum class Block(val message: String) {
        BRIDGE_UNAVAILABLE("敲敲记账桥接不可用"),
        AMOUNT_MISSING("金额缺失，请在悬浮窗补金额"),
        UNSAFE_BILL_TYPE("退款需先在悬浮窗关联原账单"),
        ASSET_UNMAPPED("账户未映射，请在悬浮窗选择资产"),
        MISSING_TO_ASSET("缺少转入账户，请在悬浮窗选择"),
        POSSIBLE_DUPLICATE("可能已记，请先确认是否重复"),
        MISSING_CATEGORY("还没有分类，请手动选择"),
    }

    /** 转账/还款：账户间调拨，不需要分类，也不该走智能归类。 */
    fun isTransfer(capture: PaymentCapture): Boolean =
        capture.billType == LocalBillType.TYPE_TRANSFER ||
            capture.billType == LocalBillType.TYPE_REPAYMENT ||
            capture.subType == LocalBillType.SUBTYPE_REPAYMENT

    fun isRefund(capture: PaymentCapture): Boolean =
        capture.subType == LocalBillType.SUBTYPE_REFUND || capture.needsRefundLink

    /** 归类前就能判断的硬性拦截；命中后不必再花时间调 AI。 */
    fun preClassificationBlock(
        capture: PaymentCapture,
        bridgeAvailable: Boolean,
    ): Block? {
        if (!bridgeAvailable) return Block.BRIDGE_UNAVAILABLE
        if ((capture.amountCents ?: 0L) <= 0L) return Block.AMOUNT_MISSING
        if (isRefund(capture)) return Block.UNSAFE_BILL_TYPE
        if (isTransfer(capture)) {
            if (capture.assetName.isNullOrBlank()) return Block.ASSET_UNMAPPED
            if (capture.toAssetName.isNullOrBlank()) return Block.MISSING_TO_ASSET
            if (capture.status == CaptureStatus.POSSIBLE_DUPLICATE) return Block.POSSIBLE_DUPLICATE
            return null
        }
        if (capture.assetName.isNullOrBlank()) return Block.ASSET_UNMAPPED
        if (capture.status == CaptureStatus.POSSIBLE_DUPLICATE) return Block.POSSIBLE_DUPLICATE
        return null
    }

    /** 真正入账前的完整准入；转账不需要分类，普通支出/收入仍要求已有分类。 */
    fun importBlock(
        capture: PaymentCapture,
        bridgeAvailable: Boolean,
    ): Block? = preClassificationBlock(capture, bridgeAvailable)
        ?: if (isTransfer(capture)) {
            null
        } else if (capture.categoryName.isNullOrBlank()) {
            Block.MISSING_CATEGORY
        } else {
            null
        }
}
