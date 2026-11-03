package com.taostudio.tapaccounting.capture.contract

/**
 * 把 AutoAccounting 的三种类型（Expend / Income / Transfer）+ 规则名，
 * 映射成敲敲记账本地的类型/子类型。
 *
 * AutoAccounting 没有“还款”“退款”这两个语义，只能从规则名判断：
 * 例如“支付宝花呗主动还款”“微信支付商家退款”。
 *
 * 判断顺序（先命中先返回）：
 * 1. 规则名含“还款”：Expend/Transfer -> 转账 + 还款子类型（2/1）；
 *    Income -> 普通收入（1/0，组合 B 口径：别人还款给我按收入记）。
 * 2. 规则名含“退款”：Income -> 收入 + 退款子类型（1/2），`needsRefundLink=true`；
 *    Expend/Transfer -> null（与出账方向矛盾，不猜）。
 * 3. Transfer -> 转账（2/0）；即使规则名含“转账/收款”也不再改判
 *    （例如“支付宝转账收款到余额宝”是账户间调拨）。
 * 4. Expend + 规则名含“收款/收入/收益/打款” -> 收入（1/0），见下面的“群收款”例外。
 * 5. Expend -> 支出（0/0）；Income -> 收入（1/0）。
 * 6. 其他/空类型 -> null。
 *
 * 已知例外：AutoAccounting 的“微信群收款”规则只在“你支付了……”时命中并输出
 * Expend；规则名虽然含“收款”，本质是出账，不能按收入关键词改判。
 */
object BillTypeLocalizer {

    const val AUTO_TYPE_EXPEND: String = "Expend"
    const val AUTO_TYPE_INCOME: String = "Income"
    const val AUTO_TYPE_TRANSFER: String = "Transfer"

    private val REPAYMENT_KEYWORDS = listOf("还款")
    private val REFUND_KEYWORDS = listOf("退款")
    private val INCOME_KEYWORDS = listOf("收款", "收入", "收益", "打款")

    /** “群收款”是出账，不能因为含“收款”就改判收入。 */
    private val EXPEND_KEYWORD_EXCEPTIONS = listOf("群收款")

    /**
     * @param autoType AutoAccounting 的 `BillType`：Expend / Income / Transfer
     * @param ruleName AutoAccounting 规则显示名；用于识别还款/退款/收入关键词
     * @return 本地类型；无法判断时返回 null，绝不根据金额或商户猜
     */
    fun localize(autoType: String, ruleName: String?): LocalBillTypeMapping? {
        val type = autoType.trim()
        val name = ruleName.orEmpty().trim()
        val isRepayment = name.containsAny(REPAYMENT_KEYWORDS)
        val isRefund = name.containsAny(REFUND_KEYWORDS)
        val isIncomeHint = name.containsAny(INCOME_KEYWORDS)

        return when (type) {
            AUTO_TYPE_EXPEND -> when {
                isRepayment -> repayment()
                isRefund -> null
                isIncomeHint && !name.containsAny(EXPEND_KEYWORD_EXCEPTIONS) -> income()
                else -> expense()
            }

            AUTO_TYPE_TRANSFER -> when {
                isRepayment -> repayment()
                isRefund -> null
                else -> transfer()
            }

            AUTO_TYPE_INCOME -> when {
                isRefund -> refund()
                // 组合 B：Income + 还款 也按普通收入（1/0），不再返回 null。
                else -> income()
            }

            else -> null
        }
    }

    private fun expense(): LocalBillTypeMapping =
        LocalBillTypeMapping(type = LocalBillType.TYPE_EXPENSE)

    private fun income(): LocalBillTypeMapping =
        LocalBillTypeMapping(type = LocalBillType.TYPE_INCOME)

    private fun transfer(): LocalBillTypeMapping =
        LocalBillTypeMapping(type = LocalBillType.TYPE_TRANSFER)

    private fun repayment(): LocalBillTypeMapping = LocalBillTypeMapping(
        type = LocalBillType.TYPE_TRANSFER,
        subType = LocalBillType.SUBTYPE_REPAYMENT,
    )

    private fun refund(): LocalBillTypeMapping = LocalBillTypeMapping(
        type = LocalBillType.TYPE_INCOME,
        subType = LocalBillType.SUBTYPE_REFUND,
        needsRefundLink = true,
    )

    private fun String.containsAny(keywords: List<String>): Boolean =
        keywords.any { contains(it) }
}
