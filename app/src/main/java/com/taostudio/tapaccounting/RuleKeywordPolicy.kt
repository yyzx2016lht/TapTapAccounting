package com.taostudio.tapaccounting

/**
 * AI 规则关键词的本地闸门：模型可以返回任何字符串，写规则前统一在这里清洗/拒绝。
 *
 * 只接受 1-8 个字、能复用、不是泛词的关键词；空结果表示“不学习”。
 */
internal object RuleKeywordPolicy {

    /** 泛词：只描述一顿饭/一次动作，换一笔就不成立，写进规则会污染自动分类。 */
    val GENERIC_KEYWORDS: Set<String> = setOf(
        "午饭", "晚饭", "早饭", "午餐", "晚餐", "早餐",
        "吃饭", "用餐", "夜宵", "宵夜",
        "超市", "商店", "便利店", "小卖部", "商场", "商超",
        "购物", "买东西", "买点东西", "买菜", "买饭", "买水果",
        "买衣服", "买零食", "买日用品", "购物消费",
        "消费", "支出", "花费", "付款", "支付", "转账",
        "退款", "红包", "收款", "收入", "还款", "充值",
        "餐饮", "交通", "娱乐", "医疗", "居住", "通讯",
        "生活", "日常", "其他", "未知", "无", "一般", "杂项",
    )

    private val EDGE_PUNCTUATION = charArrayOf(
        '"', '“', '”', '\'', '`', '。', '，', ',', '.', '!', '！', '?', '？',
        ':', '：', ';', '；', '、', ' ', '\t', '\n', '\r',
    )

    /** 备注里出现这些动词/虚词时，整句通常不是可复用关键词，交给 AI 再提炼。 */
    private val NOTE_ACTION_CHARS = "买吃喝付给在去得了和与还刷转收发".toSet()

    /**
     * 清洗模型输出。返回空串表示不要写规则。
     *
     * - 去掉引号/句号/Markdown 反引号与“关键词：”前缀；
     * - 只取第一行，长度必须在 1-8；
     * - 命中泛词或“给某某”这类对象描述时返回空。
     */
    fun sanitize(raw: String?): String {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        text = text.lineSequence().firstOrNull()?.trim().orEmpty()
        text = text.removePrefix("关键词：").removePrefix("关键词:").trim()
        text = text.trim(*EDGE_PUNCTUATION)
        if (text.isEmpty()) return ""
        if (text.length !in 1..8) return ""
        if (text in GENERIC_KEYWORDS) return ""
        if (text.startsWith("给")) return ""
        if (text.length == 1 && text[0] in NOTE_ACTION_CHARS) return ""
        if (text.all { it.isDigit() }) return ""
        if (text.all { !it.isLetterOrDigit() }) return ""
        return text
    }

    /**
     * 备注本身就是一个短关键词时（如“苹果”“星巴克”），直接把它当关键词，省掉一次 AI 往返。
     *
     * 含动词/虚词、数字、空白或泛词的整句不走这条捷径，交给 [AIService.suggestRuleKeyword]
     * 真正提炼，避免把“买了苹果”这种整句写成规则。
     */
    fun directKeywordFromNote(note: String?): String? {
        val cleaned = note?.trim().orEmpty()
        if (cleaned.length !in 1..8) return null
        if (cleaned.any { it.isWhitespace() || it in NOTE_ACTION_CHARS || it.isDigit() }) return null
        if (GENERIC_KEYWORDS.any { cleaned.contains(it) }) return null
        return sanitize(cleaned).takeIf { it.isNotEmpty() }
    }
}
