package com.taostudio.tapaccounting.capture.hook

/**
 * 数据关键词过滤（白名单），等价于 AutoAccounting 的 DefaultData.DATA_FILTER 与 AnalysisUtils。
 *
 * 在 Hook 捕获的最源头执行过滤：
 * 绝大多数普通聊天消息、群消息、朋友圈互动、公众号新闻推文、内部状态同步等，
 * 根本不包含任何动账记账关键词。直接在源头拦截丢弃，绝不上报、不跨进程唤醒、不写入数据库。
 */
internal object DataFilter {

    /**
     * 与 AutoAccounting DefaultData.DATA_FILTER 完全一致的关键词白名单。
     * 内容包含其中任意一个关键词才被视为“疑似与资金动账/账单相关”，允许进入后续规则匹配。
     */
    val KEYWORDS: List<String> = listOf(
        "银行", "信用卡", "借记卡", "公积金",
        "元", "￥", "¥", "人民币",
        "消费", "支付", "支出", "转出", "取出", "取款",
        "收入", "转入", "存入", "存款", "退款",
        "还款", "贷款", "借款", "逾期",
        "转账", "付款", "收款", "扣费", "扣款",
        "账户", "余额",
        "交易", "动账", "账单",
    )

    /**
     * 检查文本是否命中账单白名单关键词。
     */
    fun matches(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        for (keyword in KEYWORDS) {
            if (text.contains(keyword)) return true
        }
        return false
    }

    /**
     * 针对微信系统通知栏（com.tencent.mm）的专用校验：
     *
     * 微信普通好友聊天、群聊在后台时的系统通知标题为“好友名/群名”，内容为纯文本聊天内容。
     * AutoAccounting 的微信规则集里仅有两条 NOTICE 规则：
     * 1) 微信支付 (rule_269，expectedTitle="微信支付")
     * 2) 微信抢红包 (rule_268，"已存入零钱")
     *
     * 因此来自微信的通知：只允许官方“微信支付”、零钱入账或转账通知通过，100% 阻断普通聊天与群聊通知。
     */
    fun isWechatNoticeAllowed(title: String?, text: String?): Boolean {
        val t = title.orEmpty()
        val b = text.orEmpty()
        if (t == "微信支付" || t.contains("微信支付") || b.contains("微信支付")) return true
        if (b.contains("已存入零钱") || b.contains("微信支付凭证")) return true
        if (b.contains("微信转账") || (b.contains("转账") && b.contains("已收"))) return true
        return false
    }
}
