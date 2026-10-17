package com.taostudio.tapaccounting

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.AiRule
import com.taostudio.tapaccounting.ui.common.MultiSelectMotion
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import kotlin.coroutines.resume

class AiRuleManageActivity : AppCompatActivity() {

    private lateinit var rvRules: RecyclerView
    private lateinit var btnAddRule: MaterialButton
    private lateinit var etRuleSearch: android.widget.EditText
    private lateinit var layoutRuleMultiActions: View
    private lateinit var tvRuleSelectedCount: TextView
    private lateinit var btnRuleSelectAll: TextView
    private lateinit var btnRuleCancelMulti: TextView
    private lateinit var btnRuleDeleteMulti: TextView
    private lateinit var adapter: RuleAdapter
    private lateinit var db: AppDatabase

    private var allRules: List<AiRule> = emptyList()
    private var keyword: String = ""
    private var isMultiSelectMode = false
    private val selectedRuleIds = mutableSetOf<Int>()
    private enum class RuleSaveOutcome { SAVED, OVERWRITTEN, CANCELED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_rule_manage)

        db = AppDatabase.getDatabase(this)
        rvRules = findViewById(R.id.rv_rules)
        btnAddRule = findViewById(R.id.btn_add_rule)
        etRuleSearch = findViewById(R.id.et_rule_search)
        layoutRuleMultiActions = findViewById(R.id.layout_rule_multi_actions)
        tvRuleSelectedCount = findViewById(R.id.tv_rule_selected_count)
        btnRuleSelectAll = findViewById(R.id.btn_rule_select_all)
        btnRuleCancelMulti = findViewById(R.id.btn_rule_cancel_multi)
        btnRuleDeleteMulti = findViewById(R.id.btn_rule_delete_multi)

        adapter = RuleAdapter(
            onClick = { rule ->
                if (isMultiSelectMode) {
                    toggleRuleSelection(rule)
                } else {
                    showEditDeleteDialog(rule)
                }
            },
            onLongClick = { rule ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode(rule)
                }
            },
            onToggleEnabled = { rule, enabled ->
                lifecycleScope.launch {
                    db.aiRuleDao().updateRule(rule.copy(isEnabled = enabled))
                }
            },
            isMultiSelectMode = { isMultiSelectMode },
            isSelected = { rule -> selectedRuleIds.contains(rule.id) }
        )

        rvRules.layoutManager = LinearLayoutManager(this)
        rvRules.adapter = adapter

        lifecycleScope.launch {
            db.aiRuleDao().getAllRules().collectLatest { rules ->
                allRules = rules
                if (isMultiSelectMode) {
                    selectedRuleIds.retainAll(rules.map { it.id }.toSet())
                    if (selectedRuleIds.isEmpty()) {
                        exitMultiSelectMode()
                    } else {
                        updateMultiSelectUi()
                    }
                }
                submitFilteredRules()
            }
        }

        etRuleSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString().orEmpty().trim()
                submitFilteredRules()
            }
        })

        btnAddRule.setOnClickListener { showEditDeleteDialog(null) }
        btnRuleCancelMulti.setOnClickListener { exitMultiSelectMode() }
        btnRuleSelectAll.setOnClickListener { toggleSelectAllVisibleRules() }
        btnRuleDeleteMulti.setOnClickListener { deleteSelectedRules() }
    }

    private fun submitFilteredRules() {
        val filtered = if (keyword.isBlank()) {
            allRules
        } else {
            val key = keyword.lowercase()
            allRules.filter { rule -> buildSearchSource(rule).contains(key) }
        }
        adapter.submitList(filtered)
        updateMultiSelectUi()
    }

    private fun buildSearchSource(rule: AiRule): String {
        return listOf(
            rule.keyword,
            rule.targetCategory.orEmpty(),
            rule.targetAccount1.orEmpty(),
            rule.targetAccount2.orEmpty(),
            when (rule.targetType) {
                0 -> getString(R.string.type_expense)
                1 -> getString(R.string.type_income)
                2 -> getString(R.string.type_transfer)
                3 -> getString(R.string.type_repayment)
                else -> ""
            }
        ).joinToString(" ").lowercase()
    }

    private fun enterMultiSelectMode(initialRule: AiRule) {
        val offset = captureRuleListOffset()
        isMultiSelectMode = true
        selectedRuleIds.clear()
        selectedRuleIds.add(initialRule.id)
        adapter.notifyDataSetChanged()
        updateMultiSelectUi()
        restoreRuleListOffset(offset, extraOffset = -multiActionOffsetPx())
    }

    private fun exitMultiSelectMode() {
        val offset = captureRuleListOffset()
        isMultiSelectMode = false
        selectedRuleIds.clear()
        adapter.notifyDataSetChanged()
        restoreRuleListOffset(offset, extraOffset = multiActionOffsetPx())
        updateMultiSelectUi()
    }

    private fun toggleRuleSelection(rule: AiRule) {
        if (!selectedRuleIds.add(rule.id)) {
            selectedRuleIds.remove(rule.id)
        }
        if (selectedRuleIds.isEmpty()) {
            exitMultiSelectMode()
        } else {
            adapter.notifyDataSetChanged()
            updateMultiSelectUi()
        }
    }

    private fun toggleSelectAllVisibleRules() {
        val visibleIds = adapter.currentItems.map { it.id }.toSet()
        if (visibleIds.isEmpty()) return
        if (selectedRuleIds.containsAll(visibleIds) && selectedRuleIds.size == visibleIds.size) {
            selectedRuleIds.clear()
        } else {
            selectedRuleIds.clear()
            selectedRuleIds.addAll(visibleIds)
        }
        if (selectedRuleIds.isEmpty()) {
            exitMultiSelectMode()
        } else {
            adapter.notifyDataSetChanged()
            updateMultiSelectUi()
        }
    }

    private fun updateMultiSelectUi() {
        if (isMultiSelectMode) MultiSelectMotion.animateIn(layoutRuleMultiActions)
        else MultiSelectMotion.animateOut(layoutRuleMultiActions)
        tvRuleSelectedCount.text = getString(R.string.selected_count, selectedRuleIds.size)

        val visibleIds = adapter.currentItems.map { it.id }.toSet()
        btnRuleSelectAll.text =
            if (visibleIds.isNotEmpty() && selectedRuleIds.containsAll(visibleIds) && selectedRuleIds.size == visibleIds.size) {
                getString(R.string.cancel_select_all)
            } else {
                getString(R.string.select_all_rules)
            }
    }

    private fun captureRuleListOffset(): Pair<Int, Int>? {
        val layoutManager = rvRules.layoutManager as? LinearLayoutManager ?: return null
        val firstPosition = layoutManager.findFirstVisibleItemPosition()
        if (firstPosition == RecyclerView.NO_POSITION) return null
        val firstView = layoutManager.findViewByPosition(firstPosition) ?: return null
        return firstPosition to firstView.top
    }

    private fun restoreRuleListOffset(offset: Pair<Int, Int>?, extraOffset: Int) {
        val layoutManager = rvRules.layoutManager as? LinearLayoutManager ?: return
        val (position, top) = offset ?: return
        rvRules.post {
            layoutManager.scrollToPositionWithOffset(position, top + extraOffset)
        }
    }

    private fun multiActionOffsetPx(): Int {
        val actionHeight = if (layoutRuleMultiActions.height > 0) {
            layoutRuleMultiActions.height
        } else {
            layoutRuleMultiActions.measuredHeight
        }
        val extra = (12f * resources.displayMetrics.density).toInt()
        return actionHeight + extra
    }

    private fun deleteSelectedRules() {
        if (selectedRuleIds.isEmpty()) return
        val toDelete = allRules.filter { selectedRuleIds.contains(it.id) }
        if (toDelete.isEmpty()) {
            exitMultiSelectMode()
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.delete_rule_title)
            .setMessage(getString(R.string.delete_rule_confirm, toDelete.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    toDelete.forEach { db.aiRuleDao().deleteRule(it) }
                    exitMultiSelectMode()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun showEditDeleteDialog(rule: AiRule?) {
        com.taostudio.tapaccounting.logic.RuleDialogHelper.showDialog(
            ctx = this,
            rule = rule,
            referenceText = null,
            onSave = { newRule ->
                lifecycleScope.launch {
                    val outcome = saveRuleWithKeywordConflictPrompt(
                        newRule = newRule,
                        editingRuleId = rule?.id
                    )
                    when (outcome) {
                        RuleSaveOutcome.SAVED -> Utils.toast(this@AiRuleManageActivity, getString(R.string.rule_saved))
                        RuleSaveOutcome.OVERWRITTEN -> Utils.toast(this@AiRuleManageActivity, getString(R.string.rule_overwritten))
                        RuleSaveOutcome.CANCELED -> Utils.toast(this@AiRuleManageActivity, getString(R.string.rule_save_canceled))
                    }
                }
            },
            onDelete = {
                lifecycleScope.launch {
                    db.aiRuleDao().deleteRule(it)
                }
            }
        )
    }

    private suspend fun saveRuleWithKeywordConflictPrompt(
        newRule: AiRule,
        editingRuleId: Int?
    ): RuleSaveOutcome = withContext(Dispatchers.IO) {
        val dao = db.aiRuleDao()
        val keyword = newRule.keyword.trim()
        val currentEditId = editingRuleId ?: newRule.id.takeIf { it > 0 }
        val conflicts = dao.getRulesByKeyword(keyword)
            .filter { existing -> currentEditId == null || existing.id != currentEditId }

        if (conflicts.isEmpty()) {
            val toSave = newRule.copy(keyword = keyword)
            if (toSave.id > 0) dao.updateRule(toSave) else dao.insertRule(toSave)
            return@withContext RuleSaveOutcome.SAVED
        }

        val shouldOverwrite = withContext(Dispatchers.Main) {
            promptKeywordOverwrite(
                keyword = keyword,
                existingCount = conflicts.size
            )
        }
        if (!shouldOverwrite) {
            return@withContext RuleSaveOutcome.CANCELED
        }

        val target = conflicts.first()
        dao.insertRule(newRule.copy(id = target.id, keyword = keyword))
        currentEditId?.takeIf { it != target.id }?.let { dao.deleteRuleById(it) }
        conflicts.drop(1).forEach { duplicate ->
            if (duplicate.id != target.id) dao.deleteRuleById(duplicate.id)
        }
        RuleSaveOutcome.OVERWRITTEN
    }

    private suspend fun promptKeywordOverwrite(
        keyword: String,
        existingCount: Int
    ): Boolean = suspendCancellableCoroutine { cont ->
        if (isFinishing || isDestroyed) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.duplicate_keyword_title)
            .setMessage(getString(R.string.duplicate_rule_dialog_message, keyword, existingCount))
            .setPositiveButton(R.string.continue_and_overwrite) { d, _ ->
                d.dismiss()
                if (cont.isActive) cont.resume(true)
            }
            .setNegativeButton(R.string.cancel_save) { d, _ ->
                d.dismiss()
                if (cont.isActive) cont.resume(false)
            }
            .setOnCancelListener {
                if (cont.isActive) cont.resume(false)
            }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    override fun onBackPressed() {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
            return
        }
        super.onBackPressed()
    }
    inner class RuleAdapter(
        private val onClick: (AiRule) -> Unit,
        private val onLongClick: (AiRule) -> Unit,
        private val onToggleEnabled: (AiRule, Boolean) -> Unit,
        private val isMultiSelectMode: () -> Boolean,
        private val isSelected: (AiRule) -> Boolean
    ) : RecyclerView.Adapter<RuleAdapter.ViewHolder>() {

        private var items = emptyList<AiRule>()
        val currentItems: List<AiRule> get() = items

        fun submitList(list: List<AiRule>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ai_rule, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val rule = items[position]

            holder.tvKeyword.text = rule.keyword
            holder.tvKeyword.setTextColor(Color.parseColor(if (rule.isEnabled) "#24344A" else "#8A98AD"))
            holder.tvStatus.visibility = if (rule.isEnabled) View.GONE else View.VISIBLE

            val ctx = holder.itemView.context
            val parts = mutableListOf<String>()
            rule.targetType?.let {
                parts += when (it) {
                    0 -> ctx.getString(R.string.type_expense)
                    1 -> ctx.getString(R.string.type_income)
                    2 -> ctx.getString(R.string.type_transfer)
                    3 -> ctx.getString(R.string.type_repayment)
                    else -> ctx.getString(R.string.type_unknown)
                }
            }
            listOf(rule.targetCategory, rule.targetAccount1, rule.targetAccount2)
                .filter { !it.isNullOrBlank() }
                .forEach { parts += it!! }
            holder.tvActions.text = if (parts.isEmpty()) {
                ctx.getString(R.string.no_auto_fill_action)
            } else {
                parts.joinToString(" · ")
            }
            holder.cbSelect.visibility = if (isMultiSelectMode()) View.VISIBLE else View.GONE
            holder.cbSelect.isChecked = isSelected(rule)
            val restingAlpha = if (rule.isEnabled) 1f else 0.72f
            holder.itemView.alpha = if (isMultiSelectMode() && isSelected(rule)) restingAlpha * 0.82f else restingAlpha

            holder.switchEnabled.setOnCheckedChangeListener(null)
            holder.switchEnabled.isChecked = rule.isEnabled
            holder.switchEnabled.isEnabled = !isMultiSelectMode()
            holder.switchEnabled.alpha = if (isMultiSelectMode()) 0.45f else 1f
            holder.switchEnabled.setOnCheckedChangeListener { _, checked ->
                if (!isMultiSelectMode()) onToggleEnabled(rule, checked)
            }

            holder.itemView.setOnClickListener { onClick(rule) }
            holder.itemView.setOnLongClickListener {
                onLongClick(rule)
                true
            }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvKeyword: TextView = view.findViewById(R.id.tv_keyword)
            val tvStatus: TextView = view.findViewById(R.id.tv_status)
            val tvActions: TextView = view.findViewById(R.id.tv_actions)
            val cbSelect: CheckBox = view.findViewById(R.id.cb_rule_select)
            val switchEnabled: Switch = view.findViewById(R.id.switch_rule_enabled)
        }
    }
}

