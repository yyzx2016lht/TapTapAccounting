package com.taostudio.tapaccounting.ui.recurring

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.RecurringFrequency
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.logic.RecurringBillingService
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 周期账单管理页。
 * Tab：我的 / 待确认 / 已忽略
 */
class RecurringBillsActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var adapter: RecurringPatternAdapter
    private lateinit var rvBills: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvPendingNotice: TextView
    private lateinit var layoutBulkActions: View
    private lateinit var tvBulkSelected: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var switchAutoDetect: Switch
    private lateinit var tvAutoDetectTolerance: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recurring_bills)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        db = AppDatabase.getDatabase(this)
        rvBills = findViewById(R.id.rv_recurring_bills)
        rvBills.layoutManager = LinearLayoutManager(this)
        tvEmpty = findViewById(R.id.tv_recurring_empty)
        tvSummary = findViewById(R.id.tv_recurring_summary)
        tvPendingNotice = findViewById(R.id.tv_pending_notice)
        layoutBulkActions = findViewById(R.id.layout_recurring_bulk_actions)
        tvBulkSelected = findViewById(R.id.tv_recurring_bulk_selected)
        tabLayout = findViewById(R.id.tab_recurring)
        switchAutoDetect = findViewById(R.id.switch_recurring_auto_detect)
        tvAutoDetectTolerance = findViewById(R.id.tv_recurring_auto_detect_tolerance)

        adapter = RecurringPatternAdapter(
            onConfirm = { pattern -> updateStatus(pattern, RecurringStatus.CONFIRMED, getString(R.string.recurring_status_confirmed)) },
            onDismiss = { pattern -> updateStatus(pattern, RecurringStatus.DISMISSED, getString(R.string.recurring_status_dismissed)) },
            onRestorePending = { pattern -> updateStatus(pattern, RecurringStatus.SUGGESTED, getString(R.string.recurring_status_pending)) },
            onEdit = { pattern -> showPatternDialog(pattern) },
            onSelectionChanged = { count -> bindBulkSelection(count) }
        )
        rvBills.adapter = adapter
        findViewById<View>(R.id.btn_add_recurring)?.setOnClickListener { showPatternDialog(null) }
        findViewById<View>(R.id.btn_recurring_bulk_confirm)?.setOnClickListener {
            bulkUpdateStatus(RecurringStatus.CONFIRMED)
        }
        findViewById<View>(R.id.btn_recurring_bulk_dismiss)?.setOnClickListener {
            bulkUpdateStatus(RecurringStatus.DISMISSED)
        }
        findViewById<View>(R.id.btn_recurring_bulk_delete)?.setOnClickListener {
            confirmBulkDelete()
        }
        bindAutoDetectSettings()
        if (Prefs.isRecurringAutoDetectEnabled(this)) {
            scanRecurringPatterns(showToast = false)
        } else {
            maybeShowAutoDetectGuide()
        }
        tvPendingNotice.setOnClickListener {
            if (tabLayout.selectedTabPosition != 1) {
                selectStatusTab(RecurringStatus.SUGGESTED)
            }
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                adapter.clearSelection()
                when (tab?.position) {
                    0 -> loadBills(RecurringStatus.CONFIRMED)
                    1 -> loadBills(RecurringStatus.SUGGESTED)
                    2 -> loadBills(RecurringStatus.DISMISSED)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        selectStatusTab(RecurringStatus.CONFIRMED)
    }

    private fun bindBulkSelection(count: Int) {
        val wasVisible = layoutBulkActions.visibility == View.VISIBLE
        val shouldBeVisible = count > 0
        val offset = if (wasVisible != shouldBeVisible) captureRecurringListOffset() else null
        val extraOffset = when {
            !wasVisible && shouldBeVisible -> -bulkActionOffsetPx()
            wasVisible && !shouldBeVisible -> bulkActionOffsetPx()
            else -> 0
        }
        layoutBulkActions.visibility = if (shouldBeVisible) View.VISIBLE else View.GONE
        if (count > 0) {
            tvBulkSelected.text = getString(R.string.recurring_bulk_selected_fmt, count)
        }
        if (extraOffset != 0) {
            restoreRecurringListOffset(offset, extraOffset)
        }
    }

    private fun captureRecurringListOffset(): Pair<Int, Int>? {
        val layoutManager = rvBills.layoutManager as? LinearLayoutManager ?: return null
        val firstPosition = layoutManager.findFirstVisibleItemPosition()
        if (firstPosition == RecyclerView.NO_POSITION) return null
        val firstView = layoutManager.findViewByPosition(firstPosition) ?: return null
        return firstPosition to firstView.top
    }

    private fun restoreRecurringListOffset(offset: Pair<Int, Int>?, extraOffset: Int) {
        val layoutManager = rvBills.layoutManager as? LinearLayoutManager ?: return
        val (position, top) = offset ?: return
        rvBills.post {
            layoutManager.scrollToPositionWithOffset(position, top + extraOffset)
        }
    }

    private fun bulkActionOffsetPx(): Int {
        val actionHeight = if (layoutBulkActions.height > 0) {
            layoutBulkActions.height
        } else {
            layoutBulkActions.measuredHeight
        }
        val marginBottom = (layoutBulkActions.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        return actionHeight + marginBottom
    }

    private fun bindAutoDetectSettings() {
        switchAutoDetect.isChecked = Prefs.isRecurringAutoDetectEnabled(this)
        updateAutoDetectToleranceText()
        switchAutoDetect.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setRecurringAutoDetectEnabled(this, isChecked)
            if (isChecked) scanRecurringPatterns(showToast = true)
        }
        tvAutoDetectTolerance.setOnClickListener { showAutoDetectToleranceDialog() }
    }

    private fun maybeShowAutoDetectGuide() {
        if (Prefs.hasSeenRecurringAutoDetectGuide(this)) return
        rvBills.post {
            if (isFinishing || isDestroyed || Prefs.isRecurringAutoDetectEnabled(this)) return@post
            val view = layoutInflater.inflate(R.layout.dialog_recurring_auto_detect_guide, null)
            val btnEnable = view.findViewById<View>(R.id.btn_recurring_auto_detect_enable)
            val btnLater = view.findViewById<View>(R.id.btn_recurring_auto_detect_later)
            val dialog = BottomSheetDialog(this)
            var handled = false
            dialog.setContentView(view)
            dialog.setOnDismissListener {
                if (!handled) {
                    Prefs.setRecurringAutoDetectGuideSeen(this)
                }
            }
            btnEnable.setOnClickListener {
                handled = true
                Prefs.setRecurringAutoDetectGuideSeen(this)
                switchAutoDetect.isChecked = true
                dialog.dismiss()
            }
            btnLater.setOnClickListener {
                handled = true
                Prefs.setRecurringAutoDetectGuideSeen(this)
                dialog.dismiss()
            }
            dialog.show()
        }
    }

    private fun updateAutoDetectToleranceText() {
        tvAutoDetectTolerance.text = getString(
            R.string.recurring_auto_detect_tolerance_fmt,
            Prefs.getRecurringDetectAmountTolerance(this)
        )
    }

    private fun showAutoDetectToleranceDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_recurring_tolerance, null)
        val input = view.findViewById<EditText>(R.id.et_recurring_tolerance_value)
        input.setText(String.format(Locale.getDefault(), "%.0f", Prefs.getRecurringDetectAmountTolerance(this)))
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        view.findViewById<View>(R.id.btn_recurring_tolerance_save).setOnClickListener {
            val value = input.text.toString().toDoubleOrNull()
            if (value == null || value < 0) {
                Toast.makeText(this, R.string.recurring_input_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.setRecurringDetectAmountTolerance(this, value)
            updateAutoDetectToleranceText()
            dialog.dismiss()
            if (Prefs.isRecurringAutoDetectEnabled(this)) scanRecurringPatterns(showToast = true)
        }
        view.findViewById<View>(R.id.btn_recurring_tolerance_cancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            input.requestFocus()
        }
        dialog.show()
    }

    private fun scanRecurringPatterns(showToast: Boolean) {
        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                RecurringBillingService(db).scanRecentBills(
                    amountTolerance = Prefs.getRecurringDetectAmountTolerance(this@RecurringBillsActivity)
                )
            }
            if (showToast) {
                Toast.makeText(
                    this@RecurringBillsActivity,
                    getString(R.string.recurring_auto_detect_scanned_fmt, changed),
                    Toast.LENGTH_SHORT
                ).show()
            }
            updatePendingNotice(
                when (tabLayout.selectedTabPosition) {
                    1 -> RecurringStatus.SUGGESTED
                    2 -> RecurringStatus.DISMISSED
                    else -> RecurringStatus.CONFIRMED
                }
            )
            if (tabLayout.selectedTabPosition == 1) loadBills(RecurringStatus.SUGGESTED)
        }
    }

    private fun selectStatusTab(status: RecurringStatus) {
        val index = when (status) {
            RecurringStatus.CONFIRMED -> 0
            RecurringStatus.SUGGESTED -> 1
            RecurringStatus.DISMISSED -> 2
        }
        if (tabLayout.selectedTabPosition == index) {
            loadBills(status)
        } else {
            tabLayout.getTabAt(index)?.select()
        }
    }

    private fun showPatternDialog(existing: RecurringPattern?) {
        lifecycleScope.launch {
            val view = layoutInflater.inflate(R.layout.dialog_recurring_pattern, null)
            val etMerchant = view.findViewById<EditText>(R.id.et_recurring_merchant)
            val etAmount = view.findViewById<EditText>(R.id.et_recurring_amount)
            val etDay = view.findViewById<EditText>(R.id.et_recurring_day)
            val spBillType = view.findViewById<Spinner>(R.id.spinner_recurring_bill_type)
            val spFrequency = view.findViewById<Spinner>(R.id.spinner_recurring_frequency)
            val layoutCategoryField = view.findViewById<View>(R.id.layout_recurring_category_field)
            val layoutAccountField = view.findViewById<View>(R.id.layout_recurring_account_field)
            val tvCategory = view.findViewById<TextView>(R.id.tv_recurring_category_picker)
            val tvAccountLabel = view.findViewById<TextView>(R.id.tv_recurring_account_label)
            val tvAccount = view.findViewById<TextView>(R.id.tv_recurring_account_picker)
            val layoutTargetAccount = view.findViewById<View>(R.id.layout_recurring_target_account)
            val tvTargetAccount = view.findViewById<TextView>(R.id.tv_recurring_target_account_picker)
            val tvTitle = view.findViewById<TextView>(R.id.tv_recurring_dialog_title)
            val btnCancel = view.findViewById<TextView>(R.id.btn_recurring_cancel)
            val btnSave = view.findViewById<View>(R.id.btn_recurring_save)
            var selectedCategoryName = existing?.categoryName
            // 已有的 categoryId 要优先沿用：分类改名会让 categoryName 这份展示路径快照过期，
            // 保存时按旧名反查会失败，从而把原本有效的分类关联静默清空。
            var selectedCategoryId = existing?.categoryId
            var selectedAccountName = existing?.accountName
            var selectedTargetAccountName = existing?.toAccountName?.takeIf { it.isNotBlank() }
            val assetFeatureEnabled = Prefs.isAssetFeatureEnabled(this@RecurringBillsActivity)
            var selectedBillType = existing?.billType?.takeIf { assetFeatureEnabled || it != Bill.TYPE_TRANSFER } ?: Bill.TYPE_EXPENSE
            var selectedBillSubType = existing?.billSubType?.takeIf { selectedBillType == Bill.TYPE_TRANSFER } ?: Bill.SUBTYPE_NORMAL

            val typeItems = if (assetFeatureEnabled) listOf(
                getString(R.string.expense),
                getString(R.string.income),
                getString(R.string.transfer),
                getString(R.string.repayment)
            ) else listOf(
                getString(R.string.expense),
                getString(R.string.income)
            )
            spBillType.adapter = ArrayAdapter(this@RecurringBillsActivity, android.R.layout.simple_spinner_dropdown_item, typeItems)
            val frequencyItems = listOf(
                getString(R.string.recurring_frequency_weekly),
                getString(R.string.recurring_frequency_monthly),
                getString(R.string.recurring_frequency_yearly)
            )
            spFrequency.adapter = ArrayAdapter(this@RecurringBillsActivity, android.R.layout.simple_spinner_dropdown_item, frequencyItems)
            fun billTypeIndex(type: Int, subType: Int): Int = when {
                type == Bill.TYPE_INCOME -> 1
                type == Bill.TYPE_TRANSFER && subType == Bill.SUBTYPE_REPAYMENT -> 3
                type == Bill.TYPE_TRANSFER -> 2
                else -> 0
            }
            fun applyTypeUi() {
                val isTransferFamily = assetFeatureEnabled && selectedBillType == Bill.TYPE_TRANSFER
                if (selectedBillType == Bill.TYPE_TRANSFER) {
                    selectedCategoryName = if (selectedBillSubType == Bill.SUBTYPE_REPAYMENT) {
                        getString(R.string.repayment)
                    } else {
                        getString(R.string.transfer)
                    }
                    selectedCategoryId = null
                }
                tvCategory.text = selectedCategoryName?.takeIf { it.isNotBlank() } ?: getString(R.string.budget_not_set)
                tvCategory.isEnabled = !isTransferFamily
                tvCategory.alpha = if (isTransferFamily) 0.65f else 1f
                layoutCategoryField.layoutParams = (layoutCategoryField.layoutParams as LinearLayout.LayoutParams).apply {
                    marginEnd = if (assetFeatureEnabled) (5 * resources.displayMetrics.density).toInt() else 0
                }
                layoutAccountField.visibility = if (assetFeatureEnabled) View.VISIBLE else View.GONE
                tvAccountLabel.text = if (isTransferFamily) {
                    getString(R.string.recurring_source_account_hint)
                } else {
                    getString(R.string.recurring_account_hint)
                }
                layoutTargetAccount.visibility = if (isTransferFamily) View.VISIBLE else View.GONE
                tvAccount.text = selectedAccountName?.takeIf { it.isNotBlank() } ?: getString(R.string.budget_not_set)
                tvTargetAccount.text = selectedTargetAccountName?.takeIf { it.isNotBlank() } ?: getString(R.string.budget_not_set)
            }
            spBillType.setSelection(billTypeIndex(selectedBillType, selectedBillSubType))
            spBillType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, selectedView: View?, position: Int, id: Long) {
                    val previousType = selectedBillType
                    selectedBillType = when (position) {
                        1 -> Bill.TYPE_INCOME
                        2, 3 -> if (assetFeatureEnabled) Bill.TYPE_TRANSFER else Bill.TYPE_EXPENSE
                        else -> Bill.TYPE_EXPENSE
                    }
                    selectedBillSubType = if (assetFeatureEnabled && position == 3) Bill.SUBTYPE_REPAYMENT else Bill.SUBTYPE_NORMAL
                    if (previousType != selectedBillType) {
                        selectedCategoryName = when (position) {
                            1 -> null
                            2 -> getString(R.string.transfer)
                            3 -> getString(R.string.repayment)
                            else -> null
                        }
                        // 换了账单类型，原分类可能不再适用（收入/支出分类互不相通），交给保存时按名字重新解析
                        selectedCategoryId = null
                    }
                    applyTypeUi()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            applyTypeUi()
            tvCategory.setOnClickListener {
                if (selectedBillType == Bill.TYPE_TRANSFER) {
                    Toast.makeText(this@RecurringBillsActivity, getString(R.string.transfer_follow_bill), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                OverlayDialogs.showGridCategoryPicker(
                    this@RecurringBillsActivity,
                    selectedCategoryName.orEmpty(),
                    if (selectedBillType == Bill.TYPE_INCOME) {
                        com.taostudio.tapaccounting.Prefs.TYPE_INCOME
                    } else {
                        com.taostudio.tapaccounting.Prefs.TYPE_EXPENSE
                    }
                ) { selected ->
                    selectedCategoryName = selected.ifBlank { null }
                    // 用户重新选了分类，旧 id 作废，保存时按新名字解析
                    selectedCategoryId = null
                    applyTypeUi()
                }
            }
            tvAccount.setOnClickListener {
                OverlayDialogs.showGridAssetPicker(
                    this@RecurringBillsActivity,
                    selectedAccountName.orEmpty(),
                    getString(R.string.select_asset)
                ) { selected ->
                    selectedAccountName = selected.ifBlank { null }
                    applyTypeUi()
                }
            }
            tvTargetAccount.setOnClickListener {
                OverlayDialogs.showGridAssetPicker(
                    this@RecurringBillsActivity,
                    selectedTargetAccountName.orEmpty(),
                    getString(R.string.recurring_target_account_hint)
                ) { selected ->
                    selectedTargetAccountName = selected.ifBlank { null }
                    applyTypeUi()
                }
            }

            existing?.let { pattern ->
                etMerchant.setText(pattern.merchantKey)
                etAmount.setText(String.format(Locale.getDefault(), "%.2f", pattern.amountApprox))
                pattern.dayOfMonthHint?.let { etDay.setText(it.toString()) }
                spFrequency.setSelection(
                    when (pattern.frequency) {
                        RecurringFrequency.WEEKLY -> 0
                        RecurringFrequency.MONTHLY -> 1
                        RecurringFrequency.YEARLY -> 2
                    }
                )
            } ?: run {
                spFrequency.setSelection(1)
                etDay.setText("1")
            }

            tvTitle.text = if (existing == null) {
                getString(R.string.recurring_add_title)
            } else {
                getString(R.string.recurring_edit_title)
            }

            BottomSheetDialog(this@RecurringBillsActivity).also { dialog ->
                dialog.setContentView(view)
                btnCancel.setOnClickListener { dialog.dismiss() }
                btnSave.setOnClickListener {
                    savePatternFromDialog(
                        dialog = dialog,
                        existing = existing,
                        etMerchant = etMerchant,
                        etAmount = etAmount,
                        etDay = etDay,
                        spFrequency = spFrequency,
                        selectedCategoryName = selectedCategoryName,
                        selectedCategoryId = selectedCategoryId,
                        selectedAccountName = selectedAccountName.takeIf { assetFeatureEnabled },
                        selectedTargetAccountName = selectedTargetAccountName.takeIf { assetFeatureEnabled },
                        billType = selectedBillType,
                        billSubType = selectedBillSubType
                    )
                }
                dialog.show()
            }
        }
    }

    private fun savePatternFromDialog(
        dialog: Dialog,
        existing: RecurringPattern?,
        etMerchant: EditText,
        etAmount: EditText,
        etDay: EditText,
        spFrequency: Spinner,
        selectedCategoryName: String?,
        selectedCategoryId: Long?,
        selectedAccountName: String?,
        selectedTargetAccountName: String?,
        billType: Int,
        billSubType: Int
    ) {
        val merchant = etMerchant.text.toString().trim()
        val amount = etAmount.text.toString().toDoubleOrNull()
        val tolerance = Prefs.getRecurringDetectAmountTolerance(this)
        val day = etDay.text.toString().toIntOrNull()
        if (merchant.isBlank() || amount == null || amount <= 0) {
            Toast.makeText(this, getString(R.string.recurring_input_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        if (day != null && day !in 1..31) {
            Toast.makeText(this, getString(R.string.recurring_day_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        if (billType == Bill.TYPE_TRANSFER && (selectedAccountName.isNullOrBlank() || selectedTargetAccountName.isNullOrBlank())) {
            Toast.makeText(
                this,
                if (billSubType == Bill.SUBTYPE_REPAYMENT) getString(R.string.toast_repayment_require_both) else getString(R.string.toast_transfer_require_both),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val frequency = when (spFrequency.selectedItemPosition) {
            0 -> RecurringFrequency.WEEKLY
            2 -> RecurringFrequency.YEARLY
            else -> RecurringFrequency.MONTHLY
        }

        lifecycleScope.launch {
            val savedPattern = withContext(Dispatchers.IO) {
                val service = RecurringBillingService(db)
                val categoryType = if (billType == Bill.TYPE_INCOME) 1 else 0
                // 分类解析：能沿用已有 categoryId 就沿用。分类改名后 categoryName 这份快照会过期，
                // 按旧名反查会失败，从而把原本有效的关联静默清空——用户只是改个金额，分类就丢了。
                // 只有用户重新选了分类（id 已置空）或新建模式时，才按名字反查。
                val resolvedCategoryId = RecurringBillingService.resolvePatternCategoryId(
                    keptCategoryId = selectedCategoryId,
                    selectedCategoryName = selectedCategoryName,
                    billType = billType
                ) { name ->
                    CategoryRepository(db.categoryDao())
                        .findCategoryByDisplayName(categoryType, name)?.id
                }
                // 展示名以分类树当前路径为准，顺手把过期的旧名快照修正回来
                val resolvedCategoryName = if (billType == Bill.TYPE_TRANSFER) {
                    selectedCategoryName
                } else {
                    resolvedCategoryId?.let { id ->
                        CategoryRepository.displayNamesById(
                            db.categoryDao().getCategoriesListByType(categoryType)
                        )[id]
                    } ?: selectedCategoryName
                }
                if (existing == null) {
                    service.createManualPattern(
                        merchantText = merchant,
                        amountApprox = amount,
                        amountTolerance = tolerance,
                        frequency = frequency,
                        dayOfMonthHint = day,
                        bookName = BookAccountManager.getSelectedBook(this@RecurringBillsActivity),
                        categoryId = resolvedCategoryId,
                        categoryName = resolvedCategoryName,
                        accountName = selectedAccountName,
                        toAccountName = selectedTargetAccountName,
                        billType = billType,
                        billSubType = billSubType
                    )
                } else {
                    val normalizedKey = RecurringBillingService.normalizeMerchantKey(merchant)
                    val lastSeenAt = existing.lastSeenAt
                    val updated = existing.copy(
                        merchantKey = normalizedKey,
                        amountApprox = amount,
                        amountTolerance = tolerance.coerceAtLeast(0.0),
                        frequency = frequency,
                        dayOfMonthHint = day,
                        categoryId = resolvedCategoryId,
                        categoryName = resolvedCategoryName,
                        accountName = selectedAccountName,
                        toAccountName = selectedTargetAccountName.orEmpty(),
                        billType = billType,
                        billSubType = billSubType,
                        status = RecurringStatus.CONFIRMED,
                        nextExpectedAt = RecurringBillingService.calculateNextExpected(lastSeenAt, frequency, day),
                        updatedAt = System.currentTimeMillis()
                    )
                    db.recurringPatternDao().update(updated)
                    updated
                }
            }
            dialog.dismiss()
            Toast.makeText(this@RecurringBillsActivity, getString(R.string.recurring_saved), Toast.LENGTH_SHORT).show()
            selectStatusTab(RecurringStatus.CONFIRMED)
            RecurringDuePromptController.showIfDue(this@RecurringBillsActivity, savedPattern)
        }
    }

    private fun updateStatus(pattern: RecurringPattern, status: RecurringStatus, toast: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.recurringPatternDao().update(
                    pattern.copy(status = status, updatedAt = System.currentTimeMillis())
                )
            }
            Toast.makeText(this@RecurringBillsActivity, toast, Toast.LENGTH_SHORT).show()
            val currentTab = tabLayout.selectedTabPosition
            when (currentTab) {
                0 -> loadBills(RecurringStatus.CONFIRMED)
                1 -> loadBills(RecurringStatus.SUGGESTED)
                2 -> loadBills(RecurringStatus.DISMISSED)
            }
        }
    }

    private fun bulkUpdateStatus(status: RecurringStatus) {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                selected.forEach { pattern ->
                    db.recurringPatternDao().update(pattern.copy(status = status, updatedAt = now))
                }
            }
            Toast.makeText(
                this@RecurringBillsActivity,
                getString(R.string.recurring_bulk_done, selected.size),
                Toast.LENGTH_SHORT
            ).show()
            adapter.clearSelection()
            reloadCurrentTab()
        }
    }

    private fun confirmBulkDelete() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        val view = layoutInflater.inflate(R.layout.dialog_recurring_bulk_delete, null)
        view.findViewById<TextView>(R.id.tv_recurring_bulk_delete_count).text =
            getString(R.string.recurring_bulk_delete_count_fmt, selected.size)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        view.findViewById<View>(R.id.btn_recurring_bulk_delete_confirm).setOnClickListener {
            dialog.dismiss()
            bulkDelete(selected)
        }
        view.findViewById<View>(R.id.btn_recurring_bulk_delete_cancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun bulkDelete(selected: List<RecurringPattern>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                selected.forEach { pattern -> db.recurringPatternDao().deleteById(pattern.id) }
            }
            Toast.makeText(
                this@RecurringBillsActivity,
                getString(R.string.recurring_bulk_deleted, selected.size),
                Toast.LENGTH_SHORT
            ).show()
            adapter.clearSelection()
            reloadCurrentTab()
        }
    }

    private fun reloadCurrentTab() {
        when (tabLayout.selectedTabPosition) {
            0 -> loadBills(RecurringStatus.CONFIRMED)
            1 -> loadBills(RecurringStatus.SUGGESTED)
            2 -> loadBills(RecurringStatus.DISMISSED)
        }
    }

    private fun loadBills(status: RecurringStatus) {
        lifecycleScope.launch {
            val bills = withContext(Dispatchers.IO) {
                db.recurringPatternDao().getByStatus(status)
            }
            updatePendingNotice(status)
            if (bills.isEmpty()) {
                tvSummary.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = when (status) {
                    RecurringStatus.SUGGESTED -> getString(R.string.recurring_empty_pending)
                    RecurringStatus.CONFIRMED -> getString(R.string.recurring_empty_mine)
                    RecurringStatus.DISMISSED -> getString(R.string.recurring_empty_dismissed)
                }
                rvBills.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvBills.visibility = View.VISIBLE
                bindSummary(status, bills)
                adapter.submitList(bills)
            }
        }
    }

    private fun bindSummary(status: RecurringStatus, bills: List<RecurringPattern>) {
        val now = System.currentTimeMillis()
        val dueSoonAt = now + 3L * 24L * 3600_000L
        val dueSoonCount = bills.count { next ->
            val expectedAt = next.nextExpectedAt ?: return@count false
            expectedAt in now..dueSoonAt
        }
        val overdueCount = bills.count { (it.nextExpectedAt ?: Long.MAX_VALUE) < now }
        val label = when (status) {
            RecurringStatus.SUGGESTED -> getString(R.string.recurring_tab_pending)
            RecurringStatus.CONFIRMED -> getString(R.string.recurring_tab_mine)
            RecurringStatus.DISMISSED -> getString(R.string.recurring_tab_dismissed)
        }
        tvSummary.visibility = View.VISIBLE
        tvSummary.text = getString(
            R.string.recurring_summary_fmt,
            label,
            bills.size,
            dueSoonCount,
            overdueCount
        )
    }

    private fun updatePendingNotice(currentStatus: RecurringStatus) {
        lifecycleScope.launch {
            val pendingCount = withContext(Dispatchers.IO) {
                db.recurringPatternDao().getByStatus(RecurringStatus.SUGGESTED).size
            }
            if (pendingCount > 0) {
                tvPendingNotice.visibility = View.VISIBLE
                tvPendingNotice.text = if (currentStatus == RecurringStatus.SUGGESTED) {
                    getString(R.string.recurring_pending_current_notice_fmt, pendingCount)
                } else {
                    getString(R.string.recurring_pending_notice_fmt, pendingCount)
                }
            } else {
                tvPendingNotice.visibility = View.GONE
            }
        }
    }
}
