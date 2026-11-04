package com.taostudio.tapaccounting.capture.parse

import android.content.Context
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.data.AssetMappingRule
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.data.CategoryRule

/**
 * 本地资产映射与分类规则匹配引擎（毫秒级同步匹配 + 自动记账预置规则种子）。
 */
object CaptureRuleMatcher {

    /**
     * 首次启动或用户点击“恢复/导入预置映射与规则”时写入默认数据。
     * 包含从自动记账导出的真实资产映射与高频商家/菜市场分类规则。
     */
    suspend fun ensureSeeded(context: Context, force: Boolean = false) {
        if (!force && CapturePrefs.isDefaultsSeeded(context)) return
        val db = CaptureApp.from(context).database
        val assetDao = db.assetMappingDao()
        val categoryDao = db.categoryRuleDao()

        if (force || assetDao.count() == 0) {
            DEFAULT_ASSET_MAPPINGS.forEachIndexed { index, (pattern, target) ->
                assetDao.upsert(
                    AssetMappingRule(
                        pattern = pattern,
                        targetAssetName = target,
                        isRegex = false,
                        sort = index,
                        enabled = true,
                    )
                )
            }
        }

        if (force || categoryDao.count() == 0) {
            DEFAULT_CATEGORY_RULES.forEachIndexed { index, (keyword, field, category) ->
                categoryDao.upsert(
                    CategoryRule(
                        keyword = keyword,
                        matchField = field,
                        isRegex = false,
                        targetCategoryName = category,
                        sort = index,
                        enabled = true,
                    )
                )
            }
        }

        CapturePrefs.setDefaultsSeeded(context, true)
    }

    /**
     * 把原始支付方式（如 "零钱"、"招商银行储蓄卡(4852)"）映射成敲敲记账资产名。
     */
    suspend fun resolveAssetName(context: Context, rawPaymentMethod: String?): String? {
        val raw = rawPaymentMethod?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        ensureSeeded(context)
        val rules = CaptureApp.from(context).database.assetMappingDao().listEnabled()

        // 1. 精确匹配优先
        rules.firstOrNull { !it.isRegex && it.pattern.equals(raw, ignoreCase = true) }?.let {
            return it.targetAssetName
        }

        // 2. 包含 / 正则匹配
        for (rule in rules) {
            val matched = if (rule.isRegex) {
                runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE).containsMatchIn(raw) }
                    .getOrDefault(false)
            } else {
                raw.contains(rule.pattern, ignoreCase = true)
            }
            if (matched && rule.targetAssetName.isNotBlank()) {
                return rule.targetAssetName
            }
        }

        // 3. 启发式兜底
        return when {
            raw.contains("零钱") -> "微信"
            raw.contains("花呗") -> "花呗"
            raw.contains("余额") -> "支付宝"
            raw.contains("招商银行") || raw.contains("4852") -> "招商银行"
            raw.contains("建设银行") || raw.contains("3529") -> "建设银行(3529)"
            raw.contains("农业银行") || raw.contains("3077") -> "农业银行"
            raw.contains("中国银行") || raw.contains("8336") -> "中国银行"
            raw.contains("工商银行") || raw.contains("3066") -> "工商银行"
            raw.contains("浦发银行") || raw.contains("6729") -> "浦发银行"
            else -> null
        }
    }

    /**
     * 根据 备注（最高优）、商户、商品名 匹配本地分类规则。
     */
    suspend fun resolveCategoryName(
        context: Context,
        merchant: String?,
        shopItem: String?,
        note: String?,
        fallbackCategory: String? = null,
    ): String? {
        ensureSeeded(context)
        val rules = CaptureApp.from(context).database.categoryRuleDao().listEnabled()
        val m = merchant?.trim().orEmpty()
        val item = shopItem?.trim().orEmpty()
        val n = note?.trim().orEmpty()

        // 如果用户手写了备注，先跑一次备注命中（避免商户是笼统的“微信支付”覆盖掉手写备注“青菜”）
        if (n.isNotEmpty()) {
            for (rule in rules) {
                if (rule.matchField == CategoryRule.MATCH_MERCHANT) continue
                if (matchesText(rule, n)) return rule.targetCategoryName
            }
        }

        // 再跑全字段匹配
        for (rule in rules) {
            val targetText = when (rule.matchField) {
                CategoryRule.MATCH_MERCHANT -> m
                CategoryRule.MATCH_NOTE -> n
                else -> "$n $m $item"
            }
            if (targetText.isNotBlank() && matchesText(rule, targetText)) {
                return rule.targetCategoryName
            }
        }

        return fallbackCategory?.takeIf { it.isNotBlank() }
    }

    private fun matchesText(rule: CategoryRule, text: String): Boolean {
        if (rule.keyword.isBlank()) return false
        return if (rule.isRegex) {
            runCatching { Regex(rule.keyword, RegexOption.IGNORE_CASE).containsMatchIn(text) }
                .getOrDefault(false)
        } else {
            text.contains(rule.keyword, ignoreCase = true)
        }
    }

    /** 来自自动记账真实资产映射表（assets_map.json） */
    private val DEFAULT_ASSET_MAPPINGS: List<Pair<String, String>> = listOf(
        "零钱" to "微信",
        "微信零钱" to "微信",
        "微信支付" to "微信",
        "招商银行储蓄卡(4852)" to "招商银行",
        "招商银行(4852)" to "招商银行",
        "支付卡(招商银行4852)" to "招商银行",
        "招商银行储蓄卡" to "招商银行",
        "建设银行储蓄卡(3529)" to "建设银行(3529)",
        "建设银行储蓄账户(3529)" to "建设银行(3529)",
        "中国建设银行(3529)" to "建设银行(3529)",
        "银行卡(建设银行3529)" to "建设银行(3529)",
        "建设银行储蓄卡" to "建设银行(3529)",
        "农业银行储蓄卡(3077)" to "农业银行",
        "中国农业银行(3077)" to "农业银行",
        "农业银行（3077）" to "农业银行",
        "银行卡(3077)" to "农业银行",
        "农业银行储蓄卡" to "农业银行",
        "中国银行储蓄卡(8336)" to "中国银行",
        "中国银行(8336)" to "中国银行",
        "银行卡(中国银行8336)" to "中国银行",
        "中国银行储蓄卡" to "中国银行",
        "工商银行储蓄卡(3066)" to "工商银行",
        "工商银行储蓄卡" to "工商银行",
        "浦发银行储蓄卡(6729)" to "浦发银行",
        "浦发银行(6729)" to "浦发银行",
        "浦发银行储蓄卡" to "浦发银行",
        "支付宝（花呗）" to "花呗",
        "花呗分期(3期)" to "花呗",
        "花呗分期" to "花呗",
        "花呗" to "花呗",
        "账户余额(个人余额)" to "支付宝",
        "支付宝余额" to "支付宝",
        "账户余额" to "支付宝",
        "支付宝基金" to "基金",
        "蚂蚁财富账户" to "基金",
        "抖音月付" to "抖音月付",
    )

    /** 来自自动记账分类规则（category_rules.json）+ 菜市场/日常手写备注高频词 */
    private val DEFAULT_CATEGORY_RULES: List<Triple<String, String, String>> = listOf(
        // 手写备注高频词（菜市场/日常）
        Triple("青菜", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("买菜", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("白菜", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("生菜", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("菠菜", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("西红柿", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("番茄", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("土豆", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("黄瓜", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("辣椒", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("鸡蛋", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("豆腐", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("猪肉", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("五花肉", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("排骨", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("牛肉", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("鸡肉", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("瘦肉", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("蔬菜", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("菜摊", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("肉摊", CategoryRule.MATCH_ANY, "吃的 - 买菜"),
        Triple("水果", CategoryRule.MATCH_ANY, "吃的 - 水果"),
        Triple("苹果", CategoryRule.MATCH_ANY, "吃的 - 水果"),
        Triple("香蕉", CategoryRule.MATCH_ANY, "吃的 - 水果"),
        Triple("西瓜", CategoryRule.MATCH_ANY, "吃的 - 水果"),
        Triple("草莓", CategoryRule.MATCH_ANY, "吃的 - 水果"),
        Triple("早餐", CategoryRule.MATCH_ANY, "吃的 - 三餐"),
        Triple("午餐", CategoryRule.MATCH_ANY, "吃的 - 三餐"),
        Triple("晚餐", CategoryRule.MATCH_ANY, "吃的 - 三餐"),
        Triple("夜宵", CategoryRule.MATCH_ANY, "吃的 - 三餐"),
        Triple("外卖", CategoryRule.MATCH_ANY, "吃的 - 三餐"),
        Triple("瑞幸", CategoryRule.MATCH_ANY, "喝的 - 饮料"),
        Triple("咖啡", CategoryRule.MATCH_ANY, "喝的 - 饮料"),
        Triple("奶茶", CategoryRule.MATCH_ANY, "喝的 - 饮料"),
        Triple("矿泉水", CategoryRule.MATCH_ANY, "喝的 - 饮料"),
        // 用户自动记账中的高频商家规则
        Triple("淘宝闪购", CategoryRule.MATCH_MERCHANT, "吃的 - 三餐"),
        Triple("美宜佳", CategoryRule.MATCH_MERCHANT, "喝的 - 饮料"),
        Triple("华润万家", CategoryRule.MATCH_MERCHANT, "喝的 - 饮料"),
        Triple("吉鑫蔬菜档", CategoryRule.MATCH_MERCHANT, "吃的 - 买菜"),
        Triple("牛肉档口", CategoryRule.MATCH_MERCHANT, "吃的 - 买菜"),
        Triple("棠德市场", CategoryRule.MATCH_MERCHANT, "吃的 - 买菜"),
        Triple("易鲜购超市", CategoryRule.MATCH_MERCHANT, "吃的 - 买菜"),
        Triple("鑫乐荟", CategoryRule.MATCH_MERCHANT, "吃的 - 买菜"),
        Triple("惠客多百货", CategoryRule.MATCH_MERCHANT, "吃的 - 买菜"),
        Triple("福乐多连锁超市", CategoryRule.MATCH_MERCHANT, "吃的 - 水果"),
        Triple("阳光百货", CategoryRule.MATCH_MERCHANT, "吃的 - 水果"),
        Triple("群接龙", CategoryRule.MATCH_ANY, "吃的 - 水果"),
        Triple("嘉禾苏铂超市", CategoryRule.MATCH_MERCHANT, "吃的 - 零食"),
        Triple("美天惠", CategoryRule.MATCH_MERCHANT, "吃的 - 零食"),
        Triple("麦乐鸡", CategoryRule.MATCH_MERCHANT, "零食 - 饮料"),
        Triple("东北鲜肉饼", CategoryRule.MATCH_MERCHANT, "吃的 - 三餐"),
        Triple("土家鲜肉饼", CategoryRule.MATCH_MERCHANT, "吃的 - 三餐"),
        Triple("安徽板面", CategoryRule.MATCH_MERCHANT, "吃的 - 三餐"),
        Triple("邱记锅盔", CategoryRule.MATCH_MERCHANT, "吃的 - 三餐"),
        Triple("新风小吃店", CategoryRule.MATCH_MERCHANT, "吃的 - 三餐"),
        Triple("西关情饼店", CategoryRule.MATCH_MERCHANT, "吃的 - 三餐"),
        Triple("零居鲜生", CategoryRule.MATCH_MERCHANT, "吃的 - 三餐"),
        Triple("学生第一食堂", CategoryRule.MATCH_MERCHANT, "喝的 - 饮料"),
        Triple("广东技术师范大学", CategoryRule.MATCH_MERCHANT, "吃的 - 三餐"),
        Triple("抖音生活服务", CategoryRule.MATCH_MERCHANT, "吃的 - 三餐"),
        Triple("抖音电商", CategoryRule.MATCH_MERCHANT, "衣服"),
        Triple("UNIQLO", CategoryRule.MATCH_MERCHANT, "衣服 - 衣服"),
        Triple("无印良品", CategoryRule.MATCH_MERCHANT, "学习 - 书籍"),
        Triple("哈啰", CategoryRule.MATCH_MERCHANT, "交通 - 共享单车"),
        Triple("岭南通", CategoryRule.MATCH_MERCHANT, "交通 - 公交"),
        Triple("广东联合电子服务", CategoryRule.MATCH_MERCHANT, "交通 - 打车"),
        Triple("易加油", CategoryRule.MATCH_MERCHANT, "汽车 - 油费"),
        Triple("菜鸟", CategoryRule.MATCH_MERCHANT, "快递"),
        Triple("一城一家", CategoryRule.MATCH_MERCHANT, "住房 - 水电煤"),
        Triple("鲜花店", CategoryRule.MATCH_MERCHANT, "住房 - 花"),
        Triple("宠物医疗", CategoryRule.MATCH_MERCHANT, "养猫 - 看病"),
        Triple("中国联合网络通信", CategoryRule.MATCH_MERCHANT, "网络通讯"),
    )
}
