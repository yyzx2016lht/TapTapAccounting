package com.taostudio.tapaccounting.capture.ingest

import com.taostudio.tapaccounting.capture.contract.CapturePayload
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.PaymentCapture

/**
 * 退款消息本身没有资产时，从原转账推断“原路退回”。
 *
 * 微信转账退款的规则分支会把支付方式默认成“零钱”，但原转账可能是银行卡。
 * 这里只在退款报文没有明确资产信息时，用原转账的付款方式覆盖；
 * 有“退款方式/退回/缓存支付工具”的退款通知，以通知本身为准。
 */
internal object RefundAssetInheritance {

    fun inherit(refund: PaymentCapture, original: PaymentCapture?): PaymentCapture {
        if (original == null) return refund
        if (!isRefund(refund)) return refund

        val originalAsset = original.assetName?.takeIf { it.isNotBlank() }
        if (hasExplicitAsset(refund)) {
            // 本地映射没命中时，若退款方式里包含原资产名（如“农业银行 (3077)”含“农业银行”），
            // 用原资产兜底；不把银行卡退款强行映射成零钱。
            if (refund.assetName.isNullOrBlank() &&
                originalAsset != null &&
                refund.paymentMethodRaw.orEmpty().contains(originalAsset)
            ) {
                return refund.copy(assetName = originalAsset)
            }
            return refund
        }

        val inheritedMethod = original.paymentMethodRaw?.takeIf { it.isNotBlank() }
        if (originalAsset == null && inheritedMethod == null) return refund

        return refund.copy(
            assetName = originalAsset ?: refund.assetName,
            paymentMethodRaw = inheritedMethod ?: refund.paymentMethodRaw,
        )
    }

    fun inherit(refund: CapturePayload, original: PaymentCapture?): CapturePayload {
        if (original == null) return refund
        if (!isRefund(refund.subType, refund.needsRefundLink)) return refund

        val originalAsset = original.assetName?.takeIf { it.isNotBlank() }
        if (hasExplicitAsset(refund.rawJson)) {
            if (refund.assetName.isNullOrBlank() &&
                originalAsset != null &&
                refund.paymentMethodRaw.orEmpty().contains(originalAsset)
            ) {
                return refund.copy(assetName = originalAsset)
            }
            return refund
        }

        val inheritedMethod = original.paymentMethodRaw?.takeIf { it.isNotBlank() }
        if (originalAsset == null && inheritedMethod == null) return refund

        return refund.copy(
            assetName = originalAsset ?: refund.assetName,
            paymentMethodRaw = inheritedMethod ?: refund.paymentMethodRaw,
        )
    }

    fun hasExplicitAsset(refund: PaymentCapture): Boolean =
        hasExplicitAsset(refund.rawJson)

    private fun hasExplicitAsset(rawJson: String?): Boolean {
        val raw = rawJson.orEmpty()
        return raw.contains("cachedPayTools") ||
            raw.contains("退款方式") ||
            raw.contains("退回")
    }

    private fun isRefund(capture: PaymentCapture): Boolean =
        isRefund(capture.subType, capture.needsRefundLink)

    private fun isRefund(subType: Int?, needsRefundLink: Boolean): Boolean =
        subType == LocalBillType.SUBTYPE_REFUND || needsRefundLink
}
