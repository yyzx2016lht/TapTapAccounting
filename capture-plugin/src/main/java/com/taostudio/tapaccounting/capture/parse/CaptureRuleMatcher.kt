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
     * 只补通用资产映射；分类规则默认为空，不会给新安装种商户规则。
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
                        source = CategoryRule.SOURCE_SEED,
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

        // 3. 启发式兜底（仅通用支付通道，绝不预置个人银行卡及尾号）
        return when {
            raw.contains("零钱") -> "微信"
            raw.contains("花呗") -> "花呗"
            raw.contains("余额") -> "支付宝"
            else -> null
        }
    }

    /**
     * 只按“用户备注”匹配分类规则。
     *
     * 只执行 [CategoryRule.MATCH_NOTE] / [CategoryRule.MATCH_ANY]；
     * 商户规则（[CategoryRule.MATCH_MERCHANT]）数据保留，但不再参与自动归类。
     */
    suspend fun resolveNoteCategory(context: Context, note: String?): String? {
        val n = note?.trim().orEmpty()
        if (n.isEmpty()) return null
        ensureSeeded(context)
        val rules = CaptureApp.from(context).database.categoryRuleDao().listEnabled()
        return matchNoteCategory(rules, n)
    }

    /** 纯规则匹配，便于单测：只认备注，商户规则直接跳过。 */
    internal fun matchNoteCategory(rules: List<CategoryRule>, note: String?): String? {
        val n = note?.trim().orEmpty()
        if (n.isEmpty()) return null
        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.matchField != CategoryRule.MATCH_NOTE &&
                rule.matchField != CategoryRule.MATCH_ANY
            ) {
                continue
            }
            if (matchesText(rule, n)) return rule.targetCategoryName
        }
        return null
    }

    /**
     * 根据 备注（最高优）、商户、商品名 匹配本地分类规则。
     *
     * 只供只读展示等历史场景使用；hook 入库与备注重分类路径必须用
     * [resolveNoteCategory]，不得再用商户规则自动命中。
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

        // 如果用户手写了备注，先跑一次备注命中（避免商户是笼统的“微信支付”覆盖掉手写备注“咖啡”）
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

    /**
     * 开箱默认资产映射（仅保留通用系统级通道，绝不硬编码任何个人银行卡及卡号尾号）。
     * 更多特定银行卡映射或自定义规则，可通过界面【从自动记账导入】一键同步。
     */
    private val DEFAULT_ASSET_MAPPINGS: List<Pair<String, String>> = listOf(
        "零钱" to "微信",
        "微信零钱" to "微信",
        "微信支付" to "微信",
        "支付宝（花呗）" to "花呗",
        "花呗分期" to "花呗",
        "花呗" to "花呗",
        "账户余额(个人余额)" to "支付宝",
        "支付宝余额" to "支付宝",
        "账户余额" to "支付宝",
    )

    /**
     * 开箱默认分类规则：保持纯净空列表。
     * 因为每位用户的敲敲记账账本分类体系（如父子分类、命名习惯）截然不同，
     * 绝不预置带有特定个人生活轨迹或特定商家/分类层级的规则。
     * 新安装不种任何商户规则；已安装用户的既有规则（含商户规则）原样保留，
     * 老用户可随时通过【从自动记账导入】一键迁移自己原有的规则库。
     */
    private val DEFAULT_CATEGORY_RULES: List<Triple<String, String, String>> = emptyList()
}
