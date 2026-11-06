package com.taostudio.tapaccounting.logic

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.taostudio.tapaccounting.RuleKeywordPolicy
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.AccountingRule
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.BillRuleProvenance
import com.taostudio.tapaccounting.data.local.entity.Category
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

                RuleDialogHelper.showCorrectionActionSheet(
                    ctx = ctx,
                    message = decision.promptMessage,
                    hasExistingRule = decision.existingRule != null,
                    isOverlay = ctx !is Activity,
                    onUpdate = {
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
                    onDeleteRule = {
                        CoroutineScope(Dispatchers.IO).launch {
                            decision.existingRule?.let { deleteOrDisableRule(db, it) }
                            provenance?.let {
                                db.billRuleProvenanceDao().upsert(
                                    it.copy(
                                        source = BillRuleProvenance.Source.MANUAL,
                                        ruleId = null,
                                        keyword = null,
                                    )
                                )
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "已删除/停用旧规则，这笔账单不变", Toast.LENGTH_SHORT).show()
                                onFinished()
                            }
                        }
                    },
                    onOnlyThisTime = onFinished,
                    onUndo = {
                        CoroutineScope(Dispatchers.IO).launch {
                            undoAutoCategory(db, billId)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "已撤销这次自动分类，落回「其他」", Toast.LENGTH_SHORT).show()
                                onFinished()
                            }
                        }
                    },
                    onCancel = onFinished
                )
            }
        }
    }

    /** 用户手动规则/AI 规则直接删；内置/导入规则只停用，不破坏规则包。 */
    private suspend fun deleteOrDisableRule(db: AppDatabase, rule: AccountingRule) {
        val now = System.currentTimeMillis()
        if (rule.source == AccountingRule.Source.USER || rule.source == AccountingRule.Source.AI) {
            db.accountingRuleDao().deleteById(rule.id)
        } else {
            db.accountingRuleDao().update(rule.copy(enabled = false, updatedAt = now))
        }
    }

    /** 撤销这次自动分类：账单分类落回「其他」，并清掉来源记录，避免再次提示。 */
    private suspend fun undoAutoCategory(db: AppDatabase, billId: Long) {
        val bill = db.billDao().getBillById(billId) ?: return
        val type = if (bill.type == Bill.TYPE_INCOME) 1 else 0
        val categories = db.categoryDao().getAllCategoriesList().toMutableList()
        var other = categories.firstOrNull { it.type == type && it.name == "其他" }
        if (other == null) {
            val id = db.categoryDao().insertCategory(
                Category(name = "其他", type = type, sortOrder = Int.MAX_VALUE)
            )
            other = Category(id = id, name = "其他", type = type, sortOrder = Int.MAX_VALUE)
        }
        db.billDao().updateBillCategory(billId, other.name, other.id)
        db.billRuleProvenanceDao().deleteByBillId(billId)
    }

    /**
     * 统一编辑入口：分类变了走分类修正；只改备注走关键词修正。
     */
    fun onUserEditedBill(
        ctx: Context,
        billId: Long,
        oldCategory: String?,
        newCategory: String?,
        oldRemark: String?,
        newRemark: String?,
        onFinished: () -> Unit = {},
    ) {
        when {
            oldCategory?.trim().orEmpty() != newCategory?.trim().orEmpty() -> {
                onUserCorrectCategory(
                    ctx = ctx,
                    billId = billId,
                    oldCategory = oldCategory,
                    newCategory = newCategory,
                    note = newRemark,
                    merchant = null,
                    onFinished = onFinished,
                )
            }
            oldRemark?.trim().orEmpty() != newRemark?.trim().orEmpty() -> {
                onUserCorrectRemark(
                    ctx = ctx,
                    billId = billId,
                    category = newCategory,
                    oldRemark = oldRemark,
                    newRemark = newRemark,
                    onFinished = onFinished,
                )
            }
            else -> onFinished()
        }
    }

    /**
     * 只改备注：不强制改分类，只问要不要把规则关键词改成新备注里的词。
     */
    private fun onUserCorrectRemark(
        ctx: Context,
        billId: Long,
        category: String?,
        oldRemark: String?,
        newRemark: String?,
        onFinished: () -> Unit,
    ) {
        val oldNote = oldRemark?.trim().orEmpty()
        val newNote = newRemark?.trim().orEmpty()
        if (oldNote == newNote || newNote.isEmpty()) {
            onFinished()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(ctx)
            val provenance = db.billRuleProvenanceDao().getByBillId(billId)
            if (provenance == null ||
                (provenance.source != BillRuleProvenance.Source.RULE &&
                    provenance.source != BillRuleProvenance.Source.AI)
            ) {
                withContext(Dispatchers.Main) { onFinished() }
                return@launch
            }
            val oldKeyword = provenance.keyword?.trim()?.takeIf { it.isNotEmpty() }
                ?: RuleKeywordPolicy.directKeywordFromNote(oldNote)
                ?: oldNote.take(8)
            val newKeyword = RuleKeywordPolicy.directKeywordFromNote(newNote)
                ?: newNote.take(8)
            if (newKeyword.isBlank() || oldKeyword == newKeyword) {
                withContext(Dispatchers.Main) { onFinished() }
                return@launch
            }
            val existingRules = db.accountingRuleDao().findAllByKeyword(oldKeyword)
            val existing = provenance.ruleId?.let { id -> existingRules.firstOrNull { it.id == id } }
                ?: existingRules.firstOrNull()
            if (existing == null) {
                withContext(Dispatchers.Main) { onFinished() }
                return@launch
            }

            withContext(Dispatchers.Main) {
                RuleDialogHelper.showCorrectionActionSheet(
                    ctx = ctx,
                    message = "备注从「${oldNote.take(20)}」改成「${newNote.take(20)}」，要把规则关键词更新为「$newKeyword」吗？",
                    hasExistingRule = true,
                    isOverlay = ctx !is Activity,
                    onUpdate = {
                        RuleDialogHelper.showRuleDialog(
                            ctx = ctx,
                            rule = existing,
                            referenceText = newNote,
                            defaultKeyword = newKeyword,
                            defaultCat = category,
                            isOverlay = ctx !is Activity,
                            categoryOnlyLearnMode = true,
                            onSave = { customizedRule ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    val now = System.currentTimeMillis()
                                    db.accountingRuleDao().upsert(
                                        customizedRule.copy(
                                            source = AccountingRule.Source.USER,
                                            enabled = true,
                                            updatedAt = now,
                                        )
                                    )
                                    db.billRuleProvenanceDao().upsert(
                                        provenance.copy(
                                            source = BillRuleProvenance.Source.MANUAL,
                                            ruleId = customizedRule.id.takeIf { it > 0 }
                                                ?: existing.id,
                                            keyword = customizedRule.keyword,
                                        )
                                    )
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "规则关键词已更新", Toast.LENGTH_SHORT).show()
                                        onFinished()
                                    }
                                }
                            },
                            onCancel = onFinished,
                        )
                    },
                    onDeleteRule = {
                        CoroutineScope(Dispatchers.IO).launch {
                            deleteOrDisableRule(db, existing)
                            db.billRuleProvenanceDao().upsert(
                                provenance.copy(
                                    source = BillRuleProvenance.Source.MANUAL,
                                    ruleId = null,
                                    keyword = null,
                                )
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "已删除/停用旧规则，这笔账单不变", Toast.LENGTH_SHORT).show()
                                onFinished()
                            }
                        }
                    },
                    onOnlyThisTime = onFinished,
                    onUndo = {
                        CoroutineScope(Dispatchers.IO).launch {
                            undoAutoCategory(db, billId)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "已撤销这次自动分类，落回「其他」", Toast.LENGTH_SHORT).show()
                                onFinished()
                            }
                        }
                    },
                    onCancel = onFinished,
                )
            }
        }
    }

    fun maybePrompt(
        ctx: Context,
        context: EditContext,
        onFinished: () -> Unit = {},
    ) {
        onUserEditedBill(
            ctx = ctx,
            billId = context.newBill.id,
            oldCategory = context.oldBill.categoryName,
            newCategory = context.newBill.categoryName,
            oldRemark = context.oldBill.remark,
            newRemark = context.newBill.remark,
            onFinished = onFinished,
        )
    }
}
