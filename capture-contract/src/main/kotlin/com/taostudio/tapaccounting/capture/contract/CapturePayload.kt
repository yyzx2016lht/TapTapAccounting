package com.taostudio.tapaccounting.capture.contract

/**
 * hook 捕获到的一笔支付。
 *
 * 金额统一用“分”（Long），时间统一用毫秒（Long）；App 在边界转成现有 Double / 字符串。
 * 缺失字段一律 null / 空，不编造。
 */
data class CapturePayload(
    /** captureId（UUID），幂等键。 */
    val captureId: String,
    /** WECHAT / ALIPAY。 */
    val source: String,
    /** hook 事件类型：DATA（DB/WebView 结构化数据）或 NOTICE（通知）。 */
    val rawType: String = "DATA",
    /** 订单号 / 交易号；有则用于去重。 */
    val sourceTxnId: String? = null,
    /** 实付金额（分）；解析失败为 null。 */
    val amountCents: Long? = null,
    val currency: String = "CNY",
    val merchant: String? = null,
    /** 原始支付方式，如“零钱”“招商银行储蓄卡(1234)”。 */
    val paymentMethodRaw: String? = null,
    /** 支付时间（毫秒）；缺失时用捕获时间。 */
    val paidAt: Long,
    /** 捕获时间（毫秒）。 */
    val capturedAt: Long = paidAt,
    /** 账本默认名。 */
    val bookName: String? = null,
    /** 备注草稿。 */
    val note: String? = null,
    /**
     * 本地账单主类型：0 支出 / 1 收入 / 2 转账；未识别为 null（待分类）。
     * 不要写旧值 3；还款用 2 + [subType]=1。
     */
    val billType: Int? = null,
    /** 本地子类型：0 普通 / 1 还款 / 2 退款；未识别为 null。 */
    val subType: Int? = null,
    /** 退款必须关联原支出账单；非退款/未识别为 false。 */
    val needsRefundLink: Boolean = false,
    /** 调试/规则版本信息。 */
    val rawJson: String? = null,
    val ruleVersion: String? = null,
    val sourceAppVersion: String? = null,
    val status: String = CaptureStatus.PENDING,
    /** 金额缺失标记；默认由 amountCents 推导。 */
    val needsAmount: Boolean = amountCents == null,
)
