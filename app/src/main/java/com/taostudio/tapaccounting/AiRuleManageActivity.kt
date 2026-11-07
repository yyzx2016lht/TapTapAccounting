package com.taostudio.tapaccounting

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.taostudio.tapaccounting.capture.ui.RuleMappingViews
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.AccountingRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记账习惯规则页。
 *
 * 直接复用「分类规则」那套 UI（RuleMappingViews.buildCategoryRuleTab），
 * 数据源统一为 accounting_rules，避免两套规则页视觉/交互不一致。
 */
class AiRuleManageActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var root: FrameLayout
    private lateinit var ruleViews: RuleMappingViews

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.getDatabase(this)
        root = FrameLayout(this)
        setContentView(root)
        ruleViews = RuleMappingViews(this, lifecycleScope) { loadRules() }
        loadRules()
    }

    private fun loadRules() {
        lifecycleScope.launch {
            val rules: List<AccountingRule> = withContext(Dispatchers.IO) {
                db.accountingRuleDao().listAll()
            }
            render(rules)
        }
    }

    private fun render(rules: List<AccountingRule>) {
        root.removeAllViews()
        root.addView(ruleViews.buildCategoryRuleTab(rules, accountingContext = null))
    }
}
