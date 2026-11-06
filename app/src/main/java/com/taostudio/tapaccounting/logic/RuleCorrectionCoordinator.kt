package com.taostudio.tapaccounting.logic

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.taostudio.tapaccounting.RuleKeywordPolicy
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.AccountingRule
import com.taostudio.tapaccounting.data.local.entity.AiRule
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.BillRuleProvenance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账单来源与分类规则修正协调器 (T07)。
 *
 * 核心职责：
 * 1. 账单分类来源展示徽标文案映射（MANUAL / RULE / AI / SHORTCUT）；
 * 2. 用户在账单详情或编辑页修改分类后的规则智能冲突裁决：
 *    - 命中 USER 手动规则 -> 提示覆盖确认；
 *    - 命中 AI 规则或尚无规则 -> 提示新建/固化；
 * 3. 弹窗交互闭环与统一持久化至 accounting_rules (source = USER)。
 */
object RuleCorrectionCoordinator {

    const val SOURCE_MANUAL = "MANUAL"
    const val SOURCE_RULE = "RULE"
    const val SOURCE_AI = "AI"
    const val SOURCE_SHORTCUT = "SHORTCUT"

    enum class CorrectionAction {
        OVERWRITE_USER_RULE,
        CREATE_OR_SOLIDIFY_RULE,
        NO_OP
    }

    data class DecisionResult(
        val action: CorrectionAction,
        val keyword: String,
        val targetCategory: String,
        val promptMessage: String,
        val existingRule: AccountingRule? = null
    )

    data class EditContext(
        val oldBill: Bill,
        val newBill: Bill,
        val provenance: BillRuleProvenance?,
        val isOverlay: Boolean,
    )

    /**
     * 将账单来源与规则名映射为详情页展示的徽标文案。
     */
    fun resolveProvenanceBadgeText(provenance: BillRuleProvenance?, ruleName: String? = null): String? {
        if (provenance == null) return null
        val effectiveRuleName = ruleName?.takeIf { it.isNotBlank() } ?: provenance.keyword?.takeIf { it.isNotBlank() }
        return resolveProvenanceBadgeText(provenance.source, effectiveRuleName)
    }

    /**
     * 根据来源标识和规则名解析徽标文本。
     */
    fun resolveProvenanceBadgeText(source: String?, ruleName: String? = null): String? {
        if (source.isNullOrBlank()) return null
        return when (source.uppercase()) {
            SOURCE_MANUAL -> "手动分类"
            SOURCE_RULE -> {
                if (!ruleName.isNullOrBlank()) "规则命中：$ruleName" else "规则命中"
            }
            SOURCE_AI -> "AI 建议"
            SOURCE_SHORTCUT -> "快捷入账"
            else -> null
        }
    }

    /**
     * 纯函数：裁决分类变更后是触发“覆盖已有规则”还是“新建/固化规则”。
     */
    fun decideCorrection(
        oldCategory: String?,
        newCategory: String?,
        candidateKeyword: String?,
        provenance: BillRuleProvenance? = null,
        matchingRules: List<AccountingRule> = emptyList()
    ): DecisionResult {
        val cleanKeyword = candidateKeyword?.trim().orEmpty()
        val cleanNewCat = newCategory?.trim().orEmpty()
        val cleanOldCat = oldCategory?.trim().orEmpty()

        if (cleanKeyword.isEmpty() || cleanNewCat.isEmpty() || cleanOldCat == cleanNewCat) {
            return DecisionResult(
                action = CorrectionAction.NO_OP,
                keyword = cleanKeyword,
                targetCategory = cleanNewCat,
                promptMessage = ""
            )
        }

        // 查找匹配规则：优先按 provenance.ruleId 匹配，其次按关键词精确查找，若存在 USER 规则则优先认定为 USER 覆盖
        val ruleById = provenance?.ruleId?.let { rid -> matchingRules.firstOrNull { it.id == rid } }
        val userRule = matchingRules.firstOrNull {
            it.source == AccountingRule.Source.USER &&
                (it.keyword.equals(cleanKeyword, ignoreCase = true) || it.id == provenance?.ruleId)
        }
        val targetRule = userRule ?: ruleById ?: matchingRules.firstOrNull {
            it.keyword.equals(cleanKeyword, ignoreCase = true)
        }

        return if (targetRule?.source == AccountingRule.Source.USER) {
            DecisionResult(
                action = CorrectionAction.OVERWRITE_USER_RULE,
                keyword = cleanKeyword,
                targetCategory = cleanNewCat,
                promptMessage = "您之前已为「$cleanKeyword」设置过分类规则，是否更新为「$cleanNewCat」？",
                existingRule = targetRule
            )
        } else {
            DecisionResult(
                action = CorrectionAction.CREATE_OR_SOLIDIFY_RULE,
                keyword = cleanKeyword,
                targetCategory = cleanNewCat,
                promptMessage = "已将「$cleanKeyword」分类修改为「$cleanNewCat」，是否将此规则记住，下次自动生效？",
                existingRule = targetRule
            )
        }
    }

    /**
     * 用户修改分类后统一协调入口。
     */
    fun onUserCorrectCategory(
        ctx: Context,
        billId: Long,
        oldCategory: String?,
        newCategory: String?,
        note: String?,
        merchant: String? = null,
        onFinished: () -> Unit = {}
    ) {
        val cleanOld = oldCategory?.trim().orEmpty()
        val cleanNew = newCategory?.trim().orEmpty()
        if (cleanNew.isEmpty() || cleanOld == cleanNew) {
            onFinished()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(ctx)
            val provenance = if (billId > 0) db.billRuleProvenanceDao().getByBillId(billId) else null

            // 候选关键词提取策略：商户名 > 溯源记录已有关键词 > 从备注提取短词 > 备注整词
            val candidateKeyword = merchant?.trim()?.takeIf { it.isNotEmpty() }
                ?: provenance?.keyword?.trim()?.takeIf { it.isNotEmpty() }
                ?: note?.let { RuleKeywordPolicy.directKeywordFromNote(it) }?.takeIf { it.isNotEmpty() }
                ?: note?.trim()?.takeIf { it.isNotEmpty() }

            if (candidateKeyword.isNullOrBlank()) {
                withContext(Dispatchers.Main) { onFinished() }
                return@launch
            }

            val matchingRules = mutableListOf<AccountingRule>()
            provenance?.ruleId?.let { rid ->
                db.accountingRuleDao().listAll().find { it.id == rid }?.let { matchingRules.add(it) }
            }
            val byKeyword = db.accountingRuleDao().findAllByKeyword(candidateKeyword)
            byKeyword.forEach { rule ->
                if (matchingRules.none { it.id == rule.id }) {
                    matchingRules.add(rule)
                }
            }

            val decision = decideCorrection(
                oldCategory = cleanOld,
                newCategory = cleanNew,
                candidateKeyword = candidateKeyword,
                provenance = provenance,
                matchingRules = matchingRules
            )

            if (decision.action == CorrectionAction.NO_OP) {
                withContext(Dispatchers.Main) { onFinished() }
                return@launch
            }

            withContext(Dispatchers.Main) {
                if (ctx is Activity && (ctx.isFinishing || ctx.isDestroyed)) {
                    onFinished()
                    return@withContext
                }

                RuleDialogHelper.showCorrectionConfirmDialog(
                    ctx = ctx,
                    message = decision.promptMessage,
                    keyword = decision.keyword,
                    targetCategory = decision.targetCategory,
                    existingRule = decision.existingRule,
                    isOverlay = ctx !is Activity,
                    onConfirmSave = {
                        CoroutineScope(Dispatchers.IO).launch {
                            val now = System.currentTimeMillis()
                            val ruleToSave = if (decision.existingRule != null) {
                                decision.existingRule.copy(
                                    keyword = decision.keyword,
                                    targetCategory = decision.targetCategory,
                                    source = AccountingRule.Source.USER,
                                    enabled = true,
                                    updatedAt = now
                                )
                            } else {
                                AccountingRule(
                                    id = 0L,
                                    keyword = decision.keyword,
                                    targetCategory = decision.targetCategory,
                                    matchField = AccountingRule.MatchField.REMARK,
                                    matchMode = AccountingRule.MatchMode.SUBSTRING,
                                    source = AccountingRule.Source.USER,
                                    enabled = true,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            }
                            db.accountingRuleDao().upsert(ruleToSave)

                            // 同步兼容旧版 ai_rule 表
                            runCatching {
                                val legacyId = db.aiRuleDao().getRulesByKeyword(decision.keyword).firstOrNull()?.id ?: 0
                                db.aiRuleDao().insertRule(
                                    AiRule(
                                        id = legacyId,
                                        keyword = decision.keyword,
                                        targetType = ruleToSave.targetType,
                                        targetCategory = decision.targetCategory,
                                        targetAccount1 = ruleToSave.targetAccount1,
                                        targetAccount2 = ruleToSave.targetAccount2,
                                        isEnabled = true
                                    )
                                )
                            }

                            withContext(Dispatchers.Main) {
                                val toastMsg = if (decision.action == CorrectionAction.OVERWRITE_USER_RULE) {
                                    "规则已更新"
                                } else {
                                    "规则已记住"
                                }
                                Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
                                onFinished()
                            }
                        }
                    },
                    onCustomize = {
                        RuleDialogHelper.showRuleDialog(
                            ctx = ctx,
                            rule = decision.existingRule,
                            referenceText = note ?: merchant,
                            defaultKeyword = decision.keyword,
                            defaultCat = decision.targetCategory,
                            isOverlay = ctx !is Activity,
                            categoryOnlyLearnMode = true,
                            onSave = { customizedRule ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    val now = System.currentTimeMillis()
                                    val ruleToSave = customizedRule.copy(
                                        source = AccountingRule.Source.USER,
                                        enabled = true,
                                        updatedAt = now
                                    )
                                    db.accountingRuleDao().upsert(ruleToSave)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "规则已保存", Toast.LENGTH_SHORT).show()
                                        onFinished()
                                    }
                                }
                            },
                            onCancel = onFinished
                        )
                    },
                    onCancel = onFinished
                )
            }
        }
    }

    fun maybePrompt(
        ctx: Context,
        context: EditContext,
        onFinished: () -> Unit = {},
    ) {
        if (context.oldBill.categoryName != context.newBill.categoryName) {
            onUserCorrectCategory(
                ctx = ctx,
                billId = context.newBill.id,
                oldCategory = context.oldBill.categoryName,
                newCategory = context.newBill.categoryName,
                note = context.newBill.remark,
                merchant = null,
                onFinished = onFinished
            )
        } else {
            onFinished()
        }
    }
}
